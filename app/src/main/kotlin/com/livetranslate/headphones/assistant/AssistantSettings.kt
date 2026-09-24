package com.livetranslate.headphones.assistant

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.livetranslate.headphones.audio.TtsVoiceStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How proactive the real-time ambient-help mode is, from least to most assistance. */
enum class AssistanceLevel(val level: Int, val label: String, val checkIntervalMs: Long) {
    MINIMAL(1, "Minimal", 90_000L),
    LOW(2, "Low", 60_000L),
    BALANCED(3, "Balanced", 30_000L),
    HIGH(4, "High", 15_000L),
    MAXIMUM(5, "Maximum", 8_000L),
    ;

    companion object {
        fun fromLevel(level: Int): AssistanceLevel =
            entries.firstOrNull { it.level == level } ?: BALANCED
    }
}

/**
 * Persisted settings for the Lingo AI assistant: the Gemini API key (stored encrypted,
 * since it's a secret), the assistance level, and whether real-time ambient help is on.
 */
class AssistantSettings(context: Context) {
    private val appContext = context.applicationContext

    // Plain prefs for non-secret toggles; encrypted prefs just for the API key.
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val securePrefs: SharedPreferences by lazy { buildEncryptedPrefs() }

    private val _apiKey = MutableStateFlow(readApiKey())
    val apiKey: StateFlow<String?> = _apiKey.asStateFlow()

    private val _assistanceLevel = MutableStateFlow(
        AssistanceLevel.fromLevel(prefs.getInt(KEY_LEVEL, AssistanceLevel.BALANCED.level)),
    )
    val assistanceLevel: StateFlow<AssistanceLevel> = _assistanceLevel.asStateFlow()

    private val _realTimeHelpEnabled = MutableStateFlow(prefs.getBoolean(KEY_REALTIME, false))
    val realTimeHelpEnabled: StateFlow<Boolean> = _realTimeHelpEnabled.asStateFlow()

    private val _spitModeEnabled = MutableStateFlow(prefs.getBoolean(KEY_SPIT, false))
    val spitModeEnabled: StateFlow<Boolean> = _spitModeEnabled.asStateFlow()

    private val _ttsVoiceStyle = MutableStateFlow(readVoiceStyle())
    val ttsVoiceStyle: StateFlow<TtsVoiceStyle> = _ttsVoiceStyle.asStateFlow()

    private val _homeWifiSsids = MutableStateFlow(readHomeWifiSsids())
    val homeWifiSsids: StateFlow<List<String>> = _homeWifiSsids.asStateFlow()

    fun setApiKey(key: String) {
        runCatching {
            securePrefs.edit().putString(KEY_API_KEY, key).apply()
        }.onFailure { Log.e(TAG, "failed to persist API key", it) }
        _apiKey.value = key.ifBlank { null }
    }

    fun setAssistanceLevel(level: AssistanceLevel) {
        prefs.edit().putInt(KEY_LEVEL, level.level).apply()
        _assistanceLevel.value = level
    }

    fun setRealTimeHelpEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REALTIME, enabled).apply()
        _realTimeHelpEnabled.value = enabled
        // Exclusive with SPIT mode.
        if (enabled && _spitModeEnabled.value) {
            prefs.edit().putBoolean(KEY_SPIT, false).apply()
            _spitModeEnabled.value = false
        }
    }

    fun setSpitModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPIT, enabled).apply()
        _spitModeEnabled.value = enabled
        // Exclusive with real-time help.
        if (enabled && _realTimeHelpEnabled.value) {
            prefs.edit().putBoolean(KEY_REALTIME, false).apply()
            _realTimeHelpEnabled.value = false
        }
    }

    fun setTtsVoiceStyle(style: TtsVoiceStyle) {
        prefs.edit().putString(KEY_VOICE, style.name).apply()
        _ttsVoiceStyle.value = style
    }

    /** Wi-Fi network names Lingo trusts for its cloud calls; anywhere else it forces
     * mobile data instead. */
    fun setHomeWifiSsids(names: List<String>) {
        val cleaned = names.map { it.trim() }.filter { it.isNotEmpty() }
        prefs.edit().putString(KEY_HOME_WIFI, cleaned.joinToString(SSID_DELIMITER)).apply()
        _homeWifiSsids.value = cleaned
    }

    fun hasApiKey(): Boolean = !_apiKey.value.isNullOrBlank()

    private fun readVoiceStyle(): TtsVoiceStyle =
        runCatching { TtsVoiceStyle.valueOf(prefs.getString(KEY_VOICE, TtsVoiceStyle.NATURAL_FEMALE.name)!!) }
            .getOrDefault(TtsVoiceStyle.NATURAL_FEMALE)

    private fun readHomeWifiSsids(): List<String> {
        val stored = prefs.getString(KEY_HOME_WIFI, null) ?: return DEFAULT_HOME_WIFI_SSIDS
        val parsed = stored.split(SSID_DELIMITER).map { it.trim() }.filter { it.isNotEmpty() }
        return parsed.ifEmpty { DEFAULT_HOME_WIFI_SSIDS }
    }

    private fun readApiKey(): String? =
        runCatching { securePrefs.getString(KEY_API_KEY, null) }
            .onFailure { Log.e(TAG, "failed to read API key", it) }
            .getOrNull()
            ?.ifBlank { null }

    private fun buildEncryptedPrefs(): SharedPreferences =
        runCatching {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                SECURE_PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            Log.e(TAG, "EncryptedSharedPreferences unavailable, falling back to plain prefs", it)
            appContext.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE)
        }

    companion object {
        private const val TAG = "AssistantSettings"
        private const val PREFS = "assistant_settings"
        private const val SECURE_PREFS = "assistant_secure_settings"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_LEVEL = "assistance_level"
        private const val KEY_REALTIME = "realtime_help_enabled"
        private const val KEY_SPIT = "spit_mode_enabled"
        private const val KEY_VOICE = "tts_voice_style"
        private const val KEY_HOME_WIFI = "home_wifi_ssids"
        private const val SSID_DELIMITER = "|"
        private val DEFAULT_HOME_WIFI_SSIDS = listOf("Waupoos24", "Waupoos24-5G")
    }
}

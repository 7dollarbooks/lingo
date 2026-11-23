package com.livetranslate.headphones

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Small persisted store for app-wide UI preferences. */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(readThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
    }

    private fun readThemeMode(): ThemeMode =
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name)!!) }
            .getOrDefault(ThemeMode.SYSTEM)

    fun valhallaBaseUrl(): String {
        val stored = prefs.getString(KEY_VALHALLA, null)
        val resolved = resolveValhallaBaseUrl(stored)
        if (stored != null && stored.trim().trimEnd('/') != resolved) {
            prefs.edit().putString(KEY_VALHALLA, resolved).apply()
        }
        return resolved
    }

    fun setValhallaBaseUrl(url: String) {
        val clean = resolveValhallaBaseUrl(url)
        prefs.edit().putString(KEY_VALHALLA, clean).apply()
        _valhallaBaseUrl.value = clean
    }

    private val _valhallaBaseUrl = MutableStateFlow(valhallaBaseUrl())
    val valhallaBaseUrlFlow: StateFlow<String> = _valhallaBaseUrl.asStateFlow()

    companion object {
        private const val PREFS = "app_settings"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_VALHALLA = "valhalla_base_url"
        const val DEFAULT_VALHALLA = "https://valhalla1.openstreetmap.de"
    }
}

/**
 * The bare valhalla.openstreetmap.de host is the demo website. POST /route there
 * answers 405, so a saved copy of that address has to move to the API host.
 */
internal fun resolveValhallaBaseUrl(saved: String?): String {
    val trimmed = saved?.trim()?.trimEnd('/').orEmpty()
    if (trimmed.isEmpty()) return AppSettings.DEFAULT_VALHALLA
    val host = runCatching { java.net.URI(trimmed).host }.getOrNull()?.lowercase()
    if (host == "valhalla.openstreetmap.de") return AppSettings.DEFAULT_VALHALLA
    return trimmed
}

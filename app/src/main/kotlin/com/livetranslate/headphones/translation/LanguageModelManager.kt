package com.livetranslate.headphones.translation

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LanguagePackState(
    val code: String,
    val label: String,
    val downloaded: Boolean,
    val downloading: Boolean = false,
    val error: String? = null,
)

class LanguageModelManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val translators = mutableMapOf<String, Translator>()
    private val downloadedCache = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val remoteModelManager = RemoteModelManager.getInstance()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _packStates = MutableStateFlow<List<LanguagePackState>>(emptyList())
    val packStates: StateFlow<List<LanguagePackState>> = _packStates.asStateFlow()

    // The language the user is actively listening to. null means "Auto" (best-effort
    // multi-language switching across all downloaded languages).
    private val _activeSourceLanguage = MutableStateFlow(prefs.getString(KEY_ACTIVE, null))
    val activeSourceLanguage: StateFlow<String?> = _activeSourceLanguage.asStateFlow()

    init {
        refreshStates()
    }

    fun setActiveSourceLanguage(code: String?) {
        prefs.edit().putString(KEY_ACTIVE, code).apply()
        _activeSourceLanguage.value = code
    }

    fun activeSourceOrNull(): String? = _activeSourceLanguage.value

    fun selectedLanguageCodes(): Set<String> =
        prefs.getStringSet(KEY_SELECTED, emptySet())?.toSet() ?: emptySet()

    fun setSelectedLanguageCodes(codes: Set<String>) {
        prefs.edit().putStringSet(KEY_SELECTED, HashSet(codes)).apply()
        refreshStates()
    }

    // Rebuilds the visible list immediately (cheap, main-thread safe) and then
    // refreshes the "downloaded" flags on a background thread. The model check
    // uses a blocking Tasks.await, so it must never run on the main thread.
    fun refreshStates() {
        val selected = selectedLanguageCodes()
        val visible = SupportedLanguages.options
            .filter { selected.contains(it.code) || selected.isEmpty() }
            .ifEmpty { SupportedLanguages.options.take(5) }
        val existing = _packStates.value.associateBy { it.code }
        _packStates.value = visible.map { option ->
            existing[option.code]?.copy(label = option.label)
                ?: LanguagePackState(option.code, option.label, downloaded = false)
        }
        scope.launch {
            val snapshot = _packStates.value
            val updated = snapshot.map { state ->
                if (state.downloading) state
                else state.copy(downloaded = isModelDownloaded(state.code))
            }
            _packStates.value = updated
        }
    }

    suspend fun download(code: String, requireWifi: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "download() START code=$code requireWifi=$requireWifi")
            updatePack(code) { it.copy(downloading = true, error = null) }
            val translator = translatorFor(code)
            val conditions = DownloadConditions.Builder()
                .apply { if (requireWifi) requireWifi() }
                .build()
            runCatching {
                Log.i(TAG, "download() awaiting downloadModelIfNeeded for $code …")
                Tasks.await(translator.downloadModelIfNeeded(conditions))
                val present = isModelDownloaded(code)
                Log.i(TAG, "download() task returned for $code, isModelDownloaded=$present")
                check(present) {
                    if (requireWifi) {
                        "Not downloaded — Wi-Fi required. Connect Wi-Fi or use Mobile."
                    } else {
                        "Download did not complete — check your internet connection."
                    }
                }
            }.onSuccess {
                Log.i(TAG, "download() SUCCESS for $code")
                val selected = selectedLanguageCodes().toMutableSet().apply { add(code) }
                prefs.edit().putStringSet(KEY_SELECTED, HashSet(selected)).apply()
                updatePack(code) { it.copy(downloaded = true, downloading = false, error = null) }
            }.onFailure { err ->
                Log.e(TAG, "download() FAILED for $code", err)
                updatePack(code) {
                    it.copy(
                        downloaded = false,
                        downloading = false,
                        error = err.message ?: "Download failed",
                    )
                }
            }
        }

    suspend fun allSelectedDownloaded(): Boolean = withContext(Dispatchers.IO) {
        val selected = selectedLanguageCodes()
        if (selected.isEmpty()) return@withContext false
        selected.all { code -> isModelDownloaded(code) }
    }

    // Returns the selected languages whose translate model is actually downloaded,
    // ordered stably by the SupportedLanguages list (never depends on Set order).
    suspend fun downloadedSelectedCodes(): List<String> = withContext(Dispatchers.IO) {
        val selected = selectedLanguageCodes()
        SupportedLanguages.options
            .map { it.code }
            .filter { it in selected && isModelDownloaded(it) }
    }

    fun isModelDownloaded(code: String): Boolean {
        if (code in downloadedCache) return true
        val present = runCatching {
            Tasks.await(
                remoteModelManager.isModelDownloaded(
                    TranslateRemoteModel.Builder(code).build(),
                ),
            )
        }.onFailure { Log.e(TAG, "isModelDownloaded($code) check threw", it) }
            .getOrDefault(false)
        if (present) downloadedCache.add(code)
        return present
    }

    /** Load a downloaded model so the first spoken phrase does not pay that cost. */
    suspend fun warmup(codes: List<String>) = withContext(Dispatchers.IO) {
        codes.distinct().forEach { code ->
            if (!isModelDownloaded(code)) return@forEach
            runCatching { Tasks.await(translatorFor(code).translate(" ")) }
                .onSuccess { Log.i(TAG, "warmed $code") }
                .onFailure { Log.w(TAG, "warmup failed for $code", it) }
        }
    }

    fun translatorFor(sourceLanguage: String): Translator {
        return translators.getOrPut(sourceLanguage) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguage)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
            Translation.getClient(options)
        }
    }

    private fun updatePack(code: String, transform: (LanguagePackState) -> LanguagePackState) {
        _packStates.value = _packStates.value.map { state ->
            if (state.code == code) transform(state) else state
        }
    }

    companion object {
        private const val TAG = "LanguageModelManager"
        private const val PREFS = "language_models"
        private const val KEY_SELECTED = "selected_languages"
        private const val KEY_ACTIVE = "active_source_language"
    }
}

package com.livetranslate.headphones

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.livetranslate.headphones.assistant.AssistanceLevel
import com.livetranslate.headphones.assistant.AssistantSession
import com.livetranslate.headphones.assistant.AssistantSettings
import com.livetranslate.headphones.assistant.HomeNetworkStatus
import com.livetranslate.headphones.audio.HeadsetState
import com.livetranslate.headphones.audio.TtsVoiceStyle
import com.livetranslate.headphones.glasses.GlassesState
import com.livetranslate.headphones.glasses.vendor.GlassesWifiImport
import com.livetranslate.headphones.glasses.vendor.VideoImportResult
import com.livetranslate.headphones.glasses.ScannedGlassesDevice
import com.livetranslate.headphones.memory.MemoryNote
import com.livetranslate.headphones.navigation.TravelMode
import com.livetranslate.headphones.text.TextLine
import com.livetranslate.headphones.text.TextSource
import com.livetranslate.headphones.translation.LanguageModelManager
import com.livetranslate.headphones.translation.LanguagePackState
import com.livetranslate.headphones.vision.VisionResult
import com.livetranslate.headphones.watch.WatchVitals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = LiveTranslateApp.from(application)
    val session: TranslationSession = app.translationSession
    val languageModelManager: LanguageModelManager = app.languageModelManager
    private val appSettings: AppSettings = app.appSettings
    val assistantSettings: AssistantSettings = app.assistantSettings
    val assistantSession: AssistantSession = app.assistantSession

    private val _appScreen = MutableStateFlow(AppScreen.MAIN)
    val appScreen: StateFlow<AppScreen> = _appScreen.asStateFlow()

    private val _watchVitals = MutableStateFlow(WatchVitals())
    val watchVitals: StateFlow<WatchVitals> = _watchVitals.asStateFlow()

    fun saveWatchVitals(vitals: WatchVitals) {
        _watchVitals.value = vitals
    }

    fun setAppScreen(screen: AppScreen) {
        _appScreen.value = screen
    }

    val themeMode: StateFlow<ThemeMode> = appSettings.themeMode

    fun setThemeMode(mode: ThemeMode) = appSettings.setThemeMode(mode)

    val apiKey: StateFlow<String?> = assistantSettings.apiKey

    fun setApiKey(key: String) = assistantSettings.setApiKey(key)

    val assistanceLevel: StateFlow<AssistanceLevel> = assistantSettings.assistanceLevel

    fun setAssistanceLevel(level: AssistanceLevel) = assistantSettings.setAssistanceLevel(level)

    val realTimeHelpEnabled: StateFlow<Boolean> = assistantSettings.realTimeHelpEnabled

    fun setRealTimeHelpEnabled(enabled: Boolean) = assistantSettings.setRealTimeHelpEnabled(enabled)

    val spitModeEnabled: StateFlow<Boolean> = assistantSettings.spitModeEnabled

    fun setSpitModeEnabled(enabled: Boolean) = assistantSettings.setSpitModeEnabled(enabled)

    val ttsVoiceStyle: StateFlow<TtsVoiceStyle> = assistantSettings.ttsVoiceStyle

    fun setTtsVoiceStyle(style: TtsVoiceStyle) {
        assistantSettings.setTtsVoiceStyle(style)
        assistantSession.applyVoiceStyle()
    }

    val assistantStatus: StateFlow<AssistantStatus> = assistantSession.status.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AssistantStatus(),
    )

    val assistantExchanges: StateFlow<List<AssistantExchange>> = assistantSession.exchanges.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val homeWifiSsids: StateFlow<List<String>> = assistantSettings.homeWifiSsids

    fun setHomeWifiSsids(names: List<String>) = assistantSettings.setHomeWifiSsids(names)

    // Recomputed on every connectivity tick (transport change or SSID-only change via
    // epoch) so the UI always reflects where Lingo's next cloud call will actually go.
    val homeNetworkStatus: StateFlow<HomeNetworkStatus> = combine(
        app.networkMonitor.transport,
        app.networkMonitor.epoch,
        assistantSettings.homeWifiSsids,
    ) { _, _, homeSsids -> app.networkMonitor.homeNetworkStatus(homeSsids) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeNetworkStatus.BLOCKED)

    val visionCaptureRequested: StateFlow<Boolean> =
        assistantSession.visionCaptureRequest
            .map { it != null }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val visionInitialPrompt: StateFlow<String> =
        assistantSession.visionCaptureRequest
            .map { it?.initialPrompt.orEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun completeVisionCapture(jpegBytes: ByteArray?, prompt: String?) =
        assistantSession.completeVisionCapture(jpegBytes, prompt)

    fun clearAssistantExchanges() = assistantSession.clearExchanges()

    private val _videoImportStatus = MutableStateFlow("Record on the glasses, then import the video.")
    val videoImportStatus: StateFlow<String> = _videoImportStatus.asStateFlow()

    private val _videoImporting = MutableStateFlow(false)
    val videoImporting: StateFlow<Boolean> = _videoImporting.asStateFlow()

    private val _importedVideoUri = MutableStateFlow<Uri?>(null)
    val importedVideoUri: StateFlow<Uri?> = _importedVideoUri.asStateFlow()

    fun importGlassesVideo() {
        if (_videoImporting.value) return
        viewModelScope.launch {
            _videoImporting.value = true
            _videoImportStatus.value = "Importing the newest video from the glasses."
            val result = withContext(Dispatchers.IO) {
                GlassesWifiImport.importNewestVideo(
                    getApplication(),
                    System.currentTimeMillis() + VIDEO_IMPORT_BUDGET_MS,
                    app.glassesFacade.savedDeviceName.value,
                )
            }
            when (result) {
                is VideoImportResult.Saved -> {
                    _importedVideoUri.value = result.uri
                    _videoImportStatus.value = "Saved ${result.name}."
                }
                VideoImportResult.MissingPermission -> {
                    _videoImportStatus.value = "Allow location and nearby devices, then import again."
                }
                VideoImportResult.NoVideo -> {
                    _videoImportStatus.value = "No video came across. Record on the glasses, then try again."
                }
            }
            _videoImporting.value = false
        }
    }

    fun askAboutWhatISee() {
        viewModelScope.launch { assistantSession.askAboutWhatISee() }
    }

    val glassesState: StateFlow<GlassesState> = app.glassesFacade.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlassesState.Absent)

    val glassesSavedName: StateFlow<String?> = app.glassesFacade.savedDeviceName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val glassesSavedAddress: StateFlow<String?> = app.glassesFacade.savedDeviceAddress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val glassesIsScanning: StateFlow<Boolean> = app.glassesFacade.isScanning
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val glassesScannedDevices: StateFlow<List<ScannedGlassesDevice>> = app.glassesFacade.scannedDevices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val visionResult: StateFlow<VisionResult?> = assistantSession.visionResult
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun startGlassesScan() = app.glassesFacade.startScan()
    fun stopGlassesScan() = app.glassesFacade.stopScan()
    fun connectGlasses(address: String, name: String?) = app.glassesFacade.connectToDevice(address, name)
    fun disconnectGlasses() = app.glassesFacade.disconnect()
    fun forgetGlasses() {
        app.glassesFacade.disconnect()
        app.glassesFacade.clearSavedDevice()
    }

    fun tryAutoReconnectGlasses() {
        app.glassesFacade.tryAutoReconnect()
    }

    fun dismissVisionResult() = assistantSession.dismissVisionResult()

    fun reopenLastVisionResult() = assistantSession.reopenLastVisionResult()

    fun retryVisionGemini() {
        viewModelScope.launch { assistantSession.retryVisionGemini() }
    }

    fun retakeVision(preferPhone: Boolean = false) {
        viewModelScope.launch { assistantSession.retakeVision(preferPhone = preferPhone) }
    }

    fun acceptSignGeminiFallback() {
        viewModelScope.launch { assistantSession.acceptSignGeminiFallback() }
    }

    val status: StateFlow<SessionStatus> = session.status.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SessionStatus(),
    )

    val transcripts: StateFlow<List<TranscriptEntry>> = session.transcripts.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val packStates: StateFlow<List<LanguagePackState>> = languageModelManager.packStates.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val modelsReady: StateFlow<Boolean> = languageModelManager.packStates
        .map { packs ->
            val selected = languageModelManager.selectedLanguageCodes()
            selected.isNotEmpty() && packs.any { it.code in selected && it.downloaded }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val downloadedLanguages: StateFlow<List<LanguagePackState>> = languageModelManager.packStates
        .map { packs -> packs.filter { it.downloaded } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeSourceLanguage: StateFlow<String?> = languageModelManager.activeSourceLanguage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val bluetoothHeadset: StateFlow<HeadsetState> = session.bluetoothHeadset
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HeadsetState(false, null))

    fun setSourceLanguage(code: String?) {
        languageModelManager.setActiveSourceLanguage(code)
    }

    fun refreshLanguagePacks() {
        languageModelManager.refreshStates()
    }

    fun toggleLanguageSelection(code: String, selected: Boolean) {
        val current = languageModelManager.selectedLanguageCodes().toMutableSet()
        if (selected) current.add(code) else current.remove(code)
        languageModelManager.setSelectedLanguageCodes(current)
    }

    fun downloadLanguage(code: String, requireWifi: Boolean) {
        viewModelScope.launch {
            languageModelManager.download(code, requireWifi)
        }
    }

    fun clearTranscripts() {
        session.clearTranscripts()
    }

    val textLines: StateFlow<List<TextLine>> = app.textSink.lines.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    private val _pinnedLineId = MutableStateFlow<Long?>(null)
    val pinnedLineId: StateFlow<Long?> = _pinnedLineId.asStateFlow()

    fun pinLine(id: Long) {
        _pinnedLineId.value = if (_pinnedLineId.value == id) null else id
    }

    fun speakPrivateLine() {
        val lines = app.textSink.lines.value
        val pinned = lines.firstOrNull { it.id == _pinnedLineId.value }
        val line = pinned
            ?: lines.lastOrNull { it.source == TextSource.CAPTION }
            ?: lines.lastOrNull()
            ?: return
        viewModelScope.launch { session.speakLine(line.text) }
    }

    val memoryNotes: StateFlow<List<MemoryNote>> = app.memorySession.notes.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val memoryStatus: StateFlow<String> = app.memorySession.status.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        "Idle",
    )

    fun rememberNow() {
        viewModelScope.launch { app.memorySession.remember() }
    }

    fun startRememberTimer(minutes: Int) = app.memorySession.startTimer(minutes)

    fun cancelRememberTimer() = app.memorySession.cancelTimer()

    fun setNoteKeep(id: String, keep: Boolean) = app.memorySession.setKeep(id, keep)

    val navigationStatus: StateFlow<String> = app.navigationSession.status.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        "Idle",
    )

    val navigationActive: StateFlow<Boolean> = app.navigationSession.active.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        false,
    )

    val travelMode: StateFlow<TravelMode> = app.navigationSession.travelMode.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        TravelMode.WALK,
    )

    fun setTravelMode(mode: TravelMode) = app.navigationSession.setTravelMode(mode)

    fun startNavigation(destination: String) = app.navigationSession.start(destination)

    fun stopNavigation() = app.navigationSession.stop()

    val valhallaBaseUrl: StateFlow<String> = app.appSettings.valhallaBaseUrlFlow

    fun setValhallaBaseUrl(url: String) = app.appSettings.setValhallaBaseUrl(url)

    private companion object {
        const val VIDEO_IMPORT_BUDGET_MS = 8 * 60_000L
    }
}

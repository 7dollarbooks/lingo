package com.livetranslate.headphones

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.livetranslate.headphones.assistant.AssistanceLevel
import com.livetranslate.headphones.assistant.AssistantSession
import com.livetranslate.headphones.assistant.AssistantSettings
import com.livetranslate.headphones.assistant.HomeNetworkStatus
import com.livetranslate.headphones.audio.HeadsetState
import com.livetranslate.headphones.audio.TtsVoiceStyle
import com.livetranslate.headphones.glasses.GlassesState
import com.livetranslate.headphones.glasses.ScannedGlassesDevice
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
import kotlinx.coroutines.launch

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
}

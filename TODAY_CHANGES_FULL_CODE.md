# Lingo — full code for today's vision + glasses changes

Generated 2026-09-21 14:27. Full current contents of each touched file.

---

## `app\build.gradle.kts`

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.livetranslate.headphones"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.livetranslate.headphones"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:text-recognition:16.0.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // HeyCyan / Oudmon glasses BLE SDK (consumed AAR â€” not published by Lingo)
    implementation(files("libs/glasses_sdk_20250723_v01.aar"))

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

```

---

## `app\src\main\res\values\strings.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Lingo</string>
    <string name="notification_channel_name">Live translation</string>
    <string name="notification_channel_desc">Keeps translation and glasses connection active</string>
    <string name="notification_title">Live translation active</string>
    <string name="notification_title_glasses">Lingo + glasses</string>
    <string name="notification_text">Listening via Bluetooth headset</string>
    <string name="notification_assistant_starting">Starting Lingo assistantâ€¦</string>
    <string name="notification_assistant_listening">Listening via Shokz</string>
    <string name="notification_assistant_listening_glasses">Listening â€” glasses connected</string>
    <string name="notification_assistant_glasses_only">Glasses connected (not listening)</string>
    <string name="notification_view_last_result">View last result</string>
</resources>

```

---

## `app\src\main\kotlin\com\livetranslate\headphones\MainActivity.kt`

```kotlin
package com.livetranslate.headphones

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.livetranslate.headphones.service.TranslationForegroundService
import com.livetranslate.headphones.ui.AssistantScreen
import com.livetranslate.headphones.ui.MainScreen
import com.livetranslate.headphones.ui.ObjectIdentifyResultScreen
import com.livetranslate.headphones.ui.SettingsScreen
import com.livetranslate.headphones.ui.SignTranslateResultScreen
import com.livetranslate.headphones.ui.VisionCaptureScreen
import com.livetranslate.headphones.ui.theme.AppTheme
import com.livetranslate.headphones.vision.VisionResult

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* UI still loads; user can grant from settings. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestRuntimePermissions()
        handleVisionIntent(intent)
        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            val visionCaptureRequested by viewModel.visionCaptureRequested.collectAsState()
            val visionInitialPrompt by viewModel.visionInitialPrompt.collectAsState()
            val visionResult by viewModel.visionResult.collectAsState()
            val screen by viewModel.appScreen.collectAsState()
            AppTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when {
                        visionResult is VisionResult.ObjectIdentify -> ObjectIdentifyResultScreen(
                            result = visionResult as VisionResult.ObjectIdentify,
                            onDone = viewModel::dismissVisionResult,
                            onRetake = { viewModel.retakeVision(preferPhone = false) },
                            onRetry = viewModel::retryVisionGemini,
                        )
                        visionResult is VisionResult.SignTranslate -> SignTranslateResultScreen(
                            result = visionResult as VisionResult.SignTranslate,
                            onDone = viewModel::dismissVisionResult,
                            onRetakePhone = { viewModel.retakeVision(preferPhone = true) },
                            onRetake = { viewModel.retakeVision(preferPhone = false) },
                            onRetry = viewModel::retryVisionGemini,
                            onAcceptGemini = viewModel::acceptSignGeminiFallback,
                        )
                        visionCaptureRequested -> VisionCaptureScreen(
                            initialPrompt = visionInitialPrompt,
                            onCaptured = { jpegBytes, prompt ->
                                viewModel.completeVisionCapture(jpegBytes, prompt)
                            },
                        )
                        screen == AppScreen.SETTINGS -> SettingsScreen(
                            viewModel = viewModel,
                            onBack = { viewModel.setAppScreen(AppScreen.MAIN) },
                        )
                        screen == AppScreen.ASSISTANT -> AssistantScreen(
                            viewModel = viewModel,
                            onBack = { viewModel.setAppScreen(AppScreen.MAIN) },
                            onStart = { startAssistant() },
                            onStop = { stopAssistant() },
                        )
                        else -> MainScreen(
                            viewModel = viewModel,
                            onOpenSettings = { viewModel.setAppScreen(AppScreen.SETTINGS) },
                            onOpenAssistant = { viewModel.setAppScreen(AppScreen.ASSISTANT) },
                            onStart = { mode -> startTranslation(mode) },
                            onStop = { stopTranslation() },
                            onLoopbackStart = { startLoopback() },
                            onLoopbackStop = { stopLoopback() },
                            onTestTts = { testTts() },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleVisionIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.tryAutoReconnectGlasses()
    }

    private fun handleVisionIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_VIEW_LAST_VISION_RESULT -> viewModel.reopenLastVisionResult()
        }
    }

    private fun requestRuntimePermissions() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            add(Manifest.permission.CAMERA)
        }.filter { perm ->
            ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startTranslation(mode: AppMode) {
        val intent = Intent(this, TranslationForegroundService::class.java).apply {
            action = TranslationForegroundService.ACTION_START
            putExtra(TranslationForegroundService.EXTRA_MODE, mode.name)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopTranslation() {
        val intent = Intent(this, TranslationForegroundService::class.java).apply {
            action = TranslationForegroundService.ACTION_STOP
        }
        startService(intent)
    }

    private fun startLoopback() {
        val intent = Intent(this, TranslationForegroundService::class.java).apply {
            action = TranslationForegroundService.ACTION_LOOPBACK_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopLoopback() {
        val intent = Intent(this, TranslationForegroundService::class.java).apply {
            action = TranslationForegroundService.ACTION_LOOPBACK_STOP
        }
        startService(intent)
    }

    private fun testTts() {
        val intent = Intent(this, TranslationForegroundService::class.java).apply {
            action = TranslationForegroundService.ACTION_TEST_TTS
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun startAssistant() {
        val intent = Intent(this, TranslationForegroundService::class.java).apply {
            action = TranslationForegroundService.ACTION_START_ASSISTANT
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopAssistant() {
        val intent = Intent(this, TranslationForegroundService::class.java).apply {
            action = TranslationForegroundService.ACTION_STOP_ASSISTANT
        }
        startService(intent)
    }

    companion object {
        const val ACTION_VIEW_LAST_VISION_RESULT =
            "com.livetranslate.headphones.VIEW_LAST_VISION_RESULT"
    }
}

```

---

## `app\src\main\kotlin\com\livetranslate\headphones\MainViewModel.kt`

```kotlin
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

```

---

## `app\src\main\kotlin\com\livetranslate\headphones\LiveTranslateApp.kt`

```kotlin
package com.livetranslate.headphones

import android.app.Application
import com.livetranslate.headphones.assistant.AssistantSession
import com.livetranslate.headphones.assistant.AssistantSettings
import com.livetranslate.headphones.assistant.LlmClient
import com.livetranslate.headphones.assistant.NetworkMonitor
import com.livetranslate.headphones.glasses.GlassesFacade
import com.livetranslate.headphones.glasses.GlassesRepository
import com.livetranslate.headphones.glasses.vendor.OudmonSdkBootstrap
import com.livetranslate.headphones.translation.LanguageModelManager

class LiveTranslateApp : Application() {
    lateinit var languageModelManager: LanguageModelManager
        private set
    lateinit var translationSession: TranslationSession
        private set
    lateinit var appSettings: AppSettings
        private set
    lateinit var assistantSettings: AssistantSettings
        private set
    lateinit var networkMonitor: NetworkMonitor
        private set
    lateinit var llmClient: LlmClient
        private set
    lateinit var glassesFacade: GlassesFacade
        private set
    lateinit var assistantSession: AssistantSession
        private set

    override fun onCreate() {
        super.onCreate()
        OudmonSdkBootstrap.init(this)
        languageModelManager = LanguageModelManager(this)
        translationSession = TranslationSession(this, languageModelManager)
        appSettings = AppSettings(this)
        assistantSettings = AssistantSettings(this)
        networkMonitor = NetworkMonitor(this)
        llmClient = LlmClient(assistantSettings, networkMonitor)
        glassesFacade = GlassesRepository.getInstance(this)
        assistantSession = AssistantSession(
            this,
            llmClient,
            assistantSettings,
            glassesFacade,
            languageModelManager,
        )
    }

    companion object {
        fun from(app: Application): LiveTranslateApp = app as LiveTranslateApp
    }
}

```

---

## `app\src\main\kotlin\com\livetranslate\headphones\assistant\VisionIntent.kt`

```kotlin
package com.livetranslate.headphones.assistant

/**
 * Local heuristic for deciding whether a spoken query needs the camera (identifying an
 * object, or translating/reading physical text) rather than a plain text answer.
 *
 * Deliberately phrase-based rather than single common words like "this"/"that", to avoid
 * routing ordinary questions ("what is this weather like") into a camera capture flow.
 *
 * Sign phrases win over object phrases when both could match.
 */
sealed class VisionKind {
    data object SignTranslate : VisionKind()
    data object ObjectIdentify : VisionKind()
    data object None : VisionKind()
}

object VisionIntent {
    private val signPhrases = listOf(
        "translate this sign",
        "translate this text",
        "translate this label",
        "translate this menu",
        "translate what this says",
        "read this sign",
        "read this label",
        "read this text",
        "read this to me",
        "what does this say",
        "what does that say",
        "translate the sign",
        "read the sign",
        "what does the sign say",
    )

    private val objectPhrases = listOf(
        "what is this",
        "what's this",
        "what is that",
        "what's that",
        "what am i looking at",
        "what am i seeing",
        "identify this",
        "identify that",
        "identify the object",
        "look at this",
        "what kind of",
        "what type of",
    )

    private val keywords = listOf("camera", "picture", "photo", "snapshot")

    fun kind(query: String): VisionKind {
        val lower = query.lowercase()
        if (signPhrases.any { lower.contains(it) }) return VisionKind.SignTranslate
        if (objectPhrases.any { lower.contains(it) }) return VisionKind.ObjectIdentify
        if (keywords.any { lower.contains(it) }) return VisionKind.ObjectIdentify
        return VisionKind.None
    }

    fun needsCamera(query: String): Boolean = kind(query) !is VisionKind.None
}

```

---

## `app\src\main\kotlin\com\livetranslate\headphones\assistant\AssistantSession.kt`

```kotlin
package com.livetranslate.headphones.assistant

import android.content.Context
import android.util.Log
import com.livetranslate.headphones.AssistantExchange
import com.livetranslate.headphones.AssistantStatus
import com.livetranslate.headphones.ExchangeKind
import com.livetranslate.headphones.audio.BluetoothAudioRouter
import com.livetranslate.headphones.audio.HalfDuplexPolicy
import com.livetranslate.headphones.audio.RoutedTtsPlayer
import com.livetranslate.headphones.glasses.FallbackReason
import com.livetranslate.headphones.glasses.GlassesFacade
import com.livetranslate.headphones.glasses.GlassesState
import com.livetranslate.headphones.glasses.StillProvenance
import com.livetranslate.headphones.translation.LanguageModelManager
import com.livetranslate.headphones.vision.GlassesStillSource
import com.livetranslate.headphones.vision.PhoneCameraStillSource
import com.livetranslate.headphones.vision.SignPipelineOutcome
import com.livetranslate.headphones.vision.SignTranslatePipeline
import com.livetranslate.headphones.vision.StillFile
import com.livetranslate.headphones.vision.StillFileCache
import com.livetranslate.headphones.vision.StillSelector
import com.livetranslate.headphones.vision.VisionResult
import com.livetranslate.headphones.vision.VisionTimings
import com.livetranslate.headphones.vision.glassesFallbackReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Result of a vision capture: the JPEG plus whatever question the user typed/edited. */
data class VisionCaptureResult(
    val jpegBytes: ByteArray,
    val prompt: String,
    val provenance: StillProvenance = StillProvenance.Phone,
    val fallbackReason: FallbackReason? = null,
)

data class VisionCaptureRequest(
    val initialPrompt: String = "",
)

/**
 * Lifecycle owner for the Lingo assistant. Owns dual-output vision turns:
 * always TTS + [visionResult] state (queued until MainActivity is foreground).
 */
class AssistantSession(
    context: Context,
    private val llmClient: LlmClient,
    private val assistantSettings: AssistantSettings,
    private val glassesFacade: GlassesFacade,
    private val languageModelManager: LanguageModelManager,
    private val visionTimings: VisionTimings = VisionTimings.DEFAULT,
) {
    private val appContext = context.applicationContext
    private val audioRouter = BluetoothAudioRouter(appContext)
    private val halfDuplex = HalfDuplexPolicy(visionHoldMaxMs = visionTimings.visionHoldMaxMs)
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var glassesStatusJob: Job? = null
    private var ttsPlayer: RoutedTtsPlayer? = null
    private var engine: AssistantEngine? = null

    private val stillFileCache = StillFileCache(appContext)
    private val signPipeline = SignTranslatePipeline(languageModelManager, llmClient)

    private val _status = MutableStateFlow(AssistantStatus())
    val status: StateFlow<AssistantStatus> = _status.asStateFlow()

    private val _exchanges = MutableStateFlow<List<AssistantExchange>>(emptyList())
    val exchanges: StateFlow<List<AssistantExchange>> = _exchanges.asStateFlow()

    private val _visionCaptureRequest = MutableStateFlow<VisionCaptureRequest?>(null)
    val visionCaptureRequest: StateFlow<VisionCaptureRequest?> = _visionCaptureRequest.asStateFlow()
    private var pendingVisionCapture: CompletableDeferred<VisionCaptureResult?>? = null

    private val _visionResult = MutableStateFlow<VisionResult?>(null)
    val visionResult: StateFlow<VisionResult?> = _visionResult.asStateFlow()
    private var lastVisionResult: VisionResult? = null
    private var heldStillId: String? = null

    /** When true, next StillSelector call skips glasses (Retake with phone). */
    @Volatile private var forcePhoneCapture: Boolean = false

    private var lastVisionKind: VisionKind = VisionKind.ObjectIdentify
    private var lastVisionQuery: String = ""

    private val stillSelector = StillSelector(
        glasses = GlassesStillSource(glassesFacade),
        phone = PhoneCameraStillSource { promptHint -> requestPhoneCapture(promptHint) },
        canUseGlasses = {
            !forcePhoneCapture && glassesFacade.capability.value.canCapture
        },
        glassesBlockReason = {
            if (forcePhoneCapture) FallbackReason.UserCancelled
            else glassesFallbackReason(glassesFacade)
        },
        glassesBudgetMs = glassesFacade.timings.selectorGlassesBudgetMs,
        handoffMarginMs = glassesFacade.timings.handoffMarginMs,
        onStatus = { message -> _status.value = _status.value.copy(statusMessage = message) },
    )

    suspend fun start() {
        if (_status.value.isActive) return
        glassesFacade.tryAutoReconnect()
        if (!audioRouter.start()) {
            _status.value = _status.value.copy(
                isActive = false,
                statusMessage = "Connect Shokz via Bluetooth and enable phone audio",
            )
            return
        }

        val player = RoutedTtsPlayer(
            appContext,
            audioRouter,
            voiceStyle = assistantSettings.ttsVoiceStyle.value,
        )
        ttsPlayer = player
        player.warmup()
        val newEngine = AssistantEngine(
            context = appContext,
            llmClient = llmClient,
            ttsPlayer = player,
            assistantSettings = assistantSettings,
            halfDuplex = halfDuplex,
            visionTimings = visionTimings,
            onStatus = { message -> _status.value = _status.value.copy(statusMessage = message) },
            onExchange = { exchange -> _exchanges.value = (_exchanges.value + exchange).takeLast(50) },
            onListening = { listening -> _status.value = _status.value.copy(listening = listening) },
            runVisionTurn = { kind, query -> runVisionTurn(kind, query) },
        )
        engine = newEngine

        runCatching { newEngine.start() }.onFailure { err ->
            engine = null
            player.shutdown()
            ttsPlayer = null
            audioRouter.stop()
            _status.value = _status.value.copy(
                isActive = false,
                statusMessage = err.message ?: "Failed to start assistant",
            )
            return
        }

        glassesStatusJob?.cancel()
        glassesStatusJob = sessionScope.launch {
            glassesFacade.state.collectLatest { st ->
                val connected = st is GlassesState.Ready || st is GlassesState.Connecting ||
                    st is GlassesState.Degraded
                _status.value = _status.value.copy(glassesConnected = connected)
            }
        }

        val spitOn = assistantSettings.spitModeEnabled.value
        _status.value = _status.value.copy(
            isActive = true,
            glassesConnected = glassesFacade.state.value is GlassesState.Ready,
            statusMessage = if (spitOn) "SPIT â€” listening for questionsâ€¦" else "Listening for \"Hey Lingo\"â€¦",
        )
    }

    suspend fun stop() {
        glassesStatusJob?.cancel()
        glassesStatusJob = null
        engine?.stop()
        engine = null
        ttsPlayer?.shutdown()
        ttsPlayer = null
        audioRouter.stop()
        pendingVisionCapture?.complete(null)
        pendingVisionCapture = null
        _visionCaptureRequest.value = null
        _status.value = AssistantStatus()
    }

    fun clearExchanges() {
        _exchanges.value = emptyList()
    }

    fun applyVoiceStyle() {
        ttsPlayer?.setVoiceStyle(assistantSettings.ttsVoiceStyle.value)
    }

    /**
     * One-shot "Ask about what I see": classify from prompt; default ObjectIdentify.
     */
    suspend fun askAboutWhatISee(initialPrompt: String = "") {
        val alreadyActive = _status.value.isActive
        val player = if (alreadyActive) {
            ttsPlayer
        } else {
            if (!audioRouter.start()) {
                _status.value = _status.value.copy(statusMessage = "Connect Shokz before asking Lingo")
                return
            }
            RoutedTtsPlayer(
                appContext,
                audioRouter,
                voiceStyle = assistantSettings.ttsVoiceStyle.value,
            ).also { ttsPlayer = it }
        }
        if (player == null) {
            _status.value = _status.value.copy(statusMessage = "Assistant isn't ready yet")
            return
        }

        halfDuplex.beginVisionHold()
        runCatching {
            val prompt = initialPrompt.ifBlank { DEFAULT_VISION_PROMPT }
            val kind = when (val k = VisionIntent.kind(prompt)) {
                is VisionKind.None -> VisionKind.ObjectIdentify
                else -> k
            }
            val answer = runVisionTurn(kind, prompt)
            halfDuplex.expireVisionHoldIfNeeded()
            if (halfDuplex.maySpeakLateAnswer() || alreadyActive) {
                // TTS already spoken inside runVisionTurn when player was session TTS;
                // for one-shot idle path, ensure spoken (runVisionTurn uses ttsPlayer).
            }
            _status.value = _status.value.copy(
                statusMessage = when {
                    !alreadyActive -> "Idle"
                    assistantSettings.spitModeEnabled.value -> "SPIT â€” listening for questionsâ€¦"
                    else -> "Listening for \"Hey Lingo\"â€¦"
                },
            )
            answer
        }.onFailure { err ->
            Log.e(TAG, "askAboutWhatISee failed", err)
            _status.value = _status.value.copy(statusMessage = err.message ?: "Vision request failed")
        }
        halfDuplex.clearVisionHold()
        halfDuplex.endResponse()

        if (!alreadyActive) {
            player.shutdown()
            ttsPlayer = null
            audioRouter.stop()
        }
    }

    /**
     * Atomic vision turn: fresh capture â†’ pipeline â†’ TTS + publish [visionResult].
     * @return spoken answer string
     */
    suspend fun runVisionTurn(kind: VisionKind, query: String): String {
        lastVisionKind = kind
        lastVisionQuery = query
        halfDuplex.beginVisionHold()
        _status.value = _status.value.copy(statusMessage = "Lookingâ€¦")
        val capture = captureStill(query)
        forcePhoneCapture = false
        if (capture == null) {
            val msg = "I couldn't get a look at that â€” capture was cancelled."
            speakAndNote(msg)
            return msg
        }
        val still = stillFileCache.write(capture.jpegBytes)
        return when (kind) {
            is VisionKind.SignTranslate -> runSignPath(still, capture, query)
            is VisionKind.ObjectIdentify -> runObjectPath(still, capture, query)
            is VisionKind.None -> {
                val msg = "No vision intent."
                speakAndNote(msg)
                msg
            }
        }.also {
            halfDuplex.clearVisionHold()
        }
    }

    private suspend fun runObjectPath(
        still: StillFile,
        capture: VisionCaptureResult,
        query: String,
    ): String {
        _status.value = _status.value.copy(statusMessage = "Thinkingâ€¦")
        val outcome = runCatching {
            llmClient.askWithImage(
                prompt = query.ifBlank { SignTranslatePipeline.OBJECT_IDENTIFY_PROMPT },
                jpegBytes = capture.jpegBytes,
                systemInstruction = OBJECT_SYSTEM,
                maxOutputTokens = 120,
            )
        }
        val offline = outcome.isFailure
        val answer = if (offline) {
            SignTranslatePipeline.GEMINI_OFFLINE_MESSAGE
        } else {
            outcome.getOrThrow()
        }
        val result = VisionResult.ObjectIdentify(
            still = still,
            answer = answer,
            provenance = capture.provenance,
            fallbackReason = capture.fallbackReason,
            query = query,
            geminiOffline = offline,
        )
        publishVisionResult(result)
        speakAndNote(answer)
        recordExchange(query, answer, capture)
        return answer
    }

    private suspend fun runSignPath(
        still: StillFile,
        capture: VisionCaptureResult,
        query: String,
    ): String {
        _status.value = _status.value.copy(statusMessage = "Reading signâ€¦")
        // Emit downloading state so UI can show while pack fetches.
        val downloadingPlaceholder = VisionResult.SignTranslate(
            still = still,
            answer = "Downloading translation modelâ€¦",
            provenance = capture.provenance,
            fallbackReason = capture.fallbackReason,
            query = query,
            downloadingModel = true,
        )
        var showedDownload = false
        val outcome = signPipeline.run(
            jpegBytes = capture.jpegBytes,
            provenance = capture.provenance,
            onDownloading = {
                showedDownload = true
                publishVisionResult(downloadingPlaceholder, speak = false)
            },
        )
        val result = when (outcome) {
            is SignPipelineOutcome.OverlayReady -> VisionResult.SignTranslate(
                still = still,
                answer = outcome.answer,
                provenance = capture.provenance,
                fallbackReason = capture.fallbackReason,
                query = query,
                blocks = outcome.blocks,
                imageWidth = outcome.imageWidth,
                imageHeight = outcome.imageHeight,
            )
            is SignPipelineOutcome.GlassesZeroOcr -> VisionResult.SignTranslate(
                still = still,
                answer = outcome.message,
                provenance = capture.provenance,
                fallbackReason = capture.fallbackReason,
                query = query,
                glassesZeroOcr = true,
            )
            is SignPipelineOutcome.Downloading -> VisionResult.SignTranslate(
                still = still,
                answer = outcome.message,
                provenance = capture.provenance,
                query = query,
                downloadingModel = true,
            )
            is SignPipelineOutcome.GeminiFallback -> VisionResult.SignTranslate(
                still = still,
                answer = outcome.answer,
                provenance = capture.provenance,
                fallbackReason = capture.fallbackReason,
                query = query,
                banner = outcome.banner,
                geminiOffline = outcome.geminiOffline,
                imageWidth = outcome.imageWidth,
                imageHeight = outcome.imageHeight,
            )
        }
        if (showedDownload && result.downloadingModel) {
            // stay on download UI â€” shouldn't happen; fall through
        }
        publishVisionResult(result)
        speakAndNote(result.answer)
        recordExchange(query, result.answer, capture)
        return result.answer
    }

    /** Gemini offline retry â€” reuses cached still, does not re-capture. */
    suspend fun retryVisionGemini() {
        val current = _visionResult.value ?: lastVisionResult ?: return
        val bytes = stillFileCache.readBytes(current.still) ?: return
        _status.value = _status.value.copy(statusMessage = "Retryingâ€¦")
        when (current) {
            is VisionResult.ObjectIdentify -> {
                val outcome = runCatching {
                    llmClient.askWithImage(
                        prompt = current.query.ifBlank { SignTranslatePipeline.OBJECT_IDENTIFY_PROMPT },
                        jpegBytes = bytes,
                        systemInstruction = OBJECT_SYSTEM,
                    )
                }
                val offline = outcome.isFailure
                val answer = if (offline) SignTranslatePipeline.GEMINI_OFFLINE_MESSAGE else outcome.getOrThrow()
                val updated = current.copy(answer = answer, geminiOffline = offline)
                publishVisionResult(updated)
                speakAndNote(answer)
            }
            is VisionResult.SignTranslate -> {
                val outcome = runCatching {
                    llmClient.askWithImage(
                        prompt = SignTranslatePipeline.SIGN_GEMINI_PROMPT,
                        jpegBytes = bytes,
                        systemInstruction = SIGN_SYSTEM,
                    )
                }
                val offline = outcome.isFailure
                val answer = if (offline) SignTranslatePipeline.GEMINI_OFFLINE_MESSAGE else outcome.getOrThrow()
                val updated = current.copy(
                    answer = answer,
                    geminiOffline = offline,
                    glassesZeroOcr = false,
                    banner = current.banner ?: SignTranslatePipeline.BANNER_SCRIPT_UNSUPPORTED,
                    downloadingModel = false,
                )
                publishVisionResult(updated)
                speakAndNote(answer)
            }
        }
    }

    /** Secondary affordance after glasses zero-OCR: Sign Gemini without re-capture. */
    suspend fun acceptSignGeminiFallback() {
        val current = _visionResult.value as? VisionResult.SignTranslate ?: return
        if (!current.glassesZeroOcr) return
        val bytes = stillFileCache.readBytes(current.still) ?: return
        val outcome = runCatching {
            llmClient.askWithImage(
                prompt = SignTranslatePipeline.SIGN_GEMINI_PROMPT,
                jpegBytes = bytes,
                systemInstruction = SIGN_SYSTEM,
            )
        }
        val offline = outcome.isFailure
        val answer = if (offline) SignTranslatePipeline.GEMINI_OFFLINE_MESSAGE else outcome.getOrThrow()
        publishVisionResult(
            current.copy(
                answer = answer,
                glassesZeroOcr = false,
                geminiOffline = offline,
                banner = SignTranslatePipeline.BANNER_SCRIPT_UNSUPPORTED,
            ),
        )
        speakAndNote(answer)
    }

    /** Retake always re-captures (never reopen cached JPEG as shortcut). */
    suspend fun retakeVision(preferPhone: Boolean = false) {
        forcePhoneCapture = preferPhone
        val kind = (_visionResult.value?.kind ?: lastVisionKind).let {
            if (it is VisionKind.None) VisionKind.ObjectIdentify else it
        }
        val query = _visionResult.value?.query?.ifBlank { lastVisionQuery } ?: lastVisionQuery.ifBlank {
            DEFAULT_VISION_PROMPT
        }
        dismissVisionResult()
        runVisionTurn(kind, query)
    }

    fun dismissVisionResult() {
        val current = _visionResult.value
        if (current != null) {
            lastVisionResult = current
            heldStillId?.let { stillFileCache.release(it) }
            heldStillId = null
        }
        _visionResult.value = null
    }

    fun reopenLastVisionResult() {
        val last = lastVisionResult ?: return
        publishVisionResult(last, speak = false)
    }

    fun hasLastVisionResult(): Boolean = lastVisionResult != null || _visionResult.value != null

    private fun publishVisionResult(result: VisionResult, speak: Boolean = false) {
        heldStillId?.let { stillFileCache.release(it) }
        stillFileCache.hold(result.still.id)
        heldStillId = result.still.id
        lastVisionResult = result
        _visionResult.value = result
        if (speak) {
            sessionScope.launch { speakAndNote(result.answer) }
        }
    }

    private suspend fun speakAndNote(text: String) {
        val player = ttsPlayer
        if (player != null) {
            runCatching { player.speak(text) }
        }
        halfDuplex.noteSpoken(text)
    }

    private fun recordExchange(query: String, answer: String, capture: VisionCaptureResult) {
        _exchanges.value = (
            _exchanges.value + AssistantExchange(
                ExchangeKind.ASKED,
                query = query,
                response = answer,
                hasImage = true,
                imageProvenance = capture.provenance,
                fallbackReason = capture.fallbackReason,
            )
            ).takeLast(50)
    }

    fun completeVisionCapture(jpegBytes: ByteArray?, prompt: String?) {
        val result = if (jpegBytes != null) {
            VisionCaptureResult(
                jpegBytes = jpegBytes,
                prompt = prompt?.trim().orEmpty().ifBlank { DEFAULT_VISION_PROMPT },
                provenance = StillProvenance.Phone,
                fallbackReason = null,
            )
        } else {
            null
        }
        pendingVisionCapture?.complete(result)
        pendingVisionCapture = null
        _visionCaptureRequest.value = null
    }

    private suspend fun captureStill(initialPrompt: String): VisionCaptureResult? {
        halfDuplex.beginVisionHold()
        val still = stillSelector.capture(initialPrompt) ?: return null
        return VisionCaptureResult(
            jpegBytes = still.jpegBytes,
            prompt = initialPrompt.ifBlank { DEFAULT_VISION_PROMPT },
            provenance = still.provenance,
            fallbackReason = still.fallbackReason,
        )
    }

    private suspend fun requestPhoneCapture(initialPrompt: String): ByteArray? {
        val deferred = CompletableDeferred<VisionCaptureResult?>()
        pendingVisionCapture = deferred
        _visionCaptureRequest.value = VisionCaptureRequest(initialPrompt = initialPrompt)
        val result = deferred.await()
        return result?.jpegBytes
    }

    companion object {
        private const val TAG = "AssistantSession"
        const val DEFAULT_VISION_PROMPT =
            "In one or two short sentences, identify what this is. Be as specific as the " +
                "image allows. If you are unsure, say so. Do not invent fine print."

        private const val OBJECT_SYSTEM =
            "You identify objects in photos for a voice headset. Reply in English in one or " +
                "two short spoken sentences. Be specific when the image allows; say unsure if unsure. " +
                "Do not invent fine print."

        private const val SIGN_SYSTEM =
            "You extract and translate text from photos. Reply in English only. " +
                "Do not describe scenes or objects â€” only text content and translation."
    }
}

```

---

## `app\src\main\kotlin\com\livetranslate\headphones\assistant\AssistantEngine.kt`

```kotlin
package com.livetranslate.headphones.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.livetranslate.headphones.AssistantExchange
import com.livetranslate.headphones.ExchangeKind
import com.livetranslate.headphones.audio.HalfDuplexPolicy
import com.livetranslate.headphones.audio.RoutedTtsPlayer
import com.livetranslate.headphones.vision.VisionTimings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the assistant's mic pipeline: continuous speech recognition feeds both
 * wake-word detection (on-demand Q&A) and an ambient rolling buffer (real-time proactive
 * help).
 *
 * Uses the platform (cloud-capable) [SpeechRecognizer] rather than the on-device SODA
 * recognizer â€” the assistant already needs network for Gemini, and online English STT
 * is much more reliable for short wake phrases over Bluetooth SCO.
 */
class AssistantEngine(
    private val context: Context,
    private val llmClient: LlmClient,
    private val ttsPlayer: RoutedTtsPlayer,
    private val assistantSettings: AssistantSettings,
    private val halfDuplex: HalfDuplexPolicy,
    private val visionTimings: VisionTimings = VisionTimings.DEFAULT,
    private val onStatus: (String) -> Unit,
    private val onExchange: (AssistantExchange) -> Unit,
    private val onListening: (Boolean) -> Unit,
    /** Dual-output vision turn: TTS + queued result screen handled inside session. */
    private val runVisionTurn: suspend (VisionKind, String) -> String,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var speechRecognizer: SpeechRecognizer? = null
    private var active = false

    private val ambientBuffer = AmbientTranscriptBuffer()
    private var awaitingFollowUp = false
    private var conversationHistory = listOf<Turn>()
    private var proactiveJob: Job? = null
    private var lastProactiveSpeechAtMs = 0L
    private var lastPartial: String = ""
    private var wakeHandledForUtterance = false

    private var spitRepeatTimeoutJob: Job? = null
    private var spitStablePartial: String = ""
    private val spitCommitRunnable = Runnable {
        if (!active || halfDuplex.isBusy() || wakeHandledForUtterance) return@Runnable
        if (!assistantSettings.spitModeEnabled.value) return@Runnable
        val text = spitStablePartial
        if (text.isBlank() || !QuestionDetector.isQuestion(text)) return@Runnable
        if (!QuestionDetector.looksFinished(text)) return@Runnable
        Log.i(TAG, "SPIT stable commit: '$text'")
        wakeHandledForUtterance = true
        runCatching { speechRecognizer?.stopListening() }
        handleUtterance(text)
    }

    private val restartRunnable = Runnable { if (active) startListening() }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.i(TAG, "onReadyForSpeech")
            lastPartial = ""
            spitStablePartial = ""
            mainHandler.removeCallbacks(spitCommitRunnable)
            wakeHandledForUtterance = false
            onListening(true)
        }

        override fun onBeginningOfSpeech() {
            Log.i(TAG, "onBeginningOfSpeech")
        }

        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            Log.i(TAG, "onEndOfSpeech")
            onListening(false)
        }

        override fun onError(error: Int) {
            Log.w(TAG, "onError: ${speechErrorLabel(error)} ($error) lastPartial='$lastPartial'")
            if (!active) return
            if (!wakeHandledForUtterance && lastPartial.isNotBlank()) {
                handleUtterance(lastPartial)
            }
            lastPartial = ""
            wakeHandledForUtterance = false
            if (halfDuplex.isBusy()) return
            val delayMs = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                -> 0L
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT,
                -> 150L
                else -> 50L
            }
            if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                onStatus("Listen retry (${speechErrorLabel(error)})")
            }
            restartListening(delayMs)
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
            Log.i(TAG, "onResults: '${text ?: "<null>"}'")
            if (!active) return
            if (!text.isNullOrBlank() && !wakeHandledForUtterance) {
                handleUtterance(text)
            }
            lastPartial = ""
            wakeHandledForUtterance = false
            if (!halfDuplex.isBusy()) restartListening(0)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
            if (text.isNullOrBlank()) return
            lastPartial = text
            Log.i(TAG, "onPartial: '$text'")
            if (wakeHandledForUtterance || halfDuplex.isBusy()) return

            if (assistantSettings.spitModeEnabled.value) {
                // Commit only after the transcript stops changing â€” lets the full question
                // finish, then fires ~0.5s after the last word instead of waiting on silence.
                if (QuestionDetector.isQuestion(text)) {
                    spitStablePartial = text
                    mainHandler.removeCallbacks(spitCommitRunnable)
                    mainHandler.postDelayed(spitCommitRunnable, SPIT_STABLE_MS)
                } else {
                    mainHandler.removeCallbacks(spitCommitRunnable)
                }
                return
            }

            if (!awaitingFollowUp) {
                val match = WakeWordDetector.findWake(text)
                if (match != null && match.trailingQuery.length >= 2) {
                    Log.i(TAG, "wake on partial trailing='${match.trailingQuery}'")
                    runCatching { speechRecognizer?.stopListening() }
                    handleUtterance(text)
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    suspend fun start() = withContext(Dispatchers.Main) {
        if (active) return@withContext
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            throw IllegalStateException("Speech recognition is not available")
        }
        // Cloud-capable platform recognizer: better short-phrase English + BT SCO than SODA.
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(recognitionListener)
        }
        active = true
        awaitingFollowUp = false
        conversationHistory = emptyList()
        ambientBuffer.clear()
        lastPartial = ""
        wakeHandledForUtterance = false
        halfDuplex.clearSpitRepeat()
        halfDuplex.clearVisionHold()
        halfDuplex.endResponse()
        val isSpit = assistantSettings.spitModeEnabled.value
        onStatus(if (isSpit) "SPIT â€” listening for questionsâ€¦" else "Listening for \"Hey Lingo\"â€¦")
        muteRecognizerBeep()
        startListening()
        startProactiveLoop()
        // Warm TLS to Gemini on the home/cellular route so the first question is snappy.
        scope.launch { runCatching { llmClient.warmup() } }
    }

    suspend fun stop() = withContext(Dispatchers.Main) {
        active = false
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.removeCallbacks(spitCommitRunnable)
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        proactiveJob?.cancel()
        proactiveJob = null
        spitRepeatTimeoutJob?.cancel()
        spitRepeatTimeoutJob = null
        halfDuplex.clearSpitRepeat()
        halfDuplex.clearVisionHold()
        halfDuplex.endResponse()
        unmuteRecognizerBeep()
        onListening(false)
        onStatus("Stopped")
    }

    /**
     * The platform SpeechRecognizer plays a start/stop beep with no public API to disable
     * it. The documented workaround is muting the stream it plays through (STREAM_MUSIC on
     * current Android speech services) for as long as recognition is running, rather than
     * around each individual listen cycle â€” Lingo restarts listening constantly, so muting
     * per-cycle would be both racy and audibly choppy.
     */
    private fun muteRecognizerBeep() {
        runCatching { audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0) }
            .onFailure { Log.w(TAG, "couldn't mute recognizer beep", it) }
    }

    private fun unmuteRecognizerBeep() {
        runCatching { audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0) }
            .onFailure { Log.w(TAG, "couldn't unmute recognizer beep", it) }
    }

    private fun startListening() {
        val recognizer = speechRecognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            // Wake-word: short silence (partial early-stop handles most queries).
            // SPIT: modest silence as a fallback; stable-partial commit is the fast path.
            val spit = assistantSettings.spitModeEnabled.value
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                if (spit) 1_100L else 700L,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                if (spit) 850L else 550L,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                if (spit) 500L else 300L,
            )
        }
        Log.i(TAG, "startListening")
        runCatching { recognizer.startListening(intent) }
            .onFailure { Log.e(TAG, "startListening failed", it) }
    }

    private fun restartListening(delayMs: Long) {
        if (!active) return
        mainHandler.removeCallbacks(restartRunnable)
        if (delayMs <= 0L) {
            mainHandler.post(restartRunnable)
        } else {
            mainHandler.postDelayed(restartRunnable, delayMs)
        }
    }

    private fun handleUtterance(text: String) {
        ambientBuffer.append(text)
        when (val drop = halfDuplex.dropReasonForUtterance(text)) {
            HalfDuplexPolicy.DropReason.Busy -> {
                Log.i(TAG, "half-duplex busy â€” dropping '$text'")
                return
            }
            HalfDuplexPolicy.DropReason.SpitRepeat -> {
                Log.i(TAG, "SPIT: detected user repeat â€” ignoring")
                onStatus("SPIT â€” listening for questionsâ€¦")
                return
            }
            HalfDuplexPolicy.DropReason.Echo -> {
                Log.i(TAG, "echo filter â€” ignoring '$text'")
                return
            }
            null -> Unit
        }
        if (assistantSettings.spitModeEnabled.value) {
            handleSpitUtterance(text)
            return
        }
        if (awaitingFollowUp) {
            awaitingFollowUp = false
            wakeHandledForUtterance = true
            handleQuery(text)
            return
        }
        val match = WakeWordDetector.findWake(text) ?: run {
            Log.i(TAG, "no wake in '$text'")
            onStatus("Heard: \"$text\"")
            return
        }
        wakeHandledForUtterance = true
        Log.i(TAG, "WAKE matched trailing='${match.trailingQuery}' from '$text'")
        if (match.trailingQuery.isNotBlank()) {
            handleQuery(match.trailingQuery)
        } else {
            awaitingFollowUp = true
            onStatus("Yes? Listeningâ€¦")
            scope.launch {
                runCatching { ttsPlayer.speak("Yes?") }
                halfDuplex.noteSpoken("Yes?")
            }
        }
    }

    private fun handleSpitUtterance(text: String) {
        Log.i(TAG, "SPIT utterance: '$text' busy=${halfDuplex.isBusy()} awaitRepeat=${halfDuplex.spitRepeatArmed()}")
        if (halfDuplex.spitRepeatArmed()) {
            // Non-repeat while armed â€” clear and continue.
            halfDuplex.clearSpitRepeat()
            spitRepeatTimeoutJob?.cancel()
            spitRepeatTimeoutJob = null
        }
        if (!QuestionDetector.isQuestion(text)) {
            Log.i(TAG, "SPIT: statement â€” ignoring '$text'")
            onStatus("SPIT â€” listening for questionsâ€¦")
            return
        }
        Log.i(TAG, "SPIT: question detected '$text'")
        handleQuery(text, isSpit = true)
    }

    private fun handleQuery(query: String, isSpit: Boolean = false) {
        halfDuplex.beginResponse()
        scope.launch {
            onStatus("Thinkingâ€¦")
            val systemPrompt = if (isSpit) SPIT_SYSTEM_PROMPT else SYSTEM_PROMPT
            val visionKind = VisionIntent.kind(query)
            val needsVision = visionKind !is VisionKind.None
            runCatching {
                var displayQuery = query
                val response = if (needsVision) {
                    halfDuplex.beginVisionHold()
                    scope.launch {
                        delay(visionTimings.visionHoldMaxMs)
                        if (halfDuplex.expireVisionHoldIfNeeded() && active) {
                            withContext(Dispatchers.Main) { restartListening(0) }
                        }
                    }
                    onStatus("Lookingâ€¦")
                    // Session owns capture + TTS + visionResult dual-output (already spoke).
                    runVisionTurn(visionKind, query)
                } else {
                    val history = if (isSpit) conversationHistory.takeLast(SPIT_MAX_HISTORY_TURNS) else conversationHistory
                    val reply = llmClient.ask(
                        query,
                        history,
                        systemInstruction = systemPrompt,
                        maxOutputTokens = if (isSpit) SPIT_MAX_OUTPUT_TOKENS else 120,
                    )
                    conversationHistory = (conversationHistory + Turn("user", query) + Turn("model", reply))
                        .takeLast(MAX_HISTORY_TURNS)
                    reply
                }
                if (!needsVision) {
                    onExchange(
                        AssistantExchange(
                            kind = ExchangeKind.ASKED,
                            query = displayQuery,
                            response = response,
                            hasImage = false,
                        ),
                    )
                }
                halfDuplex.expireVisionHoldIfNeeded()
                val speakNow = if (needsVision && !halfDuplex.isVisionHoldActive() && !halfDuplex.isBusy()) {
                    halfDuplex.maySpeakLateAnswer()
                } else {
                    true
                }
                if (isSpit) {
                    // Vision path already spoke inside session; text path speaks here.
                    if (!needsVision && speakNow) runCatching { ttsPlayer.speak(response) }
                    halfDuplex.armSpitRepeat(response)
                    onStatus("Ignore my repeatâ€¦")
                    spitRepeatTimeoutJob?.cancel()
                    spitRepeatTimeoutJob = scope.launch {
                        delay(SPIT_REPEAT_WINDOW_MS)
                        if (halfDuplex.spitRepeatArmed()) {
                            Log.i(TAG, "SPIT: repeat-ignore window expired")
                            halfDuplex.clearSpitRepeat()
                            if (active) onStatus("SPIT â€” listening for questionsâ€¦")
                        }
                    }
                } else {
                    onStatus("Listening for \"Hey Lingo\"â€¦")
                    if (!needsVision && speakNow) {
                        runCatching { ttsPlayer.speak(response) }
                        halfDuplex.noteSpoken(response)
                    }
                }
            }.onFailure { err ->
                val message = err.message ?: "Something went wrong."
                Log.e(TAG, "handleQuery failed", err)
                onStatus(message)
                onExchange(
                    AssistantExchange(
                        kind = ExchangeKind.ASKED,
                        query = query,
                        response = message,
                        hasImage = needsVision,
                    ),
                )
                runCatching { ttsPlayer.speak(message) }
                halfDuplex.noteSpoken(message)
                if (isSpit) onStatus("SPIT â€” listening for questionsâ€¦")
            }
            halfDuplex.clearVisionHold()
            halfDuplex.endResponse()
            if (active) {
                withContext(Dispatchers.Main) { restartListening(0) }
            }
        }
    }

    private fun startProactiveLoop() {
        proactiveJob?.cancel()
        proactiveJob = scope.launch {
            while (active) {
                if (assistantSettings.spitModeEnabled.value) { delay(5_000L); continue }
                val enabled = assistantSettings.realTimeHelpEnabled.value
                val level = assistantSettings.assistanceLevel.value
                delay(level.checkIntervalMs)
                if (!active || !enabled || halfDuplex.isBusy()) continue
                val sinceLastSpokenMs = System.currentTimeMillis() - lastProactiveSpeechAtMs
                if (sinceLastSpokenMs < MIN_PROACTIVE_COOLDOWN_MS) continue
                val window = ambientBuffer.consumeIfNew() ?: continue
                runCatching {
                    llmClient.ask(
                        prompt = "Recent ambient conversation:\n\"$window\"",
                        systemInstruction = proactiveSystemPrompt(level),
                    )
                }.onSuccess { reply ->
                    val trimmed = reply.trim()
                    if (trimmed.isBlank() || trimmed.equals(NO_RESPONSE_SENTINEL, ignoreCase = true)) return@onSuccess
                    lastProactiveSpeechAtMs = System.currentTimeMillis()
                    onExchange(AssistantExchange(kind = ExchangeKind.TIP, query = null, response = trimmed))
                    runCatching { ttsPlayer.speak(trimmed) }
                    halfDuplex.noteSpoken(trimmed)
                }.onFailure { err ->
                    Log.w(TAG, "proactive check failed", err)
                }
            }
        }
    }

    private fun proactiveSystemPrompt(level: AssistanceLevel): String = SYSTEM_PROMPT +
        "\nYou are passively monitoring ambient conversation and deciding whether to offer a" +
        " short, spoken tip or piece of assistance. Assistance level: ${level.label} (${level.level}/5)." +
        " " + when (level) {
            AssistanceLevel.MINIMAL -> "Only speak up for something clearly important " +
                "(e.g. a safety issue or a direct unanswered question). Otherwise stay silent."
            AssistanceLevel.LOW -> "Only speak up when you're confident it's genuinely useful."
            AssistanceLevel.BALANCED -> "Offer help when it's moderately likely to be useful."
            AssistanceLevel.HIGH -> "Offer helpful info or tips fairly often, even for minor things."
            AssistanceLevel.MAXIMUM -> "Be very proactive â€” offer relevant info, tips, or " +
                "suggestions whenever you can add value."
        } +
        " If you have nothing worth saying, respond with exactly \"$NO_RESPONSE_SENTINEL\" and nothing else." +
        " When you do respond, keep it to one or two short spoken sentences."

    private fun speechErrorLabel(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "audio"
        SpeechRecognizer.ERROR_CLIENT -> "client"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permissions"
        SpeechRecognizer.ERROR_NETWORK -> "network"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "no match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
        SpeechRecognizer.ERROR_SERVER -> "server"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech timeout"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "language not supported"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language unavailable"
        else -> "unknown ($error)"
    }

    companion object {
        private const val TAG = "AssistantEngine"
        private const val MAX_HISTORY_TURNS = 8
        private const val SPIT_MAX_HISTORY_TURNS = 4
        private const val SPIT_MAX_OUTPUT_TOKENS = 80
        private const val MIN_PROACTIVE_COOLDOWN_MS = 20_000L
        private const val NO_RESPONSE_SENTINEL = "NO_RESPONSE"
        private const val SPIT_REPEAT_WINDOW_MS = 10_000L
        /** How long a SPIT partial must stay unchanged before we treat the question as done. */
        private const val SPIT_STABLE_MS = 420L
        const val SYSTEM_PROMPT = "You are Lingo, a concise voice assistant in a Bluetooth headset. " +
            "Reply in one short spoken sentence whenever possible â€” two max. No markdown, lists, or preamble. " +
            "Never restate or repeat the user's question â€” say only the answer. " +
            "For time or date questions, read the device clock from your system instructions exactly."
        const val SPIT_SYSTEM_PROMPT = "You are Lingo in SPIT (Smartest Person In Town) mode. " +
            "State ONLY the answer in ONE short sentence. Do not restate, echo, or paraphrase the question. " +
            "No preamble (never start with \"The answer is\" or \"X isâ€¦\" when X is the question topic repeated). " +
            "No lists, no markdown. Be direct. For time or date questions, read the device clock exactly."
    }
}

```

---

## `app\src\main\kotlin\com\livetranslate\headphones\service\TranslationForegroundService.kt`

```kotlin
package com.livetranslate.headphones.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.livetranslate.headphones.AppMode
import com.livetranslate.headphones.LiveTranslateApp
import com.livetranslate.headphones.MainActivity
import com.livetranslate.headphones.R
import com.livetranslate.headphones.glasses.GlassesState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TranslationForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val app: LiveTranslateApp
        get() = LiveTranslateApp.from(application)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val modeName = intent.getStringExtra(EXTRA_MODE) ?: AppMode.LISTEN.name
                val mode = runCatching { AppMode.valueOf(modeName) }.getOrDefault(AppMode.LISTEN)
                app.translationSession.setMode(mode)
                startForegroundTyped(buildNotification(getString(R.string.notification_text)))
                scope.launch {
                    app.assistantSession.stop()
                    app.translationSession.startTranslation()
                    updateNotification(app.translationSession.status.value.statusMessage)
                }
            }
            ACTION_STOP -> {
                scope.launch {
                    app.translationSession.stopTranslation()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_LOOPBACK_START -> {
                startForegroundTyped(buildNotification("Loopback test"))
                app.translationSession.startLoopback()
                scope.launch {
                    kotlinx.coroutines.delay(300)
                    updateNotification(app.translationSession.status.value.statusMessage)
                }
            }
            ACTION_LOOPBACK_STOP -> {
                app.translationSession.stopLoopback()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_TEST_TTS -> {
                startForegroundTyped(buildNotification("TTS test"))
                scope.launch {
                    app.translationSession.testTts()
                    updateNotification(app.translationSession.status.value.statusMessage)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_START_ASSISTANT -> {
                val glassesHeld = isGlassesHeld()
                startForegroundTyped(
                    buildNotification(assistantNotificationText(listening = false, glassesHeld = glassesHeld)),
                    includeConnectedDevice = glassesHeld,
                )
                scope.launch {
                    // Mutually exclusive with translation â€” only one SpeechRecognizer session
                    // can be active at a time.
                    app.translationSession.stopTranslation()
                    app.assistantSession.start()
                    updateAssistantNotification()
                    // Keep notification in sync with glasses BLE + status message.
                    launch {
                        app.assistantSession.status.collectLatest { updateAssistantNotification() }
                    }
                    launch {
                        app.glassesFacade.state.collectLatest { updateAssistantNotification() }
                    }
                    launch {
                        app.assistantSession.visionResult.collectLatest { updateAssistantNotification() }
                    }
                }
            }
            ACTION_STOP_ASSISTANT -> {
                scope.launch {
                    app.assistantSession.stop()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun isGlassesHeld(): Boolean {
        val st = app.glassesFacade.state.value
        return st is GlassesState.Ready || st is GlassesState.Connecting || st is GlassesState.Degraded
    }

    private fun assistantNotificationText(listening: Boolean, glassesHeld: Boolean): String = when {
        listening && glassesHeld -> getString(R.string.notification_assistant_listening_glasses)
        listening -> getString(R.string.notification_assistant_listening)
        glassesHeld -> getString(R.string.notification_assistant_glasses_only)
        else -> getString(R.string.notification_assistant_starting)
    }

    private fun updateAssistantNotification() {
        val status = app.assistantSession.status.value
        val glassesHeld = status.glassesConnected || isGlassesHeld()
        val text = assistantNotificationText(
            listening = status.isActive && status.listening,
            glassesHeld = glassesHeld,
        ).let { base ->
            val msg = status.statusMessage
            if (msg.isNotBlank() && status.isActive) {
                // Prefer live status when more specific than the template.
                if (msg.contains("glasses", ignoreCase = true) ||
                    msg.contains("camera", ignoreCase = true) ||
                    msg.contains("Looking", ignoreCase = true) ||
                    msg.contains("Thinking", ignoreCase = true) ||
                    msg.contains("SPIT", ignoreCase = true) ||
                    msg.contains("Ignore", ignoreCase = true)
                ) {
                    msg
                } else {
                    base
                }
            } else {
                base
            }
        }
        updateNotification(text, includeConnectedDevice = glassesHeld)
    }

    private fun updateNotification(message: String, includeConnectedDevice: Boolean = false) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(message, includeConnectedDevice))
    }

    private fun startForegroundTyped(
        notification: Notification,
        includeConnectedDevice: Boolean = false,
    ) {
        val type = if (includeConnectedDevice) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun buildNotification(
        message: String,
        includeConnectedDevice: Boolean = false,
    ): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (includeConnectedDevice) {
            getString(R.string.notification_title_glasses)
        } else {
            getString(R.string.notification_title)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)

        if (app.assistantSession.hasLastVisionResult()) {
            val viewIntent = PendingIntent.getActivity(
                this,
                1,
                Intent(this, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_VIEW_LAST_VISION_RESULT
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(
                0,
                getString(R.string.notification_view_last_result),
                viewIntent,
            )
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "live_translate"
        const val NOTIFICATION_ID = 42

        const val ACTION_START = "com.livetranslate.headphones.START"
        const val ACTION_STOP = "com.livetranslate.headphones.STOP"
        const val ACTION_LOOPBACK_START = "com.livetranslate.headphones.LOOPBACK_START"
        const val ACTION_LOOPBACK_STOP = "com.livetranslate.headphones.LOOPBACK_STOP"
        const val ACTION_TEST_TTS = "com.livetranslate.headphones.TEST_TTS"
        const val ACTION_START_ASSISTANT = "com.livetranslate.headphones.START_ASSISTANT"
        const val ACTION_STOP_ASSISTANT = "com.livetranslate.headphones.STOP_ASSISTANT"
        const val EXTRA_MODE = "mode"
    }
}

```

---

## `app\src\main\kotlin\com\livetranslate\headphones\ui\AssistantScreen.kt`

```kotlin
package com.livetranslate.headphones.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.livetranslate.headphones.AssistantExchange
import com.livetranslate.headphones.AssistantStatus
import com.livetranslate.headphones.ExchangeKind
import com.livetranslate.headphones.MainViewModel
import com.livetranslate.headphones.assistant.AssistanceLevel
import com.livetranslate.headphones.assistant.HomeNetworkStatus
import com.livetranslate.headphones.audio.TtsVoiceStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val status by viewModel.assistantStatus.collectAsState()
    val exchanges by viewModel.assistantExchanges.collectAsState()
    val realTimeHelp by viewModel.realTimeHelpEnabled.collectAsState()
    val spitMode by viewModel.spitModeEnabled.collectAsState()
    val level by viewModel.assistanceLevel.collectAsState()
    val network by viewModel.homeNetworkStatus.collectAsState()
    val apiKey by viewModel.apiKey.collectAsState()
    val voiceStyle by viewModel.ttsVoiceStyle.collectAsState()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    fun checkLocationPermission() = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    var hasLocationPermission by remember { mutableStateOf(checkLocationPermission()) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasLocationPermission = granted }

    // Android silently revokes a "granted only this time" location permission once the app
    // leaves the foreground, so re-check every time this screen comes back to the front â€”
    // otherwise a stale `true` would let Lingo's home-Wi-Fi detection fail silently.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasLocationPermission = checkLocationPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Lingo Assistant", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { AssistantHeroCard(status, network) }

            item {
                val glassesState by viewModel.glassesState.collectAsState()
                val savedName by viewModel.glassesSavedName.collectAsState()
                val savedAddress by viewModel.glassesSavedAddress.collectAsState()
                val isScanning by viewModel.glassesIsScanning.collectAsState()
                val scanned by viewModel.glassesScannedDevices.collectAsState()
                GlassesConnectCard(
                    glassesState = glassesState,
                    savedName = savedName,
                    savedAddress = savedAddress,
                    isScanning = isScanning,
                    scannedDevices = scanned,
                    onStartScan = viewModel::startGlassesScan,
                    onStopScan = viewModel::stopGlassesScan,
                    onConnect = viewModel::connectGlasses,
                    onDisconnect = viewModel::disconnectGlasses,
                    onForget = viewModel::forgetGlasses,
                )
            }

            if (!hasLocationPermission) {
                item {
                    LocationPermissionNotice(
                        onGrant = { locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                        onOpenSettings = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.fromParts("package", context.packageName, null)),
                            )
                        },
                    )
                }
            }

            if (apiKey.isNullOrBlank()) {
                item { MissingApiKeyNotice() }
            }

            item {
                StartStopButton(isActive = status.isActive, onStart = onStart, onStop = onStop)
            }

            item {
                SpitModeCard(
                    enabled = spitMode,
                    onToggle = viewModel::setSpitModeEnabled,
                )
            }

            item {
                RealTimeHelpCard(
                    enabled = realTimeHelp,
                    dimmed = spitMode,
                    level = level,
                    onToggle = viewModel::setRealTimeHelpEnabled,
                    onLevelChange = viewModel::setAssistanceLevel,
                )
            }

            item {
                VoiceStyleCard(
                    selected = voiceStyle,
                    onSelect = viewModel::setTtsVoiceStyle,
                )
            }

            item {
                OutlinedButton(
                    onClick = viewModel::askAboutWhatISee,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Ask about a photo")
                }
            }

            item {
                ExchangeHeader(count = exchanges.size, onClear = viewModel::clearAssistantExchanges)
            }

            if (exchanges.isEmpty()) {
                item { ExchangeEmptyState() }
            } else {
                items(exchanges.reversed()) { exchange -> ExchangeCard(exchange) }
            }
        }
    }
}

@Composable
private fun AssistantHeroCard(status: AssistantStatus, network: HomeNetworkStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (status.isActive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val onContainer = if (status.isActive) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(onContainer.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.SmartToy, contentDescription = null, tint = onContainer)
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            !status.isActive -> "Idle"
                            status.statusMessage.contains("SPIT", ignoreCase = true) ||
                                status.statusMessage.contains("Ignore my repeat", ignoreCase = true) -> "SPIT mode"
                            else -> "Listening for \"Hey Lingo\""
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = onContainer,
                    )
                    Text(
                        text = status.statusMessage,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = onContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            NetworkPill(network)
        }
    }
}

@Composable
private fun NetworkPill(network: HomeNetworkStatus) {
    val (icon, label, ok) = when (network) {
        HomeNetworkStatus.HOME_WIFI -> Triple(Icons.Filled.Wifi, "Home Wi-Fi", true)
        HomeNetworkStatus.MOBILE_DATA -> Triple(Icons.Filled.SignalCellularAlt, "Mobile data (away)", true)
        HomeNetworkStatus.BLOCKED -> Triple(Icons.Filled.CloudOff, "No connection", false)
    }
    val container = if (ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
    val content = if (ok) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer
    Surface(color = container, shape = RoundedCornerShape(50)) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = content)
        }
    }
}

@Composable
private fun LocationPermissionNotice(onGrant: () -> Unit, onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Lingo needs Location access to tell your home Wi-Fi apart from other networks. " +
                    "When prompted, choose \"While using the app\" â€” not \"Only this time\", which " +
                    "Android revokes as soon as Lingo isn't in the foreground.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onGrant, shape = RoundedCornerShape(12.dp)) {
                    Text("Grant access")
                }
                TextButton(onClick = onOpenSettings) {
                    Text("Open app settings")
                }
            }
        }
    }
}

@Composable
private fun MissingApiKeyNotice() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            "Add a free Gemini API key in Settings before starting Lingo.",
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun StartStopButton(isActive: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    if (isActive) {
        Button(
            onClick = onStop,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Icon(Icons.Filled.Stop, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("Stop assistant", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    } else {
        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("Start assistant", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceStyleCard(
    selected: TtsVoiceStyle,
    onSelect: (TtsVoiceStyle) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Voice", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Natural voices use the phone's higher-quality networked TTS when available.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = selected == TtsVoiceStyle.NATURAL_FEMALE,
                    onClick = { onSelect(TtsVoiceStyle.NATURAL_FEMALE) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                    label = { Text("Female") },
                )
                SegmentedButton(
                    selected = selected == TtsVoiceStyle.NATURAL_MALE,
                    onClick = { onSelect(TtsVoiceStyle.NATURAL_MALE) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                    label = { Text("Male") },
                )
                SegmentedButton(
                    selected = selected == TtsVoiceStyle.SYSTEM,
                    onClick = { onSelect(TtsVoiceStyle.SYSTEM) },
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                    label = { Text("System") },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpitModeCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "SPIT mode",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Smartest Person In Town â€” answers questions out loud in your headset, then ignores you when you repeat the answer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            if (enabled) {
                Text(
                    "No wake word needed. Speak a question â€” Lingo answers instantly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun RealTimeHelpCard(
    enabled: Boolean,
    dimmed: Boolean,
    level: AssistanceLevel,
    onToggle: (Boolean) -> Unit,
    onLevelChange: (AssistanceLevel) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.45f else 1f),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Real-time help", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Lingo listens to what's around it and offers unprompted tips.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    enabled = !dimmed,
                )
            }

            Text(
                "Assistance level: ${level.label} (${level.level}/5)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                AssistanceLevel.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = level == option,
                        onClick = { onLevelChange(option) },
                        enabled = enabled && !dimmed,
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = AssistanceLevel.entries.size),
                        label = { Text("${option.level}") },
                    )
                }
            }
            Text(
                "1 = least proactive (wake word only), 5 = most proactive.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExchangeHeader(count: Int, onClear: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = if (count > 0) "Conversation ($count)" else "Conversation",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (count > 0) {
            TextButton(onClick = onClear) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Clear")
            }
        }
    }
}

@Composable
private fun ExchangeEmptyState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 36.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Filled.SmartToy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
            Text(
                "Say \"Hey Lingo\" or \"OK Lingo\" to ask something",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExchangeCard(exchange: AssistantExchange) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExchangeBadge(exchange)
                if (exchange.hasImage) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Filled.Image,
                        contentDescription = "Vision request",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = when (exchange.imageProvenance) {
                            com.livetranslate.headphones.glasses.StillProvenance.Glasses -> "Glasses"
                            com.livetranslate.headphones.glasses.StillProvenance.Phone ->
                                if (exchange.fallbackReason != null) "Phone camera" else "Phone"
                            null -> "Photo"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (exchange.hasImage && exchange.fallbackReason != null) {
                Text(
                    text = when (exchange.fallbackReason) {
                        com.livetranslate.headphones.glasses.FallbackReason.GlassesCoolingDown ->
                            "Glasses busy â€” used phone camera"
                        com.livetranslate.headphones.glasses.FallbackReason.GlassesTimeout ->
                            "Glasses timed out â€” used phone camera"
                        com.livetranslate.headphones.glasses.FallbackReason.GlassesNotReady ->
                            "Glasses not ready â€” used phone camera"
                        com.livetranslate.headphones.glasses.FallbackReason.GlassesCaptureFailed ->
                            "Glasses capture failed â€” used phone camera"
                        com.livetranslate.headphones.glasses.FallbackReason.GlassesMalformedPayload ->
                            "Glasses image unreadable â€” used phone camera"
                        com.livetranslate.headphones.glasses.FallbackReason.GlassesUnsupported ->
                            "Glasses unsupported â€” used phone camera"
                        com.livetranslate.headphones.glasses.FallbackReason.UserCancelled ->
                            "Capture cancelled"
                        null -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (!exchange.query.isNullOrBlank()) {
                Text(
                    text = "\"${exchange.query}\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = exchange.response,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ExchangeBadge(exchange: AssistantExchange) {
    val icon: ImageVector
    val label: String
    when (exchange.kind) {
        ExchangeKind.ASKED -> {
            icon = Icons.Filled.Mic
            label = "Asked"
        }
        ExchangeKind.TIP -> {
            icon = Icons.Filled.Lightbulb
            label = "Tip"
        }
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(50)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

```

---

## `app\src\main\kotlin\com\livetranslate\headphones\ui\GlassesConnectCard.kt`

```kotlin
package com.livetranslate.headphones.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.livetranslate.headphones.glasses.GlassesState
import com.livetranslate.headphones.glasses.ScannedGlassesDevice

@Composable
fun GlassesConnectCard(
    glassesState: GlassesState,
    savedName: String?,
    savedAddress: String?,
    isScanning: Boolean,
    scannedDevices: List<ScannedGlassesDevice>,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (address: String, name: String?) -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showScanDialog by remember { mutableStateOf(false) }

    fun hasScanPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val scan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
        val connect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        return scan && connect
    }

    var permitted by remember { mutableStateOf(hasScanPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        permitted = results.values.all { it }
        if (permitted) {
            showScanDialog = true
            onStartScan()
        }
    }

    DisposableEffect(lifecycleOwner, showScanDialog) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                if (showScanDialog) {
                    onStopScan()
                    showScanDialog = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (showScanDialog) onStopScan()
        }
    }

    val statusLabel = when (glassesState) {
        GlassesState.Ready -> "Connected"
        GlassesState.Connecting -> "Connectingâ€¦"
        is GlassesState.Degraded -> "Connected (degraded)"
        GlassesState.Unsupported -> "Unsupported"
        GlassesState.Absent -> if (savedAddress != null) "Saved â€” not connected" else "Not connected"
    }
    val displayName = when {
        glassesState is GlassesState.Ready || glassesState is GlassesState.Connecting ||
            glassesState is GlassesState.Degraded -> savedName ?: "Glasses"
        savedName != null -> savedName
        else -> "No glasses paired"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when {
                        glassesState is GlassesState.Ready -> Icons.Filled.BluetoothConnected
                        isScanning -> Icons.Filled.BluetoothSearching
                        else -> Icons.Filled.Bluetooth
                    },
                    contentDescription = null,
                )
                Spacer(Modifier.padding(horizontal = 6.dp))
                Column(Modifier.weight(1f)) {
                    Text("Glasses", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(displayName, style = MaterialTheme.typography.bodyMedium)
                    Text(statusLabel, style = MaterialTheme.typography.bodySmall)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    glassesState is GlassesState.Ready || glassesState is GlassesState.Connecting ||
                        glassesState is GlassesState.Degraded -> {
                        OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                        TextButton(onClick = onForget) { Text("Forget") }
                    }
                    savedAddress != null -> {
                        OutlinedButton(onClick = { onConnect(savedAddress, savedName) }) {
                            Text("Connect")
                        }
                        OutlinedButton(
                            onClick = {
                                if (permitted) {
                                    showScanDialog = true
                                    onStartScan()
                                } else {
                                    permissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.BLUETOOTH_SCAN,
                                            Manifest.permission.BLUETOOTH_CONNECT,
                                        ),
                                    )
                                }
                            },
                        ) { Text("Scan") }
                        TextButton(onClick = onForget) { Text("Forget") }
                    }
                    else -> {
                        OutlinedButton(
                            onClick = {
                                if (permitted) {
                                    showScanDialog = true
                                    onStartScan()
                                } else {
                                    permissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.BLUETOOTH_SCAN,
                                            Manifest.permission.BLUETOOTH_CONNECT,
                                        ),
                                    )
                                }
                            },
                        ) { Text("Scan for glasses") }
                    }
                }
            }
        }
    }

    if (showScanDialog) {
        AlertDialog(
            onDismissRequest = {
                onStopScan()
                showScanDialog = false
            },
            title = { Text("Nearby glasses") },
            text = {
                Column {
                    if (isScanning) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Text("Scanningâ€¦")
                }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (scannedDevices.isEmpty() && !isScanning) {
                        Text("No devices found. Make sure the glasses are on and nearby.")
                    }
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(scannedDevices, key = { it.address }) { device ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onConnect(device.address, device.name)
                                        onStopScan()
                                        showScanDialog = false
                                    }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(device.name, fontWeight = FontWeight.Medium)
                                Text(
                                    "${device.address}  Â·  ${device.rssi} dBm",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onStopScan()
                        showScanDialog = false
                    },
                ) { Text("Close") }
            },
            dismissButton = {
                if (!isScanning) {
                    TextButton(onClick = onStartScan) { Text("Scan again") }
                }
            },
        )
    }
}

```

---

## `app\src\main\kotlin\com\livetranslate\headphones\ui\VisionResultScreens.kt`

```kotlin
package com.livetranslate.headphones.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livetranslate.headphones.vision.VisionResult

@Composable
fun ObjectIdentifyResultScreen(
    result: VisionResult.ObjectIdentify,
    onDone: () -> Unit,
    onRetake: () -> Unit,
    onRetry: () -> Unit,
) {
    ResultScaffold(
        stillPath = result.still.file.absolutePath,
        answer = result.answer,
        banner = if (result.geminiOffline) result.answer else null,
        downloading = false,
        onDone = onDone,
        primaryAction = if (result.geminiOffline) {
            "Retry" to onRetry
        } else {
            "Retake" to onRetake
        },
        secondaryAction = if (result.geminiOffline) "Retake" to onRetake else null,
        overlay = null,
    )
}

@Composable
fun SignTranslateResultScreen(
    result: VisionResult.SignTranslate,
    onDone: () -> Unit,
    onRetakePhone: () -> Unit,
    onRetake: () -> Unit,
    onRetry: () -> Unit,
    onAcceptGemini: () -> Unit,
) {
    when {
        result.downloadingModel -> {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Downloading translation modelâ€¦",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Wiâ€‘Fi recommended",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        result.glassesZeroOcr -> {
            ResultScaffold(
                stillPath = result.still.file.absolutePath,
                answer = result.answer,
                banner = null,
                downloading = false,
                onDone = onDone,
                primaryAction = "Retake with phone camera?" to onRetakePhone,
                secondaryAction = "Try Gemini text-only" to onAcceptGemini,
                overlay = null,
            )
        }
        else -> {
            ResultScaffold(
                stillPath = result.still.file.absolutePath,
                answer = result.answer,
                banner = result.banner,
                downloading = false,
                onDone = onDone,
                primaryAction = when {
                    result.geminiOffline -> "Retry" to onRetry
                    else -> "Retake" to onRetake
                },
                secondaryAction = when {
                    result.geminiOffline -> "Retake" to onRetake
                    result.banner != null -> "Retake with phone" to onRetakePhone
                    else -> null
                },
                overlay = if (result.blocks.isNotEmpty()) {
                    {
                        SignOverlay(blocks = result.blocks)
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun ResultScaffold(
    stillPath: String,
    answer: String,
    banner: String?,
    downloading: Boolean,
    onDone: () -> Unit,
    primaryAction: Pair<String, () -> Unit>?,
    secondaryAction: Pair<String, () -> Unit>?,
    overlay: (@Composable () -> Unit)?,
) {
    val bitmap = remember(stillPath) {
        BitmapFactory.decodeFile(stillPath)?.asImageBitmap()
    }
    DisposableEffect(stillPath) {
        onDispose { /* bitmap recycled by GC / ImageBitmap */ }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black),
        ) {
            if (bitmap != null) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Captured still",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    if (overlay != null) {
                        Box(Modifier.fillMaxSize()) { overlay() }
                    }
                }
            }
            if (downloading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }

        if (!banner.isNullOrBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    banner,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(0.55f)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = answer,
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Start,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onDone) { Text("Done") }
                Spacer(Modifier.weight(1f))
                secondaryAction?.let { (label, action) ->
                    OutlinedButton(onClick = action, shape = RoundedCornerShape(12.dp)) {
                        Text(label)
                    }
                }
                primaryAction?.let { (label, action) ->
                    Button(onClick = action, shape = RoundedCornerShape(12.dp)) {
                        Text(label)
                    }
                }
            }
        }
    }
}

@Composable
private fun SignOverlay(blocks: List<com.livetranslate.headphones.vision.SignTextBlock>) {
    val stroke = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize()) {
        blocks.forEach { block ->
            val left = block.box.left * size.width
            val top = block.box.top * size.height
            val w = (block.box.right - block.box.left) * size.width
            val h = (block.box.bottom - block.box.top) * size.height
            drawRect(
                color = stroke.copy(alpha = 0.85f),
                topLeft = Offset(left, top),
                size = Size(w, h),
                style = Stroke(width = 3f),
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.35f),
                topLeft = Offset(left, top),
                size = Size(w, h.coerceAtLeast(28f)),
            )
        }
    }
}

```

---

## `app\src\main\kotlin\com\livetranslate\headphones\vision\StillFileCache.kt`

```kotlin
package com.livetranslate.headphones.vision

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Disk cache for vision stills. Every vision turn writes a JPEG file.
 * TTL 1h / max 3 files; files held by an open result screen are not evicted.
 */
class StillFileCache(context: Context) {
    private val dir = File(context.applicationContext.cacheDir, "vision_stills").also { it.mkdirs() }
    private val held = ConcurrentHashMap.newKeySet<String>()

    fun write(jpegBytes: ByteArray): StillFile {
        evictExpired()
        val id = UUID.randomUUID().toString()
        val file = File(dir, "$id.jpg")
        file.writeBytes(jpegBytes)
        evictOverflow()
        return StillFile(id = id, file = file, uri = Uri.fromFile(file))
    }

    fun hold(id: String) {
        held.add(id)
    }

    fun release(id: String) {
        held.remove(id)
        evictExpired()
    }

    fun readBytes(still: StillFile): ByteArray? =
        runCatching { still.file.takeIf { it.exists() }?.readBytes() }.getOrNull()

    private fun evictExpired() {
        val cutoff = System.currentTimeMillis() - TTL_MS
        dir.listFiles()?.forEach { file ->
            val id = file.nameWithoutExtension
            if (id in held) return@forEach
            if (file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    private fun evictOverflow() {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }.orEmpty()
        var excess = files.size - MAX_FILES
        for (file in files) {
            if (excess <= 0) break
            val id = file.nameWithoutExtension
            if (id in held) continue
            if (file.delete()) excess--
        }
    }

    companion object {
        private const val TAG = "StillFileCache"
        private const val TTL_MS = 60L * 60L * 1000L
        private const val MAX_FILES = 3
    }
}

data class StillFile(
    val id: String,
    val file: File,
    val uri: Uri,
)

```

---

## `app\src\main\kotlin\com\livetranslate\headphones\vision\VisionResult.kt`

```kotlin
package com.livetranslate.headphones.vision

import android.graphics.Rect
import android.net.Uri
import com.livetranslate.headphones.assistant.VisionKind
import com.livetranslate.headphones.glasses.FallbackReason
import com.livetranslate.headphones.glasses.StillProvenance

/**
 * Queued / displayable result of a vision turn. Always published alongside TTS.
 * MainActivity shows when foreground; otherwise state stays until resume.
 */
sealed class VisionResult {
    abstract val still: StillFile
    abstract val answer: String
    abstract val provenance: StillProvenance
    abstract val fallbackReason: FallbackReason?
    abstract val query: String
    abstract val kind: VisionKind
    abstract val geminiOffline: Boolean

    data class ObjectIdentify(
        override val still: StillFile,
        override val answer: String,
        override val provenance: StillProvenance,
        override val fallbackReason: FallbackReason? = null,
        override val query: String,
        override val geminiOffline: Boolean = false,
    ) : VisionResult() {
        override val kind: VisionKind = VisionKind.ObjectIdentify
    }

    data class SignTranslate(
        override val still: StillFile,
        override val answer: String,
        override val provenance: StillProvenance,
        override val fallbackReason: FallbackReason? = null,
        override val query: String,
        override val geminiOffline: Boolean = false,
        val blocks: List<SignTextBlock> = emptyList(),
        /** e.g. SCRIPT_UNSUPPORTED â€” showing Gemini translation */
        val banner: String? = null,
        val downloadingModel: Boolean = false,
        val glassesZeroOcr: Boolean = false,
        val imageWidth: Int = 0,
        val imageHeight: Int = 0,
    ) : VisionResult() {
        override val kind: VisionKind = VisionKind.SignTranslate
    }
}

data class SignTextBlock(
    val sourceText: String,
    val englishText: String,
    /** Normalized 0â€“1 bounding box relative to still width/height. */
    val box: RectFNorm,
)

data class RectFNorm(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    companion object {
        fun fromPixel(rect: Rect, width: Int, height: Int): RectFNorm {
            val w = width.coerceAtLeast(1).toFloat()
            val h = height.coerceAtLeast(1).toFloat()
            return RectFNorm(
                left = rect.left / w,
                top = rect.top / h,
                right = rect.right / w,
                bottom = rect.bottom / h,
            )
        }
    }
}

fun VisionResult.stillUri(): Uri = still.uri

```

---

## `app\src\main\kotlin\com\livetranslate\headphones\vision\SignOcr.kt`

```kotlin
package com.livetranslate.headphones.vision

import android.graphics.BitmapFactory
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class OcrBlock(
    val text: String,
    val boundingBox: Rect,
)

data class OcrResult(
    val blocks: List<OcrBlock>,
    val imageWidth: Int,
    val imageHeight: Int,
)

/** Latin-script on-device OCR via ML Kit. */
object SignOcr {
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognize(jpegBytes: ByteArray): OcrResult = withContext(Dispatchers.Default) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
        val width = bounds.outWidth.coerceAtLeast(1)
        val height = bounds.outHeight.coerceAtLeast(1)

        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: return@withContext OcrResult(emptyList(), width, height)
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val text = Tasks.await(recognizer.process(image))
            val blocks = text.textBlocks.mapNotNull { block ->
                val box = block.boundingBox ?: return@mapNotNull null
                val t = block.text.trim()
                if (t.isEmpty()) null else OcrBlock(t, box)
            }
            OcrResult(blocks, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }
}

```

---

## `app\src\main\kotlin\com\livetranslate\headphones\vision\SignTranslatePipeline.kt`

```kotlin
package com.livetranslate.headphones.vision

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.livetranslate.headphones.assistant.LlmClient
import com.livetranslate.headphones.glasses.StillProvenance
import com.livetranslate.headphones.translation.LanguageModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class SignPipelineOutcome {
    data class OverlayReady(
        val blocks: List<SignTextBlock>,
        val answer: String,
        val imageWidth: Int,
        val imageHeight: Int,
    ) : SignPipelineOutcome()

    data class GlassesZeroOcr(val message: String) : SignPipelineOutcome()

    data class Downloading(val message: String = "Downloading translation modelâ€¦") : SignPipelineOutcome()

    data class GeminiFallback(
        val answer: String,
        val banner: String?,
        val geminiOffline: Boolean = false,
        val imageWidth: Int = 0,
        val imageHeight: Int = 0,
    ) : SignPipelineOutcome()
}

/**
 * SignTranslate: Latin OCR â†’ ML Kit translate â†’ overlay; else Sign-Gemini prompt.
 */
class SignTranslatePipeline(
    private val languageModelManager: LanguageModelManager,
    private val llmClient: LlmClient,
) {
    private val languageId = LanguageIdentification.getClient()

    suspend fun run(
        jpegBytes: ByteArray,
        provenance: StillProvenance,
        onDownloading: (suspend () -> Unit)? = null,
    ): SignPipelineOutcome = withContext(Dispatchers.IO) {
        val ocr = SignOcr.recognize(jpegBytes)
        if (ocr.blocks.isEmpty()) {
            return@withContext if (provenance == StillProvenance.Glasses) {
                SignPipelineOutcome.GlassesZeroOcr(GLASSES_ZERO_OCR_MESSAGE)
            } else {
                geminiFallback(jpegBytes, banner = BANNER_NO_TEXT, ocr.imageWidth, ocr.imageHeight)
            }
        }

        val joined = ocr.blocks.joinToString("\n") { it.text }
        val langCode = identifyLanguage(joined)
        if (langCode == null || langCode == "und" || langCode == "en") {
            // English / unidentified â€” still show overlay with source as English.
            if (langCode == "en") {
                val blocks = ocr.blocks.map {
                    SignTextBlock(
                        sourceText = it.text,
                        englishText = it.text,
                        box = RectFNorm.fromPixel(it.boundingBox, ocr.imageWidth, ocr.imageHeight),
                    )
                }
                return@withContext SignPipelineOutcome.OverlayReady(
                    blocks = blocks,
                    answer = blocks.joinToString("\n") { it.englishText },
                    imageWidth = ocr.imageWidth,
                    imageHeight = ocr.imageHeight,
                )
            }
            // Non-Latin / und â€” Gemini extract+translate
            return@withContext geminiFallback(
                jpegBytes,
                banner = BANNER_SCRIPT_UNSUPPORTED,
                ocr.imageWidth,
                ocr.imageHeight,
            )
        }

        val downloaded = languageModelManager.isModelDownloaded(langCode)
        if (!downloaded) {
            onDownloading?.invoke()
            val download = languageModelManager.download(langCode, requireWifi = false)
            if (download.isFailure) {
                Log.w(TAG, "translate pack download failed for $langCode", download.exceptionOrNull())
                return@withContext geminiFallback(
                    jpegBytes,
                    banner = BANNER_MODEL_FAILED,
                    ocr.imageWidth,
                    ocr.imageHeight,
                )
            }
        }

        val translator = languageModelManager.translatorFor(langCode)
        val blocks = ocr.blocks.map { block ->
            val english = runCatching {
                Tasks.await(translator.translate(block.text))
            }.getOrElse { block.text }
            SignTextBlock(
                sourceText = block.text,
                englishText = english.trim().ifBlank { block.text },
                box = RectFNorm.fromPixel(block.boundingBox, ocr.imageWidth, ocr.imageHeight),
            )
        }
        SignPipelineOutcome.OverlayReady(
            blocks = blocks,
            answer = blocks.joinToString("\n") { it.englishText },
            imageWidth = ocr.imageWidth,
            imageHeight = ocr.imageHeight,
        )
    }

    private suspend fun geminiFallback(
        jpegBytes: ByteArray,
        banner: String?,
        imageWidth: Int,
        imageHeight: Int,
    ): SignPipelineOutcome {
        return runCatching {
            val answer = llmClient.askWithImage(
                prompt = SIGN_GEMINI_PROMPT,
                jpegBytes = jpegBytes,
                systemInstruction = SIGN_SYSTEM,
                maxOutputTokens = 120,
            )
            SignPipelineOutcome.GeminiFallback(
                answer = answer,
                banner = banner,
                geminiOffline = false,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
            )
        }.getOrElse { err ->
            Log.w(TAG, "Sign Gemini fallback failed", err)
            SignPipelineOutcome.GeminiFallback(
                answer = GEMINI_OFFLINE_MESSAGE,
                banner = banner,
                geminiOffline = true,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
            )
        }
    }

    private fun identifyLanguage(text: String): String? =
        runCatching { Tasks.await(languageId.identifyLanguage(text)) }.getOrNull()

    companion object {
        private const val TAG = "SignTranslatePipeline"

        const val SIGN_GEMINI_PROMPT =
            "Extract any legible text in the image and translate to English. " +
                "If no text is legible, say so; do not describe objects."

        private const val SIGN_SYSTEM =
            "You extract and translate text from photos. Reply in English only. " +
                "Do not describe scenes or objects â€” only text content and translation."

        const val OBJECT_IDENTIFY_PROMPT =
            "In one or two short sentences, identify what this is. Be as specific as the " +
                "image allows. If you are unsure, say so. Do not invent fine print or labels " +
                "you cannot read."

        const val GLASSES_ZERO_OCR_MESSAGE =
            "I couldn't read that from the glasses."

        const val GEMINI_OFFLINE_MESSAGE =
            "Couldn't reach Gemini â€” tap retry"

        const val BANNER_SCRIPT_UNSUPPORTED =
            "SCRIPT_UNSUPPORTED â€” showing Gemini translation"

        const val BANNER_NO_TEXT =
            "No text found â€” showing Gemini translation"

        const val BANNER_MODEL_FAILED =
            "Translation model unavailable â€” showing Gemini translation"
    }
}

```

---

## `app\src\main\kotlin\com\livetranslate\headphones\glasses\GlassesFacade.kt`

```kotlin
package com.livetranslate.headphones.glasses

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Narrow glasses API owned by Lingo. Callers never import `com.oudmon.ble.*`.
 *
 * **CoolingDown is the correctness control (DeepSeek A1).** See Phase 0â€“1 pins.
 * [captureOnce] returns [CaptureOutcome] (A2). JPEG recovery inside repository (A3).
 * Deadline is hard stop; chunk wait = min(dataCapMs, remaining) (A4).
 */
interface GlassesFacade {
    val state: StateFlow<GlassesState>
    val capability: StateFlow<GlassesCapability>
    val events: SharedFlow<GlassesEvent>
    val timings: GlassesTimings

    val scannedDevices: StateFlow<List<ScannedGlassesDevice>>
    val isScanning: StateFlow<Boolean>
    val savedDeviceName: StateFlow<String?>
    val savedDeviceAddress: StateFlow<String?>

    suspend fun connect(address: String? = null)
    fun connectToDevice(address: String, name: String? = null)
    /** Session-start / app-foreground only â€” not ambient. Returns true if a reconnect was attempted. */
    fun tryAutoReconnect(): Boolean
    fun clearSavedDevice()
    fun disconnect()

    fun startScan()
    fun stopScan()

    suspend fun captureOnce(deadlineEpochMs: Long): CaptureOutcome
    fun abandonCapture(reason: String = "selector_cutoff")
}

```

---

## `app\src\main\kotlin\com\livetranslate\headphones\glasses\GlassesModels.kt`

```kotlin
package com.livetranslate.headphones.glasses

/** Connection / readiness axis â€” independent of cooling / canCapture. */
sealed class GlassesState {
    data object Absent : GlassesState()
    data object Connecting : GlassesState()
    data object Ready : GlassesState()
    data class Degraded(val reason: String) : GlassesState()
    data object Unsupported : GlassesState()
}

sealed class GlassesEvent {
    data object Wake : GlassesEvent()
    data object Button : GlassesEvent()
    data object BatteryLow : GlassesEvent()
    data class Dropped(val reason: String) : GlassesEvent()
}

enum class CaptureBlockReason {
    NotConnected,
    NotReady,
    CoolingDown,
}

/**
 * Capability for StillSelector: connected âˆ§ Ready âˆ§ Â¬CoolingDown.
 *
 * **CoolingDown is the correctness control (DeepSeek A1):** set on abandon/deadline
 * until wire-idle ([GlassesTimings.chunkIdleMs]) or [GlassesTimings.coolingDownMaxMs].
 * It gates re-arm so leftover BLE chunks cannot complete a newly armed capture.
 * Local gen checks are bookkeeping only â€” the wire has no per-capture token.
 */
data class GlassesCapability(
    val state: GlassesState,
    val coolingDown: Boolean,
    val canCapture: Boolean,
    val blockReason: CaptureBlockReason? = null,
)

data class ScannedGlassesDevice(
    val name: String,
    val address: String,
    val rssi: Int,
)

enum class StillProvenance {
    Glasses,
    Phone,
}

enum class FallbackReason {
    GlassesNotReady,
    GlassesCoolingDown,
    GlassesCaptureFailed,
    GlassesTimeout,
    GlassesMalformedPayload,
    GlassesUnsupported,
    UserCancelled,
}

/**
 * Result of [GlassesFacade.captureOnce]. Typed failures so StillSelector can map
 * Timeout vs MalformedPayload vs Busy without inferring from a bare null (DeepSeek A2).
 */
sealed class CaptureOutcome {
    data class Success(val jpegBytes: ByteArray) : CaptureOutcome() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Success) return false
            return jpegBytes.contentEquals(other.jpegBytes)
        }

        override fun hashCode(): Int = jpegBytes.contentHashCode()
    }

    data object Timeout : CaptureOutcome()
    data object MalformedPayload : CaptureOutcome()
    /** CoolingDown / single-flight â€” cannot arm. */
    data object Busy : CaptureOutcome()
    data object NotConnected : CaptureOutcome()
}

fun CaptureOutcome.toFallbackReason(): FallbackReason? = when (this) {
    is CaptureOutcome.Success -> null
    CaptureOutcome.Timeout -> FallbackReason.GlassesTimeout
    CaptureOutcome.MalformedPayload -> FallbackReason.GlassesMalformedPayload
    CaptureOutcome.Busy -> FallbackReason.GlassesCoolingDown
    CaptureOutcome.NotConnected -> FallbackReason.GlassesNotReady
}

data class StillResult(
    val jpegBytes: ByteArray,
    val provenance: StillProvenance,
    val fallbackReason: FallbackReason? = null,
    val width: Int? = null,
    val height: Int? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StillResult) return false
        return jpegBytes.contentEquals(other.jpegBytes) &&
            provenance == other.provenance &&
            fallbackReason == other.fallbackReason &&
            width == other.width &&
            height == other.height
    }

    override fun hashCode(): Int {
        var result = jpegBytes.contentHashCode()
        result = 31 * result + provenance.hashCode()
        result = 31 * result + (fallbackReason?.hashCode() ?: 0)
        result = 31 * result + (width ?: 0)
        result = 31 * result + (height ?: 0)
        return result
    }
}

```

---

## `app\src\main\kotlin\com\livetranslate\headphones\glasses\GlassesRepository.kt`

```kotlin
package com.livetranslate.headphones.glasses

import android.content.Context
import android.util.Log
import com.livetranslate.headphones.glasses.vendor.GlassesBleClient
import com.livetranslate.headphones.glasses.vendor.OudmonGlassesBleClient
import com.livetranslate.headphones.vision.ImagePrep
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * App-scoped glasses facade backed by [GlassesBleClient] (Oudmon in [vendor]).
 *
 * Mutex discipline: hold only for initiate/mutate; SDK callbacks never take [mutex].
 *
 * CoolingDown is the correctness control (DeepSeek A1). [ImagePrep.extractJpeg] runs
 * before [CaptureOutcome.Success] (A3). Deadline is hard stop; chunk wait =
 * min(dataCapMs, remaining) (A4).
 */
class GlassesRepository(
    context: Context,
    override val timings: GlassesTimings = GlassesTimings.DEFAULT,
    private val ble: GlassesBleClient = OudmonGlassesBleClient(),
) : GlassesFacade {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<GlassesState>(GlassesState.Absent)
    override val state: StateFlow<GlassesState> = _state.asStateFlow()

    private val _coolingDown = MutableStateFlow(false)
    private val _capability = MutableStateFlow(recomputeCapability())
    override val capability: StateFlow<GlassesCapability> = _capability.asStateFlow()

    private val _events = MutableSharedFlow<GlassesEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<GlassesEvent> = _events.asSharedFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedGlassesDevice>>(emptyList())
    override val scannedDevices: StateFlow<List<ScannedGlassesDevice>> = _scannedDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _savedDeviceName = MutableStateFlow(prefs.getString(KEY_NAME, null))
    override val savedDeviceName: StateFlow<String?> = _savedDeviceName.asStateFlow()

    private val _savedDeviceAddress = MutableStateFlow(prefs.getString(KEY_ADDRESS, null))
    override val savedDeviceAddress: StateFlow<String?> = _savedDeviceAddress.asStateFlow()

    @Volatile private var captureGen: Int = 0
    @Volatile private var captureDeferred: CompletableDeferred<CaptureOutcome>? = null
    @Volatile private var captureArmed: Boolean = false
    @Volatile private var photoSignalDeferred: CompletableDeferred<Unit>? = null
    private var coolingJob: Job? = null

    init {
        ble.registerConnectionListener { connected ->
            // Callback path: no mutex
            _state.value = if (connected) GlassesState.Ready else GlassesState.Absent
            publishCapability()
            if (!connected) {
                _events.tryEmit(GlassesEvent.Dropped("ble_disconnected"))
            }
        }
        ble.registerPhotoSignalListener {
            // Bookkeeping only: complete signal if a capture is armed
            if (captureArmed) photoSignalDeferred?.complete(Unit)
        }
    }

    private fun recomputeCapability(): GlassesCapability {
        val st = _state.value
        val cooling = _coolingDown.value
        val ready = st is GlassesState.Ready
        val reason = when {
            st is GlassesState.Unsupported -> CaptureBlockReason.NotReady
            st is GlassesState.Absent -> CaptureBlockReason.NotConnected
            !ready -> CaptureBlockReason.NotReady
            cooling -> CaptureBlockReason.CoolingDown
            else -> null
        }
        return GlassesCapability(
            state = st,
            coolingDown = cooling,
            canCapture = ready && !cooling && ble.isConnected(),
            blockReason = reason,
        )
    }

    private fun publishCapability() {
        _capability.value = recomputeCapability()
    }

    override suspend fun connect(address: String?) {
        val addr = address?.trim().orEmpty().ifEmpty { _savedDeviceAddress.value.orEmpty() }
        if (addr.isEmpty()) {
            Log.w(TAG, "connect requires a device address")
            return
        }
        connectToDevice(addr, _savedDeviceName.value)
    }

    override fun connectToDevice(address: String, name: String?) {
        val addr = address.trim()
        if (addr.isEmpty()) return
        stopScan()
        scope.launch {
            mutex.withLock {
                _state.value = GlassesState.Connecting
                publishCapability()
            }
        }
        if (!name.isNullOrBlank()) saveDevice(name, addr)
        else if (_savedDeviceAddress.value != addr) {
            saveDevice(_savedDeviceName.value ?: "Glasses", addr)
        }
        ble.connect(addr)
    }

    override fun tryAutoReconnect(): Boolean {
        val addr = _savedDeviceAddress.value?.trim().orEmpty()
        if (addr.isEmpty()) return false
        if (ble.isConnected()) return true
        Log.i(TAG, "tryAutoReconnect: $addr")
        connectToDevice(addr, _savedDeviceName.value)
        return true
    }

    override fun clearSavedDevice() {
        prefs.edit().remove(KEY_NAME).remove(KEY_ADDRESS).apply()
        _savedDeviceName.value = null
        _savedDeviceAddress.value = null
    }

    private fun saveDevice(name: String, address: String) {
        prefs.edit().putString(KEY_NAME, name).putString(KEY_ADDRESS, address).apply()
        _savedDeviceName.value = name
        _savedDeviceAddress.value = address
    }

    override fun startScan() {
        _scannedDevices.value = emptyList()
        _isScanning.value = true
        ble.startScan(
            appContext,
            onDevice = { name, address, rssi ->
                val scanned = ScannedGlassesDevice(name, address, rssi)
                val current = _scannedDevices.value.toMutableList()
                if (current.none { it.address == scanned.address }) {
                    current.add(0, scanned)
                    current.sortByDescending { it.rssi }
                    _scannedDevices.value = current.take(30)
                    if (current.size >= 30) stopScan()
                }
            },
            onStopped = { _isScanning.value = false },
            onFailed = {
                _isScanning.value = false
                Log.e(TAG, "scan failed code=$it")
            },
        )
    }

    override fun stopScan() {
        ble.stopScan(appContext)
        _isScanning.value = false
    }

    override fun disconnect() {
        stopScan()
        scope.launch {
            mutex.withLock {
                abandonLocked(CaptureOutcome.Timeout)
                ble.disconnect()
                _state.value = GlassesState.Absent
                publishCapability()
            }
        }
    }

    override fun abandonCapture(reason: String) {
        scope.launch {
            mutex.withLock { abandonLocked(CaptureOutcome.Timeout) }
        }
    }

    private fun abandonLocked(outcome: CaptureOutcome) {
        captureGen++
        captureArmed = false
        captureDeferred?.complete(outcome)
        captureDeferred = null
        photoSignalDeferred?.complete(Unit)
        photoSignalDeferred = null
        enterCoolingLocked()
    }

    private fun enterCoolingLocked() {
        _coolingDown.value = true
        publishCapability()
        coolingJob?.cancel()
        val gen = captureGen
        coolingJob = scope.launch {
            val idleWait = timings.signalMs + timings.settleMs + timings.chunkIdleMs
            delay(minOf(idleWait, timings.coolingDownMaxMs))
            mutex.withLock {
                if (_coolingDown.value) {
                    _coolingDown.value = false
                    publishCapability()
                    Log.i(TAG, "cooling cleared (gen=$gen)")
                }
            }
        }
    }

    override suspend fun captureOnce(deadlineEpochMs: Long): CaptureOutcome = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() >= deadlineEpochMs) return@withContext CaptureOutcome.Timeout

        val cap = _capability.value
        when {
            !ble.isConnected() || cap.state is GlassesState.Absent ->
                return@withContext CaptureOutcome.NotConnected
            !cap.canCapture || cap.coolingDown ->
                return@withContext CaptureOutcome.Busy
        }

        val myGen: Int
        val deferred: CompletableDeferred<CaptureOutcome>
        mutex.withLock {
            if (captureDeferred != null || _coolingDown.value) {
                return@withContext CaptureOutcome.Busy
            }
            myGen = ++captureGen
            deferred = CompletableDeferred()
            captureDeferred = deferred
            captureArmed = true
            photoSignalDeferred = CompletableDeferred()
        }

        try {
            // Quiet drain
            val drainDone = CompletableDeferred<Unit>()
            ble.drainThumbnails(onChunk = {}, onComplete = { drainDone.complete(Unit) })
            withTimeoutOrNull(timings.drainMs) { drainDone.await() }

            if (System.currentTimeMillis() >= deadlineEpochMs || captureGen != myGen) {
                finishWithAbandon(myGen, CaptureOutcome.Timeout)
                return@withContext CaptureOutcome.Timeout
            }

            // Single ACK window (no resend)
            val ackBox = CompletableDeferred<Int?>()
            ble.sendCaptureCommand { code ->
                if (!ackBox.isCompleted) ackBox.complete(code)
            }
            val ackCode = withTimeoutOrNull(timings.ackMs) { ackBox.await() }
            val softFail = ackCode == null || ackCode != 0
            if (softFail) {
                delay(timings.ackWarmupDelayMs) // delay-only warmup
            }

            if (System.currentTimeMillis() >= deadlineEpochMs || captureGen != myGen) {
                finishWithAbandon(myGen, CaptureOutcome.Timeout)
                return@withContext CaptureOutcome.Timeout
            }

            val signalWait = minOf(
                timings.signalMs,
                (deadlineEpochMs - System.currentTimeMillis()).coerceAtLeast(1L),
            )
            val gotSignal = withTimeoutOrNull(signalWait) {
                photoSignalDeferred?.await()
            } != null
            if (!gotSignal) {
                Log.w(TAG, "photo signal timeout")
                finishWithAbandon(myGen, CaptureOutcome.Timeout)
                return@withContext CaptureOutcome.Timeout
            }
            delay(timings.settleMs)

            val buf = ByteArrayOutputStream()
            val pages = sortedMapOf<Int, ByteArray>()
            var usesPages = false
            var totalPages = -1
            val done = CompletableDeferred<Boolean>()
            var lastChunkAt = 0L
            var hadData = false

            ble.listenThumbnails { data, isComplete ->
                // Callback: no mutex. Bookkeeping: ignore if not armed / wrong gen.
                if (!captureArmed || captureGen != myGen || done.isCompleted) return@listenThumbnails
                if (data.isNotEmpty()) {
                    hadData = true
                    lastChunkAt = System.currentTimeMillis()
                    if (data.size > 11 && data[0] == 0xBC.toByte() && data[1] == 0xFD.toByte()) {
                        usesPages = true
                        val pg = data[9].toInt() and 0xFF
                        totalPages = data[7].toInt() and 0xFF
                        if (pg !in pages) pages[pg] = data.copyOfRange(11, data.size)
                    } else {
                        runCatching { buf.write(data) }
                    }
                }
                if (isComplete) done.complete(hadData)
            }

            // A4: chunk budget = min(dataCapMs, remaining to deadline)
            val dataBudget = minOf(
                timings.dataCapMs,
                (deadlineEpochMs - System.currentTimeMillis()).coerceAtLeast(1L),
            )
            withTimeoutOrNull(dataBudget) {
                val completed = withTimeoutOrNull(dataBudget - timings.chunkIdleMs.coerceAtMost(dataBudget)) {
                    done.await()
                }
                if (completed == true) return@withTimeoutOrNull true
                if (hadData && lastChunkAt > 0) {
                    val idle = System.currentTimeMillis() - lastChunkAt
                    if (idle >= timings.chunkIdleMs) return@withTimeoutOrNull true
                    delay((timings.chunkIdleMs - idle + 50).coerceAtMost(dataBudget))
                    return@withTimeoutOrNull true
                }
                false
            }

            if (captureGen != myGen || !captureArmed) {
                return@withContext CaptureOutcome.Timeout
            }
            if (System.currentTimeMillis() >= deadlineEpochMs) {
                finishWithAbandon(myGen, CaptureOutcome.Timeout)
                return@withContext CaptureOutcome.Timeout
            }

            val raw = if (usesPages && pages.isNotEmpty()) {
                val out = ByteArrayOutputStream()
                val maxPage = totalPages.takeIf { it > 0 } ?: ((pages.keys.maxOrNull() ?: 0) + 1)
                for (p in 0 until maxPage) pages[p]?.let { out.write(it) }
                out.toByteArray()
            } else {
                buf.toByteArray()
            }

            if (raw.isEmpty()) {
                finishWithAbandon(myGen, CaptureOutcome.Timeout)
                return@withContext CaptureOutcome.Timeout
            }

            // A3: JPEG recovery inside facade
            val jpeg = ImagePrep.extractJpeg(raw)
            if (jpeg == null || !ImagePrep.isValidJpeg(jpeg)) {
                finishOutcome(myGen, CaptureOutcome.MalformedPayload)
                return@withContext CaptureOutcome.MalformedPayload
            }
            finishOutcome(myGen, CaptureOutcome.Success(jpeg))
            return@withContext CaptureOutcome.Success(jpeg)
        } catch (t: Throwable) {
            Log.e(TAG, "captureOnce failed", t)
            finishWithAbandon(myGen, CaptureOutcome.Timeout)
            return@withContext CaptureOutcome.Timeout
        }
    }

    private suspend fun finishWithAbandon(gen: Int, outcome: CaptureOutcome) {
        mutex.withLock {
            if (gen == captureGen) {
                captureArmed = false
                captureDeferred?.complete(outcome)
                captureDeferred = null
                photoSignalDeferred = null
                captureGen++
                enterCoolingLocked()
            }
        }
    }

    private fun finishOutcome(gen: Int, outcome: CaptureOutcome) {
        if (gen == captureGen) {
            captureArmed = false
            captureDeferred?.complete(outcome)
            captureDeferred = null
            photoSignalDeferred = null
        }
    }

    companion object {
        private const val TAG = "GlassesRepo"
        private const val PREFS = "lingo_glasses"
        private const val KEY_NAME = "saved_device_name"
        private const val KEY_ADDRESS = "saved_device_address"

        @Volatile private var instance: GlassesRepository? = null

        fun getInstance(context: Context, timings: GlassesTimings = GlassesTimings.DEFAULT): GlassesRepository {
            return instance ?: synchronized(this) {
                instance ?: GlassesRepository(context.applicationContext, timings).also { instance = it }
            }
        }
    }
}

```

---

## `app\src\main\kotlin\com\livetranslate\headphones\glasses\FakeGlassesFacade.kt`

```kotlin
package com.livetranslate.headphones.glasses

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Test/dev facade: no Oudmon AAR. Failure injection exercises selector/cooling paths.
 *
 * CoolingDown is the correctness gate (DeepSeek A1): after abandon, re-arm is blocked
 * until cooling clears â€” gen alone cannot protect a new capture from hot-wire leftovers.
 */
class FakeGlassesFacade(
    override val timings: GlassesTimings = GlassesTimings.DEFAULT,
    private val jpegBytes: ByteArray = MINIMAL_JPEG,
    /** Soft-fail first ACK then delay-only warmup (no resend). */
    var softFailAck: Boolean = false,
    /** Delay before photo signal; if past deadline, capture returns Timeout. */
    var signalDelayMs: Long = 50L,
    /** Emit chunks slowly so abandon mid-stream can be tested. */
    var chunkDelayMs: Long = 0L,
    var chunkCount: Int = 1,
    /** Start in Ready when true. */
    var startReady: Boolean = true,
    /**
     * When true, [captureOnce] simulates leftover wire data after abandon (for tests that
     * force-clear cooling). Production path never arms while cooling.
     */
    var injectStaleChunksAfterAbandon: Boolean = false,
) : GlassesFacade {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<GlassesState>(
        if (startReady) GlassesState.Ready else GlassesState.Absent,
    )
    override val state: StateFlow<GlassesState> = _state.asStateFlow()

    private val _coolingDown = MutableStateFlow(false)
    private val _capability = MutableStateFlow(recomputeCapability())
    override val capability: StateFlow<GlassesCapability> = _capability.asStateFlow()

    private val _events = MutableSharedFlow<GlassesEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<GlassesEvent> = _events.asSharedFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedGlassesDevice>>(emptyList())
    override val scannedDevices: StateFlow<List<ScannedGlassesDevice>> = _scannedDevices.asStateFlow()
    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    private val _savedDeviceName = MutableStateFlow<String?>(null)
    override val savedDeviceName: StateFlow<String?> = _savedDeviceName.asStateFlow()
    private val _savedDeviceAddress = MutableStateFlow<String?>(null)
    override val savedDeviceAddress: StateFlow<String?> = _savedDeviceAddress.asStateFlow()

    @Volatile private var captureGen: Int = 0
    @Volatile private var captureDeferred: CompletableDeferred<CaptureOutcome>? = null
    @Volatile private var captureArmed: Boolean = false
    private var coolingJob: Job? = null

    private fun recomputeCapability(): GlassesCapability {
        val st = _state.value
        val cooling = _coolingDown.value
        val ready = st is GlassesState.Ready
        val connected = st is GlassesState.Ready || st is GlassesState.Connecting || st is GlassesState.Degraded
        val reason = when {
            st is GlassesState.Unsupported -> CaptureBlockReason.NotReady
            !connected || st is GlassesState.Absent -> CaptureBlockReason.NotConnected
            !ready -> CaptureBlockReason.NotReady
            cooling -> CaptureBlockReason.CoolingDown
            else -> null
        }
        return GlassesCapability(
            state = st,
            coolingDown = cooling,
            canCapture = ready && !cooling,
            blockReason = reason,
        )
    }

    private fun publishCapability() {
        _capability.value = recomputeCapability()
    }

    override suspend fun connect(address: String?) {
        mutex.withLock {
            _state.value = GlassesState.Connecting
            publishCapability()
        }
        delay(timings.drainMs)
        mutex.withLock {
            _state.value = GlassesState.Ready
            if (!address.isNullOrBlank()) {
                _savedDeviceAddress.value = address
                _savedDeviceName.value = _savedDeviceName.value ?: "Fake glasses"
            }
            publishCapability()
        }
    }

    override fun connectToDevice(address: String, name: String?) {
        _savedDeviceAddress.value = address
        _savedDeviceName.value = name ?: "Fake glasses"
        scope.launch { connect(address) }
    }

    override fun tryAutoReconnect(): Boolean {
        val addr = _savedDeviceAddress.value ?: return false
        scope.launch { connect(addr) }
        return true
    }

    override fun clearSavedDevice() {
        _savedDeviceName.value = null
        _savedDeviceAddress.value = null
    }

    override fun startScan() {
        _isScanning.value = true
        _scannedDevices.value = listOf(
            ScannedGlassesDevice("Fake HeyCyan", "AA:BB:CC:DD:EE:FF", -50),
        )
        scope.launch {
            delay(500)
            _isScanning.value = false
        }
    }

    override fun stopScan() {
        _isScanning.value = false
    }

    override fun disconnect() {
        stopScan()
        scope.launch {
            mutex.withLock {
                abandonLocked("disconnect")
                _state.value = GlassesState.Absent
                publishCapability()
            }
        }
    }

    override fun abandonCapture(reason: String) {
        // Synchronous so CoolingDown is visible before the next captureOnce (DeepSeek A1).
        kotlinx.coroutines.runBlocking {
            mutex.withLock { abandonLocked(reason) }
        }
    }

    private fun abandonLocked(reason: String) {
        captureGen++
        captureArmed = false
        captureDeferred?.complete(CaptureOutcome.Timeout)
        captureDeferred = null
        enterCoolingLocked()
    }

    private fun enterCoolingLocked() {
        _coolingDown.value = true
        publishCapability()
        coolingJob?.cancel()
        val genAtAbandon = captureGen
        coolingJob = scope.launch {
            val idleWait = timings.signalMs + timings.settleMs + timings.chunkIdleMs
            delay(minOf(idleWait, timings.coolingDownMaxMs))
            mutex.withLock {
                if (_coolingDown.value) {
                    _coolingDown.value = false
                    publishCapability()
                }
            }
        }
    }

    /** Test-only: clear cooling without waiting (dangerous â€” mimics removing the safety gate). */
    fun forceClearCoolingForTest() {
        scope.launch {
            mutex.withLock {
                coolingJob?.cancel()
                _coolingDown.value = false
                publishCapability()
            }
        }
    }

    override suspend fun captureOnce(deadlineEpochMs: Long): CaptureOutcome = withContext(Dispatchers.Default) {
        val remainingAtStart = deadlineEpochMs - System.currentTimeMillis()
        if (remainingAtStart <= 0L) return@withContext CaptureOutcome.Timeout

        val cap = _capability.value
        when {
            cap.state is GlassesState.Absent || cap.blockReason == CaptureBlockReason.NotConnected ->
                return@withContext CaptureOutcome.NotConnected
            !cap.canCapture || cap.coolingDown ->
                return@withContext CaptureOutcome.Busy
        }

        val deferred: CompletableDeferred<CaptureOutcome>
        val myGen: Int
        mutex.withLock {
            if (captureDeferred != null || _coolingDown.value) {
                return@withContext CaptureOutcome.Busy
            }
            myGen = ++captureGen
            deferred = CompletableDeferred()
            captureDeferred = deferred
            captureArmed = true
        }

        try {
            delay(timings.drainMs)
            if (System.currentTimeMillis() >= deadlineEpochMs || captureGen != myGen) {
                finishTimeout(myGen)
                return@withContext deferred.await()
            }

            // Single ACK window
            val ackOk = !softFailAck
            delay(minOf(timings.ackMs, (deadlineEpochMs - System.currentTimeMillis()).coerceAtLeast(1L)))
            if (!ackOk || softFailAck) {
                delay(timings.ackWarmupDelayMs)
            }

            val signalWait = minOf(timings.signalMs, (deadlineEpochMs - System.currentTimeMillis()).coerceAtLeast(1L))
            delay(minOf(signalDelayMs, signalWait))
            if (signalDelayMs > signalWait || System.currentTimeMillis() >= deadlineEpochMs) {
                abandonOnDeadline(myGen)
                return@withContext deferred.await()
            }

            delay(timings.settleMs)

            // Chunk gather ceiling under deadline (A4)
            val chunkBudget = minOf(
                timings.dataCapMs,
                (deadlineEpochMs - System.currentTimeMillis()).coerceAtLeast(1L),
            )
            val chunkDeadline = System.currentTimeMillis() + chunkBudget

            repeat(chunkCount) { i ->
                if (System.currentTimeMillis() >= deadlineEpochMs || System.currentTimeMillis() >= chunkDeadline) {
                    abandonOnDeadline(myGen)
                    return@withContext deferred.await()
                }
                if (chunkDelayMs > 0) delay(chunkDelayMs)
                if (i == chunkCount - 1 && captureGen == myGen && captureArmed) {
                    completeCapture(myGen, CaptureOutcome.Success(jpegBytes))
                }
            }
            return@withContext deferred.await()
        } catch (t: Throwable) {
            completeCapture(myGen, CaptureOutcome.Timeout)
            throw t
        }
    }

    private fun abandonOnDeadline(myGen: Int) {
        scope.launch {
            mutex.withLock {
                if (captureGen == myGen) {
                    captureArmed = false
                    captureDeferred?.complete(CaptureOutcome.Timeout)
                    captureDeferred = null
                    captureGen++
                    enterCoolingLocked()
                }
            }
        }
    }

    private fun finishTimeout(myGen: Int) {
        if (captureGen == myGen) {
            captureArmed = false
            captureDeferred?.complete(CaptureOutcome.Timeout)
            captureDeferred = null
            scope.launch { mutex.withLock { enterCoolingLocked() } }
        }
    }

    private fun completeCapture(gen: Int, outcome: CaptureOutcome) {
        val d = captureDeferred
        if (gen == captureGen && d != null && captureArmed) {
            d.complete(outcome)
            captureDeferred = null
            captureArmed = false
        }
    }

    companion object {
        /** Minimal valid JPEG (1x1 pixel). */
        val MINIMAL_JPEG: ByteArray = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
            0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0xFF.toByte(), 0xDB.toByte(), 0x00, 0x43, 0x00,
            0x08, 0x06, 0x06, 0x07, 0x06, 0x05, 0x08, 0x07,
            0x07, 0x07, 0x09, 0x09, 0x08, 0x0A, 0x0C, 0x14,
            0x0D, 0x0C, 0x0B, 0x0B, 0x0C, 0x19, 0x12, 0x13,
            0x0F, 0x14, 0x1D, 0x1A, 0x1F, 0x1E, 0x1D, 0x1A,
            0x1C, 0x1C, 0x20, 0x24, 0x2E, 0x27, 0x20, 0x22,
            0x2C, 0x23, 0x1C, 0x1C, 0x28, 0x37, 0x29, 0x2C,
            0x30, 0x31, 0x34, 0x34, 0x34, 0x1F, 0x27, 0x39,
            0x3D, 0x38, 0x32, 0x3C, 0x2E, 0x33, 0x34, 0x32,
            0xFF.toByte(), 0xC0.toByte(), 0x00, 0x0B, 0x08,
            0x00, 0x01, 0x00, 0x01, 0x01, 0x01, 0x11, 0x00,
            0xFF.toByte(), 0xC4.toByte(), 0x00, 0x14, 0x00,
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x08,
            0xFF.toByte(), 0xC4.toByte(), 0x00, 0x14, 0x10,
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08, 0x01,
            0x01, 0x00, 0x00, 0x3F, 0x00, 0x7F.toByte(),
            0xFF.toByte(), 0xD9.toByte(),
        )
    }
}

```

---

## `app\src\main\kotlin\com\livetranslate\headphones\glasses\vendor\GlassesBleClient.kt`

```kotlin
package com.livetranslate.headphones.glasses.vendor

import android.content.Context

/**
 * Thin BLE client for the Oudmon / HeyCyan glasses AAR.
 *
 * **This package is the only place allowed to import `com.oudmon.ble.*`.**
 * Callbacks must never take [com.livetranslate.headphones.glasses.GlassesRepository]'s mutex â€”
 * they only invoke the provided lambdas (complete deferreds lock-free).
 */
interface GlassesBleClient {
    fun isConnected(): Boolean
    fun connect(address: String)
    fun disconnect()
    fun registerConnectionListener(onChanged: (connected: Boolean) -> Unit)
    fun unregisterConnectionListener()

    fun startScan(
        context: Context,
        onDevice: (name: String, address: String, rssi: Int) -> Unit,
        onStopped: () -> Unit,
        onFailed: (errorCode: Int) -> Unit,
    )

    fun stopScan(context: Context)

    /** Drain stale thumbnail callbacks; invoke [onIdle] when complete or timed out by caller. */
    fun drainThumbnails(onChunk: (ByteArray) -> Unit, onComplete: () -> Unit)

    /**
     * Send capture+thumbnail command `0x02,0x01,0x06`.
     * [onAck] receives errorCode (0 = ok) or null on timeout handled by caller.
     */
    fun sendCaptureCommand(onAck: (errorCode: Int?) -> Unit)

    fun listenThumbnails(
        onChunk: (data: ByteArray, isComplete: Boolean) -> Unit,
    )

    fun registerPhotoSignalListener(onPhotoSignal: () -> Unit)
    fun unregisterPhotoSignalListener()
}

```

---

## `app\src\main\kotlin\com\livetranslate\headphones\glasses\vendor\OudmonGlassesBleClient.kt`

```kotlin
package com.livetranslate.headphones.glasses.vendor

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import com.oudmon.ble.base.scan.BleScannerHelper
import com.oudmon.ble.base.scan.ScanRecord
import com.oudmon.ble.base.scan.ScanWrapperCallback

/**
 * Real Oudmon AAR adapter. Callbacks run on the SDK thread â€” callers must not
 * acquire the repository mutex from these lambdas (complete deferreds lock-free).
 *
 * Field note: document actual callback-thread delivery when observed on device.
 */
class OudmonGlassesBleClient : GlassesBleClient {
    private var connectionListener: ((Boolean) -> Unit)? = null
    private var photoSignalListener: (() -> Unit)? = null
    private var deviceNotifyRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanTimeout: Runnable? = null

    override fun isConnected(): Boolean = BleOperateManager.getInstance().isConnected

    override fun connect(address: String) {
        BleOperateManager.getInstance().connectDirectly(address)
        connectionListener?.invoke(isConnected())
    }

    override fun disconnect() {
        BleOperateManager.getInstance().unBindDevice()
        connectionListener?.invoke(false)
    }

    override fun registerConnectionListener(onChanged: (Boolean) -> Unit) {
        connectionListener = onChanged
        onChanged(isConnected())
    }

    override fun unregisterConnectionListener() {
        connectionListener = null
    }

    override fun startScan(
        context: Context,
        onDevice: (name: String, address: String, rssi: Int) -> Unit,
        onStopped: () -> Unit,
        onFailed: (errorCode: Int) -> Unit,
    ) {
        stopScan(context)
        BleScannerHelper.getInstance().reSetCallback()
        BleScannerHelper.getInstance().scanDevice(
            context,
            null,
            object : ScanWrapperCallback {
                override fun onStart() = Unit

                override fun onLeScan(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
                    if (device == null || device.name.isNullOrEmpty()) return
                    onDevice(device.name ?: "Unknown", device.address, rssi)
                }

                override fun onStop() {
                    onStopped()
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.e(TAG, "Scan failed: $errorCode")
                    onFailed(errorCode)
                }

                override fun onParsedData(device: BluetoothDevice?, scanRecord: ScanRecord?) = Unit
                override fun onBatchScanResults(results: MutableList<ScanResult>?) = Unit
            },
        )
        val timeout = Runnable {
            stopScan(context)
            onStopped()
        }
        scanTimeout = timeout
        mainHandler.postDelayed(timeout, SCAN_TIMEOUT_MS)
    }

    override fun stopScan(context: Context) {
        scanTimeout?.let { mainHandler.removeCallbacks(it) }
        scanTimeout = null
        runCatching { BleScannerHelper.getInstance().stopScan(context) }
    }

    override fun drainThumbnails(onChunk: (ByteArray) -> Unit, onComplete: () -> Unit) {
        LargeDataHandler.getInstance().getPictureThumbnails { _, isComplete, data ->
            if (data != null && data.isNotEmpty()) onChunk(data)
            if (isComplete) onComplete()
        }
    }

    override fun sendCaptureCommand(onAck: (Int?) -> Unit) {
        val thumbnailSize: Byte = 0x02
        LargeDataHandler.getInstance().glassesControl(
            byteArrayOf(0x02, 0x01, 0x06, thumbnailSize, thumbnailSize, 0x02),
        ) { _, resp ->
            val code = runCatching {
                resp.javaClass.getDeclaredField("errorCode").apply { isAccessible = true }.getInt(resp)
            }.getOrNull()
            Log.i(TAG, "capture ACK errorCode=$code")
            onAck(code)
        }
    }

    override fun listenThumbnails(onChunk: (ByteArray, Boolean) -> Unit) {
        LargeDataHandler.getInstance().getPictureThumbnails { _, isComplete, data ->
            onChunk(data ?: ByteArray(0), isComplete)
        }
    }

    override fun registerPhotoSignalListener(onPhotoSignal: () -> Unit) {
        photoSignalListener = onPhotoSignal
        if (deviceNotifyRegistered) return
        deviceNotifyRegistered = true
        LargeDataHandler.getInstance().addOutDeviceListener(
            100,
            object : GlassesDeviceNotifyListener() {
                override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
                    val data = response.loadData ?: return
                    if (cmdType != 0x73 || data.size <= 6) return
                    val sub = data[6].toInt() and 0xFF
                    when (sub) {
                        0x01, 0x02 -> photoSignalListener?.invoke()
                    }
                }
            },
        )
    }

    override fun unregisterPhotoSignalListener() {
        photoSignalListener = null
    }

    companion object {
        private const val TAG = "OudmonBle"
        private const val SCAN_TIMEOUT_MS = 15_000L
    }
}

```

---

## `app\src\test\java\com\livetranslate\headphones\assistant\VisionIntentTest.kt`

```kotlin
package com.livetranslate.headphones.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionIntentTest {
    @Test
    fun signPhrasesWinOverObject() {
        val kind = VisionIntent.kind("hey what is this sign say translate this sign")
        assertEquals(VisionKind.SignTranslate, kind)
    }

    @Test
    fun objectIdentifyPhrases() {
        assertEquals(VisionKind.ObjectIdentify, VisionIntent.kind("what is this"))
        assertEquals(VisionKind.ObjectIdentify, VisionIntent.kind("what kind of tree is that"))
    }

    @Test
    fun ordinaryQaIsNone() {
        assertEquals(VisionKind.None, VisionIntent.kind("what time is it"))
        assertTrue(!VisionIntent.needsCamera("how's the weather"))
    }
}

```


package com.livetranslate.headphones

import android.content.Context
import com.livetranslate.headphones.audio.BluetoothAudioRouter
import com.livetranslate.headphones.audio.HeadsetState
import com.livetranslate.headphones.audio.LoopbackSession
import com.livetranslate.headphones.audio.PcmAudioCapture
import com.livetranslate.headphones.audio.PcmAudioPlayback
import com.livetranslate.headphones.audio.RoutedTtsPlayer
import com.livetranslate.headphones.translation.ConversationEngine
import com.livetranslate.headphones.translation.LanguageModelManager
import com.livetranslate.headphones.translation.ListenModeEngine
import com.livetranslate.headphones.translation.SupportedLanguages
import com.livetranslate.headphones.translation.TranslationEngine
import com.livetranslate.headphones.translation.TranslationProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TranslationSession(
    context: Context,
    private val languageModelManager: LanguageModelManager,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val audioRouter = BluetoothAudioRouter(appContext)
    private var ttsPlayer: RoutedTtsPlayer? = null
    private var capture: PcmAudioCapture? = null
    private var playback: PcmAudioPlayback? = null
    private var loopback: LoopbackSession? = null
    private var engine: TranslationEngine? = null

    private val _status = MutableStateFlow(SessionStatus())
    val status: StateFlow<SessionStatus> = _status.asStateFlow()

    private val _transcripts = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    val transcripts: StateFlow<List<TranscriptEntry>> = _transcripts.asStateFlow()

    val bluetoothHeadset: StateFlow<HeadsetState> = audioRouter.headset

    fun setMode(mode: AppMode) {
        _status.value = _status.value.copy(mode = mode)
    }

    suspend fun startTranslation() {
        stopLoopback()
        if (!audioRouter.start()) {
            _status.value = _status.value.copy(
                isActive = false,
                scoConnected = false,
                statusMessage = "Connect Shokz via Bluetooth and enable phone audio",
            )
            return
        }

        val downloadedCodes = languageModelManager.downloadedSelectedCodes()
        if (downloadedCodes.isEmpty()) {
            audioRouter.stop()
            _status.value = _status.value.copy(
                isActive = false,
                scoConnected = false,
                modelsReady = false,
                statusMessage = "Download at least one language pack in Settings",
            )
            return
        }

        // English is always the recognizer's starting language (auto-discarded on ID).
        // If the user picked a specific source language, use the reliable "English + one"
        // mode; otherwise fall back to best-effort switching across all downloaded ones.
        val activeSource = languageModelManager.activeSourceOrNull()
            ?.takeIf { downloadedCodes.contains(it) }
        val candidateTags = if (activeSource != null) {
            listOf(SupportedLanguages.ENGLISH_TAG, SupportedLanguages.recognizerTag(activeSource))
        } else {
            (listOf(SupportedLanguages.ENGLISH_TAG) +
                downloadedCodes.map { SupportedLanguages.recognizerTag(it) }).distinct()
        }

        ttsPlayer = RoutedTtsPlayer(appContext, audioRouter)
        scope.launch { ttsPlayer?.warmup() }
        scope.launch {
            val ordered = listOfNotNull(activeSource) + downloadedCodes.filter { it != activeSource }
            languageModelManager.warmup(ordered)
        }
        val processor = TranslationProcessor(
            languageModelManager = languageModelManager,
            selectedSourceLanguage = activeSource,
            onTranscript = { entry ->
                _transcripts.value = (_transcripts.value + entry).takeLast(100)
            },
            onStatus = { message ->
                _status.value = _status.value.copy(statusMessage = message)
            },
        )

        engine = when (_status.value.mode) {
            AppMode.LISTEN -> ListenModeEngine(
                context = appContext,
                processor = processor,
                ttsPlayer = ttsPlayer!!,
                candidateLanguageTags = candidateTags,
                onStatus = { message ->
                    _status.value = _status.value.copy(statusMessage = message)
                },
                onMicLevel = { level ->
                    _status.value = _status.value.copy(micLevel = level)
                },
            )
            AppMode.CONVERSATION -> ConversationEngine(
                context = appContext,
                processor = processor,
                ttsPlayer = ttsPlayer!!,
                candidateLanguageTags = candidateTags,
                onStatus = { message ->
                    _status.value = _status.value.copy(statusMessage = message)
                },
            )
        }

        runCatching { engine?.start() }.onFailure { err ->
            engine = null
            ttsPlayer?.shutdown()
            ttsPlayer = null
            audioRouter.stop()
            _status.value = _status.value.copy(
                isActive = false,
                scoConnected = false,
                statusMessage = err.message ?: "Failed to start translation",
            )
            return
        }

        _status.value = _status.value.copy(
            isActive = true,
            scoConnected = true,
            scoDeviceName = audioRouter.deviceName.value,
            modelsReady = true,
            loopbackActive = false,
            statusMessage = when (_status.value.mode) {
                AppMode.LISTEN -> "Listen mode active — phone can stay in pocket"
                AppMode.CONVERSATION -> "Conversation mode active"
            },
        )
    }

    suspend fun stopTranslation() {
        engine?.stop()
        engine = null
        ttsPlayer?.shutdown()
        ttsPlayer = null
        capture?.stop()
        playback?.stop()
        capture = null
        playback = null
        audioRouter.stop()
        _status.value = SessionStatus(mode = _status.value.mode)
    }

    suspend fun testTts() {
        if (!audioRouter.start()) {
            _status.value = _status.value.copy(
                statusMessage = "Connect Shokz before TTS test",
            )
            return
        }
        val player = ttsPlayer ?: RoutedTtsPlayer(appContext, audioRouter).also { ttsPlayer = it }
        _status.value = _status.value.copy(
            statusMessage = "Playing TTS test in headphones…",
            scoConnected = true,
            scoDeviceName = audioRouter.deviceName.value,
        )
        runCatching {
            player.play("Translation output test. You should hear this in your Shokz headphones.")
        }.onFailure {
            _status.value = _status.value.copy(statusMessage = "TTS test failed: ${it.message}")
        }.onSuccess {
            _status.value = _status.value.copy(statusMessage = "TTS test complete")
        }
        if (!_status.value.isActive) {
            player.shutdown()
            ttsPlayer = null
            audioRouter.stop()
        }
    }

    fun startLoopback(): Boolean {
        scope.launch {
            stopTranslation()
            if (!audioRouter.start()) {
                _status.value = _status.value.copy(
                    loopbackActive = false,
                    statusMessage = "SCO not available for loopback test",
                )
                return@launch
            }
            val scoInput = audioRouter.inputDevice
            val cap = PcmAudioCapture(scoInput = scoInput)
            val play = PcmAudioPlayback(outputDevice = audioRouter.outputDevice ?: scoInput)
            capture = cap
            playback = play
            loopback = LoopbackSession(cap, play, scope)
            val ok = loopback?.start() == true && cap.routedToSco()
            _status.value = _status.value.copy(
                isActive = false,
                loopbackActive = ok,
                scoConnected = ok,
                scoDeviceName = audioRouter.deviceName.value,
                statusMessage = if (ok) {
                    "Loopback test active — speak into Shokz mic"
                } else {
                    "Loopback failed — mic not routed to SCO"
                },
            )
        }
        return true
    }

    fun stopLoopback() {
        loopback?.stop()
        loopback = null
        capture?.stop()
        playback?.stop()
        capture = null
        playback = null
        audioRouter.stop()
        _status.value = _status.value.copy(
            loopbackActive = false,
            scoConnected = false,
            statusMessage = "Loopback stopped",
        )
    }

    fun clearTranscripts() {
        _transcripts.value = emptyList()
    }
}

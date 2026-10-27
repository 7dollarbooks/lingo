package com.livetranslate.headphones

import android.content.Context
import com.livetranslate.headphones.audio.BluetoothAudioRouter
import com.livetranslate.headphones.audio.HeadsetState
import com.livetranslate.headphones.audio.LoopbackSession
import com.livetranslate.headphones.audio.PcmAudioCapture
import com.livetranslate.headphones.audio.PcmAudioPlayback
import com.livetranslate.headphones.audio.RoutedTtsPlayer
import com.livetranslate.headphones.text.TextSink
import com.livetranslate.headphones.text.TextSource
import com.livetranslate.headphones.translation.ConversationEngine
import com.livetranslate.headphones.translation.LanguageModelManager
import com.livetranslate.headphones.translation.ListenModeEngine
import com.livetranslate.headphones.translation.SupportedLanguages
import com.livetranslate.headphones.translation.TranslationEngine
import com.livetranslate.headphones.translation.TranslationProcessor
import com.livetranslate.headphones.translation.captionText
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
    private val textSink: TextSink,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val audioRouter = BluetoothAudioRouter(appContext)
    private var ttsPlayer: RoutedTtsPlayer? = null
    private var routeHeld = false
    private var speechHolds = 0
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
        val conversation = _status.value.mode == AppMode.CONVERSATION
        val routed = if (conversation) {
            if (routeHeld && speechHolds == 0) releaseRouter()
            val ok = audioRouter.startPhone()
            if (ok) routeHeld = true
            ok
        } else {
            ensureShokz()
        }
        if (!routed) {
            _status.value = _status.value.copy(
                isActive = false,
                scoConnected = false,
                statusMessage = if (conversation) {
                    "Phone speaker or mic is not available"
                } else {
                    "Connect Shokz via Bluetooth and enable phone audio"
                },
            )
            return
        }

        val downloadedCodes = languageModelManager.downloadedSelectedCodes()
        if (downloadedCodes.isEmpty()) {
            _status.value = _status.value.copy(
                isActive = false,
                scoConnected = false,
                modelsReady = false,
                statusMessage = "Download at least one language pack in Settings",
            )
            releaseRouter()
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

        if (ttsPlayer == null) {
            ttsPlayer = RoutedTtsPlayer(appContext, audioRouter)
            scope.launch { ttsPlayer?.warmup() }
        }
        scope.launch {
            val ordered = listOfNotNull(activeSource) + downloadedCodes.filter { it != activeSource }
            languageModelManager.warmup(ordered)
        }
        val processor = TranslationProcessor(
            languageModelManager = languageModelManager,
            selectedSourceLanguage = activeSource,
            onTranscript = { entry ->
                _transcripts.value = (_transcripts.value + entry).takeLast(100)
                val caption = captionText(entry)
                if (caption != null) {
                    val listen = _status.value.mode == AppMode.LISTEN
                    textSink.publish(TextSource.CAPTION, entry.speakerLabel, caption, announce = listen)
                }
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
            _status.value = _status.value.copy(
                isActive = false,
                scoConnected = false,
                statusMessage = err.message ?: "Failed to start translation",
            )
            releaseRouter()
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
                AppMode.CONVERSATION -> "Conversation mode — phone speaker and mic"
            },
        )
    }

    suspend fun stopTranslation() {
        engine?.stop()
        engine = null
        capture?.stop()
        playback?.stop()
        capture = null
        playback = null
        _status.value = SessionStatus(mode = _status.value.mode)
        releaseRouter()
    }

    /** Navigation holds the Shokz speaker so a turn can be spoken while captions stay quiet. */
    suspend fun acquireSpeech(): Boolean {
        if (!ensureShokz()) return false
        if (ttsPlayer == null) {
            val player = RoutedTtsPlayer(appContext, audioRouter)
            ttsPlayer = player
            player.warmup()
        }
        speechHolds++
        return true
    }

    fun releaseSpeech() {
        speechHolds = (speechHolds - 1).coerceAtLeast(0)
        releaseRouter()
    }

    /** Read a line on the Shokz music path. Used when that line is written to the watch. */
    fun announceOnShokz(text: String) {
        val spoken = "Watch. ${text.trim().replace('-', ' ')}"
        scope.launch {
            val cold = !routeHeld && speechHolds == 0
            if (cold && audioRouter.activateMedia() == null) return@launch
            val player = ttsPlayer ?: RoutedTtsPlayer(appContext, audioRouter).also { ttsPlayer = it }
            audioRouter.withMediaFocus { player.playClearly(spoken) }
            if (cold && !_status.value.isActive && speechHolds == 0) {
                player.shutdown()
                if (ttsPlayer === player) ttsPlayer = null
            }
        }
    }

    suspend fun speakLine(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (ttsPlayer == null) {
            if (!acquireSpeech()) return
            ttsPlayer?.speak(clean)
            releaseSpeech()
            return
        }
        ttsPlayer?.speak(clean)
    }

    suspend fun testTts() {
        if (!ensureShokz()) {
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
        if (!_status.value.isActive && speechHolds == 0) {
            player.shutdown()
            ttsPlayer = null
            releaseRouter()
        }
    }

    fun startLoopback(): Boolean {
        scope.launch {
            stopTranslation()
            if (!ensureShokz()) {
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
        _status.value = _status.value.copy(
            loopbackActive = false,
            scoConnected = false,
            statusMessage = "Loopback stopped",
        )
        releaseRouter()
    }

    fun clearTranscripts() {
        _transcripts.value = emptyList()
        textSink.clear(TextSource.CAPTION)
    }

    private suspend fun ensureShokz(): Boolean {
        if (routeHeld) return true
        val ok = audioRouter.start()
        routeHeld = ok
        return ok
    }

    private fun releaseRouter() {
        if (_status.value.isActive || _status.value.loopbackActive || speechHolds > 0) return
        ttsPlayer?.shutdown()
        ttsPlayer = null
        if (!routeHeld) return
        audioRouter.stop()
        routeHeld = false
    }
}

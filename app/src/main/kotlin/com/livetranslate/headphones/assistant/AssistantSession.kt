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
import kotlinx.coroutines.channels.Channel
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
        onAnnounce = { message ->
            sessionScope.launch { speakAndNote(message) }
        },
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
            statusMessage = if (spitOn) "SPIT — listening for questions…" else "Listening for \"Hey Lingo\"…",
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
                    assistantSettings.spitModeEnabled.value -> "SPIT — listening for questions…"
                    else -> "Listening for \"Hey Lingo\"…"
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
     * Atomic vision turn: fresh capture → pipeline → TTS + publish [visionResult].
     * @return spoken answer string
     */
    suspend fun runVisionTurn(kind: VisionKind, query: String): String {
        lastVisionKind = kind
        lastVisionQuery = query
        halfDuplex.beginVisionHold()
        _status.value = _status.value.copy(statusMessage = "Looking…")
        val capture = captureStill(query)
        forcePhoneCapture = false
        if (capture == null) {
            val msg = "I couldn't get a photo from the glasses."
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
        _status.value = _status.value.copy(statusMessage = "Thinking…")
        val updates = Channel<String>(Channel.CONFLATED)
        var spokenThrough = 0
        val speaker = sessionScope.launch {
            for (partial in updates) {
                publishVisionResult(
                    VisionResult.ObjectIdentify(
                        still = still,
                        answer = partial,
                        provenance = capture.provenance,
                        fallbackReason = capture.fallbackReason,
                        query = query,
                    ),
                )
                spokenThrough = speakCompleteSentences(partial, spokenThrough)
            }
        }
        val outcome = try {
            runCatching {
                llmClient.askWithImage(
                    prompt = query.ifBlank { SignTranslatePipeline.OBJECT_IDENTIFY_PROMPT },
                    jpegBytes = capture.jpegBytes,
                    systemInstruction = OBJECT_SYSTEM,
                    maxOutputTokens = 48,
                    onText = { partial -> updates.trySend(partial) },
                )
            }
        } finally {
            updates.close()
            speaker.join()
        }
        val offline = outcome.isFailure
        val answer = if (offline) {
            SignTranslatePipeline.GEMINI_OFFLINE_MESSAGE
        } else {
            outcome.getOrThrow()
        }
        if (!offline) {
            val tail = answer.drop(spokenThrough).trim()
            if (tail.isNotEmpty()) speakAndNote(tail)
        } else {
            speakAndNote(answer)
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
        recordExchange(query, answer, capture)
        return answer
    }

    private suspend fun runSignPath(
        still: StillFile,
        capture: VisionCaptureResult,
        query: String,
    ): String {
        _status.value = _status.value.copy(statusMessage = "Reading sign…")
        // Emit downloading state so UI can show while pack fetches.
        val downloadingPlaceholder = VisionResult.SignTranslate(
            still = still,
            answer = "Downloading translation model…",
            provenance = capture.provenance,
            fallbackReason = capture.fallbackReason,
            query = query,
            downloadingModel = true,
        )
        var showedDownload = false
        val updates = Channel<String>(Channel.CONFLATED)
        var spokenThrough = 0
        val speaker = sessionScope.launch {
            for (partial in updates) {
                publishVisionResult(
                    VisionResult.SignTranslate(
                        still = still,
                        answer = partial,
                        provenance = capture.provenance,
                        fallbackReason = capture.fallbackReason,
                        query = query,
                    ),
                )
                spokenThrough = speakCompleteSentences(partial, spokenThrough)
            }
        }
        val outcome = try {
            signPipeline.run(
                jpegBytes = capture.jpegBytes,
                provenance = capture.provenance,
                onDownloading = {
                    showedDownload = true
                    publishVisionResult(downloadingPlaceholder, speak = false)
                },
                onGeminiText = { partial -> updates.trySend(partial) },
            )
        } finally {
            updates.close()
            speaker.join()
        }
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
            // stay on download UI — shouldn't happen; fall through
        }
        publishVisionResult(result)
        val streamed = outcome is SignPipelineOutcome.GeminiFallback && !outcome.geminiOffline
        if (streamed) {
            val tail = result.answer.drop(spokenThrough).trim()
            if (tail.isNotEmpty()) speakAndNote(tail)
        } else {
            speakAndNote(result.answer)
        }
        recordExchange(query, result.answer, capture)
        return result.answer
    }

    /** Gemini offline retry — reuses cached still, does not re-capture. */
    suspend fun retryVisionGemini() {
        val current = _visionResult.value ?: lastVisionResult ?: return
        val bytes = stillFileCache.readBytes(current.still) ?: return
        _status.value = _status.value.copy(statusMessage = "Retrying…")
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
                    askImageSpeaking(
                        prompt = SignTranslatePipeline.SIGN_GEMINI_PROMPT,
                        jpegBytes = bytes,
                        systemInstruction = SIGN_SYSTEM,
                    ) { partial ->
                        publishVisionResult(
                            current.copy(
                                answer = partial,
                                glassesZeroOcr = false,
                                downloadingModel = false,
                            ),
                        )
                    }
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
                if (offline) speakAndNote(answer)
            }
        }
    }

    /** Secondary affordance after glasses zero-OCR: Sign Gemini without re-capture. */
    suspend fun acceptSignGeminiFallback() {
        val current = _visionResult.value as? VisionResult.SignTranslate ?: return
        if (!current.glassesZeroOcr) return
        val bytes = stillFileCache.readBytes(current.still) ?: return
        val outcome = runCatching {
            askImageSpeaking(
                prompt = SignTranslatePipeline.SIGN_GEMINI_PROMPT,
                jpegBytes = bytes,
                systemInstruction = SIGN_SYSTEM,
            ) { partial ->
                publishVisionResult(
                    current.copy(
                        answer = partial,
                        glassesZeroOcr = false,
                        banner = SignTranslatePipeline.BANNER_SCRIPT_UNSUPPORTED,
                    ),
                )
            }
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
        if (offline) speakAndNote(answer)
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

    /** Speak finished sentences as a vision reply arrives, then any leftover tail. */
    private suspend fun askImageSpeaking(
        prompt: String,
        jpegBytes: ByteArray,
        systemInstruction: String,
        maxOutputTokens: Int = 120,
        publish: (String) -> Unit,
    ): String {
        val updates = Channel<String>(Channel.CONFLATED)
        var spokenThrough = 0
        val speaker = sessionScope.launch {
            for (partial in updates) {
                publish(partial)
                spokenThrough = speakCompleteSentences(partial, spokenThrough)
            }
        }
        val answer = try {
            llmClient.askWithImage(
                prompt = prompt,
                jpegBytes = jpegBytes,
                systemInstruction = systemInstruction,
                maxOutputTokens = maxOutputTokens,
                onText = { updates.trySend(it) },
            )
        } finally {
            updates.close()
            speaker.join()
        }
        val tail = answer.drop(spokenThrough).trim()
        if (tail.isNotEmpty()) speakAndNote(tail)
        return answer
    }

    /** Speak each finished sentence in [full] that starts at [alreadySpoken]. */
    private suspend fun speakCompleteSentences(full: String, alreadySpoken: Int): Int {
        var cursor = alreadySpoken
        while (cursor < full.length) {
            val end = (cursor until full.length).firstOrNull { i ->
                full[i] == '.' || full[i] == '!' || full[i] == '?'
            } ?: break
            val sentence = full.substring(cursor, end + 1).trim()
            cursor = end + 1
            if (sentence.isNotEmpty()) speakAndNote(sentence)
        }
        return cursor
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
            "You identify objects in photos for a voice headset. Reply in English in one short " +
                "spoken sentence, about fifteen words. Be specific when the image allows; say unsure " +
                "if unsure. Do not invent fine print."

        private const val SIGN_SYSTEM =
            "You extract and translate text from photos. Reply in English only. " +
                "Do not describe scenes or objects — only text content and translation."
    }
}

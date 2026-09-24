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
 * recognizer — the assistant already needs network for Gemini, and online English STT
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

    /** Fires only if the recognizer session actually ended. A live session keeps cancelling it. */
    private val sessionEndedRunnable = Runnable { if (active) startListening() }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.i(TAG, "onReadyForSpeech")
            noteSessionAlive()
            lastPartial = ""
            spitStablePartial = ""
            mainHandler.removeCallbacks(spitCommitRunnable)
            wakeHandledForUtterance = false
            onListening(true)
        }

        override fun onBeginningOfSpeech() {
            Log.i(TAG, "onBeginningOfSpeech")
            noteSessionAlive()
        }

        override fun onRmsChanged(rmsdB: Float) {
            noteSessionAlive()
        }
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
            mainHandler.removeCallbacks(sessionEndedRunnable)
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
            if (!halfDuplex.isBusy()) scheduleRestartIfSessionEnded()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            noteSessionAlive()
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
            if (text.isNullOrBlank()) return
            lastPartial = text
            Log.i(TAG, "onPartial: '$text'")
            if (wakeHandledForUtterance || halfDuplex.isBusy()) return

            if (assistantSettings.spitModeEnabled.value) {
                // Commit only after the transcript stops changing — lets the full question
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
                    wakeHandledForUtterance = true
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
        onStatus(if (isSpit) "SPIT — listening for questions…" else "Listening for \"Hey Lingo\"…")
        muteRecognizerBeep()
        startListening()
        startProactiveLoop()
        // Warm TLS to Gemini on the home/cellular route so the first question is snappy.
        scope.launch { runCatching { llmClient.warmup() } }
    }

    suspend fun stop() = withContext(Dispatchers.Main) {
        active = false
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.removeCallbacks(sessionEndedRunnable)
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
     * The platform SpeechRecognizer plays a beep each time listening starts, and there is
     * no API to turn that beep off. On this phone the beep is com.google.android.tts on
     * the notification stream, about every 5 seconds. That stream stays muted while the
     * assistant is on. Lingo's own voice uses the music stream and is unmuted per reply.
     */
    private var mutedNotificationForBeep = false

    private fun muteRecognizerBeep() {
        runCatching { audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0) }
            .onFailure { Log.w(TAG, "couldn't mute recognizer beep", it) }
        // Measured on this phone: com.google.android.tts plays the listen beep as
        // USAGE_NOTIFICATION_EVENT into the Shokz each time startListening runs.
        if (!audioManager.isStreamMute(AudioManager.STREAM_NOTIFICATION)) {
            runCatching {
                audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
                mutedNotificationForBeep = true
            }.onFailure { Log.w(TAG, "couldn't mute notification beep", it) }
        }
    }

    private fun unmuteRecognizerBeep() {
        runCatching { audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0) }
            .onFailure { Log.w(TAG, "couldn't unmute recognizer beep", it) }
        if (mutedNotificationForBeep) {
            runCatching { audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0) }
            mutedNotificationForBeep = false
        }
    }

    private fun noteSessionAlive() {
        mainHandler.removeCallbacks(sessionEndedRunnable)
    }

    private fun scheduleRestartIfSessionEnded() {
        if (!active || assistantSettings.spitModeEnabled.value) {
            if (!halfDuplex.isBusy()) restartListening(0)
            return
        }
        mainHandler.removeCallbacks(sessionEndedRunnable)
        mainHandler.postDelayed(sessionEndedRunnable, SESSION_RESTART_IF_ENDED_MS)
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
            // One long session while idle. A short silence end restarts recognition and
            // the platform plays a beep on every start.
            val spit = assistantSettings.spitModeEnabled.value
            if (!spit) {
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, true)
            }
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                if (spit) 1_100L else 25_000L,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                if (spit) 850L else 20_000L,
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
                Log.i(TAG, "half-duplex busy — dropping '$text'")
                return
            }
            HalfDuplexPolicy.DropReason.SpitRepeat -> {
                Log.i(TAG, "SPIT: detected user repeat — ignoring")
                onStatus("SPIT — listening for questions…")
                return
            }
            HalfDuplexPolicy.DropReason.Echo -> {
                Log.i(TAG, "echo filter — ignoring '$text'")
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
            onStatus("Yes? Listening…")
            scope.launch {
                runCatching { ttsPlayer.speak("Yes?") }
                halfDuplex.noteSpoken("Yes?")
            }
        }
    }

    private fun handleSpitUtterance(text: String) {
        Log.i(TAG, "SPIT utterance: '$text' busy=${halfDuplex.isBusy()} awaitRepeat=${halfDuplex.spitRepeatArmed()}")
        if (halfDuplex.spitRepeatArmed()) {
            // Non-repeat while armed — clear and continue.
            halfDuplex.clearSpitRepeat()
            spitRepeatTimeoutJob?.cancel()
            spitRepeatTimeoutJob = null
        }
        if (!QuestionDetector.isQuestion(text)) {
            Log.i(TAG, "SPIT: statement — ignoring '$text'")
            onStatus("SPIT — listening for questions…")
            return
        }
        Log.i(TAG, "SPIT: question detected '$text'")
        handleQuery(text, isSpit = true)
    }

    private fun handleQuery(query: String, isSpit: Boolean = false) {
        halfDuplex.beginResponse()
        scope.launch {
            onStatus("Thinking…")
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
                    onStatus("Looking…")
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
                    onStatus("Ignore my repeat…")
                    spitRepeatTimeoutJob?.cancel()
                    spitRepeatTimeoutJob = scope.launch {
                        delay(SPIT_REPEAT_WINDOW_MS)
                        if (halfDuplex.spitRepeatArmed()) {
                            Log.i(TAG, "SPIT: repeat-ignore window expired")
                            halfDuplex.clearSpitRepeat()
                            if (active) onStatus("SPIT — listening for questions…")
                        }
                    }
                } else {
                    onStatus("Listening for \"Hey Lingo\"…")
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
                if (isSpit) onStatus("SPIT — listening for questions…")
            }
            halfDuplex.clearVisionHold()
            halfDuplex.endResponse()
            if (active) {
                withContext(Dispatchers.Main) { scheduleRestartIfSessionEnded() }
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
            AssistanceLevel.MAXIMUM -> "Be very proactive — offer relevant info, tips, or " +
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
        private const val SESSION_RESTART_IF_ENDED_MS = 1_500L
        private const val NO_RESPONSE_SENTINEL = "NO_RESPONSE"
        private const val SPIT_REPEAT_WINDOW_MS = 10_000L
        /** How long a SPIT partial must stay unchanged before we treat the question as done. */
        private const val SPIT_STABLE_MS = 420L
        const val SYSTEM_PROMPT = "You are Lingo, a concise voice assistant in a Bluetooth headset. " +
            "Reply in one short spoken sentence whenever possible — two max. No markdown, lists, or preamble. " +
            "Never restate or repeat the user's question — say only the answer. " +
            "For time or date questions, read the device clock from your system instructions exactly."
        const val SPIT_SYSTEM_PROMPT = "You are Lingo in SPIT (Smartest Person In Town) mode. " +
            "State ONLY the answer in ONE short sentence. Do not restate, echo, or paraphrase the question. " +
            "No preamble (never start with \"The answer is\" or \"X is…\" when X is the question topic repeated). " +
            "No lists, no markdown. Be direct. For time or date questions, read the device clock exactly."
    }
}

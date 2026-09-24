package com.livetranslate.headphones.translation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.livetranslate.headphones.TranscriptEntry
import com.livetranslate.headphones.audio.RoutedTtsPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ConversationEngine(
    private val context: Context,
    private val processor: TranslationProcessor,
    private val ttsPlayer: RoutedTtsPlayer,
    private val candidateLanguageTags: List<String>,
    private val onStatus: (String) -> Unit,
) : TranslationEngine {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pipeline: RecognitionPipeline? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var active = false
    private var plan: RecognizerPlan? = null
    private var listeningStarted = false

    private val restartRunnable = Runnable {
        if (active) startListening()
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.i(TAG, "onReadyForSpeech")
            onStatus("Conversation — listening…")
        }

        override fun onBeginningOfSpeech() {
            Log.i(TAG, "onBeginningOfSpeech")
        }

        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            Log.i(TAG, "onEndOfSpeech")
            onStatus("Processing speech…")
        }

        override fun onError(error: Int) {
            Log.w(TAG, "onError: ${speechErrorLabel(error)} ($error)")
            if (!active) return
            pipeline?.flushPending()
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                restartListening(0)
                return
            }
            onStatus("Recognizer retrying (${speechErrorLabel(error)})")
            restartListening(50)
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
            val detected = results?.getString("android.speech.extra.LANGUAGE")
            Log.i(TAG, "onResults: '${text ?: "<null>"}' recognizerLang=${detected ?: "?"}")
            if (!active) return
            if (!text.isNullOrBlank()) pipeline?.onFinal(text)
            restartListening(0)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
            if (!text.isNullOrBlank()) pipeline?.onPartial(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    override suspend fun start() = withContext(Dispatchers.Main) {
        if (active) return@withContext
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            throw IllegalStateException("Speech recognition is not available")
        }
        val onDevice = SpeechSupport.onDeviceAvailable(context)
        speechRecognizer = SpeechSupport.createRecognizer(context, onDevice).apply {
            setRecognitionListener(recognitionListener)
        }
        pipeline = RecognitionPipeline(processor, ttsPlayer, speakerLabel = "Speaker")
        active = true
        listeningStarted = false
        SpeechSupport.buildPlan(context, candidateLanguageTags) { p ->
            plan = p
            if (active && !listeningStarted) {
                listeningStarted = true
                startListening()
            }
        }
    }

    override suspend fun stop() = withContext(Dispatchers.Main) {
        active = false
        listeningStarted = false
        mainHandler.removeCallbacks(restartRunnable)
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        pipeline?.close()
        pipeline = null
        onStatus("Stopped")
    }

    override suspend fun processSegment(pcm: ShortArray, speakerLabel: String): TranscriptEntry? = null

    private fun startListening() {
        val recognizer = speechRecognizer ?: return
        val p = plan
        val primary = p?.primary ?: candidateLanguageTags.firstOrNull() ?: SupportedLanguages.ENGLISH_TAG
        val allowed = p?.allowed ?: candidateLanguageTags
        Log.i(TAG, "startListening primary=$primary allowed=$allowed onDevice=${p?.onDevice}")
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, primary)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, primary)
            putExtra(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES, ArrayList(allowed))
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, ArrayList(allowed))
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
            if (p?.onDevice == true) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 450L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 300L)
        }
        recognizer.startListening(intent)
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
        private const val TAG = "ConvEngine"
    }
}

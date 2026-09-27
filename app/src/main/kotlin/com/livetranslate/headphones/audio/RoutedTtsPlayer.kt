package com.livetranslate.headphones.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class TtsVoiceStyle {
    /** Prefer high-quality networked English voices that sound natural (usually female). */
    NATURAL_FEMALE,
    /** Prefer high-quality networked English voices that sound natural (usually male). */
    NATURAL_MALE,
    /** Leave whatever the system default is. */
    SYSTEM,
}

class RoutedTtsPlayer(
    private val context: Context,
    private val audioRouter: BluetoothAudioRouter,
    private var voiceStyle: TtsVoiceStyle = TtsVoiceStyle.NATURAL_FEMALE,
) {
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private val ttsMutex = Mutex()
    private var appliedVoiceName: String? = null

    fun setVoiceStyle(style: TtsVoiceStyle) {
        voiceStyle = style
        // Force re-apply on next ensureTts / speak.
        appliedVoiceName = null
        textToSpeech?.let { applyVoice(it) }
    }

    /**
     * Low-latency path for the assistant: speaks through the live TTS engine (no WAV
     * round-trip) as media, so it follows the Shokz A2DP route.
     */
    suspend fun speak(text: String) = ttsMutex.withLock {
        withContext(Dispatchers.Main) {
            ensureTts()
            if (!ttsReady) return@withContext
            withMusicAudible {
            textToSpeech?.setAudioAttributes(mediaAttributes())
            val utteranceId = UUID.randomUUID().toString()
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            }
            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) = Unit

                        override fun onDone(id: String?) {
                            if (id == utteranceId && cont.isActive) {
                                cont.resume(Unit) {}
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) {
                            if (id == utteranceId && cont.isActive) {
                                cont.resumeWithException(IllegalStateException("TTS speak failed"))
                            }
                        }

                        override fun onError(id: String?, errorCode: Int) {
                            if (id == utteranceId && cont.isActive) {
                                cont.resumeWithException(IllegalStateException("TTS speak failed ($errorCode)"))
                            }
                        }
                    })
                    cont.invokeOnCancellation {
                        textToSpeech?.stop()
                    }
                    val result = textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
                    if (result != TextToSpeech.SUCCESS && cont.isActive) {
                        cont.resumeWithException(IllegalStateException("TTS speak returned $result"))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "speak failed for '$text'", e)
            }
            }
        }
    }

    /**
     * Plays [text] via synthesize-to-file then MediaPlayer with an explicit preferred
     * Bluetooth device. Used by the translation pipeline where routing must be precise.
     */
    suspend fun play(text: String) {
        val file = synthesizeToFile(text) ?: return
        try {
            playPrepared(file)
        } finally {
            file.delete()
        }
    }

    /**
     * Ordered playback with one-file prefetch: while utterance N plays, utterance N+1
     * synthesizes in parallel so dead air between chunks shrinks.
     */
    suspend fun playQueue(incoming: ReceiveChannel<String>) = withContext(Dispatchers.IO) {
        val ready = kotlinx.coroutines.channels.Channel<File>(capacity = 1)
        val synthJob = launch {
            for (text in incoming) {
                val file = synthesizeToFile(text)
                if (file != null) {
                    ready.send(file)
                }
            }
            ready.close()
        }
        try {
            for (file in ready) {
                try {
                    playPrepared(file)
                } finally {
                    file.delete()
                }
            }
        } finally {
            synthJob.cancel()
            while (true) {
                val leftover = ready.tryReceive().getOrNull() ?: break
                leftover.delete()
            }
        }
    }

    /** Warm the engine so the first assistant reply doesn't pay init cost. */
    suspend fun warmup() = ttsMutex.withLock {
        withContext(Dispatchers.Main) { ensureTts() }
    }

    fun shutdown() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        ttsReady = false
        appliedVoiceName = null
    }

    private suspend fun synthesizeToFile(text: String): File? = ttsMutex.withLock {
        withContext(Dispatchers.Main) {
            ensureTts()
            if (!ttsReady) return@withContext null
            val file = File(context.cacheDir, "tts_${UUID.randomUUID()}.wav")
            val utteranceId = UUID.randomUUID().toString()
            val params = Bundle()
            try {
                suspendCancellableCoroutine { cont ->
                    cont.invokeOnCancellation { file.delete() }
                    textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) = Unit
                        override fun onDone(id: String?) {
                            if (id == utteranceId) cont.resume(Unit)
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) {
                            if (id == utteranceId) {
                                cont.resumeWithException(IllegalStateException("TTS synthesis failed"))
                            }
                        }
                    })
                    val result = textToSpeech?.synthesizeToFile(text, params, file, utteranceId)
                    if (result != TextToSpeech.SUCCESS) {
                        cont.resumeWithException(IllegalStateException("TTS synthesizeToFile returned $result"))
                    }
                }
                if (!file.exists() || file.length() == 0L) {
                    file.delete()
                    null
                } else {
                    file
                }
            } catch (e: CancellationException) {
                file.delete()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "synthesize failed for '$text'", e)
                file.delete()
                null
            }
        }
    }

    private suspend fun playPrepared(file: File) {
        if (audioRouter.phoneRoute) {
            val speaker = audioRouter.outputDevice
            if (speaker == null || !playFileOnDevice(file, speaker)) {
                Log.w(TAG, "Phone speaker playback failed")
            }
            return
        }
        val media = audioRouter.outputDevice ?: audioRouter.fallbackA2dpDevice ?: audioRouter.selectA2dpOutput()
        val played = media?.let { playFileOnDevice(file, it) } ?: false
        if (!played) {
            playFileOnDevice(file, null)
        }
    }

    private suspend fun playFileOnDevice(file: File, device: AudioDeviceInfo?): Boolean =
        withMusicAudible {
        withContext(Dispatchers.IO) {
            if (!file.exists() || file.length() == 0L) return@withContext false
            suspendCancellableCoroutine { cont ->
                try {
                    val player = MediaPlayer()
                    player.setAudioAttributes(mediaAttributes())
                    if (device != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        player.setPreferredDevice(device)
                    }
                    player.setVolume(1f, 1f)
                    player.setDataSource(file.absolutePath)
                    player.setOnCompletionListener {
                        it.release()
                        cont.resume(true)
                    }
                    player.setOnErrorListener { mp, _, _ ->
                        mp.release()
                        cont.resume(false)
                        true
                    }
                    player.prepare()
                    player.start()
                    cont.invokeOnCancellation { player.release() }
                } catch (_: Exception) {
                    cont.resume(false)
                }
            }
        }
        }

    /**
     * The assistant mutes [AudioManager.STREAM_MUSIC] to hide the recognizer beep.
     * Lingo's voice uses that stream, so lift the mute for the utterance and restore it.
     */
    private suspend fun <T> withMusicAudible(block: suspend () -> T): T {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val wasMuted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
        if (wasMuted) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            Log.i(TAG, "music stream unmuted for Lingo playback")
        }
        return try {
            block()
        } finally {
            if (wasMuted) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            }
        }
    }

    private suspend fun ensureTts() = suspendCancellableCoroutine { cont ->
        if (textToSpeech != null && ttsReady) {
            applyVoice(textToSpeech!!)
            cont.resume(Unit)
            return@suspendCancellableCoroutine
        }
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val tts = textToSpeech
                tts?.setAudioAttributes(mediaAttributes())
                tts?.language = Locale.US
                tts?.setSpeechRate(1.08f)
                tts?.setPitch(1.0f)
                if (tts != null) applyVoice(tts)
                ttsReady = true
            }
            cont.resume(Unit)
        }
    }

    private fun applyVoice(tts: TextToSpeech) {
        if (voiceStyle == TtsVoiceStyle.SYSTEM) {
            if (appliedVoiceName != "system") {
                Log.i(TAG, "using system default voice")
                appliedVoiceName = "system"
            }
            return
        }
        val preferFemale = voiceStyle == TtsVoiceStyle.NATURAL_FEMALE
        val chosen = pickBestEnglishVoice(tts.voices.orEmpty(), preferFemale)
        if (chosen != null && chosen.name != appliedVoiceName) {
            val ok = tts.setVoice(chosen)
            Log.i(
                TAG,
                "setVoice=${chosen.name} quality=${chosen.quality} network=${!chosen.isNetworkConnectionRequired} result=$ok",
            )
            if (ok == TextToSpeech.SUCCESS) appliedVoiceName = chosen.name
        }
    }

    /**
     * Prefer on-device English voices (matching the requested gender when the voice name
     * hints at it). On-device voices synthesize instantly; network-required voices need a
     * round-trip to Google's TTS servers *after* the Gemini call already used one, which is
     * a major source of perceived latency for a real-time voice assistant — so quality/
     * network preference is intentionally secondary to "runs locally" here.
     */
    private fun pickBestEnglishVoice(voices: Set<Voice>, preferFemale: Boolean): Voice? {
        val english = voices.filter {
            it.locale.language.equals("en", ignoreCase = true) &&
                (it.locale.country.isEmpty() ||
                    it.locale.country.equals("US", true) ||
                    it.locale.country.equals("GB", true) ||
                    it.locale.country.equals("AU", true) ||
                    it.locale.country.equals("CA", true))
        }
        if (english.isEmpty()) return null

        fun genderScore(v: Voice): Int {
            val n = v.name.lowercase()
            val femaleHints = listOf(
                "female", "fema", "woman", "zira", "samantha", "karen", "moira", "tessa",
                "fiona", "serena", "eva", "allison", "amy", "emma", "joanna", "salli",
                "ivy", "kimberly", "kendra", "nicole", "raveena", "cathy", "susan",
            )
            val maleHints = listOf(
                "male", "man", "david", "mark", "daniel", "tom", "alex", "fred", "bruce",
                "arthur", "brian", "matthew", "justin", "joey", "kevin", "stephen",
            )
            val isFemale = femaleHints.any { n.contains(it) }
            val isMale = maleHints.any { n.contains(it) }
            return when {
                preferFemale && isFemale -> 2
                !preferFemale && isMale -> 2
                preferFemale && isMale -> -2
                !preferFemale && isFemale -> -2
                else -> 0
            }
        }

        // Prefer: matching gender, then on-device (no network round-trip), then quality,
        // then lower latency.
        return english.sortedWith(
            compareByDescending<Voice> { genderScore(it) }
                .thenBy { if (it.isNetworkConnectionRequired) 1 else 0 }
                .thenByDescending { it.quality }
                .thenBy { it.latency },
        ).firstOrNull()
    }

    companion object {
        private const val TAG = "RoutedTts"

        private fun mediaAttributes(): AudioAttributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
    }
}

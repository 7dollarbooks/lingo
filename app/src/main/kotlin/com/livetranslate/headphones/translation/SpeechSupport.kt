package com.livetranslate.headphones.translation

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.ModelDownloadListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Plan describing how to configure the recognizer for this session.
 *
 * [primary] is the language the recognizer starts in and [allowed] is the set it may
 * automatically switch between. Both only contain languages whose on-device speech
 * models are installed, so the recognizer never reports "language unavailable".
 */
data class RecognizerPlan(
    val primary: String,
    val allowed: List<String>,
    val onDevice: Boolean,
)

/**
 * Helpers for the Android on-device speech recognizer.
 *
 * Automatic multi-language switching (EXTRA_ENABLE_LANGUAGE_SWITCH) only works with the
 * on-device recognizer, across languages whose on-device (SODA) speech models are
 * installed. This object reports what's installed, downloads the missing candidate
 * languages, and emits an updated [RecognizerPlan] as soon as each download completes so
 * new languages become active without restarting the session.
 */
object SpeechSupport {
    private const val TAG = "SpeechSupport"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var probeRecognizer: SpeechRecognizer? = null

    // Speech models we have successfully downloaded during this process. Used to expand
    // the allowed set immediately, even if checkRecognitionSupport lags behind.
    private val downloadedTags = mutableSetOf<String>()

    // Retained so a download completing can re-emit an updated plan to the live session.
    private var replanDesired: List<String> = emptyList()
    private var replanOnPlan: ((RecognizerPlan) -> Unit)? = null
    private var lastPrimary: String = "en-US"
    private var lastAllowed: List<String> = emptyList()

    fun onDeviceAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun createRecognizer(context: Context, onDevice: Boolean): SpeechRecognizer =
        if (onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    private fun base(tag: String) = tag.substringBefore('-').lowercase()

    private fun match(desired: String, pool: Collection<String>): String? =
        pool.firstOrNull { it.equals(desired, true) } ?: pool.firstOrNull { base(it) == base(desired) }

    /**
     * Builds a [RecognizerPlan] for the [desired] languages. Must be called on the main
     * thread; [onPlan] is invoked on the main thread once support has been queried and
     * again whenever a pending model download completes.
     */
    fun buildPlan(context: Context, desired: List<String>, onPlan: (RecognizerPlan) -> Unit) {
        replanDesired = desired
        replanOnPlan = onPlan

        if (!onDeviceAvailable(context)) {
            Log.w(TAG, "on-device unavailable; default recognizer, no language switching")
            onPlan(RecognizerPlan(desired.firstOrNull() ?: "en-US", desired, onDevice = false))
            return
        }

        runCatching { probeRecognizer?.destroy() }
        val probe = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        probeRecognizer = probe

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        }
        probe.checkRecognitionSupport(
            intent,
            context.mainExecutor,
            object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    val installed = support.installedOnDeviceLanguages
                    val supported = support.supportedOnDeviceLanguages
                    Log.i(TAG, "installed=$installed")
                    Log.i(TAG, "pending=${support.pendingOnDeviceLanguages}")
                    Log.i(TAG, "supported=$supported")

                    val pool = installed + downloadedTags
                    val allowed = desired.mapNotNull { match(it, pool) }.distinct()

                    // Download any desired language that's supported but not yet installed.
                    desired.forEach { d ->
                        if (match(d, pool) == null) {
                            val dlTag = match(d, supported)
                            if (dlTag != null) {
                                Log.i(TAG, "downloading speech model $dlTag (for $d)")
                                downloadModel(context, probe, dlTag)
                            } else {
                                Log.w(TAG, "no on-device speech model for $d")
                            }
                        }
                    }

                    val primary = allowed.firstOrNull { base(it) == "en" }
                        ?: allowed.firstOrNull()
                        ?: installed.firstOrNull()
                        ?: desired.firstOrNull()
                        ?: "en-US"
                    val finalAllowed = allowed.ifEmpty { listOf(primary) }.distinct()
                    lastPrimary = primary
                    lastAllowed = finalAllowed
                    Log.i(TAG, "PLAN primary=$primary allowed=$finalAllowed")
                    onPlan(RecognizerPlan(primary, finalAllowed, onDevice = true))
                }

                override fun onError(error: Int) {
                    Log.w(TAG, "checkRecognitionSupport onError=$error")
                    onPlan(RecognizerPlan(desired.firstOrNull() ?: "en-US", desired, onDevice = true))
                }
            },
        )
    }

    private fun downloadModel(context: Context, recognizer: SpeechRecognizer, tag: String) {
        val dl = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                recognizer.triggerModelDownload(
                    dl,
                    context.mainExecutor,
                    object : ModelDownloadListener {
                        override fun onProgress(completedPercent: Int) {
                            Log.i(TAG, "download $tag $completedPercent%")
                        }

                        override fun onSuccess() {
                            Log.i(TAG, "download $tag SUCCESS")
                            onModelInstalled(tag)
                        }

                        override fun onScheduled() {
                            Log.i(TAG, "download $tag scheduled")
                        }

                        override fun onError(error: Int) {
                            Log.w(TAG, "download $tag error=$error")
                        }
                    },
                )
            } else {
                @Suppress("DEPRECATION")
                recognizer.triggerModelDownload(dl)
            }
        }.onFailure { Log.e(TAG, "triggerModelDownload failed for $tag", it) }
    }

    // A model finished downloading: fold it into the allowed set and push an updated plan
    // to the live session so the new language can be used without a Stop/Start.
    private fun onModelInstalled(tag: String) {
        downloadedTags.add(tag)
        val onPlan = replanOnPlan ?: return
        val newAllowed = (lastAllowed + replanDesired.mapNotNull { match(it, downloadedTags) })
            .distinct()
        if (newAllowed != lastAllowed) {
            lastAllowed = newAllowed
            Log.i(TAG, "REPLAN primary=$lastPrimary allowed=$newAllowed")
            mainHandler.post { onPlan(RecognizerPlan(lastPrimary, newAllowed, onDevice = true)) }
        }
    }
}

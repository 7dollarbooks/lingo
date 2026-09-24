package com.livetranslate.headphones.vision

import android.util.Log
import com.livetranslate.headphones.glasses.CaptureOutcome
import com.livetranslate.headphones.glasses.FallbackReason
import com.livetranslate.headphones.glasses.GlassesFacade
import com.livetranslate.headphones.glasses.StillProvenance
import com.livetranslate.headphones.glasses.StillResult
import com.livetranslate.headphones.glasses.toFallbackReason
import kotlinx.coroutines.delay

class GlassesStillSource(
    private val facade: GlassesFacade,
) : StillSource {
    override suspend fun capture(promptHint: String): StillResult? =
        captureWithReason(promptHint).first

    /**
     * CyanBridge-style whole-attempt retries: attempts `0..maxCaptureRetries` with
     * backoff `800 + (n−1)×700` ms between tries. Each attempt gets its own deadline.
     */
    suspend fun captureWithReason(promptHint: String): Pair<StillResult?, FallbackReason?> {
        if (!facade.capability.value.canCapture) {
            return null to glassesFallbackReason(facade)
        }
        val timings = facade.timings
        val maxAttempts = 1 + timings.maxCaptureRetries.coerceAtLeast(0)
        var lastReason: FallbackReason = FallbackReason.GlassesCaptureFailed

        for (attempt in 0 until maxAttempts) {
            if (attempt > 0) {
                val backoff = 800L + (attempt - 1) * 700L
                Log.i(TAG, "retry attempt=$attempt/$maxAttempts backoff=${backoff}ms")
                delay(backoff)
                if (!facade.capability.value.canCapture) {
                    return null to (glassesFallbackReason(facade) ?: lastReason)
                }
            } else {
                Log.i(TAG, "capture attempt=0/$maxAttempts")
            }

            val deadline = System.currentTimeMillis() + timings.oneAttemptBudgetMs()
            Log.i(
                TAG,
                "timer=oneAttemptBudgetMs armed budget=${timings.oneAttemptBudgetMs()}ms " +
                    "attempt=$attempt (signalMs=${timings.signalMs} dataCapMs=${timings.dataCapMs})",
            )
            var outcome = facade.captureOnce(deadline)
            // Busy usually means another Wi-Fi import is still running. Wait it out
            // instead of opening the phone camera.
            var busyWait = 0
            while (outcome is CaptureOutcome.Busy && System.currentTimeMillis() < deadline) {
                busyWait++
                Log.i(TAG, "capture Busy waiting ${busyWait}s for in-flight import")
                delay(1_000)
                if (!facade.capability.value.canCapture && !facade.capability.value.coolingDown) {
                    break
                }
                outcome = facade.captureOnce(deadline)
            }
            when (outcome) {
                is CaptureOutcome.Success -> {
                    Log.i(TAG, "capture Success on attempt=$attempt jpeg=${outcome.jpegBytes.size}B")
                    return StillResult(
                        jpegBytes = outcome.jpegBytes,
                        provenance = StillProvenance.Glasses,
                        fallbackReason = null,
                    ) to null
                }
                else -> {
                    lastReason = outcome.toFallbackReason() ?: FallbackReason.GlassesCaptureFailed
                    Log.w(TAG, "capture attempt=$attempt failed reason=$lastReason")
                    if (outcome is CaptureOutcome.Busy || outcome is CaptureOutcome.NotConnected) {
                        return null to lastReason
                    }
                }
            }
        }
        return null to lastReason
    }

    companion object {
        private const val TAG = "GlassesCap"
    }
}

/**
 * Phone path: completes via existing Activity CameraX deferred (UI).
 * [requestPhoneCapture] should set visionCaptureRequest and await user capture.
 */
class PhoneCameraStillSource(
    private val requestPhoneCapture: suspend (promptHint: String) -> ByteArray?,
) : StillSource {
    override suspend fun capture(promptHint: String): StillResult? {
        val bytes = requestPhoneCapture(promptHint) ?: return null
        val prepared = ImagePrep.extractJpeg(bytes) ?: bytes
        return StillResult(
            jpegBytes = prepared,
            provenance = StillProvenance.Phone,
            fallbackReason = null,
        )
    }
}

fun glassesFallbackReason(facade: GlassesFacade): FallbackReason? {
    val cap = facade.capability.value
    return when {
        cap.canCapture -> null
        cap.coolingDown -> FallbackReason.GlassesCoolingDown
        else -> FallbackReason.GlassesNotReady
    }
}

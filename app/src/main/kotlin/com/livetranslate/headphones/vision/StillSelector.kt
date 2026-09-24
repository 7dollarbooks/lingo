package com.livetranslate.headphones.vision

import com.livetranslate.headphones.glasses.FallbackReason
import com.livetranslate.headphones.glasses.StillProvenance
import com.livetranslate.headphones.glasses.StillResult
import com.livetranslate.headphones.glasses.toStatusMessage

fun interface StillSource {
    suspend fun capture(promptHint: String): StillResult?
}

/**
 * Prefer glasses when [com.livetranslate.headphones.glasses.GlassesCapability.canCapture];
 * honest stage-based fallback copy before phone. Retries live inside [GlassesStillSource].
 */
class StillSelector(
    private val glasses: StillSource?,
    private val phone: StillSource,
    private val canUseGlasses: () -> Boolean,
    private val glassesBlockReason: () -> FallbackReason?,
    private val glassesBudgetMs: Long,
    private val handoffMarginMs: Long,
    private val onStatus: (String) -> Unit = {},
    /** Spoken announce for fallback (same strings as status). */
    private val onAnnounce: (String) -> Unit = {},
) {
    suspend fun capture(promptHint: String = ""): StillResult? {
        val glassesSource = glasses
        if (glassesSource != null && canUseGlasses()) {
            onStatus("Looking through glasses…")
            val (glassesResult, failReason) = when (glassesSource) {
                is GlassesStillSource -> glassesSource.captureWithReason(promptHint)
                else -> {
                    // Generic StillSource: honor outer budget (tests / fakes).
                    val waitMs = (glassesBudgetMs - handoffMarginMs).coerceAtLeast(1L)
                    android.util.Log.i(
                        "GlassesCap",
                        "timer=selectorGlassesBudgetMs waiting ${waitMs}ms " +
                            "(budget=$glassesBudgetMs handoff=$handoffMarginMs)",
                    )
                    val result = runCatching {
                        kotlinx.coroutines.withTimeoutOrNull(waitMs) {
                            glassesSource.capture(promptHint)
                        }
                    }.getOrNull()
                    if (result == null) {
                        android.util.Log.w(
                            "GlassesCap",
                            "timer=selectorGlassesBudgetMs FIRED budget=${waitMs}ms",
                        )
                    }
                    result to if (result == null) FallbackReason.GlassesTimeout else null
                }
            }
            if (glassesResult != null) return glassesResult

            failReason?.let { android.util.Log.w("GlassesCap", "glasses photo missed reason=$it") }
            onStatus("Glasses didn’t return a photo.")
            return null
        }
        val reason = glassesBlockReason() ?: FallbackReason.GlassesNotReady
        if (reason == FallbackReason.UserCancelled) {
            onStatus(reason.toStatusMessage())
            val phoneResult = phone.capture(promptHint) ?: return null
            return phoneResult.copy(
                provenance = StillProvenance.Phone,
                fallbackReason = reason,
            )
        }
        onStatus("Glasses didn’t return a photo.")
        return null
    }
}

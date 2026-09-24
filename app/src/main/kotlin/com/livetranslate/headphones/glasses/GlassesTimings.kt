package com.livetranslate.headphones.glasses

/**
 * Timing knobs for BLE glasses capture and selector budgets.
 *
 * **Invariant (margin inside):** [facadeP95Ms] + [handoffMarginMs] ≤ [selectorGlassesBudgetMs]
 * where [facadeP95Ms] = drain + ack + signal + settle (**excludes** warmup).
 *
 * **Warmup shape:** after the **single** ACK window (success or soft-fail), wait
 * [ackWarmupDelayMs] then proceed to photo-signal. Warmup is **delay-only** — never a
 * second ACK resend.
 *
 * Defaults align with CyanBridge per-attempt stages (12s photo-signal, ~1.2s settle).
 * Chunk phase uses [dataCapMs] capped by the caller deadline. Whole-attempt retries
 * live in the still path (not an ACK resend).
 */
data class GlassesTimings(
    val drainMs: Long = 300L,
    val ackMs: Long = 2_000L,
    /** Delay after the single ACK window before waiting for photo signal — not a resend. */
    val ackWarmupDelayMs: Long = 600L,
    val signalMs: Long = 12_000L,
    val settleMs: Long = 1_200L,
    val settleRetryMs: Long = 1_500L,
    val chunkIdleMs: Long = 5_000L,
    /**
     * Wall-clock room for one Wi-Fi import (peer join plus the full JPEG).
     * The still source uses this as the attempt deadline.
     */
    val dataCapMs: Long = 75_000L,
    val coolingDownMaxMs: Long = 30_000L,
    /**
     * Budget for one glasses attempt (signal + settle + chunk room + margin).
     * Retries are sequenced by the still source; selector no longer cuts a single 10s window.
     */
    val selectorGlassesBudgetMs: Long = 50_000L,
    val handoffMarginMs: Long = 2_000L,
    /** One Wi-Fi import per ask. A miss falls through to the phone camera. */
    val maxCaptureRetries: Int = 0,
) {
    /** p95 excludes ACK warmup (warmup is a rare tail / delay-only). */
    fun facadeP95Ms(): Long = drainMs + ackMs + signalMs + settleMs

    /** Warmup-inclusive path: single ACK + delay + signal + settle (still no second ACK). */
    fun facadeWarmupInclusiveMs(): Long = facadeP95Ms() + ackWarmupDelayMs

    /** One attempt wall: pre-chunk stages + chunk cap. */
    fun oneAttemptBudgetMs(): Long = facadeWarmupInclusiveMs() + dataCapMs

    fun assertInvariant() {
        val p95 = facadeP95Ms()
        check(p95 + handoffMarginMs <= selectorGlassesBudgetMs) {
            "GlassesTimings invariant violated: facadeP95($p95) + handoff($handoffMarginMs) " +
                "> selectorBudget($selectorGlassesBudgetMs)"
        }
        val withWarmup = facadeWarmupInclusiveMs()
        check(withWarmup + handoffMarginMs <= selectorGlassesBudgetMs) {
            "GlassesTimings warmup-inclusive path exceeds budget: $withWarmup + $handoffMarginMs " +
                "> $selectorGlassesBudgetMs (warmup must stay delay-only, never ACK resend)"
        }
    }

    /** Scale all durations for tests (e.g. 1/50). */
    fun scaled(factor: Double): GlassesTimings = copy(
        drainMs = (drainMs * factor).toLong().coerceAtLeast(1L),
        ackMs = (ackMs * factor).toLong().coerceAtLeast(1L),
        ackWarmupDelayMs = (ackWarmupDelayMs * factor).toLong().coerceAtLeast(1L),
        signalMs = (signalMs * factor).toLong().coerceAtLeast(1L),
        settleMs = (settleMs * factor).toLong().coerceAtLeast(1L),
        settleRetryMs = (settleRetryMs * factor).toLong().coerceAtLeast(1L),
        chunkIdleMs = (chunkIdleMs * factor).toLong().coerceAtLeast(1L),
        dataCapMs = (dataCapMs * factor).toLong().coerceAtLeast(1L),
        coolingDownMaxMs = (coolingDownMaxMs * factor).toLong().coerceAtLeast(1L),
        selectorGlassesBudgetMs = (selectorGlassesBudgetMs * factor).toLong().coerceAtLeast(1L),
        handoffMarginMs = (handoffMarginMs * factor).toLong().coerceAtLeast(1L),
    )

    init {
        assertInvariant()
    }

    companion object {
        val DEFAULT = GlassesTimings()
    }
}

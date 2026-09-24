package com.livetranslate.headphones.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field-derived invariant: editing [GlassesTimings.signalMs] past the selector budget
 * must fail construction. Warmup stays delay-only (never a second ACK).
 */
class GlassesTimingsTest {

    @Test
    fun defaultTimingsSatisfyMarginInsideInvariant() {
        val t = GlassesTimings.DEFAULT
        assertTrue(
            "p95+margin must fit selector budget",
            t.facadeP95Ms() + t.handoffMarginMs <= t.selectorGlassesBudgetMs,
        )
        assertTrue(
            "warmup-inclusive (delay-only) must still fit",
            t.facadeWarmupInclusiveMs() + t.handoffMarginMs <= t.selectorGlassesBudgetMs,
        )
        assertEquals(12_000L, t.signalMs)
        assertEquals(1_200L, t.settleMs)
        assertEquals(0, t.maxCaptureRetries)
        t.assertInvariant()
    }

    @Test
    fun facadeP95ExcludesWarmupDelay() {
        val t = GlassesTimings.DEFAULT
        assertEquals(
            t.drainMs + t.ackMs + t.signalMs + t.settleMs,
            t.facadeP95Ms(),
        )
        assertEquals(t.facadeP95Ms() + t.ackWarmupDelayMs, t.facadeWarmupInclusiveMs())
        assertFalse(
            "p95 must not silently include warmup",
            t.facadeP95Ms() == t.facadeWarmupInclusiveMs(),
        )
    }

    @Test
    fun delayOnlyWarmupFitsWhileSecondAckBloatsAttempt() {
        val t = GlassesTimings.DEFAULT
        val delayOnly = t.facadeWarmupInclusiveMs()
        val withSecondAck = t.facadeP95Ms() + t.ackMs
        assertTrue("second ACK window must exceed delay-only warmup", withSecondAck > delayOnly)
        assertTrue(
            "delay-only warmup + margin must fit selector budget",
            delayOnly + t.handoffMarginMs <= t.selectorGlassesBudgetMs,
        )
    }

    @Test
    fun oneAttemptBudgetIncludesChunkCap() {
        val t = GlassesTimings.DEFAULT
        assertEquals(t.facadeWarmupInclusiveMs() + t.dataCapMs, t.oneAttemptBudgetMs())
    }

    @Test(expected = IllegalStateException::class)
    fun editingSignalMsToBreakInvariantFailsConstruction() {
        // Must exceed selectorGlassesBudgetMs after p95 + handoff.
        GlassesTimings(signalMs = 60_000L)
    }

    @Test
    fun scaledTimingsStillAssertInvariant() {
        val scaled = GlassesTimings.DEFAULT.scaled(1.0 / 50.0)
        scaled.assertInvariant()
        assertTrue(scaled.facadeP95Ms() + scaled.handoffMarginMs <= scaled.selectorGlassesBudgetMs)
    }
}

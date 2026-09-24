package com.livetranslate.headphones.glasses

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeGlassesFacadeTest {

    private fun scaledFacade(block: FakeGlassesFacade.() -> Unit = {}): FakeGlassesFacade {
        val timings = GlassesTimings.DEFAULT.scaled(1.0 / 50.0)
        return FakeGlassesFacade(timings = timings, startReady = true).apply(block)
    }

    @Test
    fun captureOnceReturnsSuccessJpegWhenReady() = runBlocking {
        val fake = scaledFacade { signalDelayMs = 1L }
        val deadline = System.currentTimeMillis() + fake.timings.selectorGlassesBudgetMs
        val outcome = fake.captureOnce(deadline)
        assertTrue(outcome is CaptureOutcome.Success)
        val jpeg = (outcome as CaptureOutcome.Success).jpegBytes
        assertTrue(jpeg.size >= 2)
        assertTrue(jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte())
    }

    @Test
    fun softFailAckUsesDelayOnlyWarmupNotSecondAck() = runBlocking {
        val fake = scaledFacade {
            softFailAck = true
            signalDelayMs = 1L
        }
        val deadline = System.currentTimeMillis() + fake.timings.selectorGlassesBudgetMs
        val outcome = fake.captureOnce(deadline)
        assertTrue(outcome is CaptureOutcome.Success)
    }

    @Test
    fun abandonEntersCoolingDownBlockingCapture() = runBlocking {
        val fake = scaledFacade()
        assertTrue(fake.capability.value.canCapture)
        fake.abandonCapture("test")
        delay(20)
        assertTrue(fake.capability.value.coolingDown)
        assertFalse(fake.capability.value.canCapture)
        val deadline = System.currentTimeMillis() + fake.timings.selectorGlassesBudgetMs
        assertEquals(CaptureOutcome.Busy, fake.captureOnce(deadline))
    }

    @Test
    fun coolingClearsAfterMax() = runBlocking {
        val timings = GlassesTimings.DEFAULT.scaled(1.0 / 50.0).copy(coolingDownMaxMs = 40L)
        timings.assertInvariant()
        val fake = FakeGlassesFacade(timings = timings, startReady = true)
        fake.abandonCapture("cool")
        delay(15)
        assertTrue(fake.capability.value.coolingDown)
        delay(timings.coolingDownMaxMs + 50)
        assertFalse(fake.capability.value.coolingDown)
        assertTrue(fake.capability.value.canCapture)
    }

    /**
     * DeepSeek A1: while CoolingDown, a second capture must not arm.
     * CoolingDown — not the gen filter — is the correctness control.
     */
    @Test
    fun hotWireReArmBlockedByCoolingDown() = runBlocking {
        val timings = GlassesTimings.DEFAULT.scaled(1.0 / 50.0).copy(coolingDownMaxMs = 5_000L)
        val fake = FakeGlassesFacade(timings = timings, startReady = true, signalDelayMs = 1L)
        assertTrue(fake.capability.value.canCapture)
        fake.abandonCapture("simulate_hot_wire")
        assertTrue("cooling must engage after abandon", fake.capability.value.coolingDown)
        assertFalse(fake.capability.value.canCapture)
        val second = fake.captureOnce(System.currentTimeMillis() + timings.selectorGlassesBudgetMs)
        assertEquals(
            "second arm while cooling must be Busy (CoolingDown correctness)",
            CaptureOutcome.Busy,
            second,
        )
    }

    @Test
    fun notConnectedWhenAbsent() = runBlocking {
        val fake = FakeGlassesFacade(
            timings = GlassesTimings.DEFAULT.scaled(1.0 / 50.0),
            startReady = false,
        )
        val outcome = fake.captureOnce(System.currentTimeMillis() + 5_000)
        assertEquals(CaptureOutcome.NotConnected, outcome)
    }
}

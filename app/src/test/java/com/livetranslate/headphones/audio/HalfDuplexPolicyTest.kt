package com.livetranslate.headphones.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HalfDuplexPolicyTest {

    @Test
    fun visionHoldExpiresAndReleasesBusy() {
        val policy = HalfDuplexPolicy(visionHoldMaxMs = 50L)
        policy.beginVisionHold(nowMs = 1_000L)
        assertTrue(policy.isBusy(nowMs = 1_010L))
        assertTrue(policy.isVisionHoldActive(nowMs = 1_010L))
        assertTrue(policy.expireVisionHoldIfNeeded(nowMs = 1_060L))
        assertFalse(policy.isVisionHoldActive(nowMs = 1_060L))
        assertFalse(policy.isBusy(nowMs = 1_060L))
    }

    @Test
    fun spitRepeatAndEchoDrops() {
        val policy = HalfDuplexPolicy(echoWindowMs = 5_000L, spitRepeatWindowMs = 5_000L)
        policy.armSpitRepeat("Paris is the capital of France", nowMs = 100L)
        assertEquals(
            HalfDuplexPolicy.DropReason.SpitRepeat,
            policy.dropReasonForUtterance("Paris is the capital of France", nowMs = 200L),
        )
        policy.noteSpoken("The sky is blue", nowMs = 1_000L)
        assertEquals(
            HalfDuplexPolicy.DropReason.Echo,
            policy.dropReasonForUtterance("The sky is blue", nowMs = 1_100L),
        )
        assertNull(policy.dropReasonForUtterance("What time is it", nowMs = 1_200L))
    }
}

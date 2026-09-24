package com.livetranslate.headphones.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionIntentTest {
    @Test
    fun signPhrasesWinOverObject() {
        val kind = VisionIntent.kind("hey what is this sign say translate this sign")
        assertEquals(VisionKind.SignTranslate, kind)
    }

    @Test
    fun objectIdentifyPhrases() {
        assertEquals(VisionKind.ObjectIdentify, VisionIntent.kind("what is this"))
        assertEquals(VisionKind.ObjectIdentify, VisionIntent.kind("what kind of tree is that"))
        assertEquals(VisionKind.ObjectIdentify, VisionIntent.kind("Describe what I am seeing."))
        assertEquals(VisionKind.ObjectIdentify, VisionIntent.kind("Describe what I'm seeing."))
        assertEquals(VisionKind.ObjectIdentify, VisionIntent.kind("Describe whats in front of me."))
        assertEquals(VisionKind.ObjectIdentify, VisionIntent.kind("Describe what's in front of me."))
        assertEquals(VisionKind.ObjectIdentify, VisionIntent.kind("Tell me about this object."))
    }

    @Test
    fun ordinaryQaIsNone() {
        assertEquals(VisionKind.None, VisionIntent.kind("what time is it"))
        assertTrue(!VisionIntent.needsCamera("how's the weather"))
    }
}

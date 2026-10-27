package com.livetranslate.headphones.watch

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchMessageTest {
    @Test
    fun command65CutsTheBodyAt512Characters() {
        val payload = WatchMessages.payload("a".repeat(600))
        assertEquals(WatchMessages.TYPE_OTHER, payload[0].toInt() and 0xFF)
        val text = payload.copyOfRange(1, payload.size).toString(Charsets.UTF_8)
        assertEquals("Lingo: " + "a".repeat(WatchMessages.MAX_BODY_CHARS), text)

        val packet = MoyoungPackets.buildPacket(512, WatchMessages.CMD_SEND_MESSAGE, payload)
        assertEquals(0xFE.toByte(), packet[0])
        assertEquals(0xEA.toByte(), packet[1])
        assertEquals(WatchMessages.CMD_SEND_MESSAGE, packet[4].toInt() and 0xFF)
    }
}

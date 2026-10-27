package com.livetranslate.headphones.watch

/**
 * Moyoung packet layout already used by this watch: FE EA | marker | length | command | payload.
 * No Bluetooth types, so the caption packet can be checked in a unit test.
 */
object MoyoungPackets {
    fun buildPacket(mtu: Int, type: Int, payload: ByteArray): ByteArray {
        val packet = ByteArray(payload.size + 5)
        val length = packet.size
        packet[0] = 0xFE.toByte()
        packet[1] = 0xEA.toByte()
        packet[2] = if (mtu <= 20) 16 else ((32 + (length shr 8)) and 0xFF).toByte()
        packet[3] = (length and 0xFF).toByte()
        packet[4] = type.toByte()
        payload.copyInto(packet, destinationOffset = 5)
        return packet
    }
}

/**
 * Command 65 message. Type 11 ("other") plus `Lingo: ` and the line.
 * The line is cut at 512 characters, which is the body limit documented for this protocol.
 */
object WatchMessages {
    const val CMD_SEND_MESSAGE = 65
    const val TYPE_OTHER = 11
    const val MAX_BODY_CHARS = 512
    const val PREFIX = "Lingo: "

    fun payload(line: String): ByteArray {
        val body = line.trim().take(MAX_BODY_CHARS)
        val text = PREFIX + body
        return byteArrayOf(TYPE_OTHER.toByte()) + text.toByteArray(Charsets.UTF_8)
    }

    /** Shorten the line until the whole packet fits in one ATT write. */
    fun payloadFitting(line: String, maxPacketBytes: Int): ByteArray {
        var body = line.trim().take(MAX_BODY_CHARS)
        while (true) {
            val encoded = payload(body)
            if (encoded.size + 5 <= maxPacketBytes || body.isEmpty()) return encoded
            body = body.dropLast(1)
        }
    }
}

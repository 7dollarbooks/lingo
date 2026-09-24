package com.livetranslate.headphones

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Receives Gadgetbridge 0.94.0 BLE notifications.
 *
 * The event puts the characteristic UUID in [EXTRA_CHARACTERISTIC] and the
 * value in [EXTRA_PAYLOAD] as hex. [EXTRA_CHARACTERISTIC_UUID] is the key
 * Gadgetbridge uses on the read/write commands, not on this event.
 */
class GadgetbridgeBleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val deviceAddress = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
        val uuid = intent.getStringExtra(EXTRA_CHARACTERISTIC)
            ?: intent.getStringExtra(EXTRA_CHARACTERISTIC_UUID)
        val payloadHex = intent.getStringExtra(EXTRA_PAYLOAD)
        Log.d(TAG, "BLE Data from $deviceAddress | UUID: $uuid | Payload: $payloadHex")
        heartRateBpm(uuid, payloadHex)?.let { bpm ->
            Log.d(TAG, "Heart rate $bpm bpm from $uuid")
        }
    }

    companion object {
        const val ACTION =
            "nodomain.freeyourgadget.gadgetbridge.ble_api.events.CHARACTERISTIC_CHANGED"
        const val EXTRA_DEVICE_ADDRESS = "EXTRA_DEVICE_ADDRESS"
        const val EXTRA_CHARACTERISTIC = "EXTRA_CHARACTERISTIC"
        const val EXTRA_CHARACTERISTIC_UUID = "EXTRA_CHARACTERISTIC_UUID"
        const val EXTRA_PAYLOAD = "EXTRA_PAYLOAD"

        private const val TAG = "LingoApp"
        private const val HEART_RATE_MEASUREMENT = "00002a37-0000-1000-8000-00805f9b34fb"

        fun register(context: Context) {
            ContextCompat.registerReceiver(
                context,
                GadgetbridgeBleReceiver(),
                IntentFilter(ACTION),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }

        /** Standard Heart Rate Measurement (0x2A37): flags, then uint8 or uint16 bpm. */
        private fun heartRateBpm(uuid: String?, payloadHex: String?): Int? {
            if (uuid == null || !uuid.equals(HEART_RATE_MEASUREMENT, ignoreCase = true)) return null
            val bytes = decodeHex(payloadHex) ?: return null
            if (bytes.isEmpty()) return null
            val wide = bytes[0].toInt() and 0x01 != 0
            val bpm = if (!wide) {
                if (bytes.size < 2) return null
                bytes[1].toInt() and 0xFF
            } else {
                if (bytes.size < 3) return null
                (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)
            }
            return bpm.takeIf { it in 1..300 }
        }

        private fun decodeHex(hex: String?): ByteArray? {
            val clean = hex?.filter { !it.isWhitespace() } ?: return null
            if (clean.isEmpty() || clean.length % 2 != 0) return null
            return runCatching {
                ByteArray(clean.length / 2) { i ->
                    clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            }.getOrNull()
        }
    }
}

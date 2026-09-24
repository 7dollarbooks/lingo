package com.livetranslate.headphones.glasses.vendor

import android.util.Log
import kotlinx.coroutines.CompletableDeferred

/**
 * Cheap app-side capture diagnostics when HCI snoop is unavailable.
 * Distinguishes: which named timer fired, whether [LargeDataHandler.initEnable]
 * ran on-device, and whether the arm GATT write completed vs hung.
 */
internal object OudmonCaptureDiag {
    @Volatile var initEnableRan: Boolean = false
        private set
    @Volatile var lastInitEnableAtMs: Long = 0L
        private set

    @Volatile var lastArmWriteCallAtMs: Long = 0L
        private set
    @Volatile var lastArmGattWriteCbAtMs: Long = 0L
        private set
    @Volatile var lastArmProtocolAckAtMs: Long = 0L
        private set

    fun markInitEnable() {
        initEnableRan = true
        lastInitEnableAtMs = System.currentTimeMillis()
    }

    fun clearOnDisconnect() {
        initEnableRan = false
        lastInitEnableAtMs = 0L
    }

    fun markArmWriteCall() {
        lastArmWriteCallAtMs = System.currentTimeMillis()
        // Reset completion markers for this arm attempt.
        lastArmGattWriteCbAtMs = 0L
        lastArmProtocolAckAtMs = 0L
    }

    fun markArmGattWriteCallback() {
        lastArmGattWriteCbAtMs = System.currentTimeMillis()
    }

    fun markArmProtocolAck() {
        lastArmProtocolAckAtMs = System.currentTimeMillis()
    }

    /**
     * Set while a capture is waiting for the 0x4A notify. Completed with true when
     * frame byte 7 is 0x01 (HeyCyan `h5/j.a`), false when a 0x4A frame says otherwise.
     */
    @Volatile var prepareWaiter: CompletableDeferred<Boolean>? = null

    fun onRawNotify(bytes: ByteArray?) {
        val frame = bytes ?: return
        if (frame.size < 2 || (frame[0].toInt() and 0xFF) != 0xBC) return
        val cmd = frame[1].toInt() and 0xFF
        val waiter = prepareWaiter ?: return
        if (cmd != 0x4A || frame.size < 8) {
            Log.i(
                "GlassesCap",
                "raw notify during prepare cmd=0x${"%02x".format(cmd)} len=${frame.size} ${frame.toHexCompact()}",
            )
            return
        }
        val flag = frame[7].toInt() and 0xFF
        val ready = flag == 0x01
        Log.i(
            "GlassesCap",
            "cmd 0x4A byte7=0x${"%02x".format(flag)} ready=$ready raw=${frame.toHexCompact()}",
        )
        if (!waiter.isCompleted) waiter.complete(ready)
    }

    fun isArmPayload(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.size < 3) return false
        for (i in 0..bytes.size - 3) {
            if ((bytes[i].toInt() and 0xFF) == 0x02 &&
                (bytes[i + 1].toInt() and 0xFF) == 0x01 &&
                (bytes[i + 2].toInt() and 0xFF) == 0x06
            ) {
                return true
            }
        }
        return false
    }

    fun snapshot(now: Long = System.currentTimeMillis()): String {
        fun age(ts: Long): String =
            if (ts <= 0L) "never" else "${now - ts}ms_ago"
        return "initEnableRan=$initEnableRan initEnable=${age(lastInitEnableAtMs)} " +
            "armWriteCall=${age(lastArmWriteCallAtMs)} " +
            "armGattWriteCb=${age(lastArmGattWriteCbAtMs)} " +
            "armProtocolAck=${age(lastArmProtocolAckAtMs)}"
    }
}

internal fun ByteArray.toHexCompact(): String =
    joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }

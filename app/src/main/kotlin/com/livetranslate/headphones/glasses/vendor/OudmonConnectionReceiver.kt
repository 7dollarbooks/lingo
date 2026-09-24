package com.livetranslate.headphones.glasses.vendor

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.QCBluetoothCallbackCloneReceiver
import com.oudmon.ble.base.communication.LargeDataHandler

/**
 * Oudmon GATT/connect callbacks via LocalBroadcastManager.
 *
 * Registered once for the Application lifetime (never unregistered) — intentional for
 * the app-scoped BLE singleton. LBM delivers on the main looper; keep this receiver
 * non-blocking (no file I/O / no blocking GATT).
 */
internal class OudmonConnectionReceiver : QCBluetoothCallbackCloneReceiver() {
    override fun connectStatue(device: BluetoothDevice?, connected: Boolean) {
        Log.i(TAG, "connectStatue connected=$connected name=${device?.name}")
        // Match CyanBridge: do not declare Ready on connectStatue(true) —
        // wait for onServiceDiscovered (initEnable + isReady) first.
        if (!connected) {
            OudmonCaptureDiag.clearOnDisconnect()
            OudmonConnectionHub.onConnectionChanged(false)
        }
    }

    override fun onServiceDiscovered() {
        // Exact CyanBridge order: initEnable first, then isReady.
        Log.i(CAP_TAG, "initEnable CALL (onServiceDiscovered)")
        LargeDataHandler.getInstance().initEnable()
        OudmonCaptureDiag.markInitEnable()
        BleOperateManager.getInstance().isReady = true
        Log.i(CAP_TAG, "initEnable DONE + isReady ${OudmonCaptureDiag.snapshot()}")
        // CyanBridge opens this on connect. Photo signal (0x73) shares the AI notify path.
        enableAiVoiceWake()
        OudmonConnectionHub.onConnectionChanged(true)
    }

    /** Query AI voice, then enable it if the glasses report it closed. */
    private fun enableAiVoiceWake() {
        Log.i(CAP_TAG, "aiVoiceWake query")
        LargeDataHandler.getInstance().aiVoiceWake(false, false) { _, rsp ->
            val open = rsp?.isOpen == true
            Log.i(CAP_TAG, "aiVoiceWake query isOpen=$open")
            if (open) return@aiVoiceWake
            LargeDataHandler.getInstance().aiVoiceWake(true, true) { _, rsp2 ->
                Log.i(CAP_TAG, "aiVoiceWake set isOpen=${rsp2?.isOpen == true}")
            }
        }
    }

    /**
     * SDK fires this on the GATT characteristic-write broadcast — i.e. the write
     * callback, not the app's protocol ACK. A hang here means the arm write never completed.
     */
    override fun onCharacteristicChange(address: String?, uuid: String?, bytes: ByteArray?) {
        OudmonCaptureDiag.onRawNotify(bytes)
    }

    override fun onCommandSend(bytes: ByteArray?) {
        val payload = bytes ?: ByteArray(0)
        val hex = payload.toHexCompact()
        if (OudmonCaptureDiag.isArmPayload(payload)) {
            OudmonCaptureDiag.markArmGattWriteCallback()
            Log.i(
                CAP_TAG,
                "arm GATT write-callback payload=$hex ${OudmonCaptureDiag.snapshot()}",
            )
        } else {
            Log.i(CAP_TAG, "GATT write-callback payload=$hex len=${payload.size}")
        }
    }

    companion object {
        private const val TAG = "OudmonConnRx"
        private const val CAP_TAG = "GlassesCap"
    }
}

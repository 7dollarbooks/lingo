package com.livetranslate.headphones.glasses.vendor

import android.content.Context

/**
 * Thin BLE client for the Oudmon / HeyCyan glasses AAR.
 *
 * **This package is the only place allowed to import `com.oudmon.ble.*`.**
 * Callbacks must never take [com.livetranslate.headphones.glasses.GlassesRepository]'s mutex —
 * they only invoke the provided lambdas (complete deferreds lock-free).
 */
interface GlassesBleClient {
    fun isConnected(): Boolean
    /**
     * Issue an async GATT connect. Returns [Result.failure] if the SDK throws synchronously;
     * success does not mean Ready yet — wait for the connection listener.
     */
    fun connect(address: String): Result<Unit>
    fun disconnect()
    fun registerConnectionListener(onChanged: (connected: Boolean) -> Unit)
    fun unregisterConnectionListener()

    fun startScan(
        context: Context,
        onDevice: (name: String, address: String, rssi: Int) -> Unit,
        onStopped: () -> Unit,
        onFailed: (errorCode: Int) -> Unit,
    )

    fun stopScan(context: Context)

    /** Drain stale thumbnail callbacks; invoke [onIdle] when complete or timed out by caller. */
    fun drainThumbnails(onChunk: (ByteArray) -> Unit, onComplete: () -> Unit)

    /**
     * HeyCyan earphone-glasses still path: command 0x4A, payload `[0x01]`.
     * The SDK parser drops this command, so the reply is observed on the raw notify.
     */
    fun sendEarphonePrepare()

    /**
     * HeyCyan still: glasses control `02 01 06 <thumbnailSize> <thumbnailSize>` (5 bytes).
     * [onAck] receives errorCode (0 = ok) or null on timeout handled by caller.
     */
    fun sendCaptureCommand(onAck: (errorCode: Int?) -> Unit)

    fun listenThumbnails(
        onChunk: (data: ByteArray, isComplete: Boolean) -> Unit,
    )

    fun registerPhotoSignalListener(onPhotoSignal: () -> Unit)
    fun unregisterPhotoSignalListener()
}

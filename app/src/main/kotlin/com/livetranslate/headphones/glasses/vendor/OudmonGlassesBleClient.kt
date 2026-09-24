package com.livetranslate.headphones.glasses.vendor

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.queue.BleDataBean
import com.oudmon.ble.base.bluetooth.queue.BleThreadManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import com.oudmon.ble.base.scan.BleScannerHelper
import com.oudmon.ble.base.scan.ScanRecord
import com.oudmon.ble.base.scan.ScanWrapperCallback

/**
 * Real Oudmon AAR adapter. Callbacks run on the SDK thread — callers must not
 * acquire the repository mutex from these lambdas (complete deferreds lock-free).
 *
 * Field note: document actual callback-thread delivery when observed on device.
 */
class OudmonGlassesBleClient : GlassesBleClient {
    private var connectionListener: ((Boolean) -> Unit)? = null
    private var photoSignalListener: (() -> Unit)? = null
    private var deviceNotifyRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanTimeout: Runnable? = null

    override fun isConnected(): Boolean = BleOperateManager.getInstance().isConnected

    override fun connect(address: String): Result<Unit> =
        runCatching {
            BleOperateManager.getInstance().connectDirectly(address)
            // Do NOT invoke connectionListener from isConnected() here — that stomps
            // Connecting → Absent while GATT is still in flight. Ready comes from
            // OudmonConnectionReceiver.onServiceDiscovered via OudmonConnectionHub.
        }.onFailure { err ->
            Log.e(TAG, "connectDirectly failed addr=$address", err)
        }

    override fun disconnect() {
        runCatching {
            BleOperateManager.getInstance().unBindDevice()
        }.onFailure { Log.e(TAG, "unBindDevice failed", it) }
        connectionListener?.invoke(false)
    }

    override fun registerConnectionListener(onChanged: (Boolean) -> Unit) {
        connectionListener = onChanged
        OudmonConnectionHub.setListener(onChanged)
        onChanged(isConnected())
    }

    override fun unregisterConnectionListener() {
        connectionListener = null
        OudmonConnectionHub.setListener(null)
    }

    override fun startScan(
        context: Context,
        onDevice: (name: String, address: String, rssi: Int) -> Unit,
        onStopped: () -> Unit,
        onFailed: (errorCode: Int) -> Unit,
    ) {
        stopScan(context)
        BleScannerHelper.getInstance().reSetCallback()
        BleScannerHelper.getInstance().scanDevice(
            context,
            null,
            object : ScanWrapperCallback {
                override fun onStart() = Unit

                override fun onLeScan(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
                    if (device == null || device.name.isNullOrEmpty()) return
                    onDevice(device.name ?: "Unknown", device.address, rssi)
                }

                override fun onStop() {
                    onStopped()
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.e(TAG, "Scan failed: $errorCode")
                    onFailed(errorCode)
                }

                override fun onParsedData(device: BluetoothDevice?, scanRecord: ScanRecord?) = Unit
                override fun onBatchScanResults(results: MutableList<ScanResult>?) = Unit
            },
        )
        val timeout = Runnable {
            stopScan(context)
            onStopped()
        }
        scanTimeout = timeout
        mainHandler.postDelayed(timeout, SCAN_TIMEOUT_MS)
    }

    override fun stopScan(context: Context) {
        scanTimeout?.let { mainHandler.removeCallbacks(it) }
        scanTimeout = null
        runCatching { BleScannerHelper.getInstance().stopScan(context) }
    }

    override fun drainThumbnails(onChunk: (ByteArray) -> Unit, onComplete: () -> Unit) {
        LargeDataHandler.getInstance().getPictureThumbnails { _, isComplete, data ->
            if (data != null && data.isNotEmpty()) onChunk(data)
            if (isComplete) onComplete()
        }
    }

    override fun sendEarphonePrepare() {
        // HeyCyan g5/p.p: command 0x4A, one byte 0x01. July SDK has no wrapper for 0x4A.
        val payload = byteArrayOf(0x01)
        Log.i(CAP_TAG, "prepare write CALL cmd=0x4A payload=${payload.toHexCompact()}")
        writeRawCommand(0x4A, payload)
    }

    override fun sendCaptureCommand(onAck: (Int?) -> Unit) {
        // HeyCyan still (LE2/f0 and LE2/O case 5): 02 01 06 <thumb> <thumb>.
        // thumbnailSize default in that app is 2. No sixth byte.
        val thumbnailSize: Byte = 0x02
        val payload = byteArrayOf(0x02, 0x01, 0x06, thumbnailSize, thumbnailSize)
        val sendAt = System.currentTimeMillis()
        OudmonCaptureDiag.markArmWriteCall()
        Log.i(
            CAP_TAG,
            "arm write CALL payload=${payload.toHexCompact()} ${OudmonCaptureDiag.snapshot(sendAt)}",
        )
        LargeDataHandler.getInstance().glassesControl(payload) { _, resp ->
            val code = runCatching { resp.errorCode }.getOrNull()
                ?: runCatching {
                    resp.javaClass.getDeclaredField("errorCode").apply { isAccessible = true }.getInt(resp)
                }.getOrNull()
            OudmonCaptureDiag.markArmProtocolAck()
            val latency = System.currentTimeMillis() - sendAt
            Log.i(
                CAP_TAG,
                "arm protocol ACK errorCode=$code latency=${latency}ms ${OudmonCaptureDiag.snapshot()}",
            )
            onAck(code)
        }
    }

    override fun listenThumbnails(onChunk: (ByteArray, Boolean) -> Unit) {
        LargeDataHandler.getInstance().getPictureThumbnails { _, isComplete, data ->
            val bytes = data ?: ByteArray(0)
            // Unconditional — even when repository will drop for gen mismatch.
            Log.i(
                CAP_TAG,
                "thumbnail raw size=${bytes.size} isComplete=$isComplete",
            )
            onChunk(bytes, isComplete)
        }
    }

    override fun registerPhotoSignalListener(onPhotoSignal: () -> Unit) {
        photoSignalListener = onPhotoSignal
        if (deviceNotifyRegistered) return
        deviceNotifyRegistered = true
        LargeDataHandler.getInstance().addOutDeviceListener(
            100,
            object : GlassesDeviceNotifyListener() {
            override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
                    val data = response.loadData
                    // Unconditional entry log (before armed/gen filters) — DeepSeek A/B split.
                    val size = data?.size ?: -1
                    val sub = if (data != null && data.size > 6) data[6].toInt() and 0xFF else -1
                    Log.i(
                        CAP_TAG,
                        "DeviceNotify raw cmd=0x${Integer.toHexString(cmdType)} size=$size sub=0x${"%02x".format(sub)} armedListener=${photoSignalListener != null}",
                    )
                    if (data == null) return
                    if (cmdType != 0x73 || data.size <= 6) return
                    when (sub) {
                        0x01, 0x02 -> {
                            Log.i(CAP_TAG, "photo signal sub=0x${"%02x".format(sub)}")
                            photoSignalListener?.invoke()
                        }
                    }
                }
            },
        )
    }

    override fun unregisterPhotoSignalListener() {
        photoSignalListener = null
    }

    /**
     * Same queue path as [LargeDataHandler.glassesControl], for command bytes the
     * July 2025 SDK does not wrap. [LargeDataHandler.addHeader] is private.
     */
    private fun writeRawCommand(cmd: Int, payload: ByteArray) {
        runCatching {
            val handler = LargeDataHandler.getInstance()
            handler.packageLength()
            val addHeader = handler.javaClass.getDeclaredMethod(
                "addHeader",
                Int::class.javaPrimitiveType,
                ByteArray::class.java,
            )
            addHeader.isAccessible = true
            val framed = addHeader.invoke(handler, cmd, payload) as ByteArray
            val packageLength = handler.javaClass.getDeclaredField("mPackageLength").apply {
                isAccessible = true
            }.getInt(handler)
            BleThreadManager.getInstance().addData(BleDataBean(framed, packageLength))
            Log.i(CAP_TAG, "raw write queued cmd=0x${"%02x".format(cmd)} frame=${framed.toHexCompact()}")
        }.onFailure { err ->
            Log.e(CAP_TAG, "raw write failed cmd=0x${"%02x".format(cmd)}", err)
        }
    }

    companion object {
        private const val TAG = "OudmonBle"
        private const val CAP_TAG = "GlassesCap"
        private const val SCAN_TIMEOUT_MS = 15_000L
    }
}

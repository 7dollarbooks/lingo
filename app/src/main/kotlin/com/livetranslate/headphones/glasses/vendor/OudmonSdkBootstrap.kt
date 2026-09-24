package com.livetranslate.headphones.glasses.vendor

import android.app.Application
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.oudmon.ble.base.bluetooth.BleAction
import com.oudmon.ble.base.bluetooth.BleBaseControl
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler

/**
 * Sole place that boots the Oudmon AAR (keeps `com.oudmon.ble.*` inside [vendor]).
 *
 * Mirrors CyanBridge MyApplication.initBle / initReceiver: BleOperateManager init,
 * BleBaseControl context, and a process-lifetime LocalBroadcast connection receiver.
 */
object OudmonSdkBootstrap {
    private const val TAG = "OudmonBootstrap"
    @Volatile private var initialized = false

    fun init(app: Application) {
        if (initialized) return
        runCatching {
            LargeDataHandler.getInstance()
            BleOperateManager.getInstance(app)
            BleOperateManager.getInstance().setApplication(app)
            BleOperateManager.getInstance().init()
            BleBaseControl.getInstance(app).setmContext(app)

            // App-scoped: registered once, never unregistered (same lifetime as Application).
            val receiver = OudmonConnectionReceiver()
            LocalBroadcastManager.getInstance(app)
                .registerReceiver(receiver, BleAction.getIntentFilter())

            initialized = true
            Log.i(TAG, "Oudmon BLE SDK initialized")
        }.onFailure {
            Log.e(TAG, "Oudmon BLE SDK init failed", it)
        }
    }
}

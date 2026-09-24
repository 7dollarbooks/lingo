package com.livetranslate.headphones.watch

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Direct link to the ST9. The packet layout is the Moyoung one already used on
 * this watch: steps on fee1, measurements on fee3, commands written to fee2.
 */
class MoyoungWatchClient(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var payloadMtu = 20
    private var packet = ByteArray(0)
    private var packetFilled = 0
    private var stage = Stage.IDLE
    private var listener: ((WatchVitals) -> Unit)? = null
    private var vitals = WatchVitals(status = "Connecting to ST9")

    fun start(onUpdate: (WatchVitals) -> Unit) {
        listener = onUpdate
        publish()
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            publish(vitals.copy(status = "Phone Bluetooth is off"))
            return
        }
        val device = adapter.getRemoteDevice(WATCH_ADDRESS)
        stage = Stage.CONNECTING
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun stop() {
        stage = Stage.IDLE
        listener = null
        main.removeCallbacksAndMessages(null)
        val current = gatt
        gatt = null
        current?.disconnect()
        current?.close()
    }

    fun refreshHeartRate() = sendStart(CMD_HEART_RATE, byteArrayOf(0), "Asking the watch for a heart rate")

    fun refreshBloodPressure() =
        sendStart(CMD_BLOOD_PRESSURE, byteArrayOf(0, 0, 0), "Asking the watch for blood pressure")

    fun refreshSteps() = rereadSteps("Asking the watch for steps")

    fun refreshCalories() = rereadSteps("Asking the watch for calories")

    private fun sendStart(type: Int, payload: ByteArray, status: String) {
        val link = gatt
        if (link == null || stage != Stage.READY) {
            publish(vitals.copy(status = "Watch is not ready for a measurement yet"))
            return
        }
        publish(vitals.copy(status = status))
        writeCommand(link, type, payload)
    }

    @SuppressLint("MissingPermission")
    private fun rereadSteps(status: String) {
        val link = gatt
        if (link == null || stage != Stage.READY) {
            publish(vitals.copy(status = "Watch is not ready for a measurement yet"))
            return
        }
        publish(vitals.copy(status = status))
        readCharacteristic(link, link.getService(SERVICE)?.getCharacteristic(STEPS))
    }

    private fun publish(next: WatchVitals = vitals) {
        vitals = next
        val callback = listener ?: return
        main.post { callback(next) }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "connection status=$status state=$newState")
            if (newState != BluetoothProfile.STATE_CONNECTED) {
                if (stage != Stage.IDLE) {
                    publish(vitals.copy(status = "Watch link closed ($status)"))
                }
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                publish(vitals.copy(status = "Watch link failed ($status)"))
                return
            }
            publish(vitals.copy(status = "Connected. Asking the watch for a larger packet size."))
            gatt.requestMtu(REQUEST_MTU)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            payloadMtu = if (status == BluetoothGatt.GATT_SUCCESS && mtu > 23) mtu - 3 else 20
            Log.i(TAG, "mtu=$mtu status=$status payload=$payloadMtu")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                publish(vitals.copy(status = "Watch services failed ($status)"))
                return
            }
            val service = gatt.getService(SERVICE)
            if (service == null) {
                publish(vitals.copy(status = "Watch service not found"))
                return
            }
            stage = Stage.NOTIFY_IN
            if (!enableNotify(gatt, service.getCharacteristic(DATA_IN))) {
                publish(vitals.copy(status = "Watch measurement notify failed"))
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val service = gatt.getService(SERVICE) ?: return
            when (stage) {
                Stage.NOTIFY_IN -> {
                    stage = Stage.NOTIFY_STEPS
                    if (!enableNotify(gatt, service.getCharacteristic(STEPS))) {
                        publish(vitals.copy(status = "Watch step notify failed"))
                    }
                }
                Stage.NOTIFY_STEPS -> {
                    readCharacteristic(gatt, service.getCharacteristic(STEPS))
                    val standard = gatt.getService(HEART_RATE_SERVICE)?.getCharacteristic(STANDARD_HEART_RATE)
                    if (standard != null && enableNotify(gatt, standard)) {
                        stage = Stage.NOTIFY_STANDARD
                    } else {
                        beginMeasurements(gatt)
                    }
                }
                Stage.NOTIFY_STANDARD -> beginMeasurements(gatt)
                else -> Unit
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == STEPS) {
                applySteps(value)
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT >= 33) return
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == STEPS) {
                @Suppress("DEPRECATION")
                applySteps(characteristic.value ?: return)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            when (characteristic.uuid) {
                STEPS -> applySteps(value)
                DATA_IN -> onData(value)
                STANDARD_HEART_RATE -> applyStandardHeartRate(value)
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            when (characteristic.uuid) {
                STEPS -> applySteps(value)
                DATA_IN -> onData(value)
                STANDARD_HEART_RATE -> applyStandardHeartRate(value)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic?): Boolean {
        if (characteristic == null) return false
        if (!gatt.setCharacteristicNotification(characteristic, true)) return false
        val descriptor = characteristic.getDescriptor(CCCD) ?: return false
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    @SuppressLint("MissingPermission")
    private fun readCharacteristic(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic?) {
        if (characteristic == null) return
        gatt.readCharacteristic(characteristic)
    }

    @SuppressLint("MissingPermission")
    private fun writeCommand(gatt: BluetoothGatt, type: Int, payload: ByteArray) {
        val service = gatt.getService(SERVICE) ?: return
        val out = service.getCharacteristic(DATA_OUT) ?: return
        val packet = buildPacket(payloadMtu, type, payload)
        Log.i(TAG, "tx ${packet.joinToString(" ") { "%02x".format(it) }}")
        out.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(out, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            out.value = packet
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(out)
        }
    }

    @SuppressLint("MissingPermission")
    private fun beginMeasurements(gatt: BluetoothGatt) {
        stage = Stage.READY
        publish(vitals.copy(status = "Connected"))
        writeCommand(gatt, CMD_SYNC_TIME, watchTimeBytes())
        readCharacteristic(gatt, gatt.getService(SERVICE)?.getCharacteristic(STEPS))
    }

    private fun applyStandardHeartRate(value: ByteArray) {
        if (value.isEmpty()) return
        val wide = value[0].toInt() and 0x01 != 0
        val bpm = if (!wide) {
            if (value.size < 2) return
            value[1].toInt() and 0xFF
        } else {
            if (value.size < 3) return
            (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
        }
        Log.i(TAG, "standard heart=$bpm")
        if (bpm in 1..300) publish(vitals.copy(heartBpm = bpm, status = "Heart rate $bpm"))
    }

    private fun applySteps(value: ByteArray) {
        if (value.size != 9) {
            Log.i(TAG, "steps length ${value.size}")
            return
        }
        val steps = uint24le(value, 0)
        val calories = uint24le(value, 6)
        Log.i(TAG, "steps=$steps calories=$calories")
        publish(vitals.copy(steps = steps, calories = calories))
    }

    private fun onData(fragment: ByteArray) {
        Log.i(TAG, "rx ${fragment.joinToString(" ") { "%02x".format(it) }}")
        if (packetFilled == 0) {
            val len = packetLength(fragment) ?: return
            packet = ByteArray(len)
        }
        val copy = minOf(fragment.size, packet.size - packetFilled)
        if (copy > 0) fragment.copyInto(packet, packetFilled, 0, copy)
        packetFilled += fragment.size
        if (packetFilled < packet.size) return
        val complete = packet
        packet = ByteArray(0)
        packetFilled = 0
        val parsed = parsePacket(complete) ?: return
        when (parsed.first) {
            CMD_HEART_RATE -> {
                val bpm = parsed.second.firstOrNull()?.toInt()?.and(0xFF) ?: return
                Log.i(TAG, "heart=$bpm")
                if (bpm in 1..300) {
                    publish(vitals.copy(heartBpm = bpm, status = "Heart rate $bpm"))
                }
            }
            CMD_BLOOD_PRESSURE -> {
                val payload = parsed.second
                if (payload.size < 3) return
                val systolic = payload[1].toInt() and 0xFF
                val diastolic = payload[2].toInt() and 0xFF
                Log.i(TAG, "bp=$systolic/$diastolic")
                if (systolic in 1..254 && diastolic in 1..254) {
                    publish(
                        vitals.copy(
                            systolic = systolic,
                            diastolic = diastolic,
                            status = "Blood pressure $systolic/$diastolic",
                        ),
                    )
                }
            }
        }
    }

    private enum class Stage { IDLE, CONNECTING, NOTIFY_IN, NOTIFY_STEPS, NOTIFY_STANDARD, READY }

    companion object {
        private const val TAG = "WatchLink"
        const val WATCH_ADDRESS = "F8:06:E1:5F:D7:12"
        private const val REQUEST_MTU = 511
        private const val CMD_SYNC_TIME = 49
        private const val CMD_HEART_RATE = 109
        private const val CMD_BLOOD_PRESSURE = 105

        private val SERVICE: UUID = UUID.fromString("0000feea-0000-1000-8000-00805f9b34fb")
        private val STEPS: UUID = UUID.fromString("0000fee1-0000-1000-8000-00805f9b34fb")
        private val DATA_OUT: UUID = UUID.fromString("0000fee2-0000-1000-8000-00805f9b34fb")
        private val DATA_IN: UUID = UUID.fromString("0000fee3-0000-1000-8000-00805f9b34fb")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val STANDARD_HEART_RATE: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

        private fun watchTimeBytes(): ByteArray {
            val pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS"
            val local = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getDefault() }
            val formatted = local.format(Date())
            val watch = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT+8") }
            val seconds = (watch.parse(formatted)!!.time / 1000).toInt()
            return byteArrayOf(
                (seconds shr 24).toByte(),
                (seconds shr 16).toByte(),
                (seconds shr 8).toByte(),
                seconds.toByte(),
                8,
            )
        }

        private fun uint24le(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16)

        private fun buildPacket(mtu: Int, type: Int, payload: ByteArray): ByteArray {
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

        private fun packetLength(fragment: ByteArray): Int? {
            if (fragment.size < 4 || fragment[0] != 0xFE.toByte() || fragment[1] != 0xEA.toByte()) return null
            val marker = fragment[2].toInt() and 0xFF
            val high = if (marker == 16) 0 else marker - 32
            if (high < 0) return null
            val low = fragment[3].toInt() and 0xFF
            val len = (high shl 8) or low
            return len.takeIf { it >= 5 }
        }

        private fun parsePacket(packet: ByteArray): Pair<Int, ByteArray>? {
            val len = packetLength(packet) ?: return null
            if (len != packet.size || packet.size < 5) return null
            return (packet[4].toInt() and 0xFF) to packet.copyOfRange(5, packet.size)
        }
    }
}

data class WatchVitals(
    val status: String = "Connecting to ST9",
    val heartBpm: Int? = null,
    val systolic: Int? = null,
    val diastolic: Int? = null,
    val steps: Int? = null,
    val calories: Int? = null,
)

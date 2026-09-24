package com.livetranslate.headphones.audio

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

data class HeadsetState(
    val connected: Boolean,
    val name: String?,
)

/**
 * Sends Lingo audio as media (A2DP) to the Shokz. A phone-call route would put the
 * glasses on SCO and disable their shutter, so playback stays on the music path.
 */
class BluetoothAudioRouter(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    // Reflects whether a Bluetooth headset is connected at all (A2DP/BLE/SCO), independent
    // of whether a call/SCO session is active. Used so the UI can show the headset at idle.
    private val _headset = MutableStateFlow(HeadsetState(false, null))
    val headset: StateFlow<HeadsetState> = _headset.asStateFlow()

    private var previousMode = AudioManager.MODE_NORMAL
    private var focusRequest: AudioFocusRequest? = null
    private var headsetProxy: BluetoothHeadset? = null
    private var voiceDevice: BluetoothDevice? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val presenceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = updateHeadsetState()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = updateHeadsetState()
    }

    init {
        audioManager.registerAudioDeviceCallback(presenceCallback, null)
        updateHeadsetState()
    }

    fun connectedBluetoothOutput(): AudioDeviceInfo? =
        selectA2dpOutput() ?: audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }

    private fun updateHeadsetState() {
        val device = connectedBluetoothOutput()
        _headset.value = HeadsetState(
            connected = device != null,
            name = device?.productName?.toString(),
        )
    }

    var inputDevice: AudioDeviceInfo? = null
        private set
    var outputDevice: AudioDeviceInfo? = null
        private set
    var fallbackA2dpDevice: AudioDeviceInfo? = null
        private set

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (!_active.value) return
            refreshDevices()
            val glassesTookAudio = addedDevices.any { nameLooksLikeGlasses(it.productName?.toString()) }
            if (glassesTookAudio) {
                scope.launch { activatePreferredHeadset() }
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val lost = removedDevices.any { it.id == outputDevice?.id }
            if (lost && _active.value) {
                refreshDevices()
            }
        }
    }

    suspend fun start(): Boolean {
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        previousMode = audioManager.mode
        // Media strategy (the podcast path) only applies in normal mode.
        if (audioManager.mode != AudioManager.MODE_NORMAL) {
            audioManager.mode = AudioManager.MODE_NORMAL
        }
        audioManager.isSpeakerphoneOn = false

        // Leave audio focus to the speech recognizer. Holding it here left the mic silent.
        activatePreferredHeadset()
        refreshDevices()
        val media = selectA2dpOutput()
        if (media == null) {
            _active.value = false
            _deviceName.value = null
            val seen = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .joinToString { "${it.productName}:${it.type}" }
            Log.w(TAG, "No Shokz media output. outputs=$seen")
            return false
        }

        inputDevice = selectScoInput()
        if (inputDevice == null) {
            val seen = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .joinToString { "${it.productName}:${it.type}" }
            Log.w(TAG, "Shokz mic not active. inputs=$seen")
        }
        outputDevice = media
        fallbackA2dpDevice = media
        _active.value = true
        _deviceName.value = media.productName?.toString() ?: "Bluetooth headset"
        Log.i(
            TAG,
            "Media chosen id=${media.id} name=${media.productName} addr=${deviceAddress(media)} type=a2dp",
        )
        return true
    }

    fun stop() {
        stopShokzVoiceRecognition()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        abandonAudioFocus()
        audioManager.mode = previousMode
        inputDevice = null
        outputDevice = null
        fallbackA2dpDevice = null
        _active.value = false
        _deviceName.value = null
    }

    /** SCO / communication path — Shokz only. A call on the glasses disables their shutter. */
    fun selectScoInput(): AudioDeviceInfo? {
        val candidates = scoCandidates(audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS))
        return candidates.firstOrNull { nameLooksLikeHeadset(it.productName?.toString()) }
            ?: pickBySavedAddress(candidates)
            ?: candidates.firstOrNull()
    }

    /** A2DP / media path — independent of SCO selection. */
    fun selectA2dpOutput(): AudioDeviceInfo? {
        val candidates = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            .filter { !nameLooksLikeGlasses(it.productName?.toString()) }
        // Do not reuse SCO address preference for media; prefer headset names, then first.
        return candidates.firstOrNull { nameLooksLikeHeadset(it.productName?.toString()) }
            ?: candidates.firstOrNull()
    }

    @Deprecated("Use selectScoInput()", ReplaceWith("selectScoInput()"))
    fun findScoInput(): AudioDeviceInfo? = selectScoInput()

    @Deprecated("Use selectA2dpOutput()", ReplaceWith("selectA2dpOutput()"))
    fun findA2dpOutput(): AudioDeviceInfo? = selectA2dpOutput()

    /** Make the bonded Shokz the active media device, the same path a podcast uses. */
    private suspend fun activatePreferredHeadset() {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: return
        val shokz = runCatching {
            adapter.bondedDevices.firstOrNull { nameLooksLikeHeadset(it.name) }
        }.getOrNull()
        if (shokz == null) {
            Log.w(TAG, "No bonded Shokz")
            return
        }
        Log.i(TAG, "Switching media and mic to ${shokz.name}")
        withTimeoutOrNull(2_500) {
            suspendCancellableCoroutine { cont ->
                val pending = AtomicInteger(2)
                fun finish() {
                    if (pending.decrementAndGet() <= 0 && cont.isActive) cont.resume(Unit)
                }
                val listener = object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        when (profile) {
                            BluetoothProfile.A2DP -> {
                                val ok = setActiveDevice(proxy, shokz)
                                Log.i(TAG, "A2DP active -> ${shokz.name} ok=$ok")
                                runCatching { adapter.closeProfileProxy(profile, proxy) }
                            }
                            BluetoothProfile.HEADSET -> {
                                val hs = proxy as BluetoothHeadset
                                headsetProxy = hs
                                val ok = runCatching { hs.startVoiceRecognition(shokz) }.getOrElse { err ->
                                    Log.w(TAG, "startVoiceRecognition failed", err)
                                    false
                                }
                                voiceDevice = if (ok) shokz else null
                                Log.i(TAG, "startVoiceRecognition ${shokz.name} ok=$ok")
                            }
                        }
                        finish()
                    }

                    override fun onServiceDisconnected(profile: Int) = Unit
                }
                if (!adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP)) finish()
                if (!adapter.getProfileProxy(context, listener, BluetoothProfile.HEADSET)) finish()
            }
        }
        val deadline = System.currentTimeMillis() + 4_000
        while (selectScoInput() == null && System.currentTimeMillis() < deadline) {
            delay(200)
        }
        val sco = selectScoInput()
        if (sco != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val ok = audioManager.setCommunicationDevice(sco)
            Log.i(TAG, "Shokz mic setCommunicationDevice ${sco.productName} ok=$ok")
        }
    }

    private fun stopShokzVoiceRecognition() {
        val device = voiceDevice
        val proxy = headsetProxy
        if (device != null && proxy != null) {
            val ok = runCatching { proxy.stopVoiceRecognition(device) }.getOrDefault(false)
            Log.i(TAG, "stopVoiceRecognition ok=$ok")
        }
        voiceDevice = null
        headsetProxy = null
        if (proxy != null) {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            runCatching { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, proxy) }
        }
    }

    private fun setActiveDevice(proxy: BluetoothProfile, device: BluetoothDevice): Boolean =
        runCatching {
            val method = proxy.javaClass.getMethod("setActiveDevice", BluetoothDevice::class.java)
            method.invoke(proxy, device) as Boolean
        }.getOrElse {
            Log.w(TAG, "setActiveDevice failed", it)
            false
        }

    private fun scoCandidates(devices: Array<out AudioDeviceInfo>): List<AudioDeviceInfo> =
        devices.filter {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                !nameLooksLikeGlasses(it.productName?.toString())
        }

    private fun scoCandidates(devices: List<AudioDeviceInfo>): List<AudioDeviceInfo> =
        devices.filter {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                !nameLooksLikeGlasses(it.productName?.toString())
        }

    private fun refreshDevices() {
        inputDevice = selectScoInput()
        fallbackA2dpDevice = selectA2dpOutput()
    }

    private fun pickBySavedAddress(candidates: List<AudioDeviceInfo>): AudioDeviceInfo? {
        val saved = prefs.getString(KEY_SCO_ADDRESS, null)?.trim().orEmpty()
        if (saved.isEmpty()) return null
        return candidates.firstOrNull { deviceAddress(it).equals(saved, ignoreCase = true) }
    }

    /**
     * First-run default only: prefer Shokz / OpenRun / OpenComm style names;
     * deprioritize likely glasses (Even Realities, G1, Meta, Ray-Ban, etc.).
     */
    private fun pickByHeadsetHeuristic(candidates: List<AudioDeviceInfo>): AudioDeviceInfo? {
        if (candidates.isEmpty()) return null
        val preferred = candidates.firstOrNull { nameLooksLikeHeadset(it.productName?.toString()) }
        if (preferred != null) return preferred
        return candidates.firstOrNull { !nameLooksLikeGlasses(it.productName?.toString()) }
    }

    private fun persistPreferredScoAddress(device: AudioDeviceInfo) {
        val addr = deviceAddress(device)
        if (addr.isBlank()) return
        prefs.edit().putString(KEY_SCO_ADDRESS, addr).apply()
    }

    private fun deviceAddress(device: AudioDeviceInfo): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            device.address?.trim().orEmpty()
        } else {
            ""
        }

    private fun requestAudioFocus(): Boolean {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        focusRequest = null
    }

    companion object {
        private const val TAG = "BluetoothAudioRouter"
        private const val PREFS = "lingo_bt_audio"
        private const val KEY_SCO_ADDRESS = "preferred_sco_address"

        fun nameLooksLikeHeadset(name: String?): Boolean {
            val n = name?.lowercase().orEmpty()
            if (n.isEmpty()) return false
            return listOf("shokz", "openrun", "opencomm", "openswim", "aftershokz", "aeropex")
                .any { it in n }
        }

        fun nameLooksLikeGlasses(name: String?): Boolean {
            val n = name?.lowercase().orEmpty()
            if (n.isEmpty()) return false
            return listOf(
                "even", "g1", "meta", "ray-ban", "rayban", "glasses", "oudmon", "cyan",
                "dm-01", "dm01",
            ).any { it in n }
        }
    }
}

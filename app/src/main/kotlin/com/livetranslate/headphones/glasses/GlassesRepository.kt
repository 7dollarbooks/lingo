package com.livetranslate.headphones.glasses

import android.content.Context
import android.util.Log
import com.livetranslate.headphones.glasses.vendor.GlassesBleClient
import com.livetranslate.headphones.glasses.vendor.GlassesWifiImport
import com.livetranslate.headphones.glasses.vendor.OudmonCaptureDiag
import com.livetranslate.headphones.glasses.vendor.OudmonGlassesBleClient
import com.livetranslate.headphones.vision.ImagePrep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * App-scoped glasses facade backed by [GlassesBleClient] (Oudmon in [vendor]).
 *
 * Mutex discipline: hold only for initiate/mutate; SDK callbacks never take [mutex].
 *
 * CoolingDown is the correctness control. A glasses ask imports the newest JPEG
 * over Wi-Fi (HeyCyan album path). [ImagePrep.extractJpeg] runs before success.
 */
class GlassesRepository(
    context: Context,
    override val timings: GlassesTimings = GlassesTimings.DEFAULT,
    private val ble: GlassesBleClient = OudmonGlassesBleClient(),
) : GlassesFacade {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<GlassesState>(GlassesState.Absent)
    override val state: StateFlow<GlassesState> = _state.asStateFlow()

    private val _coolingDown = MutableStateFlow(false)
    private val _capability = MutableStateFlow(recomputeCapability())
    override val capability: StateFlow<GlassesCapability> = _capability.asStateFlow()

    private val _events = MutableSharedFlow<GlassesEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<GlassesEvent> = _events.asSharedFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedGlassesDevice>>(emptyList())
    override val scannedDevices: StateFlow<List<ScannedGlassesDevice>> = _scannedDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _savedDeviceName = MutableStateFlow(prefs.getString(KEY_NAME, null))
    override val savedDeviceName: StateFlow<String?> = _savedDeviceName.asStateFlow()

    private val _savedDeviceAddress = MutableStateFlow(prefs.getString(KEY_ADDRESS, null))
    override val savedDeviceAddress: StateFlow<String?> = _savedDeviceAddress.asStateFlow()

    @Volatile private var captureGen: Int = 0
    @Volatile private var captureDeferred: CompletableDeferred<CaptureOutcome>? = null
    @Volatile private var captureArmed: Boolean = false
    @Volatile private var photoSignalDeferred: CompletableDeferred<Unit>? = null
    private var coolingJob: Job? = null

    init {
        ble.registerConnectionListener { connected ->
            // Callback path: no mutex
            _state.value = if (connected) GlassesState.Ready else GlassesState.Absent
            publishCapability()
            if (!connected) {
                _events.tryEmit(GlassesEvent.Dropped("ble_disconnected"))
            }
        }
        ble.registerPhotoSignalListener {
            // Bookkeeping only: complete signal if a capture is armed
            if (captureArmed) photoSignalDeferred?.complete(Unit)
        }
    }

    private fun recomputeCapability(): GlassesCapability {
        val st = _state.value
        val cooling = _coolingDown.value
        val ready = st is GlassesState.Ready
        val reason = when {
            st is GlassesState.Unsupported -> CaptureBlockReason.NotReady
            st is GlassesState.Absent -> CaptureBlockReason.NotConnected
            !ready -> CaptureBlockReason.NotReady
            cooling -> CaptureBlockReason.CoolingDown
            else -> null
        }
        return GlassesCapability(
            state = st,
            coolingDown = cooling,
            canCapture = ready && !cooling && ble.isConnected(),
            blockReason = reason,
        )
    }

    private fun publishCapability() {
        _capability.value = recomputeCapability()
    }

    override suspend fun connect(address: String?) {
        val addr = address?.trim().orEmpty().ifEmpty { _savedDeviceAddress.value.orEmpty() }
        if (addr.isEmpty()) {
            Log.w(TAG, "connect requires a device address")
            return
        }
        connectToDevice(addr, _savedDeviceName.value)
    }

    override fun connectToDevice(address: String, name: String?) {
        val addr = address.trim()
        if (addr.isEmpty()) return
        stopScan()
        // Synchronous Connecting before SDK call — do not race a coroutine past connect.
        _state.value = GlassesState.Connecting
        publishCapability()
        if (!name.isNullOrBlank()) saveDevice(name, addr)
        else if (_savedDeviceAddress.value != addr) {
            saveDevice(_savedDeviceName.value ?: "Glasses", addr)
        }
        val result = ble.connect(addr)
        if (result.isFailure) {
            val err = result.exceptionOrNull()
            Log.e(TAG, "connect failed addr=$addr", err)
            val message = err?.message?.takeIf { it.isNotBlank() }
                ?: err?.javaClass?.simpleName
                ?: "Connect failed"
            _state.value = GlassesState.Degraded(message)
            publishCapability()
        }
    }

    override fun tryAutoReconnect(): Boolean {
        val addr = _savedDeviceAddress.value?.trim().orEmpty()
        if (addr.isEmpty()) return false
        if (ble.isConnected()) return true
        Log.i(TAG, "tryAutoReconnect: $addr")
        connectToDevice(addr, _savedDeviceName.value)
        return true
    }

    override fun clearSavedDevice() {
        prefs.edit().remove(KEY_NAME).remove(KEY_ADDRESS).apply()
        _savedDeviceName.value = null
        _savedDeviceAddress.value = null
    }

    private fun saveDevice(name: String, address: String) {
        prefs.edit().putString(KEY_NAME, name).putString(KEY_ADDRESS, address).apply()
        _savedDeviceName.value = name
        _savedDeviceAddress.value = address
    }

    override fun startScan() {
        _scannedDevices.value = emptyList()
        _isScanning.value = true
        ble.startScan(
            appContext,
            onDevice = { name, address, rssi ->
                val scanned = ScannedGlassesDevice(name, address, rssi)
                val current = _scannedDevices.value.toMutableList()
                if (current.none { it.address == scanned.address }) {
                    current.add(0, scanned)
                    current.sortByDescending { it.rssi }
                    _scannedDevices.value = current.take(30)
                    if (current.size >= 30) stopScan()
                }
            },
            onStopped = { _isScanning.value = false },
            onFailed = {
                _isScanning.value = false
                Log.e(TAG, "scan failed code=$it")
            },
        )
    }

    override fun stopScan() {
        ble.stopScan(appContext)
        _isScanning.value = false
    }

    override fun disconnect() {
        stopScan()
        scope.launch {
            mutex.withLock {
                abandonLocked(CaptureOutcome.Timeout)
                ble.disconnect()
                _state.value = GlassesState.Absent
                publishCapability()
            }
        }
    }

    override fun abandonCapture(reason: String) {
        scope.launch {
            mutex.withLock { abandonLocked(CaptureOutcome.Timeout) }
        }
    }

    private fun abandonLocked(outcome: CaptureOutcome) {
        captureGen++
        captureArmed = false
        captureDeferred?.complete(outcome)
        captureDeferred = null
        photoSignalDeferred?.complete(Unit)
        photoSignalDeferred = null
        enterCoolingLocked()
    }

    private fun enterCoolingLocked() {
        _coolingDown.value = true
        publishCapability()
        coolingJob?.cancel()
        val gen = captureGen
        coolingJob = scope.launch {
            val idleWait = timings.signalMs + timings.settleMs + timings.chunkIdleMs
            delay(minOf(idleWait, timings.coolingDownMaxMs))
            mutex.withLock {
                if (_coolingDown.value) {
                    _coolingDown.value = false
                    publishCapability()
                    Log.i(TAG, "cooling cleared (gen=$gen)")
                }
            }
        }
    }

    override suspend fun captureOnce(deadlineEpochMs: Long): CaptureOutcome = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        if (startedAt >= deadlineEpochMs) {
            Log.w(CAP_TAG, "timer=outerDeadline FIRED before start remaining=0 ${OudmonCaptureDiag.snapshot(startedAt)}")
            return@withContext CaptureOutcome.Timeout
        }

        val cap = _capability.value
        when {
            !ble.isConnected() || cap.state is GlassesState.Absent ->
                return@withContext CaptureOutcome.NotConnected
            !cap.canCapture || cap.coolingDown ->
                return@withContext CaptureOutcome.Busy
        }

        val myGen: Int
        val deferred: CompletableDeferred<CaptureOutcome>
        mutex.withLock {
            // Check single-flight before logging start — a concurrent Ask used to
            // log "start" then return Busy and open the phone camera mid-import.
            if (captureDeferred != null || _coolingDown.value) {
                Log.w(CAP_TAG, "captureOnce Busy inFlight=${captureDeferred != null} cooling=${_coolingDown.value}")
                return@withContext CaptureOutcome.Busy
            }
            myGen = ++captureGen
            deferred = CompletableDeferred()
            captureDeferred = deferred
            captureArmed = true
            photoSignalDeferred = CompletableDeferred()
        }

        Log.i(
            CAP_TAG,
            "captureOnce start remaining=${deadlineEpochMs - startedAt}ms " +
                "signalMs=${timings.signalMs} ackMs=${timings.ackMs} dataCapMs=${timings.dataCapMs} " +
                OudmonCaptureDiag.snapshot(startedAt),
        )
        if (!OudmonCaptureDiag.initEnableRan) {
            Log.e(
                CAP_TAG,
                "initEnable NEVER ran on this connection — large-data path likely dead; " +
                    "silence at any budget",
            )
        }

        try {
            // HeyCyan album import: the button photo is a full JPEG on the glasses,
            // pulled over Wi-Fi P2P. The BLE thumbnail arm is not this firmware's path.
            Log.i(CAP_TAG, "wifi import start remaining=${deadlineEpochMs - System.currentTimeMillis()}ms")
            val raw = GlassesWifiImport.importNewestJpeg(
                appContext,
                deadlineEpochMs,
                _savedDeviceName.value,
            )
            if (captureGen != myGen) {
                Log.w(TAG, "stage=abandoned during wifi import")
                return@withContext CaptureOutcome.Failed(CaptureFailureStage.NeverSignalled)
            }
            if (System.currentTimeMillis() >= deadlineEpochMs && raw == null) {
                Log.w(CAP_TAG, "timer=outerDeadline FIRED during wifi import")
                finishOutcome(myGen, CaptureOutcome.Timeout)
                return@withContext CaptureOutcome.Timeout
            }
            val jpeg = raw?.let { ImagePrep.extractJpeg(it) }
            if (jpeg == null || !ImagePrep.isValidJpeg(jpeg)) {
                Log.w(CAP_TAG, "wifi import produced no jpeg raw=${raw?.size ?: 0}")
                finishOutcome(myGen, CaptureOutcome.Failed(CaptureFailureStage.NeverSignalled))
                return@withContext CaptureOutcome.Failed(CaptureFailureStage.NeverSignalled)
            }
            Log.i(TAG, "stage=Success jpeg=${jpeg.size}B elapsed=${System.currentTimeMillis() - startedAt}ms")
            finishOutcome(myGen, CaptureOutcome.Success(jpeg))
            return@withContext CaptureOutcome.Success(jpeg)
        } catch (t: Throwable) {
            Log.e(TAG, "captureOnce failed", t)
            finishOutcome(myGen, CaptureOutcome.Failed(CaptureFailureStage.NeverSignalled))
            return@withContext CaptureOutcome.Failed(CaptureFailureStage.NeverSignalled)
        }
    }

    private suspend fun finishWithAbandon(gen: Int, outcome: CaptureOutcome) {
        mutex.withLock {
            if (gen == captureGen) {
                captureArmed = false
                captureDeferred?.complete(outcome)
                captureDeferred = null
                photoSignalDeferred = null
                captureGen++
                enterCoolingLocked()
            }
        }
    }

    private fun finishOutcome(gen: Int, outcome: CaptureOutcome) {
        if (gen == captureGen) {
            captureArmed = false
            captureDeferred?.complete(outcome)
            captureDeferred = null
            photoSignalDeferred = null
        }
    }

    companion object {
        private const val TAG = "GlassesRepo"
        private const val CAP_TAG = "GlassesCap"
        private const val PREFS = "lingo_glasses"
        private const val KEY_NAME = "saved_device_name"
        private const val KEY_ADDRESS = "saved_device_address"

        @Volatile private var instance: GlassesRepository? = null

        fun getInstance(context: Context, timings: GlassesTimings = GlassesTimings.DEFAULT): GlassesRepository {
            return instance ?: synchronized(this) {
                instance ?: GlassesRepository(context.applicationContext, timings).also { instance = it }
            }
        }
    }
}

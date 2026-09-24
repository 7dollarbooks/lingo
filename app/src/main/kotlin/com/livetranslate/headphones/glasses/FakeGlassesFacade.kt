package com.livetranslate.headphones.glasses

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
 * Test/dev facade: no Oudmon AAR. Failure injection exercises selector/cooling paths.
 *
 * CoolingDown is the correctness gate (DeepSeek A1): after abandon, re-arm is blocked
 * until cooling clears — gen alone cannot protect a new capture from hot-wire leftovers.
 */
class FakeGlassesFacade(
    override val timings: GlassesTimings = GlassesTimings.DEFAULT,
    private val jpegBytes: ByteArray = MINIMAL_JPEG,
    /** Soft-fail first ACK then delay-only warmup (no resend). */
    var softFailAck: Boolean = false,
    /** Delay before photo signal; if past deadline, capture returns Timeout. */
    var signalDelayMs: Long = 50L,
    /** Emit chunks slowly so abandon mid-stream can be tested. */
    var chunkDelayMs: Long = 0L,
    var chunkCount: Int = 1,
    /** Start in Ready when true. */
    var startReady: Boolean = true,
    /**
     * When true, [captureOnce] simulates leftover wire data after abandon (for tests that
     * force-clear cooling). Production path never arms while cooling.
     */
    var injectStaleChunksAfterAbandon: Boolean = false,
) : GlassesFacade {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<GlassesState>(
        if (startReady) GlassesState.Ready else GlassesState.Absent,
    )
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
    private val _savedDeviceName = MutableStateFlow<String?>(null)
    override val savedDeviceName: StateFlow<String?> = _savedDeviceName.asStateFlow()
    private val _savedDeviceAddress = MutableStateFlow<String?>(null)
    override val savedDeviceAddress: StateFlow<String?> = _savedDeviceAddress.asStateFlow()

    @Volatile private var captureGen: Int = 0
    @Volatile private var captureDeferred: CompletableDeferred<CaptureOutcome>? = null
    @Volatile private var captureArmed: Boolean = false
    private var coolingJob: Job? = null

    private fun recomputeCapability(): GlassesCapability {
        val st = _state.value
        val cooling = _coolingDown.value
        val ready = st is GlassesState.Ready
        val connected = st is GlassesState.Ready || st is GlassesState.Connecting || st is GlassesState.Degraded
        val reason = when {
            st is GlassesState.Unsupported -> CaptureBlockReason.NotReady
            !connected || st is GlassesState.Absent -> CaptureBlockReason.NotConnected
            !ready -> CaptureBlockReason.NotReady
            cooling -> CaptureBlockReason.CoolingDown
            else -> null
        }
        return GlassesCapability(
            state = st,
            coolingDown = cooling,
            canCapture = ready && !cooling,
            blockReason = reason,
        )
    }

    private fun publishCapability() {
        _capability.value = recomputeCapability()
    }

    override suspend fun connect(address: String?) {
        mutex.withLock {
            _state.value = GlassesState.Connecting
            publishCapability()
        }
        delay(timings.drainMs)
        mutex.withLock {
            _state.value = GlassesState.Ready
            if (!address.isNullOrBlank()) {
                _savedDeviceAddress.value = address
                _savedDeviceName.value = _savedDeviceName.value ?: "Fake glasses"
            }
            publishCapability()
        }
    }

    override fun connectToDevice(address: String, name: String?) {
        _savedDeviceAddress.value = address
        _savedDeviceName.value = name ?: "Fake glasses"
        scope.launch { connect(address) }
    }

    override fun tryAutoReconnect(): Boolean {
        val addr = _savedDeviceAddress.value ?: return false
        scope.launch { connect(addr) }
        return true
    }

    override fun clearSavedDevice() {
        _savedDeviceName.value = null
        _savedDeviceAddress.value = null
    }

    override fun startScan() {
        _isScanning.value = true
        _scannedDevices.value = listOf(
            ScannedGlassesDevice("Fake HeyCyan", "AA:BB:CC:DD:EE:FF", -50),
        )
        scope.launch {
            delay(500)
            _isScanning.value = false
        }
    }

    override fun stopScan() {
        _isScanning.value = false
    }

    override fun disconnect() {
        stopScan()
        scope.launch {
            mutex.withLock {
                abandonLocked("disconnect")
                _state.value = GlassesState.Absent
                publishCapability()
            }
        }
    }

    override fun abandonCapture(reason: String) {
        // Synchronous so CoolingDown is visible before the next captureOnce (DeepSeek A1).
        kotlinx.coroutines.runBlocking {
            mutex.withLock { abandonLocked(reason) }
        }
    }

    private fun abandonLocked(reason: String) {
        captureGen++
        captureArmed = false
        captureDeferred?.complete(CaptureOutcome.Timeout)
        captureDeferred = null
        enterCoolingLocked()
    }

    private fun enterCoolingLocked() {
        _coolingDown.value = true
        publishCapability()
        coolingJob?.cancel()
        val genAtAbandon = captureGen
        coolingJob = scope.launch {
            val idleWait = timings.signalMs + timings.settleMs + timings.chunkIdleMs
            delay(minOf(idleWait, timings.coolingDownMaxMs))
            mutex.withLock {
                if (_coolingDown.value) {
                    _coolingDown.value = false
                    publishCapability()
                }
            }
        }
    }

    /** Test-only: clear cooling without waiting (dangerous — mimics removing the safety gate). */
    fun forceClearCoolingForTest() {
        scope.launch {
            mutex.withLock {
                coolingJob?.cancel()
                _coolingDown.value = false
                publishCapability()
            }
        }
    }

    override suspend fun captureOnce(deadlineEpochMs: Long): CaptureOutcome = withContext(Dispatchers.Default) {
        val remainingAtStart = deadlineEpochMs - System.currentTimeMillis()
        if (remainingAtStart <= 0L) return@withContext CaptureOutcome.Timeout

        val cap = _capability.value
        when {
            cap.state is GlassesState.Absent || cap.blockReason == CaptureBlockReason.NotConnected ->
                return@withContext CaptureOutcome.NotConnected
            !cap.canCapture || cap.coolingDown ->
                return@withContext CaptureOutcome.Busy
        }

        val deferred: CompletableDeferred<CaptureOutcome>
        val myGen: Int
        mutex.withLock {
            if (captureDeferred != null || _coolingDown.value) {
                return@withContext CaptureOutcome.Busy
            }
            myGen = ++captureGen
            deferred = CompletableDeferred()
            captureDeferred = deferred
            captureArmed = true
        }

        try {
            delay(timings.drainMs)
            if (System.currentTimeMillis() >= deadlineEpochMs || captureGen != myGen) {
                finishTimeout(myGen)
                return@withContext deferred.await()
            }

            // Single ACK window
            val ackOk = !softFailAck
            delay(minOf(timings.ackMs, (deadlineEpochMs - System.currentTimeMillis()).coerceAtLeast(1L)))
            if (!ackOk || softFailAck) {
                delay(timings.ackWarmupDelayMs)
            }

            val signalWait = minOf(timings.signalMs, (deadlineEpochMs - System.currentTimeMillis()).coerceAtLeast(1L))
            delay(minOf(signalDelayMs, signalWait))
            if (signalDelayMs > signalWait || System.currentTimeMillis() >= deadlineEpochMs) {
                abandonOnDeadline(myGen)
                return@withContext deferred.await()
            }

            delay(timings.settleMs)

            // Chunk gather ceiling under deadline (A4)
            val chunkBudget = minOf(
                timings.dataCapMs,
                (deadlineEpochMs - System.currentTimeMillis()).coerceAtLeast(1L),
            )
            val chunkDeadline = System.currentTimeMillis() + chunkBudget

            repeat(chunkCount) { i ->
                if (System.currentTimeMillis() >= deadlineEpochMs || System.currentTimeMillis() >= chunkDeadline) {
                    abandonOnDeadline(myGen)
                    return@withContext deferred.await()
                }
                if (chunkDelayMs > 0) delay(chunkDelayMs)
                if (i == chunkCount - 1 && captureGen == myGen && captureArmed) {
                    completeCapture(myGen, CaptureOutcome.Success(jpegBytes))
                }
            }
            return@withContext deferred.await()
        } catch (t: Throwable) {
            completeCapture(myGen, CaptureOutcome.Timeout)
            throw t
        }
    }

    private fun abandonOnDeadline(myGen: Int) {
        scope.launch {
            mutex.withLock {
                if (captureGen == myGen) {
                    captureArmed = false
                    captureDeferred?.complete(CaptureOutcome.Timeout)
                    captureDeferred = null
                    captureGen++
                    enterCoolingLocked()
                }
            }
        }
    }

    private fun finishTimeout(myGen: Int) {
        if (captureGen == myGen) {
            captureArmed = false
            captureDeferred?.complete(CaptureOutcome.Timeout)
            captureDeferred = null
            scope.launch { mutex.withLock { enterCoolingLocked() } }
        }
    }

    private fun completeCapture(gen: Int, outcome: CaptureOutcome) {
        val d = captureDeferred
        if (gen == captureGen && d != null && captureArmed) {
            d.complete(outcome)
            captureDeferred = null
            captureArmed = false
        }
    }

    companion object {
        /** Minimal valid JPEG (1x1 pixel). */
        val MINIMAL_JPEG: ByteArray = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
            0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0xFF.toByte(), 0xDB.toByte(), 0x00, 0x43, 0x00,
            0x08, 0x06, 0x06, 0x07, 0x06, 0x05, 0x08, 0x07,
            0x07, 0x07, 0x09, 0x09, 0x08, 0x0A, 0x0C, 0x14,
            0x0D, 0x0C, 0x0B, 0x0B, 0x0C, 0x19, 0x12, 0x13,
            0x0F, 0x14, 0x1D, 0x1A, 0x1F, 0x1E, 0x1D, 0x1A,
            0x1C, 0x1C, 0x20, 0x24, 0x2E, 0x27, 0x20, 0x22,
            0x2C, 0x23, 0x1C, 0x1C, 0x28, 0x37, 0x29, 0x2C,
            0x30, 0x31, 0x34, 0x34, 0x34, 0x1F, 0x27, 0x39,
            0x3D, 0x38, 0x32, 0x3C, 0x2E, 0x33, 0x34, 0x32,
            0xFF.toByte(), 0xC0.toByte(), 0x00, 0x0B, 0x08,
            0x00, 0x01, 0x00, 0x01, 0x01, 0x01, 0x11, 0x00,
            0xFF.toByte(), 0xC4.toByte(), 0x00, 0x14, 0x00,
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x08,
            0xFF.toByte(), 0xC4.toByte(), 0x00, 0x14, 0x10,
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08, 0x01,
            0x01, 0x00, 0x00, 0x3F, 0x00, 0x7F.toByte(),
            0xFF.toByte(), 0xD9.toByte(),
        )
    }
}

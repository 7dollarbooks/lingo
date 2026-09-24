package com.livetranslate.headphones.glasses

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Narrow glasses API owned by Lingo. Callers never import `com.oudmon.ble.*`.
 *
 * **CoolingDown is the correctness control (DeepSeek A1).** See Phase 0–1 pins.
 * [captureOnce] returns [CaptureOutcome] (A2). JPEG recovery inside repository (A3).
 * Deadline is hard stop; chunk wait = min(dataCapMs, remaining) (A4).
 */
interface GlassesFacade {
    val state: StateFlow<GlassesState>
    val capability: StateFlow<GlassesCapability>
    val events: SharedFlow<GlassesEvent>
    val timings: GlassesTimings

    val scannedDevices: StateFlow<List<ScannedGlassesDevice>>
    val isScanning: StateFlow<Boolean>
    val savedDeviceName: StateFlow<String?>
    val savedDeviceAddress: StateFlow<String?>

    suspend fun connect(address: String? = null)
    fun connectToDevice(address: String, name: String? = null)
    /** Session-start / app-foreground only — not ambient. Returns true if a reconnect was attempted. */
    fun tryAutoReconnect(): Boolean
    fun clearSavedDevice()
    fun disconnect()

    fun startScan()
    fun stopScan()

    suspend fun captureOnce(deadlineEpochMs: Long): CaptureOutcome
    fun abandonCapture(reason: String = "selector_cutoff")
}

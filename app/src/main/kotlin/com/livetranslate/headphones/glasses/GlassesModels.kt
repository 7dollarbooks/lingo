package com.livetranslate.headphones.glasses

/** Connection / readiness axis — independent of cooling / canCapture. */
sealed class GlassesState {
    data object Absent : GlassesState()
    data object Connecting : GlassesState()
    data object Ready : GlassesState()
    data class Degraded(val reason: String) : GlassesState()
    data object Unsupported : GlassesState()
}

sealed class GlassesEvent {
    data object Wake : GlassesEvent()
    data object Button : GlassesEvent()
    data object BatteryLow : GlassesEvent()
    data class Dropped(val reason: String) : GlassesEvent()
}

enum class CaptureBlockReason {
    NotConnected,
    NotReady,
    CoolingDown,
}

/**
 * Capability for StillSelector: connected ∧ Ready ∧ ¬CoolingDown.
 *
 * **CoolingDown is the correctness control (DeepSeek A1):** set on abandon/deadline
 * until wire-idle ([GlassesTimings.chunkIdleMs]) or [GlassesTimings.coolingDownMaxMs].
 * It gates re-arm so leftover BLE chunks cannot complete a newly armed capture.
 * Local gen checks are bookkeeping only — the wire has no per-capture token.
 */
data class GlassesCapability(
    val state: GlassesState,
    val coolingDown: Boolean,
    val canCapture: Boolean,
    val blockReason: CaptureBlockReason? = null,
)

data class ScannedGlassesDevice(
    val name: String,
    val address: String,
    val rssi: Int,
)

enum class StillProvenance {
    Glasses,
    Phone,
}

enum class FallbackReason {
    GlassesNotReady,
    GlassesCoolingDown,
    GlassesCaptureFailed,
    GlassesTimeout,
    GlassesMalformedPayload,
    GlassesUnsupported,
    UserCancelled,
    GlassesNeverSignalled,
    GlassesSignalledNoChunks,
    GlassesChunksBadJpeg,
}

/** Where a glasses capture attempt died — drives honest status / spoken copy. */
enum class CaptureFailureStage {
    NeverSignalled,
    SignalledNoChunks,
    ChunksBadJpeg,
}

/**
 * Result of [GlassesFacade.captureOnce]. Typed failures so StillSelector can map
 * stage vs Busy without inferring from a bare null (DeepSeek A2).
 */
sealed class CaptureOutcome {
    data class Success(val jpegBytes: ByteArray) : CaptureOutcome() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Success) return false
            return jpegBytes.contentEquals(other.jpegBytes)
        }

        override fun hashCode(): Int = jpegBytes.contentHashCode()
    }

    data class Failed(val stage: CaptureFailureStage) : CaptureOutcome()
    /** @deprecated Prefer [Failed]; kept for Busy/NotConnected mapping. */
    data object Timeout : CaptureOutcome()
    data object MalformedPayload : CaptureOutcome()
    /** CoolingDown / single-flight — cannot arm. */
    data object Busy : CaptureOutcome()
    data object NotConnected : CaptureOutcome()
}

fun CaptureOutcome.toFallbackReason(): FallbackReason? = when (this) {
    is CaptureOutcome.Success -> null
    is CaptureOutcome.Failed -> when (stage) {
        CaptureFailureStage.NeverSignalled -> FallbackReason.GlassesNeverSignalled
        CaptureFailureStage.SignalledNoChunks -> FallbackReason.GlassesSignalledNoChunks
        CaptureFailureStage.ChunksBadJpeg -> FallbackReason.GlassesChunksBadJpeg
    }
    CaptureOutcome.Timeout -> FallbackReason.GlassesTimeout
    CaptureOutcome.MalformedPayload -> FallbackReason.GlassesMalformedPayload
    CaptureOutcome.Busy -> FallbackReason.GlassesCoolingDown
    CaptureOutcome.NotConnected -> FallbackReason.GlassesNotReady
}

fun FallbackReason.toStatusMessage(): String = when (this) {
    FallbackReason.GlassesNeverSignalled ->
        "Glasses didn’t signal a photo — using phone camera…"
    FallbackReason.GlassesSignalledNoChunks ->
        "Glasses sent no image data — using phone camera…"
    FallbackReason.GlassesChunksBadJpeg,
    FallbackReason.GlassesMalformedPayload ->
        "Glasses image unreadable — using phone camera…"
    FallbackReason.GlassesTimeout,
    FallbackReason.GlassesCaptureFailed ->
        "Glasses didn’t return a still — using phone camera…"
    FallbackReason.GlassesCoolingDown ->
        "Glasses busy — using phone camera…"
    FallbackReason.GlassesNotReady,
    FallbackReason.GlassesUnsupported ->
        "Opening camera…"
    FallbackReason.UserCancelled ->
        "Opening camera…"
}

data class StillResult(
    val jpegBytes: ByteArray,
    val provenance: StillProvenance,
    val fallbackReason: FallbackReason? = null,
    val width: Int? = null,
    val height: Int? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StillResult) return false
        return jpegBytes.contentEquals(other.jpegBytes) &&
            provenance == other.provenance &&
            fallbackReason == other.fallbackReason &&
            width == other.width &&
            height == other.height
    }

    override fun hashCode(): Int {
        var result = jpegBytes.contentHashCode()
        result = 31 * result + provenance.hashCode()
        result = 31 * result + (fallbackReason?.hashCode() ?: 0)
        result = 31 * result + (width ?: 0)
        result = 31 * result + (height ?: 0)
        return result
    }
}

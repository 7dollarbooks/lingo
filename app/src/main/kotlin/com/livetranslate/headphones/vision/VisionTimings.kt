package com.livetranslate.headphones.vision

/**
 * Vision mic-hold and UI budgets. Independent of [com.livetranslate.headphones.glasses.GlassesTimings]
 * upload/retry — [visionHoldMaxMs] must not track NetworkMonitor backoff.
 */
data class VisionTimings(
    /** Half-duplex / busyResponding cap from vision start. */
    val visionHoldMaxMs: Long = 15_000L,
    val statusCaptureMs: Long = 1_000L,
    val statusStillWorkingMs: Long = 4_000L,
) {
    fun scaled(factor: Double): VisionTimings = copy(
        visionHoldMaxMs = (visionHoldMaxMs * factor).toLong().coerceAtLeast(1L),
        statusCaptureMs = (statusCaptureMs * factor).toLong().coerceAtLeast(1L),
        statusStillWorkingMs = (statusStillWorkingMs * factor).toLong().coerceAtLeast(1L),
    )

    companion object {
        val DEFAULT = VisionTimings()
    }
}

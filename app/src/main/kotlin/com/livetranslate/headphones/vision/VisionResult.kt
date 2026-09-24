package com.livetranslate.headphones.vision

import android.graphics.Rect
import android.net.Uri
import com.livetranslate.headphones.assistant.VisionKind
import com.livetranslate.headphones.glasses.FallbackReason
import com.livetranslate.headphones.glasses.StillProvenance

/**
 * Queued / displayable result of a vision turn. Always published alongside TTS.
 * MainActivity shows when foreground; otherwise state stays until resume.
 */
sealed class VisionResult {
    abstract val still: StillFile
    abstract val answer: String
    abstract val provenance: StillProvenance
    abstract val fallbackReason: FallbackReason?
    abstract val query: String
    abstract val kind: VisionKind
    abstract val geminiOffline: Boolean

    data class ObjectIdentify(
        override val still: StillFile,
        override val answer: String,
        override val provenance: StillProvenance,
        override val fallbackReason: FallbackReason? = null,
        override val query: String,
        override val geminiOffline: Boolean = false,
    ) : VisionResult() {
        override val kind: VisionKind = VisionKind.ObjectIdentify
    }

    data class SignTranslate(
        override val still: StillFile,
        override val answer: String,
        override val provenance: StillProvenance,
        override val fallbackReason: FallbackReason? = null,
        override val query: String,
        override val geminiOffline: Boolean = false,
        val blocks: List<SignTextBlock> = emptyList(),
        /** e.g. SCRIPT_UNSUPPORTED — showing Gemini translation */
        val banner: String? = null,
        val downloadingModel: Boolean = false,
        val glassesZeroOcr: Boolean = false,
        val imageWidth: Int = 0,
        val imageHeight: Int = 0,
    ) : VisionResult() {
        override val kind: VisionKind = VisionKind.SignTranslate
    }
}

data class SignTextBlock(
    val sourceText: String,
    val englishText: String,
    /** Normalized 0–1 bounding box relative to still width/height. */
    val box: RectFNorm,
)

data class RectFNorm(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    companion object {
        fun fromPixel(rect: Rect, width: Int, height: Int): RectFNorm {
            val w = width.coerceAtLeast(1).toFloat()
            val h = height.coerceAtLeast(1).toFloat()
            return RectFNorm(
                left = rect.left / w,
                top = rect.top / h,
                right = rect.right / w,
                bottom = rect.bottom / h,
            )
        }
    }
}

fun VisionResult.stillUri(): Uri = still.uri

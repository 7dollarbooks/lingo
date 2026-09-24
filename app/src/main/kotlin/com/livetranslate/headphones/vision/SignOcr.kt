package com.livetranslate.headphones.vision

import android.graphics.BitmapFactory
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class OcrBlock(
    val text: String,
    val boundingBox: Rect,
)

data class OcrResult(
    val blocks: List<OcrBlock>,
    val imageWidth: Int,
    val imageHeight: Int,
)

/** Latin-script on-device OCR via ML Kit. */
object SignOcr {
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognize(jpegBytes: ByteArray): OcrResult = withContext(Dispatchers.Default) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
        val width = bounds.outWidth.coerceAtLeast(1)
        val height = bounds.outHeight.coerceAtLeast(1)

        val bitmap = BitmapFactory.decodeByteArray(
            jpegBytes,
            0,
            jpegBytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize(width, height, 1600) },
        ) ?: return@withContext OcrResult(emptyList(), width, height)
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val text = Tasks.await(recognizer.process(image))
            val blocks = text.textBlocks.mapNotNull { block ->
                val box = block.boundingBox ?: return@mapNotNull null
                val t = block.text.trim()
                if (t.isEmpty()) null else OcrBlock(t, box)
            }
            OcrResult(blocks, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    private fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= maxEdge || height / (sample * 2) >= maxEdge) {
            sample *= 2
        }
        return sample
    }
}

package com.livetranslate.headphones.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater

/**
 * Normalize stills to Gemini-ready JPEG. Load-bearing for glasses BLE payloads
 * (raw JPEG, zlib, SOI scan, duplicate SOI strip) — from CyanBridge tryExtractJpeg.
 */
object ImagePrep {
    private const val TAG = "ImagePrep"
    private const val CACHE_DIR = "lingo_vision"
    private const val MAX_FILES = 3
    private const val TTL_MS = 60L * 60L * 1000L

    fun extractJpeg(raw: ByteArray): ByteArray? {
        if (raw.size < 2) return null
        // Already JPEG
        if (raw[0] == 0xFF.toByte() && raw[1] == 0xD8.toByte()) {
            return stripDuplicateJpeg(raw)
        }
        // zlib
        if (raw[0] == 0x78.toByte()) {
            val inflated = inflate(raw) ?: return null
            if (inflated.size >= 2 && inflated[0] == 0xFF.toByte() && inflated[1] == 0xD8.toByte()) {
                return stripDuplicateJpeg(inflated)
            }
            return findJpegSoi(inflated)
        }
        return findJpegSoi(raw)
    }

    /**
     * JPEG small enough to upload. Longest side is at most [maxEdge] pixels and
     * the file is recompressed at [quality]. A result larger than the original
     * is discarded.
     */
    fun shrinkForUpload(jpeg: ByteArray, maxEdge: Int = 1024, quality: Int = 70): ByteArray {
        if (jpeg.size < 4) return jpeg
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return jpeg
        val decoded = BitmapFactory.decodeByteArray(
            jpeg,
            0,
            jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge) },
        ) ?: return jpeg
        val scaled = scaleDown(decoded, maxEdge)
        if (scaled !== decoded) decoded.recycle()
        val out = ByteArrayOutputStream()
        val wrote = scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        scaled.recycle()
        if (!wrote) return jpeg
        val compressed = out.toByteArray()
        Log.i(
            TAG,
            "shrink ${bounds.outWidth}x${bounds.outHeight} ${jpeg.size}B -> ${compressed.size}B",
        )
        return if (compressed.size in 1 until jpeg.size) compressed else jpeg
    }

    private fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= maxEdge || height / (sample * 2) >= maxEdge) {
            sample *= 2
        }
        return sample
    }

    private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longest
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    fun isValidJpeg(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        if (bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return false
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null
    }

    /** Persist for debug; evict TTL/count. Prefer RAM for upload. */
    fun writeCache(contextDir: File, jpeg: ByteArray): File? {
        return runCatching {
            val dir = File(contextDir, CACHE_DIR).apply { mkdirs() }
            evict(dir)
            val out = File(dir, "still_${System.currentTimeMillis()}.jpg")
            out.writeBytes(jpeg)
            out
        }.onFailure { Log.w(TAG, "cache write failed", it) }.getOrNull()
    }

    private fun evict(dir: File) {
        val now = System.currentTimeMillis()
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        files.filter { now - it.lastModified() > TTL_MS }.forEach { it.delete() }
        val remaining = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (remaining.size > MAX_FILES) {
            remaining.take(remaining.size - MAX_FILES).forEach { it.delete() }
        }
    }

    private fun inflate(raw: ByteArray, nowrap: Boolean = false): ByteArray? = runCatching {
        val inflater = Inflater(nowrap)
        inflater.setInput(raw)
        val out = ByteArray(raw.size * 4)
        val n = inflater.inflate(out)
        inflater.end()
        if (n <= 0) null else out.copyOf(n)
    }.getOrNull()

    private fun findJpegSoi(data: ByteArray): ByteArray? {
        for (i in 0 until data.size - 2) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte() && data[i + 2] == 0xFF.toByte()) {
                return stripDuplicateJpeg(data.copyOfRange(i, data.size))
            }
        }
        return null
    }

    private fun stripDuplicateJpeg(jpeg: ByteArray): ByteArray {
        if (jpeg.size < 4) return jpeg
        for (i in 2 until jpeg.size - 2) {
            if (jpeg[i] == 0xFF.toByte() && jpeg[i + 1] == 0xD8.toByte() && jpeg[i + 2] == 0xFF.toByte()) {
                return jpeg.copyOfRange(0, i)
            }
        }
        return jpeg
    }
}

package com.livetranslate.headphones.vision

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Disk cache for vision stills. Every vision turn writes a JPEG file.
 * TTL 1h / max 3 files; files held by an open result screen are not evicted.
 */
class StillFileCache(context: Context) {
    private val dir = File(context.applicationContext.cacheDir, "vision_stills").also { it.mkdirs() }
    private val held = ConcurrentHashMap.newKeySet<String>()

    fun write(jpegBytes: ByteArray): StillFile {
        evictExpired()
        val id = UUID.randomUUID().toString()
        val file = File(dir, "$id.jpg")
        file.writeBytes(jpegBytes)
        evictOverflow()
        return StillFile(id = id, file = file, uri = Uri.fromFile(file))
    }

    fun hold(id: String) {
        held.add(id)
    }

    fun release(id: String) {
        held.remove(id)
        evictExpired()
    }

    fun readBytes(still: StillFile): ByteArray? =
        runCatching { still.file.takeIf { it.exists() }?.readBytes() }.getOrNull()

    private fun evictExpired() {
        val cutoff = System.currentTimeMillis() - TTL_MS
        dir.listFiles()?.forEach { file ->
            val id = file.nameWithoutExtension
            if (id in held) return@forEach
            if (file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    private fun evictOverflow() {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }.orEmpty()
        var excess = files.size - MAX_FILES
        for (file in files) {
            if (excess <= 0) break
            val id = file.nameWithoutExtension
            if (id in held) continue
            if (file.delete()) excess--
        }
    }

    companion object {
        private const val TAG = "StillFileCache"
        private const val TTL_MS = 60L * 60L * 1000L
        private const val MAX_FILES = 3
    }
}

data class StillFile(
    val id: String,
    val file: File,
    val uri: Uri,
)

package com.livetranslate.headphones.memory

import android.content.Context
import java.io.File

/**
 * Notes and photos stay in app files. Expired notes are removed the next time Lingo starts.
 */
class MemoryStore(private val dir: File) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "memory"))

    fun all(): List<MemoryNote> = read().sortedBy { it.createdAtMs }

    fun add(note: MemoryNote, jpeg: ByteArray) {
        dir.mkdirs()
        File(dir, note.photoFileName).writeBytes(jpeg)
        val notes = read().filter { it.id != note.id } + note
        write(notes)
    }

    fun setKeep(id: String, keep: Boolean) {
        val notes = read().map { if (it.id == id) it.copy(keep = keep) else it }
        write(notes)
    }

    fun purgeExpired(nowMs: Long): Int {
        val notes = read()
        val expired = notes.filter { MemoryRetention.isExpired(it.createdAtMs, nowMs, it.keep) }
        if (expired.isEmpty()) return 0
        expired.forEach { File(dir, it.photoFileName).delete() }
        write(notes.filter { it !in expired })
        return expired.size
    }

    private fun read(): List<MemoryNote> {
        val file = File(dir, NOTES)
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { decode(it) }
    }

    private fun write(notes: List<MemoryNote>) {
        dir.mkdirs()
        File(dir, NOTES).writeText(notes.joinToString("\n") { encode(it) })
    }

    companion object {
        private const val NOTES = "notes.tsv"

        fun encode(note: MemoryNote): String = listOf(
            note.id,
            note.createdAtMs.toString(),
            if (note.keep) "1" else "0",
            note.latitude?.toString().orEmpty(),
            note.longitude?.toString().orEmpty(),
            note.heartBpm?.toString().orEmpty(),
            note.systolic?.toString().orEmpty(),
            note.diastolic?.toString().orEmpty(),
            note.steps?.toString().orEmpty(),
            note.calories?.toString().orEmpty(),
            note.photoFileName,
            note.text.replace('\t', ' ').replace('\n', ' '),
        ).joinToString("\t")

        fun decode(line: String): MemoryNote? {
            val parts = line.split('\t')
            if (parts.size < 12) return null
            return MemoryNote(
                id = parts[0],
                createdAtMs = parts[1].toLongOrNull() ?: return null,
                keep = parts[2] == "1",
                latitude = parts[3].toDoubleOrNull(),
                longitude = parts[4].toDoubleOrNull(),
                heartBpm = parts[5].toIntOrNull(),
                systolic = parts[6].toIntOrNull(),
                diastolic = parts[7].toIntOrNull(),
                steps = parts[8].toIntOrNull(),
                calories = parts[9].toIntOrNull(),
                photoFileName = parts[10],
                text = parts.subList(11, parts.size).joinToString("\t"),
            )
        }
    }
}

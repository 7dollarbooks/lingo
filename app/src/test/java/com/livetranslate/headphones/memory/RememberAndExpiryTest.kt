package com.livetranslate.headphones.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RememberAndExpiryTest {
    @Test
    fun rememberThisMatchesAndAQuestionDoesNot() {
        assertTrue(RememberIntent.matches("Remember this!"))
        assertTrue(RememberIntent.matches("remember this spot"))
        assertFalse(RememberIntent.matches("what time is it"))
        assertFalse(RememberIntent.matches("hey where did I park"))
    }

    @Test
    fun notesOlderThanSevenDaysAreDeletedUnlessKept() {
        val dir = File(System.getProperty("java.io.tmpdir"), "lingo-memory-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val store = MemoryStore(dir)
        val now = 1_700_000_000_000L
        val expired = note("old", now - MemoryRetention.MS - 1, keep = false)
        val kept = note("kept", now - MemoryRetention.MS - 1, keep = true)
        val fresh = note("fresh", now - 1_000, keep = false)
        store.add(expired, byteArrayOf(1))
        store.add(kept, byteArrayOf(2))
        store.add(fresh, byteArrayOf(3))

        assertEquals(1, store.purgeExpired(now))
        assertEquals(listOf("kept", "fresh"), store.all().map { it.id })
        assertFalse(File(dir, expired.photoFileName).exists())
        assertTrue(File(dir, kept.photoFileName).exists())
    }

    private fun note(id: String, createdAtMs: Long, keep: Boolean) = MemoryNote(
        id = id,
        text = "Parked by the blue door",
        photoFileName = "$id.jpg",
        latitude = 44.0,
        longitude = -76.9,
        heartBpm = null,
        systolic = null,
        diastolic = null,
        steps = null,
        calories = null,
        createdAtMs = createdAtMs,
        keep = keep,
    )
}

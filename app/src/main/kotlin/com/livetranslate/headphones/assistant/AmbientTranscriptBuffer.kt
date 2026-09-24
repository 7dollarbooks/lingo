package com.livetranslate.headphones.assistant

/**
 * Rolling, timestamped window of ambient transcript text for the real-time help loop.
 *
 * Old text ages out of the window automatically; [consumeIfNew] only returns the window
 * text when there's meaningfully new content since the last check, so the proactive loop
 * doesn't re-send (and re-bill against the free tier) an unchanged window every tick.
 */
class AmbientTranscriptBuffer(private val windowMs: Long = 90_000L) {
    private data class Entry(val text: String, val timestampMs: Long)

    private val entries = ArrayDeque<Entry>()
    private var lastCheckedLength = 0

    @Synchronized
    fun append(text: String) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        entries.addLast(Entry(text, now))
        prune(now)
    }

    @Synchronized
    fun currentWindowText(): String {
        prune(System.currentTimeMillis())
        return entries.joinToString(" ") { it.text }
    }

    /** Returns the current window text only if it grew by at least [minNewChars] since
     * the last call that returned non-null. */
    @Synchronized
    fun consumeIfNew(minNewChars: Int = 15): String? {
        val window = currentWindowText()
        if (window.length - lastCheckedLength < minNewChars) return null
        lastCheckedLength = window.length
        return window.ifBlank { null }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        lastCheckedLength = 0
    }

    private fun prune(now: Long) {
        while (entries.isNotEmpty() && now - entries.first().timestampMs > windowMs) {
            entries.removeFirst()
        }
        // lastCheckedLength refers to the concatenated window length, which shrinks as
        // entries age out; clamp so consumeIfNew doesn't get stuck waiting for a window
        // that can no longer grow past its old peak.
        val currentLength = entries.sumOf { it.text.length + 1 }
        if (lastCheckedLength > currentLength) lastCheckedLength = currentLength
    }
}

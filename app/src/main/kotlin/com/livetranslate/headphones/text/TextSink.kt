package com.livetranslate.headphones.text

import com.livetranslate.headphones.watch.MoyoungWatchClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class TextSource { CAPTION, MEMORY, NAVIGATION }

data class TextLine(
    val id: Long,
    val source: TextSource,
    val speakerLabel: String,
    val text: String,
    val timestampMs: Long,
)

/**
 * Finished lines for the phone. Each one is also handed to the watch.
 * Partials are not published.
 */
class TextSink(private val watch: MoyoungWatchClient) {
    private val lock = Any()
    private var nextId = 1L
    private val _lines = MutableStateFlow<List<TextLine>>(emptyList())
    val lines: StateFlow<List<TextLine>> = _lines.asStateFlow()

    fun publish(source: TextSource, speakerLabel: String, text: String, announce: Boolean = true) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val line = synchronized(lock) {
            TextLine(
                id = nextId++,
                source = source,
                speakerLabel = speakerLabel,
                text = clean,
                timestampMs = System.currentTimeMillis(),
            )
        }
        _lines.update { (it + line).takeLast(MAX_LINES) }
        watch.sendMessage(clean, announce)
    }

    fun clear(source: TextSource) {
        _lines.update { lines -> lines.filter { it.source != source } }
    }

    companion object {
        const val MAX_LINES = 100
    }
}

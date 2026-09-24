package com.livetranslate.headphones.translation

import android.util.Log
import com.livetranslate.headphones.audio.RoutedTtsPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Turns the recognizer's stream of partial/final hypotheses into ordered, spoken
 * segments.
 *
 * Long monologues are flushed incrementally (via [UtteranceSegmenter]) so translation
 * and TTS start before the speaker pauses. Translation runs as segments arrive even while
 * the previous English chunk is still playing; TTS synthesizes one utterance ahead.
 */
class RecognitionPipeline(
    private val processor: TranslationProcessor,
    private val ttsPlayer: RoutedTtsPlayer,
    private val speakerLabel: String,
    minSegmentChars: Int = 8,
    forceSegmentChars: Int = 14,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val segmentChannel = Channel<String>(Channel.UNLIMITED)
    private val playChannel = Channel<String>(Channel.UNLIMITED)
    private val segmenter = UtteranceSegmenter(minSegmentChars, forceSegmentChars) { segment ->
        segmentChannel.trySend(segment)
    }

    init {
        // Translate as soon as segments arrive — does not wait for TTS to finish.
        scope.launch {
            for (segment in segmentChannel) {
                runCatching { processor.translateRecognizedText(segment, speakerLabel) }
                    .onSuccess { english ->
                        if (!english.isNullOrBlank()) playChannel.send(english)
                    }
                    .onFailure { Log.e(TAG, "segment translate failed", it) }
            }
            playChannel.close()
        }
        // Ordered playback with synthesize-ahead prefetch inside the player.
        scope.launch {
            runCatching { ttsPlayer.playQueue(playChannel) }
                .onFailure { Log.e(TAG, "play queue failed", it) }
        }
    }

    fun onPartial(text: String) = segmenter.onPartial(text)

    fun onFinal(text: String) = segmenter.onFinal(text)

    /** Emit any uncommitted remainder, then clear. Used before recognizer error restarts. */
    fun flushPending() = segmenter.flushPending()

    fun close() {
        segmentChannel.close()
        scope.cancel()
    }

    companion object {
        private const val TAG = "RecogPipeline"
    }
}

/**
 * Accumulates recognition hypotheses and emits stable segments.
 *
 * The recognizer emits cumulative partial hypotheses and may revise recent words, so
 * only the prefix that is common across the last few partials is considered "stable"
 * and safe to speak. Stable text is cut at sentence/clause boundaries once it reaches
 * [minSegmentChars], or force-cut at [forceSegmentChars] to bound latency for
 * continuous speech with no punctuation (e.g. Mandarin).
 */
internal class UtteranceSegmenter(
    private val minSegmentChars: Int,
    private val forceSegmentChars: Int,
    private val onSegment: (String) -> Unit,
) {
    private val recent = ArrayDeque<String>()
    private var committed = ""

    fun onPartial(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        recent.addLast(t)
        // Two recent partials are enough to treat the common prefix as stable, which
        // emits the first speakable chunk sooner than waiting for a third revision.
        while (recent.size > 2) recent.removeFirst()

        val stable = longestCommonPrefix(recent)
        if (stable.length <= committed.length) return

        val cut = boundaryCut(stable, committed.length)
        if (cut > committed.length) {
            val segment = stable.substring(committed.length, cut).trim()
            committed = stable.substring(0, cut)
            if (segment.isNotEmpty()) {
                Log.i(TAG, "emit partial segment '$segment'")
                onSegment(segment)
            }
        }
    }

    fun onFinal(text: String) {
        val remainder = remainderAfterCommon(text.trim(), committed).trim()
        reset()
        if (remainder.isNotEmpty()) {
            Log.i(TAG, "emit final remainder '$remainder'")
            onSegment(remainder)
        }
    }

    /**
     * Best-effort flush of text that has appeared in partials but was not yet cut into a
     * segment (e.g. recognizer ended with NO_MATCH after useful partials).
     */
    fun flushPending() {
        val last = recent.lastOrNull()?.trim().orEmpty()
        val remainder = if (last.isNotEmpty()) {
            remainderAfterCommon(last, committed).trim()
        } else {
            ""
        }
        reset()
        if (remainder.isNotEmpty()) {
            Log.i(TAG, "flush pending '$remainder'")
            onSegment(remainder)
        }
    }

    fun reset() {
        recent.clear()
        committed = ""
    }

    private fun boundaryCut(text: String, from: Int): Int {
        val available = text.length - from
        if (available < minSegmentChars) return from
        for (i in text.length downTo from + minSegmentChars) {
            if (isBoundary(text[i - 1])) return i
        }
        if (available >= forceSegmentChars) return text.length
        return from
    }

    private fun isBoundary(c: Char): Boolean = c.isWhitespace() || c in SENTENCE_PUNCT

    private fun remainderAfterCommon(text: String, prefix: String): String {
        if (prefix.isEmpty()) return text
        if (text.startsWith(prefix)) return text.substring(prefix.length)
        val common = longestCommonPrefix(listOf(text, prefix)).length
        return text.substring(common)
    }

    private fun longestCommonPrefix(items: Collection<String>): String {
        if (items.isEmpty()) return ""
        val list = items.toList()
        var prefix = list[0]
        for (i in 1 until list.size) {
            val s = list[i]
            val max = minOf(prefix.length, s.length)
            var j = 0
            while (j < max && prefix[j] == s[j]) j++
            prefix = prefix.substring(0, j)
            if (prefix.isEmpty()) break
        }
        return prefix
    }

    companion object {
        private const val TAG = "UtterSeg"
        private const val SENTENCE_PUNCT = ".!?。！？,，;；、:："
    }
}

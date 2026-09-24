package com.livetranslate.headphones.assistant

/**
 * Fast local heuristic for SPIT mode: decides whether a raw STT transcript looks like a
 * genuine spoken question without any extra cloud round-trip.
 *
 * Intentionally permissive enough for conversational English but conservative enough to
 * ignore passing statements, filler phrases, and the user's repeat of a prior answer.
 */
object QuestionDetector {

    private val QUESTION_STARTERS = setOf(
        // interrogative words
        "who", "what", "when", "where", "why", "how",
        // auxiliary / modal openers
        "is", "are", "was", "were", "am",
        "do", "does", "did",
        "can", "could", "would", "should", "will", "shall",
        "have", "has", "had",
        // polite / indirect question starters
        "tell", "explain", "define", "describe",
        "name", "list", "give",
    )

    // Short phrases that look like question starters but are almost always fillers.
    private val FILLER_REJECTIONS = setOf("can you", "can i", "can we", "what if")

    /** Minimum word count before we even consider something a question. */
    private const val MIN_WORDS = 3

    /**
     * Returns `true` if [text] looks like a spoken question worth sending to Gemini.
     * Operates on the raw STT transcript; punctuation is irrelevant.
     */
    fun isQuestion(text: String): Boolean {
        val normalized = text.trim().lowercase()
        if (normalized.isBlank()) return false

        val words = normalized
            .replace(Regex("[^a-z0-9? ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .split(" ")
            .filter { it.isNotEmpty() }
        if (words.size < MIN_WORDS) return false

        // Explicit question mark from STT is a strong signal.
        if (normalized.endsWith("?")) return true

        val first = words[0]
        val firstTwo = words.take(2).joinToString(" ")

        // Reject bare filler openers that never form a real question ("can you", "what if").
        // Longer "can you …" utterances are still accepted as questions.
        if (words.size <= 3 && firstTwo in FILLER_REJECTIONS) return false

        return first in QUESTION_STARTERS
    }

    /**
     * True when [text] doesn't look mid-sentence (hanging articles/prepositions/auxiliaries).
     * Used so SPIT won't commit on a stable partial like "Who was the 7th president of the".
     */
    fun looksFinished(text: String): Boolean {
        val words = normalize(text).split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val last = words.last()
        return last !in HANGING_ENDINGS
    }

    private val HANGING_ENDINGS = setOf(
        "a", "an", "the", "of", "to", "for", "in", "on", "at", "by", "from", "with",
        "and", "or", "but", "as", "than",
        "is", "are", "was", "were", "am", "be", "been", "being",
        "do", "does", "did", "have", "has", "had",
        "can", "could", "would", "should", "will", "shall", "may", "might",
        "who", "what", "when", "where", "why", "how", "which", "whose",
    )

    /**
     * Normalizes [text] for repeat-ignore matching: lowercase, strip punctuation,
     * collapse whitespace.
     */
    fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Returns `true` if [spokenByUser] is a close enough match to [lastAnswer] to be
     * treated as the user repeating the assistant's answer out loud.
     *
     * Uses token-overlap ratio so STT imprecision and partial repeats still match.
     */
    fun isRepeatOfAnswer(spokenByUser: String, lastAnswer: String): Boolean {
        if (lastAnswer.isBlank() || spokenByUser.isBlank()) return false
        val userTokens = normalize(spokenByUser).split(" ").filter { it.isNotEmpty() }.toSet()
        val answerTokens = normalize(lastAnswer).split(" ").filter { it.isNotEmpty() }.toSet()
        if (answerTokens.isEmpty()) return false
        val overlap = userTokens.intersect(answerTokens).size
        val ratio = overlap.toDouble() / answerTokens.size
        // Require at least 60 % of the answer's tokens to appear in what the user said.
        return ratio >= 0.60
    }
}

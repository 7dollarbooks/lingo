package com.livetranslate.headphones.assistant

import java.util.Locale

/**
 * Answers only when the question's subject is the phone clock.
 * "What time is the game?" and "what date was that" stay with the model.
 */
object LocalFacts {
    private val timePhrases = setOf(
        "what time is it",
        "what's the time",
        "whats the time",
        "what is the time",
    )
    private val datePhrases = setOf(
        "what is today's date",
        "what's today's date",
        "whats today's date",
        "what is todays date",
        "what's todays date",
        "whats todays date",
    )
    private val dayPhrases = setOf(
        "what day is it",
        "what day of the week is it",
    )

    fun answer(query: String): String? = when (normalize(query)) {
        in timePhrases -> DeviceClock.spokenTime()
        in datePhrases -> DeviceClock.spokenDate()
        in dayPhrases -> DeviceClock.spokenDay()
        else -> null
    }

    private fun normalize(query: String): String {
        val cleaned = query.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9' ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.removeSuffix(" right now").trim()
    }
}

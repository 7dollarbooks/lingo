package com.livetranslate.headphones.assistant

import java.util.Locale

/**
 * Questions that need the live web go straight to Gemini.
 * A photo question such as "what is the price of this" is not a web lookup.
 */
object LiveFacts {
    private val webPhrases = listOf(
        "weather",
        "forecast",
        "news",
        "headline",
        "who won",
        "score",
        "stock price",
        "how much does",
        "how much is",
        "what is the price",
        "what's the price",
        "whats the price",
        "price of",
    )

    fun needsWeb(query: String): Boolean {
        val q = query.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9' ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (q.contains(" this")) return false
        return webPhrases.any { q.contains(it) }
    }
}

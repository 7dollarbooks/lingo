package com.livetranslate.headphones.assistant

/**
 * Matches "Hey Lingo", "OK Lingo", or a leading "Lingo" against recognized speech text.
 *
 * On-device STT often mishears "Lingo", so matching is intentionally fuzzy: any
 * hey/ok/okay + a nearby word that looks like "lingo" counts as a wake.
 */
object WakeWordDetector {
    private val wakePrefixes = listOf("hey", "hay", "hi", "ok", "okay", "oke")

    // Exact / near-exact STT spellings we've seen for "Lingo".
    private val exactNames = setOf(
        "lingo", "linko", "lingoh", "lingo.", "lingo!", "lingo?",
        "lindo", "linga", "lingoe", "linggo", "linggoe",
        "bingo", // occasional mishear
        "lingual", "lincoln", // stretch; still useful as wake if preceded by hey/ok
    )

    data class Match(val trailingQuery: String)

    fun findWake(text: String): Match? {
        val normalized = text.lowercase()
            .replace(Regex("[^a-z0-9\\s']"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isEmpty()) return null

        val tokens = normalized.split(' ')
        for (i in tokens.indices) {
            if (tokens[i] !in wakePrefixes) continue
            // "hey lingo …" or "okay lingo …"
            if (i + 1 < tokens.size && looksLikeLingo(tokens[i + 1])) {
                val after = tokens.drop(i + 2).joinToString(" ").trim()
                return Match(after)
            }
            // "heylingo" mashed into one token after "ok" is rare; also catch
            // "hey,lingo" already split. Single token "heylingo" / "oklingo":
            if (looksLikeLingo(tokens[i].removePrefix("hey").removePrefix("hay").removePrefix("ok").removePrefix("okay"))) {
                val after = tokens.drop(i + 1).joinToString(" ").trim()
                return Match(after)
            }
        }

        // "Lingo, take a picture" — the name alone, without hey/ok.
        if (looksLikeLingo(tokens[0])) {
            return Match(tokens.drop(1).joinToString(" ").trim())
        }

        // Whole-string contains an exact phrase as a fallback.
        for (prefix in wakePrefixes) {
            for (name in exactNames) {
                val phrase = "$prefix $name"
                val idx = normalized.indexOf(phrase)
                if (idx >= 0) {
                    val after = normalized.substring(idx + phrase.length).trim()
                    return Match(after)
                }
            }
        }
        return null
    }

    private fun looksLikeLingo(token: String): Boolean {
        val t = token.trim('\'', '.', ',', '!', '?')
        if (t.isEmpty()) return false
        if (t in exactNames) return true
        // Edit-distance-ish: starts with "lin"/"ling" and is short.
        if (t.startsWith("ling") && t.length in 4..10) return true
        if (t.startsWith("lin") && t.length in 4..8 && t.contains('g')) return true
        // Compact forms: heylingo / oklingo already stripped above; bare "lingo"-like.
        return false
    }
}

package com.livetranslate.headphones.memory

/** Trailing words after a wake. "remember this" starts one capture. A normal question does not. */
object RememberIntent {
    fun matches(trailing: String): Boolean {
        val normalized = trailing.lowercase()
            .replace(Regex("[^a-z\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return normalized == "remember this" || normalized.startsWith("remember this ")
    }
}

/** Questions answered from saved notes, not from a new photo. */
object MemoryRecall {
    fun matches(query: String): Boolean {
        val normalized = query.lowercase()
            .replace(Regex("[^a-z\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isEmpty()) return false
        return normalized.contains("where did i park") ||
            normalized.contains("where did i leave") ||
            normalized.contains("what did i remember") ||
            normalized.contains("where was i") ||
            normalized.startsWith("remind me")
    }
}

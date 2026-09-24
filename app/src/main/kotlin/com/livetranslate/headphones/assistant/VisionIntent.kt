package com.livetranslate.headphones.assistant

/**
 * Local heuristic for deciding whether a spoken query needs the camera (identifying an
 * object, or translating/reading physical text) rather than a plain text answer.
 *
 * Deliberately phrase-based rather than single common words like "this"/"that", to avoid
 * routing ordinary questions ("what is this weather like") into a camera capture flow.
 *
 * Sign phrases win over object phrases when both could match.
 */
sealed class VisionKind {
    data object SignTranslate : VisionKind()
    data object ObjectIdentify : VisionKind()
    data object None : VisionKind()
}

object VisionIntent {
    private val signPhrases = listOf(
        "translate this sign",
        "translate this text",
        "translate this label",
        "translate this menu",
        "translate what this says",
        "read this sign",
        "read this label",
        "read this text",
        "read this to me",
        "what does this say",
        "what does that say",
        "translate the sign",
        "read the sign",
        "what does the sign say",
    )

    private val objectPhrases = listOf(
        "what is this",
        "what's this",
        "what is that",
        "what's that",
        "what am i looking at",
        "what am i seeing",
        "describe what i am seeing",
        "describe what i'm seeing",
        "describe whats in front of me",
        "describe what's in front of me",
        "tell me about this object",
        "take a picture",
        "identify this",
        "identify that",
        "identify the object",
        "look at this",
        "what kind of",
        "what type of",
    )

    private val keywords = listOf("camera", "picture", "photo", "snapshot")

    fun kind(query: String): VisionKind {
        val lower = query.lowercase()
        if (signPhrases.any { lower.contains(it) }) return VisionKind.SignTranslate
        if (objectPhrases.any { lower.contains(it) }) return VisionKind.ObjectIdentify
        if (keywords.any { lower.contains(it) }) return VisionKind.ObjectIdentify
        return VisionKind.None
    }

    fun needsCamera(query: String): Boolean = kind(query) !is VisionKind.None
}

package com.livetranslate.headphones

import com.livetranslate.headphones.glasses.FallbackReason
import com.livetranslate.headphones.glasses.StillProvenance

enum class AppMode {
    LISTEN,
    CONVERSATION,
}

/** Top-level UI destination; kept in the ViewModel so rotation doesn't reset it. */
enum class AppScreen {
    MAIN,
    SETTINGS,
    ASSISTANT,
    VITALS,
}

data class TranscriptEntry(
    val speakerLabel: String,
    val sourceLanguage: String,
    val sourceText: String,
    val englishText: String?,
    val discardedEnglish: Boolean,
    val timestampMs: Long = System.currentTimeMillis(),
)

data class SessionStatus(
    val isActive: Boolean = false,
    val scoConnected: Boolean = false,
    val scoDeviceName: String? = null,
    val modelsReady: Boolean = false,
    val loopbackActive: Boolean = false,
    val statusMessage: String = "Idle",
    val mode: AppMode = AppMode.LISTEN,
    val micLevel: Float = 0f,
)

enum class ExchangeKind { ASKED, TIP }

/** One assistant interaction: either a direct answer to a "Hey Lingo" query, or an
 * unprompted tip offered by the real-time ambient-help loop. */
data class AssistantExchange(
    val kind: ExchangeKind,
    val query: String?,
    val response: String,
    val hasImage: Boolean = false,
    /** Glasses vs phone still — never hide pocket-cam fallback. */
    val imageProvenance: StillProvenance? = null,
    val fallbackReason: FallbackReason? = null,
    val timestampMs: Long = System.currentTimeMillis(),
)

data class AssistantStatus(
    val isActive: Boolean = false,
    val listening: Boolean = false,
    val statusMessage: String = "Idle",
    /** True while glasses GATT is held for the active assistant session. */
    val glassesConnected: Boolean = false,
)

package com.livetranslate.headphones.translation

import com.livetranslate.headphones.TranscriptEntry

/** English sentence shown as a caption: the translation, or the source when it was already English. */
fun captionText(entry: TranscriptEntry): String? =
    if (entry.discardedEnglish) {
        entry.sourceText.trim().ifEmpty { null }
    } else {
        entry.englishText?.trim()?.ifEmpty { null }
    }

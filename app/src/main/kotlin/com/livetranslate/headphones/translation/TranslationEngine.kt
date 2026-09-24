package com.livetranslate.headphones.translation

import com.livetranslate.headphones.TranscriptEntry

interface TranslationEngine {
    suspend fun start()
    suspend fun stop()
    suspend fun processSegment(
        pcm: ShortArray,
        speakerLabel: String,
    ): TranscriptEntry?
}

package com.livetranslate.headphones.translation

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.livetranslate.headphones.TranscriptEntry
import java.util.Locale

class TranslationProcessor(
    private val languageModelManager: LanguageModelManager,
    // The language the user is listening to (ML Kit code), or null for Auto. When set,
    // anything not clearly English is translated as this language, which avoids
    // mis-identifying short fragments as the wrong language.
    private val selectedSourceLanguage: String?,
    private val onTranscript: (TranscriptEntry) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val languageIdentifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(0.5f)
            .build(),
    )

    /**
     * Identifies language, translates to English when appropriate, and emits a transcript
     * entry. Returns English text to speak, or null when nothing should be played.
     * Playback is handled by [RecognitionPipeline] so the next segment can translate while
     * TTS is still speaking.
     */
    suspend fun translateRecognizedText(text: String, speakerLabel: String): String? {
        val trimmed = text.trim()
        if (trimmed.length < MIN_CHARS || trimmed.none { it.isLetter() }) {
            Log.i(TAG, "ignoring short/noise segment '$trimmed'")
            return null
        }
        Log.i(TAG, "handle '$trimmed' speaker=$speakerLabel source=${selectedSourceLanguage ?: "auto"}")
        val selected = selectedSourceLanguage
        val script = if (selected != null && selected in NON_LATIN) scriptOf(trimmed) else Script.MIXED
        if (selected != null && script == Script.FOREIGN) {
            Log.i(TAG, "skip language id; script matches $selected")
            return translateAs(trimmed, speakerLabel, selected)
        }
        if (selected != null && script == Script.LATIN) {
            Log.i(TAG, "latin text while listening to $selected — discarded")
            discardEnglish(speakerLabel, "en", trimmed)
            return null
        }
        val detectedLang = Tasks.await(languageIdentifier.identifyLanguage(trimmed))
        Log.i(TAG, "detected language=$detectedLang")

        val sourceLang: String
        if (selected != null) {
            if (selected != TranslateLanguage.ENGLISH && isEnglish(detectedLang)) {
                discardEnglish(speakerLabel, detectedLang, trimmed)
                return null
            }
            sourceLang = selected
        } else {
            if (isEnglish(detectedLang)) {
                discardEnglish(speakerLabel, detectedLang, trimmed)
                return null
            }
            val mapped = mapToTranslateLanguage(detectedLang)
            if (mapped == null || !languageModelManager.isModelDownloaded(mapped)) {
                Log.i(TAG, "auto: no downloaded model for '$detectedLang' — ignoring")
                onStatus("Ignored — no model for detected language")
                return null
            }
            sourceLang = mapped
        }

        return translateAs(trimmed, speakerLabel, sourceLang)
    }

    private fun translateAs(text: String, speakerLabel: String, sourceLang: String): String? {
        if (!languageModelManager.isModelDownloaded(sourceLang)) {
            onStatus("Download ${SupportedLanguages.labelFor(sourceLang)} model first")
            return null
        }
        val translator = languageModelManager.translatorFor(sourceLang)
        val english = Tasks.await(translator.translate(text)).trim()
        Log.i(TAG, "translated ($sourceLang) '$text' -> '$english'")
        if (english.isEmpty()) return null
        onTranscript(
            TranscriptEntry(
                speakerLabel = speakerLabel,
                sourceLanguage = sourceLang,
                sourceText = text,
                englishText = english,
                discardedEnglish = false,
            ),
        )
        onStatus("Translated to English")
        return english
    }

    private fun discardEnglish(speakerLabel: String, detectedLang: String, text: String) {
        onTranscript(
            TranscriptEntry(
                speakerLabel = speakerLabel,
                sourceLanguage = detectedLang,
                sourceText = text,
                englishText = null,
                discardedEnglish = true,
            ),
        )
        onStatus("English detected — discarded")
    }

    private fun isEnglish(code: String): Boolean =
        code == "en" || code.startsWith("en-")

    private fun mapToTranslateLanguage(code: String): String? {
        if (code == "und" || code.isBlank()) return null
        val normalized = code.lowercase(Locale.US)
        return when {
            normalized.startsWith("zh") -> TranslateLanguage.CHINESE
            normalized.startsWith("pt") -> TranslateLanguage.PORTUGUESE
            else -> SupportedLanguages.options.firstOrNull { it.code == normalized }?.code
        }
    }

    private enum class Script { LATIN, FOREIGN, MIXED }

    /** Latin letters versus another script, so a known source can skip language id. */
    private fun scriptOf(text: String): Script {
        var latin = false
        var foreign = false
        for (c in text) {
            if (!c.isLetter()) continue
            if (c.code <= 0x024F) latin = true else foreign = true
        }
        return when {
            foreign && !latin -> Script.FOREIGN
            latin && !foreign -> Script.LATIN
            else -> Script.MIXED
        }
    }

    companion object {
        private const val TAG = "TransProc"
        private const val MIN_CHARS = 2
        private val NON_LATIN = setOf(
            TranslateLanguage.CHINESE,
            TranslateLanguage.JAPANESE,
            TranslateLanguage.KOREAN,
            TranslateLanguage.ARABIC,
            TranslateLanguage.HINDI,
            TranslateLanguage.RUSSIAN,
            TranslateLanguage.THAI,
            TranslateLanguage.UKRAINIAN,
            TranslateLanguage.GREEK,
        )
    }
}

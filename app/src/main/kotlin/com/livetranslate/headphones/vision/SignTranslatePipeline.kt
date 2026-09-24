package com.livetranslate.headphones.vision

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.livetranslate.headphones.assistant.LlmClient
import com.livetranslate.headphones.glasses.StillProvenance
import com.livetranslate.headphones.translation.LanguageModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

sealed class SignPipelineOutcome {
    data class OverlayReady(
        val blocks: List<SignTextBlock>,
        val answer: String,
        val imageWidth: Int,
        val imageHeight: Int,
    ) : SignPipelineOutcome()

    data class GlassesZeroOcr(val message: String) : SignPipelineOutcome()

    data class Downloading(val message: String = "Downloading translation model…") : SignPipelineOutcome()

    data class GeminiFallback(
        val answer: String,
        val banner: String?,
        val geminiOffline: Boolean = false,
        val imageWidth: Int = 0,
        val imageHeight: Int = 0,
    ) : SignPipelineOutcome()
}

/**
 * SignTranslate: Latin OCR → ML Kit translate → overlay; else Sign-Gemini prompt.
 */
class SignTranslatePipeline(
    private val languageModelManager: LanguageModelManager,
    private val llmClient: LlmClient,
) {
    private val languageId = LanguageIdentification.getClient()

    suspend fun run(
        jpegBytes: ByteArray,
        provenance: StillProvenance,
        onDownloading: (suspend () -> Unit)? = null,
        onGeminiText: ((String) -> Unit)? = null,
    ): SignPipelineOutcome = withContext(Dispatchers.IO) {
        val ocr = SignOcr.recognize(jpegBytes)
        if (ocr.blocks.isEmpty()) {
            return@withContext if (provenance == StillProvenance.Glasses) {
                SignPipelineOutcome.GlassesZeroOcr(GLASSES_ZERO_OCR_MESSAGE)
            } else {
                return@withContext geminiFallback(jpegBytes, banner = BANNER_NO_TEXT, ocr.imageWidth, ocr.imageHeight, onGeminiText)
            }
        }

        val joined = ocr.blocks.joinToString("\n") { it.text }
        val langCode = identifyLanguage(joined)
        if (langCode == null || langCode == "und" || langCode == "en") {
            // English / unidentified — still show overlay with source as English.
            if (langCode == "en") {
                val blocks = ocr.blocks.map {
                    SignTextBlock(
                        sourceText = it.text,
                        englishText = it.text,
                        box = RectFNorm.fromPixel(it.boundingBox, ocr.imageWidth, ocr.imageHeight),
                    )
                }
                return@withContext SignPipelineOutcome.OverlayReady(
                    blocks = blocks,
                    answer = blocks.joinToString("\n") { it.englishText },
                    imageWidth = ocr.imageWidth,
                    imageHeight = ocr.imageHeight,
                )
            }
            // Non-Latin / und — Gemini extract+translate
            return@withContext geminiFallback(
                jpegBytes,
                banner = BANNER_SCRIPT_UNSUPPORTED,
                ocr.imageWidth,
                ocr.imageHeight,
                onGeminiText,
            )
        }

        val downloaded = languageModelManager.isModelDownloaded(langCode)
        if (!downloaded) {
            onDownloading?.invoke()
            val download = languageModelManager.download(langCode, requireWifi = false)
            if (download.isFailure) {
                Log.w(TAG, "translate pack download failed for $langCode", download.exceptionOrNull())
                return@withContext geminiFallback(
                    jpegBytes,
                    banner = BANNER_MODEL_FAILED,
                    ocr.imageWidth,
                    ocr.imageHeight,
                    onGeminiText,
                )
            }
        }

        val translator = languageModelManager.translatorFor(langCode)
        val blocks = coroutineScope {
            ocr.blocks.map { block ->
                async {
                    val english = runCatching {
                        Tasks.await(translator.translate(block.text))
                    }.getOrElse { block.text }
                    SignTextBlock(
                        sourceText = block.text,
                        englishText = english.trim().ifBlank { block.text },
                        box = RectFNorm.fromPixel(block.boundingBox, ocr.imageWidth, ocr.imageHeight),
                    )
                }
            }.awaitAll()
        }
        SignPipelineOutcome.OverlayReady(
            blocks = blocks,
            answer = blocks.joinToString("\n") { it.englishText },
            imageWidth = ocr.imageWidth,
            imageHeight = ocr.imageHeight,
        )
    }

    private suspend fun geminiFallback(
        jpegBytes: ByteArray,
        banner: String?,
        imageWidth: Int,
        imageHeight: Int,
        onGeminiText: ((String) -> Unit)?,
    ): SignPipelineOutcome {
        return runCatching {
            val answer = llmClient.askWithImage(
                prompt = SIGN_GEMINI_PROMPT,
                jpegBytes = jpegBytes,
                systemInstruction = SIGN_SYSTEM,
                maxOutputTokens = 120,
                onText = onGeminiText,
            )
            SignPipelineOutcome.GeminiFallback(
                answer = answer,
                banner = banner,
                geminiOffline = false,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
            )
        }.getOrElse { err ->
            Log.w(TAG, "Sign Gemini fallback failed", err)
            SignPipelineOutcome.GeminiFallback(
                answer = GEMINI_OFFLINE_MESSAGE,
                banner = banner,
                geminiOffline = true,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
            )
        }
    }

    private fun identifyLanguage(text: String): String? =
        runCatching { Tasks.await(languageId.identifyLanguage(text)) }.getOrNull()

    companion object {
        private const val TAG = "SignTranslatePipeline"

        const val SIGN_GEMINI_PROMPT =
            "Extract any legible text in the image and translate to English. " +
                "If no text is legible, say so; do not describe objects."

        private const val SIGN_SYSTEM =
            "You extract and translate text from photos. Reply in English only. " +
                "Do not describe scenes or objects — only text content and translation."

        const val OBJECT_IDENTIFY_PROMPT =
            "In one or two short sentences, identify what this is. Be as specific as the " +
                "image allows. If you are unsure, say so. Do not invent fine print or labels " +
                "you cannot read."

        const val GLASSES_ZERO_OCR_MESSAGE =
            "I couldn't read that from the glasses."

        const val GEMINI_OFFLINE_MESSAGE =
            "Couldn't reach Gemini — tap retry"

        const val BANNER_SCRIPT_UNSUPPORTED =
            "SCRIPT_UNSUPPORTED — showing Gemini translation"

        const val BANNER_NO_TEXT =
            "No text found — showing Gemini translation"

        const val BANNER_MODEL_FAILED =
            "Translation model unavailable — showing Gemini translation"
    }
}

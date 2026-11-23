package com.livetranslate.headphones.assistant

import android.util.Log
import com.livetranslate.headphones.vision.ImagePrep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One question at a time: Nitro, then Gemini. The lock keeps a cellular process bind
 * from landing on an open Tailscale socket.
 */
class ModelRouter(
    private val settings: AssistantSettings,
    private val llm: LlmClient,
    private val ollama: OllamaClient,
) {
    private val lock = Mutex()
    private val geminiWarmed = AtomicBoolean(false)

    fun warmupNitro() {
        runCatching { ollama.warmup() }
            .onFailure { Log.w(TAG, "Nitro warmup skipped", it) }
    }

    suspend fun ask(
        feature: String,
        prompt: String,
        history: List<Turn> = emptyList(),
        systemInstruction: String? = null,
        maxOutputTokens: Int = 120,
        shortcuts: Boolean = true,
        geminiFallback: Boolean = true,
        onText: ((String) -> Unit)? = null,
    ): String = lock.withLock {
        withContext(Dispatchers.IO) {
            val type = if (feature == "proactive") "proactive" else "text"
            if (!settings.nitroFirst.value) {
                FallbackLog.route(feature, type, "nitro-off", GEMINI_MODEL, prompt)
                return@withContext llm.ask(prompt, history, systemInstruction, maxOutputTokens)
            }
            if (shortcuts) {
                LocalFacts.answer(prompt)?.let { local ->
                    FallbackLog.route(feature, "clock", "local", "local", prompt)
                    onText?.invoke(local)
                    return@withContext local
                }
                if (LiveFacts.needsWeb(prompt)) {
                    FallbackLog.route(feature, "live-facts", "live-facts", GEMINI_MODEL, prompt)
                    warmGemini()
                    return@withContext llm.ask(prompt, history, systemInstruction, maxOutputTokens)
                }
            }
            val nitro = runCatching {
                ollama.complete(
                    prompt = prompt,
                    history = history,
                    systemInstruction = systemInstruction,
                    maxTokens = maxOutputTokens,
                    imageJpeg = null,
                    onText = onText,
                )
            }
            finishText(feature, type, prompt, history, systemInstruction, maxOutputTokens, geminiFallback, onText, nitro)
        }
    }

    suspend fun askWithImage(
        feature: String,
        prompt: String,
        jpegBytes: ByteArray,
        systemInstruction: String? = null,
        maxOutputTokens: Int = 80,
        history: List<Turn> = emptyList(),
        onText: ((String) -> Unit)? = null,
    ): String = lock.withLock {
        withContext(Dispatchers.IO) {
            val contextual = withHistory(prompt, history)
            val jpeg = ImagePrep.shrinkForUpload(jpegBytes)
            if (!settings.nitroFirst.value) {
                FallbackLog.route(feature, "vision", "nitro-off", GEMINI_MODEL, prompt)
                return@withContext llm.askWithImage(
                    contextual,
                    jpeg,
                    systemInstruction,
                    maxOutputTokens,
                    onText,
                )
            }
            val nitro = runCatching {
                ollama.complete(
                    prompt = contextual,
                    history = emptyList(),
                    systemInstruction = systemInstruction,
                    maxTokens = maxOutputTokens,
                    imageJpeg = jpeg,
                    onText = onText,
                )
            }
            nitro.fold(
                onSuccess = { result ->
                    if (result.spoke) {
                        FallbackLog.route(feature, "vision", "answered", settings.nitroModel.value, prompt)
                        return@withContext result.text
                    }
                    val reason = hedgeReason(result.text)
                    if (reason == null) {
                        FallbackLog.route(feature, "vision", "answered", settings.nitroModel.value, prompt)
                        if (result.text.isNotBlank()) onText?.invoke(result.text)
                        return@withContext result.text
                    }
                    FallbackLog.route(feature, "vision", reason, GEMINI_MODEL, prompt)
                    warmGemini()
                    llm.askWithImage(contextual, jpeg, systemInstruction, maxOutputTokens, onText)
                },
                onFailure = { err ->
                    val reason = failureReason(err)
                    FallbackLog.route(feature, "vision", reason, GEMINI_MODEL, prompt)
                    warmGemini()
                    llm.askWithImage(contextual, jpeg, systemInstruction, maxOutputTokens, onText)
                },
            )
        }
    }

    private suspend fun finishText(
        feature: String,
        type: String,
        prompt: String,
        history: List<Turn>,
        systemInstruction: String?,
        maxOutputTokens: Int,
        geminiFallback: Boolean,
        onText: ((String) -> Unit)?,
        nitro: Result<OllamaResult>,
    ): String = nitro.fold(
        onSuccess = { result ->
            if (result.spoke) {
                FallbackLog.route(feature, type, "answered", settings.nitroModel.value, prompt)
                return@fold result.text
            }
            val reason = hedgeReason(result.text)
            if (reason == null) {
                FallbackLog.route(feature, type, "answered", settings.nitroModel.value, prompt)
                if (result.text.isNotBlank()) onText?.invoke(result.text)
                return@fold result.text
            }
            if (!geminiFallback) {
                FallbackLog.route(feature, type, reason, settings.nitroModel.value, prompt)
                return@fold result.text
            }
            FallbackLog.route(feature, type, reason, GEMINI_MODEL, prompt)
            warmGemini()
            val gemini = llm.ask(prompt, history, systemInstruction, maxOutputTokens)
            if (gemini.isNotBlank()) onText?.invoke(gemini)
            gemini
        },
        onFailure = { err ->
            val reason = if (!geminiFallback && failureReason(err) == "unreachable") {
                "nitro-off"
            } else {
                failureReason(err)
            }
            if (!geminiFallback) {
                FallbackLog.route(feature, type, reason, settings.nitroModel.value, prompt)
                throw LlmException("Nitro is unreachable.", err)
            }
            FallbackLog.route(feature, type, reason, GEMINI_MODEL, prompt)
            warmGemini()
            val gemini = llm.ask(prompt, history, systemInstruction, maxOutputTokens)
            if (gemini.isNotBlank()) onText?.invoke(gemini)
            gemini
        },
    )

    private fun warmGemini() {
        if (geminiWarmed.compareAndSet(false, true)) {
            llm.warmup()
        }
    }

    private fun withHistory(prompt: String, history: List<Turn>): String {
        if (history.isEmpty()) return prompt
        val lines = history.takeLast(8).joinToString("\n") { "${it.role}: ${it.text}" }
        return "Recent conversation:\n$lines\n\n$prompt"
    }

    private fun failureReason(err: Throwable): String = when (err) {
        is OllamaException -> if (err.timedOut) "timeout" else "unreachable"
        else -> "unreachable"
    }

    private fun hedgeReason(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return "empty"
        if (trimmed.startsWith("DON'T KNOW", ignoreCase = true)) return "hedge"
        if (trimmed.startsWith("DONT KNOW", ignoreCase = true)) return "hedge"
        val bare = trimmed.trimEnd('.', '!', ' ').lowercase()
        if (bare in EXACT_HEDGES) return "hedge"
        return null
    }

    companion object {
        private const val TAG = "ModelRouter"
        const val GEMINI_MODEL = "gemini-flash-lite-latest"
        private val EXACT_HEDGES = setOf(
            "i don't know",
            "i dont know",
            "i can't tell",
            "i cant tell",
            "i cannot tell",
            "i'm not sure",
            "im not sure",
            "i am not sure",
        )
    }
}

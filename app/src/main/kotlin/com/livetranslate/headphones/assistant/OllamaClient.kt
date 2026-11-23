package com.livetranslate.headphones.assistant

import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class OllamaException(
    message: String,
    val timedOut: Boolean,
    cause: Throwable? = null,
) : Exception(message, cause)

data class OllamaResult(val text: String, val spoke: Boolean)

/**
 * Streaming client for the Nitro's Ollama. Uses the native chat API so qwen3-vl
 * can be asked with thinking off; otherwise the reply budget is spent before any
 * spoken words. Speech is held until the reply is clearly not a don't-know sentinel.
 */
class OllamaClient(private val settings: AssistantSettings) {
    fun warmup() {
        val base = settings.nitroBaseUrl.value.trim().trimEnd('/')
        val model = settings.nitroModel.value.ifBlank { AssistantSettings.DEFAULT_NITRO_MODEL }
        val body = JSONObject()
            .put("model", model)
            .put("stream", false)
            .put("think", false)
            .put("keep_alive", KEEP_ALIVE)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Hi")))
            .put("options", JSONObject().put("num_predict", 1).put("num_ctx", NUM_CTX))
        val http = client(connectSec = 2, readSec = 40)
        val request = Request.Builder()
            .url("$base/api/chat")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw OllamaException("Nitro warmup HTTP ${response.code}", timedOut = false)
            }
        }
        Log.i(TAG, "Nitro model warmed")
    }

    fun complete(
        prompt: String,
        history: List<Turn>,
        systemInstruction: String?,
        maxTokens: Int,
        imageJpeg: ByteArray?,
        onText: ((String) -> Unit)?,
    ): OllamaResult {
        val base = settings.nitroBaseUrl.value.trim().trimEnd('/')
        val connect = settings.nitroConnectTimeoutSec.value.coerceIn(1, 30)
        val read = settings.nitroReadTimeoutSec.value.coerceIn(2, 120)
        val http = client(connect.toLong(), read.toLong())
        val body = buildBody(prompt, history, systemInstruction, maxTokens, imageJpeg)
        val request = Request.Builder()
            .url("$base/api/chat")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        var gotHeaders = false
        val assembled = StringBuilder()
        var spoke = false
        return try {
            http.newCall(request).execute().use { response ->
                gotHeaders = true
                if (!response.isSuccessful) {
                    val raw = response.body?.string().orEmpty()
                    Log.w(TAG, "Nitro HTTP ${response.code}: $raw")
                    throw OllamaException("Nitro returned ${response.code}", timedOut = false)
                }
                val source = response.body?.byteStream()
                    ?: throw OllamaException("Nitro returned an empty body", timedOut = false)
                val reader = BufferedReader(InputStreamReader(source, Charsets.UTF_8))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val rawLine = line?.trim().orEmpty()
                    if (rawLine.isEmpty()) continue
                    val payload = rawLine.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue
                    val chunk = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                    val piece = deltaText(chunk)
                    if (piece.isNotEmpty()) {
                        assembled.append(piece)
                        if (release(assembled.toString(), onText, spoke)) spoke = true
                    }
                    if (chunk.optBoolean("done")) break
                }
                OllamaResult(assembled.toString().trim(), spoke)
            }
        } catch (e: OllamaException) {
            if (spoke) OllamaResult(assembled.toString().trim(), spoke = true) else throw e
        } catch (e: SocketTimeoutException) {
            if (spoke) {
                OllamaResult(assembled.toString().trim(), spoke = true)
            } else {
                throw OllamaException(
                    if (gotHeaders) "Nitro timed out" else "Nitro unreachable",
                    timedOut = gotHeaders,
                    cause = e,
                )
            }
        } catch (e: IOException) {
            if (spoke) {
                OllamaResult(assembled.toString().trim(), spoke = true)
            } else {
                throw OllamaException("Nitro unreachable", timedOut = false, cause = e)
            }
        }
    }

    private fun release(full: String, onText: ((String) -> Unit)?, already: Boolean): Boolean {
        if (onText == null) return already
        if (already) {
            onText(full)
            return true
        }
        if (heldBack(full)) return false
        onText(full)
        return true
    }

    private fun heldBack(full: String): Boolean {
        val trimmed = full.trimStart()
        if (trimmed.startsWith("DON'T KNOW", ignoreCase = true)) return true
        if (trimmed.startsWith("DONT KNOW", ignoreCase = true)) return true
        val bare = trimmed.trimEnd('.', '!', ' ').lowercase()
        if (bare.isEmpty()) return true
        return EXACT_HEDGES.any { hedge -> hedge == bare || hedge.startsWith(bare) }
    }

    private fun deltaText(chunk: JSONObject): String {
        val native = chunk.optJSONObject("message")?.optString("content").orEmpty()
        if (native.isNotEmpty()) return native
        val choices = chunk.optJSONArray("choices") ?: return ""
        if (choices.length() == 0) return ""
        val choice = choices.getJSONObject(0)
        val delta = choice.optJSONObject("delta")?.optString("content").orEmpty()
        if (delta.isNotEmpty()) return delta
        return choice.optJSONObject("message")?.optString("content").orEmpty()
    }

    private fun buildBody(
        prompt: String,
        history: List<Turn>,
        systemInstruction: String?,
        maxTokens: Int,
        imageJpeg: ByteArray?,
    ): JSONObject {
        val clock = DeviceClock.block()
        val suffix = "If you cannot answer from the conversation and the device clock, " +
            "start the reply with DON'T KNOW."
        val system = listOfNotNull(
            systemInstruction?.trim()?.ifBlank { null },
            clock,
            suffix,
        ).joinToString("\n\n")
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", system))
        history.forEach { turn ->
            val role = if (turn.role == "model") "assistant" else "user"
            messages.put(JSONObject().put("role", role).put("content", turn.text))
        }
        val user = JSONObject().put("role", "user").put("content", prompt)
        if (imageJpeg != null) {
            val b64 = Base64.encodeToString(imageJpeg, Base64.NO_WRAP)
            user.put("images", JSONArray().put(b64))
        }
        messages.put(user)
        return JSONObject()
            .put("model", settings.nitroModel.value.ifBlank { AssistantSettings.DEFAULT_NITRO_MODEL })
            .put("stream", true)
            .put("think", false)
            .put("keep_alive", KEEP_ALIVE)
            .put(
                "options",
                JSONObject()
                    .put("num_predict", maxTokens)
                    .put("num_ctx", NUM_CTX),
            )
            .put("messages", messages)
    }

    private fun client(connectSec: Long, readSec: Long): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(connectSec, TimeUnit.SECONDS)
            .readTimeout(readSec, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

    companion object {
        private const val TAG = "OllamaClient"
        private const val KEEP_ALIVE = "30m"
        private const val NUM_CTX = 4096
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
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

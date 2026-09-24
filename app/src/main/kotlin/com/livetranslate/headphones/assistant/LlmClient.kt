package com.livetranslate.headphones.assistant

import android.util.Base64
import android.util.Log
import com.livetranslate.headphones.vision.ImagePrep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** A single turn in the running conversation, "user" or "model". */
data class Turn(val role: String, val text: String)

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thin REST client for the Gemini API free tier. No SDK dependency — just OkHttp + manual
 * JSON, matching this app's lean dependency footprint.
 *
 * Cloud calls try a process bind to cellular first. If the phone refuses that bind, the
 * call goes on the active default network with no socket bind. At home that default is
 * Wi-Fi. Away from home the default is cellular, and [NetworkMonitor.runOnCellular]
 * already sends on that route when the bind is refused.
 */
class LlmClient(
    private val settings: AssistantSettings,
    private val networkMonitor: NetworkMonitor,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        // Keep the TLS session warm between questions so SPIT doesn't pay handshake cost
        // on every ask.
        .pingInterval(20, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .build()

    /**
     * Opens a TLS connection to Gemini on the cell route, then home Wi-Fi if cell fails,
     * so the first real question doesn't pay DNS + handshake latency.
     */
    fun warmup() {
        val apiKey = settings.apiKey.value ?: return
        runCatching {
            val request = Request.Builder()
                .url("$ENDPOINT_BASE/$MODEL")
                .addHeader("x-goog-api-key", apiKey)
                .get()
                .build()
            executePreferred { httpClient ->
                httpClient.newCall(request).execute().use { /* drain / discard */ }
            }
            Log.i(TAG, "Gemini connection warmed")
        }.onFailure { Log.w(TAG, "Gemini warmup skipped", it) }
    }

    /** Plain text Q&A / proactive ambient check — no image. */
    suspend fun ask(
        prompt: String,
        history: List<Turn> = emptyList(),
        systemInstruction: String? = null,
        maxOutputTokens: Int = 120,
    ): String = generate(
        systemInstruction,
        history + Turn("user", prompt),
        imageJpeg = null,
        maxOutputTokens = maxOutputTokens,
    )

    /** Vision request: a single still frame plus a question about it. */
    suspend fun askWithImage(
        prompt: String,
        jpegBytes: ByteArray,
        systemInstruction: String? = null,
        maxOutputTokens: Int = 80,
        onText: ((String) -> Unit)? = null,
    ): String = generate(
        systemInstruction,
        listOf(Turn("user", prompt)),
        imageJpeg = ImagePrep.shrinkForUpload(jpegBytes),
        maxOutputTokens = maxOutputTokens,
        onText = onText,
    )

    private suspend fun generate(
        systemInstruction: String?,
        turns: List<Turn>,
        imageJpeg: ByteArray?,
        maxOutputTokens: Int = 120,
        onText: ((String) -> Unit)? = null,
    ): String = withContext(Dispatchers.IO) {
        val apiKey = settings.apiKey.value
        if (apiKey.isNullOrBlank()) {
            throw LlmException("No Gemini API key set — add one in Settings.")
        }
        val instruction = withDeviceContext(systemInstruction)
        // Flash-Lite doesn't use extended thinking — skip thinkingConfig entirely so we
        // never pay for a 400 + retry round-trip on aliases that reject the field.
        val body = buildRequestBody(instruction, turns, imageJpeg, maxOutputTokens = maxOutputTokens)
        val text = executePreferred { httpClient ->
            callGeminiStream(httpClient, apiKey, body, onText)
        }
        text ?: throw LlmException("The assistant returned an empty response.")
    }

    private fun <T> executePreferred(block: (OkHttpClient) -> T): T {
        if (networkMonitor.cellularNetworkOrNull() != null) {
            try {
                Log.i(TAG, "Gemini via cellular")
                return networkMonitor.runOnCellular { block(client) }
            } catch (e: Exception) {
                Log.w(TAG, "cellular Gemini failed", e)
            }
        }
        val transport = networkMonitor.currentTransport()
        if (transport == NetworkTransport.NONE) {
            throw LlmException("Connect to mobile data or your home Wi-Fi to use Lingo.")
        }
        // bindProcessToNetwork(cellular) returned false, and Network.bindSocket on the
        // LTE network returned EPERM. The active default already has a route, so send
        // there with no bind.
        Log.i(TAG, "Gemini via default route $transport")
        return block(client)
    }

    /**
     * Streams the model reply and returns as soon as generation finishes (finishReason),
     * without waiting for the connection to drain. Same final text as a non-stream call.
     */
    private fun callGeminiStream(
        httpClient: OkHttpClient,
        apiKey: String,
        body: JSONObject,
        onText: ((String) -> Unit)?,
    ): String? {
        val request = Request.Builder()
            .url("$ENDPOINT_BASE/$MODEL:streamGenerateContent?alt=sse")
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val raw = response.body?.string().orEmpty()
                    Log.w(TAG, "Gemini stream failed: ${response.code} $raw")
                    throw LlmException(friendlyHttpError(response.code, raw))
                }
                val source = response.body?.byteStream()
                    ?: throw LlmException("The assistant returned an empty response.")
                val reader = BufferedReader(InputStreamReader(source, Charsets.UTF_8))
                val assembled = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val rawLine = line ?: continue
                    if (!rawLine.startsWith("data:")) continue
                    val payload = rawLine.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue
                    val chunk = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                    val grew = appendStreamText(chunk, assembled)
                    if (grew) onText?.invoke(assembled.toString())
                    if (streamFinished(chunk)) break
                }
                assembled.toString().trim().ifBlank { null }
            }
        } catch (e: LlmException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Gemini stream IO error", e)
            throw LlmException("Couldn't reach the assistant service — check your connection.", e)
        }
    }

    private fun appendStreamText(chunk: JSONObject, into: StringBuilder): Boolean {
        val before = into.length
        val candidates = chunk.optJSONArray("candidates") ?: return false
        if (candidates.length() == 0) return false
        val parts = candidates.getJSONObject(0)
            .optJSONObject("content")
            ?.optJSONArray("parts") ?: return false
        for (i in 0 until parts.length()) {
            val piece = parts.getJSONObject(i).optString("text")
            if (piece.isNotEmpty()) into.append(piece)
        }
        return into.length > before
    }

    private fun streamFinished(chunk: JSONObject): Boolean {
        val candidates = chunk.optJSONArray("candidates") ?: return false
        if (candidates.length() == 0) return false
        val reason = candidates.getJSONObject(0).optString("finishReason")
        return reason.isNotBlank() && !reason.equals("FINISH_REASON_UNSPECIFIED", ignoreCase = true)
    }

    /**
     * Gemini has no live clock of its own — inject this phone's local date/time/zone into
     * every system instruction so questions like "what time is it?" are answered correctly
     * for the user's location/settings.
     */
    private fun withDeviceContext(systemInstruction: String?): String {
        val now = Date()
        val zone = TimeZone.getDefault()
        val dateFmt = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).apply { timeZone = zone }
        val timeFmt = SimpleDateFormat("h:mm:ss a", Locale.US).apply { timeZone = zone }
        val zoneName = zone.getDisplayName(zone.inDaylightTime(now), TimeZone.LONG, Locale.US)
        val zoneId = zone.id
        val clockBlock =
            "Device clock (authoritative — use this for any time/date/timezone question; " +
                "never invent or guess the time):\n" +
                "- Local date: ${dateFmt.format(now)}\n" +
                "- Local time: ${timeFmt.format(now)}\n" +
                "- Time zone: $zoneName ($zoneId)\n" +
                "- UTC offset: ${formatUtcOffset(zone, now)}"
        return listOfNotNull(systemInstruction?.trim()?.ifBlank { null }, clockBlock)
            .joinToString("\n\n")
    }

    private fun formatUtcOffset(zone: TimeZone, now: Date): String {
        val totalMinutes = zone.getOffset(now.time) / 60_000
        val sign = if (totalMinutes >= 0) "+" else "-"
        val abs = kotlin.math.abs(totalMinutes)
        val hours = abs / 60
        val minutes = abs % 60
        return String.format(Locale.US, "UTC%s%02d:%02d", sign, hours, minutes)
    }

    private fun buildRequestBody(
        systemInstruction: String?,
        turns: List<Turn>,
        imageJpeg: ByteArray?,
        maxOutputTokens: Int = 120,
    ): JSONObject {
        val contents = JSONArray()
        turns.forEachIndexed { index, turn ->
            val parts = JSONArray()
            // Attach the image to the final user turn only.
            if (imageJpeg != null && index == turns.lastIndex && turn.role == "user") {
                parts.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject()
                            .put("mime_type", "image/jpeg")
                            .put("data", Base64.encodeToString(imageJpeg, Base64.NO_WRAP)),
                    ),
                )
            }
            parts.put(JSONObject().put("text", turn.text))
            contents.put(JSONObject().put("role", turn.role).put("parts", parts))
        }

        return JSONObject().apply {
            put("contents", contents)
            if (!systemInstruction.isNullOrBlank()) {
                put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction))),
                )
            }
            put(
                "generationConfig",
                JSONObject().apply {
                    put("maxOutputTokens", maxOutputTokens)
                    put("temperature", 0.3)
                },
            )
        }
    }

    private fun friendlyHttpError(code: Int, raw: String): String = when (code) {
        400 -> "The assistant request was rejected — check the API key is valid."
        401, 403 -> "The Gemini API key was rejected. Double-check it in Settings."
        429 -> "Free-tier rate limit hit — try again in a moment."
        else -> {
            Log.w(TAG, "HTTP $code: $raw")
            "The assistant service returned an error ($code)."
        }
    }

    companion object {
        private const val TAG = "LlmClient"
        private const val ENDPOINT_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
        // Flash-Lite: the fastest, lowest-latency tier, and doesn't "think" by default —
        // much better fit for a real-time voice assistant than plain Flash. The "-latest"
        // suffix auto-tracks new releases so we don't chase deprecations on pinned versions.
        // See https://ai.google.dev/gemini-api/docs/models
        private const val MODEL = "gemini-flash-lite-latest"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

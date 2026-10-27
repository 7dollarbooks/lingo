package com.livetranslate.headphones.memory

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.CancellationSignal
import com.livetranslate.headphones.assistant.LlmClient
import com.livetranslate.headphones.glasses.GlassesFacade
import com.livetranslate.headphones.text.TextSink
import com.livetranslate.headphones.text.TextSource
import com.livetranslate.headphones.vision.GlassesStillSource
import com.livetranslate.headphones.watch.MoyoungWatchClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

class MemorySession(
    private val context: Context,
    private val glasses: GlassesFacade,
    private val llm: LlmClient,
    private val store: MemoryStore,
    private val watch: MoyoungWatchClient,
    private val textSink: TextSink,
    private val phonePhoto: suspend () -> ByteArray?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var timerJob: Job? = null

    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _notes = MutableStateFlow(store.all())
    val notes: StateFlow<List<MemoryNote>> = _notes.asStateFlow()

    fun startTimer(minutes: Int) {
        timerJob?.cancel()
        val mins = minutes.coerceIn(1, 180)
        _status.value = "Remembering in $mins min"
        timerJob = scope.launch {
            delay(mins * 60_000L)
            remember()
        }
    }

    fun cancelTimer() {
        timerJob?.cancel()
        timerJob = null
        _status.value = "Idle"
    }

    fun setKeep(id: String, keep: Boolean) {
        store.setKeep(id, keep)
        _notes.value = store.all()
    }

    suspend fun remember(): String {
        watch.hold()
        return try {
            _status.value = "Taking a photo…"
            val jpeg = captureJpeg()
            if (jpeg == null) {
                val message = "I couldn't get a photo."
                _status.value = message
                message
            } else {
                val fix = oneFix()
                val vitals = watch.snapshot().takeIf { watch.isReady() }
                _status.value = "Writing the note…"
                val text = llm.askWithImage(
                    prompt = NOTE_PROMPT,
                    jpegBytes = jpeg,
                    systemInstruction = NOTE_SYSTEM,
                    maxOutputTokens = 40,
                ).trim().ifBlank { "Saved a place with no description." }
                val id = UUID.randomUUID().toString()
                val note = MemoryNote(
                    id = id,
                    text = text,
                    photoFileName = "$id.jpg",
                    latitude = fix?.latitude,
                    longitude = fix?.longitude,
                    heartBpm = vitals?.heartBpm,
                    systolic = vitals?.systolic,
                    diastolic = vitals?.diastolic,
                    steps = vitals?.steps,
                    calories = vitals?.calories,
                    createdAtMs = System.currentTimeMillis(),
                    keep = false,
                )
                store.add(note, jpeg)
                _notes.value = store.all()
                textSink.publish(TextSource.MEMORY, "Memory", text, announce = false)
                _status.value = text
                text
            }
        } catch (err: Exception) {
            val message = err.message ?: "Could not save a note."
            _status.value = message
            message
        } finally {
            watch.release()
        }
    }

    /**
     * @return the short spoken answer, or null when this is not a memory question.
     * The full stored note is published for the phone and the watch.
     */
    suspend fun recall(query: String): String? {
        if (!MemoryRecall.matches(query)) return null
        val notes = store.all()
        if (notes.isEmpty()) {
            val message = "I don't have a saved note."
            textSink.publish(TextSource.MEMORY, "Memory", message, announce = false)
            _status.value = message
            return message
        }
        val chosen = pick(query, notes)
        val full = chosen.joinToString("\n") { it.text }
        watch.hold()
        val spoken = try {
            llm.ask(
                prompt = "Question: $query\nNotes:\n$full\nAnswer in one short spoken sentence using only these notes.",
                systemInstruction = RECALL_SYSTEM,
                maxOutputTokens = 40,
            ).trim().ifBlank { full }
        } catch (err: Exception) {
            err.message ?: full
        } finally {
            watch.release()
        }
        textSink.publish(TextSource.MEMORY, "Memory", full, announce = false)
        _status.value = full
        return spoken
    }

    private suspend fun captureJpeg(): ByteArray? {
        glasses.tryAutoReconnect()
        withTimeoutOrNull(8_000) {
            while (!glasses.capability.value.canCapture) delay(300)
            true
        }
        val glassesStill = if (glasses.capability.value.canCapture) {
            GlassesStillSource(glasses).capture("")
        } else {
            null
        }
        if (glassesStill != null) return glassesStill.jpegBytes
        _status.value = "Glasses didn’t return a photo. Using the phone camera."
        return phonePhoto()
    }

    @SuppressLint("MissingPermission")
    private suspend fun oneFix() = suspendCancellableCoroutine { cont ->
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        val manager = context.getSystemService(LocationManager::class.java)
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { manager?.isProviderEnabled(it) == true }
        if (manager == null || provider == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
            if (cont.isActive) cont.resume(location)
        }
    }

    companion object {
        const val NOTE_PROMPT =
            "Write one short sentence about this place for a personal reminder. " +
                "Mention objects, signs, and layout."
        const val NOTE_SYSTEM =
            "You write one English sentence about a place. Never name or describe any person or face. " +
                "If people are present, ignore them and describe the place only."
        private const val RECALL_SYSTEM =
            "Answer only from the notes. If they do not say, say you do not know. Do not identify any person."

        fun pick(query: String, notes: List<MemoryNote>): List<MemoryNote> {
            val words = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
            val ranked = notes.map { note ->
                val hay = note.text.lowercase()
                val score = words.count { it in hay }
                note to score
            }.sortedWith(compareByDescending<Pair<MemoryNote, Int>> { it.second }.thenByDescending { it.first.createdAtMs })
            val best = ranked.firstOrNull()?.second ?: 0
            val chosen = if (best > 0) ranked.filter { it.second == best }.map { it.first } else listOf(notes.maxBy { it.createdAtMs })
            return chosen.take(3)
        }
    }
}

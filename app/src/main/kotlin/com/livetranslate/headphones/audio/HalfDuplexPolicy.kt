package com.livetranslate.headphones.audio

import com.livetranslate.headphones.assistant.QuestionDetector
import com.livetranslate.headphones.vision.VisionTimings

/**
 * Single policy for mic half-duplex: busy gate, SPIT repeat-ignore, and TTS echo filter.
 * [com.livetranslate.headphones.audio.BluetoothAudioRouter] still owns routing; this object
 * is only consulted for “should we accept / drop this utterance?”
 *
 * Vision mic hold ([VisionTimings.visionHoldMaxMs]) starts at vision capture and releases
 * at the cap even if Gemini/NetworkMonitor retries continue — late answers speak only if
 * idle, else exchange-only.
 */
class HalfDuplexPolicy(
    private val visionHoldMaxMs: Long = VisionTimings.DEFAULT.visionHoldMaxMs,
    private val echoWindowMs: Long = 4_000L,
    private val spitRepeatWindowMs: Long = 10_000L,
) {
    @Volatile private var busyResponding = false
    @Volatile private var visionHoldUntilMs: Long = 0L

    @Volatile private var spitLastAnswer: String = ""
    @Volatile private var spitAwaitingRepeat = false
    @Volatile private var spitRepeatDeadlineMs: Long = 0L

    @Volatile private var lastSpokenNormalized: String = ""
    @Volatile private var echoUntilMs: Long = 0L

    fun isBusy(nowMs: Long = System.currentTimeMillis()): Boolean {
        expireVisionHoldIfNeeded(nowMs)
        return busyResponding || isVisionHoldActive(nowMs)
    }

    fun isVisionHoldActive(nowMs: Long = System.currentTimeMillis()): Boolean =
        visionHoldUntilMs > 0L && nowMs < visionHoldUntilMs

    fun beginResponse() {
        busyResponding = true
    }

    fun endResponse() {
        busyResponding = false
    }

    /** Call at vision capture start; STT/half-duplex releases at [visionHoldMaxMs] cap. */
    fun beginVisionHold(nowMs: Long = System.currentTimeMillis()) {
        busyResponding = true
        visionHoldUntilMs = nowMs + visionHoldMaxMs
    }

    /**
     * Drop hold when the wall-clock cap elapses. Also clears [busyResponding] so STT can
     * resume while a late Gemini retry may still be in flight.
     */
    fun expireVisionHoldIfNeeded(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (visionHoldUntilMs > 0L && nowMs >= visionHoldUntilMs) {
            visionHoldUntilMs = 0L
            busyResponding = false
            return true
        }
        return false
    }

    fun clearVisionHold() {
        visionHoldUntilMs = 0L
    }

    /** Late LLM answer may be spoken only when nothing else has taken the mic. */
    fun maySpeakLateAnswer(nowMs: Long = System.currentTimeMillis()): Boolean {
        expireVisionHoldIfNeeded(nowMs)
        return !busyResponding && !isVisionHoldActive()
    }

    fun noteSpoken(answer: String, nowMs: Long = System.currentTimeMillis()) {
        lastSpokenNormalized = QuestionDetector.normalize(answer)
        echoUntilMs = nowMs + echoWindowMs
    }

    fun armSpitRepeat(answer: String, nowMs: Long = System.currentTimeMillis()) {
        spitLastAnswer = answer
        spitAwaitingRepeat = true
        spitRepeatDeadlineMs = nowMs + spitRepeatWindowMs
        noteSpoken(answer, nowMs)
    }

    fun clearSpitRepeat() {
        spitAwaitingRepeat = false
        spitLastAnswer = ""
        spitRepeatDeadlineMs = 0L
    }

    fun spitRepeatArmed(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!spitAwaitingRepeat) return false
        if (nowMs >= spitRepeatDeadlineMs) {
            clearSpitRepeat()
            return false
        }
        return true
    }

    fun spitLastAnswer(): String = spitLastAnswer

    /**
     * @return reason to drop, or null if the utterance may proceed.
     */
    fun dropReasonForUtterance(text: String, nowMs: Long = System.currentTimeMillis()): DropReason? {
        expireVisionHoldIfNeeded(nowMs)
        if (busyResponding || isVisionHoldActive(nowMs)) return DropReason.Busy
        if (spitRepeatArmed(nowMs) && QuestionDetector.isRepeatOfAnswer(text, spitLastAnswer)) {
            clearSpitRepeat()
            return DropReason.SpitRepeat
        }
        if (spitAwaitingRepeat && nowMs >= spitRepeatDeadlineMs) {
            clearSpitRepeat()
        }
        if (nowMs < echoUntilMs && lastSpokenNormalized.isNotBlank()) {
            if (QuestionDetector.isRepeatOfAnswer(text, lastSpokenNormalized)) {
                return DropReason.Echo
            }
        }
        return null
    }

    enum class DropReason {
        Busy,
        SpitRepeat,
        Echo,
    }
}

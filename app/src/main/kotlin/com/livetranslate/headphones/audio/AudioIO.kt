package com.livetranslate.headphones.audio

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import kotlin.math.sqrt

class PcmAudioCapture(
    private val sampleRate: Int = 16_000,
    private val scoInput: AudioDeviceInfo? = null,
) {
    private var audioRecord: AudioRecord? = null
    @Volatile
    var isRecording: Boolean = false
        private set

    fun start(): Boolean {
        if (isRecording) return true

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            return false
        }

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            channelConfig,
            encoding,
            minBuffer * 2,
        )

        if (scoInput != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val routed = record.setPreferredDevice(scoInput)
            if (!routed) {
                record.release()
                return false
            }
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }

        record.startRecording()
        audioRecord = record
        isRecording = true
        return true
    }

    fun readChunk(chunkSamples: Int = 1600): ShortArray? {
        val record = audioRecord ?: return null
        val buffer = ShortArray(chunkSamples)
        val read = record.read(buffer, 0, buffer.size)
        return if (read > 0) buffer.copyOf(read) else null
    }

    fun stop() {
        isRecording = false
        audioRecord?.run {
            try {
                stop()
            } catch (_: IllegalStateException) {
            }
            release()
        }
        audioRecord = null
    }

    fun routedToSco(): Boolean {
        val record = audioRecord ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return record.routedDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    }
}

class VadProcessor(
    private val sampleRate: Int = 16_000,
    private val energyThreshold: Double = 900.0,
    private val minSpeechMs: Int = 400,
    private val endSilenceMs: Int = 700,
) {
    private val minSpeechSamples = (sampleRate * minSpeechMs) / 1000
    private val endSilenceSamples = (sampleRate * endSilenceMs) / 1000
    private val segment = ArrayList<Short>()
    private var trailingSilence = 0
    private var inSpeech = false

    fun ingest(chunk: ShortArray): List<ShortArray> {
        val completed = mutableListOf<ShortArray>()
        val energy = rms(chunk)
        val speech = energy >= energyThreshold

        if (speech) {
            inSpeech = true
            trailingSilence = 0
            segment.addAll(chunk.toList())
        } else if (inSpeech) {
            segment.addAll(chunk.toList())
            trailingSilence += chunk.size
            if (trailingSilence >= endSilenceSamples && segment.size >= minSpeechSamples) {
                completed += segment.toShortArray()
                resetSegment()
            }
        }

        return completed
    }

    fun flush(): ShortArray? {
        if (!inSpeech || segment.size < minSpeechSamples) {
            resetSegment()
            return null
        }
        val out = segment.toShortArray()
        resetSegment()
        return out
    }

    private fun resetSegment() {
        segment.clear()
        trailingSilence = 0
        inSpeech = false
    }

    private fun rms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (sample in samples) {
            val v = sample.toDouble()
            sum += v * v
        }
        return sqrt(sum / samples.size)
    }
}

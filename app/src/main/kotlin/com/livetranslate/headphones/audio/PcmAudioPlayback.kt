package com.livetranslate.headphones.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PcmAudioPlayback(
    private val sampleRate: Int = 16_000,
    private val outputDevice: AudioDeviceInfo? = null,
) {
    private var audioTrack: AudioTrack? = null

    fun start() {
        if (audioTrack != null) return
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(encoding)
                    .setChannelMask(channelConfig)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (outputDevice != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            track.setPreferredDevice(outputDevice)
        }

        track.play()
        audioTrack = track
    }

    fun write(samples: ShortArray) {
        audioTrack?.write(samples, 0, samples.size)
    }

    fun stop() {
        audioTrack?.run {
            try {
                stop()
            } catch (_: IllegalStateException) {
            }
            release()
        }
        audioTrack = null
    }
}

class LoopbackSession(
    private val capture: PcmAudioCapture,
    private val playback: PcmAudioPlayback,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    fun start(): Boolean {
        if (!capture.start()) return false
        playback.start()
        job = scope.launch(Dispatchers.IO) {
            while (isActive && capture.isRecording) {
                val chunk = capture.readChunk() ?: continue
                playback.write(chunk)
            }
        }
        return true
    }

    fun stop() {
        job?.cancel()
        job = null
        capture.stop()
        playback.stop()
    }
}

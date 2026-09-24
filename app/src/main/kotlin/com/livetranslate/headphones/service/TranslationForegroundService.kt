package com.livetranslate.headphones.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.livetranslate.headphones.AppMode
import com.livetranslate.headphones.LiveTranslateApp
import com.livetranslate.headphones.MainActivity
import com.livetranslate.headphones.R
import com.livetranslate.headphones.glasses.GlassesState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TranslationForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val app: LiveTranslateApp
        get() = LiveTranslateApp.from(application)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val modeName = intent.getStringExtra(EXTRA_MODE) ?: AppMode.LISTEN.name
                val mode = runCatching { AppMode.valueOf(modeName) }.getOrDefault(AppMode.LISTEN)
                app.translationSession.setMode(mode)
                startForegroundTyped(buildNotification(getString(R.string.notification_text)))
                scope.launch {
                    app.assistantSession.stop()
                    app.translationSession.startTranslation()
                    updateNotification(app.translationSession.status.value.statusMessage)
                }
            }
            ACTION_STOP -> {
                scope.launch {
                    app.translationSession.stopTranslation()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_LOOPBACK_START -> {
                startForegroundTyped(buildNotification("Loopback test"))
                app.translationSession.startLoopback()
                scope.launch {
                    kotlinx.coroutines.delay(300)
                    updateNotification(app.translationSession.status.value.statusMessage)
                }
            }
            ACTION_LOOPBACK_STOP -> {
                app.translationSession.stopLoopback()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_TEST_TTS -> {
                startForegroundTyped(buildNotification("TTS test"))
                scope.launch {
                    app.translationSession.testTts()
                    updateNotification(app.translationSession.status.value.statusMessage)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_START_ASSISTANT -> {
                val glassesHeld = isGlassesHeld()
                startForegroundTyped(
                    buildNotification(assistantNotificationText(listening = false, glassesHeld = glassesHeld)),
                    includeConnectedDevice = glassesHeld,
                )
                scope.launch {
                    // Mutually exclusive with translation — only one SpeechRecognizer session
                    // can be active at a time.
                    app.translationSession.stopTranslation()
                    app.assistantSession.start()
                    updateAssistantNotification()
                    // Keep notification in sync with glasses BLE + status message.
                    launch {
                        app.assistantSession.status.collectLatest { updateAssistantNotification() }
                    }
                    launch {
                        app.glassesFacade.state.collectLatest { updateAssistantNotification() }
                    }
                    launch {
                        app.assistantSession.visionResult.collectLatest { updateAssistantNotification() }
                    }
                }
            }
            ACTION_STOP_ASSISTANT -> {
                scope.launch {
                    app.assistantSession.stop()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun isGlassesHeld(): Boolean {
        val st = app.glassesFacade.state.value
        return st is GlassesState.Ready || st is GlassesState.Connecting || st is GlassesState.Degraded
    }

    private fun assistantNotificationText(listening: Boolean, glassesHeld: Boolean): String = when {
        listening && glassesHeld -> getString(R.string.notification_assistant_listening_glasses)
        listening -> getString(R.string.notification_assistant_listening)
        glassesHeld -> getString(R.string.notification_assistant_glasses_only)
        else -> getString(R.string.notification_assistant_starting)
    }

    private fun updateAssistantNotification() {
        val status = app.assistantSession.status.value
        val glassesHeld = status.glassesConnected || isGlassesHeld()
        val text = assistantNotificationText(
            listening = status.isActive && status.listening,
            glassesHeld = glassesHeld,
        ).let { base ->
            val msg = status.statusMessage
            if (msg.isNotBlank() && status.isActive) {
                // Prefer live status when more specific than the template.
                if (msg.contains("glasses", ignoreCase = true) ||
                    msg.contains("camera", ignoreCase = true) ||
                    msg.contains("Looking", ignoreCase = true) ||
                    msg.contains("Thinking", ignoreCase = true) ||
                    msg.contains("SPIT", ignoreCase = true) ||
                    msg.contains("Ignore", ignoreCase = true)
                ) {
                    msg
                } else {
                    base
                }
            } else {
                base
            }
        }
        updateNotification(text, includeConnectedDevice = glassesHeld)
    }

    private fun updateNotification(message: String, includeConnectedDevice: Boolean = false) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(message, includeConnectedDevice))
    }

    private fun startForegroundTyped(
        notification: Notification,
        includeConnectedDevice: Boolean = false,
    ) {
        val type = if (includeConnectedDevice) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun buildNotification(
        message: String,
        includeConnectedDevice: Boolean = false,
    ): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (includeConnectedDevice) {
            getString(R.string.notification_title_glasses)
        } else {
            getString(R.string.notification_title)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)

        if (app.assistantSession.hasLastVisionResult()) {
            val viewIntent = PendingIntent.getActivity(
                this,
                1,
                Intent(this, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_VIEW_LAST_VISION_RESULT
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(
                0,
                getString(R.string.notification_view_last_result),
                viewIntent,
            )
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "live_translate"
        const val NOTIFICATION_ID = 42

        const val ACTION_START = "com.livetranslate.headphones.START"
        const val ACTION_STOP = "com.livetranslate.headphones.STOP"
        const val ACTION_LOOPBACK_START = "com.livetranslate.headphones.LOOPBACK_START"
        const val ACTION_LOOPBACK_STOP = "com.livetranslate.headphones.LOOPBACK_STOP"
        const val ACTION_TEST_TTS = "com.livetranslate.headphones.TEST_TTS"
        const val ACTION_START_ASSISTANT = "com.livetranslate.headphones.START_ASSISTANT"
        const val ACTION_STOP_ASSISTANT = "com.livetranslate.headphones.STOP_ASSISTANT"
        const val EXTRA_MODE = "mode"
    }
}

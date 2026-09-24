package com.livetranslate.headphones

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.livetranslate.headphones.service.TranslationForegroundService
import com.livetranslate.headphones.ui.AssistantScreen
import com.livetranslate.headphones.ui.MainScreen
import com.livetranslate.headphones.ui.ObjectIdentifyResultScreen
import com.livetranslate.headphones.ui.SettingsScreen
import com.livetranslate.headphones.ui.SignTranslateResultScreen
import com.livetranslate.headphones.ui.VisionCaptureScreen
import com.livetranslate.headphones.ui.WatchVitalsScreen
import com.livetranslate.headphones.ui.theme.AppTheme
import com.livetranslate.headphones.vision.VisionResult

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* UI still loads; user can grant from settings. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestRuntimePermissions()
        handleVisionIntent(intent)
        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            val visionCaptureRequested by viewModel.visionCaptureRequested.collectAsState()
            val visionInitialPrompt by viewModel.visionInitialPrompt.collectAsState()
            val visionResult by viewModel.visionResult.collectAsState()
            val screen by viewModel.appScreen.collectAsState()
            AppTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when {
                        visionResult is VisionResult.ObjectIdentify -> ObjectIdentifyResultScreen(
                            result = visionResult as VisionResult.ObjectIdentify,
                            onDone = viewModel::dismissVisionResult,
                            onRetake = { viewModel.retakeVision(preferPhone = false) },
                            onRetry = viewModel::retryVisionGemini,
                        )
                        visionResult is VisionResult.SignTranslate -> SignTranslateResultScreen(
                            result = visionResult as VisionResult.SignTranslate,
                            onDone = viewModel::dismissVisionResult,
                            onRetakePhone = { viewModel.retakeVision(preferPhone = true) },
                            onRetake = { viewModel.retakeVision(preferPhone = false) },
                            onRetry = viewModel::retryVisionGemini,
                            onAcceptGemini = viewModel::acceptSignGeminiFallback,
                        )
                        visionCaptureRequested -> VisionCaptureScreen(
                            initialPrompt = visionInitialPrompt,
                            onCaptured = { jpegBytes, prompt ->
                                viewModel.completeVisionCapture(jpegBytes, prompt)
                            },
                        )
                        screen == AppScreen.SETTINGS -> SettingsScreen(
                            viewModel = viewModel,
                            onBack = { viewModel.setAppScreen(AppScreen.MAIN) },
                        )
                        screen == AppScreen.VITALS -> WatchVitalsScreen(
                            onBack = { viewModel.setAppScreen(AppScreen.MAIN) },
                        )
                        screen == AppScreen.ASSISTANT -> AssistantScreen(
                            viewModel = viewModel,
                            onBack = { viewModel.setAppScreen(AppScreen.MAIN) },
                            onOpenVitals = { viewModel.setAppScreen(AppScreen.VITALS) },
                            onStart = { startAssistant() },
                            onStop = { stopAssistant() },
                        )
                        else -> MainScreen(
                            viewModel = viewModel,
                            onOpenSettings = { viewModel.setAppScreen(AppScreen.SETTINGS) },
                            onOpenAssistant = { viewModel.setAppScreen(AppScreen.ASSISTANT) },
                            onOpenVitals = { viewModel.setAppScreen(AppScreen.VITALS) },
                            onStart = { mode -> startTranslation(mode) },
                            onStop = { stopTranslation() },
                            onLoopbackStart = { startLoopback() },
                            onLoopbackStop = { stopLoopback() },
                            onTestTts = { testTts() },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleVisionIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.tryAutoReconnectGlasses()
    }

    private fun handleVisionIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_VIEW_LAST_VISION_RESULT -> viewModel.reopenLastVisionResult()
            ACTION_OPEN_VITALS -> viewModel.setAppScreen(AppScreen.VITALS)
        }
    }

    private fun requestRuntimePermissions() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            add(Manifest.permission.CAMERA)
        }.filter { perm ->
            ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startTranslation(mode: AppMode) {
        val intent = Intent(this, TranslationForegroundService::class.java).apply {
            action = TranslationForegroundService.ACTION_START
            putExtra(TranslationForegroundService.EXTRA_MODE, mode.name)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopTranslation() {
        val intent = Intent(this, TranslationForegroundService::class.java).apply {
            action = TranslationForegroundService.ACTION_STOP
        }
        startService(intent)
    }

    private fun startLoopback() {
        val intent = Intent(this, TranslationForegroundService::class.java).apply {
            action = TranslationForegroundService.ACTION_LOOPBACK_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopLoopback() {
        val intent = Intent(this, TranslationForegroundService::class.java).apply {
            action = TranslationForegroundService.ACTION_LOOPBACK_STOP
        }
        startService(intent)
    }

    private fun testTts() {
        val intent = Intent(this, TranslationForegroundService::class.java).apply {
            action = TranslationForegroundService.ACTION_TEST_TTS
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun startAssistant() {
        val intent = Intent(this, TranslationForegroundService::class.java).apply {
            action = TranslationForegroundService.ACTION_START_ASSISTANT
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopAssistant() {
        val intent = Intent(this, TranslationForegroundService::class.java).apply {
            action = TranslationForegroundService.ACTION_STOP_ASSISTANT
        }
        startService(intent)
    }

    companion object {
        const val ACTION_VIEW_LAST_VISION_RESULT =
            "com.livetranslate.headphones.VIEW_LAST_VISION_RESULT"
        const val ACTION_OPEN_VITALS = "com.livetranslate.headphones.OPEN_VITALS"
    }
}

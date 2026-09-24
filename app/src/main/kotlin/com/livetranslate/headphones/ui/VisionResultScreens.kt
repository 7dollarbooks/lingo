package com.livetranslate.headphones.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livetranslate.headphones.vision.VisionResult

@Composable
fun ObjectIdentifyResultScreen(
    result: VisionResult.ObjectIdentify,
    onDone: () -> Unit,
    onRetake: () -> Unit,
    onRetry: () -> Unit,
) {
    ResultScaffold(
        stillPath = result.still.file.absolutePath,
        answer = result.answer,
        banner = if (result.geminiOffline) result.answer else null,
        downloading = false,
        onDone = onDone,
        primaryAction = if (result.geminiOffline) {
            "Retry" to onRetry
        } else {
            "Retake" to onRetake
        },
        secondaryAction = if (result.geminiOffline) "Retake" to onRetake else null,
        overlay = null,
    )
}

@Composable
fun SignTranslateResultScreen(
    result: VisionResult.SignTranslate,
    onDone: () -> Unit,
    onRetakePhone: () -> Unit,
    onRetake: () -> Unit,
    onRetry: () -> Unit,
    onAcceptGemini: () -> Unit,
) {
    when {
        result.downloadingModel -> {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Downloading translation model…",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Wi‑Fi recommended",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        result.glassesZeroOcr -> {
            ResultScaffold(
                stillPath = result.still.file.absolutePath,
                answer = result.answer,
                banner = null,
                downloading = false,
                onDone = onDone,
                primaryAction = "Retake with phone camera?" to onRetakePhone,
                secondaryAction = "Try Gemini text-only" to onAcceptGemini,
                overlay = null,
            )
        }
        else -> {
            ResultScaffold(
                stillPath = result.still.file.absolutePath,
                answer = result.answer,
                banner = result.banner,
                downloading = false,
                onDone = onDone,
                primaryAction = when {
                    result.geminiOffline -> "Retry" to onRetry
                    else -> "Retake" to onRetake
                },
                secondaryAction = when {
                    result.geminiOffline -> "Retake" to onRetake
                    result.banner != null -> "Retake with phone" to onRetakePhone
                    else -> null
                },
                overlay = if (result.blocks.isNotEmpty()) {
                    {
                        SignOverlay(blocks = result.blocks)
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun ResultScaffold(
    stillPath: String,
    answer: String,
    banner: String?,
    downloading: Boolean,
    onDone: () -> Unit,
    primaryAction: Pair<String, () -> Unit>?,
    secondaryAction: Pair<String, () -> Unit>?,
    overlay: (@Composable () -> Unit)?,
) {
    val bitmap = remember(stillPath) {
        BitmapFactory.decodeFile(stillPath)?.asImageBitmap()
    }
    DisposableEffect(stillPath) {
        onDispose { /* bitmap recycled by GC / ImageBitmap */ }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black),
        ) {
            if (bitmap != null) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Captured still",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    if (overlay != null) {
                        Box(Modifier.fillMaxSize()) { overlay() }
                    }
                }
            }
            if (downloading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }

        if (!banner.isNullOrBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    banner,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(0.55f)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = answer,
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Start,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onDone) { Text("Done") }
                Spacer(Modifier.weight(1f))
                secondaryAction?.let { (label, action) ->
                    OutlinedButton(onClick = action, shape = RoundedCornerShape(12.dp)) {
                        Text(label)
                    }
                }
                primaryAction?.let { (label, action) ->
                    Button(onClick = action, shape = RoundedCornerShape(12.dp)) {
                        Text(label)
                    }
                }
            }
        }
    }
}

@Composable
private fun SignOverlay(blocks: List<com.livetranslate.headphones.vision.SignTextBlock>) {
    val stroke = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize()) {
        blocks.forEach { block ->
            val left = block.box.left * size.width
            val top = block.box.top * size.height
            val w = (block.box.right - block.box.left) * size.width
            val h = (block.box.bottom - block.box.top) * size.height
            drawRect(
                color = stroke.copy(alpha = 0.85f),
                topLeft = Offset(left, top),
                size = Size(w, h),
                style = Stroke(width = 3f),
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.35f),
                topLeft = Offset(left, top),
                size = Size(w, h.coerceAtLeast(28f)),
            )
        }
    }
}

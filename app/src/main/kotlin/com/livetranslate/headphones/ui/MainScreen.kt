package com.livetranslate.headphones.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livetranslate.headphones.AppMode
import com.livetranslate.headphones.MainViewModel
import com.livetranslate.headphones.SessionStatus
import com.livetranslate.headphones.TranscriptEntry
import com.livetranslate.headphones.audio.HeadsetState
import com.livetranslate.headphones.navigation.TravelMode
import com.livetranslate.headphones.text.TextLine
import com.livetranslate.headphones.text.TextSource
import com.livetranslate.headphones.translation.LanguagePackState
import com.livetranslate.headphones.translation.SupportedLanguages

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit,
    onOpenAssistant: () -> Unit,
    onOpenVitals: () -> Unit,
    onStart: (AppMode) -> Unit,
    onStop: () -> Unit,
    onLoopbackStart: () -> Unit,
    onLoopbackStop: () -> Unit,
    onTestTts: () -> Unit,
) {
    val status by viewModel.status.collectAsState()
    val transcripts by viewModel.transcripts.collectAsState()
    val textLines by viewModel.textLines.collectAsState()
    val pinnedLineId by viewModel.pinnedLineId.collectAsState()
    val navigationStatus by viewModel.navigationStatus.collectAsState()
    val navigationActive by viewModel.navigationActive.collectAsState()
    val travelMode by viewModel.travelMode.collectAsState()
    val modelsReady by viewModel.modelsReady.collectAsState()
    val downloadedLanguages by viewModel.downloadedLanguages.collectAsState()
    val activeSource by viewModel.activeSourceLanguage.collectAsState()
    val headset by viewModel.bluetoothHeadset.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Lingo",
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    IconButton(onClick = onOpenVitals) {
                        Icon(Icons.Default.Favorite, contentDescription = "Watch vitals")
                    }
                    IconButton(onClick = onOpenAssistant) {
                        Icon(Icons.Default.SmartToy, contentDescription = "Lingo assistant")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { HeroStatusCard(status, modelsReady, headset) }

            item {
                ModeSelector(
                    selected = status.mode,
                    enabled = !status.isActive && !status.loopbackActive,
                    onSelect = { viewModel.session.setMode(it) },
                )
            }

            item {
                SourceLanguageSelector(
                    downloaded = downloadedLanguages,
                    active = activeSource,
                    enabled = !status.isActive && !status.loopbackActive,
                    onSelect = viewModel::setSourceLanguage,
                )
            }

            if (status.isActive && status.mode == AppMode.LISTEN) {
                item { MicLevelMeter(status.micLevel) }
            }

            item {
                PrimaryActionButton(
                    status = status,
                    onStart = { onStart(status.mode) },
                    onStop = onStop,
                )
            }

            item {
                SecondaryActions(
                    status = status,
                    onLoopbackStart = onLoopbackStart,
                    onLoopbackStop = onLoopbackStop,
                    onTestTts = onTestTts,
                )
            }

            item {
                NavigationCard(
                    status = navigationStatus,
                    active = navigationActive,
                    mode = travelMode,
                    onMode = viewModel::setTravelMode,
                    onStart = viewModel::startNavigation,
                    onStop = viewModel::stopNavigation,
                )
            }

            if (status.mode == AppMode.LISTEN) {
                val captions = textLines.filter { it.source == TextSource.CAPTION }
                item {
                    CaptionHeader(
                        count = captions.size,
                        onClear = viewModel::clearTranscripts,
                        onPrivate = viewModel::speakPrivateLine,
                        privateEnabled = captions.isNotEmpty(),
                    )
                }
                val pinned = captions.firstOrNull { it.id == pinnedLineId }
                if (pinned != null) {
                    item {
                        CaptionLine(line = pinned, pinned = true, onPin = { viewModel.pinLine(pinned.id) })
                    }
                }
                if (captions.isEmpty()) {
                    item { CaptionEmpty() }
                } else {
                    items(captions.reversed()) { line ->
                        if (line.id != pinnedLineId) {
                            CaptionLine(line = line, pinned = false, onPin = { viewModel.pinLine(line.id) })
                        }
                    }
                }
            } else {
                item {
                    TranscriptHeader(
                        count = transcripts.size,
                        onClear = viewModel::clearTranscripts,
                    )
                }
                if (transcripts.isEmpty()) {
                    item { TranscriptEmptyState() }
                } else {
                    items(transcripts.reversed()) { entry ->
                        TranscriptCard(entry)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroStatusCard(status: SessionStatus, modelsReady: Boolean, headset: HeadsetState) {
    val active = status.isActive || status.loopbackActive
    val containerColor by animateColorAsState(
        targetValue = when {
            status.isActive -> MaterialTheme.colorScheme.primaryContainer
            status.loopbackActive -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "heroColor",
    )
    val onContainer = when {
        status.isActive -> MaterialTheme.colorScheme.onPrimaryContainer
        status.loopbackActive -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(onContainer.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = when {
                            status.mode == AppMode.CONVERSATION -> Icons.Filled.RecordVoiceOver
                            else -> Icons.Filled.Hearing
                        },
                        contentDescription = null,
                        tint = onContainer,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LiveDot(active = active, color = onContainer)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = when {
                                status.isActive -> "Live"
                                status.loopbackActive -> "Loopback"
                                else -> "Idle"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = onContainer,
                        )
                    }
                    Text(
                        text = status.statusMessage,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = onContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val headsetConnected = status.scoConnected || headset.connected
                StatusPill(
                    icon = if (headsetConnected) Icons.Filled.BluetoothConnected else Icons.Filled.Bluetooth,
                    text = status.scoDeviceName ?: headset.name
                        ?: if (headsetConnected) "Headset connected" else "No headset",
                    ok = headsetConnected,
                )
                StatusPill(
                    icon = Icons.Filled.Translate,
                    text = if (modelsReady) "Packs ready" else "Packs required",
                    ok = modelsReady,
                )
            }
        }
    }
}

@Composable
private fun LiveDot(active: Boolean, color: Color) {
    val alpha = if (active) {
        val transition = rememberInfiniteTransition(label = "pulse")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "pulseAlpha",
        ).value
    } else {
        0.5f
    }
    Box(
        modifier = Modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha)),
    )
}

@Composable
private fun StatusPill(icon: ImageVector, text: String, ok: Boolean) {
    val container = if (ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
    val content = if (ok) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer
    Surface(color = container, shape = RoundedCornerShape(50)) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSelector(
    selected: AppMode,
    enabled: Boolean,
    onSelect: (AppMode) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = selected == AppMode.LISTEN,
            onClick = { onSelect(AppMode.LISTEN) },
            enabled = enabled,
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            icon = { Icon(Icons.Filled.Hearing, contentDescription = null, modifier = Modifier.size(18.dp)) },
            label = { Text("Listen") },
        )
        SegmentedButton(
            selected = selected == AppMode.CONVERSATION,
            onClick = { onSelect(AppMode.CONVERSATION) },
            enabled = enabled,
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            icon = { Icon(Icons.Filled.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp)) },
            label = { Text("Conversation") },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceLanguageSelector(
    downloaded: List<LanguagePackState>,
    active: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Listening language",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LanguageChip(label = "Auto", selected = active == null, enabled = enabled) { onSelect(null) }
            downloaded.forEach { pack ->
                LanguageChip(label = pack.label, selected = active == pack.code, enabled = enabled) {
                    onSelect(pack.code)
                }
            }
        }
        Text(
            text = if (active == null) {
                "Auto: best-effort across all languages (works best for one at a time)."
            } else {
                "Locked to one language for the most accurate detection. English is always ignored."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LanguageChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        enabled = enabled,
        shape = RoundedCornerShape(50),
        border = FilterChipDefaults.filterChipBorder(enabled = enabled, selected = selected),
    )
}

@Composable
private fun MicLevelMeter(level: Float) {
    val animated by androidx.compose.animation.core.animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        animationSpec = tween(120),
        label = "mic",
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("Mic level", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(50)),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
private fun PrimaryActionButton(
    status: SessionStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    if (status.isActive) {
        Button(
            onClick = onStop,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Icon(Icons.Filled.Stop, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("Stop", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    } else {
        Button(
            onClick = onStart,
            enabled = !status.loopbackActive,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (status.mode == AppMode.CONVERSATION) "Start conversation" else "Start listening",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SecondaryActions(
    status: SessionStatus,
    onLoopbackStart: () -> Unit,
    onLoopbackStop: () -> Unit,
    onTestTts: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        if (!status.loopbackActive) {
            OutlinedButton(
                onClick = onLoopbackStart,
                enabled = !status.isActive,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Outlined.Loop, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Loopback")
            }
        } else {
            OutlinedButton(
                onClick = onLoopbackStop,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Stop loop")
            }
        }
        OutlinedButton(
            onClick = onTestTts,
            enabled = !status.isActive && !status.loopbackActive,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Test TTS")
        }
    }
}

@Composable
private fun NavigationCard(
    status: String,
    active: Boolean,
    mode: TravelMode,
    onMode: (TravelMode) -> Unit,
    onStart: (String) -> Unit,
    onStop: () -> Unit,
) {
    var destination by remember { mutableStateOf("") }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Navigation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(status, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TravelMode.entries.forEach { option ->
                    FilterChip(
                        selected = mode == option,
                        onClick = { if (!active) onMode(option) },
                        label = {
                            Text(
                                when (option) {
                                    TravelMode.WALK -> "Walk"
                                    TravelMode.BIKE -> "Bike"
                                    TravelMode.CAR -> "Car"
                                },
                            )
                        },
                    )
                }
            }
            OutlinedTextField(
                value = destination,
                onValueChange = { destination = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Destination") },
                enabled = !active,
            )
            if (active) {
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop navigation") }
            } else {
                Button(
                    onClick = { onStart(destination) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = destination.isNotBlank(),
                ) { Text("Start navigation") }
            }
        }
    }
}

@Composable
private fun CaptionHeader(
    count: Int,
    onClear: () -> Unit,
    onPrivate: () -> Unit,
    privateEnabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = if (count > 0) "Captions ($count)" else "Captions",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row {
            TextButton(onClick = onPrivate, enabled = privateEnabled) { Text("Private line") }
            if (count > 0) {
                TextButton(onClick = onClear) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun CaptionEmpty() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
            .padding(20.dp),
    ) {
        Text(
            "Captions show here. Each finished line is read on the Shokz as it goes to the watch.",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun CaptionLine(line: TextLine, pinned: Boolean, onPin: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
            .clickable(onClick = onPin)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = if (pinned) "${line.speakerLabel} · pinned" else line.speakerLabel,
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = line.text,
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 34.sp,
        )
    }
}

@Composable
private fun TranscriptHeader(count: Int, onClear: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = if (count > 0) "Transcript ($count)" else "Transcript",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (count > 0) {
            TextButton(onClick = onClear) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Clear")
            }
        }
    }
}

@Composable
private fun TranscriptEmptyState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 36.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Filled.Translate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
            Text(
                "Translations will appear here",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Pick a language, start listening, and non-English speech gets translated to your headphones.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TranscriptCard(entry: TranscriptEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = entry.speakerLabel.take(1).uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = entry.speakerLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                LanguageBadge(entry.sourceLanguage, discarded = entry.discardedEnglish)
            }

            if (entry.discardedEnglish) {
                Text(
                    text = entry.sourceText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "English detected — not translated",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = entry.sourceText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.englishText != null) {
                    Text(
                        text = entry.englishText,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    Text(
                        "Not translated",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageBadge(code: String, discarded: Boolean) {
    val container = if (discarded) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.secondaryContainer
    val content = if (discarded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(color = container, shape = RoundedCornerShape(50)) {
        Text(
            text = prettyLanguage(code),
            style = MaterialTheme.typography.labelMedium,
            color = content,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private fun prettyLanguage(code: String): String {
    if (code.isBlank() || code == "und") return "Unknown"
    val base = code.substringBefore('-').lowercase()
    val label = SupportedLanguages.labelFor(base)
    return if (label != base) label else code.uppercase()
}

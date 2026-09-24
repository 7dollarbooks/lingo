package com.livetranslate.headphones.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.livetranslate.headphones.AssistantExchange
import com.livetranslate.headphones.AssistantStatus
import com.livetranslate.headphones.ExchangeKind
import com.livetranslate.headphones.MainViewModel
import com.livetranslate.headphones.assistant.AssistanceLevel
import com.livetranslate.headphones.assistant.HomeNetworkStatus
import com.livetranslate.headphones.audio.TtsVoiceStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenVitals: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val status by viewModel.assistantStatus.collectAsState()
    val exchanges by viewModel.assistantExchanges.collectAsState()
    val realTimeHelp by viewModel.realTimeHelpEnabled.collectAsState()
    val spitMode by viewModel.spitModeEnabled.collectAsState()
    val level by viewModel.assistanceLevel.collectAsState()
    val network by viewModel.homeNetworkStatus.collectAsState()
    val apiKey by viewModel.apiKey.collectAsState()
    val voiceStyle by viewModel.ttsVoiceStyle.collectAsState()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    fun checkLocationPermission() = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    var hasLocationPermission by remember { mutableStateOf(checkLocationPermission()) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasLocationPermission = granted }

    // Android silently revokes a "granted only this time" location permission once the app
    // leaves the foreground, so re-check every time this screen comes back to the front —
    // otherwise a stale `true` would let Lingo's home-Wi-Fi detection fail silently.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasLocationPermission = checkLocationPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Lingo Assistant", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { AssistantHeroCard(status, network) }

            item {
                val glassesState by viewModel.glassesState.collectAsState()
                val savedName by viewModel.glassesSavedName.collectAsState()
                val savedAddress by viewModel.glassesSavedAddress.collectAsState()
                val isScanning by viewModel.glassesIsScanning.collectAsState()
                val scanned by viewModel.glassesScannedDevices.collectAsState()
                GlassesConnectCard(
                    glassesState = glassesState,
                    savedName = savedName,
                    savedAddress = savedAddress,
                    isScanning = isScanning,
                    scannedDevices = scanned,
                    onStartScan = viewModel::startGlassesScan,
                    onStopScan = viewModel::stopGlassesScan,
                    onConnect = viewModel::connectGlasses,
                    onDisconnect = viewModel::disconnectGlasses,
                    onForget = viewModel::forgetGlasses,
                )
            }

            item {
                OutlinedButton(
                    onClick = onOpenVitals,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Watch vitals")
                }
            }

            if (!hasLocationPermission) {
                item {
                    LocationPermissionNotice(
                        onGrant = { locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                        onOpenSettings = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.fromParts("package", context.packageName, null)),
                            )
                        },
                    )
                }
            }

            if (apiKey.isNullOrBlank()) {
                item { MissingApiKeyNotice() }
            }

            item {
                StartStopButton(isActive = status.isActive, onStart = onStart, onStop = onStop)
            }

            item {
                SpitModeCard(
                    enabled = spitMode,
                    onToggle = viewModel::setSpitModeEnabled,
                )
            }

            item {
                RealTimeHelpCard(
                    enabled = realTimeHelp,
                    dimmed = spitMode,
                    level = level,
                    onToggle = viewModel::setRealTimeHelpEnabled,
                    onLevelChange = viewModel::setAssistanceLevel,
                )
            }

            item {
                VoiceStyleCard(
                    selected = voiceStyle,
                    onSelect = viewModel::setTtsVoiceStyle,
                )
            }

            item {
                OutlinedButton(
                    onClick = viewModel::askAboutWhatISee,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Ask about a photo")
                }
            }

            item {
                ExchangeHeader(count = exchanges.size, onClear = viewModel::clearAssistantExchanges)
            }

            if (exchanges.isEmpty()) {
                item { ExchangeEmptyState() }
            } else {
                items(exchanges.reversed()) { exchange -> ExchangeCard(exchange) }
            }
        }
    }
}

@Composable
private fun AssistantHeroCard(status: AssistantStatus, network: HomeNetworkStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (status.isActive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val onContainer = if (status.isActive) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(onContainer.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.SmartToy, contentDescription = null, tint = onContainer)
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            !status.isActive -> "Idle"
                            status.statusMessage.contains("SPIT", ignoreCase = true) ||
                                status.statusMessage.contains("Ignore my repeat", ignoreCase = true) -> "SPIT mode"
                            else -> "Listening for \"Hey Lingo\""
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = onContainer,
                    )
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
            NetworkPill(network)
        }
    }
}

@Composable
private fun NetworkPill(network: HomeNetworkStatus) {
    val (icon, label, ok) = when (network) {
        HomeNetworkStatus.HOME_WIFI -> Triple(Icons.Filled.Wifi, "Home Wi-Fi", true)
        HomeNetworkStatus.MOBILE_DATA -> Triple(Icons.Filled.SignalCellularAlt, "Mobile data (away)", true)
        HomeNetworkStatus.BLOCKED -> Triple(Icons.Filled.CloudOff, "No connection", false)
    }
    val container = if (ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
    val content = if (ok) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer
    Surface(color = container, shape = RoundedCornerShape(50)) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = content)
        }
    }
}

@Composable
private fun LocationPermissionNotice(onGrant: () -> Unit, onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Lingo needs Location access to tell your home Wi-Fi apart from other networks. " +
                    "When prompted, choose \"While using the app\" — not \"Only this time\", which " +
                    "Android revokes as soon as Lingo isn't in the foreground.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onGrant, shape = RoundedCornerShape(12.dp)) {
                    Text("Grant access")
                }
                TextButton(onClick = onOpenSettings) {
                    Text("Open app settings")
                }
            }
        }
    }
}

@Composable
private fun MissingApiKeyNotice() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            "Add a free Gemini API key in Settings before starting Lingo.",
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun StartStopButton(isActive: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    if (isActive) {
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
            Text("Stop assistant", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    } else {
        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("Start assistant", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceStyleCard(
    selected: TtsVoiceStyle,
    onSelect: (TtsVoiceStyle) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Voice", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Natural voices use the phone's higher-quality networked TTS when available.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = selected == TtsVoiceStyle.NATURAL_FEMALE,
                    onClick = { onSelect(TtsVoiceStyle.NATURAL_FEMALE) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                    label = { Text("Female") },
                )
                SegmentedButton(
                    selected = selected == TtsVoiceStyle.NATURAL_MALE,
                    onClick = { onSelect(TtsVoiceStyle.NATURAL_MALE) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                    label = { Text("Male") },
                )
                SegmentedButton(
                    selected = selected == TtsVoiceStyle.SYSTEM,
                    onClick = { onSelect(TtsVoiceStyle.SYSTEM) },
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                    label = { Text("System") },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpitModeCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "SPIT mode",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Smartest Person In Town — answers questions out loud in your headset, then ignores you when you repeat the answer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            if (enabled) {
                Text(
                    "No wake word needed. Speak a question — Lingo answers instantly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun RealTimeHelpCard(
    enabled: Boolean,
    dimmed: Boolean,
    level: AssistanceLevel,
    onToggle: (Boolean) -> Unit,
    onLevelChange: (AssistanceLevel) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.45f else 1f),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Real-time help", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Lingo listens to what's around it and offers unprompted tips.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    enabled = !dimmed,
                )
            }

            Text(
                "Assistance level: ${level.label} (${level.level}/5)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                AssistanceLevel.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = level == option,
                        onClick = { onLevelChange(option) },
                        enabled = enabled && !dimmed,
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = AssistanceLevel.entries.size),
                        label = { Text("${option.level}") },
                    )
                }
            }
            Text(
                "1 = least proactive (wake word only), 5 = most proactive.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExchangeHeader(count: Int, onClear: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = if (count > 0) "Conversation ($count)" else "Conversation",
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
private fun ExchangeEmptyState() {
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
                Icons.Filled.SmartToy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
            Text(
                "Say \"Hey Lingo\" or \"OK Lingo\" to ask something",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExchangeCard(exchange: AssistantExchange) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExchangeBadge(exchange)
                if (exchange.hasImage) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Filled.Image,
                        contentDescription = "Vision request",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = when (exchange.imageProvenance) {
                            com.livetranslate.headphones.glasses.StillProvenance.Glasses -> "Glasses"
                            com.livetranslate.headphones.glasses.StillProvenance.Phone ->
                                if (exchange.fallbackReason != null) "Phone camera" else "Phone"
                            null -> "Photo"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (exchange.hasImage && exchange.fallbackReason != null) {
                Text(
                    text = when (exchange.fallbackReason) {
                        com.livetranslate.headphones.glasses.FallbackReason.GlassesCoolingDown ->
                            "Glasses busy — used phone camera"
                        com.livetranslate.headphones.glasses.FallbackReason.GlassesNeverSignalled ->
                            "Glasses didn’t signal a photo — used phone camera"
                        com.livetranslate.headphones.glasses.FallbackReason.GlassesSignalledNoChunks ->
                            "Glasses sent no image data — used phone camera"
                        com.livetranslate.headphones.glasses.FallbackReason.GlassesChunksBadJpeg,
                        com.livetranslate.headphones.glasses.FallbackReason.GlassesMalformedPayload ->
                            "Glasses image unreadable — used phone camera"
                        com.livetranslate.headphones.glasses.FallbackReason.GlassesTimeout,
                        com.livetranslate.headphones.glasses.FallbackReason.GlassesCaptureFailed ->
                            "Glasses didn’t return a still — used phone camera"
                        com.livetranslate.headphones.glasses.FallbackReason.GlassesNotReady ->
                            "Glasses not ready — used phone camera"
                        com.livetranslate.headphones.glasses.FallbackReason.GlassesUnsupported ->
                            "Glasses unsupported — used phone camera"
                        com.livetranslate.headphones.glasses.FallbackReason.UserCancelled ->
                            "Capture cancelled"
                        null -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (!exchange.query.isNullOrBlank()) {
                Text(
                    text = "\"${exchange.query}\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = exchange.response,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ExchangeBadge(exchange: AssistantExchange) {
    val icon: ImageVector
    val label: String
    when (exchange.kind) {
        ExchangeKind.ASKED -> {
            icon = Icons.Filled.Mic
            label = "Asked"
        }
        ExchangeKind.TIP -> {
            icon = Icons.Filled.Lightbulb
            label = "Tip"
        }
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(50)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

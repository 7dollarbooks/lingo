package com.livetranslate.headphones.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.livetranslate.headphones.glasses.GlassesState
import com.livetranslate.headphones.glasses.ScannedGlassesDevice

@Composable
fun GlassesConnectCard(
    glassesState: GlassesState,
    savedName: String?,
    savedAddress: String?,
    isScanning: Boolean,
    scannedDevices: List<ScannedGlassesDevice>,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (address: String, name: String?) -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showScanDialog by remember { mutableStateOf(false) }

    fun hasScanPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val scan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
        val connect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        return scan && connect
    }

    var permitted by remember { mutableStateOf(hasScanPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        permitted = results.values.all { it }
        if (permitted) {
            showScanDialog = true
            onStartScan()
        }
    }

    DisposableEffect(lifecycleOwner, showScanDialog) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                if (showScanDialog) {
                    onStopScan()
                    showScanDialog = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (showScanDialog) onStopScan()
        }
    }

    val statusLabel = when (glassesState) {
        GlassesState.Ready -> "Connected"
        GlassesState.Connecting -> "Connecting…"
        is GlassesState.Degraded -> "Failed: ${glassesState.reason}"
        GlassesState.Unsupported -> "Unsupported"
        GlassesState.Absent -> if (savedAddress != null) "Saved — not connected" else "Not connected"
    }
    val displayName = when {
        glassesState is GlassesState.Ready || glassesState is GlassesState.Connecting ||
            glassesState is GlassesState.Degraded -> savedName ?: "Glasses"
        savedName != null -> savedName
        else -> "No glasses paired"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when {
                        glassesState is GlassesState.Ready -> Icons.Filled.BluetoothConnected
                        isScanning -> Icons.Filled.BluetoothSearching
                        else -> Icons.Filled.Bluetooth
                    },
                    contentDescription = null,
                )
                Spacer(Modifier.padding(horizontal = 6.dp))
                Column(Modifier.weight(1f)) {
                    Text("Glasses - Meta Beta", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(displayName, style = MaterialTheme.typography.bodyMedium)
                    Text(statusLabel, style = MaterialTheme.typography.bodySmall)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    glassesState is GlassesState.Ready || glassesState is GlassesState.Connecting ||
                        glassesState is GlassesState.Degraded -> {
                        OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                        TextButton(onClick = onForget) { Text("Forget") }
                    }
                    savedAddress != null -> {
                        OutlinedButton(onClick = { onConnect(savedAddress, savedName) }) {
                            Text("Connect")
                        }
                        OutlinedButton(
                            onClick = {
                                if (permitted) {
                                    showScanDialog = true
                                    onStartScan()
                                } else {
                                    permissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.BLUETOOTH_SCAN,
                                            Manifest.permission.BLUETOOTH_CONNECT,
                                        ),
                                    )
                                }
                            },
                        ) { Text("Scan") }
                        TextButton(onClick = onForget) { Text("Forget") }
                    }
                    else -> {
                        OutlinedButton(
                            onClick = {
                                if (permitted) {
                                    showScanDialog = true
                                    onStartScan()
                                } else {
                                    permissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.BLUETOOTH_SCAN,
                                            Manifest.permission.BLUETOOTH_CONNECT,
                                        ),
                                    )
                                }
                            },
                        ) { Text("Scan for glasses") }
                    }
                }
            }
        }
    }

    if (showScanDialog) {
        AlertDialog(
            onDismissRequest = {
                onStopScan()
                showScanDialog = false
            },
            title = { Text("Nearby glasses") },
            text = {
                Column {
                    if (isScanning) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Text("Scanning…")
                }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (scannedDevices.isEmpty() && !isScanning) {
                        Text("No devices found. Make sure the glasses are on and nearby.")
                    }
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(scannedDevices, key = { it.address }) { device ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onConnect(device.address, device.name)
                                        onStopScan()
                                        showScanDialog = false
                                    }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(device.name, fontWeight = FontWeight.Medium)
                                Text(
                                    "${device.address}  ·  ${device.rssi} dBm",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onStopScan()
                        showScanDialog = false
                    },
                ) { Text("Close") }
            },
            dismissButton = {
                if (!isScanning) {
                    TextButton(onClick = onStartScan) { Text("Scan again") }
                }
            },
        )
    }
}

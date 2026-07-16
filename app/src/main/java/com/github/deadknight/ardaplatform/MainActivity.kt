package com.github.deadknight.ardaplatform

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { PlatformBootstrapScreen() } }

        if (intent.getBooleanExtra(EXTRA_AUTO_START_BRIDGE, false)) {
            startForegroundService(
                Intent(this, ArdaBootstrapServerService::class.java)
                    .setAction(ArdaBootstrapServerService.ACTION_START),
            )
        }
    }

    companion object {
        const val EXTRA_AUTO_START_BRIDGE = "arda_auto_start_bridge"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlatformBootstrapScreen() {
    val context = LocalContext.current
    val initialConfig = remember { ArdaBridgeConfigStore.load(context) }
    val serverRunning by ArdaPlatformState.serverRunning
    val peerConnected by ArdaPlatformState.peerConnected
    val hotspotReady by ArdaPlatformState.hotspotReady
    val hotspotSsid by ArdaPlatformState.hotspotSsid

    var bridgeHost by rememberSaveable { mutableStateOf(initialConfig.host) }
    var bridgePort by rememberSaveable {
        mutableStateOf(initialConfig.port.toString())
    }
    var bridgeToken by rememberSaveable { mutableStateOf(initialConfig.token) }
    var configMessage by rememberSaveable { mutableStateOf<String?>(null) }

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val allGranted = result.values.all { it }
        ArdaPlatformState.log(
            "ARDA_RN3B_AAOS_NEARBY_PERMISSION=" +
                if (allGranted) "PASS" else "FAIL",
        )
    }

    val discoverableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        ArdaPlatformState.log(
            "ARDA_RN3B_AAOS_DISCOVERABLE_RESULT=${result.resultCode}",
        )
    }

    fun requestPermissions() {
        val required = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        val missing = required.filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            ArdaPlatformState.log("ARDA_RN3B_AAOS_NEARBY_PERMISSION=PASS")
        } else {
            permissionsLauncher.launch(missing.toTypedArray())
        }
    }

    fun saveBridgeConfig(): Boolean {
        val port = bridgePort.toIntOrNull()
        if (bridgeHost != "127.0.0.1") {
            configMessage = "RN-3B debug bridge must use 127.0.0.1."
            return false
        }
        if (port == null || port !in 1..65535) {
            configMessage = "Bridge port is invalid."
            return false
        }
        if (bridgeToken.isBlank()) {
            configMessage = "Bridge token cannot be empty."
            return false
        }
        ArdaBridgeConfigStore.save(
            context,
            ArdaBridgeConfig(bridgeHost, port, bridgeToken),
        )
        configMessage = "Bridge configuration saved."
        ArdaPlatformState.log("ARDA_RN3B_AAOS_BRIDGE_CONFIG_SAVED=PASS")
        return true
    }

    fun makeDiscoverable() {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            ArdaPlatformState.log("ARDA_RN3B_AAOS_ADAPTER=FAIL")
            return
        }
        discoverableLauncher.launch(
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300),
        )
    }

    fun startServer() {
        if (!saveBridgeConfig()) return
        context.startForegroundService(
            Intent(context, ArdaBootstrapServerService::class.java)
                .setAction(ArdaBootstrapServerService.ACTION_START),
        )
    }

    fun stopServer() {
        context.startService(
            Intent(context, ArdaBootstrapServerService::class.java)
                .setAction(ArdaBootstrapServerService.ACTION_STOP),
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "ARDA Platform Bluetooth Bridge",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "RN-3B Bluetooth + local-only Wi-Fi",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        peerConnected && hotspotReady -> Color(0xFFD7F8E1)
                        serverRunning -> Color(0xFFDCE9FF)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Bluetooth UUID", style = MaterialTheme.typography.labelMedium)
                    Text(
                        ArdaBluetoothTransport.serviceUuid.toString(),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Server: " + if (serverRunning) "running" else "stopped")
                    Text("Peer: " + if (peerConnected) "connected" else "none")
                    Text("Local Wi-Fi: " + if (hotspotReady) "ready (${hotspotSsid ?: "?"})" else "not ready")
                    Text("Internet egress: phone cellular proxy")
                    Text("Protocol owner: arda-senderd")
                }
            }

            OutlinedTextField(
                value = bridgeHost,
                onValueChange = { bridgeHost = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("senderd bridge host") },
                singleLine = true,
                enabled = !serverRunning,
            )
            OutlinedTextField(
                value = bridgePort,
                onValueChange = { bridgePort = it.filter(Char::isDigit) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("senderd bridge port") },
                singleLine = true,
                enabled = !serverRunning,
            )
            OutlinedTextField(
                value = bridgeToken,
                onValueChange = { bridgeToken = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("RN-3B debug bridge token") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = !serverRunning,
                supportingText = {
                    Text("Production will replace TCP/token with init Unix socket + SELinux.")
                },
            )
            configMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = ::requestPermissions, modifier = Modifier.weight(1f)) {
                    Text("Grant Nearby")
                }
                Button(onClick = ::makeDiscoverable, modifier = Modifier.weight(1f)) {
                    Text("Discoverable")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = ::startServer,
                    enabled = !serverRunning,
                    modifier = Modifier.weight(1f),
                ) { Text("Start bridge") }
                OutlinedButton(
                    onClick = ::stopServer,
                    enabled = serverRunning,
                    modifier = Modifier.weight(1f),
                ) { Text("Stop") }
            }

            Text(
                "Marker log",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(ArdaPlatformState.logs) { line ->
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            line.endsWith("=PASS") -> Color(0xFF087A36)
                            line.contains("=FAIL") || line.contains("_ERROR=") ->
                                MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(vertical = 3.dp),
                    )
                }
            }
        }
    }
}

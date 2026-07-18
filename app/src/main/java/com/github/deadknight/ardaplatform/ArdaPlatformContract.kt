package com.github.deadknight.ardaplatform

import android.net.Uri

/** Cross-user contract between the driver UI and the single-user platform core. */
object ArdaPlatformContract {
    val authority: String = "${BuildConfig.APPLICATION_ID}.provider"
    val snapshotUri: Uri = Uri.parse("content://$authority/snapshot")

    const val columnHost = "host"
    const val columnPort = "port"
    const val columnToken = "token"
    const val columnKeepBootstrapRfcommOpen = "keep_bootstrap_rfcomm_open"
    const val columnKeepBluetoothProfilesConnected =
        "keep_bluetooth_profiles_connected"
    const val columnRuntimeConfigPath = "runtime_config_path"
    const val columnServerRunning = "server_running"
    const val columnHotspotReady = "hotspot_ready"
    const val columnHotspotSsid = "hotspot_ssid"
    const val columnHotspotBssid = "hotspot_bssid"
    const val columnHotspotIp = "hotspot_ip"
    const val columnHotspotSecurity = "hotspot_security"
    const val columnPeerConnected = "peer_connected"
    const val columnPeerAddress = "peer_address"
    const val columnBridgeConnected = "bridge_connected"
    const val columnLastEvent = "last_event"
    const val columnLastError = "last_error"

    const val methodEnsureStarted = "ensure_started"
    const val methodReloadConfig = "reload_config"
}

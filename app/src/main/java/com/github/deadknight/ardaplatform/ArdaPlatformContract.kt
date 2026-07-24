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
    const val columnForcedVideoResolution = "forced_video_resolution"
    const val columnVideoRuntimeConfigPath = "video_runtime_config_path"
    const val columnVideoSdrPayload = "video_sdr_payload"
    const val columnVideoSdrUpdatedAtMillis = "video_sdr_updated_at_millis"
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

    const val methodVehicleSourcesList = "vehicle_sources_list"
    const val methodVehicleSourcesRescan = "vehicle_sources_rescan"
    const val methodVehicleSourcesApprove = "vehicle_sources_approve"
    const val methodVehicleSourcesRevoke = "vehicle_sources_revoke"
    const val methodVehicleSourcesSetEnabled = "vehicle_sources_set_enabled"

    const val methodVemStatus = "vem_status"
    const val methodVemSetBatteryPercent = "vem_set_battery_percent"
    const val methodVemSetFuelType = "vem_set_fuel_type"
    const val methodVemSetConnectorTypes = "vem_set_connector_types"
    const val methodVemClearModel = "vem_clear_model"
    const val methodVemNotifyModel = "vem_notify_model"

    const val methodVehicleProfileApply = "vehicle_profile_apply"
    const val methodVehicleProfileStatus = "vehicle_profile_status"

    const val extraUserId = "user_id"
    const val extraSources = "sources"
    const val extraError = "error"
    const val extraComponent = "component"
    const val extraPriority = "priority"
    const val extraEnabled = "enabled"
    const val extraPublishCapabilities = "publish_capabilities"
    const val extraCommandCapabilities = "command_capabilities"
    const val extraPublishProperties = "publish_properties"
    const val extraCommandProperties = "command_properties"

    const val extraRequestId = "request_id"
    const val extraRuntimeState = "runtime_state"
    const val extraRuntimeDetail = "runtime_detail"
    const val extraFuelTypeValue = "fuel_type_value"
    const val extraConnectorTypeValues = "connector_type_values"
    const val extraUpdatedAtMillis = "updated_at_millis"
}

package com.github.deadknight.ardaplatform

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.database.Cursor

data class ArdaPlatformSnapshot(
    val config: ArdaBridgeConfig,
    val runtimeConfigPath: String,
    val serverRunning: Boolean,
    val hotspotReady: Boolean,
    val hotspotSsid: String?,
    val hotspotBssid: String?,
    val hotspotIp: String?,
    val hotspotSecurity: String?,
    val peerConnected: Boolean,
    val peerAddress: String?,
    val bridgeConnected: Boolean,
    val lastEvent: String?,
    val lastError: String?,
)

/** UI-side access to the single-user provider, with a local fallback for previews. */
object ArdaPlatformRepository {
    private const val perUserRange = 100_000
    private const val bottomHvacVisibleSetting = "arda_bottom_hvac_visible"
    fun ensureCoreStarted(context: Context) {
        val called = runCatching {
            context.contentResolver.call(
                ArdaPlatformContract.snapshotUri,
                ArdaPlatformContract.methodEnsureStarted,
                null,
                null,
            )
        }.isSuccess
        if (!called) {
            context.startForegroundService(
                Intent(context, ArdaBootstrapServerService::class.java)
                    .setAction(ArdaBootstrapServerService.ACTION_START)
                    .putExtra(
                        ArdaBootstrapServerService.EXTRA_START_SOURCE,
                        "PLATFORM_UI_FALLBACK",
                    ),
            )
        }
    }

    fun loadSnapshot(context: Context): ArdaPlatformSnapshot {
        val providerSnapshot = runCatching {
            context.contentResolver.query(
                ArdaPlatformContract.snapshotUri,
                null,
                null,
                null,
                null,
            )?.use { cursor ->
                check(cursor.moveToFirst()) { "Platform snapshot is empty" }
                cursor.toSnapshot()
            }
        }.getOrNull()
        return providerSnapshot ?: localSnapshot(context)
    }


    fun requestVehicleProfileApply(
        context: Context,
        fuelType: ConfiguredFuelType,
        connectorTypes: Set<ConfiguredEvConnectorType>,
    ): VehicleProfileRuntimeStatus {
        val result = context.contentResolver.call(
            ArdaPlatformContract.snapshotUri,
            ArdaPlatformContract.methodVehicleProfileApply,
            null,
            Bundle().apply {
                putInt(ArdaPlatformContract.extraUserId, currentUserId())
                putInt(ArdaPlatformContract.extraFuelTypeValue, fuelType.vhalValue)
                val connectorValues = if (fuelType.enablesEnergyModel) {
                    connectorTypes.map { it.vhalValue }
                        .distinct()
                        .sorted()
                        .ifEmpty { listOf(ConfiguredEvConnectorType.Unknown.vhalValue) }
                } else {
                    listOf(ConfiguredEvConnectorType.Unknown.vhalValue)
                }
                putIntArray(
                    ArdaPlatformContract.extraConnectorTypeValues,
                    connectorValues.toIntArray(),
                )
            },
        ) ?: error("Vehicle profile provider returned no result")
        result.getString(ArdaPlatformContract.extraError)?.let(::error)
        return VehicleProfileRuntimeStatus.fromBundle(result)
    }

    fun loadVehicleProfileRuntimeStatus(context: Context): VehicleProfileRuntimeStatus {
        val result = runCatching {
            context.contentResolver.call(
                ArdaPlatformContract.snapshotUri,
                ArdaPlatformContract.methodVehicleProfileStatus,
                null,
                null,
            )
        }.getOrNull()
        return VehicleProfileRuntimeStatus.fromBundle(result)
    }

    fun isBottomHvacVisible(context: Context): Boolean =
        Settings.Global.getInt(
            context.contentResolver,
            bottomHvacVisibleSetting,
            1,
        ) != 0

    fun setBottomHvacVisible(context: Context, visible: Boolean) {
        check(
            Settings.Global.putInt(
                context.contentResolver,
                bottomHvacVisibleSetting,
                if (visible) 1 else 0,
            ),
        ) {
            "Could not update the bottom HVAC visibility setting."
        }
    }

    fun saveConfig(context: Context, config: ArdaBridgeConfig) {
        val values = ContentValues().apply {
            put(ArdaPlatformContract.columnHost, config.host)
            put(ArdaPlatformContract.columnPort, config.port)
            put(ArdaPlatformContract.columnToken, config.token)
            put(
                ArdaPlatformContract.columnKeepBootstrapRfcommOpen,
                config.keepBootstrapRfcommOpen,
            )
            put(
                ArdaPlatformContract.columnKeepBluetoothProfilesConnected,
                config.keepBluetoothProfilesConnected,
            )
        }
        val updated = runCatching {
            context.contentResolver.update(
                ArdaPlatformContract.snapshotUri,
                values,
                null,
                null,
            )
        }.getOrDefault(0)
        if (updated != 1) {
            ArdaBridgeConfigStore.saveAndReload(context, config)
        }
    }

    private fun Cursor.toSnapshot(): ArdaPlatformSnapshot = ArdaPlatformSnapshot(
        config = ArdaBridgeConfig(
            host = string(ArdaPlatformContract.columnHost),
            port = int(ArdaPlatformContract.columnPort),
            token = string(ArdaPlatformContract.columnToken),
            keepBootstrapRfcommOpen = boolean(
                ArdaPlatformContract.columnKeepBootstrapRfcommOpen,
            ),
            keepBluetoothProfilesConnected = boolean(
                ArdaPlatformContract.columnKeepBluetoothProfilesConnected,
            ),
        ),
        runtimeConfigPath = string(ArdaPlatformContract.columnRuntimeConfigPath),
        serverRunning = boolean(ArdaPlatformContract.columnServerRunning),
        hotspotReady = boolean(ArdaPlatformContract.columnHotspotReady),
        hotspotSsid = nullableString(ArdaPlatformContract.columnHotspotSsid),
        hotspotBssid = nullableString(ArdaPlatformContract.columnHotspotBssid),
        hotspotIp = nullableString(ArdaPlatformContract.columnHotspotIp),
        hotspotSecurity = nullableString(ArdaPlatformContract.columnHotspotSecurity),
        peerConnected = boolean(ArdaPlatformContract.columnPeerConnected),
        peerAddress = nullableString(ArdaPlatformContract.columnPeerAddress),
        bridgeConnected = boolean(ArdaPlatformContract.columnBridgeConnected),
        lastEvent = nullableString(ArdaPlatformContract.columnLastEvent),
        lastError = nullableString(ArdaPlatformContract.columnLastError),
    )

    private fun localSnapshot(context: Context): ArdaPlatformSnapshot {
        val config = ArdaBridgeConfigStore.load(context)
        return ArdaPlatformSnapshot(
            config = config,
            runtimeConfigPath = ArdaBridgeConfigStore.runtimeTomlFile(context).path,
            serverRunning = ArdaPlatformState.serverRunning.value,
            hotspotReady = ArdaPlatformState.hotspotReady.value,
            hotspotSsid = ArdaPlatformState.hotspotSsid.value,
            hotspotBssid = ArdaPlatformState.hotspotBssid.value,
            hotspotIp = ArdaPlatformState.hotspotIp.value,
            hotspotSecurity = ArdaPlatformState.hotspotSecurity.value,
            peerConnected = ArdaPlatformState.peerConnected.value,
            peerAddress = ArdaPlatformState.peerAddress.value,
            bridgeConnected = ArdaPlatformState.bridgeConnected.value,
            lastEvent = ArdaPlatformState.lastEvent.value,
            lastError = ArdaPlatformState.lastError.value,
        )
    }

    private fun currentUserId(): Int = Process.myUid() / perUserRange

    private fun Cursor.index(name: String): Int =
        getColumnIndexOrThrow(name)

    private fun Cursor.string(name: String): String = getString(index(name))
    private fun Cursor.nullableString(name: String): String? =
        index(name).let { column -> if (isNull(column)) null else getString(column) }
    private fun Cursor.int(name: String): Int = getInt(index(name))
    private fun Cursor.boolean(name: String): Boolean = getInt(index(name)) != 0
}

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
    val videoState: ArdaVideoConfigState,
    val videoRuntimeConfigPath: String,
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
    private const val launcherMapOnRightSetting = "arda_launcher_map_on_right"
    private const val launcherMapWidthPercentSetting = "arda_launcher_map_width_percent"
    private const val leftRailWhenHvacHiddenSetting =
        "arda_left_rail_when_hvac_hidden"
    private const val rightStatusRailEnabledSetting =
        "arda_right_status_rail_enabled"
    private const val leftRailRuntimeActiveSetting =
        "arda_left_rail_runtime_active"
    private const val systemBarLayoutModeSetting =
        "arda_system_bar_layout_mode"
    private const val aapDensityScaleTenthsSetting =
        "arda_aap_density_scale_tenths"
    private const val systemBarLayoutHorizontal = 0
    private const val systemBarLayoutVertical = 1

    const val launcherMapWidthMinPercent = 45
    const val launcherMapWidthMaxPercent = 80
    const val launcherMapWidthDefaultPercent = 67

    const val aapDensityScaleMinTenths = 1
    const val aapDensityScaleMaxTenths = 20
    const val aapDensityScaleDefaultTenths = 10
    const val androidMinimumWmDensity = 72

    data class VisualThemeConfig(
        val launcherBackgroundMode: Int,
        val launcherBackgroundColor: String,
        val navigationBarBackgroundColor: String,
        val statusBarBackgroundColor: String,
        val launcherBackgroundImageAvailable: Boolean,
    )
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



    fun isLeftRailRuntimeActive(context: Context): Boolean =
        Settings.Global.getInt(
            context.contentResolver,
            leftRailRuntimeActiveSetting,
            0,
        ) != 0

    fun isSystemBarLayoutVertical(context: Context): Boolean {
        val configuredMode = Settings.Global.getInt(
            context.contentResolver,
            systemBarLayoutModeSetting,
            -1,
        )
        return when (configuredMode) {
            systemBarLayoutVertical -> true
            systemBarLayoutHorizontal -> false
            else -> {
                // Upgrade compatibility: preserve an already-complete vertical
                // layout, but normalize every mixed legacy combination to
                // horizontal when the user next saves.
                isLeftRailWhenHvacHiddenEnabled(context) &&
                    isRightStatusRailEnabled(context)
            }
        }
    }

    fun setSystemBarLayoutVertical(context: Context, vertical: Boolean) {
        val resolver = context.contentResolver
        val modeValue = if (vertical) {
            systemBarLayoutVertical
        } else {
            systemBarLayoutHorizontal
        }
        val railValue = if (vertical) 1 else 0

        val writes = buildList {
            // Vertical mode has no bottom bar, therefore HVAC cannot remain
            // visible. Write all legacy keys together; SystemUI's observer
            // debounce applies the final complete mode.
            if (vertical) {
                add(Settings.Global.putInt(resolver, bottomHvacVisibleSetting, 0))
            }
            add(
                Settings.Global.putInt(
                    resolver,
                    leftRailWhenHvacHiddenSetting,
                    railValue,
                ),
            )
            add(
                Settings.Global.putInt(
                    resolver,
                    rightStatusRailEnabledSetting,
                    railValue,
                ),
            )
            add(
                Settings.Global.putInt(
                    resolver,
                    systemBarLayoutModeSetting,
                    modeValue,
                ),
            )
        }

        check(writes.all { it }) {
            "Could not update the system bar layout."
        }
    }

    fun isLeftRailWhenHvacHiddenEnabled(context: Context): Boolean =
        Settings.Global.getInt(
            context.contentResolver,
            leftRailWhenHvacHiddenSetting,
            0,
        ) != 0

    fun setLeftRailWhenHvacHiddenEnabled(context: Context, enabled: Boolean) {
        check(
            Settings.Global.putInt(
                context.contentResolver,
                leftRailWhenHvacHiddenSetting,
                if (enabled) 1 else 0,
            ),
        ) {
            "Could not update the HVAC-hidden left rail setting."
        }
    }

    fun isRightStatusRailEnabled(context: Context): Boolean =
        Settings.Global.getInt(
            context.contentResolver,
            rightStatusRailEnabledSetting,
            0,
        ) != 0

    fun setRightStatusRailEnabled(context: Context, enabled: Boolean) {
        check(
            Settings.Global.putInt(
                context.contentResolver,
                rightStatusRailEnabledSetting,
                if (enabled) 1 else 0,
            ),
        ) {
            "Could not update the right status rail setting."
        }
    }

    fun isLauncherMapOnRight(context: Context): Boolean =
        Settings.Global.getInt(
            context.contentResolver,
            launcherMapOnRightSetting,
            1,
        ) != 0

    fun setLauncherMapOnRight(context: Context, onRight: Boolean) {
        check(
            Settings.Global.putInt(
                context.contentResolver,
                launcherMapOnRightSetting,
                if (onRight) 1 else 0,
            ),
        ) {
            "Could not update the launcher map position."
        }
    }

    fun getLauncherMapWidthPercent(context: Context): Int =
        Settings.Global.getInt(
            context.contentResolver,
            launcherMapWidthPercentSetting,
            launcherMapWidthDefaultPercent,
        ).coerceIn(launcherMapWidthMinPercent, launcherMapWidthMaxPercent)

    fun setLauncherMapWidthPercent(context: Context, percent: Int) {
        val safePercent = percent.coerceIn(
            launcherMapWidthMinPercent,
            launcherMapWidthMaxPercent,
        )
        check(
            Settings.Global.putInt(
                context.contentResolver,
                launcherMapWidthPercentSetting,
                safePercent,
            ),
        ) {
            "Could not update the launcher map width."
        }
    }

    fun getAapDensityScaleTenths(context: Context): Int =
        Settings.Global.getInt(
            context.contentResolver,
            aapDensityScaleTenthsSetting,
            aapDensityScaleDefaultTenths,
        ).coerceIn(aapDensityScaleMinTenths, aapDensityScaleMaxTenths)

    fun setAapDensityScaleTenths(context: Context, tenths: Int) {
        val safeTenths = tenths.coerceIn(
            aapDensityScaleMinTenths,
            aapDensityScaleMaxTenths,
        )
        check(
            Settings.Global.putInt(
                context.contentResolver,
                aapDensityScaleTenthsSetting,
                safeTenths,
            ),
        ) {
            "Could not update the Android Auto density scale."
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
            put(
                ArdaPlatformContract.columnForcedVideoResolution,
                config.forcedVideoResolution,
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
            forcedVideoResolution = string(
                ArdaPlatformContract.columnForcedVideoResolution,
            ),
        ),
        videoState = ArdaVideoConfigStore.loadFromSnapshot(
            forcedResolution = string(ArdaPlatformContract.columnForcedVideoResolution),
            rawSdrPayload = nullableString(ArdaPlatformContract.columnVideoSdrPayload),
            updatedAtMillis = long(ArdaPlatformContract.columnVideoSdrUpdatedAtMillis),
        ),
        videoRuntimeConfigPath = string(ArdaPlatformContract.columnVideoRuntimeConfigPath),
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

    fun getVisualTheme(context: Context): VisualThemeConfig {
        val resolver = context.contentResolver

        fun readBar(key: String): String {
            val value = Settings.Global.getString(resolver, key)
                ?: return ArdaVisualTheme.noOverride
            if (value.equals(ArdaVisualTheme.noOverride, ignoreCase = true)) {
                return ArdaVisualTheme.noOverride
            }
            return value.takeIf(ArdaVisualTheme::isValidColor)
                ?.let(ArdaVisualTheme::normalizeColor)
                ?: ArdaVisualTheme.noOverride
        }

        return VisualThemeConfig(
            launcherBackgroundMode = Settings.Global.getInt(
                resolver,
                ArdaVisualTheme.launcherBackgroundModeSetting,
                ArdaVisualTheme.launcherBackgroundModeDefault,
            ).coerceIn(
                ArdaVisualTheme.launcherBackgroundModeDefault,
                ArdaVisualTheme.launcherBackgroundModeImage,
            ),
            launcherBackgroundColor = Settings.Global.getString(
                resolver,
                ArdaVisualTheme.launcherBackgroundColorSetting,
            )?.takeIf(ArdaVisualTheme::isValidColor)
                ?.let(ArdaVisualTheme::normalizeColor)
                ?: ArdaVisualTheme.defaultLauncherColor,
            navigationBarBackgroundColor = readBar(
                ArdaVisualTheme.navigationBarBackgroundColorSetting,
            ),
            statusBarBackgroundColor = readBar(
                ArdaVisualTheme.statusBarBackgroundColorSetting,
            ),
            launcherBackgroundImageAvailable =
                ArdaVisualThemeStore.hasLauncherBackground(context),
        )
    }

    fun saveVisualTheme(
        context: Context,
        launcherBackgroundMode: Int,
        launcherBackgroundColor: String,
        navigationBarBackgroundColor: String,
        statusBarBackgroundColor: String,
    ) {
        val resolver = context.contentResolver
        val normalizedLauncher =
            ArdaVisualTheme.normalizeColor(launcherBackgroundColor)
        check(ArdaVisualTheme.isValidColor(normalizedLauncher)) {
            "Invalid launcher background color"
        }

        fun normalizeBar(value: String): String {
            if (value.equals(ArdaVisualTheme.noOverride, ignoreCase = true)) {
                return ArdaVisualTheme.noOverride
            }
            val normalized = ArdaVisualTheme.normalizeColor(value)
            check(ArdaVisualTheme.isValidColor(normalized)) {
                "Invalid system bar color"
            }
            return normalized
        }

        val normalizedNavigation = normalizeBar(navigationBarBackgroundColor)
        val normalizedStatus = normalizeBar(statusBarBackgroundColor)

        if (launcherBackgroundMode == ArdaVisualTheme.launcherBackgroundModeImage) {
            check(ArdaVisualThemeStore.hasLauncherBackground(context)) {
                "Select a launcher background image first"
            }
        }

        val writes = listOf(
            Settings.Global.putInt(
                resolver,
                ArdaVisualTheme.launcherBackgroundModeSetting,
                launcherBackgroundMode.coerceIn(
                    ArdaVisualTheme.launcherBackgroundModeDefault,
                    ArdaVisualTheme.launcherBackgroundModeImage,
                ),
            ),
            Settings.Global.putString(
                resolver,
                ArdaVisualTheme.launcherBackgroundColorSetting,
                normalizedLauncher,
            ),
            Settings.Global.putString(
                resolver,
                ArdaVisualTheme.launcherBackgroundUriSetting,
                ArdaVisualTheme.launcherBackgroundUri(context).toString(),
            ),
            Settings.Global.putString(
                resolver,
                ArdaVisualTheme.navigationBarBackgroundColorSetting,
                normalizedNavigation,
            ),
            Settings.Global.putString(
                resolver,
                ArdaVisualTheme.statusBarBackgroundColorSetting,
                normalizedStatus,
            ),
        )
        check(writes.all { it }) { "Could not save ARDA visual theme" }

        // Written last so Launcher/SystemUI consume one coherent snapshot.
        ArdaVisualThemeStore.bumpRevision(context)
    }

    private fun localSnapshot(context: Context): ArdaPlatformSnapshot {
        val config = ArdaBridgeConfigStore.load(context)
        return ArdaPlatformSnapshot(
            config = config,
            videoState = ArdaVideoConfigStore.load(context),
            videoRuntimeConfigPath = ArdaVideoConfigStore.runtimeTomlFile(context).path,
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
    private fun Cursor.long(name: String): Long = getLong(index(name))
    private fun Cursor.boolean(name: String): Boolean = getInt(index(name)) != 0
}

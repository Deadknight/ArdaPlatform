package com.github.deadknight.ardaplatform

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.arda.vehicle.admin.VehicleSourceGrantRequest
import com.arda.vehicle.admin.VehicleSourceInfo

/**
 * Signature-protected single-user bridge for the foreground driver UI.
 *
 * The provider always executes in the system-user core process, so configuration
 * updates target the TOML watched by arda-connectivity-supervisor even when the
 * Activity is later installed for a FULL driver user.
 */
class ArdaPlatformProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let {
            ArdaBridgeConfigStore.ensureRuntimeConfig(it)
            VehicleSourceManagerClient.connect(it)
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        require(matcher.match(uri) == matchSnapshot) { "Unsupported URI: $uri" }
        val coreContext = requireNotNull(context)
        val config = ArdaBridgeConfigStore.load(coreContext)
        val videoState = ArdaVideoConfigStore.load(coreContext)
        val columns = snapshotColumns
        return MatrixCursor(columns, 1).apply {
            addRow(
                arrayOf(
                    config.host,
                    config.port,
                    config.token,
                    config.keepBootstrapRfcommOpen.asInt(),
                    config.keepBluetoothProfilesConnected.asInt(),
                    config.forcedVideoResolution,
                    ArdaVideoConfigStore.runtimeTomlFile(coreContext).path,
                    videoState.rawSdrPayload,
                    videoState.updatedAtMillis,
                    ArdaBridgeConfigStore.runtimeTomlFile(coreContext).path,
                    ArdaPlatformState.serverRunning.value.asInt(),
                    ArdaPlatformState.hotspotReady.value.asInt(),
                    ArdaPlatformState.hotspotSsid.value,
                    ArdaPlatformState.hotspotBssid.value,
                    ArdaPlatformState.hotspotIp.value,
                    ArdaPlatformState.hotspotSecurity.value,
                    ArdaPlatformState.peerConnected.value.asInt(),
                    ArdaPlatformState.peerAddress.value,
                    ArdaPlatformState.bridgeConnected.value.asInt(),
                    ArdaPlatformState.lastEvent.value,
                    ArdaPlatformState.lastError.value,
                ),
            )
        }
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        require(matcher.match(uri) == matchSnapshot) { "Unsupported URI: $uri" }
        val coreContext = requireNotNull(context)
        val current = ArdaBridgeConfigStore.load(coreContext)
        val requested = requireNotNull(values) { "Configuration values are required" }
        val updated = ArdaBridgeConfig(
            host = requested.getAsString(ArdaPlatformContract.columnHost)
                ?: current.host,
            port = requested.getAsInteger(ArdaPlatformContract.columnPort)
                ?: current.port,
            token = requested.getAsString(ArdaPlatformContract.columnToken)
                ?: current.token,
            keepBootstrapRfcommOpen = requested.booleanOrDefault(
                ArdaPlatformContract.columnKeepBootstrapRfcommOpen,
                current.keepBootstrapRfcommOpen,
            ),
            keepBluetoothProfilesConnected = requested.booleanOrDefault(
                ArdaPlatformContract.columnKeepBluetoothProfilesConnected,
                current.keepBluetoothProfilesConnected,
            ),
            forcedVideoResolution = requested.getAsString(
                ArdaPlatformContract.columnForcedVideoResolution,
            ) ?: current.forcedVideoResolution,
        )
        ArdaBridgeConfigStore.saveAndReload(coreContext, updated)
        coreContext.contentResolver.notifyChange(ArdaPlatformContract.snapshotUri, null)
        return 1
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val coreContext = requireNotNull(context)
        when (method) {
            ArdaPlatformContract.methodEnsureStarted -> {
                coreContext.startForegroundService(
                    Intent(coreContext, ArdaBootstrapServerService::class.java)
                        .setAction(ArdaBootstrapServerService.ACTION_START)
                        .putExtra(
                            ArdaBootstrapServerService.EXTRA_START_SOURCE,
                            "PLATFORM_UI",
                        ),
                )
            }

            ArdaPlatformContract.methodReloadConfig -> {
                coreContext.startForegroundService(
                    Intent(coreContext, ArdaBootstrapServerService::class.java)
                        .setAction(ArdaBootstrapServerService.ACTION_RELOAD_CONFIG),
                )
            }

            ArdaPlatformContract.methodVehicleSourcesList -> {
                return vehicleSourcesResult {
                    val userId = requireNotNull(extras).getInt(
                        ArdaPlatformContract.extraUserId,
                    )
                    val manager = VehicleSourceManagerClient.require(coreContext)
                    val items = ArrayList<Bundle>()
                    manager.listSources(userId).orEmpty().forEach { info ->
                        items += info.toBundle()
                    }
                    Bundle().apply {
                        putParcelableArrayList(ArdaPlatformContract.extraSources, items)
                    }
                }
            }

            ArdaPlatformContract.methodVehicleSourcesRescan -> {
                return vehicleSourcesResult {
                    val userId = requireNotNull(extras).getInt(
                        ArdaPlatformContract.extraUserId,
                    )
                    VehicleSourceManagerClient.require(coreContext).rescan(userId)
                    Bundle.EMPTY
                }
            }

            ArdaPlatformContract.methodVehicleSourcesApprove -> {
                return vehicleSourcesResult {
                    val data = requireNotNull(extras)
                    val request = VehicleSourceGrantRequest().apply {
                        userId = data.getInt(ArdaPlatformContract.extraUserId)
                        componentName = requireNotNull(
                            data.getString(ArdaPlatformContract.extraComponent),
                        )
                        priority = data.getInt(ArdaPlatformContract.extraPriority, 100)
                        publishCapabilities = data.getIntArray(
                            ArdaPlatformContract.extraPublishCapabilities,
                        ) ?: intArrayOf()
                        commandCapabilities = data.getIntArray(
                            ArdaPlatformContract.extraCommandCapabilities,
                        ) ?: intArrayOf()
                        publishPropertyIds = data.getIntArray(
                            ArdaPlatformContract.extraPublishProperties,
                        ) ?: intArrayOf()
                        commandPropertyIds = data.getIntArray(
                            ArdaPlatformContract.extraCommandProperties,
                        ) ?: intArrayOf()
                    }
                    VehicleSourceManagerClient.require(coreContext).approveSource(request)
                    Bundle.EMPTY
                }
            }

            ArdaPlatformContract.methodVehicleSourcesRevoke -> {
                return vehicleSourcesResult {
                    val data = requireNotNull(extras)
                    VehicleSourceManagerClient.require(coreContext).revokeSource(
                        data.getInt(ArdaPlatformContract.extraUserId),
                        requireNotNull(data.getString(ArdaPlatformContract.extraComponent)),
                    )
                    Bundle.EMPTY
                }
            }

            ArdaPlatformContract.methodVehicleSourcesSetEnabled -> {
                return vehicleSourcesResult {
                    val data = requireNotNull(extras)
                    VehicleSourceManagerClient.require(coreContext).setSourceEnabled(
                        data.getInt(ArdaPlatformContract.extraUserId),
                        requireNotNull(data.getString(ArdaPlatformContract.extraComponent)),
                        data.getBoolean(ArdaPlatformContract.extraEnabled),
                    )
                    Bundle.EMPTY
                }
            }

            ArdaPlatformContract.methodVemStatus -> {
                return vehicleEnergyResult(
                    coreContext,
                    VehicleEnergyContract.methodStatus,
                    extras,
                )
            }

            ArdaPlatformContract.methodVemSetBatteryPercent -> {
                return vehicleEnergyResult(
                    coreContext,
                    VehicleEnergyContract.methodSetBatteryPercent,
                    extras,
                )
            }

            ArdaPlatformContract.methodVemSetFuelType -> {
                return vehicleEnergyResult(
                    coreContext,
                    VehicleEnergyContract.methodSetFuelType,
                    extras,
                )
            }

            ArdaPlatformContract.methodVemSetConnectorTypes -> {
                return vehicleEnergyResult(
                    coreContext,
                    VehicleEnergyContract.methodSetConnectorTypes,
                    extras,
                )
            }

            ArdaPlatformContract.methodVemClearModel -> {
                return vehicleEnergyResult(
                    coreContext,
                    VehicleEnergyContract.methodClearModel,
                    extras,
                )
            }

            ArdaPlatformContract.methodVemNotifyModel -> {
                return vehicleEnergyResult(
                    coreContext,
                    VehicleEnergyContract.methodNotifyModel,
                    extras,
                )
            }

            ArdaPlatformContract.methodVehicleProfileApply -> {
                val data = requireNotNull(extras)
                val targetUserId = data.getInt(ArdaPlatformContract.extraUserId, -1)
                require(targetUserId >= 0) { "Invalid target user: $targetUserId" }

                val fuelTypeValue = data.getInt(
                    ArdaPlatformContract.extraFuelTypeValue,
                    Int.MIN_VALUE,
                )
                val fuelType = ConfiguredFuelType.entries.firstOrNull {
                    it.vhalValue == fuelTypeValue
                } ?: error("Unsupported fuel type value: $fuelTypeValue")

                val requestedConnectors: IntArray = data.getIntArray(
                    ArdaPlatformContract.extraConnectorTypeValues,
                ) ?: intArrayOf()
                val connectorValues = if (fuelType.enablesEnergyModel) {
                    val effectiveConnectors = if (requestedConnectors.isEmpty()) {
                        intArrayOf(ConfiguredEvConnectorType.Unknown.vhalValue)
                    } else {
                        requestedConnectors
                    }
                    effectiveConnectors.distinct().sorted()
                } else {
                    listOf(ConfiguredEvConnectorType.Unknown.vhalValue)
                }
                connectorValues.forEach { value ->
                    require(ConfiguredEvConnectorType.entries.any { it.vhalValue == value }) {
                        "Unsupported connector value: $value"
                    }
                }

                val applying = VehicleProfileRuntimeStore.beginApply(
                    coreContext,
                    targetUserId,
                    fuelType.vhalValue,
                    connectorValues,
                )
                return runCatching {
                    VehicleSourceManagerClient.require(coreContext).applyVehicleInfo(
                        targetUserId,
                        intArrayOf(fuelType.vhalValue),
                        connectorValues.toIntArray(),
                    )
                    val notifyResult = vehicleEnergyResult(
                        coreContext,
                        VehicleEnergyContract.methodNotifyModel,
                        Bundle().apply {
                            putInt(ArdaPlatformContract.extraUserId, targetUserId)
                        },
                    )
                    notifyResult.getString(VehicleEnergyContract.extraError)?.let(::error)
                    VehicleProfileRuntimeStore.complete(
                        coreContext,
                        applying,
                        VehicleProfileRuntimeState.Pass,
                        "Fuel and connector values applied; CarService refreshed; energy model notified. Maps refresh is best effort.",
                    ).toBundle()
                }.getOrElse { failure ->
                    VehicleProfileRuntimeStore.complete(
                        coreContext,
                        applying,
                        VehicleProfileRuntimeState.Fail,
                        failure.message ?: failure.javaClass.simpleName,
                    ).toBundle()
                }
            }

            ArdaPlatformContract.methodVehicleProfileStatus -> {
                return VehicleProfileRuntimeStore.statusBundle(coreContext)
            }

            else -> return super.call(method, arg, extras)
        }
        return Bundle.EMPTY
    }


    private fun vehicleEnergyResult(
        coreContext: android.content.Context,
        method: String,
        extras: Bundle?,
    ): Bundle = runCatching {
        val callerUserId = Binder.getCallingUid() / perUserRange
        val targetUserId = extras?.getInt(
            ArdaPlatformContract.extraUserId,
            callerUserId,
        ) ?: callerUserId
        require(targetUserId >= 0) { "Invalid target user: $targetUserId" }

        coreContext.contentResolver.call(
            VehicleEnergyContract.rootUriForUser(targetUserId),
            method,
            null,
            Bundle(extras ?: Bundle.EMPTY),
        ) ?: Bundle.EMPTY
    }.getOrElse { error ->
        Bundle().apply {
            putString(
                ArdaPlatformContract.extraError,
                error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private inline fun vehicleSourcesResult(block: () -> Bundle): Bundle =
        runCatching(block).getOrElse { error ->
            Bundle().apply {
                putString(
                    ArdaPlatformContract.extraError,
                    error.message ?: error.javaClass.simpleName,
                )
            }
        }

    private fun VehicleSourceInfo.toBundle(): Bundle = Bundle().apply {
        putInt(ArdaPlatformContract.extraUserId, userId)
        putString("package_name", packageName)
        putString(ArdaPlatformContract.extraComponent, componentName)
        putString("adapter_id", adapterId)
        putString("display_name", displayName)
        putInt("uid", uid)
        putInt("api_version", apiVersion)
        putString("signer_summary", signerSummary)
        putInt("requested_priority", requestedPriority)
        putInt(ArdaPlatformContract.extraPriority, effectivePriority)
        putInt("source_id", sourceId)
        putIntArray("requested_publish_capabilities", requestedPublishCapabilities)
        putIntArray("requested_command_capabilities", requestedCommandCapabilities)
        putIntArray("requested_publish_properties", requestedPublishPropertyIds)
        putIntArray("requested_command_properties", requestedCommandPropertyIds)
        putIntArray("granted_publish_capabilities", grantedPublishCapabilities)
        putIntArray("granted_command_capabilities", grantedCommandCapabilities)
        putIntArray("granted_publish_properties", grantedPublishPropertyIds)
        putIntArray("granted_command_properties", grantedCommandPropertyIds)
        putString("state", state)
        putBoolean(ArdaPlatformContract.extraEnabled, enabled)
        putBoolean("connected", connected)
        putBoolean("ready", ready)
        putBoolean("router_registered", routerRegistered)
        putInt("pending_signals", pendingSignals)
        putBoolean("additional_approval", additionalApproval)
        putString("last_error", lastError)
    }

    override fun getType(uri: Uri): String =
        "vnd.android.cursor.item/vnd.${ArdaPlatformContract.authority}.snapshot"

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Insert is not supported")

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Delete is not supported")

    private fun Boolean.asInt(): Int = if (this) 1 else 0

    private fun ContentValues.booleanOrDefault(key: String, default: Boolean): Boolean =
        when (val value = get(key)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.equals("true", ignoreCase = true) || value == "1"
            else -> default
        }

    companion object {
        private const val perUserRange = 100_000
        private const val matchSnapshot = 1
        private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(ArdaPlatformContract.authority, "snapshot", matchSnapshot)
        }
        private val snapshotColumns = arrayOf(
            ArdaPlatformContract.columnHost,
            ArdaPlatformContract.columnPort,
            ArdaPlatformContract.columnToken,
            ArdaPlatformContract.columnKeepBootstrapRfcommOpen,
            ArdaPlatformContract.columnKeepBluetoothProfilesConnected,
            ArdaPlatformContract.columnForcedVideoResolution,
            ArdaPlatformContract.columnVideoRuntimeConfigPath,
            ArdaPlatformContract.columnVideoSdrPayload,
            ArdaPlatformContract.columnVideoSdrUpdatedAtMillis,
            ArdaPlatformContract.columnRuntimeConfigPath,
            ArdaPlatformContract.columnServerRunning,
            ArdaPlatformContract.columnHotspotReady,
            ArdaPlatformContract.columnHotspotSsid,
            ArdaPlatformContract.columnHotspotBssid,
            ArdaPlatformContract.columnHotspotIp,
            ArdaPlatformContract.columnHotspotSecurity,
            ArdaPlatformContract.columnPeerConnected,
            ArdaPlatformContract.columnPeerAddress,
            ArdaPlatformContract.columnBridgeConnected,
            ArdaPlatformContract.columnLastEvent,
            ArdaPlatformContract.columnLastError,
        )
    }
}

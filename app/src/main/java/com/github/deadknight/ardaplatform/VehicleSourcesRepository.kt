package com.github.deadknight.ardaplatform

import android.content.Context
import android.os.Bundle

object VehicleSourcesRepository {
    private const val PER_USER_UID_RANGE = 100_000

    private fun currentUserId(): Int =
        android.os.Process.myUid() / PER_USER_UID_RANGE

    fun load(context: Context): VehicleSourcesState =
        runCatching {
            loadOrThrow(context)
        }.getOrElse { error ->
            VehicleSourcesState(
                error = providerErrorMessage(error),
            )
        }

    private fun loadOrThrow(context: Context): VehicleSourcesState {
        val result = call(
            context,
            ArdaPlatformContract.methodVehicleSourcesList,
            Bundle().apply {
                putInt(ArdaPlatformContract.extraUserId, currentUserId())
            },
        )
        val error = result.getString(ArdaPlatformContract.extraError)
        if (error != null) return VehicleSourcesState(error = error)

        @Suppress("DEPRECATION")
        val bundles = result.getParcelableArrayList<Bundle>(
            ArdaPlatformContract.extraSources,
        ).orEmpty()

        return VehicleSourcesState(
            sources = bundles.map { it.toVehicleSource() },
        )
    }

    fun rescan(context: Context) {
        callChecked(
            context,
            ArdaPlatformContract.methodVehicleSourcesRescan,
            baseExtras(),
        )
    }

    fun approve(
        context: Context,
        source: VehicleSourceSnapshot,
        publishProperties: IntArray,
        commandProperties: IntArray,
    ) {
        val publishCapabilities = if (publishProperties.isNotEmpty()) {
            source.requestedPublishCapabilities
        } else {
            intArrayOf()
        }
        val commandCapabilities = if (commandProperties.isNotEmpty()) {
            source.requestedCommandCapabilities
        } else {
            intArrayOf()
        }

        callChecked(
            context,
            ArdaPlatformContract.methodVehicleSourcesApprove,
            baseExtras().apply {
                putString(ArdaPlatformContract.extraComponent, source.componentName)
                putInt(ArdaPlatformContract.extraPriority, source.effectivePriority)
                putIntArray(
                    ArdaPlatformContract.extraPublishCapabilities,
                    publishCapabilities,
                )
                putIntArray(
                    ArdaPlatformContract.extraCommandCapabilities,
                    commandCapabilities,
                )
                putIntArray(
                    ArdaPlatformContract.extraPublishProperties,
                    publishProperties,
                )
                putIntArray(
                    ArdaPlatformContract.extraCommandProperties,
                    commandProperties,
                )
            },
        )
    }

    fun revoke(context: Context, source: VehicleSourceSnapshot) {
        callChecked(
            context,
            ArdaPlatformContract.methodVehicleSourcesRevoke,
            baseExtras().apply {
                putString(
                    ArdaPlatformContract.extraComponent,
                    source.componentName,
                )
            },
        )
    }

    fun setEnabled(
        context: Context,
        source: VehicleSourceSnapshot,
        enabled: Boolean,
    ) {
        callChecked(
            context,
            ArdaPlatformContract.methodVehicleSourcesSetEnabled,
            baseExtras().apply {
                putString(
                    ArdaPlatformContract.extraComponent,
                    source.componentName,
                )
                putBoolean(ArdaPlatformContract.extraEnabled, enabled)
            },
        )
    }

    private fun baseExtras(): Bundle = Bundle().apply {
        putInt(ArdaPlatformContract.extraUserId, currentUserId())
    }

    private fun callChecked(
        context: Context,
        method: String,
        extras: Bundle,
    ) {
        val result = call(context, method, extras)
        result.getString(ArdaPlatformContract.extraError)?.let(::error)
    }

    private fun call(
        context: Context,
        method: String,
        extras: Bundle,
    ): Bundle = requireNotNull(
        context.contentResolver.call(
            ArdaPlatformContract.snapshotUri,
            method,
            null,
            extras,
        ),
    ) {
        "Vehicle Sources provider returned no result"
    }

    private fun providerErrorMessage(error: Throwable): String {
        val authority = ArdaPlatformContract.authority
        return when (error) {
            is IllegalArgumentException ->
                "ARDA Platform core provider is unavailable: $authority"

            is SecurityException ->
                "ARDA Platform core provider permission denied: $authority"

            else ->
                error.message ?: "Vehicle Sources provider is unavailable"
        }
    }

    private fun Bundle.toVehicleSource(): VehicleSourceSnapshot =
        VehicleSourceSnapshot(
            userId = getInt(ArdaPlatformContract.extraUserId),
            packageName = getString("package_name").orEmpty(),
            componentName = getString(
                ArdaPlatformContract.extraComponent,
            ).orEmpty(),
            adapterId = getString("adapter_id").orEmpty(),
            displayName = getString("display_name").orEmpty(),
            uid = getInt("uid", -1),
            apiVersion = getInt("api_version"),
            signerSummary = getString("signer_summary").orEmpty(),
            requestedPriority = getInt("requested_priority"),
            effectivePriority = getInt(
                ArdaPlatformContract.extraPriority,
            ),
            sourceId = getInt("source_id"),
            requestedPublishCapabilities = getIntArray(
                "requested_publish_capabilities",
            ) ?: intArrayOf(),
            requestedCommandCapabilities = getIntArray(
                "requested_command_capabilities",
            ) ?: intArrayOf(),
            requestedPublishPropertyIds = getIntArray(
                "requested_publish_properties",
            ) ?: intArrayOf(),
            requestedCommandPropertyIds = getIntArray(
                "requested_command_properties",
            ) ?: intArrayOf(),
            grantedPublishCapabilities = getIntArray(
                "granted_publish_capabilities",
            ) ?: intArrayOf(),
            grantedCommandCapabilities = getIntArray(
                "granted_command_capabilities",
            ) ?: intArrayOf(),
            grantedPublishPropertyIds = getIntArray(
                "granted_publish_properties",
            ) ?: intArrayOf(),
            grantedCommandPropertyIds = getIntArray(
                "granted_command_properties",
            ) ?: intArrayOf(),
            state = getString("state").orEmpty(),
            enabled = getBoolean(ArdaPlatformContract.extraEnabled),
            connected = getBoolean("connected"),
            ready = getBoolean("ready"),
            routerRegistered = getBoolean("router_registered"),
            pendingSignals = getInt("pending_signals"),
            additionalApproval = getBoolean("additional_approval"),
            lastError = getString("last_error").orEmpty(),
        )
}

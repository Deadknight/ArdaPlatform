package com.github.deadknight.ardaplatform

import android.content.Context
import android.os.Bundle

/** Status persistence for the direct Manager -> router -> VHAL administration path. */
object VehicleProfileRuntimeStore {
    private const val preferencesName = "arda_vehicle_profile_runtime"
    private const val keyRequestId = "request_id"
    private const val keyState = "state"
    private const val keyDetail = "detail"
    private const val keyTargetUserId = "target_user_id"
    private const val keyFuelTypeValue = "fuel_type_value"
    private const val keyConnectorValues = "connector_values"
    private const val keyUpdatedAtMillis = "updated_at_millis"

    fun beginApply(
        context: Context,
        targetUserId: Int,
        fuelTypeValue: Int,
        connectorValues: List<Int>,
    ): VehicleProfileRuntimeStatus {
        val status = VehicleProfileRuntimeStatus(
            requestId = System.currentTimeMillis().toString(),
            state = VehicleProfileRuntimeState.Applying,
            detail = "Applying through ArdaVehicleManager and the authenticated VHAL router.",
            targetUserId = targetUserId,
            fuelTypeValue = fuelTypeValue,
            connectorValues = connectorValues.distinct().sorted(),
            updatedAtMillis = System.currentTimeMillis(),
        )
        saveStatus(context, status)
        return status
    }

    fun complete(
        context: Context,
        current: VehicleProfileRuntimeStatus,
        state: VehicleProfileRuntimeState,
        detail: String,
    ): VehicleProfileRuntimeStatus {
        require(state == VehicleProfileRuntimeState.Pass || state == VehicleProfileRuntimeState.Fail)
        val status = current.copy(
            state = state,
            detail = detail.take(300),
            updatedAtMillis = System.currentTimeMillis(),
        )
        saveStatus(context, status)
        context.contentResolver.notifyChange(ArdaPlatformContract.snapshotUri, null)
        return status
    }

    fun loadStatus(context: Context): VehicleProfileRuntimeStatus {
        val preferences = preferences(context)
        return VehicleProfileRuntimeStatus(
            requestId = preferences.getString(keyRequestId, null),
            state = VehicleProfileRuntimeState.parse(preferences.getString(keyState, null)),
            detail = preferences.getString(keyDetail, null),
            targetUserId = preferences.getInt(keyTargetUserId, -1),
            fuelTypeValue = preferences.getInt(keyFuelTypeValue, -1),
            connectorValues = preferences.getString(keyConnectorValues, null)
                .orEmpty()
                .split(',')
                .mapNotNull(String::toIntOrNull)
                .distinct()
                .sorted(),
            updatedAtMillis = preferences.getLong(keyUpdatedAtMillis, 0L),
        )
    }

    fun statusBundle(context: Context): Bundle = loadStatus(context).toBundle()

    private fun saveStatus(context: Context, status: VehicleProfileRuntimeStatus) {
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(keyRequestId, status.requestId)
            .putString(keyState, status.state.wireName)
            .putString(keyDetail, status.detail)
            .putInt(keyTargetUserId, status.targetUserId)
            .putInt(keyFuelTypeValue, status.fuelTypeValue)
            .putString(keyConnectorValues, status.connectorValues.joinToString(","))
            .putLong(keyUpdatedAtMillis, status.updatedAtMillis)
            .apply()
    }

    private fun preferences(context: Context) =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
}

enum class VehicleProfileRuntimeState(val wireName: String) {
    Idle("IDLE"),
    Pending("PENDING"),
    Applying("APPLYING"),
    Pass("PASS"),
    Fail("FAIL");

    val terminal: Boolean
        get() = this == Pass || this == Fail

    companion object {
        fun parse(value: String?): VehicleProfileRuntimeState =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: Idle
    }
}

data class VehicleProfileRuntimeStatus(
    val requestId: String?,
    val state: VehicleProfileRuntimeState,
    val detail: String?,
    val targetUserId: Int,
    val fuelTypeValue: Int,
    val connectorValues: List<Int>,
    val updatedAtMillis: Long,
) {
    fun toBundle(): Bundle = Bundle().apply {
        putString(ArdaPlatformContract.extraRequestId, requestId)
        putString(ArdaPlatformContract.extraRuntimeState, state.wireName)
        putString(ArdaPlatformContract.extraRuntimeDetail, detail)
        putInt(ArdaPlatformContract.extraUserId, targetUserId)
        putInt(ArdaPlatformContract.extraFuelTypeValue, fuelTypeValue)
        putIntArray(
            ArdaPlatformContract.extraConnectorTypeValues,
            connectorValues.toIntArray(),
        )
        putLong(ArdaPlatformContract.extraUpdatedAtMillis, updatedAtMillis)
    }

    companion object {
        fun fromBundle(bundle: Bundle?): VehicleProfileRuntimeStatus {
            val data = bundle ?: Bundle.EMPTY
            val connectorArray: IntArray = data.getIntArray(
                ArdaPlatformContract.extraConnectorTypeValues,
            ) ?: intArrayOf()
            return VehicleProfileRuntimeStatus(
                requestId = data.getString(ArdaPlatformContract.extraRequestId),
                state = VehicleProfileRuntimeState.parse(
                    data.getString(ArdaPlatformContract.extraRuntimeState),
                ),
                detail = data.getString(ArdaPlatformContract.extraRuntimeDetail),
                targetUserId = data.getInt(ArdaPlatformContract.extraUserId, -1),
                fuelTypeValue = data.getInt(ArdaPlatformContract.extraFuelTypeValue, -1),
                connectorValues = connectorArray.toList(),
                updatedAtMillis = data.getLong(
                    ArdaPlatformContract.extraUpdatedAtMillis,
                    0L,
                ),
            )
        }
    }
}

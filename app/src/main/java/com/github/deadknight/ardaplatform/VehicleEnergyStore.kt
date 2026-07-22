package com.github.deadknight.ardaplatform

import android.content.Context
import android.os.Bundle
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Device-protected storage shared by the Energy UI and MapsEnergyProvider in one Android user. */
object VehicleEnergyStore {
    private const val tag = "ArdaMapsEnergy"
    private const val preferencesName = "arda_vehicle_energy"
    private const val keyFuelType = "fuel_type"
    private const val keySelectedVehicle = "selected_vehicle"
    private const val keyVariantJson = "variant_json"
    private const val keyConnectorTypes = "connector_types"

    fun modelFile(context: Context): File = File(energyDirectory(context), "vehicle_energy_model.pb")

    fun forecastFile(context: Context): File = File(energyDirectory(context), "latest_forecast.pb")

    fun loadStatus(context: Context): VehicleEnergyStatus {
        val model = modelFile(context)
        val battery = runCatching {
            if (model.isFile) VehicleEnergyProto.inspectBattery(model.readBytes()) else null
        }.getOrNull()
        val preferences = preferences(context)
        return VehicleEnergyStatus(
            modelBytes = model.takeIf { it.isFile }?.length() ?: 0L,
            forecastBytes = forecastFile(context).takeIf(File::isFile)?.length() ?: 0L,
            batteryLevelWh = battery?.levelWh,
            batteryCapacityWh = battery?.capacityWh,
            batteryPercent = battery?.percent,
            fuelType = ConfiguredFuelType.fromWireName(
                preferences.getString(keyFuelType, null),
            ),
            connectorTypes = preferences.getStringSet(keyConnectorTypes, emptySet()).orEmpty()
                .mapNotNull(ConfiguredEvConnectorType::parseWireName)
                .toSet(),
            selectedVehicle = preferences.getString(keySelectedVehicle, null),
        )
    }

    fun installModel(
        context: Context,
        payload: ByteArray,
        selectedVehicle: String? = null,
        variantJson: String? = null,
    ): VehicleEnergyStatus {
        val normalized = VehicleEnergyProto.normalizeModelPayload(payload)
        val battery = VehicleEnergyProto.inspectBattery(normalized)
        writeAtomically(modelFile(context), normalized)
        preferences(context).edit().apply {
            if (selectedVehicle != null) putString(keySelectedVehicle, selectedVehicle)
            if (variantJson != null) putString(keyVariantJson, variantJson)
        }.apply()
        notifyModelChanged(context)
        Log.i(
            tag,
            "MODEL_INSTALLED bytes=${normalized.size} batteryLevelWh=${battery.levelWh} " +
                "batteryCapacityWh=${battery.capacityWh}",
        )
        return loadStatus(context)
    }

    fun setBatteryPercent(context: Context, percent: Int): VehicleEnergyStatus {
        val target = modelFile(context)
        require(target.isFile) { "Vehicle energy model is not installed" }
        val (patched, battery) = VehicleEnergyProto.setBatteryPercent(target.readBytes(), percent)
        writeAtomically(target, patched)
        notifyModelChanged(context)
        Log.i(
            tag,
            "BATTERY_PERCENT_SET requested=$percent levelWh=${battery.levelWh} " +
                "capacityWh=${battery.capacityWh}",
        )
        return loadStatus(context)
    }

    fun setFuelType(context: Context, fuelType: ConfiguredFuelType): VehicleEnergyStatus {
        preferences(context).edit().putString(keyFuelType, fuelType.wireName).apply()
        context.contentResolver.notifyChange(VehicleEnergyContract.rootUri, null)
        Log.i(tag, "FUEL_TYPE_SELECTION=${fuelType.wireName}")
        return loadStatus(context)
    }

    fun setConnectorTypes(
        context: Context,
        connectorTypes: Set<ConfiguredEvConnectorType>,
    ): VehicleEnergyStatus {
        preferences(context).edit()
            .putStringSet(
                keyConnectorTypes,
                connectorTypes.mapTo(linkedSetOf()) { it.wireName },
            )
            .apply()
        context.contentResolver.notifyChange(VehicleEnergyContract.rootUri, null)
        Log.i(
            tag,
            "EV_CONNECTOR_SELECTION=${connectorTypes.joinToString { it.wireName }}",
        )
        return loadStatus(context)
    }

    fun clearModel(context: Context): VehicleEnergyStatus {
        val target = modelFile(context)
        if (target.exists() && !target.delete()) {
            throw IOException("Could not delete ${target.path}")
        }
        preferences(context).edit()
            .remove(keySelectedVehicle)
            .remove(keyVariantJson)
            .apply()
        notifyModelChanged(context)
        Log.i(tag, "MODEL_CLEARED")
        return loadStatus(context)
    }

    fun saveForecast(context: Context, bytes: ByteArray) {
        writeAtomically(forecastFile(context), bytes)
    }

    fun selectedVariantJson(context: Context): String? =
        preferences(context).getString(keyVariantJson, null)

    fun statusBundle(context: Context, notifySent: Boolean = false): Bundle =
        loadStatus(context).toBundle(notifySent)

    fun notifyModelChanged(context: Context) {
        context.contentResolver.notifyChange(VehicleEnergyContract.modelUri, null)
        Log.i(tag, "MODEL_NOTIFY bytes=${modelFile(context).takeIf(File::isFile)?.length() ?: 0L}")
    }

    fun variantJson(
        deviceId: String,
        makerId: Int,
        maker: String,
        modelId: Int,
        model: String,
        variantId: Int,
        variant: String,
        maximumRegenWh: Float,
        dragCoefficient: Float,
        linearValue: Float,
        auxiliaryHeating: Map<Float, Int>,
    ): String = JSONObject().apply {
        put("deviceId", deviceId)
        put("makerId", makerId)
        put("maker", maker)
        put("modelId", modelId)
        put("model", model)
        put("nameId", variantId)
        put("name", variant)
        put("mrwh", maximumRegenWh.toDouble())
        put("cd", dragCoefficient.toDouble())
        put("l", linearValue.toDouble())
        put("aH", JSONObject().apply {
            auxiliaryHeating.toSortedMap().forEach { (temperature, watts) ->
                put(temperature.toString(), watts)
            }
        })
    }.toString()

    private fun VehicleEnergyStatus.toBundle(notifySent: Boolean): Bundle = Bundle().apply {
        putLong(VehicleEnergyContract.resultModelBytes, modelBytes)
        putLong(VehicleEnergyContract.resultForecastBytes, forecastBytes)
        batteryLevelWh?.let { putLong(VehicleEnergyContract.resultBatteryLevelWh, it) }
        batteryCapacityWh?.let { putLong(VehicleEnergyContract.resultBatteryCapacityWh, it) }
        batteryPercent?.let { putInt(VehicleEnergyContract.resultBatteryPercent, it) }
        putString(VehicleEnergyContract.resultFuelType, fuelType.wireName)
        putStringArrayList(
            VehicleEnergyContract.resultConnectorTypes,
            ArrayList(connectorTypes.map { it.wireName }),
        )
        putString(
            VehicleEnergyContract.resultConnectorTypesCsv,
            connectorTypes.sortedBy { it.vhalValue }.joinToString(",") { it.wireName },
        )
        putString(VehicleEnergyContract.resultSelectedVehicle, selectedVehicle)
        putBoolean(VehicleEnergyContract.resultNotifySent, notifySent)
    }

    private fun energyDirectory(context: Context): File =
        File(context.filesDir, "energy").also { directory ->
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("Could not create ${directory.path}")
            }
        }

    private fun preferences(context: Context) =
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    private fun writeAtomically(target: File, payload: ByteArray) {
        require(payload.size <= 4 * 1024 * 1024) { "Payload is too large" }
        val parent = requireNotNull(target.parentFile)
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Could not create ${parent.path}")
        }
        val temporary = File(parent, "${target.name}.tmp")
        FileOutputStream(temporary, false).use { output ->
            output.write(payload)
            output.flush()
            output.fd.sync()
        }
        if (target.exists() && !target.delete()) {
            temporary.delete()
            throw IOException("Could not replace ${target.path}")
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IOException("Could not rename ${temporary.path} to ${target.path}")
        }
    }
}

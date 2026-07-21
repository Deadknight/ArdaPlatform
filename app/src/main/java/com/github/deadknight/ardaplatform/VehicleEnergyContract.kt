package com.github.deadknight.ardaplatform

import android.net.Uri

/** Public contract for the Google Maps OEM vehicle-energy provider and Arda admin bridge. */
object VehicleEnergyContract {
    const val authority = "google_maps_energy"

    val rootUri: Uri = Uri.parse("content://$authority")
    val modelUri: Uri = Uri.parse("content://$authority/model")
    val forecastUri: Uri = Uri.parse("content://$authority/forecast")

    fun rootUriForUser(userId: Int): Uri {
        require(userId >= 0) { "User ID must not be negative" }
        return rootUri.buildUpon()
            .encodedAuthority("$userId@$authority")
            .build()
    }

    const val methodStatus = "status"
    const val methodNotifyModel = "notify_model"
    const val methodSetBatteryPercent = "set_battery_percent"
    const val methodSetFuelType = "set_fuel_type"
    const val methodSetConnectorTypes = "set_connector_types"
    const val methodInstallInnerHex = "install_inner_hex"
    const val methodInstallInnerBytes = "install_inner_bytes"
    const val methodClearModel = "clear_model"

    const val extraPercent = "percent"
    const val extraFuelType = "fuel_type"
    const val extraConnectorTypes = "connector_types"
    const val extraHex = "hex"
    const val extraModelBytes = "model_bytes_payload"
    const val extraError = "error"

    const val resultModelBytes = "model_bytes"
    const val resultForecastBytes = "forecast_bytes"
    const val resultBatteryLevelWh = "battery_level_wh"
    const val resultBatteryCapacityWh = "battery_capacity_wh"
    const val resultBatteryPercent = "battery_percent"
    const val resultFuelType = "fuel_type"
    const val resultConnectorTypes = "connector_types"
    const val resultSelectedVehicle = "selected_vehicle"
    const val resultNotifySent = "notify_sent"
}

enum class ConfiguredFuelType(
    val wireName: String,
    val displayName: String,
    val vhalValue: Int,
) {
    Unknown("UNKNOWN", "Unknown", 0),
    Unleaded("UNLEADED", "Unleaded gasoline", 1),
    Leaded("LEADED", "Leaded gasoline", 2),
    Diesel1("DIESEL_1", "Diesel #1", 3),
    Diesel2("DIESEL_2", "Diesel #2", 4),
    Biodiesel("BIODIESEL", "Biodiesel", 5),
    E85("E85", "E85", 6),
    Lpg("LPG", "LPG", 7),
    Cng("CNG", "CNG", 8),
    Lng("LNG", "LNG", 9),
    Electric("ELECTRIC", "Electric", 10),
    Hydrogen("HYDROGEN", "Hydrogen fuel cell", 11),
    Other("OTHER", "Other", 12);

    val enablesEnergyModel: Boolean
        get() = this == Electric

    companion object {
        fun parseWireName(value: String?): ConfiguredFuelType? =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) }

        fun fromWireName(value: String?): ConfiguredFuelType =
            parseWireName(value) ?: Unleaded
    }
}

enum class ConfiguredEvConnectorType(
    val wireName: String,
    val displayName: String,
    val vhalValue: Int,
) {
    Unknown("UNKNOWN", "Unknown", 0),
    Type1Ac("IEC_TYPE_1_AC", "Type 1 AC (J1772)", 1),
    Type2Ac("IEC_TYPE_2_AC", "Type 2 AC (Mennekes)", 2),
    Type3Ac("IEC_TYPE_3_AC", "Type 3 AC (Scame)", 3),
    Chademo("IEC_TYPE_4_DC", "CHAdeMO", 4),
    Ccs1Dc("IEC_TYPE_1_CCS_DC", "CCS1 DC (Combo 1)", 5),
    Ccs2Dc("IEC_TYPE_2_CCS_DC", "CCS2 DC (Combo 2)", 6),
    TeslaRoadster("TESLA_ROADSTER", "Tesla Roadster", 7),
    NacsAc("SAE_J3400_AC", "NACS / SAE J3400 AC", 8),
    NacsDc("SAE_J3400_DC", "NACS / SAE J3400 DC", 9),
    GbtAc("GBT_AC", "GB/T AC", 10),
    GbtDc("GBT_DC", "GB/T DC", 11),
    Other("OTHER", "Other", 101);

    companion object {
        val selectableEntries: List<ConfiguredEvConnectorType> = entries.filterNot {
            it == Unknown
        }

        fun parseWireName(value: String?): ConfiguredEvConnectorType? =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) }
    }
}

data class VehicleEnergyStatus(
    val modelBytes: Long,
    val forecastBytes: Long,
    val batteryLevelWh: Long?,
    val batteryCapacityWh: Long?,
    val batteryPercent: Int?,
    val fuelType: ConfiguredFuelType,
    val connectorTypes: Set<ConfiguredEvConnectorType>,
    val selectedVehicle: String?,
)

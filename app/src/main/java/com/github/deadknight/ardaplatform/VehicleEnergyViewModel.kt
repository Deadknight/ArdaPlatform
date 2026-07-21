package com.github.deadknight.ardaplatform

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Compose-facing state holder adapted from the supplied EvViewModel flow. */
class VehicleEnergyViewModel(
    context: Context,
    private val api: VehicleEnergyApi = VehicleEnergyApi(),
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var fuelType by mutableStateOf(ConfiguredFuelType.Unleaded)
        private set
    var status by mutableStateOf(VehicleEnergyStore.loadStatus(appContext))
        private set
    var selectedConnectorTypes by mutableStateOf(status.connectorTypes)
        private set
    var loading by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    var makers by mutableStateOf<List<String>>(emptyList())
        private set
    var models by mutableStateOf<List<String>>(emptyList())
        private set
    var variants by mutableStateOf<List<String>>(emptyList())
        private set

    var selectedMakerIndex by mutableStateOf(-1)
        private set
    var selectedModelIndex by mutableStateOf(-1)
        private set
    var selectedVariantIndex by mutableStateOf(-1)
        private set
    var variantDataLoaded by mutableStateOf(false)
        private set

    val selectedMaker: String?
        get() = makers.getOrNull(selectedMakerIndex)
    val selectedModel: String?
        get() = models.getOrNull(selectedModelIndex)
    val selectedVariant: String?
        get() = variants.getOrNull(selectedVariantIndex)

    var maximumRegenWhText by mutableStateOf("0")
    var dragCoefficientText by mutableStateOf("0")
    var linearValue by mutableStateOf(2.0f)
    var batteryPercentText by mutableStateOf(status.batteryPercent?.toString() ?: "")

    val auxiliaryHeating = mutableStateMapOf<Float, String>().apply {
        defaultAuxiliaryHeating.forEach { (temperature, watts) ->
            put(temperature, watts.toString())
        }
    }

    private var initialized = false

    fun initialize() {
        status = VehicleEnergyStore.loadStatus(appContext)
        fuelType = status.fuelType
        batteryPercentText = status.batteryPercent?.toString() ?: ""
        selectedConnectorTypes = status.connectorTypes
        if (initialized) return
        initialized = true
        if (fuelType.enablesEnergyModel) {
            loadMakers(restoreSaved = true)
        }
    }

    fun refreshStatus() {
        status = VehicleEnergyStore.loadStatus(appContext)
        fuelType = status.fuelType
        batteryPercentText = status.batteryPercent?.toString() ?: ""
        selectedConnectorTypes = status.connectorTypes
    }

    fun selectFuelType(value: ConfiguredFuelType) {
        fuelType = value
        status = VehicleEnergyStore.setFuelType(appContext, value)
        message = if (value.enablesEnergyModel) {
            "Electric selected. Vehicle-model, connector and battery controls are enabled."
        } else {
            "${value.displayName} selected. Electric-only controls are hidden; the installed model is preserved."
        }
        error = null
        if (value.enablesEnergyModel && makers.isEmpty()) {
            loadMakers(restoreSaved = true)
        }
    }

    fun toggleConnectorType(value: ConfiguredEvConnectorType) {
        val updated = selectedConnectorTypes.toMutableSet().apply {
            if (!add(value)) remove(value)
        }.toSet()
        selectedConnectorTypes = updated
        status = VehicleEnergyStore.setConnectorTypes(appContext, updated)
        message = if (updated.isEmpty()) {
            "No charging connector selected."
        } else {
            "Charging connectors updated."
        }
        error = null
    }

    fun clearConnectorTypes() {
        selectedConnectorTypes = emptySet()
        status = VehicleEnergyStore.setConnectorTypes(appContext, emptySet())
        message = "Charging connector selection cleared."
        error = null
    }

    fun loadMakers(restoreSaved: Boolean = false) {
        runNetwork("Loading manufacturers") {
            val values = api.getMakers()
            makers = values
            models = emptyList()
            variants = emptyList()
            selectedMakerIndex = -1
            selectedModelIndex = -1
            selectedVariantIndex = -1
            variantDataLoaded = false
            if (restoreSaved) restoreSelection()
        }
    }

    fun selectMaker(index: Int) {
        val maker = makers.getOrNull(index) ?: return
        selectedMakerIndex = index
        selectedModelIndex = -1
        selectedVariantIndex = -1
        variantDataLoaded = false
        models = emptyList()
        variants = emptyList()
        runNetwork("Loading models") {
            models = api.getModels(maker)
        }
    }

    fun selectModel(index: Int) {
        val maker = selectedMaker ?: return
        val model = models.getOrNull(index) ?: return
        selectedModelIndex = index
        selectedVariantIndex = -1
        variantDataLoaded = false
        variants = emptyList()
        runNetwork("Loading variants") {
            variants = api.getVariants(maker, model)
        }
    }

    fun selectVariant(index: Int) {
        val variant = variants.getOrNull(index) ?: return
        selectedVariantIndex = index
        variantDataLoaded = false
        runNetwork("Loading vehicle data") {
            val data = api.getVariantData(variant)
            maximumRegenWhText = data.maximumRegenWh.toString()
            dragCoefficientText = data.dragCoefficient.toString()
            variantDataLoaded = true
        }
    }

    fun applySelectedModel() {
        val maker = selectedMaker
        val model = selectedModel
        val variant = selectedVariant
        if (maker == null || model == null || variant == null) {
            error = "Select manufacturer, model and variant first."
            return
        }
        if (!variantDataLoaded) {
            error = "Wait for the selected variant data to load."
            return
        }
        val maximumRegenWh = maximumRegenWhText.toFloatOrNull()
        val dragCoefficient = dragCoefficientText.toFloatOrNull()
        if (maximumRegenWh == null || dragCoefficient == null) {
            error = "Maximum regen and drag coefficient must be valid numbers."
            return
        }
        val auxiliary = auxiliaryHeating.mapValues { (_, value) ->
            value.toIntOrNull() ?: 0
        }
        val variantJson = VehicleEnergyStore.variantJson(
            deviceId = Settings.Secure.getString(
                appContext.contentResolver,
                Settings.Secure.ANDROID_ID,
            ).orEmpty(),
            makerId = selectedMakerIndex + apiIndexOffset,
            maker = maker,
            modelId = selectedModelIndex + apiIndexOffset,
            model = model,
            variantId = selectedVariantIndex + apiIndexOffset,
            variant = variant,
            maximumRegenWh = maximumRegenWh,
            dragCoefficient = dragCoefficient,
            linearValue = linearValue,
            auxiliaryHeating = auxiliary,
        )

        runNetwork("Downloading vehicle energy model") {
            val bytes = api.getProto(variantJson)
            status = VehicleEnergyStore.installModel(
                appContext,
                payload = bytes,
                selectedVehicle = "$maker $model $variant",
                variantJson = variantJson,
            )
            fuelType = status.fuelType
            selectedConnectorTypes = status.connectorTypes
            batteryPercentText = status.batteryPercent?.toString() ?: ""
            message = "Vehicle energy model installed and Maps notified."
        }
    }

    fun setBatteryPercent() {
        val requested = batteryPercentText.toIntOrNull()
        if (requested == null || requested !in 0..100) {
            error = "Battery percentage must be between 0 and 100."
            return
        }
        runLocal("Updating battery percentage") {
            status = VehicleEnergyStore.setBatteryPercent(appContext, requested)
            batteryPercentText = status.batteryPercent?.toString() ?: requested.toString()
            message = "Battery percentage updated and Maps notified."
        }
    }

    fun clearModel() {
        runLocal("Clearing vehicle energy model") {
            status = VehicleEnergyStore.clearModel(appContext)
            batteryPercentText = ""
            message = "Vehicle energy model cleared."
        }
    }

    fun updateAuxiliaryHeating(temperature: Float, value: String) {
        if (value.isEmpty() || value.toIntOrNull() != null) {
            auxiliaryHeating[temperature] = value
        }
    }

    fun clearNotice() {
        message = null
        error = null
    }

    override fun close() {
        scope.cancel()
    }

    private suspend fun restoreSelection() {
        val saved = VehicleEnergyStore.selectedVariantJson(appContext) ?: return
        val json = runCatching { JSONObject(saved) }.getOrNull() ?: return
        val makerName = json.optString("maker")
        val modelName = json.optString("model")
        val variantName = json.optString("name")
        val makerIndex = makers.indexOf(makerName)
        if (makerIndex < 0) return

        selectedMakerIndex = makerIndex
        models = api.getModels(makerName)
        selectedModelIndex = models.indexOf(modelName)
        if (selectedModelIndex < 0) return

        variants = api.getVariants(makerName, modelName)
        selectedVariantIndex = variants.indexOf(variantName)
        variantDataLoaded = selectedVariantIndex >= 0
        maximumRegenWhText = json.optDouble("mrwh", 0.0).toFloat().toString()
        dragCoefficientText = json.optDouble("cd", 0.0).toFloat().toString()
        linearValue = json.optDouble("l", 2.0).toFloat().coerceIn(0.8f, 5.0f)
        val heating = json.optJSONObject("aH")
        if (heating != null) {
            auxiliaryHeating.keys.toList().forEach { temperature ->
                val key = temperature.toString()
                if (heating.has(key)) {
                    auxiliaryHeating.put(temperature, heating.optInt(key).toString())
                }
            }
        }
    }

    private fun runNetwork(label: String, block: suspend () -> Unit) {
        if (loading) return
        loading = true
        message = label
        error = null
        scope.launch {
            try {
                block()
                if (message == label) message = null
            } catch (throwable: Throwable) {
                error = throwable.message ?: throwable.javaClass.simpleName
                message = null
            } finally {
                loading = false
            }
        }
    }

    private fun runLocal(label: String, block: () -> Unit) {
        if (loading) return
        loading = true
        message = label
        error = null
        scope.launch {
            try {
                block()
                if (message == label) message = null
            } catch (throwable: Throwable) {
                error = throwable.message ?: throwable.javaClass.simpleName
                message = null
            } finally {
                loading = false
            }
        }
    }

    companion object {
        // The supplied companion UI prepends an empty selector entry before API rows,
        // so its server-facing IDs are one-based even though this UI keeps zero-based lists.
        private const val apiIndexOffset = 1

        private val defaultAuxiliaryHeating = linkedMapOf(
            -22.5f to 4851,
            -17.5f to 5421,
            -12.5f to 5460,
            -7.5f to 5065,
            -2.5f to 4403,
            2.5f to 3877,
            7.5f to 3187,
            12.5f to 2427,
            17.5f to 1880,
            22.5f to 1731,
            27.5f to 1730,
            32.5f to 1981,
            37.5f to 2339,
            42.5f to 2920,
            47.5f to 3682,
        )
    }
}

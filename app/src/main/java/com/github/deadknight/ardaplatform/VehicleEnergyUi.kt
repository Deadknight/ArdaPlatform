package com.github.deadknight.ardaplatform

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val energySurface = Color(0xFF121923)
private val energyRaised = Color(0xFF182230)
private val energyPrimary = Color(0xFF65D5C4)
private val energyPrimaryMuted = Color(0xFF173E3A)
private val energyText = Color(0xFFF2F6FA)
private val energySecondary = Color(0xFFAAB7C5)
private val energyDivider = Color(0xFF283544)
private val energyDanger = Color(0xFFFF7C86)

@Composable
fun VehicleEnergyScreen(
    viewModel: VehicleEnergyViewModel,
    modifier: Modifier = Modifier,
) {
    var showAdvanced by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.initialize()
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                EnergyHeader(viewModel)
            }

            item {
                FuelTypeCard(viewModel)
            }

            if (viewModel.fuelType.enablesEnergyModel) {
                item {
                    ChargingConnectorCard(viewModel)
                }
            }

            item {
                VehicleProfileApplyCard(viewModel)
            }

            if (viewModel.fuelType.enablesEnergyModel) {
                item {
                    ModelStatusCard(viewModel)
                }

                item {
                    VehicleSelectionCard(viewModel)
                }

                item {
                    AdvancedModelCard(
                        viewModel = viewModel,
                        expanded = showAdvanced,
                        onExpandedChange = { showAdvanced = it },
                    )
                }

                if (viewModel.status.modelBytes > 0L) {
                    item {
                        BatteryPercentageCard(viewModel)
                    }
                }
            }
        }

        if (viewModel.loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x990B1016)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = energyPrimary)
            }
        }
    }
}

@Composable
private fun EnergyHeader(viewModel: VehicleEnergyViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Vehicle Energy",
            color = energyText,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Select the vehicle fuel type and provide Google Maps with the OEM energy model.",
            color = energySecondary,
            fontSize = 15.sp,
        )

        viewModel.error?.let { value ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                color = energyDanger,
                fontWeight = FontWeight.SemiBold,
            )
        }
        viewModel.message?.let { value ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                color = energyPrimary,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun FuelTypeCard(viewModel: VehicleEnergyViewModel) {
    val fuelTypes = ConfiguredFuelType.entries
    EnergyCard {
        Text(
            text = "Fuel type",
            color = energyText,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Only a pure Electric selection enables the vehicle-model, charging-connector and battery controls below.",
            color = energySecondary,
            fontSize = 14.sp,
        )
        EnergyDropdown(
            label = "Fuel type",
            values = fuelTypes.map { it.displayName },
            selectedIndex = fuelTypes.indexOf(viewModel.fuelType),
            enabled = true,
            onSelected = { index ->
                fuelTypes.getOrNull(index)?.let(viewModel::selectFuelType)
            },
        )
        Text(
            text = "Selected VHAL value: ${viewModel.fuelType.vhalValue} (${viewModel.fuelType.wireName})",
            color = energySecondary,
            fontSize = 13.sp,
        )
        Text(
            text = "The selection is saved immediately. Apply publishes it through ArdaVehicleManager and the authenticated vehicle router.",
            color = energySecondary,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun ChargingConnectorCard(viewModel: VehicleEnergyViewModel) {
    EnergyCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Charging connector types",
                    color = energyText,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Select every AC/DC connector supported by the vehicle.",
                    color = energySecondary,
                    fontSize = 14.sp,
                )
            }
            if (viewModel.selectedConnectorTypes.isNotEmpty()) {
                TextButton(onClick = viewModel::clearConnectorTypes) {
                    Text("Clear")
                }
            }
        }

        EnergyMultiSelectDropdown(
            label = "Charging sockets",
            values = ConfiguredEvConnectorType.selectableEntries,
            selected = viewModel.selectedConnectorTypes,
            onToggle = viewModel::toggleConnectorType,
        )

        val selectedText = viewModel.selectedConnectorTypes
            .sortedBy { it.vhalValue }
            .joinToString { it.displayName }
            .ifEmpty { "No connector selected" }

        Text(
            text = selectedText,
            color = if (viewModel.selectedConnectorTypes.isEmpty()) {
                energySecondary
            } else {
                energyText
            },
            fontSize = 13.sp,
        )
        Text(
            text = "Stored as INFO_EV_CONNECTOR_TYPE-compatible values and applied together with the fuel type.",
            color = energySecondary,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun VehicleProfileApplyCard(viewModel: VehicleEnergyViewModel) {
    val runtime = viewModel.runtimeStatus
    val stateText = when (runtime.state) {
        VehicleProfileRuntimeState.Idle -> "Not applied yet"
        VehicleProfileRuntimeState.Pending -> "Queued"
        VehicleProfileRuntimeState.Applying -> "Applying"
        VehicleProfileRuntimeState.Pass -> "Applied"
        VehicleProfileRuntimeState.Fail -> "Failed"
    }
    val stateColor = when (runtime.state) {
        VehicleProfileRuntimeState.Fail -> energyDanger
        VehicleProfileRuntimeState.Pass -> energyPrimary
        else -> energySecondary
    }

    EnergyCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Live vehicle profile",
                    color = energyText,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Publishes fuel and connector types through the Manager/router/VHAL path, refreshes CarService, restarts Maps and notifies the energy model.",
                    color = energySecondary,
                    fontSize = 14.sp,
                )
            }
            TextButton(onClick = viewModel::refreshRuntimeStatus) {
                Text("Refresh")
            }
        }

        HorizontalDivider(color = energyDivider)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stateText,
                    color = stateColor,
                    fontWeight = FontWeight.Bold,
                )
                runtime.detail?.takeIf(String::isNotBlank)?.let { detail ->
                    Text(
                        text = detail,
                        color = energySecondary,
                        fontSize = 12.sp,
                    )
                }
            }
            Button(
                onClick = viewModel::applyVehicleProfile,
                enabled = !viewModel.loading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = energyPrimary,
                    contentColor = Color(0xFF06201D),
                ),
            ) {
                val buttonText = if (viewModel.loading) {
                    "Applying…"
                } else {
                    "Apply vehicle profile"
                }
                Text(
                    buttonText,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Text(
            text = "The launcher can disappear briefly while CarService restarts. The Manager persists the last applied profile and restores it after reboot.",
            color = energySecondary,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun ModelStatusCard(viewModel: VehicleEnergyViewModel) {
    val status = viewModel.status
    EnergyCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Maps model provider",
                    color = energyText,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = status.selectedVehicle ?: "No vehicle model selected",
                    color = energySecondary,
                )
            }
            TextButton(onClick = viewModel::refreshStatus) {
                Text("Refresh")
            }
        }

        HorizontalDivider(color = energyDivider)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            EnergyMetric("Model", "${status.modelBytes} B", Modifier.weight(1f))
            EnergyMetric("Forecast", "${status.forecastBytes} B", Modifier.weight(1f))
            EnergyMetric(
                "Battery",
                status.batteryPercent?.let { "$it%" } ?: "—",
                Modifier.weight(1f),
            )
        }

        if (status.batteryCapacityWh != null && status.batteryLevelWh != null) {
            Text(
                text = "${status.batteryLevelWh} Wh / ${status.batteryCapacityWh} Wh",
                color = energySecondary,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun VehicleSelectionCard(viewModel: VehicleEnergyViewModel) {
    EnergyCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Vehicle model",
                    color = energyText,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Catalog data is loaded from aap.opencharge.top.",
                    color = energySecondary,
                    fontSize = 14.sp,
                )
            }
            TextButton(onClick = { viewModel.loadMakers() }) {
                Text("Reload")
            }
        }

        EnergyDropdown(
            label = "Manufacturer",
            values = viewModel.makers,
            selectedIndex = viewModel.selectedMakerIndex,
            enabled = viewModel.makers.isNotEmpty(),
            onSelected = viewModel::selectMaker,
        )
        EnergyDropdown(
            label = "Model",
            values = viewModel.models,
            selectedIndex = viewModel.selectedModelIndex,
            enabled = viewModel.selectedMakerIndex >= 0 && viewModel.models.isNotEmpty(),
            onSelected = viewModel::selectModel,
        )
        EnergyDropdown(
            label = "Variant",
            values = viewModel.variants,
            selectedIndex = viewModel.selectedVariantIndex,
            enabled = viewModel.selectedModelIndex >= 0 && viewModel.variants.isNotEmpty(),
            onSelected = viewModel::selectVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                onClick = viewModel::applySelectedModel,
                enabled = viewModel.selectedVariantIndex >= 0 &&
                    viewModel.variantDataLoaded &&
                    !viewModel.loading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = energyPrimary,
                    contentColor = Color(0xFF06201D),
                ),
            ) {
                Text("Download & apply", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AdvancedModelCard(
    viewModel: VehicleEnergyViewModel,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    EnergyCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpandedChange(!expanded) },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Advanced model values",
                    color = energyText,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Variant defaults are filled automatically and can be adjusted before download.",
                    color = energySecondary,
                    fontSize = 14.sp,
                )
            }
            Text(
                text = if (expanded) "Hide" else "Show",
                color = energyPrimary,
                fontWeight = FontWeight.Bold,
            )
        }

        if (expanded) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EnergyNumberField(
                    value = viewModel.maximumRegenWhText,
                    onValueChange = { value ->
                        if (value.isEmpty() || value.toFloatOrNull() != null) {
                            viewModel.maximumRegenWhText = value
                        }
                    },
                    label = "Maximum regen Wh",
                    modifier = Modifier.weight(1f),
                )
                EnergyNumberField(
                    value = viewModel.dragCoefficientText,
                    onValueChange = { value ->
                        if (value.isEmpty() || value.toFloatOrNull() != null) {
                            viewModel.dragCoefficientText = value
                        }
                    },
                    label = "Drag coefficient",
                    modifier = Modifier.weight(1f),
                )
            }

            Text(
                text = "L value: ${"%.1f".format(viewModel.linearValue)}",
                color = energyText,
                fontWeight = FontWeight.SemiBold,
            )
            Slider(
                value = viewModel.linearValue,
                onValueChange = { viewModel.linearValue = it },
                valueRange = 0.8f..5.0f,
                steps = 41,
            )

            Text(
                text = "Auxiliary heating drain",
                color = energyText,
                fontWeight = FontWeight.Bold,
            )
            viewModel.auxiliaryHeating.entries.toList().sortedBy { it.key }.chunked(3).forEach { rowValues ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowValues.forEach { (temperature, value) ->
                        EnergyNumberField(
                            value = value,
                            onValueChange = {
                                viewModel.updateAuxiliaryHeating(temperature, it)
                            },
                            label = "${temperature}°C W",
                            modifier = Modifier.weight(1f),
                            integerOnly = true,
                        )
                    }
                    repeat(3 - rowValues.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun BatteryPercentageCard(viewModel: VehicleEnergyViewModel) {
    EnergyCard {
        Text(
            text = "Battery percentage API",
            color = energyText,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Updates only the model battery-level field. Capacity and vehicle physics tables stay unchanged.",
            color = energySecondary,
            fontSize = 14.sp,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = viewModel.batteryPercentText,
                onValueChange = { value ->
                    if (value.isEmpty() || value.toIntOrNull() != null) {
                        viewModel.batteryPercentText = value.take(3)
                    }
                },
                label = { Text("Battery %") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = viewModel::setBatteryPercent,
                colors = ButtonDefaults.buttonColors(
                    containerColor = energyPrimary,
                    contentColor = Color(0xFF06201D),
                ),
            ) {
                Text("Set & notify", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = viewModel::clearModel) {
                Text("Clear model")
            }
        }
        Text(
            text = "Provider API: content://google_maps_energy · method set_battery_percent",
            color = energySecondary,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun EnergyDropdown(
    label: String,
    values: List<String>,
    selectedIndex: Int,
    enabled: Boolean,
    onSelected: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = values.getOrNull(selectedIndex) ?: "Select $label"

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            color = energySecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Box {
            Card(
                colors = CardDefaults.cardColors(containerColor = energySurface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { expanded = true },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = selected,
                        color = if (enabled) energyText else energySecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("▼", color = energyPrimary)
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(energyRaised),
            ) {
                values.forEachIndexed { index, value ->
                    DropdownMenuItem(
                        text = { Text(value, color = energyText) },
                        onClick = {
                            expanded = false
                            onSelected(index)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EnergyMultiSelectDropdown(
    label: String,
    values: List<ConfiguredEvConnectorType>,
    selected: Set<ConfiguredEvConnectorType>,
    onToggle: (ConfiguredEvConnectorType) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val summary = when (selected.size) {
        0 -> "Select charging sockets"
        1 -> selected.first().displayName
        else -> "${selected.size} connector types selected"
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            color = energySecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Box {
            Card(
                colors = CardDefaults.cardColors(containerColor = energySurface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = summary,
                        color = energyText,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("▼", color = energyPrimary)
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(energyRaised),
            ) {
                values.forEach { value ->
                    val checked = value in selected
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (checked) "✓  ${value.displayName}" else "    ${value.displayName}",
                                color = energyText,
                            )
                        },
                        onClick = { onToggle(value) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EnergyNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier,
    integerOnly: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (integerOnly) KeyboardType.Number else KeyboardType.Decimal,
        ),
        singleLine = true,
        modifier = modifier,
    )
}

@Composable
private fun EnergyMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = label, color = energySecondary, fontSize = 12.sp)
        Text(text = value, color = energyText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EnergyCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = energyRaised),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

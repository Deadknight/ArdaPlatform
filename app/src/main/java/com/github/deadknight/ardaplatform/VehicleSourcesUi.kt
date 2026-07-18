package com.github.deadknight.ardaplatform

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun VehicleSourcesScreen(
    state: VehicleSourcesState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val operationFailed = stringResource(R.string.vehicle_sources_operation_failed)
    val rescannedMessage = stringResource(R.string.vehicle_sources_rescanned)
    val accessUpdatedMessage = stringResource(R.string.vehicle_sources_access_updated)
    val sourceEnabledMessage = stringResource(R.string.vehicle_sources_source_enabled)
    val sourceDisabledMessage = stringResource(R.string.vehicle_sources_source_disabled)
    val accessRevokedMessage = stringResource(R.string.vehicle_sources_access_revoked)

    fun mutate(success: String, block: () -> Unit) {
        scope.launch {
            runCatching(block)
                .onSuccess {
                    onRefresh()
                    snackbar.showSnackbar(success)
                }
                .onFailure { error ->
                    snackbar.showSnackbar(error.message ?: operationFailed)
                }
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.vehicle_sources_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.vehicle_sources_description),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = {
                    mutate(rescannedMessage) {
                        VehicleSourcesRepository.rescan(context)
                    }
                },
            ) {
                Text(stringResource(R.string.action_rescan))
            }
        }

        state.error?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = 32.dp,
                end = 32.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (state.sources.isEmpty() && state.error == null) {
                item {
                    Text(
                        text = stringResource(R.string.vehicle_sources_none_discovered),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(
                count = state.sources.size,
                key = { state.sources[it].componentName },
            ) { index ->
                val source = state.sources[index]
                VehicleSourceCard(
                    source = source,
                    onApprove = { publish, commands ->
                        mutate(accessUpdatedMessage) {
                            VehicleSourcesRepository.approve(
                                context,
                                source,
                                publish,
                                commands,
                            )
                        }
                    },
                    onEnabled = { enabled ->
                        mutate(
                            if (enabled) sourceEnabledMessage else sourceDisabledMessage,
                        ) {
                            VehicleSourcesRepository.setEnabled(context, source, enabled)
                        }
                    },
                    onRevoke = {
                        mutate(accessRevokedMessage) {
                            VehicleSourcesRepository.revoke(context, source)
                        }
                    },
                )
            }
        }
        SnackbarHost(hostState = snackbar)
    }
}

@Composable
private fun VehicleSourceCard(
    source: VehicleSourceSnapshot,
    onApprove: (IntArray, IntArray) -> Unit,
    onEnabled: (Boolean) -> Unit,
    onRevoke: () -> Unit,
) {
    val publishSelection = remember(
        source.componentName,
        source.grantedPublishPropertyIds.contentHashCode(),
        source.requestedPublishPropertyIds.contentHashCode(),
    ) {
        mutableStateMapOf<Int, Boolean>().apply {
            source.requestedPublishPropertyIds.forEach { property ->
                this[property] = source.grantedPublishPropertyIds.contains(property) ||
                    source.state == "PENDING_APPROVAL"
            }
        }
    }
    val commandSelection = remember(
        source.componentName,
        source.grantedCommandPropertyIds.contentHashCode(),
        source.requestedCommandPropertyIds.contentHashCode(),
    ) {
        mutableStateMapOf<Int, Boolean>().apply {
            source.requestedCommandPropertyIds.forEach { property ->
                this[property] = source.grantedCommandPropertyIds.contains(property)
            }
        }
    }
    var expanded by remember(source.componentName) {
        mutableStateOf(source.state != "ACTIVE")
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(source.displayName, fontWeight = FontWeight.Bold)
                    Text(
                        source.packageName,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                SourceStateBadge(source.state)
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = sourceIdentityText(source),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = sourceRuntimeText(source),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            if (source.lastError.isNotBlank()) {
                Text(
                    source.lastError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { expanded = !expanded }) {
                Text(
                    stringResource(
                        if (expanded) R.string.action_hide_access
                        else R.string.action_review_access,
                    ),
                )
            }

            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text(
                    stringResource(R.string.vehicle_sources_publish_title),
                    fontWeight = FontWeight.SemiBold,
                )
                source.requestedPublishPropertyIds.forEach { property ->
                    PropertyPermissionRow(
                        propertyId = property,
                        checked = publishSelection[property] == true,
                        warning = false,
                        onCheckedChange = { publishSelection[property] = it },
                    )
                }
                if (source.requestedCommandPropertyIds.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.vehicle_sources_command_title),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        stringResource(R.string.vehicle_sources_command_description),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    source.requestedCommandPropertyIds.forEach { property ->
                        PropertyPermissionRow(
                            propertyId = property,
                            checked = commandSelection[property] == true,
                            warning = true,
                            onCheckedChange = { commandSelection[property] = it },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = {
                            onApprove(
                                publishSelection
                                    .filterValues { it }
                                    .keys
                                    .sorted()
                                    .toIntArray(),
                                commandSelection
                                    .filterValues { it }
                                    .keys
                                    .sorted()
                                    .toIntArray(),
                            )
                        },
                    ) {
                        Text(
                            stringResource(
                                if (source.state == "PENDING_APPROVAL") {
                                    R.string.action_approve_selected
                                } else {
                                    R.string.action_save_access
                                },
                            ),
                        )
                    }
                    if (source.sourceId > 0) {
                        OutlinedButton(onClick = onRevoke) {
                            Text(stringResource(R.string.action_revoke))
                        }
                    }
                }
            }

            if (source.sourceId > 0 && source.state != "MISSING") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(
                            if (source.enabled) R.string.state_enabled
                            else R.string.state_disabled,
                        ),
                    )
                    Switch(
                        checked = source.enabled,
                        onCheckedChange = onEnabled,
                    )
                }
            }
        }
    }
}

@Composable
private fun sourceIdentityText(source: VehicleSourceSnapshot): String {
    val base = if (source.sourceId > 0) {
        stringResource(
            R.string.vehicle_sources_identity_with_source,
            source.signerSummary,
            source.sourceId,
            source.effectivePriority,
        )
    } else {
        stringResource(
            R.string.vehicle_sources_identity_without_source,
            source.signerSummary,
            source.effectivePriority,
        )
    }
    return base
}

@Composable
private fun sourceRuntimeText(source: VehicleSourceSnapshot): String {
    val adapter = stringResource(
        if (source.ready) R.string.state_ready_lower
        else R.string.state_not_ready_lower,
    )
    val router = stringResource(
        if (source.routerRegistered) R.string.state_registered_lower
        else R.string.state_offline_lower,
    )
    val base = stringResource(R.string.vehicle_sources_runtime, adapter, router)
    return if (source.pendingSignals > 0) {
        base + stringResource(
            R.string.vehicle_sources_queued_suffix,
            source.pendingSignals,
        )
    } else {
        base
    }
}

@Composable
private fun PropertyPermissionRow(
    propertyId: Int,
    checked: Boolean,
    warning: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                vehiclePropertyName(propertyId),
                color = if (warning) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                propertyId.toString(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SourceStateBadge(state: String) {
    val color = when (state) {
        "ACTIVE" -> Color(0xFF71D99A)
        "PENDING_APPROVAL", "APPROVED" -> Color(0xFFFFC66B)
        "DISABLED", "MISSING" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }
    Text(
        text = sourceStateLabel(state),
        color = color,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun sourceStateLabel(state: String): String = stringResource(
    when (state) {
        "ACTIVE" -> R.string.vehicle_source_state_active
        "PENDING_APPROVAL" -> R.string.vehicle_source_state_pending_approval
        "APPROVED" -> R.string.vehicle_source_state_approved
        "DISABLED" -> R.string.vehicle_source_state_disabled
        "MISSING" -> R.string.vehicle_source_state_missing
        "SIGNATURE_CHANGED" -> R.string.vehicle_source_state_signature_changed
        "REQUEST_CONTRACT_CHANGED" -> R.string.vehicle_source_state_request_changed
        else -> R.string.vehicle_source_state_error
    },
)

@Composable
private fun vehiclePropertyName(propertyId: Int): String {
    @StringRes val resource = when (propertyId) {
        291504647 -> R.string.vehicle_property_speed
        291504644 -> R.string.vehicle_property_odometer
        291504903 -> R.string.vehicle_property_fuel_level
        287310853 -> R.string.vehicle_property_fuel_level_low
        287310850 -> R.string.vehicle_property_parking_brake
        289408000 -> R.string.vehicle_property_gear_selection
        287310855 -> R.string.vehicle_property_night_mode
        358614274 -> R.string.vehicle_property_hvac_current
        358614275 -> R.string.vehicle_property_hvac_target
        289410874 -> R.string.vehicle_property_toll_card_status
        else -> 0
    }
    return if (resource != 0) {
        stringResource(resource)
    } else {
        stringResource(R.string.vehicle_property_unknown, propertyId)
    }
}

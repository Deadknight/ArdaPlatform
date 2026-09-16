package com.github.deadknight.ardaplatform

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val background = Color(0xFF0B1016)
private val surface = Color(0xFF121923)
private val surfaceRaised = Color(0xFF182230)
private val primary = Color(0xFF65D5C4)
private val primaryMuted = Color(0xFF173E3A)
private val success = Color(0xFF71D99A)
private val warning = Color(0xFFFFC66B)
private val danger = Color(0xFFFF7C86)
private val textPrimary = Color(0xFFF2F6FA)
private val textSecondary = Color(0xFFAAB7C5)
private val divider = Color(0xFF283544)

private val ardaColors = darkColorScheme(
    primary = primary,
    onPrimary = Color(0xFF06201D),
    primaryContainer = primaryMuted,
    onPrimaryContainer = Color(0xFFB9FFF3),
    background = background,
    onBackground = textPrimary,
    surface = surface,
    onSurface = textPrimary,
    surfaceVariant = surfaceRaised,
    onSurfaceVariant = textSecondary,
    outline = divider,
    error = danger,
)

@Composable
private fun ArdaSettingsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 20.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

private enum class PlatformTab(
    @StringRes val titleRes: Int,
    @StringRes val glyphRes: Int,
) {
    Status(R.string.tab_status, R.string.tab_status_glyph),
    Settings(R.string.tab_configuration, R.string.tab_configuration_glyph),
    VehicleSources(R.string.tab_vehicle_sources, R.string.tab_vehicle_sources_glyph),
    Energy(R.string.tab_vehicle_energy, R.string.tab_vehicle_energy_glyph),
}

@Composable
fun ArdaPlatformApp() {
    val context = LocalContext.current
    var selectedTab by rememberSaveable { mutableStateOf(PlatformTab.Status) }
    var snapshot by remember {
        mutableStateOf(ArdaPlatformRepository.loadSnapshot(context))
    }
    var vehicleSources by remember {
        mutableStateOf(VehicleSourcesRepository.load(context))
    }
    val vehicleEnergyViewModel = remember(context.applicationContext) {
        VehicleEnergyViewModel(context.applicationContext)
    }

    DisposableEffect(vehicleEnergyViewModel) {
        onDispose { vehicleEnergyViewModel.close() }
    }

    LaunchedEffect(Unit) {
        ArdaPlatformRepository.ensureCoreStarted(context)
        while (isActive) {
            snapshot = ArdaPlatformRepository.loadSnapshot(context)
            vehicleSources = VehicleSourcesRepository.load(context)
            delay(1_000)
        }
    }

    MaterialTheme(colorScheme = ardaColors) {
        // Target-SDK edge-to-edge rendering can place the activity behind AAOS
        // bars. Consume the actual system-provided safe area on every edge.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(background)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                when (selectedTab) {
                    PlatformTab.Status -> StatusScreen(
                        snapshot = snapshot,
                        modifier = Modifier.fillMaxSize(),
                    )

                    PlatformTab.Settings -> ConfigScreen(
                        snapshot = snapshot,
                        onSnapshotChanged = { snapshot = it },
                        modifier = Modifier.fillMaxSize(),
                    )

                    PlatformTab.VehicleSources -> VehicleSourcesScreen(
                        state = vehicleSources,
                        onRefresh = { vehicleSources = VehicleSourcesRepository.load(context) },
                        modifier = Modifier.fillMaxSize(),
                    )

                    PlatformTab.Energy -> VehicleEnergyScreen(
                        viewModel = vehicleEnergyViewModel,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            PlatformBottomBar(
                selectedTab = selectedTab,
                onSelected = { selectedTab = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp, vertical = 8.dp),
            )

            // AAOS draws the climate controls over this reserved area.
            /*Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(climatePanelClearance),
            )*/
        }
    }
}

@Composable
private fun PlatformBottomBar(
    selectedTab: PlatformTab,
    onSelected: (PlatformTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color(0xFF0E151E),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 0.dp,
        modifier = modifier.height(72.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlatformTab.entries.forEach { tab ->
                val selected = selectedTab == tab

                Surface(
                    color = if (selected) primaryMuted else Color.Transparent,
                    contentColor = if (selected) primary else textSecondary,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable { onSelected(tab) },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            color = if (selected) primary else divider,
                            contentColor = if (selected) Color(0xFF06201D) else textPrimary,
                            shape = CircleShape,
                            modifier = Modifier.size(26.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(tab.glyphRes),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                )
                            }
                        }

                        Spacer(Modifier.width(8.dp))

                        Text(
                            text = stringResource(tab.titleRes),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) primary else textPrimary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusScreen(
    snapshot: ArdaPlatformSnapshot,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        val horizontalPadding = if (wide) 48.dp else 24.dp
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = 28.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                StatusHero(snapshot)
            }
            item {
                StatusCards(snapshot = snapshot, wide = wide)
            }
            item {
                ConnectionDetails(snapshot)
            }
            item {
                RecentActivity(snapshot)
            }
        }
    }
}

@Composable
private fun StatusHero(snapshot: ArdaPlatformSnapshot) {
    val ready = snapshot.serverRunning && snapshot.hotspotReady
    val stateColor = when {
        snapshot.lastError != null -> danger
        ready -> success
        else -> warning
    }
    val stateTitle = when {
        snapshot.lastError != null -> stringResource(R.string.status_attention_required)
        ready -> stringResource(R.string.status_connectivity_core_ready)
        snapshot.serverRunning -> stringResource(R.string.status_preparing_local_network)
        else -> stringResource(R.string.status_starting_platform)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = surfaceRaised),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(stateColor),
            )
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stateTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = stateColor,
                )
            }
            Surface(
                color = Color(0xFF0D141C),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.version_format, BuildConfig.VERSION_NAME),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    color = textSecondary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun StatusCards(
    snapshot: ArdaPlatformSnapshot,
    wide: Boolean,
) {
    val cards = listOf(
        Triple(
            stringResource(R.string.card_platform_core),
            if (snapshot.serverRunning) stringResource(R.string.state_online) else stringResource(R.string.state_offline),
            stringResource(R.string.card_platform_core_detail) to snapshot.serverRunning,
        ),
        Triple(
            stringResource(R.string.card_local_hotspot),
            if (snapshot.hotspotReady) stringResource(R.string.state_ready) else stringResource(R.string.state_preparing),
            (snapshot.hotspotSsid ?: stringResource(R.string.state_waiting_for_ssid)) to snapshot.hotspotReady,
        ),
        Triple(
            stringResource(R.string.card_phone),
            if (snapshot.peerConnected) stringResource(R.string.state_connected) else stringResource(R.string.state_waiting),
            (snapshot.peerAddress ?: stringResource(R.string.card_phone_detail)) to snapshot.peerConnected,
        ),
        Triple(
            stringResource(R.string.card_local_bridge),
            if (snapshot.bridgeConnected) stringResource(R.string.state_active) else stringResource(R.string.state_idle),
            "${snapshot.config.host}:${snapshot.config.port}" to snapshot.bridgeConnected,
        ),
    )

    if (wide) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            cards.forEach { (title, value, detailAndActive) ->
                StatusCard(
                    title = title,
                    value = value,
                    detail = detailAndActive.first,
                    active = detailAndActive.second,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            cards.chunked(2).forEach { rowCards ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    rowCards.forEach { (title, value, detailAndActive) ->
                        StatusCard(
                            title = title,
                            value = value,
                            detail = detailAndActive.first,
                            active = detailAndActive.second,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    value: String,
    detail: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = surface),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.heightIn(min = 148.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (active) success else textSecondary),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    color = textSecondary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = detail,
                color = textSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ConnectionDetails(snapshot: ArdaPlatformSnapshot) {
    Card(
        colors = CardDefaults.cardColors(containerColor = surface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(24.dp)) {
            Text(
                text = stringResource(R.string.connection_details_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(18.dp))
            DetailGrid(
                leftLabel = stringResource(R.string.label_hotspot_ip),
                leftValue = snapshot.hotspotIp ?: stringResource(R.string.value_not_reported_yet),
                rightLabel = stringResource(R.string.label_security),
                rightValue = snapshot.hotspotSecurity?.uppercase() ?: "—",
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = divider,
            )
            DetailGrid(
                leftLabel = stringResource(R.string.label_bssid),
                leftValue = snapshot.hotspotBssid ?: stringResource(R.string.value_not_reported),
                rightLabel = stringResource(R.string.label_runtime_config),
                rightValue = snapshot.runtimeConfigPath,
            )
        }
    }
}

@Composable
private fun DetailGrid(
    leftLabel: String,
    leftValue: String,
    rightLabel: String,
    rightValue: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        DetailItem(leftLabel, leftValue, Modifier.weight(1f))
        DetailItem(rightLabel, rightValue, Modifier.weight(1f))
    }
}

@Composable
private fun DetailItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = label,
            color = textSecondary,
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RecentActivity(snapshot: ArdaPlatformSnapshot) {
    Card(
        colors = CardDefaults.cardColors(containerColor = surface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(24.dp)) {
            Text(
                text = stringResource(R.string.recent_status_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(14.dp))
            val message = snapshot.lastError ?: snapshot.lastEvent
                ?: stringResource(R.string.waiting_for_platform_events)
            Text(
                text = message,
                color = if (snapshot.lastError != null) danger else textSecondary,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ArdaColorPickerField(
    label: String,
    value: String,
    enabled: Boolean,
    allowDefault: Boolean,
    isValid: Boolean,
    onValueChange: (String) -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }

    val previewColor = remember(value) {
        parseComposeColorOrNull(value) ?: Color.Transparent
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                label = { Text(label) },
                supportingText = {
                    Text(
                        stringResource(
                            if (isValid) {
                                if (allowDefault) {
                                    R.string.system_bar_color_hint
                                } else {
                                    R.string.visual_color_format_hint
                                }
                            } else {
                                R.string.visual_color_invalid
                            },
                        ),
                    )
                },
                isError = !isValid,
                singleLine = true,
            )

            OutlinedButton(
                onClick = { pickerOpen = true },
                enabled = enabled,
                modifier = Modifier
                    .heightIn(min = 56.dp)
                    .widthIn(min = 94.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (value.equals(
                                    ArdaVisualTheme.noOverride,
                                    ignoreCase = true,
                                )
                            ) {
                                surfaceRaised
                            } else {
                                previewColor
                            },
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_pick_color))
            }
        }
    }

    if (pickerOpen) {
        ArdaColorPickerDialog(
            title = label,
            initialValue = value,
            allowDefault = allowDefault,
            onDismiss = { pickerOpen = false },
            onSelected = {
                onValueChange(it)
                pickerOpen = false
            },
        )
    }
}

@Composable
private fun ArdaColorPickerDialog(
    title: String,
    initialValue: String,
    allowDefault: Boolean,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    val fallback = AndroidColor.rgb(11, 16, 22)
    val initialColor = remember(initialValue) {
        runCatching {
            if (initialValue.equals(ArdaVisualTheme.noOverride, ignoreCase = true)) {
                fallback
            } else {
                AndroidColor.parseColor(ArdaVisualTheme.normalizeColor(initialValue))
            }
        }.getOrDefault(fallback)
    }

    val initialHsv = remember(initialColor) {
        FloatArray(3).also { AndroidColor.colorToHSV(initialColor, it) }
    }

    var hue by remember(initialColor) { mutableStateOf(initialHsv[0]) }
    var saturation by remember(initialColor) { mutableStateOf(initialHsv[1]) }
    var value by remember(initialColor) { mutableStateOf(initialHsv[2]) }

    val selectedArgb = AndroidColor.HSVToColor(
        floatArrayOf(hue, saturation, value),
    )
    val selectedHex = String.format(
        Locale.US,
        "#FF%02X%02X%02X",
        AndroidColor.red(selectedArgb),
        AndroidColor.green(selectedArgb),
        AndroidColor.blue(selectedArgb),
    )
    val selectedComposeColor = Color(selectedArgb)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(selectedComposeColor),
                )

                Spacer(Modifier.height(10.dp))
                Text(
                    text = selectedHex,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleMedium,
                )

                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.color_picker_hue, hue.roundToInt()),
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = hue,
                    onValueChange = { hue = it },
                    valueRange = 0f..360f,
                )

                Text(
                    text = stringResource(
                        R.string.color_picker_saturation,
                        (saturation * 100f).roundToInt(),
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = saturation,
                    onValueChange = { saturation = it },
                    valueRange = 0f..1f,
                )

                Text(
                    text = stringResource(
                        R.string.color_picker_brightness,
                        (value * 100f).roundToInt(),
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = 0f..1f,
                )

                Text(
                    text = stringResource(R.string.color_picker_presets),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "#FF000000",
                        "#FF0B1016",
                        "#FF182230",
                        "#FF303840",
                        "#FFFFFFFF",
                        "#FF1B365D",
                    ).forEach { preset ->
                        val presetColor = parseComposeColorOrNull(preset) ?: Color.Transparent
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(presetColor)
                                .clickable {
                                    val parsed = AndroidColor.parseColor(preset)
                                    val hsv = FloatArray(3)
                                    AndroidColor.colorToHSV(parsed, hsv)
                                    hue = hsv[0]
                                    saturation = hsv[1]
                                    value = hsv[2]
                                },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelected(selectedHex) }) {
                Text(stringResource(R.string.action_use_color))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (allowDefault) {
                    TextButton(
                        onClick = {
                            onSelected(ArdaVisualTheme.noOverride)
                        },
                    ) {
                        Text(stringResource(R.string.visual_mode_default))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
}

@Composable
private fun LauncherBackgroundImagePicker(
    imageAvailable: Boolean,
    imageRevision: Int,
    selected: Boolean,
    onPick: () -> Unit,
    onUseImage: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val file = remember(context) {
        ArdaVisualThemeStore.launcherBackgroundFile(context)
    }
    val image = remember(imageAvailable, imageRevision) {
        if (imageAvailable && file.isFile) {
            runCatching {
                BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
            }.getOrNull()
        } else {
            null
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (image != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = surfaceRaised),
            ) {
                Image(
                    bitmap = image,
                    contentDescription = stringResource(
                        R.string.launcher_background_preview_description,
                    ),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onPick,
                modifier = Modifier.heightIn(min = 52.dp),
            ) {
                Text(
                    stringResource(
                        if (imageAvailable) {
                            R.string.action_replace_background_image
                        } else {
                            R.string.action_select_background_image
                        },
                    ),
                )
            }

            if (imageAvailable && !selected) {
                Button(
                    onClick = onUseImage,
                    modifier = Modifier.heightIn(min = 52.dp),
                ) {
                    Text(stringResource(R.string.action_use_image))
                }
            }

            if (imageAvailable) {
                TextButton(onClick = onRemove) {
                    Text(stringResource(R.string.action_remove_image))
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(
                when {
                    selected -> R.string.launcher_background_image_selected
                    imageAvailable -> R.string.launcher_background_image_ready
                    else -> R.string.launcher_background_image_none
                },
            ),
            color = textSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun parseComposeColorOrNull(value: String): Color? {
    if (value.equals(ArdaVisualTheme.noOverride, ignoreCase = true)) {
        return null
    }
    return runCatching {
        Color(AndroidColor.parseColor(ArdaVisualTheme.normalizeColor(value)))
    }.getOrNull()
}

@Composable
private fun VisualModeButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(
            onClick = {},
            enabled = false,
            modifier = Modifier.heightIn(min = 52.dp),
        ) {
            Text(text)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.heightIn(min = 52.dp),
        ) {
            Text(text)
        }
    }
}

@Composable
private fun ConfigScreen(
    snapshot: ArdaPlatformSnapshot,
    onSnapshotChanged: (ArdaPlatformSnapshot) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.configuration_saved)
    val saveFailedMessage = stringResource(R.string.configuration_save_failed)
    var host by rememberSaveable { mutableStateOf(snapshot.config.host) }
    var portText by rememberSaveable { mutableStateOf(snapshot.config.port.toString()) }
    var token by rememberSaveable { mutableStateOf(snapshot.config.token) }
    var keepRfcomm by rememberSaveable {
        mutableStateOf(snapshot.config.keepBootstrapRfcommOpen)
    }
    var keepProfiles by rememberSaveable {
        mutableStateOf(snapshot.config.keepBluetoothProfilesConnected)
    }
    var forcedVideoResolution by rememberSaveable {
        mutableStateOf(snapshot.config.forcedVideoResolution)
    }
    var showToken by rememberSaveable { mutableStateOf(false) }
    var bottomHvacVisible by remember {
        mutableStateOf(ArdaPlatformRepository.isBottomHvacVisible(context))
    }
    val initialSystemBarLayoutVertical = remember {
        ArdaPlatformRepository.isSystemBarLayoutVertical(context)
    }
    var systemBarLayoutVertical by rememberSaveable {
        mutableStateOf(initialSystemBarLayoutVertical)
    }
    var persistedSystemBarLayoutVertical by remember {
        mutableStateOf(initialSystemBarLayoutVertical)
    }

    // ARDA_SYSTEMBAR_SAFE_RECENTS_SETTINGS_V1
    val initialSystemBarRespectSafeArea = remember {
        ArdaPlatformRepository.isSystemBarRespectSafeAreaEnabled(context)
    }
    var systemBarRespectSafeArea by rememberSaveable {
        mutableStateOf(initialSystemBarRespectSafeArea)
    }
    var persistedSystemBarRespectSafeArea by remember {
        mutableStateOf(initialSystemBarRespectSafeArea)
    }

    val initialDockShowRecentMap = remember {
        ArdaPlatformRepository.isDockShowRecentMapEnabled(context)
    }
    var dockShowRecentMap by rememberSaveable {
        mutableStateOf(initialDockShowRecentMap)
    }
    var persistedDockShowRecentMap by remember {
        mutableStateOf(initialDockShowRecentMap)
    }

    val initialDockShowRecentApp = remember {
        ArdaPlatformRepository.isDockShowRecentAppEnabled(context)
    }
    var dockShowRecentApp by rememberSaveable {
        mutableStateOf(initialDockShowRecentApp)
    }
    var persistedDockShowRecentApp by remember {
        mutableStateOf(initialDockShowRecentApp)
    }

    val initialLauncherMapOnRight = remember {
        ArdaPlatformRepository.isLauncherMapOnRight(context)
    }
    val initialLauncherMapWidthPercent = remember {
        ArdaPlatformRepository.getLauncherMapWidthPercent(context)
    }
    val initialAapDensityScaleTenths = remember {
        ArdaPlatformRepository.getAapDensityScaleTenths(context)
    }
    val initialCpuBoostOffsetMhz = remember {
        ArdaCpuPerformance.getRequestedOffsetMhz(context)
    }
    val initialVideoRouteState = remember {
        ArdaVideoRouteController.readState()
    }
    val initialVideoHardwareProfile = remember {
        ArdaVideoHardwareProfileController.getHardwareProfile(context)
    }
    val initialVideoFpsMode = remember {
        ArdaVideoHardwareProfileController.getFpsMode(context)
    }
    // ARDA_C2_ENCODER_TUNING_UI_V1
    val initialVideoGpuI420 = remember {
        ArdaVideoHardwareProfileController.getGpuI420Enabled(context)
    }
    val initialVideoIveSpeed = remember {
        ArdaVideoHardwareProfileController.getIveSpeed(context)
    }
    val initialVideoBitrateBps = remember {
        ArdaVideoHardwareProfileController.getBitrateBps(context)
    }
    val initialVisualTheme = remember {
        ArdaPlatformRepository.getVisualTheme(context)
    }
    var launcherMapOnRight by rememberSaveable {
        mutableStateOf(initialLauncherMapOnRight)
    }
    var launcherMapWidthPercent by rememberSaveable {
        mutableStateOf(initialLauncherMapWidthPercent)
    }
    var aapDensityScaleTenths by rememberSaveable {
        mutableStateOf(initialAapDensityScaleTenths)
    }
    var cpuBoostOffsetMhz by rememberSaveable {
        mutableStateOf(initialCpuBoostOffsetMhz)
    }
    var cpuRuntimeState by remember {
        mutableStateOf(ArdaCpuPerformance.readRuntimeState())
    }
    var videoRoute by rememberSaveable {
        mutableStateOf(initialVideoRouteState.selected)
    }
    var persistedVideoRoute by remember {
        mutableStateOf(initialVideoRouteState.selected)
    }
    var videoRouteRuntimeState by remember {
        mutableStateOf(initialVideoRouteState)
    }
    var showVideoRouteRebootDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var videoHardwareProfile by rememberSaveable {
        mutableStateOf(initialVideoHardwareProfile)
    }
    var persistedVideoHardwareProfile by remember {
        mutableStateOf(initialVideoHardwareProfile)
    }
    var videoFpsMode by rememberSaveable {
        mutableStateOf(initialVideoFpsMode)
    }
    var persistedVideoFpsMode by remember {
        mutableStateOf(initialVideoFpsMode)
    }
    // ARDA_C2_ENCODER_TUNING_UI_V1
    var videoGpuI420 by rememberSaveable {
        mutableStateOf(initialVideoGpuI420)
    }
    var persistedVideoGpuI420 by remember {
        mutableStateOf(initialVideoGpuI420)
    }
    var videoIveSpeed by rememberSaveable {
        mutableStateOf(initialVideoIveSpeed)
    }
    var persistedVideoIveSpeed by remember {
        mutableStateOf(initialVideoIveSpeed)
    }
    var videoBitrateInput by rememberSaveable {
        mutableStateOf(initialVideoBitrateBps.toString())
    }
    var persistedVideoBitrateInput by remember {
        mutableStateOf(initialVideoBitrateBps.toString())
    }
    var persistedLauncherMapOnRight by remember {
        mutableStateOf(initialLauncherMapOnRight)
    }
    var persistedLauncherMapWidthPercent by remember {
        mutableStateOf(initialLauncherMapWidthPercent)
    }
    var persistedAapDensityScaleTenths by remember {
        mutableStateOf(initialAapDensityScaleTenths)
    }
    var persistedCpuBoostOffsetMhz by remember {
        mutableStateOf(initialCpuBoostOffsetMhz)
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            cpuRuntimeState = ArdaCpuPerformance.readRuntimeState()
            videoRouteRuntimeState = ArdaVideoRouteController.readState()
            delay(1_000)
        }
    }

    var launcherBackgroundMode by rememberSaveable {
        mutableStateOf(initialVisualTheme.launcherBackgroundMode)
    }
    var launcherBackgroundColor by rememberSaveable {
        mutableStateOf(initialVisualTheme.launcherBackgroundColor)
    }
    var navigationBarBackgroundColor by rememberSaveable {
        mutableStateOf(initialVisualTheme.navigationBarBackgroundColor)
    }
    var statusBarBackgroundColor by rememberSaveable {
        mutableStateOf(initialVisualTheme.statusBarBackgroundColor)
    }
    var launcherBackgroundImageAvailable by remember {
        mutableStateOf(initialVisualTheme.launcherBackgroundImageAvailable)
    }
    var launcherBackgroundImageRevision by remember { mutableStateOf(0) }
    var persistedVisualTheme by remember {
        mutableStateOf(initialVisualTheme)
    }

    val launcherBackgroundPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                ArdaVisualThemeStore.importLauncherBackground(context, uri)
            }.onSuccess {
                launcherBackgroundImageAvailable = true
                launcherBackgroundImageRevision += 1
                launcherBackgroundMode =
                    ArdaVisualTheme.launcherBackgroundModeImage
            }.onFailure { error ->
                scope.launch {
                    snackbarHostState.showSnackbar(
                        error.message ?: saveFailedMessage,
                    )
                }
            }
        }
    }

    var persisted by remember { mutableStateOf(snapshot.config) }

    fun load(config: ArdaBridgeConfig) {
        host = config.host
        portText = config.port.toString()
        token = config.token
        keepRfcomm = config.keepBootstrapRfcommOpen
        keepProfiles = config.keepBluetoothProfilesConnected
        forcedVideoResolution = config.forcedVideoResolution
        bottomHvacVisible =
            ArdaPlatformRepository.isBottomHvacVisible(context)
        systemBarLayoutVertical =
            ArdaPlatformRepository.isSystemBarLayoutVertical(context)
        systemBarRespectSafeArea =
            ArdaPlatformRepository.isSystemBarRespectSafeAreaEnabled(context)
        dockShowRecentMap =
            ArdaPlatformRepository.isDockShowRecentMapEnabled(context)
        dockShowRecentApp =
            ArdaPlatformRepository.isDockShowRecentAppEnabled(context)
        launcherMapOnRight = ArdaPlatformRepository.isLauncherMapOnRight(context)
        launcherMapWidthPercent = ArdaPlatformRepository.getLauncherMapWidthPercent(context)
        aapDensityScaleTenths =
            ArdaPlatformRepository.getAapDensityScaleTenths(context)
        cpuBoostOffsetMhz =
            ArdaCpuPerformance.getRequestedOffsetMhz(context)
        val routeState = ArdaVideoRouteController.readState()
        videoRoute = routeState.selected
        persistedVideoRoute = routeState.selected
        videoRouteRuntimeState = routeState
        videoHardwareProfile =
            ArdaVideoHardwareProfileController.getHardwareProfile(context)
        persistedVideoHardwareProfile = videoHardwareProfile
        videoFpsMode =
            ArdaVideoHardwareProfileController.getFpsMode(context)
        persistedVideoFpsMode = videoFpsMode
        videoGpuI420 =
            ArdaVideoHardwareProfileController.getGpuI420Enabled(context)
        persistedVideoGpuI420 = videoGpuI420
        videoIveSpeed =
            ArdaVideoHardwareProfileController.getIveSpeed(context)
        persistedVideoIveSpeed = videoIveSpeed
        videoBitrateInput =
            ArdaVideoHardwareProfileController.getBitrateBps(context).toString()
        persistedVideoBitrateInput = videoBitrateInput
        persistedSystemBarLayoutVertical = systemBarLayoutVertical
        persistedSystemBarRespectSafeArea = systemBarRespectSafeArea
        persistedDockShowRecentMap = dockShowRecentMap
        persistedDockShowRecentApp = dockShowRecentApp
        persistedLauncherMapOnRight = launcherMapOnRight
        persistedLauncherMapWidthPercent = launcherMapWidthPercent
        persistedAapDensityScaleTenths = aapDensityScaleTenths
        persistedCpuBoostOffsetMhz = cpuBoostOffsetMhz
        cpuRuntimeState = ArdaCpuPerformance.readRuntimeState()
        val visualTheme = ArdaPlatformRepository.getVisualTheme(context)
        launcherBackgroundMode = visualTheme.launcherBackgroundMode
        launcherBackgroundColor = visualTheme.launcherBackgroundColor
        navigationBarBackgroundColor = visualTheme.navigationBarBackgroundColor
        statusBarBackgroundColor = visualTheme.statusBarBackgroundColor
        launcherBackgroundImageAvailable =
            visualTheme.launcherBackgroundImageAvailable
        persistedVisualTheme = visualTheme
        persisted = config
    }

    val parsedPort = portText.toIntOrNull()
    val portValid = parsedPort != null && parsedPort in 1..65535
    val tokenValid = token.isNotBlank() && token.toByteArray().size <= 512
    val draft = if (portValid) {
        ArdaBridgeConfig(
            host = host,
            port = requireNotNull(parsedPort),
            token = token,
            keepBootstrapRfcommOpen = keepRfcomm,
            keepBluetoothProfilesConnected = keepProfiles,
            forcedVideoResolution = forcedVideoResolution,
        )
    } else {
        null
    }
    val bridgeConfigDirty = draft != null && draft != persisted
    val launcherLayoutDirty =
        systemBarLayoutVertical != persistedSystemBarLayoutVertical ||
            systemBarRespectSafeArea != persistedSystemBarRespectSafeArea ||
            dockShowRecentMap != persistedDockShowRecentMap ||
            dockShowRecentApp != persistedDockShowRecentApp ||
            launcherMapOnRight != persistedLauncherMapOnRight ||
            launcherMapWidthPercent != persistedLauncherMapWidthPercent
    val densityScaleDirty =
        aapDensityScaleTenths != persistedAapDensityScaleTenths
    val cpuBoostDirty =
        cpuBoostOffsetMhz != persistedCpuBoostOffsetMhz
    val videoRouteDirty =
        videoRoute != persistedVideoRoute
    val videoHardwareProfileDirty =
        videoHardwareProfile != persistedVideoHardwareProfile
    val videoFpsModeDirty =
        videoFpsMode != persistedVideoFpsMode
    val videoGpuI420Dirty =
        videoGpuI420 != persistedVideoGpuI420
    val videoIveSpeedDirty =
        videoIveSpeed != persistedVideoIveSpeed
    val videoBitrateDirty =
        videoBitrateInput.trim() != persistedVideoBitrateInput.trim()
    val videoEncoderTuningDirty =
        videoGpuI420Dirty || videoIveSpeedDirty || videoBitrateDirty

    val parsedVideoBitrateBps =
        videoBitrateInput.trim().let { raw ->
            if (raw.isEmpty()) 0 else raw.toIntOrNull()
        }
    val videoBitrateValid =
        parsedVideoBitrateBps != null &&
            (
                parsedVideoBitrateBps == 0 ||
                    parsedVideoBitrateBps in
                    ArdaVideoHardwareProfileController.bitrateMinBps..
                        ArdaVideoHardwareProfileController.bitrateMaxBps
            )
    val launcherBackgroundColorValid =
        ArdaVisualTheme.isValidColor(launcherBackgroundColor)
    val navigationBarColorValid =
        navigationBarBackgroundColor.equals(
            ArdaVisualTheme.noOverride,
            ignoreCase = true,
        ) || ArdaVisualTheme.isValidColor(navigationBarBackgroundColor)
    val statusBarColorValid =
        statusBarBackgroundColor.equals(
            ArdaVisualTheme.noOverride,
            ignoreCase = true,
        ) || ArdaVisualTheme.isValidColor(statusBarBackgroundColor)
    val visualThemeValid =
        launcherBackgroundColorValid &&
            navigationBarColorValid &&
            statusBarColorValid &&
            (
                launcherBackgroundMode !=
                    ArdaVisualTheme.launcherBackgroundModeImage ||
                    launcherBackgroundImageAvailable
                )
    val visualThemeDirty =
        launcherBackgroundMode != persistedVisualTheme.launcherBackgroundMode ||
            ArdaVisualTheme.normalizeColor(launcherBackgroundColor) !=
                persistedVisualTheme.launcherBackgroundColor ||
            navigationBarBackgroundColor !=
                persistedVisualTheme.navigationBarBackgroundColor ||
            statusBarBackgroundColor !=
                persistedVisualTheme.statusBarBackgroundColor ||
            launcherBackgroundImageAvailable !=
                persistedVisualTheme.launcherBackgroundImageAvailable
    val dirty =
        draft != null &&
            (bridgeConfigDirty || launcherLayoutDirty ||
                densityScaleDirty || cpuBoostDirty || videoRouteDirty ||
                videoHardwareProfileDirty || videoFpsModeDirty ||
                videoEncoderTuningDirty || visualThemeDirty)

    val selectedObservedVideoConfig = snapshot.videoState.advertisedConfigs
        .firstOrNull { it.configIndex == snapshot.videoState.selectedConfigIndex }
        ?: snapshot.videoState.advertisedConfigs
            .firstOrNull { it.size == snapshot.videoState.selectedResolution }
    val densityBase = selectedObservedVideoConfig?.let { observed ->
        observed.realDensity.takeIf { it > 0 }
            ?: observed.density.takeIf { it > 0 }
    }
    val rawEffectiveDensity = densityBase?.let { base ->
        ((base * aapDensityScaleTenths) + 5) / 10
    }
    val effectiveDensity = rawEffectiveDensity?.coerceAtLeast(
        ArdaPlatformRepository.androidMinimumWmDensity,
    )

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        val horizontalPadding = if (wide) 48.dp else 24.dp
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = horizontalPadding,
                        end = horizontalPadding,
                        top = 28.dp,
                        bottom = 96.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                // ARDA_CAPTURE_PROFILE_SELECTOR_V1

                Column {
                    Text(
                        text = stringResource(R.string.configuration_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.configuration_description),
                        color = textSecondary,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = surface),
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.card_local_bridge),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (wide) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                HostField(host, Modifier.weight(1.7f))
                                PortField(
                                    value = portText,
                                    valid = portValid,
                                    onValueChange = { value ->
                                        if (value.length <= 5 && value.all(Char::isDigit)) {
                                            portText = value
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        } else {
                            HostField(host, Modifier.fillMaxWidth())
                            PortField(
                                value = portText,
                                valid = portValid,
                                onValueChange = { value ->
                                    if (value.length <= 5 && value.all(Char::isDigit)) {
                                        portText = value
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.token_label)) },
                            singleLine = true,
                            isError = !tokenValid,
                            visualTransformation = if (showToken) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            supportingText = {
                                Text(
                                    if (tokenValid) {
                                        stringResource(R.string.token_supporting_text)
                                    } else {
                                        stringResource(R.string.token_error)
                                    },
                                )
                            },
                            trailingIcon = {
                                TextButton(onClick = { showToken = !showToken }) {
                                    Text(stringResource(if (showToken) R.string.action_hide else R.string.action_show))
                                }
                            },
                        )
                        OutlinedButton(
                            onClick = {
                                token = ArdaBridgeConfigStore.generateReplacementToken()
                                showToken = true
                            },
                            modifier = Modifier.heightIn(min = 54.dp),
                        ) {
                            Text(stringResource(R.string.action_generate_secure_token))
                        }
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = surface),
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(24.dp)) {
                        Text(
                            text = stringResource(R.string.connection_behavior_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(10.dp))
                        SettingSwitch(
                            title = stringResource(R.string.keep_rfcomm_title),
                            description = stringResource(R.string.keep_rfcomm_description),
                            checked = keepRfcomm,
                            onCheckedChange = { keepRfcomm = it },
                        )
                        HorizontalDivider(color = divider)
                        SettingSwitch(
                            title = stringResource(R.string.keep_profiles_title),
                            description = stringResource(R.string.keep_profiles_description),
                            checked = keepProfiles,
                            onCheckedChange = { keepProfiles = it },
                        )
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = surface),
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.cpu_performance_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.cpu_performance_description),
                            color = textSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        val targetCpuMhz =
                            ArdaCpuPerformance.targetMhz(cpuBoostOffsetMhz)
                        val offsetLabel = when {
                            cpuBoostOffsetMhz > 0 -> "+${cpuBoostOffsetMhz}"
                            else -> cpuBoostOffsetMhz.toString()
                        }

                        Text(
                            text = stringResource(
                                R.string.cpu_boost_label,
                                offsetLabel,
                                targetCpuMhz,
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Slider(
                            value = cpuBoostOffsetMhz.toFloat(),
                            onValueChange = { value ->
                                val stepped =
                                    (value / ArdaCpuPerformance.stepMhz)
                                        .roundToInt() *
                                        ArdaCpuPerformance.stepMhz
                                cpuBoostOffsetMhz = stepped.coerceIn(
                                    ArdaCpuPerformance.minOffsetMhz,
                                    ArdaCpuPerformance.maxOffsetMhz,
                                )
                            },
                            valueRange =
                                ArdaCpuPerformance.minOffsetMhz.toFloat()..
                                    ArdaCpuPerformance.maxOffsetMhz.toFloat(),
                            steps =
                                (
                                    (ArdaCpuPerformance.maxOffsetMhz -
                                        ArdaCpuPerformance.minOffsetMhz) /
                                        ArdaCpuPerformance.stepMhz
                                    ) - 1,
                        )

                        if (
                            ArdaCpuPerformance.isHighOverclockOffset(cpuBoostOffsetMhz)
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.cpu_performance_high_overclock_warning,
                                ),
                                color = warning,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        Text(
                            text = stringResource(
                                R.string.cpu_performance_runtime,
                                cpuRuntimeState.currentMhz ?: 0,
                                cpuRuntimeState.policyMaxMhz ?: 0,
                                cpuRuntimeState.cpuInfoMaxMhz ?: 0,
                                cpuRuntimeState.temperatureC ?: 0f,
                            ),
                            color = textSecondary,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelMedium,
                        )

                        Text(
                            text = stringResource(
                                if (cpuBoostOffsetMhz == 0) {
                                    R.string.cpu_performance_stock_hint
                                } else {
                                    R.string.cpu_performance_custom_hint
                                },
                            ),
                            color = if (cpuBoostOffsetMhz > 0) warning else textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )

                        Text(
                            text = stringResource(R.string.cpu_performance_thermal_guard),
                            color = textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = surface),
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.video_configuration_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.video_configuration_description),
                            color = textSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        ArdaCaptureProfileSection(
                            embedded = true,
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = divider,
                        )

                        VideoHardwareProfileSelector(
                            selected = videoHardwareProfile,
                            onSelected = { videoHardwareProfile = it },
                        )
                        Text(
                            text = stringResource(
                                R.string.video_hw_profile_resolved,
                                when (
                                    ArdaVideoHardwareProfileController
                                        .resolveHardwareProfile(videoHardwareProfile)
                                ) {
                                    ArdaVideoHardwareProfile.Pi4Hdmi ->
                                        stringResource(R.string.video_hw_profile_pi4)
                                    ArdaVideoHardwareProfile.Pi5HwcVds ->
                                        stringResource(R.string.video_hw_profile_pi5)
                                    ArdaVideoHardwareProfile.Auto ->
                                        stringResource(R.string.video_hw_profile_auto)
                                },
                            ),
                            color = textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )

                        VideoFpsModeSelector(
                            selected = videoFpsMode,
                            hardwareProfile = videoHardwareProfile,
                            onSelected = { videoFpsMode = it },
                        )

                        // ARDA_C2_ENCODER_TUNING_UI_V2
                        // Controls are always visible. Runtime application is
                        // gated by the resolved hardware profile in autostart.
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = divider,
                        )

                        VideoEncoderTuningSection(
                            gpuI420Enabled = videoGpuI420,
                            onGpuI420Changed = { videoGpuI420 = it },
                            iveSpeed = videoIveSpeed,
                            onIveSpeedChanged = { videoIveSpeed = it },
                            bitrateInput = videoBitrateInput,
                            bitrateValid = videoBitrateValid,
                            onBitrateInputChanged = { raw ->
                                if (
                                    raw.length <= 8 &&
                                    raw.all { it.isDigit() }
                                ) {
                                    videoBitrateInput = raw
                                }
                            },
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = divider,
                        )

                        VideoRouteSelector(
                            selected = videoRoute,
                            onSelected = { videoRoute = it },
                        )
                        Text(
                            text = when (val active = videoRouteRuntimeState.active) {
                                null -> stringResource(
                                    R.string.video_route_runtime_unknown,
                                    videoRouteRuntimeState.selected.propertyValue.uppercase(Locale.US),
                                )
                                else -> stringResource(
                                    if (videoRouteRuntimeState.restartRequired) {
                                        R.string.video_route_runtime_reboot_required
                                    } else {
                                        R.string.video_route_runtime_active
                                    },
                                    active.propertyValue.uppercase(Locale.US),
                                    videoRouteRuntimeState.selected.propertyValue.uppercase(Locale.US),
                                )
                            },
                            color = if (videoRouteRuntimeState.restartRequired) warning else textSecondary,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        if (videoRouteRuntimeState.restartRequired) {
                            OutlinedButton(
                                onClick = { showVideoRouteRebootDialog = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 52.dp),
                            ) {
                                Text(stringResource(R.string.video_route_reboot_action))
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = divider,
                        )

                        VideoResolutionSelector(
                            forcedResolution = forcedVideoResolution,
                            observed = snapshot.videoState,
                            onSelected = { forcedVideoResolution = it },
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = divider,
                        )

                        Text(
                            text = stringResource(
                                R.string.video_density_scale_label,
                                aapDensityScaleTenths / 10f,
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(R.string.video_density_scale_description),
                            color = textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Slider(
                            value = aapDensityScaleTenths.toFloat(),
                            onValueChange = { value ->
                                aapDensityScaleTenths = value.roundToInt()
                            },
                            valueRange =
                                ArdaPlatformRepository.aapDensityScaleMinTenths.toFloat()..
                                    ArdaPlatformRepository.aapDensityScaleMaxTenths.toFloat(),
                            steps = ArdaPlatformRepository.aapDensityScaleMaxTenths -
                                ArdaPlatformRepository.aapDensityScaleMinTenths - 1,
                        )
                        Text(
                            text = if (
                                densityBase != null &&
                                effectiveDensity != null &&
                                rawEffectiveDensity != null
                            ) {
                                stringResource(
                                    R.string.video_density_effective_format,
                                    densityBase,
                                    effectiveDensity,
                                )
                            } else {
                                stringResource(
                                    R.string.video_density_effective_waiting,
                                )
                            },
                            color = textSecondary,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        if (
                            rawEffectiveDensity != null &&
                            rawEffectiveDensity <
                                ArdaPlatformRepository.androidMinimumWmDensity
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.video_density_minimum_clamp,
                                    ArdaPlatformRepository.androidMinimumWmDensity,
                                ),
                                color = warning,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        Text(
                            text = snapshot.videoState.selectedResolution?.let { resolution ->
                                stringResource(
                                    R.string.video_last_selected_format,
                                    resolution,
                                    snapshot.videoState.selectedFps ?: 0,
                                    snapshot.videoState.selectionPolicy ?: "unknown",
                                )
                            } ?: stringResource(R.string.video_waiting_for_sdr),
                            color = textSecondary,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        if (snapshot.videoState.advertisedConfigs.isNotEmpty()) {
                            Text(
                                text = snapshot.videoState.advertisedConfigs.joinToString(
                                    separator = " · ",
                                ) { it.displayLabel },
                                color = textSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            text = snapshot.videoRuntimeConfigPath,
                            color = textSecondary,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = surface),
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(24.dp)) {
                        Text(
                            text = stringResource(R.string.system_interface_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(10.dp))

                        Text(
                            text = stringResource(R.string.system_bar_layout_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.system_bar_layout_description),
                            color = textSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (systemBarLayoutVertical) {
                                OutlinedButton(
                                    onClick = {
                                        systemBarLayoutVertical = false
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 60.dp),
                                ) {
                                    Text(
                                        stringResource(
                                            R.string.system_bar_layout_horizontal,
                                        ),
                                    )
                                }
                                Button(
                                    onClick = {},
                                    enabled = false,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 60.dp),
                                ) {
                                    Text(
                                        stringResource(
                                            R.string.system_bar_layout_vertical,
                                        ),
                                    )
                                }
                            } else {
                                Button(
                                    onClick = {},
                                    enabled = false,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 60.dp),
                                ) {
                                    Text(
                                        stringResource(
                                            R.string.system_bar_layout_horizontal,
                                        ),
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        systemBarLayoutVertical = true
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 60.dp),
                                ) {
                                    Text(
                                        stringResource(
                                            R.string.system_bar_layout_vertical,
                                        ),
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = stringResource(
                                if (systemBarLayoutVertical) {
                                    R.string.system_bar_layout_vertical_description
                                } else {
                                    R.string.system_bar_layout_horizontal_description
                                },
                            ),
                            color = textSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 18.dp),
                            color = divider,
                        )

                        SettingSwitch(
                            title = stringResource(R.string.bottom_hvac_visibility_title),
                            description = stringResource(
                                if (systemBarLayoutVertical) {
                                    R.string.bottom_hvac_vertical_disabled_description
                                } else {
                                    R.string.bottom_hvac_visibility_description
                                },
                            ),
                            checked = bottomHvacVisible && !systemBarLayoutVertical,
                            enabled = !systemBarLayoutVertical,
                            onCheckedChange = { visible ->
                                runCatching {
                                    ArdaPlatformRepository.setBottomHvacVisible(
                                        context,
                                        visible,
                                    )
                                }.onSuccess {
                                    bottomHvacVisible = visible
                                }.onFailure { error ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            error.message ?: saveFailedMessage,
                                        )
                                    }
                                }
                            },
                        )

                        // ARDA_SYSTEMBAR_SAFE_RECENTS_SETTINGS_V1
                        ArdaSettingsToggleRow(
                            title = stringResource(R.string.system_bar_respect_safe_area_title),
                            description = stringResource(R.string.system_bar_respect_safe_area_description),
                            checked = systemBarRespectSafeArea,
                            onCheckedChange = { systemBarRespectSafeArea = it },
                        )
                        HorizontalDivider(color = divider)
                        ArdaSettingsToggleRow(
                            title = stringResource(R.string.dock_recent_map_title),
                            description = stringResource(R.string.dock_recent_map_description),
                            checked = dockShowRecentMap,
                            onCheckedChange = { dockShowRecentMap = it },
                        )
                        ArdaSettingsToggleRow(
                            title = stringResource(R.string.dock_recent_app_title),
                            description = stringResource(R.string.dock_recent_app_description),
                            checked = dockShowRecentApp,
                            onCheckedChange = { dockShowRecentApp = it },
                        )
                        HorizontalDivider(color = divider)
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 18.dp),
                            color = divider,
                        )

                        Text(
                            text = stringResource(R.string.visual_theme_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.visual_theme_description),
                            color = textSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(14.dp))

                        Text(
                            text = stringResource(R.string.launcher_background_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(R.string.launcher_background_description),
                            color = textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            VisualModeButton(
                                text = stringResource(R.string.visual_mode_default),
                                selected = launcherBackgroundMode ==
                                    ArdaVisualTheme.launcherBackgroundModeDefault,
                                onClick = {
                                    launcherBackgroundMode =
                                        ArdaVisualTheme.launcherBackgroundModeDefault
                                },
                            )
                            VisualModeButton(
                                text = stringResource(R.string.visual_mode_color),
                                selected = launcherBackgroundMode ==
                                    ArdaVisualTheme.launcherBackgroundModeColor,
                                onClick = {
                                    launcherBackgroundMode =
                                        ArdaVisualTheme.launcherBackgroundModeColor
                                },
                            )
                            VisualModeButton(
                                text = stringResource(R.string.visual_mode_image),
                                selected = launcherBackgroundMode ==
                                    ArdaVisualTheme.launcherBackgroundModeImage,
                                onClick = {
                                    if (launcherBackgroundImageAvailable) {
                                        launcherBackgroundMode =
                                            ArdaVisualTheme.launcherBackgroundModeImage
                                    } else {
                                        launcherBackgroundPicker.launch(arrayOf("image/*"))
                                    }
                                },
                            )
                        }

                        Spacer(Modifier.height(10.dp))
                        ArdaColorPickerField(
                            label = stringResource(R.string.launcher_background_color_label),
                            value = launcherBackgroundColor,
                            enabled = launcherBackgroundMode ==
                                ArdaVisualTheme.launcherBackgroundModeColor,
                            allowDefault = false,
                            isValid = launcherBackgroundColorValid,
                            onValueChange = { launcherBackgroundColor = it },
                        )

                        LauncherBackgroundImagePicker(
                            imageAvailable = launcherBackgroundImageAvailable,
                            imageRevision = launcherBackgroundImageRevision,
                            selected = launcherBackgroundMode ==
                                ArdaVisualTheme.launcherBackgroundModeImage,
                            onPick = {
                                launcherBackgroundPicker.launch(arrayOf("image/*"))
                            },
                            onUseImage = {
                                launcherBackgroundMode =
                                    ArdaVisualTheme.launcherBackgroundModeImage
                            },
                            onRemove = {
                                ArdaVisualThemeStore.removeLauncherBackground(context)
                                launcherBackgroundImageAvailable = false
                                launcherBackgroundImageRevision += 1
                                if (launcherBackgroundMode ==
                                    ArdaVisualTheme.launcherBackgroundModeImage
                                ) {
                                    launcherBackgroundMode =
                                        ArdaVisualTheme.launcherBackgroundModeDefault
                                }
                            },
                        )

                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.system_bar_colors_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(R.string.system_bar_colors_description),
                            color = textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(10.dp))

                        ArdaColorPickerField(
                            label = stringResource(R.string.navigation_bar_color_label),
                            value = navigationBarBackgroundColor,
                            enabled = true,
                            allowDefault = true,
                            isValid = navigationBarColorValid,
                            onValueChange = { navigationBarBackgroundColor = it },
                        )

                        ArdaColorPickerField(
                            label = stringResource(R.string.status_bar_color_label),
                            value = statusBarBackgroundColor,
                            enabled = true,
                            allowDefault = true,
                            isValid = statusBarColorValid,
                            onValueChange = { statusBarBackgroundColor = it },
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 18.dp),
                            color = divider,
                        )

                        Text(
                            text = stringResource(R.string.launcher_layout_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.launcher_layout_description),
                            color = textSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(14.dp))

                        Text(
                            text = stringResource(R.string.launcher_map_position_label),
                            color = textSecondary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (launcherMapOnRight) {
                                OutlinedButton(
                                    onClick = {
                                        launcherMapOnRight = false
                                    },
                                ) {
                                    Text(stringResource(R.string.launcher_map_position_left))
                                }
                                Button(
                                    onClick = {},
                                    enabled = false,
                                ) {
                                    Text(stringResource(R.string.launcher_map_position_right))
                                }
                            } else {
                                Button(
                                    onClick = {},
                                    enabled = false,
                                ) {
                                    Text(stringResource(R.string.launcher_map_position_left))
                                }
                                OutlinedButton(
                                    onClick = {
                                        launcherMapOnRight = true
                                    },
                                ) {
                                    Text(stringResource(R.string.launcher_map_position_right))
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(
                                R.string.launcher_map_width_label,
                                launcherMapWidthPercent,
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(R.string.launcher_map_width_description),
                            color = textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Slider(
                            value = launcherMapWidthPercent.toFloat(),
                            onValueChange = { value ->
                                launcherMapWidthPercent = value.roundToInt()
                            },
                            onValueChangeFinished = {},
                            valueRange =
                                ArdaPlatformRepository.launcherMapWidthMinPercent.toFloat()..
                                    ArdaPlatformRepository.launcherMapWidthMaxPercent.toFloat(),
                            steps = ArdaPlatformRepository.launcherMapWidthMaxPercent -
                                ArdaPlatformRepository.launcherMapWidthMinPercent - 1,
                        )
                    }
                }

                Text(
                    text = snapshot.runtimeConfigPath,
                    color = textSecondary,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Surface(
                color = Color(0xF20E151E),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                tonalElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = horizontalPadding, vertical = 16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = {
                            load(ArdaPlatformRepository.loadSnapshot(context).config)
                        },
                        modifier = Modifier.heightIn(min = 56.dp),
                    ) {
                        Text(stringResource(R.string.action_reload_saved))
                    }
                    Spacer(Modifier.width(14.dp))
                    Button(
                        onClick = {
                            val config = draft ?: return@Button
                            runCatching {
                                ArdaPlatformRepository.setAapDensityScaleTenths(
                                    context,
                                    aapDensityScaleTenths,
                                )
                                ArdaCpuPerformance.setRequestedOffsetMhz(
                                    context,
                                    cpuBoostOffsetMhz,
                                )
                                ArdaVideoRouteController.setSelected(videoRoute)
                                ArdaVideoHardwareProfileController.setHardwareProfile(
                                    context,
                                    videoHardwareProfile,
                                )
                                ArdaVideoHardwareProfileController.setFpsMode(
                                    context,
                                    videoFpsMode,
                                )
                                ArdaVideoHardwareProfileController.setGpuI420Enabled(
                                    context,
                                    videoGpuI420,
                                )
                                ArdaVideoHardwareProfileController.setIveSpeed(
                                    context,
                                    videoIveSpeed,
                                )
                                ArdaVideoHardwareProfileController.setBitrateBps(
                                    context,
                                    if (videoBitrateValid) {
                                        parsedVideoBitrateBps ?: 0
                                    } else {
                                        0
                                    },
                                )
                                ArdaPlatformRepository.saveConfig(context, config)
                                ArdaPlatformRepository.setSystemBarRespectSafeAreaEnabled(
                                    context,
                                    systemBarRespectSafeArea,
                                )
                                ArdaPlatformRepository.setDockShowRecentMapEnabled(
                                    context,
                                    dockShowRecentMap,
                                )
                                ArdaPlatformRepository.setDockShowRecentAppEnabled(
                                    context,
                                    dockShowRecentApp,
                                )
                                ArdaPlatformRepository.setSystemBarLayoutVertical(
                                    context,
                                    systemBarLayoutVertical,
                                )
                                if (systemBarLayoutVertical) {
                                    bottomHvacVisible = false
                                }
                                ArdaPlatformRepository.setLauncherMapOnRight(
                                    context,
                                    launcherMapOnRight,
                                )
                                ArdaPlatformRepository.setLauncherMapWidthPercent(
                                    context,
                                    launcherMapWidthPercent,
                                )
                                ArdaPlatformRepository.saveVisualTheme(
                                    context = context,
                                    launcherBackgroundMode = launcherBackgroundMode,
                                    launcherBackgroundColor = launcherBackgroundColor,
                                    navigationBarBackgroundColor =
                                        navigationBarBackgroundColor,
                                    statusBarBackgroundColor =
                                        statusBarBackgroundColor,
                                )
                                persisted = config
                                persistedSystemBarLayoutVertical =
                                    systemBarLayoutVertical
                                persistedSystemBarRespectSafeArea = systemBarRespectSafeArea
                                persistedDockShowRecentMap = dockShowRecentMap
                                persistedDockShowRecentApp = dockShowRecentApp
                                persistedLauncherMapOnRight = launcherMapOnRight
                                persistedLauncherMapWidthPercent = launcherMapWidthPercent
                                persistedAapDensityScaleTenths =
                                    aapDensityScaleTenths
                                persistedCpuBoostOffsetMhz =
                                    cpuBoostOffsetMhz
                                persistedVideoRoute = videoRoute
                                persistedVideoHardwareProfile = videoHardwareProfile
                                persistedVideoFpsMode = videoFpsMode
                                persistedVideoGpuI420 = videoGpuI420
                                persistedVideoIveSpeed = videoIveSpeed
                                persistedVideoBitrateInput =
                                    if (videoBitrateValid) {
                                        (parsedVideoBitrateBps ?: 0).toString()
                                    } else {
                                        "0"
                                    }
                                if (!videoBitrateValid) {
                                    videoBitrateInput = "0"
                                }
                                cpuRuntimeState =
                                    ArdaCpuPerformance.readRuntimeState()
                                videoRouteRuntimeState =
                                    ArdaVideoRouteController.readState()
                                persistedVisualTheme =
                                    ArdaPlatformRepository.getVisualTheme(context)
                                onSnapshotChanged(
                                    ArdaPlatformRepository.loadSnapshot(context),
                                )
                            }.onSuccess {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        savedMessage,
                                    )
                                }
                            }.onFailure { error ->
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        error.message ?: saveFailedMessage,
                                    )
                                }
                            }
                        },
                        enabled = dirty && portValid && tokenValid && visualThemeValid,
                        modifier = Modifier
                            .heightIn(min = 56.dp)
                            .widthIn(min = 190.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = primary),
                    ) {
                        Text(stringResource(R.string.action_save_apply), fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (showVideoRouteRebootDialog) {
                AlertDialog(
                    onDismissRequest = { showVideoRouteRebootDialog = false },
                    title = {
                        Text(stringResource(R.string.video_route_reboot_dialog_title))
                    },
                    text = {
                        Text(stringResource(R.string.video_route_reboot_dialog_description))
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showVideoRouteRebootDialog = false
                                runCatching {
                                    ArdaVideoRouteController.rebootToApply(context)
                                }.onFailure { error ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            error.message ?: saveFailedMessage,
                                        )
                                    }
                                }
                            },
                        ) {
                            Text(stringResource(R.string.video_route_reboot_confirm))
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showVideoRouteRebootDialog = false },
                        ) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    },
                )
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 92.dp),
            )
        }
    }
}


@Composable
private fun VideoEncoderTuningSection(
    gpuI420Enabled: Boolean,
    onGpuI420Changed: (Boolean) -> Unit,
    iveSpeed: ArdaVideoIveSpeed,
    onIveSpeedChanged: (ArdaVideoIveSpeed) -> Unit,
    bitrateInput: String,
    bitrateValid: Boolean,
    onBitrateInputChanged: (String) -> Unit,
) {
    var speedExpanded by remember { mutableStateOf(false) }

    Text(
        text = stringResource(R.string.video_encoder_tuning_title),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        text = stringResource(R.string.video_encoder_tuning_description),
        color = textSecondary,
        style = MaterialTheme.typography.bodySmall,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.video_gpu_conversion_label),
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(R.string.video_gpu_conversion_description),
                color = textSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = gpuI420Enabled,
            onCheckedChange = onGpuI420Changed,
        )
    }

    Box {
        OutlinedButton(
            onClick = { speedExpanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = stringResource(R.string.video_ive_speed_label),
                    color = textSecondary,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = iveSpeed.displayName,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text("▾")
        }

        DropdownMenu(
            expanded = speedExpanded,
            onDismissRequest = { speedExpanded = false },
        ) {
            ArdaVideoIveSpeed.entries.forEach { speed ->
                DropdownMenuItem(
                    text = { Text(speed.displayName) },
                    onClick = {
                        onIveSpeedChanged(speed)
                        speedExpanded = false
                    },
                )
            }
        }
    }

    OutlinedTextField(
        value = bitrateInput,
        onValueChange = onBitrateInputChanged,
        modifier = Modifier.fillMaxWidth(),
        label = {
            Text(stringResource(R.string.video_bitrate_override_label))
        },
        supportingText = {
            Text(
                stringResource(
                    if (bitrateValid) {
                        R.string.video_bitrate_override_description
                    } else {
                        R.string.video_bitrate_override_invalid
                    },
                ),
            )
        },
        isError = !bitrateValid,
        singleLine = true,
    )

    Text(
        text = stringResource(R.string.video_encoder_tuning_next_session),
        color = textSecondary,
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun VideoHardwareProfileSelector(
    selected: ArdaVideoHardwareProfile,
    onSelected: (ArdaVideoHardwareProfile) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    @Composable
    fun label(profile: ArdaVideoHardwareProfile): String =
        when (profile) {
            ArdaVideoHardwareProfile.Auto ->
                stringResource(R.string.video_hw_profile_auto)
            ArdaVideoHardwareProfile.Pi4Hdmi ->
                stringResource(R.string.video_hw_profile_pi4)
            ArdaVideoHardwareProfile.Pi5HwcVds ->
                stringResource(R.string.video_hw_profile_pi5)
        }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = stringResource(R.string.video_hw_profile_label),
                    color = textSecondary,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(text = label(selected), fontWeight = FontWeight.Bold)
            }
            Text("▾")
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            listOf(
                ArdaVideoHardwareProfile.Auto to
                    R.string.video_hw_profile_auto_description,
                ArdaVideoHardwareProfile.Pi4Hdmi to
                    R.string.video_hw_profile_pi4_description,
                ArdaVideoHardwareProfile.Pi5HwcVds to
                    R.string.video_hw_profile_pi5_description,
            ).forEach { (profile, descriptionRes) ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(label(profile))
                            Text(
                                stringResource(descriptionRes),
                                color = textSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    onClick = {
                        onSelected(profile)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun VideoFpsModeSelector(
    selected: ArdaVideoFpsMode,
    hardwareProfile: ArdaVideoHardwareProfile,
    onSelected: (ArdaVideoFpsMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    @Composable
    fun label(mode: ArdaVideoFpsMode): String =
        when (mode) {
            ArdaVideoFpsMode.Auto ->
                stringResource(R.string.video_fps_auto)
            ArdaVideoFpsMode.Optimal ->
                stringResource(R.string.video_fps_optimal)
            else ->
                stringResource(
                    R.string.video_fps_manual,
                    requireNotNull(mode.manualCap),
                )
        }

    val selectedDescription = when (selected) {
        ArdaVideoFpsMode.Auto ->
            stringResource(R.string.video_fps_auto_description)
        ArdaVideoFpsMode.Optimal ->
            stringResource(
                R.string.video_fps_optimal_description,
                ArdaVideoHardwareProfileController.optimalFpsCap(hardwareProfile),
            )
        else ->
            stringResource(
                R.string.video_fps_manual_description,
                requireNotNull(selected.manualCap),
            )
    }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = stringResource(R.string.video_fps_limit_label),
                    color = textSecondary,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(text = label(selected), fontWeight = FontWeight.Bold)
            }
            Text("▾")
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ArdaVideoFpsMode.entries.forEach { mode ->
                val description = when (mode) {
                    ArdaVideoFpsMode.Auto ->
                        stringResource(R.string.video_fps_auto_description)
                    ArdaVideoFpsMode.Optimal ->
                        stringResource(
                            R.string.video_fps_optimal_description,
                            ArdaVideoHardwareProfileController
                                .optimalFpsCap(hardwareProfile),
                        )
                    else ->
                        stringResource(
                            R.string.video_fps_manual_description,
                            requireNotNull(mode.manualCap),
                        )
                }

                DropdownMenuItem(
                    text = {
                        Column {
                            Text(label(mode))
                            Text(
                                description,
                                color = textSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    onClick = {
                        onSelected(mode)
                        expanded = false
                    },
                )
            }
        }
    }

    Spacer(Modifier.height(4.dp))
    Text(
        text = selectedDescription,
        color = textSecondary,
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        text = stringResource(R.string.video_fps_next_session),
        color = textSecondary,
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun VideoRouteSelector(
    selected: ArdaVideoRoute,
    onSelected: (ArdaVideoRoute) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = when (selected) {
        ArdaVideoRoute.Hdmi -> stringResource(R.string.video_route_hdmi)
        ArdaVideoRoute.Txp -> stringResource(R.string.video_route_txp)
    }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = stringResource(R.string.video_route_label),
                    color = textSecondary,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(text = selectedLabel, fontWeight = FontWeight.Bold)
            }
            Text("▾")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(R.string.video_route_hdmi))
                        Text(
                            stringResource(R.string.video_route_hdmi_description),
                            color = textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                onClick = {
                    onSelected(ArdaVideoRoute.Hdmi)
                    expanded = false
                },
            )
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(R.string.video_route_txp))
                        Text(
                            stringResource(R.string.video_route_txp_description),
                            color = textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                onClick = {
                    onSelected(ArdaVideoRoute.Txp)
                    expanded = false
                },
            )
        }
    }
}

@Composable
private fun VideoResolutionSelector(
    forcedResolution: String,
    observed: ArdaVideoConfigState,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val fallback = listOf("1280x720", "800x480", "1920x1080")
    val options = (observed.advertisedResolutions + fallback + forcedResolution)
        .filter(String::isNotBlank)
        .distinct()
        .sortedByDescending { value ->
            value.split('x').let { parts ->
                (parts.getOrNull(0)?.toLongOrNull() ?: 0L) *
                    (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
            }
        }
    val label = if (forcedResolution.isBlank()) {
        stringResource(R.string.video_force_auto)
    } else {
        forcedResolution
    }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = stringResource(R.string.video_force_label),
                    color = textSecondary,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(text = label, fontWeight = FontWeight.Bold)
            }
            Text("▾")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.video_force_auto)) },
                onClick = {
                    onSelected("")
                    expanded = false
                },
            )
            options.forEach { resolution ->
                DropdownMenuItem(
                    text = { Text(resolution) },
                    onClick = {
                        onSelected(resolution)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun HostField(value: String, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        modifier = modifier,
        label = { Text(stringResource(R.string.bridge_address_label)) },
        readOnly = true,
        singleLine = true,
        supportingText = { Text(stringResource(R.string.bridge_address_supporting_text)) },
    )
}

@Composable
private fun PortField(
    value: String,
    valid: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(stringResource(R.string.port_label)) },
        singleLine = true,
        isError = !valid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = {
            Text(
                if (valid) {
                    stringResource(R.string.port_range)
                } else {
                    stringResource(R.string.port_error)
                },
            )
        },
    )
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = description,
                color = if (enabled) textSecondary else divider,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.width(24.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

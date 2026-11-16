package com.github.deadknight.ardaplatform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

/**
 * HDMI physical-output profile UI.
 * Deliberately stores validated preferences ONLY. It NEVER changes DRM,
 * SurfaceFlinger, wm size/density, display power, or HDMI rotation live.
 */
@Composable
internal fun ArdaHdmiDisplaySettingsSection() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Physical HDMI outputs", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold)
        Text("Saved profiles only — physical modes are not changed by this screen. " +
                "AA projection resolution above is separate from HDMI output resolution.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
        ArdaHdmiPortSettings(port = 0)
        HorizontalDivider()
        ArdaHdmiPortSettings(port = 1)
        ArdaClusterMapModeSection()
    }
}

@Composable
private fun ArdaHdmiPortSettings(port: Int) {
    val context = LocalContext.current
    var saved by remember(context, port) {
        mutableStateOf(ArdaHdmiDisplayProfileStore.load(context, port))
    }
    var resolutionText by remember(context, port) { mutableStateOf(saved.resolution) }
    var dpiText by remember(context, port) {
        mutableStateOf(saved.densityDpi?.toString().orEmpty())
    }
    var rotation by remember(context, port) { mutableStateOf(saved.rotationDegrees) }
    var mainOverride by remember(context, port) {
        mutableStateOf(saved.allowAapMainOverride)
    }
    var message by remember(context, port) { mutableStateOf("") }

    val desired = runCatching {
        val dpi = if (dpiText.isBlank()) null else
            dpiText.toIntOrNull() ?: error("DPI must be numeric")
        ArdaHdmiDisplayProfile(
            port = port,
            resolution = resolutionText.trim().lowercase(),
            rotationDegrees = rotation,
            densityDpi = dpi,
            allowAapMainOverride = port == 0 && mainOverride,
        ).also { ArdaHdmiDisplayProfileStore.validate(it) }
    }.getOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(if (port == 0) "HDMI1 — AAOS MAIN" else "HDMI2 — AAOS Cluster",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = resolutionText,
                onValueChange = { if (it.length <= 12) { resolutionText = it; message = "" } },
                modifier = Modifier.weight(1.5f),
                singleLine = true,
                label = { Text("Resolution") },
                placeholder = { Text("auto / 1280x720") },
                isError = desired == null,
            )
            OutlinedTextField(
                value = dpiText,
                onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) {
                    dpiText = it; message = ""
                } },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text("DPI") },
                placeholder = { Text("auto") },
                isError = desired == null,
            )
        }
        Text("Rotation", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0, 90, 180, 270).forEach { degrees ->
                if (rotation == degrees) {
                    Button(onClick = { rotation = degrees; message = "" }) {
                        Text("${degrees}°")
                    }
                } else {
                    OutlinedButton(onClick = { rotation = degrees; message = "" }) {
                        Text("${degrees}°")
                    }
                }
            }
        }
        if (port == 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Allow Android Auto MAIN override", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(12.dp))
                Switch(checked = mainOverride, onCheckedChange = {
                    mainOverride = it; message = ""
                })
            }
        }
        if (desired == null) {
            Text("Invalid profile. Use 'auto' or WIDTHxHEIGHT, 0°/90°/180°/270°, " +
                    "and an optional DPI from 72 to 640.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = desired != null && desired != saved, onClick = {
                val valid = desired ?: return@Button
                try {
                    ArdaHdmiDisplayProfileStore.save(context, valid)
                    saved = valid
                    message = "Profile saved. HDMI output not changed."
                } catch (e: Exception) {
                    message = "Could not save profile: ${e.javaClass.simpleName}"
                }
            }) {
                Text("Save HDMI${port + 1} profile")
            }
            if (message.isNotBlank()) {
                Text(message, modifier = Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

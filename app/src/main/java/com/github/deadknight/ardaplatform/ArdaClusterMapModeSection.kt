package com.github.deadknight.ardaplatform

import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** ARDA_V40_1_FREE_MAP_MODE — AAOS Cluster only; physical HDMI mode unchanged. */
private const val ARDA_CLUSTER_MAP_MODE_SETTING = "arda_cluster_free_map_mode"

@Composable
internal fun ArdaClusterMapModeSection() {
    val context = LocalContext.current
    val choices = listOf(
        "default" to "Original",
        "launcher" to "Launcher-style",
        "cluster" to "Maps Cluster",
    )
    fun readMode(): String = runCatching {
        Settings.Global.getString(context.contentResolver, ARDA_CLUSTER_MAP_MODE_SETTING)
    }.getOrNull()?.takeIf { value -> choices.any { it.first == value } } ?: "default"
    var selected by remember(context) { mutableStateOf(readMode()) }
    var status by remember(context) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Cluster map without a route", style = MaterialTheme.typography.titleSmall)
        Text(
            "Optional. Original keeps the current placeholder. Launcher-style tries an " +
                "independent small-canvas Maps Activity; Maps Cluster tries the cluster " +
                "Activity. Neither moves HDMI1's existing Maps card. Requires V40.1 Cluster APK.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            choices.forEach { (value, label) ->
                val onSelect = {
                    val result = runCatching {
                        Settings.Global.putString(
                            context.contentResolver, ARDA_CLUSTER_MAP_MODE_SETTING, value)
                    }
                    if (result.getOrDefault(false)) {
                        selected = value
                        status = "Saved: $label"
                    } else {
                        status = "Not saved: WRITE_SECURE_SETTINGS access required"
                    }
                }
                if (selected == value) {
                    Button(onClick = onSelect, modifier = Modifier.weight(1f)) { Text(label) }
                } else {
                    OutlinedButton(onClick = onSelect, modifier = Modifier.weight(1f)) { Text(label) }
                }
            }
        }
        // ARDA_V42_MAP_EXPERIENCE: global state read by the AAOS Cluster process.
        Text("HDMI2 map theme", style = MaterialTheme.typography.titleSmall)
        val themeChoices = listOf(
            "vehicle" to "Car",
            "day" to "Day",
            "night" to "Night",
        )
        var theme by remember(context) {
            mutableStateOf(runCatching {
                Settings.Global.getString(context.contentResolver, "arda_cluster_map_theme")
            }.getOrNull()?.takeIf { it in listOf("vehicle", "day", "night") } ?: "vehicle")
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            themeChoices.forEach { (value, title) ->
                val choose = {
                    val saved = runCatching {
                        Settings.Global.putString(context.contentResolver,
                            "arda_cluster_map_theme", value)
                    }.getOrDefault(false)
                    if (saved) { theme = value; status = "Map theme request saved: $title" }
                    else status = "Cannot save theme: WRITE_SECURE_SETTINGS required"
                }
                if (theme == value) Button(onClick = choose, modifier = Modifier.weight(1f)) {
                    Text(title)
                } else OutlinedButton(onClick = choose, modifier = Modifier.weight(1f)) {
                    Text(title)
                }
            }
        }
        Text(
            "Car follows the current AAOS night mode. Day/Night store a preference only: " +
                "Google Maps' own palette cannot yet be overridden per display. " +
                "No system-wide night mode changes are made.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("HDMI2 map touch input", style = MaterialTheme.typography.titleSmall)
        var touch by remember(context) {
            mutableStateOf(runCatching {
                Settings.Global.getString(context.contentResolver, "arda_cluster_map_touch")
            }.getOrNull() == "on")
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf(false to "Blocked", true to "Allowed").forEach { (allowed, title) ->
                val choose = {
                    val saved = runCatching {
                        Settings.Global.putString(context.contentResolver,
                            "arda_cluster_map_touch", if (allowed) "on" else "off")
                    }.getOrDefault(false)
                    if (saved) { touch = allowed; status = "Physical map touch: $title" }
                    else status = "Cannot save touch preference: WRITE_SECURE_SETTINGS required"
                }
                if (touch == allowed) Button(onClick = choose, modifier = Modifier.weight(1f)) {
                    Text(title)
                } else OutlinedButton(onClick = choose, modifier = Modifier.weight(1f)) {
                    Text(title)
                }
            }
        }
        Text("Blocks touches on the HDMI2 map preview; does not change " +
            "Google Maps' internal touch-mode UI or input sent directly to its virtual display.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

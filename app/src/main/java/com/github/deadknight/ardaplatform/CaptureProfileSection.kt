package com.github.deadknight.ardaplatform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// ARDA_CAPTURE_PROFILE_SELECTOR_V1
@Composable
fun ArdaCaptureProfileSection(
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
) {
    val context = LocalContext.current

    var selected by remember {
        mutableStateOf(
            ArdaCaptureProfileController.desiredProfile(context)
        )
    }
    var status by remember {
        mutableStateOf<String?>(null)
    }

    val active = ArdaCaptureProfileController.activeProfile()


    val captureProfileContent: @Composable () -> Unit = {
        Column(
            modifier = if (embedded) {
                Modifier.fillMaxWidth()
            } else {
                Modifier.padding(16.dp)
            },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Video capture profile")
            Text("Default: Stable (FB1)")
            Text("Active after last boot: ${active.title}")

            ProfileRow(
                profile = ArdaCaptureProfile.STABLE,
                selected = selected == ArdaCaptureProfile.STABLE,
                onSelected = {
                    selected = ArdaCaptureProfile.STABLE
                    status = null
                },
            )

            ProfileRow(
                profile = ArdaCaptureProfile.PERFORMANCE,
                selected = selected == ArdaCaptureProfile.PERFORMANCE,
                onSelected = {
                    selected = ArdaCaptureProfile.PERFORMANCE
                    status = null
                },
            )

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = {
                    val result =
                        ArdaCaptureProfileController.persistDesiredProfile(
                            context,
                            selected,
                        )
                    status =
                        if (result.isSuccess) {
                            "Saved. Reboot required."
                        } else {
                            "Could not write capture profile property: " +
                                (result.exceptionOrNull()?.message ?: "unknown error")
                        }
                },
            ) {
                Text("Save profile")
            }

            status?.let { Text(it) }
        }
        }

    if (embedded) {
        captureProfileContent()
    } else {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            captureProfileContent()
        }
    }
}

@Composable
private fun ProfileRow(
    profile: ArdaCaptureProfile,
    selected: Boolean,
    onSelected: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelected,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        ) {
            Text(profile.title)
            Text(profile.description)
        }
    }
}

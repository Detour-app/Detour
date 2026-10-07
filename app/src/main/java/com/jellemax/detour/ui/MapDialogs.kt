package com.jellemax.detour.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.Manifest
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.jellemax.detour.data.SavedPlaces
import com.jellemax.detour.map.requiredStartupPermissions

/** Prominent disclosure for background location, required by Play policy to
 *  appear — and be accepted — before the system permission prompt is raised.
 *  The wording has to name the app, the data, the purpose and the fact that
 *  collection continues while the app is not in use; do not trim it. */
@Composable
internal fun BackgroundLocationDisclosure(
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record rides in the background") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Detour collects location data to start, record and finish your " +
                        "rides automatically, even when the app is closed or not in use.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Without this, a ride only records while Detour is open on screen. " +
                        "Your routes stay on this device unless you turn on sync to your " +
                        "own server.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = { TextButton(onClick = onAllow) { Text("Allow") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

/**
 * What the first-launch system dialogs are about to ask for, and why (#501),
 * shown before the first of them. One button, and no dismissing it by Back or
 * an outside tap: the only way on is the dialogs, which the rider can still
 * refuse one by one. A dialog that lists a permission this SDK level never
 * asks for would explain nothing, so [showActivity] and [showNotifications]
 * follow [com.jellemax.detour.map.requiredStartupPermissions].
 */
@Composable
internal fun PermissionExplainer(
    showActivity: Boolean,
    showNotifications: Boolean,
    onContinue: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Before you ride") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Detour will ask for a few permissions next. You can refuse any of them.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                PermissionReason("Location", "The map, spin and recording your rides.")
                if (showActivity) {
                    PermissionReason("Physical activity", "Noticing on its own when you start driving.")
                }
                if (showNotifications) {
                    PermissionReason(
                        "Notifications",
                        "The notification while a ride records, and alerts from your circles.",
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onContinue) { Text("Continue") } },
    )
}

@Composable
private fun PermissionReason(name: String, why: String) {
    Column {
        Text(name, style = MaterialTheme.typography.titleSmall)
        Text(why, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Name the current pin and save it as a shortcut. */
@Composable
internal fun SavePinDialog(
    suggestedName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(suggestedName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save this place") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name (Home, Work…)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * When the map's dialogs are up, and what they do.
 *
 * The dialogs themselves were already here; only the wiring that decides
 * whether they are showing was still in `MapScreen`, reading four pieces of
 * [MapScreenState] to do it. Both halves live together now, which is what makes
 * the state they read a two-parameter dependency rather than four more values
 * threaded through the screen.
 */
@Composable
internal fun MapScreenDialogs(
    s: MapScreenState,
    permissions: MapPermissions,
) {
    if (s.showPermissionExplainer) {
        val asked = requiredStartupPermissions(Build.VERSION.SDK_INT)
        PermissionExplainer(
            showActivity = Manifest.permission.ACTIVITY_RECOGNITION in asked,
            showNotifications = Manifest.permission.POST_NOTIFICATIONS in asked,
            onContinue = permissions.continueFromExplainer,
        )
    }

    if (s.showBgLocationDisclosure) {
        BackgroundLocationDisclosure(
            onAllow = {
                s.showBgLocationDisclosure = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    permissions.bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            },
            onDismiss = { s.showBgLocationDisclosure = false },
        )
    }

    s.savePinTarget?.let { target ->
        SavePinDialog(
            suggestedName = s.destinationName?.takeIf { it != "Dropped pin" } ?: "",
            onSave = { name ->
                SavedPlaces.add(name, target)
                s.savePinTarget = null
            },
            onDismiss = { s.savePinTarget = null },
        )
    }
}

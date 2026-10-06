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
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jellemax.detour.R
import com.jellemax.detour.data.SavedPlaces

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
        title = { Text(stringResource(R.string.map_bg_location_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.map_bg_location_collects),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.map_bg_location_without),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = { TextButton(onClick = onAllow) { Text(stringResource(R.string.map_allow)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.map_not_now)) } },
    )
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
        title = { Text(stringResource(R.string.map_save_place_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.map_save_place_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.map_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.map_cancel)) } },
    )
}

/**
 * When the map's two dialogs are up, and what they do.
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
    bgLocationLauncher: ManagedActivityResultLauncher<String, Boolean>,
) {
    if (s.showBgLocationDisclosure) {
        BackgroundLocationDisclosure(
            onAllow = {
                s.showBgLocationDisclosure = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            },
            onDismiss = { s.showBgLocationDisclosure = false },
        )
    }

    s.savePinTarget?.let { target ->
        val droppedPin = stringResource(R.string.map_dropped_pin)
        SavePinDialog(
            suggestedName = s.destinationName?.takeIf { it != droppedPin } ?: "",
            onSave = { name ->
                SavedPlaces.add(name, target)
                s.savePinTarget = null
            },
            onDismiss = { s.savePinTarget = null },
        )
    }
}

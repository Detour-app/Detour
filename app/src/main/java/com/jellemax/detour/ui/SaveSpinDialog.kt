package com.jellemax.detour.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jellemax.detour.R
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.NavEngine
import com.jellemax.detour.data.RouteCandidate
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.data.RouteStore
import com.jellemax.detour.data.SavedRoute
import com.jellemax.detour.data.SavedSpins
import com.jellemax.detour.data.TravelMode

/**
 * Names a spin result and keeps it in Routes (#589). [draft] is the route as
 * [com.jellemax.detour.data.SavedSpins.fromSpin] built it; only its name is
 * edited here.
 */
@Composable
internal fun SaveSpinDialog(draft: SavedRoute, onDismiss: () -> Unit) {
    var name by remember(draft.id) { mutableStateOf(draft.name) }
    var failed by remember(draft.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_spin_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.save_spin_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (failed) {
                    Text(
                        stringResource(R.string.route_editor_save_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Same guard as RouteEditorScreen's save: a full disk throws
                    // from a click handler, and uncaught that takes the app down.
                    val saved = runCatching { RouteStore.save(draft.copy(name = name.trim())) }
                    if (saved.isSuccess) onDismiss() else failed = true
                },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.routes_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.routes_cancel)) } },
    )
}

/** A [SaveSpinDialog] draft for a destination candidate, starting where its
 *  route does — or at [here] when it has none. Null with neither: nothing to
 *  start the route from. */
internal fun spinCandidateDraft(context: Context, c: RouteCandidate, mode: TravelMode, here: LatLon?): SavedRoute? {
    val start = c.route?.polyline?.firstOrNull() ?: here ?: return null
    return SavedSpins.fromSpin(
        id = System.currentTimeMillis(),
        name = c.name ?: context.getString(R.string.save_spin_destination_name),
        mode = mode,
        start = start,
        destination = c.destination,
        route = c.route,
    )
}

/** A [SaveSpinDialog] draft for a loop on the map, named by its length until
 *  the rider names it. Null for an empty line, which has no start.
 *
 *  A loop the router never measured (the approximate one a spin falls back to
 *  without a server) is named by its own line's length with a "~": a sketch
 *  through its stops, shorter than the roads it rides, but still a name —
 *  "Loop found" was the spin's status message, not one (#1025). */
internal fun spinLoopDraft(context: Context, route: RouteResult, mode: TravelMode): SavedRoute? {
    val start = route.polyline.firstOrNull() ?: return null
    val measured = route.distanceMeters
    val name = if (measured != null) {
        context.getString(R.string.save_spin_loop_name, formatDistanceKm(measured))
    } else {
        context.getString(R.string.save_spin_loop_name_approx, formatDistanceKm(NavEngine.lengthMeters(route.polyline)))
    }
    return SavedSpins.fromSpin(
        id = System.currentTimeMillis(),
        name = name,
        mode = mode,
        start = start,
        destination = null,
        route = route,
    )
}

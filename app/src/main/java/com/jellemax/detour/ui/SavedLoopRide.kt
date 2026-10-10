package com.jellemax.detour.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jellemax.detour.R
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoutingClient
import com.jellemax.detour.data.RoutingServer
import com.jellemax.detour.data.SavedRoute
import com.jellemax.detour.data.SavedSpins
import com.jellemax.detour.data.SavedSpins.LoopJoin
import com.jellemax.detour.data.Settings
import com.jellemax.detour.map.NavPolicy
import com.jellemax.detour.presentation.failureText
import com.jellemax.detour.tracking.LocationSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Riding a saved spin loop from Routes (#589), per the owner's decision on the
 * issue: at the loop's start (within [NavPolicy.ARRIVE_METERS]) it starts
 * straight away; anywhere else the rider picks between riding to the start
 * first and joining at the nearest point. Either way the loop is routed
 * through its stored stops and handed to the map with
 * [seedLoopNavigation], then [onRide] opens the map.
 *
 * Screen-lifetime: the pending choice is the Routes screen's, not the map's.
 */
internal class SavedLoopRide(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onStatus: (String?) -> Unit,
    private val onRide: () -> Unit,
) {
    /** A loop and where the rider is, while they choose how to join it. */
    var joining: Pair<SavedRoute, LatLon>? by mutableStateOf(null)

    fun start(route: SavedRoute) {
        onStatus(context.getString(R.string.routes_loop_routing))
        scope.launch {
            val here = try {
                LocationSources.currentLatLon(context)
            } catch (e: SecurityException) {
                null
            }
            when {
                here == null -> onStatus(context.getString(R.string.map_no_location))
                SavedSpins.atStart(route, here, NavPolicy.ARRIVE_METERS) -> ride(route, route.stops.map { it.at })
                else -> {
                    onStatus(null)
                    joining = route to here
                }
            }
        }
    }

    fun join(join: LoopJoin) {
        val (route, here) = joining ?: return
        joining = null
        onStatus(context.getString(R.string.routes_loop_routing))
        scope.launch { ride(route, SavedSpins.loopRoutingPoints(route.stops.map { it.at }, here, join)) }
    }

    private suspend fun ride(route: SavedRoute, points: List<LatLon>) {
        try {
            val routed = withContext(Dispatchers.IO) {
                RoutingClient.routeVia(
                    RoutingServer.load(), points, route.mode.ghProfile, Settings.routePreferences(),
                )
            }
            onStatus(null)
            seedLoopNavigation(route, routed)
            onRide()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onStatus(failureText("Routing", e))
        }
    }
}

@Composable
internal fun rememberSavedLoopRide(onStatus: (String?) -> Unit, onRide: () -> Unit): SavedLoopRide {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by rememberUpdatedState(onStatus)
    val ride by rememberUpdatedState(onRide)
    return remember { SavedLoopRide(context, scope, { status(it) }, { ride() }) }
}

/** The "how do you want to join this loop?" choice, while [ride] waits on one. */
@Composable
internal fun SavedLoopJoinDialog(ride: SavedLoopRide) {
    if (ride.joining == null) return
    AlertDialog(
        onDismissRequest = { ride.joining = null },
        title = { Text(stringResource(R.string.routes_loop_join_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.routes_loop_join_text))
                Button(onClick = { ride.join(LoopJoin.VIA_START) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.routes_loop_via_start))
                }
                OutlinedButton(onClick = { ride.join(LoopJoin.NEAREST) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.routes_loop_nearest))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { ride.joining = null }) { Text(stringResource(R.string.routes_cancel)) }
        },
    )
}

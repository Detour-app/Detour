package com.jellemax.detour.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.jellemax.detour.R
import com.jellemax.detour.data.HereTrafficClient
import com.jellemax.detour.data.Settings
import com.jellemax.detour.drive.TrafficCorridor
import com.jellemax.detour.drive.TrafficRound
import com.jellemax.detour.presentation.TrafficOnRoute
import com.jellemax.detour.presentation.routeAhead
import com.jellemax.detour.presentation.trafficOnRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** How often the traffic loop wakes. Not how often HERE is asked — that is
 *  [TrafficCorridor]'s TTLs — but how soon the delay follows the rider down
 *  the route, and how soon a reroute's new road is asked about. */
private const val TRAFFIC_TICK_MS = 15_000L

/**
 * HERE traffic on the phone's map while navigating (#645): the loop that asks,
 * and the push of what it got onto the map. The banner is [TrafficBanner]; the
 * ETA picks the delay up from [RetainedMap.traffic] in MapScreen's `navState`.
 *
 * Which tiles, how often and the backoff are [TrafficCorridor]'s, one round of
 * it is [TrafficRound]'s, and what to draw is [trafficOnRoute]'s — all in
 * shared/ with their tests. What stays here is the clock, the dispatchers and
 * the three conditions only the phone can see, under which nothing is asked:
 *
 * - **No key**, or one HERE refused: [HereTrafficClient.forKey] gives no
 *   client, or the corridor stops itself.
 * - **Not navigating**: keyed on it, so ending navigation ends the loop and
 *   clears the layer.
 * - **Screen off, or app in the background**: `repeatOnLifecycle(STARTED)`
 *   cancels the loop when the activity stops. Leaving the map for another
 *   screen disposes this composition and stops it too.
 *
 * The loop reads `s.route` and `s.navProgress` without keying on them, so a
 * GPS fix or a reroute does not restart it (and its delay); the next tick
 * picks them up — `detour-compose-state-hazards` §1.
 */
@Composable
internal fun MapTraffic(s: MapScreenState, retained: RetainedMap, mapOverlays: MapOverlays?) {
    val key by Settings.hereTrafficKey.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(key, s.navigating) {
        // A new key gets a clean corridor: that is how a refused key is left
        // behind. Compared against what the held state was built for, not
        // reset on every restart, so a return to the map keeps the cache.
        if (retained.trafficKey != key) {
            retained.trafficKey = key
            retained.trafficCorridor = TrafficCorridor.State()
        }
        val client = HereTrafficClient.forKey(key)
        if (client == null || !s.navigating) {
            retained.traffic = null
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val ahead = routeAhead(s.route?.polyline, s.navProgress)
                val held = retained.trafficCorridor
                val corridor = withContext(Dispatchers.IO) {
                    TrafficRound.run(held, client, ahead, navigating = true, nowMs = { System.currentTimeMillis() })
                }
                retained.trafficCorridor = corridor
                retained.traffic = withContext(Dispatchers.Default) {
                    trafficOnRoute(corridor, ahead, System.currentTimeMillis())
                }
                delay(TRAFFIC_TICK_MS)
            }
        }
    }

    LaunchedEffect(mapOverlays, retained.traffic) {
        mapOverlays?.setTraffic(retained.traffic)
    }
}

/**
 * "+7 min traffic" or "Road closed ahead" under the nav banner, in the camera
 * banner's glass card. Nothing below [TrafficOnRoute.BANNER_MIN_DELAY_MIN] and
 * no closure: a free road says nothing.
 */
@Composable
internal fun TrafficBanner(traffic: TrafficOnRoute) {
    val minutes = traffic.bannerDelayMinutes
    if (!traffic.closedAhead && minutes == null) return
    Card(
        modifier = Modifier
            .padding(top = 10.dp)
            .glassBorder(MaterialTheme.shapes.extraLarge),
        shape = MaterialTheme.shapes.extraLarge,
        colors = glassCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The map marker's own drawable, as the camera banner does, so
            // the banner and what it warns about read as the same thing.
            Icon(
                painterResource(if (traffic.closedAhead) R.drawable.ic_map_closure else R.drawable.ic_map_incident),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(28.dp),
            )
            Column {
                Text(
                    if (traffic.closedAhead) {
                        stringResource(R.string.map_traffic_closed)
                    } else {
                        stringResource(R.string.map_traffic_delay, minutes ?: 0)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (!traffic.closedAhead) {
                    Text(
                        stringResource(R.string.map_traffic_delay_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

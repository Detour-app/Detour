package com.jellemax.detour.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.data.RoutingServer
import com.jellemax.detour.data.ServerConfig
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.map.CameraAuthority
import com.jellemax.detour.map.fetchStartRoute
import com.jellemax.detour.presentation.failureText
import com.jellemax.detour.presentation.routePreviewStillCurrent
import com.jellemax.detour.presentation.routePreviewWanted
import kotlinx.coroutines.CancellationException

/**
 * The route before Start (#1027): once a destination is picked, ask for the
 * line Start would ride and put it in `s.route`, so the navigation dock's
 * callout shows km · min, the overlay render draws it, and a server that
 * cannot route says so in the dock rather than after Start. When to ask is
 * [routePreviewWanted], with its tests.
 *
 * Start still fetches its own line — [com.jellemax.detour.map.navStart] sends
 * every destination to `FetchTo` — because the rider may have moved since the
 * pick; this is a look at the trip, not the one guidance follows.
 *
 * The origin is read when the request starts, not keyed on, so a fix a second
 * does not re-ask the router (`detour-compose-state-hazards` §1). The camera
 * is fitted to the line only if nothing moved it since the pick framed the
 * destination: a rider who panned away while it loaded keeps their view.
 *
 * A route this preview wrote is dropped when the curvy preset changes, so the
 * km · min shown is for the preset Start will ask with. Any other writer's
 * route (a spin, a seeded ride) is left alone.
 */
@Composable
internal fun MapRoutePreview(
    s: MapScreenState,
    serverConfig: ServerConfig,
    mode: TravelMode,
    fitTo: (List<LatLon>) -> Unit,
) {
    val routingRevision by RoutingServer.revision.collectAsStateWithLifecycle()
    val serverUsable = remember(serverConfig, routingRevision) { RoutingServer.routingUsable(serverConfig) }
    val preset by Settings.curvyPreset.collectAsStateWithLifecycle()
    val origin by rememberUpdatedState(s.myLocation)
    val currentFitTo by rememberUpdatedState(fitTo)
    var landed by remember { mutableStateOf<RouteResult?>(null) }
    var postedError by remember { mutableStateOf<String?>(null) }

    val wanted = routePreviewWanted(
        destination = s.destination,
        guidedTo = s.guidedTo,
        route = s.route,
        guidanceBusy = s.navigating || s.rerouting,
        serverUsable = serverUsable,
        hasOrigin = s.myLocation != null,
    )

    LaunchedEffect(preset) {
        if (landed != null && s.route === landed && !s.navigating) s.route = null
    }

    LaunchedEffect(wanted, s.destination, mode, s.vias, preset, serverConfig) {
        val to = s.destination
        val from = origin
        if (!wanted || to == null || from == null) return@LaunchedEffect
        // A preview's own failure from an earlier pick must not outlive it.
        if (postedError != null && s.error == postedError) s.error = null
        postedError = null
        when (val outcome = askPreview(s, serverConfig, from, to, mode)) {
            null -> Unit
            is PreviewOutcome.Landed -> {
                if (outcome.fit) currentFitTo(outcome.route.polyline)
                landed = outcome.route
            }
            is PreviewOutcome.Failed -> postedError = outcome.text
        }
    }
}

private sealed interface PreviewOutcome {
    data class Landed(val route: RouteResult, val fit: Boolean) : PreviewOutcome
    data class Failed(val text: String) : PreviewOutcome
}

/**
 * One preview request, written into [s] if it is still current when it lands;
 * null when it is not. The camera is re-parked as a framed destination only
 * when nothing moved it while the request was out ([PreviewOutcome.Landed.fit]).
 */
private suspend fun askPreview(
    s: MapScreenState,
    serverConfig: ServerConfig,
    from: LatLon,
    to: LatLon,
    mode: TravelMode,
): PreviewOutcome? {
    val framed = s.camAuthority
    return try {
        val route = fetchStartRoute(serverConfig, from, to, mode, s.vias)
        if (!routePreviewStillCurrent(to, s.destination, s.route, s.navigating || s.rerouting)) return null
        val fit = s.camAuthority == framed && route.polyline.size >= 2
        if (fit) {
            s.camAuthority = CameraAuthority.reduce(
                s.camAuthority,
                CameraAuthority.Action.DestinationFramed(System.currentTimeMillis()),
            )
        }
        s.route = route
        PreviewOutcome.Landed(route, fit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (s.destination != to) return null
        failureText("Route", e).also { s.error = it }.let(PreviewOutcome::Failed)
    }
}

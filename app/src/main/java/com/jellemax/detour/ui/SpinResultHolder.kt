package com.jellemax.detour.ui

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RouteCandidate
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.data.SavedRoute
import com.jellemax.detour.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow

/** The last spin outcome, kept outside `remember` so it survives activity
 *  recreation (rotation, split-screen resize, a backgrounded process losing
 *  just the Activity) — process-scoped, not a substitute for the stores that
 *  already survive process death. MapScreen seeds its `remember`ed state from
 *  this on composition and writes back whenever the result changes. */
// Not private: seedRouteNavigation() below (and RoutesScreen.kt, which calls
// it) need to write into this holder from outside MapScreen's own composition.
internal data class SpinResult(
    val destination: LatLon? = null,
    val destinationName: String? = null,
    val route: RouteResult? = null,
    val candidates: List<RouteCandidate> = emptyList(),
)

internal object SpinResultHolder {
    val state = MutableStateFlow(SpinResult())
}

/**
 * Hands a saved route's final stop to the map as though it were a fresh spin
 * result, so the next time [MapScreen] composes it shows the same "Go"
 * affordances (SpinDock's nav button/menu) a spin result gets — the existing
 * in-app nav path, reused rather than duplicated.
 *
 * Only the destination carries over; [startNavigation] always re-fetches a
 * live two-point route from wherever the user actually is when they tap Go,
 * so a route with stops in between this one and the destination would have
 * them silently dropped. RoutesScreen.kt only calls this for two-stop routes
 * and instead hands longer routes to an external maps app, which can carry
 * real via points.
 */
internal fun seedRouteNavigation(route: SavedRoute) {
    Settings.setTripMode(route.mode)
    val last = route.stops.last()
    SpinResultHolder.state.value = SpinResult(
        destination = last.at,
        destinationName = last.name.ifBlank { route.name },
        route = RouteResult(
            polyline = route.polyline,
            waypoints = emptyList(),
            distanceMeters = route.distanceMeters,
            timeMs = route.timeMs,
        ),
        candidates = emptyList(),
    )
}

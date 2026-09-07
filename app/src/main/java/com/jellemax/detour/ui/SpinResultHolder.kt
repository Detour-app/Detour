package com.jellemax.detour.ui

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RouteCandidate
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.data.SavedRoute
import com.jellemax.detour.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    /**
     * Whether guidance is running along [route].
     *
     * Here rather than in MapScreen's own `remember` because [route] is here:
     * a return to the map that restored the route but not this left the line
     * drawn on the map with the turn banner, the voice and the navigation
     * camera all silently off — a stopped navigation that looks like a running
     * one, mid-ride. Rotation had the same effect, which is the reason the rest
     * of this holder exists.
     */
    val navigating: Boolean = false,
)

internal object SpinResultHolder {
    private val _state = MutableStateFlow(SpinResult())

    /** Read-only on purpose. The flow was public and written directly from two
     *  files with no shared discipline, so a third writer could have published
     *  a half-updated result — a destination without the route it belongs to,
     *  say — and nothing would have flagged it. Both writes now go through the
     *  two functions below, which is also the only place the invariant "route
     *  and navigating travel together" can be stated. */
    val state: StateFlow<SpinResult> = _state.asStateFlow()

    /** The map publishing what a spin, a pick, a cancel or the end of
     *  navigation just produced. */
    fun publish(result: SpinResult) {
        _state.value = result
    }

    /** Seeds a destination with no route and no candidates. Separate from
     *  [publish] because a caller that has only a destination must not have to
     *  invent the other four fields, and the two that tried spelled the empty
     *  cases differently. */
    fun seedDestination(destination: LatLon, name: String, route: RouteResult) {
        _state.value = SpinResult(
            destination = destination,
            destinationName = name,
            route = route,
            candidates = emptyList(),
        )
    }
}

/**
 * Hands a saved route's final stop to the map as though it were a fresh spin
 * result, so the next time [MapScreen] composes it shows the same "Go"
 * affordance a picked destination gets — the existing in-app nav path, reused
 * rather than duplicated.
 *
 * A non-null `destination` takes the bottom slot as the navigation dock
 * (`hasDestination` in `homeBottomCard`, #254), so the seeded stop arrives
 * with its name, its distance/ETA and a Start button. Seeding the holder alone
 * shows a line on a map and nothing to press.
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
    SpinResultHolder.seedDestination(
        destination = last.at,
        name = last.name.ifBlank { route.name },
        route = RouteResult(
            polyline = route.polyline,
            waypoints = emptyList(),
            distanceMeters = route.distanceMeters,
            timeMs = route.timeMs,
        ),
    )
}

package com.jellemax.detour.ui

import com.jellemax.detour.data.FollowedRoute
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RouteCandidate
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.data.SavedRoute
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.ViaStops
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
    /** A planned route's stops still to ride through on the way to
     *  [destination] (#736); empty for everything else. */
    val vias: List<LatLon> = emptyList(),
    /**
     * Where the rider is along [route]'s line through [vias], forward only
     * ([ViaStops.Ride]); null for any other line.
     *
     * Here for the reason [navigating] is: kept only in the screen's
     * `remember`, a rotation or a return to the map lost it, and the next fix
     * started a new [ViaStops.Ride] from metre zero — re-projecting the stops
     * left onto the line's first leg, or, with none left, handing the rider
     * to the global snap, which on a home → store → home line never arrives
     * (#736).
     */
    val ride: ViaStops.Ride? = null,
)

internal object SpinResultHolder {
    private val _state = MutableStateFlow(SpinResult())

    /** Read-only on purpose. The flow was public and written directly from two
     *  files with no shared discipline, so a third writer could have published
     *  a half-updated result — a destination without the route it belongs to,
     *  say — and nothing would have flagged it. Both writes now go through the
     *  two functions below, which is also the only place the invariant "route
     *  and navigating travel together" can be stated — and "a ride is on the
     *  route it travels with", which [publish] and [keepRide] both hold. */
    val state: StateFlow<SpinResult> = _state.asStateFlow()

    /** The map publishing what a spin, a pick, a cancel or the end of
     *  navigation just produced. */
    fun publish(result: SpinResult) {
        val ride = result.ride
        _state.value = if (ride != null && result.route?.polyline !== ride.line) result.copy(ride = null) else result
    }

    /** Seeds a destination with no route and no candidates. Separate from
     *  [publish] because a caller that has only a destination must not have to
     *  invent the other four fields, and the two that tried spelled the empty
     *  cases differently. */
    fun seedDestination(destination: LatLon, name: String, route: RouteResult, vias: List<LatLon>) {
        _state.value = SpinResult(
            destination = destination,
            destinationName = name,
            route = route,
            candidates = emptyList(),
            vias = vias,
        )
    }

    /** The map's [SpinResult.ride] after a fix moved it on. Kept only while
     *  the published route is the line it is on: a ride for a line not
     *  published yet (a reroute the map has not written back) would otherwise
     *  travel with the route it does not belong to, and [publish] brings it
     *  along with its own route anyway. */
    fun keepRide(ride: ViaStops.Ride?) {
        _state.update {
            if (ride == null || it.route?.polyline === ride.line) it.copy(ride = ride) else it
        }
    }

    /** Set by [seedLoop], cleared by the first [takeOpenSheet]. */
    private var openSheet = false

    /** Seeds a routed loop with no destination, and asks the map to open the
     *  spin sheet once: a loop's Go lives there, and a resting map would show
     *  the line with nothing to press (#589). */
    fun seedLoop(route: RouteResult) {
        _state.value = SpinResult(route = route)
        openSheet = true
    }

    /** Whether the map should open the spin sheet for a just-seeded loop.
     *  One-shot, so a later return to the map leaves the sheet as the rider left it. */
    fun takeOpenSheet(): Boolean = openSheet.also { openSheet = false }
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
 * The destination and the stops in between carry over ([ViaStops.of]);
 * [startNavigation] re-fetches a live route from wherever the rider is when
 * they tap Go, through those stops (#736). The route's own first stop is
 * dropped, as it always was for a two-stop route.
 */
internal fun seedRouteNavigation(route: SavedRoute) {
    Settings.setTripMode(route.mode)
    val last = route.stops.last()
    FollowedRoute.offer(route, destination = last.at, route = null)
    SpinResultHolder.seedDestination(
        destination = last.at,
        name = last.name.ifBlank { route.name },
        route = RouteResult(
            polyline = route.polyline,
            waypoints = emptyList(),
            distanceMeters = route.distanceMeters,
            timeMs = route.timeMs,
        ),
        vias = ViaStops.of(route),
    )
}

/**
 * Hands a saved spin loop to the map already routed through its stops (#589),
 * with no destination — the same shape a fresh loop spin leaves — so Go rides
 * [routed]'s own turn instructions instead of re-fetching a two-point route
 * that would drop every via point. [routed] comes from
 * [com.jellemax.detour.data.SavedSpins.loopRoutingPoints], fetched by the caller.
 */
internal fun seedLoopNavigation(route: SavedRoute, routed: RouteResult) {
    Settings.setTripMode(route.mode)
    FollowedRoute.offer(route, destination = null, route = routed)
    SpinResultHolder.seedLoop(routed)
}

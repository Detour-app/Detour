package com.jellemax.detour.map

import com.jellemax.detour.data.HeadingHint
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.data.RoutingClient
import com.jellemax.detour.data.ServerConfig
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.TravelMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What pressing Go should do with the spin result currently on the map. */
sealed interface NavStart {
    /**
     * A round trip that already carries turn instructions — start guidance
     * with the line the spin produced, no request needed.
     */
    data object UseExistingRoute : NavStart

    /** A destination: fetch a fresh line from where the rider actually is. */
    data class FetchTo(val destination: LatLon) : NavStart

    /**
     * A round trip whose loop has no instructions, which happens when the spin
     * fell back to the Overpass sampler because the routing server was down.
     * The line is drawable but unguidable, and saying so names the fix.
     */
    data object NoTurnData : NavStart
}

/**
 * Which of the three cases pressing Go is in.
 *
 * Extracted from `MapScreen.startNavigation()`, where it was an early-return
 * ladder mixed in with a camera dispatch, a service start and a voice reset —
 * so the one branch a rider actually hits when their server is down had no
 * test, and reads identically to the branch that works.
 *
 * A destination always wins over the loop case even when a route is already
 * drawn: the drawn line was built from wherever the spin happened, and the
 * rider may have moved since.
 */
fun navStart(destination: LatLon?, route: RouteResult?): NavStart = when {
    destination != null -> NavStart.FetchTo(destination)
    route?.instructions?.isNotEmpty() == true -> NavStart.UseExistingRoute
    else -> NavStart.NoTurnData
}

/**
 * The point-to-point routing request, in one place.
 *
 * `startNavigation` and the reroute branch of the navigating effect each built
 * this call themselves, with the same six arguments and the same
 * `withContext(Dispatchers.IO)` — two copies of one request, which is two
 * chances for a preference to be passed in one path and forgotten in the
 * other.
 */
suspend fun fetchNavRoute(
    serverConfig: ServerConfig,
    from: LatLon,
    to: LatLon,
    mode: TravelMode,
    /** Set on a reroute so the fresh line continues in the rider's direction of
     *  travel rather than turning them around; null on the initial route. */
    heading: HeadingHint? = null,
): RouteResult = withContext(Dispatchers.IO) {
    RoutingClient.route(
        serverConfig, from, to, mode.ghProfile,
        Settings.avoidHighways.value, Settings.avoidSmallRoads.value,
        heading,
    )
}

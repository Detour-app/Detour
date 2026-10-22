package com.jellemax.detour.map

import android.os.SystemClock
import com.jellemax.detour.data.HeadingHint
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.data.RoutingClient
import com.jellemax.detour.data.ServerConfig
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.tracking.TripTrackingService
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
     * fell back to the backend-sampled loop because the routing server was down.
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
    /** Set when the rider is moving, on Start and on a reroute, so the line
     *  continues in their direction of travel rather than turning them around;
     *  see [NavPolicy.headingHint]. */
    heading: HeadingHint? = null,
): RouteResult = withContext(Dispatchers.IO) {
    RoutingClient.route(
        serverConfig, from, to, mode.ghProfile, Settings.navRoutePreferences(), heading,
    )
}

/**
 * The heading hint for the first route of a session (#719), from the newest fix.
 *
 * Phone Start and the car's search pick both build their first route with it,
 * so a session started on the move continues forward like a reroute would,
 * instead of leaving via an onramp or a turn-around. Fix age is on the platform
 * clock, not the drive clock: it asks how old the fix is, not how far the
 * vehicle went since ([com.jellemax.detour.tracking.DriveClock.predictionNowMs]).
 */
fun startHeading(): HeadingHint? = TripTrackingService.lastFix.value?.let {
    NavPolicy.startHeadingHint(it.bearingDeg, it.speedMps, SystemClock.elapsedRealtime() - it.elapsedRealtimeMs)
}

/** The first route of a session on the phone: [fetchNavRoute] steered by
 *  [startHeading]. Reroutes call [fetchNavRoute] with the fix they decided on. */
suspend fun fetchStartRoute(
    serverConfig: ServerConfig,
    from: LatLon,
    to: LatLon,
    mode: TravelMode,
): RouteResult = fetchNavRoute(serverConfig, from, to, mode, startHeading())

package com.jellemax.detour.map

import com.jellemax.detour.data.NavEngine

/**
 * When a navigation session has arrived, and when it should ask for a fresh
 * route. Pure: values in, one decision out, no clock of its own and no I/O.
 *
 * Two surfaces drive navigation off the same GPS pipeline - the phone map
 * (`ui/MapScreen.kt`'s navigating LaunchedEffect) and Android Auto
 * (`car/NavScreen.kt`'s onFix) - and each carried its own copy of these two
 * tests, the car's under a comment admitting it. Two copies is two chances to
 * get a bound wrong on one surface only.
 */
internal object NavPolicy {

    /** Inside this much remaining route, and still on it, the trip has arrived. */
    const val ARRIVE_METERS = 40.0

    /** How far off the drawn line counts as off route: arrival must be inside
     *  this bound, a reroute outside it. */
    const val OFF_ROUTE_METERS = 60.0

    /** Minimum gap between reroute requests. Both call sites stamp on request
     *  rather than on success, so a failed reroute is retried after the cooldown
     *  rather than immediately. */
    const val REROUTE_COOLDOWN_MS = 15_000L

    /**
     * Near enough the drawn line that the route's own geometry is better
     * evidence of where the rider is, and which way they are pointing, than the
     * raw fix: the marker is drawn on the snapped point and the camera takes
     * the segment's bearing (`ui/MapScreen.kt`'s marker loop).
     *
     * A null [progress] is *not* on route — there is no snap to draw yet, so
     * the fix is all there is. `navStateFrom`'s off-route flag defaults the
     * other way round, a null reading as on route, because it is answering
     * "shall I put Off route on the banner", and doing that before the first
     * fix would be a lie. Two questions, two defaults; they are not each
     * other's complement.
     *
     * `car/NavScreen.kt`'s private `offRoute` is the same bound, phrased for a
     * Progress it always has. Pointing it here is a car-side change and belongs
     * with the head unit adopting the snapped point.
     */
    fun onRoute(progress: NavEngine.Progress?): Boolean =
        progress != null && progress.offRouteMeters <= OFF_ROUTE_METERS

    sealed interface Decision {
        /** Keep following the line that is already drawn. */
        data object Continue : Decision
        /** Arrived: end the session. */
        data object Arrived : Decision
        /** Off route and out of cooldown: fetch a fresh route to the destination. */
        data object Reroute : Decision
    }

    /**
     * [hasDestination] is false for a round trip, which has nothing to arrive at
     * and nothing to reroute to. The phone passes `destination != null`; the
     * car's destination is a constructor parameter and so always present.
     *
     * [nowMs] and [lastRerouteMs] are wall-clock millis - the caller owns the
     * clock, which is what keeps this testable.
     *
     * Arrival is tested first, matching both call sites. The order cannot change
     * an outcome: the two branches are mutually exclusive on [NavEngine.Progress.offRouteMeters],
     * arrival needing it under [OFF_ROUTE_METERS] and a reroute needing it over.
     */
    fun decide(
        progress: NavEngine.Progress,
        hasDestination: Boolean,
        rerouting: Boolean,
        lastRerouteMs: Long,
        nowMs: Long,
    ): Decision {
        if (!hasDestination) return Decision.Continue
        if (progress.remainingMeters < ARRIVE_METERS &&
            progress.offRouteMeters < OFF_ROUTE_METERS
        ) {
            return Decision.Arrived
        }
        if (progress.offRouteMeters > OFF_ROUTE_METERS &&
            !rerouting &&
            nowMs - lastRerouteMs > REROUTE_COOLDOWN_MS
        ) {
            return Decision.Reroute
        }
        return Decision.Continue
    }
}

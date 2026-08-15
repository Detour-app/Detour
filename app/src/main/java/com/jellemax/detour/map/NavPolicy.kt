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

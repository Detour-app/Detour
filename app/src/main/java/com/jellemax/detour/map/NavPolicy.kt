package com.jellemax.detour.map

import com.jellemax.detour.data.HeadingHint
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
 *
 * App-only although nothing here is Android: iOS navigation has no reroute
 * policy or heading hint yet (it sends `heading = nil`), so there is no second
 * caller to share with. The heading rules ([headingHint], [startHeadingHint])
 * stay next to [rerouteHeadingPenaltySec], and the object moves to `commonMain`
 * whole, with its tests, when iOS gains one.
 */
internal object NavPolicy {

    /** Inside this much remaining route, and still on it, the trip has arrived. */
    const val ARRIVE_METERS = 40.0

    /** How far off the drawn line counts as off route: arrival must be inside
     *  this bound, a reroute outside it. */
    const val OFF_ROUTE_METERS = 60.0

    /** A fix counts as off route only past this many times its own reported
     *  accuracy (when that is more than [OFF_ROUTE_METERS]): a 70 m fix claiming
     *  ±80 m says nothing about which road the rider is on. */
    const val OFF_ROUTE_ACCURACY_FACTOR = 1.5

    /** Consecutive off-route fixes that confirm the rider has left the line
     *  (#1008). One multipath jump in a built-up area is one fix. */
    const val OFF_ROUTE_CONFIRM_FIXES = 3

    /** ...or this long off route on the drive clock, whichever comes first, so
     *  a sparse fix stream still reroutes in bounded time. */
    const val OFF_ROUTE_CONFIRM_MS = 4_000L

    /** Minimum gap between reroute requests. Both call sites stamp on request
     *  rather than on success, so a failed reroute is retried after the cooldown
     *  rather than immediately. Doubles per failure up to
     *  [REROUTE_MAX_COOLDOWN_MS] ([rerouteCooldownMs], #1010). */
    const val REROUTE_COOLDOWN_MS = 15_000L

    /** The longest [rerouteCooldownMs] gets: 15 s, 30 s, then 60 s for as long
     *  as requests keep failing. Offline, each attempt is a request left to
     *  time out; past a minute the rider is better served by the old line. */
    const val REROUTE_MAX_COOLDOWN_MS = 60_000L

    /** Below this speed GPS course is noise, so no request carries a heading. */
    const val HEADING_MIN_SPEED_MPS = 2.0

    /**
     * Oldest fix whose course may steer the *first* route of a session.
     *
     * A reroute is decided on the fix that just arrived; Start is pressed
     * whenever, and [com.jellemax.detour.tracking.TripTrackingService.lastFix]
     * is never cleared, so the newest fix can be from a drive that ended an hour
     * ago. Steering a fresh route by that course would be worse than sending none.
     * 10 s tolerates a few missed 1 Hz fixes; past it the request goes out bare,
     * which is what every initial route did before #719.
     */
    const val START_HEADING_MAX_AGE_MS = 10_000L

    /**
     * The heading hint for a route request from a rider moving at [speedMps] on
     * course [bearingDeg]: null while parked or before GPS has a course.
     *
     * Reroutes and the initial route both use it (#719), on phone and car, so
     * the line a session opens with never leaves via an onramp or a turn-around
     * the first reroute would have avoided.
     */
    fun headingHint(bearingDeg: Float?, speedMps: Double): HeadingHint? =
        bearingDeg?.takeIf { speedMps >= HEADING_MIN_SPEED_MPS }
            ?.let { HeadingHint(it.toDouble(), rerouteHeadingPenaltySec(speedMps)) }

    /** [headingHint] for the first route of a session, from a fix [fixAgeMs]
     *  old: none once the fix is past [START_HEADING_MAX_AGE_MS]. */
    fun startHeadingHint(bearingDeg: Float?, speedMps: Double, fixAgeMs: Long): HeadingHint? =
        if (fixAgeMs > START_HEADING_MAX_AGE_MS) null else headingHint(bearingDeg, speedMps)

    /**
     * `heading_penalty` (seconds) for a route request, by current speed.
     *
     * A request made while moving (a reroute, or Start pressed on the move) passes
     * the rider's GPS course to the router as a heading hint so the line continues
     * forward instead of turning them around; this is how hard the router is
     * pushed to obey it. Slow enough to stop safely and a
     * genuine U-turn can still win when it saves real distance. On a 70/90 km/h
     * road a turn-around is unusable, so the penalty there is large enough that
     * GraphHopper only takes one when there is no other way back to the route.
     *
     * 8 m/s ≈ 29 km/h, 17 m/s ≈ 61 km/h. GraphHopper's own default is 300.
     */
    fun rerouteHeadingPenaltySec(speedMps: Double): Int = when {
        speedMps < 8.0 -> 120
        speedMps < 17.0 -> 300
        else -> 1200
    }

    /**
     * Whether to draw the rider on the route's geometry rather than at the raw
     * fix: the marker on the snapped point, the camera on that point and on the
     * segment's bearing (`ui/MapScreen.kt`'s marker loop).
     *
     * [offRouteMeters] is [NavEngine.Along.offRouteMeters] — the *windowed*
     * snap's distance, not [NavEngine.Progress.offRouteMeters]. The per-fix
     * figure measures to the globally nearest point on the route, which on an
     * out-and-back is the other leg; deciding on that parks the marker on a
     * road the rider left half an hour ago.
     *
     * **Two thresholds, because one flips.** A single 60 m test with a fix
     * sitting on it moves the marker up to 60 m and back once a second, and it
     * does that for at least [REROUTE_COOLDOWN_MS] before a fresh route can
     * end it. So: take the snap only when comfortably on the line, and keep it
     * until properly off. The upper bound is [OFF_ROUTE_METERS], because past
     * that [decide] is asking for a new route and this one is not worth
     * drawing. The lower is [ARRIVE_METERS] — borrowed as a magnitude, not as
     * the same quantity (it measures distance *along* the route), so that this
     * file gains no third number to keep in step. The 20 m band it leaves is
     * wider than the error a snap has to tolerate and narrower than the
     * reroute bound, so entering never races the decision to leave.
     *
     * [wasSnapped] is the caller's own last answer. Pure, so the state stays
     * where it can be seen rather than inside this object.
     */
    fun snapToRoute(offRouteMeters: Double, wasSnapped: Boolean): Boolean =
        if (wasSnapped) offRouteMeters <= OFF_ROUTE_METERS else offRouteMeters <= ARRIVE_METERS

    /**
     * The run of off-route fixes the caller is in, held by the caller between
     * fixes the way [snapToRoute]'s `wasSnapped` is; null while on route.
     *
     * [fixes] counts distinct fixes, [sinceMs] is the drive clock at the first,
     * [lastFixKey] identifies the newest so a caller that re-evaluates the same
     * fix (the phone's effect restarts on a route change too, not only on a new
     * fix) does not count it twice.
     */
    data class OffRouteStreak(val fixes: Int, val sinceMs: Long, val lastFixKey: Long)

    /**
     * The next [OffRouteStreak] after a fix [offRouteMeters] from the line with
     * reported [accuracyMeters]: null once a fix is back on route, else [prev]
     * extended by this fix. Off route means past [OFF_ROUTE_METERS] and past
     * [OFF_ROUTE_ACCURACY_FACTOR] times the fix's accuracy (#1008).
     *
     * [fixKey] is anything unique per fix (its elapsed-realtime stamp); [nowMs]
     * is the same drive clock [decide] gets.
     */
    fun trackOffRoute(
        prev: OffRouteStreak?,
        offRouteMeters: Double,
        accuracyMeters: Double,
        fixKey: Long,
        nowMs: Long,
    ): OffRouteStreak? {
        val bound = maxOf(OFF_ROUTE_METERS, OFF_ROUTE_ACCURACY_FACTOR * accuracyMeters)
        return when {
            offRouteMeters <= bound -> null
            prev == null -> OffRouteStreak(fixes = 1, sinceMs = nowMs, lastFixKey = fixKey)
            prev.lastFixKey == fixKey -> prev
            else -> prev.copy(fixes = prev.fixes + 1, lastFixKey = fixKey)
        }
    }

    /** Whether [streak] has lasted long enough to act on: [OFF_ROUTE_CONFIRM_FIXES]
     *  fixes, or [OFF_ROUTE_CONFIRM_MS] on the drive clock. */
    fun offRouteConfirmed(streak: OffRouteStreak?, nowMs: Long): Boolean =
        streak != null &&
            (streak.fixes >= OFF_ROUTE_CONFIRM_FIXES || nowMs - streak.sinceMs >= OFF_ROUTE_CONFIRM_MS)

    /**
     * The caller's record of reroute requests in the current off-route
     * episode (#1010), held between fixes like [OffRouteStreak].
     *
     * [lastMs] is the drive clock at the newest request (0 before the first,
     * which [decide] treats as long ago). [failures] counts requests in this
     * episode that came back without a route; it sets [rerouteCooldownMs] and
     * mutes "Rerouting" after the first attempt ([announce]). [failureText] is
     * the newest failure in rider words, for the banner; null until one fails.
     *
     * An episode ends when a fix is back on route ([afterFix]), a request
     * succeeds ([succeeded]) or navigation stops ([endEpisode]); [lastMs]
     * survives, so the cooldown still runs from the last request.
     */
    data class RerouteAttempts(
        val lastMs: Long = 0L,
        val failures: Int = 0,
        val failureText: String? = null,
    ) {
        /** Speak "Rerouting" for this request: only the first of an episode,
         *  so an offline rider is not told it every retry. */
        val announce: Boolean get() = failures == 0

        /** A request went out at [nowMs]. */
        fun started(nowMs: Long): RerouteAttempts = copy(lastMs = nowMs)

        /** The request came back without a route; [text] says why. */
        fun failed(text: String): RerouteAttempts = copy(failures = failures + 1, failureText = text)

        /** A fresh route arrived: the episode is over. */
        fun succeeded(): RerouteAttempts = endEpisode()

        /** The next record after a fix whose [trackOffRoute] result is
         *  [offRoute]: the episode ends once a fix is back on route. */
        fun afterFix(offRoute: OffRouteStreak?): RerouteAttempts = if (offRoute == null) endEpisode() else this

        /** Back to no failures, keeping [lastMs]. */
        fun endEpisode(): RerouteAttempts =
            if (failures == 0 && failureText == null) this else RerouteAttempts(lastMs = lastMs)
    }

    /** Gap before the next reroute request after [failures] failed ones in a
     *  row: [REROUTE_COOLDOWN_MS], doubled per failure, capped at
     *  [REROUTE_MAX_COOLDOWN_MS]. */
    fun rerouteCooldownMs(failures: Int): Long =
        (REROUTE_COOLDOWN_MS shl failures.coerceIn(0, MAX_BACKOFF_SHIFT)).coerceAtMost(REROUTE_MAX_COOLDOWN_MS)

    /** Enough doublings to pass any sane cap without overflowing the shift. */
    private const val MAX_BACKOFF_SHIFT = 8

    sealed interface Decision {
        /** Keep following the line that is already drawn. */
        data object Continue : Decision
        /** Arrived: end the session. */
        data object Arrived : Decision
        /** Confirmed off route and out of cooldown ([rerouteCooldownMs]): fetch
         *  a fresh route to the destination. */
        data object Reroute : Decision
    }

    /**
     * [hasDestination] is false for a round trip, which has nothing to arrive at
     * and nothing to reroute to. The phone passes `destination != null`; the
     * car's destination is a constructor parameter and so always present.
     *
     * [stopsAhead] is true while a planned route still has a stop to ride
     * through (#736): no arrival until the last one is passed. The phone reads
     * remaining distance off a forward-only snap on such a line, so a route
     * that ends where it starts does not open near its end; but a snap that
     * lost the rider (a gap, the screen recreated) searches onward and can
     * land on the way back, and remaining distance alone would then arrive
     * before the stop. The car doesn't ride via stops and passes false.
     *
     * [offRoute] is the caller's [trackOffRoute] result *for this same fix*: a
     * reroute needs it confirmed ([offRouteConfirmed]), not one fix past the
     * bound (#1008).
     *
     * [attempts] is the caller's [RerouteAttempts]: its [RerouteAttempts.lastMs]
     * and [nowMs] are drive-clock millis - the caller owns the clock, which is
     * what keeps this testable - and its failure count sets the cooldown.
     *
     * Arrival is tested first, matching both call sites. The order cannot change
     * an outcome: the two branches are mutually exclusive on [NavEngine.Progress.offRouteMeters],
     * arrival needing it under [OFF_ROUTE_METERS] and a reroute needing a streak
     * whose newest fix (this one) is past it.
     *
     * Seven parameters, one over detekt's bound: each is an independent input
     * the caller already holds, and bundling [offRoute], [rerouting] and
     * [attempts] into a holder would only rename the coupling (§8.4). The
     * state stays with the caller so this stays pure, as [snapToRoute] does.
     */
    @Suppress("LongParameterList")
    fun decide(
        progress: NavEngine.Progress,
        hasDestination: Boolean,
        stopsAhead: Boolean,
        offRoute: OffRouteStreak?,
        rerouting: Boolean,
        attempts: RerouteAttempts,
        nowMs: Long,
    ): Decision {
        if (!hasDestination) return Decision.Continue
        if (!stopsAhead &&
            progress.remainingMeters < ARRIVE_METERS &&
            progress.offRouteMeters < OFF_ROUTE_METERS
        ) {
            return Decision.Arrived
        }
        if (offRouteConfirmed(offRoute, nowMs) &&
            !rerouting &&
            nowMs - attempts.lastMs > rerouteCooldownMs(attempts.failures)
        ) {
            return Decision.Reroute
        }
        return Decision.Continue
    }
}

package com.jellemax.detour.map

import com.jellemax.detour.data.HeadingHint
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.NavEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [NavPolicy.decide] - the arrival and reroute rules the phone map and
 * the Android Auto screen share. Getting `<` versus `<=` wrong here means
 * either arriving a street early or never arriving at all, and until this file
 * neither surface had a test: both carried their own copy of the arithmetic.
 * No Android APIs involved, so no emulator/Robolectric needed.
 */
class NavPolicyTest {

    /** Only the two fields [NavPolicy.decide] reads carry meaning here; the rest
     *  are whatever a Progress needs in order to exist. */
    private fun progress(remainingMeters: Double, offRouteMeters: Double) = NavEngine.Progress(
        offRouteMeters = offRouteMeters,
        snappedAt = LatLon(50.0, 3.0),
        segmentBearingDeg = null,
        nextInstruction = null,
        distanceToTurnMeters = remainingMeters,
        remainingMeters = remainingMeters,
        routeMeters = 10_000.0,
        remainingTimeMs = null,
        speedLimitKmh = null,
    )

    /** The streak a caller holds after this fix, given it was already
     *  [NavPolicy.OFF_ROUTE_CONFIRM_FIXES] fixes off route: the rules below are
     *  about the bound and the cooldown, not about confirming (#1008), so they
     *  see a confirmed run whenever this fix is itself off route. */
    private fun streakAfter(offRouteMeters: Double, nowMs: Long) = NavPolicy.trackOffRoute(
        prev = NavPolicy.OffRouteStreak(NavPolicy.OFF_ROUTE_CONFIRM_FIXES, sinceMs = nowMs, lastFixKey = 0L),
        offRouteMeters = offRouteMeters,
        accuracyMeters = 5.0,
        fixKey = 1L,
        nowMs = nowMs,
    )

    /** A destination the rider is far from and moving towards: the parked
     *  arrival (#1011) stays out of the rules that aren't about it. */
    private val enRoute = NavPolicy.ToDestination(straightLineMeters = 5_000.0, stoppedSinceMs = null)

    private fun decide(
        remainingMeters: Double,
        offRouteMeters: Double,
        destination: NavPolicy.ToDestination? = enRoute,
        rerouting: Boolean = false,
        attempts: NavPolicy.RerouteAttempts = NavPolicy.RerouteAttempts(),
        nowMs: Long = 1_000_000L,
    ) = NavPolicy.decide(
        progress = progress(remainingMeters, offRouteMeters),
        destination = destination,
        stopsAhead = false,
        offRoute = streakAfter(offRouteMeters, nowMs),
        rerouting = rerouting,
        attempts = attempts,
        nowMs = nowMs,
    )

    @Test
    fun arrivesWhenCloseToTheEndAndStillOnTheLine() {
        assertEquals(NavPolicy.Decision.Arrived, decide(remainingMeters = 10.0, offRouteMeters = 5.0))
    }

    /** #736: a planned route that ends where it starts snaps the first fix onto
     *  its last segment, a few metres from the end. With a stop still ahead
     *  that is not an arrival. */
    @Test
    fun doesNotArriveWithAStopStillAhead() {
        assertEquals(
            NavPolicy.Decision.Continue,
            decideWithStopAhead(remainingMeters = 10.0, offRouteMeters = 5.0),
        )
    }

    /** The helper's other defaults, with a planned stop still ahead (#736). */
    private fun decideWithStopAhead(remainingMeters: Double, offRouteMeters: Double) = NavPolicy.decide(
        progress = progress(remainingMeters, offRouteMeters),
        destination = enRoute,
        stopsAhead = true,
        offRoute = streakAfter(offRouteMeters, 1_000_000L),
        rerouting = false,
        attempts = NavPolicy.RerouteAttempts(),
        nowMs = 1_000_000L,
    )

    /** A stop ahead holds arrival only; leaving the line still reroutes. */
    @Test
    fun reroutesWithAStopStillAhead() {
        assertEquals(
            NavPolicy.Decision.Reroute,
            decideWithStopAhead(remainingMeters = 10.0, offRouteMeters = 200.0),
        )
    }

    /** Arrival needs *both* bounds. Ten metres of route left while 200 m off the
     *  line is a parallel road, not a destination - and it is a reroute. */
    @Test
    fun doesNotArriveWhileOffTheLine() {
        assertEquals(NavPolicy.Decision.Reroute, decide(remainingMeters = 10.0, offRouteMeters = 200.0))
    }

    @Test
    fun doesNotArriveWhileStillFarFromTheEnd() {
        assertEquals(NavPolicy.Decision.Continue, decide(remainingMeters = 500.0, offRouteMeters = 5.0))
    }

    /** A round trip has no destination: it ends back where it started, and
     *  rerouting one would change the whole ride. Neither rule may fire however
     *  close the end of the line is. */
    @Test
    fun neverArrivesOrReroutesWithoutADestination() {
        assertEquals(
            NavPolicy.Decision.Continue,
            decide(remainingMeters = 1.0, offRouteMeters = 1.0, destination = null),
        )
        assertEquals(
            NavPolicy.Decision.Continue,
            decide(remainingMeters = 5_000.0, offRouteMeters = 500.0, destination = null),
        )
    }

    /** The boundary, stated: exactly [NavPolicy.ARRIVE_METERS] remaining does
     *  not arrive, because the test is `<`. */
    @Test
    fun exactlyTheArrivalRadiusDoesNotArrive() {
        assertEquals(
            NavPolicy.Decision.Continue,
            decide(remainingMeters = NavPolicy.ARRIVE_METERS, offRouteMeters = 5.0),
        )
        assertEquals(
            NavPolicy.Decision.Arrived,
            decide(remainingMeters = NavPolicy.ARRIVE_METERS - 0.01, offRouteMeters = 5.0),
        )
    }

    /** Exactly on the off-route bound is a dead band by construction: arrival
     *  needs `offRouteMeters <` it and a reroute needs `>` it, so 60.0 exactly
     *  does neither. Pinned because collapsing the two comparisons into one
     *  would look like a tidy-up and would change what happens at the bound. */
    @Test
    fun exactlyTheOffRouteBoundNeitherArrivesNorReroutes() {
        assertEquals(
            NavPolicy.Decision.Continue,
            decide(remainingMeters = 10.0, offRouteMeters = NavPolicy.OFF_ROUTE_METERS),
        )
    }

    @Test
    fun reroutesOncePastTheOffRouteBound() {
        assertEquals(
            NavPolicy.Decision.Reroute,
            decide(remainingMeters = 2_000.0, offRouteMeters = NavPolicy.OFF_ROUTE_METERS + 0.01),
        )
    }

    /** A request already in flight is not asked for again. Both call sites clear
     *  the flag in a `finally`, so a failed reroute re-arms on the next fix -
     *  gated from then on only by the cooldown. */
    @Test
    fun doesNotRerouteWhileOneIsInFlight() {
        assertEquals(
            NavPolicy.Decision.Continue,
            decide(remainingMeters = 2_000.0, offRouteMeters = 300.0, rerouting = true),
        )
    }

    @Test
    fun respectsTheRerouteCooldown() {
        val last = 1_000_000L
        val once = NavPolicy.RerouteAttempts(lastMs = last)
        assertEquals(
            NavPolicy.Decision.Continue,
            decide(2_000.0, 300.0, attempts = once, nowMs = last + NavPolicy.REROUTE_COOLDOWN_MS),
        )
        assertEquals(
            NavPolicy.Decision.Reroute,
            decide(2_000.0, 300.0, attempts = once, nowMs = last + NavPolicy.REROUTE_COOLDOWN_MS + 1),
        )
    }

    /** Both call sites start `RerouteAttempts.lastMs` at 0 (`MapScreen.kt:228`,
     *  `car/NavScreen.kt:112`), so the first reroute of a session must not be
     *  held off by a cooldown measured from the epoch. */
    @Test
    fun theFirstRerouteOfASessionIsNotBlocked() {
        assertEquals(
            NavPolicy.Decision.Reroute,
            decide(2_000.0, 300.0, nowMs = 1_700_000_000_000L),
        )
    }

    /** The band the marker and the camera read. Entering needs the rider
     *  properly on the line; leaving needs them properly off it, and the upper
     *  bound is [NavPolicy.decide]'s, so the snap is dropped no later than the
     *  reroute that replaces the line it was taken from. */
    @Test
    fun theSnapBandTakesTheRouteLateAndGivesItUpLate() {
        // Not yet snapped: the enter bound governs, and the gap between the two
        // thresholds is where a single-threshold test would have flipped.
        assertTrue(NavPolicy.snapToRoute(NavPolicy.ARRIVE_METERS, wasSnapped = false))
        assertFalse(NavPolicy.snapToRoute(NavPolicy.ARRIVE_METERS + 0.1, wasSnapped = false))
        assertFalse(NavPolicy.snapToRoute(50.0, wasSnapped = false))
        // Already snapped: the same 50 m keeps it, and only the reroute bound
        // gives it up.
        assertTrue(NavPolicy.snapToRoute(50.0, wasSnapped = true))
        assertTrue(NavPolicy.snapToRoute(NavPolicy.OFF_ROUTE_METERS, wasSnapped = true))
        assertFalse(NavPolicy.snapToRoute(NavPolicy.OFF_ROUTE_METERS + 0.1, wasSnapped = true))
    }

    /** The band exists so a fix parked on one threshold cannot flip the answer
     *  fix after fix. Sitting anywhere inside it, the answer is whatever it
     *  already was — which is the whole point, and what a single bound could
     *  not give. */
    @Test
    fun aFixSittingInTheBandDoesNotFlipTheMarker() {
        val inBand = (NavPolicy.ARRIVE_METERS + NavPolicy.OFF_ROUTE_METERS) / 2.0
        assertTrue(NavPolicy.ARRIVE_METERS < NavPolicy.OFF_ROUTE_METERS)
        repeat(5) {
            assertFalse(NavPolicy.snapToRoute(inBand, wasSnapped = false))
            assertTrue(NavPolicy.snapToRoute(inBand, wasSnapped = true))
        }
    }

    /** The reroute heading penalty rises with speed: slow enough to stop and a
     *  genuine U-turn can still win; at 70/90-road speed the router only turns
     *  the rider around when there is no other way back to the line. */
    @Test
    fun rerouteHeadingPenaltyRisesWithSpeed() {
        // City crawl: modest penalty.
        assertEquals(120, NavPolicy.rerouteHeadingPenaltySec(5.0))
        // ~50 km/h arterial: GraphHopper's own default.
        assertEquals(300, NavPolicy.rerouteHeadingPenaltySec(13.0))
        // ~70 km/h and up: turn-arounds effectively forbidden.
        assertEquals(1200, NavPolicy.rerouteHeadingPenaltySec(20.0))
        // Monotonic, and the boundaries are on the lower tier (`<`).
        assertEquals(120, NavPolicy.rerouteHeadingPenaltySec(0.0))
        assertEquals(300, NavPolicy.rerouteHeadingPenaltySec(8.0))
        assertEquals(1200, NavPolicy.rerouteHeadingPenaltySec(17.0))
    }

    /** A hint needs a course and real movement: parked or before GPS has a
     *  course, the request goes out bare (#719, same guard as the reroute). */
    @Test
    fun headingHintNeedsCourseAndSpeed() {
        assertEquals(HeadingHint(90.0, 1200), NavPolicy.headingHint(90f, 25.0))
        assertEquals(HeadingHint(270.0, 120), NavPolicy.headingHint(270f, 2.0))
        assertNull(NavPolicy.headingHint(90f, 1.9))
        assertNull(NavPolicy.headingHint(null, 25.0))
    }

    /** Start is pressed whenever, and lastFix is never cleared: a course from a
     *  fix past the age bound must not steer the first route. */
    @Test
    fun startHeadingHintDropsStaleFix() {
        val bound = NavPolicy.START_HEADING_MAX_AGE_MS
        assertEquals(HeadingHint(45.0, 1200), NavPolicy.startHeadingHint(45f, 30.0, fixAgeMs = 500))
        assertEquals(HeadingHint(45.0, 1200), NavPolicy.startHeadingHint(45f, 30.0, fixAgeMs = bound))
        assertNull(NavPolicy.startHeadingHint(45f, 30.0, fixAgeMs = bound + 1))
        assertNull(NavPolicy.startHeadingHint(45f, 0.5, fixAgeMs = 500))
    }

    // ---- #1008: one inaccurate fix must not reroute ------------------------

    /** Runs fixes one second apart through [NavPolicy.trackOffRoute] and
     *  [NavPolicy.decide] as a caller does, and returns each decision. */
    private fun ride(vararg fixes: Pair<Double, Double>, stepMs: Long = 1_000L): List<NavPolicy.Decision> {
        var streak: NavPolicy.OffRouteStreak? = null
        return fixes.mapIndexed { i, (offRouteMeters, accuracyMeters) ->
            val now = 1_000_000L + i * stepMs
            streak = NavPolicy.trackOffRoute(streak, offRouteMeters, accuracyMeters, fixKey = i.toLong(), nowMs = now)
            NavPolicy.decide(
                progress = progress(2_000.0, offRouteMeters),
                destination = enRoute,
                stopsAhead = false,
                offRoute = streak,
                rerouting = false,
                attempts = NavPolicy.RerouteAttempts(),
                nowMs = now,
            )
        }
    }

    /** The issue's case: a straight road, one 90 m multipath jump. */
    @Test
    fun oneOutlierOnAStraightRoadDoesNotReroute() {
        val decisions = ride(3.0 to 5.0, 4.0 to 5.0, 90.0 to 10.0, 3.0 to 5.0, 5.0 to 5.0, 2.0 to 5.0)
        assertTrue(decisions.all { it == NavPolicy.Decision.Continue })
    }

    /** Two outliers in a row are still short of the three-fix bar. */
    @Test
    fun twoOffFixesInARowDoNotReroute() {
        val decisions = ride(90.0 to 10.0, 95.0 to 10.0, 3.0 to 5.0)
        assertTrue(decisions.all { it == NavPolicy.Decision.Continue })
    }

    @Test
    fun theThirdConsecutiveOffFixReroutes() {
        assertEquals(
            listOf(NavPolicy.Decision.Continue, NavPolicy.Decision.Continue, NavPolicy.Decision.Reroute),
            ride(90.0 to 10.0, 120.0 to 10.0, 150.0 to 10.0),
        )
    }

    /** A sparse fix stream: two fixes [NavPolicy.OFF_ROUTE_CONFIRM_MS] apart
     *  confirm it on time rather than on count. */
    @Test
    fun fourSecondsOffRouteRerouteOnFewerFixes() {
        assertEquals(
            listOf(NavPolicy.Decision.Continue, NavPolicy.Decision.Reroute),
            ride(90.0 to 10.0, 120.0 to 10.0, stepMs = NavPolicy.OFF_ROUTE_CONFIRM_MS),
        )
    }

    /** A fix 80 m off that itself claims ±80 m is inside its own error. */
    @Test
    fun aFixWithinItsOwnAccuracyIsNotOffRoute() {
        fun first(offRouteMeters: Double, accuracyMeters: Double) =
            NavPolicy.trackOffRoute(null, offRouteMeters, accuracyMeters, fixKey = 1L, nowMs = 0L)
        assertNull(first(offRouteMeters = 80.0, accuracyMeters = 80.0))
        assertNull(first(offRouteMeters = 120.0, accuracyMeters = 80.0))
        assertEquals(1, first(offRouteMeters = 121.0, accuracyMeters = 80.0)?.fixes)
        // Accurate fixes keep the fixed bound.
        assertNull(first(NavPolicy.OFF_ROUTE_METERS, accuracyMeters = 3.0))
    }

    /** The phone's effect also restarts on a new route with the same fix; that
     *  must not count as a second fix. */
    @Test
    fun theSameFixSeenTwiceCountsOnce() {
        val first = NavPolicy.trackOffRoute(null, 90.0, 10.0, fixKey = 7L, nowMs = 0L)
        val again = NavPolicy.trackOffRoute(first, 90.0, 10.0, fixKey = 7L, nowMs = 10L)
        assertEquals(first, again)
        assertFalse(NavPolicy.offRouteConfirmed(NavPolicy.trackOffRoute(again, 90.0, 10.0, 8L, 20L), 20L))
    }

    // ---- #1011: parked short of the destination ----------------------------

    private val now = 1_000_000L

    /** Stopped [stoppedMs] on the drive clock, [meters] straight-line from the destination. */
    private fun parked(meters: Double, stoppedMs: Long) =
        NavPolicy.ToDestination(straightLineMeters = meters, stoppedSinceMs = now - stoppedMs)

    /** The issue's case: stopped in the car park 60 m short of the route's end,
     *  80 m from the shop itself. The end-of-line rule never fires there. */
    @Test
    fun arrivesWhenParkedNearTheDestination() {
        assertEquals(
            NavPolicy.Decision.Arrived,
            decide(remainingMeters = 60.0, offRouteMeters = 20.0, destination = parked(80.0, 15_000L)),
        )
    }

    /** Off the line in a car park beside the road: arriving beats asking for
     *  a fresh route to a point 100 m away. */
    @Test
    fun parkedArrivalWinsOverAConfirmedReroute() {
        assertEquals(
            NavPolicy.Decision.Arrived,
            decide(remainingMeters = 60.0, offRouteMeters = 120.0, destination = parked(100.0, 20_000L)),
        )
    }

    /** A red light or a give-way near the destination is shorter than the wait. */
    @Test
    fun aShortStopNearTheDestinationDoesNotArrive() {
        assertEquals(
            NavPolicy.Decision.Continue,
            decide(remainingMeters = 60.0, offRouteMeters = 20.0, destination = parked(80.0, 14_999L)),
        )
    }

    /** Stopped for a long time, but further than [NavPolicy.PARKED_ARRIVE_METERS]
     *  from the destination itself: a jam, not an arrival. */
    @Test
    fun aLongStopFarFromTheDestinationDoesNotArrive() {
        assertEquals(
            NavPolicy.Decision.Continue,
            decide(remainingMeters = 60.0, offRouteMeters = 20.0, destination = parked(150.1, 600_000L)),
        )
        assertEquals(
            NavPolicy.Decision.Arrived,
            decide(remainingMeters = 60.0, offRouteMeters = 20.0, destination = parked(150.0, 15_000L)),
        )
    }

    /** #736 still holds: a planned stop ahead blocks the parked arrival too. */
    @Test
    fun parkedNearTheDestinationWithAStopAheadDoesNotArrive() {
        val decision = NavPolicy.decide(
            progress = progress(2_000.0, 5.0),
            destination = parked(50.0, 60_000L),
            stopsAhead = true,
            offRoute = null,
            rerouting = false,
            attempts = NavPolicy.RerouteAttempts(),
            nowMs = now,
        )
        assertEquals(NavPolicy.Decision.Continue, decision)
    }

    @Test
    fun trackStoppedStartsKeepsAndEndsARun() {
        assertNull(NavPolicy.trackStopped(prevSinceMs = null, speedMps = NavPolicy.PARKED_SPEED_MPS, nowMs = 10L))
        assertEquals(10L, NavPolicy.trackStopped(prevSinceMs = null, speedMps = 0.4, nowMs = 10L))
        assertEquals(10L, NavPolicy.trackStopped(prevSinceMs = 10L, speedMps = 1.0, nowMs = 9_000L))
        assertNull(NavPolicy.trackStopped(prevSinceMs = 10L, speedMps = 3.0, nowMs = 9_000L))
    }

    /** Creeping up to the bay and stopping, fix by fix, as a caller steps it. */
    @Test
    fun aRideIntoTheCarParkArrivesFifteenSecondsAfterStopping() {
        var since: Long? = null
        val speeds = listOf(8.0, 4.0, 2.0) + List(20) { 0.3 }
        val decisions = speeds.mapIndexed { i, speed ->
            val t = now + i * 1_000L
            since = NavPolicy.trackStopped(since, speed, t)
            NavPolicy.decide(
                progress = progress(70.0, 15.0),
                destination = NavPolicy.ToDestination(90.0, since),
                stopsAhead = false,
                offRoute = null,
                rerouting = false,
                attempts = NavPolicy.RerouteAttempts(),
                nowMs = t,
            )
        }
        // First stopped fix is index 3; 15 s later is index 18.
        assertEquals(18, decisions.indexOfFirst { it == NavPolicy.Decision.Arrived })
    }

    // --- #1010: offline, back off and stop saying "Rerouting" ---------------

    @Test
    fun cooldownDoublesPerFailureUpToAMinute() {
        assertEquals(
            listOf(15_000L, 30_000L, 60_000L, 60_000L, 60_000L),
            listOf(0, 1, 2, 3, 40).map(NavPolicy::rerouteCooldownMs),
        )
    }

    /** After two failures the next request waits 60 s, not 15 s. */
    @Test
    fun aFailedRerouteHoldsTheNextOneOffLonger() {
        val last = 1_000_000L
        val twice = NavPolicy.RerouteAttempts().started(last).failed("x").failed("x")
        assertEquals(
            NavPolicy.Decision.Continue,
            decide(2_000.0, 300.0, attempts = twice, nowMs = last + NavPolicy.REROUTE_COOLDOWN_MS + 1),
        )
        assertEquals(
            NavPolicy.Decision.Continue,
            decide(2_000.0, 300.0, attempts = twice, nowMs = last + NavPolicy.REROUTE_MAX_COOLDOWN_MS),
        )
        assertEquals(
            NavPolicy.Decision.Reroute,
            decide(2_000.0, 300.0, attempts = twice, nowMs = last + NavPolicy.REROUTE_MAX_COOLDOWN_MS + 1),
        )
    }

    /** The issue's case, a rider off route with no signal: "Rerouting" is
     *  said on the first request and never on a retry. */
    @Test
    fun reroutingIsAnnouncedOncePerEpisode() {
        var attempts = NavPolicy.RerouteAttempts()
        val announced = (0 until 4).map {
            val say = attempts.announce
            attempts = attempts.started(it * 60_000L).failed("Can't reroute — no connection")
            say
        }
        assertEquals(listOf(true, false, false, false), announced)
        assertEquals("Can't reroute — no connection", attempts.failureText)
    }

    @Test
    fun backOnRouteEndsTheEpisodeButKeepsTheLastRequestTime() {
        val failed = NavPolicy.RerouteAttempts().started(5_000L).failed("x")
        val streak = NavPolicy.OffRouteStreak(fixes = 3, sinceMs = 0L, lastFixKey = 1L)
        assertEquals(failed, failed.afterFix(streak))
        val back = failed.afterFix(null)
        assertEquals(NavPolicy.RerouteAttempts(lastMs = 5_000L), back)
        assertTrue(back.announce)
    }

    @Test
    fun aFreshRouteEndsTheEpisode() {
        val ok = NavPolicy.RerouteAttempts().started(5_000L).failed("x").started(65_000L).succeeded()
        assertEquals(NavPolicy.RerouteAttempts(lastMs = 65_000L), ok)
        assertEquals(NavPolicy.REROUTE_COOLDOWN_MS, NavPolicy.rerouteCooldownMs(ok.failures))
    }
}

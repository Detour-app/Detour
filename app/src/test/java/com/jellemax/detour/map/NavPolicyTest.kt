package com.jellemax.detour.map

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.NavEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun decide(
        remainingMeters: Double,
        offRouteMeters: Double,
        hasDestination: Boolean = true,
        rerouting: Boolean = false,
        lastRerouteMs: Long = 0L,
        nowMs: Long = 1_000_000L,
    ) = NavPolicy.decide(
        progress = progress(remainingMeters, offRouteMeters),
        hasDestination = hasDestination,
        rerouting = rerouting,
        lastRerouteMs = lastRerouteMs,
        nowMs = nowMs,
    )

    @Test
    fun arrivesWhenCloseToTheEndAndStillOnTheLine() {
        assertEquals(NavPolicy.Decision.Arrived, decide(remainingMeters = 10.0, offRouteMeters = 5.0))
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
            decide(remainingMeters = 1.0, offRouteMeters = 1.0, hasDestination = false),
        )
        assertEquals(
            NavPolicy.Decision.Continue,
            decide(remainingMeters = 5_000.0, offRouteMeters = 500.0, hasDestination = false),
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
        assertEquals(
            NavPolicy.Decision.Continue,
            decide(2_000.0, 300.0, lastRerouteMs = last, nowMs = last + NavPolicy.REROUTE_COOLDOWN_MS),
        )
        assertEquals(
            NavPolicy.Decision.Reroute,
            decide(2_000.0, 300.0, lastRerouteMs = last, nowMs = last + NavPolicy.REROUTE_COOLDOWN_MS + 1),
        )
    }

    /** Both call sites start `lastRerouteMs` at 0 (`MapScreen.kt:228`,
     *  `car/NavScreen.kt:112`), so the first reroute of a session must not be
     *  held off by a cooldown measured from the epoch. */
    @Test
    fun theFirstRerouteOfASessionIsNotBlocked() {
        assertEquals(
            NavPolicy.Decision.Reroute,
            decide(2_000.0, 300.0, lastRerouteMs = 0L, nowMs = 1_700_000_000_000L),
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
}

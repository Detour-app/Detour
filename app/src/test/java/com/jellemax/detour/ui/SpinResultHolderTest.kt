package com.jellemax.detour.ui

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RouteCandidate
import com.jellemax.detour.data.RouteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the two write paths [SpinResultHolder] now has, and the invariant that
 * made them worth having.
 *
 * The flow used to be a public `MutableStateFlow` written directly from two
 * files. Nothing stopped a writer publishing a destination without the route it
 * belongs to, and `SpinResult.navigating`'s own KDoc names what that costs
 * mid-ride: "a stopped navigation that looks like a running one". These tests
 * are what a third writer would now have to break on purpose.
 */
class SpinResultHolderTest {

    private fun route() = RouteResult(
        polyline = listOf(LatLon(51.0, 4.0), LatLon(51.1, 4.1)),
        waypoints = emptyList(),
        distanceMeters = 12_000.0,
        timeMs = 900_000L,
    )

    @Before
    fun reset() {
        SpinResultHolder.publish(SpinResult())
    }

    @Test
    fun publishRoundTripsEveryFieldTheMapSets() {
        val candidates = listOf(
            RouteCandidate(
                destination = LatLon(51.2, 4.2),
                name = "A",
                route = route(),
                straightLineMeters = 1.0,
            ),
        )
        SpinResultHolder.publish(
            SpinResult(
                destination = LatLon(51.2, 4.2),
                destinationName = "A",
                route = route(),
                candidates = candidates,
                navigating = true,
            )
        )
        val s = SpinResultHolder.state.value
        assertEquals(LatLon(51.2, 4.2), s.destination)
        assertEquals("A", s.destinationName)
        assertEquals(12_000.0, s.route!!.distanceMeters!!, 1e-9)
        assertEquals(1, s.candidates.size)
        assertTrue(s.navigating)
    }

    /**
     * The reason [SpinResultHolder.seedDestination] exists rather than a second
     * bare `publish`: a caller holding only a saved route must not have to
     * invent the other fields, and the two that did spelled the empty cases
     * differently.
     */
    @Test
    fun seedingADestinationLeavesNoStaleCandidatesOrNavigation() {
        SpinResultHolder.publish(
            SpinResult(
                destination = LatLon(50.0, 3.0),
                candidates = listOf(
                    RouteCandidate(
                        destination = LatLon(50.0, 3.0),
                        name = "old",
                        route = null,
                        straightLineMeters = 2.0,
                    ),
                ),
                navigating = true,
            )
        )
        SpinResultHolder.seedDestination(
            destination = LatLon(52.0, 5.0),
            name = "Bruges",
            route = route(),
        )
        val s = SpinResultHolder.state.value
        assertEquals(LatLon(52.0, 5.0), s.destination)
        assertEquals("Bruges", s.destinationName)
        // Both must be cleared: a seeded destination is not a spin result, and
        // it is certainly not a running navigation.
        assertTrue(s.candidates.isEmpty())
        assertFalse(s.navigating)
    }

    @Test
    fun defaultsAreEmptyRatherThanNullBearing() {
        val s = SpinResult()
        assertEquals(null, s.destination)
        assertEquals(null, s.route)
        assertTrue(s.candidates.isEmpty())
        assertFalse(s.navigating)
    }
}

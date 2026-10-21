package com.jellemax.detour.ui

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RouteCandidate
import com.jellemax.detour.map.CameraAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the camera a restored spin result starts with (#427): reopening the app
 * on a candidate list used to hand it a following camera, which took the map
 * back to the rider with none of the three pins in view. Also pins how a
 * planned route's stops (#736) are dropped: ridden past, or a new destination.
 */
class MapScreenStateTest {

    private fun candidate(name: String) = RouteCandidate(
        destination = LatLon(51.2, 4.2),
        name = name,
        route = null,
        straightLineMeters = 1.0,
    )

    @Test
    fun seedWithCandidatesStartsParkedLikeTheSpinThatMadeThem() {
        val s = MapScreenState(SpinResult(candidates = listOf(candidate("A"), candidate("B"))), null)

        assertTrue(
            "#427: a restored candidate list must not hand the camera back to the follow loop",
            s.camAuthority.camSuspended,
        )
        assertEquals(CameraAuthority.ParkReason.Spin, s.camAuthority.parkedBy)
        // Same park the spin took: no quiet-window stamp, following intent kept.
        assertEquals(0L, s.camAuthority.lastGestureMs)
        assertTrue(s.camAuthority.followMe)
    }

    @Test
    fun seedWithoutCandidatesStartsFollowing() {
        val s = MapScreenState(SpinResult(destination = LatLon(51.2, 4.2), destinationName = "A"), null)

        assertFalse(s.camAuthority.camSuspended)
        assertTrue(s.camAuthority.following)
    }

    private val store = LatLon(52.0, 5.1)
    private val bakery = LatLon(52.0, 5.2)
    private val work = LatLon(52.0, 5.3)

    @Test
    fun aStopRiddenPastIsPassedAndTheRestStayAhead() {
        val s = MapScreenState(SpinResult(destination = work, vias = listOf(store, bakery)), null)
        val line = (0..60).map { LatLon(52.0, 5.0 + it * 0.005) }

        assertEquals(listOf(store, bakery), s.passVias(line, LatLon(52.0, 5.0)))
        assertEquals(listOf(bakery), s.passVias(line, LatLon(52.0, 5.15)))
        assertEquals(listOf(bakery), s.vias)
    }

    @Test
    fun anotherDestinationDropsThePlannedStops() {
        val s = MapScreenState(SpinResult(destination = work, vias = listOf(store, bakery)), null)

        s.destination = work
        assertEquals("rewriting the same destination keeps its stops", listOf(store, bakery), s.vias)
        s.destination = LatLon(51.0, 4.0)
        assertTrue("#736: a new pick must not route through the old route's stops", s.vias.isEmpty())
    }

    @Test
    fun clearingTheDestinationDropsThePlannedStops() {
        val s = MapScreenState(SpinResult(destination = work, vias = listOf(store)), null)

        s.destination = null
        assertTrue(s.vias.isEmpty())
    }
}

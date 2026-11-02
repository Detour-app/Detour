package com.jellemax.detour.ui

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RouteCandidate
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.map.CameraAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

        s.followPlanned(line, LatLon(52.0, 5.0))
        assertEquals(listOf(store, bakery), s.vias)
        s.followPlanned(line, LatLon(52.0, 5.15))
        assertEquals(listOf(bakery), s.vias)
    }

    /** #736: the way back of a line through stops is where the global snap
     *  goes wrong, so the forward ride outlives the last stop on its line. */
    @Test
    fun theLineThroughTheStopsIsFollowedPastTheLastOne() {
        val s = MapScreenState(SpinResult(destination = work, vias = listOf(store)), null)
        val line = (0..60).map { LatLon(52.0, 5.0 + it * 0.005) }

        (0..30).forEach { s.followPlanned(line, LatLon(52.0, 5.0 + it * 0.005)) }
        assertTrue(s.vias.isEmpty())
        assertNotNull(s.followPlanned(line, LatLon(52.0, 5.155)))
        assertNull("a fresh line with no stops left is placed globally", s.followPlanned(line.toList(), work))
    }

    private fun routeOn(line: List<LatLon>) =
        RouteResult(polyline = line, waypoints = emptyList(), distanceMeters = 0.0, timeMs = 0L)

    /** The screen rebuilt from what it published, as a rotation or a return to
     *  the map rebuilds it — through [SpinResultHolder], so its invariants hold. */
    private fun rebuilt(s: MapScreenState): MapScreenState {
        SpinResultHolder.publish(
            SpinResult(s.destination, s.destinationName, s.route, s.candidates, s.navigating, s.vias, s.viaRide)
        )
        SpinResultHolder.keepRide(s.viaRide)
        return MapScreenState(SpinResultHolder.state.value, null).also { SpinResultHolder.publish(SpinResult()) }
    }

    /** #736: home → store → home on one road. Rebuilt on the way back, the
     *  ride must carry on from where the rider was, not from metre zero —
     *  where the global snap would put a rider near home at the start again. */
    @Test
    fun aRebuiltScreenCarriesOnTheRideOnTheWayBack() {
        val out = (0..20).map { LatLon(52.0, 5.0 + it * 0.005) }
        val line = out + out.reversed().drop(1)
        val s = MapScreenState(SpinResult(destination = out.first(), vias = listOf(out.last())), null)
        s.route = routeOn(line)

        (0..34).forEach { s.followPlanned(line, line[it]) }
        assertTrue(s.vias.isEmpty())
        val before = s.followPlanned(line, line[34])!!.meters

        val r = rebuilt(s)
        assertEquals(before, r.viaRide!!.along.meters, 1.0)
        val after = r.followPlanned(line, line[36])
        assertNotNull("a rebuilt screen past the last stop stays on the forward ride", after)
        assertTrue("the ride went on from the way back, not from home", after!!.meters > before)
    }

    /** #736: rebuilt with a stop still ahead, the stops already passed stay
     *  passed and the next one is not re-projected onto the way out. */
    @Test
    fun aRebuiltScreenKeepsTheStopsItProjected() {
        val out = (0..20).map { LatLon(52.0, 5.0 + it * 0.005) }
        val line = out + out.reversed().drop(1)
        val corner = out[10]
        val s = MapScreenState(SpinResult(destination = out.first(), vias = listOf(out.last(), corner)), null)
        s.route = routeOn(line)

        (0..22).forEach { s.followPlanned(line, line[it]) }
        assertEquals(listOf(corner), s.vias)

        val r = rebuilt(s)
        r.followPlanned(line, line[23])
        assertEquals("the corner on the way back is still ahead", listOf(corner), r.vias)
    }

    @Test
    fun aRideNotOnThePublishedRouteIsNotKept() {
        val line = (0..60).map { LatLon(52.0, 5.0 + it * 0.005) }
        val s = MapScreenState(SpinResult(destination = work, vias = listOf(store)), null)
        s.route = routeOn(line)
        s.followPlanned(line, line[1])
        s.route = routeOn(line.map { LatLon(52.001, it.lon) })

        assertNull(rebuilt(s).viaRide)
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

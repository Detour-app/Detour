package com.jellemax.detour.drive

import com.jellemax.detour.data.HereTrafficClient.FlowSegment
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers TrafficEta.kt (#643): the router's time plus the delay HERE's flow
 * puts on the legs of the route it matches, same direction only.
 */
class TrafficEtaTest {

    /** 1 km due north, as two 500 m legs. */
    private val route = listOf(LatLon(50.0, 4.0), LatLon(50.0045, 4.0), LatLon(50.009, 4.0))
    private val routeMeters = route.zipWithNext { a, b -> RoadRoulette.distanceMeters(a, b) }.sum()

    private fun segment() = FlowSegment(
        shape = listOf(LatLon(49.999, 4.0001), LatLon(50.010, 4.0001)),
        speedMps = 25.0,
        freeFlowMps = 25.0,
        jamFactor = 0.0,
        closed = false,
    )

    @Test
    fun noFlowLeavesTheRoutersTimeAlone() {
        val eta = TrafficEta.estimate(route, routerSeconds = 60.0, flow = emptyList())
        assertEquals(60.0, eta.durationSeconds, absoluteTolerance = 1e-9)
        assertEquals(0.0, eta.matchedMeters, absoluteTolerance = 1e-9, "no data, not no delay")
    }

    @Test
    fun freeFlowAddsNoDelay() {
        val eta = TrafficEta.estimate(route, 60.0, listOf(segment()))
        assertEquals(0.0, eta.delaySeconds, absoluteTolerance = 1e-9)
        assertEquals(routeMeters, eta.matchedMeters, absoluteTolerance = 1e-6)
    }

    @Test
    fun aJamAddsTheTimeLostAgainstFreeFlow() {
        val eta = TrafficEta.estimate(route, 60.0, listOf(segment().copy(speedMps = 5.0, jamFactor = 7.0)))
        val expected = routeMeters / 5.0 - routeMeters / 25.0
        assertEquals(expected, eta.delaySeconds, absoluteTolerance = 1e-6)
        assertEquals(60.0 + expected, eta.durationSeconds, absoluteTolerance = 1e-6)
    }

    @Test
    fun aJamOnTheOtherCarriagewayIsNotTheRiders() {
        val southbound = segment().let { it.copy(shape = it.shape.reversed(), speedMps = 2.0) }
        val eta = TrafficEta.estimate(route, 60.0, listOf(southbound))
        assertEquals(0.0, eta.delaySeconds, absoluteTolerance = 1e-9, "opposite direction matched")
        assertEquals(0.0, eta.matchedMeters, absoluteTolerance = 1e-9)
    }

    @Test
    fun aJamOnAParallelRoadFurtherAwayIsNotMatched() {
        val parallel = segment().copy(
            shape = listOf(LatLon(49.999, 4.001), LatLon(50.010, 4.001)), // ~70 m east
            speedMps = 2.0,
        )
        assertEquals(0.0, TrafficEta.estimate(route, 60.0, listOf(parallel)).delaySeconds, absoluteTolerance = 1e-9)
    }

    @Test
    fun aStandstillIsSlowButNotInfinite() {
        val eta = TrafficEta.estimate(route, 60.0, listOf(segment().copy(speedMps = 0.0, jamFactor = 9.9)))
        val expected = routeMeters / TrafficEta.MIN_SPEED_MPS - routeMeters / 25.0
        assertEquals(expected, eta.delaySeconds, absoluteTolerance = 1e-6)
    }

    @Test
    fun aClosedRoadIsFlaggedRatherThanTimed() {
        val eta = TrafficEta.estimate(route, 60.0, listOf(segment().copy(speedMps = 0.0, jamFactor = 10.0, closed = true)))
        assertTrue(eta.closedAhead)
        assertEquals(0.0, eta.delaySeconds, absoluteTolerance = 1e-9)
    }

    @Test
    fun anOpenRouteIsNotClosed() {
        assertFalse(TrafficEta.estimate(route, 60.0, listOf(segment())).closedAhead)
    }
}

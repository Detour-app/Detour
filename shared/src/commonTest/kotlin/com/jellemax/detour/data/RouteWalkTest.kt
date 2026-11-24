package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [walkRoute]: the car test drive's evenly spaced walk along a route polyline. */
class RouteWalkTest {

    // About 111 m per 0.001° of latitude: two segments heading north, then east.
    private val north = LatLon(51.0, 3.7)
    private val corner = LatLon(51.001, 3.7)
    private val east = LatLon(51.001, 3.7015)

    @Test
    fun stepsAreEvenlySpacedAcrossSegmentBoundaries() {
        val steps = walkRoute(listOf(north, corner, east), stepMeters = 14.0).toList()
        val gaps = steps.zipWithNext { a, b -> RoadRoulette.distanceMeters(a.at, b.at) }
        // Along the first, straight segment every gap is exactly one step.
        gaps.take(5).forEach { assertEquals(14.0, it, absoluteTolerance = 0.5) }
        // Round the corner and onto the end the straight-line gap is shorter, never longer.
        assertTrue(gaps.all { it <= 14.5 }, "no step may jump further than the step: $gaps")
    }

    @Test
    fun walkStartsAtTheStartAndEndsAtTheEnd() {
        val steps = walkRoute(listOf(north, corner, east), stepMeters = 14.0).toList()
        assertEquals(north, steps.first().at)
        assertEquals(east, steps.last().at)
    }

    @Test
    fun bearingFollowsEachSegment() {
        val steps = walkRoute(listOf(north, corner, east), stepMeters = 14.0).toList()
        assertEquals(0.0, steps.first().bearingDeg, absoluteTolerance = 0.5)
        assertEquals(90.0, steps.last().bearingDeg, absoluteTolerance = 0.5)
    }

    @Test
    fun repeatedPointsAndShortPolylinesWalkNowhereOdd() {
        assertEquals(emptyList(), walkRoute(listOf(north), 14.0).toList())
        assertEquals(emptyList(), walkRoute(listOf(north, north), 14.0).toList())
        val steps = walkRoute(listOf(north, north, corner), 14.0).toList()
        assertEquals(north, steps.first().at)
        assertEquals(corner, steps.last().at)
    }
}

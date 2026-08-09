package com.jellemax.detour.data

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The route-progress geometry the maps draw the driven part of a route with:
 * [NavEngine.lengthMeters] and [NavEngine.prefix], plus the fraction
 * [NavEngine.Progress] hands them.
 *
 * Everything here runs along a meridian (constant longitude), where the
 * flat-earth approximation is exact by construction — one degree of latitude is
 * the 111_320 m the engine uses — so the expected values are arithmetic rather
 * than transcribed from a run. Distances are still compared with a tolerance:
 * the engine sums segments in order, and asking float arithmetic for an exact
 * total is how a correct test goes red on a different platform.
 */
class NavEngineTest {

    /** 0.01° of latitude, ~1113.2 m per step, four steps. */
    private val line = (0..4).map { LatLon(50.0 + it * 0.01, 3.0) }
    private val total = 4 * 0.01 * 111_320.0

    private fun assertClose(expected: Double, actual: Double, tolerance: Double) {
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected but was $actual")
    }

    @Test
    fun lengthAddsUpTheSegments() {
        assertClose(total, NavEngine.lengthMeters(line), 0.5)
        assertClose(0.0, NavEngine.lengthMeters(emptyList()), 0.0)
        assertClose(0.0, NavEngine.lengthMeters(listOf(LatLon(50.0, 3.0))), 0.0)
    }

    @Test
    fun prefixCutsMidSegment() {
        // An eighth of a four-segment line lands halfway along the first
        // segment, which no vertex sits on — the end point is interpolated.
        val eighth = NavEngine.prefix(line, 0.125)
        assertEquals(2, eighth.size)
        assertClose(50.005, eighth.last().lat, 1e-9)
        assertClose(total / 8, NavEngine.lengthMeters(eighth), 0.5)
    }

    @Test
    fun prefixKeepsTheVerticesItHasPassed() {
        val half = NavEngine.prefix(line, 0.5)
        assertClose(50.02, half.last().lat, 1e-9)
        assertClose(total / 2, NavEngine.lengthMeters(half), 0.5)
        // The vertices behind the cut are still in it, in order, so the drawn
        // line follows the road rather than shortcutting across its bends.
        // (Only the two the cut is safely past: whether the vertex the cut
        // lands on is kept or re-emitted as an interpolated copy of itself is
        // down to the last bit of a float, and invisible either way.)
        assertEquals(line.take(2), half.take(2))
    }

    @Test
    fun prefixHasNothingToDrawAtTheStart() {
        assertTrue(NavEngine.prefix(line, 0.0).isEmpty())
        assertTrue(NavEngine.prefix(line, -1.0).isEmpty())
        assertTrue(NavEngine.prefix(listOf(LatLon(50.0, 3.0)), 0.5).isEmpty())
        assertTrue(NavEngine.prefix(emptyList(), 0.5).isEmpty())
    }

    @Test
    fun prefixIsTheWholeLineAtTheEnd() {
        for (fraction in listOf(1.0, 2.0)) {
            val whole = NavEngine.prefix(line, fraction)
            assertEquals(line.size, whole.size)
            assertClose(line.last().lat, whole.last().lat, 1e-9)
            assertClose(total, NavEngine.lengthMeters(whole), 0.5)
        }
    }

    @Test
    fun drivenFractionIsRemainingTheOtherWayRound() {
        fun progress(remaining: Double, routeMeters: Double) = NavEngine.Progress(
            offRouteMeters = 0.0,
            nextInstruction = null,
            distanceToTurnMeters = remaining,
            remainingMeters = remaining,
            routeMeters = routeMeters,
            remainingTimeMs = null,
            speedLimitKmh = null,
        )
        assertClose(0.0, progress(1000.0, 1000.0).drivenFraction, 1e-9)
        assertClose(0.75, progress(250.0, 1000.0).drivenFraction, 1e-9)
        assertClose(1.0, progress(0.0, 1000.0).drivenFraction, 1e-9)
        // A route with no measurable length can't have been driven along.
        assertClose(0.0, progress(0.0, 0.0).drivenFraction, 1e-9)
    }
}

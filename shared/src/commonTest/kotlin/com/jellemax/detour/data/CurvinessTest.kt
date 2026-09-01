package com.jellemax.detour.data

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Characterises [Curviness.traceScore] — the same 25-300m circumradius
 *  window [Curviness.score] and [Curviness.routeScore] already use, applied
 *  to a driven trace instead of an OSM way or a routed polyline. Fixtures
 *  are literal coordinates (Ghent, arbitrarily), never a real recording —
 *  per detour-trip-data §6. */
class CurvinessTest {

    private val origin = LatLon(51.05, 3.72)

    private fun at(meters: Double, bearingDeg: Double) =
        RoadRoulette.offset(origin, meters, bearingDeg * PI / 180.0)

    @Test
    fun aStraightLineScoresZero() {
        // 41 points, ~1023m nominal total: RoadRoulette.offset's flat-degree
        // projection vs. distanceMeters's haversine means each "25m" step is
        // actually ~24.97m (same discrepancy SpeedLimitTrackerTest documents),
        // so a (0..20) range's ~499.4m real length would trip the <500m floor
        // below for the wrong reason instead of exercising the collinear-guard
        // path in circumradiusMeters this test is named for.
        val points = (0..40).map { at(it * 25.0, 90.0) } // due east, 25m apart
        assertEquals(0.0, Curviness.traceScore(points), absoluteTolerance = 1e-9)
    }

    @Test
    fun aTightSCurveScoresAboveZero() {
        // Alternating bearing every point traces a tight zig-zag within the
        // 25-300m radius window.
        val points = (0..20).map { i ->
            val bearing = if (i % 2 == 0) 80.0 else 100.0
            at(i * 25.0, bearing)
        }
        assertTrue(Curviness.traceScore(points) > 0.0)
    }

    @Test
    fun fewerThanThreePointsScoresZero() {
        assertEquals(0.0, Curviness.traceScore(listOf(origin, at(25.0, 90.0))))
    }

    @Test
    fun aShortTraceUnderFiveHundredMetersScoresZero() {
        val points = (0..5).map { at(it * 25.0, 90.0) } // 125m total
        assertEquals(0.0, Curviness.traceScore(points))
    }
}

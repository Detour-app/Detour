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

    // --- Curviness.score: same 25-300m circumradius window and 500m length
    // floor as traceScore, but additionally skips any vertex next to an OSM
    // junction node (way.nodes[i-1..i+1] in the junctions set) — a turn at an
    // intersection contributes nothing, only curvature within the road does.
    // Takes an OverpassWay (raw OSM geometry + node ids), not a polyline.

    private fun way(points: List<LatLon>, nodeIds: List<Long> = points.indices.map { (it + 1).toLong() }) =
        OverpassWay(nodeIds, points)

    @Test
    fun aStraightWayScoresZero() {
        val points = (0..40).map { at(it * 25.0, 90.0) } // same fixture as aStraightLineScoresZero
        assertEquals(0.0, Curviness.score(way(points), emptySet()), absoluteTolerance = 1e-9)
    }

    @Test
    fun aWindingWayScoresHigherThanAStraightWay() {
        val straight = way((0..40).map { at(it * 25.0, 90.0) })
        val winding = way((0..40).map { i -> at(i * 25.0, if (i % 2 == 0) 80.0 else 100.0) })
        assertTrue(Curviness.score(winding, emptySet()) > Curviness.score(straight, emptySet()))
    }

    @Test
    fun fewerThanThreePointsScoresZeroForScore() {
        // The task brief for this suite assumed these sizes throw. They don't:
        // score() guards on pts.size < 3 and returns before touching way.nodes.
        assertEquals(0.0, Curviness.score(way(emptyList()), emptySet()))
        assertEquals(0.0, Curviness.score(way(listOf(origin)), emptySet()))
        assertEquals(0.0, Curviness.score(way(listOf(origin, at(25.0, 90.0))), emptySet()))
    }

    @Test
    fun aShortWindingWayUnderFiveHundredMetersScoresZero() {
        // Same winding geometry as aWindingWayScoresHigherThanAStraightWay,
        // just fewer points (~200m total) — the length floor cuts it off no
        // matter how curvy the road actually is.
        val points = (0..8).map { i -> at(i * 25.0, if (i % 2 == 0) 80.0 else 100.0) }
        assertEquals(0.0, Curviness.score(way(points), emptySet()))
    }

    @Test
    fun verticesAtAJunctionAreExcludedFromTheCurvyScore() {
        val points = (0..40).map { i -> at(i * 25.0, if (i % 2 == 0) 80.0 else 100.0) }
        val nodeIds = points.indices.map { (it + 1).toLong() }
        assertTrue(Curviness.score(way(points, nodeIds), emptySet()) > 0.0)
        // Marking every node a junction removes every vertex's curvy
        // contribution, even though the geometry itself hasn't changed.
        assertEquals(0.0, Curviness.score(way(points, nodeIds), nodeIds.toSet()))
    }

    @Test
    fun aClosedLoopWayScoresAboveZero() {
        // Round-trip mode's plan() assembles a loop of waypoints, even though
        // each individual way scored by score() is an ordinary (non-looping)
        // OSM road. Confirm score() has no hidden assumption that breaks when
        // a way's own points do form a closed loop (e.g. the shared start/end
        // point being treated as some kind of special case).
        val radius = 100.0 // inside the 25-300m window: three points on a
        // circle have a circumradius equal to the circle's own radius.
        val ring = (0 until 12).map { i -> at(radius, i * 30.0) }
        val points = ring + ring.first() // close the loop exactly, no fp drift
        val nodeIds = (1..12L).toList() + 1L
        assertTrue(Curviness.score(way(points, nodeIds), emptySet()) > 0.0)
    }

    // --- Curviness.routeScore: same 25-300m window, but junction-skipping
    // comes from GraphHopper turn instructions (marks polyline vertices near
    // any instruction whose sign != 0) instead of OSM node ids — and, unlike
    // score()/traceScore(), there is no 500m length floor.

    @Test
    fun aStraightPolylineScoresZero() {
        val points = (0..40).map { at(it * 25.0, 90.0) }
        assertEquals(0.0, Curviness.routeScore(points, emptyList()), absoluteTolerance = 1e-9)
    }

    @Test
    fun aWindingPolylineScoresHigherThanAStraightPolyline() {
        val straight = (0..40).map { at(it * 25.0, 90.0) }
        val winding = (0..40).map { i -> at(i * 25.0, if (i % 2 == 0) 80.0 else 100.0) }
        assertTrue(Curviness.routeScore(winding, emptyList()) > Curviness.routeScore(straight, emptyList()))
    }

    @Test
    fun fewerThanThreePointsScoresZeroForRouteScore() {
        assertEquals(0.0, Curviness.routeScore(emptyList(), emptyList()))
        assertEquals(0.0, Curviness.routeScore(listOf(origin), emptyList()))
        assertEquals(0.0, Curviness.routeScore(listOf(origin, at(25.0, 90.0)), emptyList()))
    }

    @Test
    fun aShortWindingPolylineUnderFiveHundredMetersScoresAboveZero() {
        // Same short winding geometry that scores 0 under score()'s 500m
        // floor (aShortWindingWayUnderFiveHundredMetersScoresZero) — routeScore
        // has no such floor, so the identical geometry still scores > 0 here.
        val points = (0..8).map { i -> at(i * 25.0, if (i % 2 == 0) 80.0 else 100.0) }
        assertTrue(Curviness.routeScore(points, emptyList()) > 0.0)
    }

    @Test
    fun aTurnInstructionExcludesNearbyVerticesFromTheScore() {
        val points = (0..40).map { i -> at(i * 25.0, if (i % 2 == 0) 80.0 else 100.0) }
        val baseline = Curviness.routeScore(points, emptyList())
        val turn = NavInstruction(text = "turn right", distanceMeters = 0.0, sign = 3, startIndex = 20, endIndex = 20)
        assertTrue(Curviness.routeScore(points, listOf(turn)) < baseline)
    }

    @Test
    fun aContinueInstructionSignZeroExcludesNothing() {
        // sign == 0 ("continue onto…") is explicitly skipped by routeScore —
        // it bends no road, so it should not suppress any vertex's score.
        val points = (0..40).map { i -> at(i * 25.0, if (i % 2 == 0) 80.0 else 100.0) }
        val baseline = Curviness.routeScore(points, emptyList())
        val continueOn = NavInstruction(text = "continue", distanceMeters = 0.0, sign = 0, startIndex = 20, endIndex = 20)
        assertEquals(baseline, Curviness.routeScore(points, listOf(continueOn)), absoluteTolerance = 1e-9)
    }
}

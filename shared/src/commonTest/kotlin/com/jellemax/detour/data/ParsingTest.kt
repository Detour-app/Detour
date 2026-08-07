package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The port swapped org.json for kotlinx.serialization across every wire format
 * the app reads. These pin the behaviours that swap could plausibly have
 * changed — absent vs null vs wrong-type, and the positional arrays whose
 * shapes are other people's (GraphHopper, Overpass, the trace store).
 *
 * Deliberately no network: everything here is a parser fed a literal.
 */
class JsonAccessorTest {

    @Test
    fun missingKeysReturnDefaultsRatherThanThrowing() {
        val o = jsonObjectOf("""{"a": "x"}""")
        assertEquals("", o.optString("missing"))
        assertEquals("fallback", o.optString("missing", "fallback"))
        assertEquals(0, o.optInt("missing"))
        assertEquals(7L, o.optLong("missing", 7L))
        assertTrue(o.optDouble("missing").isNaN())
        assertNull(o.optObject("missing"))
        assertNull(o.optArray("missing"))
    }

    @Test
    fun jsonNullReadsAsAbsent() {
        // The sync server omits fields it has no value for, but GraphHopper
        // sends explicit nulls in speed-limit tuples. Both have to read the
        // same way, which is what org.json's opt* did.
        val o = jsonObjectOf("""{"a": null}""")
        assertEquals("", o.optString("a"))
        assertTrue(!o.has("a"))
    }

    @Test
    fun wrongTypeReturnsTheDefault() {
        val o = jsonObjectOf("""{"n": "not-a-number"}""")
        assertEquals(0, o.optInt("n"))
        assertTrue(o.optDouble("n").isNaN())
    }

    @Test
    fun arrayIsNullCoversNullAndPastTheEnd() {
        val a = jsonArrayOf("""[1, null]""")
        assertTrue(!a.isNull(0))
        assertTrue(a.isNull(1))
        assertTrue(a.isNull(2)) // off the end
    }
}

class TraceStoreParsingTest {

    @Test
    fun parsesTheModernFivePointFormat() {
        val line = """[[50.8,3.2,1700000000000,42.5,-12.3],[50.9,3.3,1700000001000,44.0,8.1]]"""
        val points = TraceStore.parsePoints(line)!!
        assertEquals(2, points.size)
        assertEquals(50.8, points[0].at.lat)
        assertEquals(1700000000000L, points[0].timeMs)
        assertEquals(42.5, points[0].speedKmh)
        assertEquals(-12.3, points[0].leanDeg)
    }

    @Test
    fun pointsWrittenBeforeTheTailExistedStillRead() {
        // Two-element points predate speed/lean entirely; they must come back
        // as "unknown" rather than failing the whole line.
        val points = TraceStore.parsePoints("""[[50.8,3.2],[50.9,3.3]]""")!!
        assertEquals(2, points.size)
        assertEquals(-1L, points[0].timeMs)
        assertEquals(0.0, points[0].speedKmh)
        assertNull(points[0].leanDeg)
    }

    @Test
    fun nullLeanMeansAVehicleThatDoesNotMeasureIt() {
        val points = TraceStore.parsePoints(
            """[[50.8,3.2,1,10.0,null],[50.9,3.3,2,11.0,null]]""")!!
        assertNull(points[0].leanDeg)
    }

    @Test
    fun linesTooShortOrMalformedAreSkippedNotFatal() {
        assertNull(TraceStore.parsePoints("""[[50.8,3.2]]"""))   // one point
        assertNull(TraceStore.parsePoints("not json"))
        // parseLines is fed a friend's file as well as our own, so one bad
        // line must not take the rest with it.
        val lines = listOf("""[[50.8,3.2],[50.9,3.3]]""", "garbage")
        assertEquals(1, TraceStore.parseLines(lines).size)
    }
}

class MaxSpeedParsingTest {

    @Test
    fun plainNumbersAndUnits() {
        assertEquals(50.0, RoadRoulette.parseMaxSpeed("50"))
        assertEquals(30.0, RoadRoulette.parseMaxSpeed("30 km/h"))
        assertEquals(30.0, RoadRoulette.parseMaxSpeed("30kmh"))
        assertEquals(48.2802, RoadRoulette.parseMaxSpeed("30 mph")!!, absoluteTolerance = 0.001)
    }

    @Test
    fun zonesAndImplicitUrban() {
        assertEquals(30.0, RoadRoulette.parseMaxSpeed("NL:zone30"))
        assertEquals(50.0, RoadRoulette.parseMaxSpeed("BE:urban"))
        assertEquals(20.0, RoadRoulette.parseMaxSpeed("DE:living_street"))
    }

    @Test
    fun anythingAmbiguousRefuses() {
        // Showing the wrong limit is worse than showing none, so country
        // :rural (80 in NL, 100 in DE) and the signalled/variable values all
        // have to come back null.
        assertNull(RoadRoulette.parseMaxSpeed("none"))
        assertNull(RoadRoulette.parseMaxSpeed("signals"))
        assertNull(RoadRoulette.parseMaxSpeed("variable"))
        assertNull(RoadRoulette.parseMaxSpeed("DE:rural"))
        assertNull(RoadRoulette.parseMaxSpeed("walk"))
    }
}

class NavEngineTest {

    /// A straight 1 km line east, with one turn instruction at its midpoint.
    private fun route(): RouteResult {
        val polyline = (0..10).map { LatLon(50.0, 3.0 + it * 0.001) }
        return RouteResult(
            polyline = polyline,
            waypoints = emptyList(),
            distanceMeters = null,
            instructions = listOf(
                NavInstruction("Turn right", 500.0, sign = 2, startIndex = 5, endIndex = 6),
            ),
        )
    }

    @Test
    fun progressAtTheStartHasTheWholeRouteLeft() {
        val p = NavEngine.progress(route(), LatLon(50.0, 3.0))!!
        assertTrue(p.offRouteMeters < 1.0)
        assertEquals("Turn right", p.nextInstruction?.text)
        assertTrue(p.remainingMeters > 600.0, "got ${p.remainingMeters}")
        assertEquals(p.routeMeters, p.remainingMeters, absoluteTolerance = 1.0)
    }

    @Test
    fun offRouteDistanceIsMeasuredToTheLineNotTheVertices() {
        // Beside the midpoint of a segment, not near any vertex.
        val p = NavEngine.progress(route(), LatLon(50.001, 3.0045))!!
        assertTrue(p.offRouteMeters > 100.0, "got ${p.offRouteMeters}")
        assertTrue(p.offRouteMeters < 120.0, "got ${p.offRouteMeters}")
    }

    @Test
    fun tooShortToFollow() {
        val degenerate = RouteResult(
            polyline = listOf(LatLon(50.0, 3.0)),
            waypoints = emptyList(),
            distanceMeters = null,
        )
        assertNull(NavEngine.progress(degenerate, LatLon(50.0, 3.0)))
    }

    @Test
    fun cameraZoomStaysWithinTwoLevelsOfTheUsersChoice() {
        val base = 16.0
        val stopped = NavEngine.cameraZoom(base, speedMps = 0.0, distanceToTurnMeters = 1e9)
        val motorway = NavEngine.cameraZoom(base, speedMps = 35.0, distanceToTurnMeters = 1e9)
        assertTrue(stopped in (base - 2.0)..(base + 2.0))
        assertTrue(motorway in (base - 2.0)..(base + 2.0))
        // Faster means further out; a turn coming up pulls back in.
        assertTrue(motorway < stopped)
        assertTrue(
            NavEngine.cameraZoom(base, 35.0, distanceToTurnMeters = 50.0) > motorway)
    }
}

class ExploredAreaTest {

    @Test
    fun aDrivenRoadMakesItsOwnCellExplored() {
        val area = ExploredArea(listOf(listOf(LatLon(50.8, 3.2), LatLon(50.801, 3.201))))
        assertTrue(area.isExplored(LatLon(50.8, 3.2)))
    }

    @Test
    fun somewhereElseEntirelyIsNot() {
        val area = ExploredArea(listOf(listOf(LatLon(50.8, 3.2), LatLon(50.801, 3.201))))
        assertTrue(!area.isExplored(LatLon(51.5, 4.9)))
    }

    @Test
    fun noTracesMeansNothingIsExplored() {
        assertTrue(!ExploredArea(emptyList()).isExplored(LatLon(50.8, 3.2)))
    }
}

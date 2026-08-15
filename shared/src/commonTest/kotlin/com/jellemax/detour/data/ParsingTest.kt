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

/**
 * [SpeedCameras.parseSection] against the two E40 trajectcontrole relations the
 * `trajectcontrole.txt` replay route drives, fed the JSON Overpass prints for
 * `out geom` — node members with their coordinates inline.
 *
 * Written to settle maxke24/Detour#22, which reads the recorded early clear of
 * the average-speed chip (306 m into a 3852 m section) as the parser mistaking
 * a member list clipped by the fetch radius for a complete one, and returning a
 * short section derived from the entry cluster alone. For these two relations it
 * does not: see [aMemberListClippedShortOfTheFarGantryIsRejectedRatherThanReturnedShort].
 *
 * The refs, roles and coordinates are the real ones, read on 2026-08-12 from
 * `api.openstreetmap.org/api/0.6/relation/<id>/full.json` — a different service
 * from Overpass, which is refusing this IP — and they agree with the geometry
 * table in `tools/mocklocation/baseline/README.md`. Deliberately no network:
 * this is the parser fed a literal, like everything else in this file.
 */
class SpeedCameraSectionTest {

    // Relation 15685856, "Trajectcontrole E40" Bertem-Leuven, 3852 m: a pair of
    // device nodes 22 m apart at the Leuven gantry and one at the Bertem gantry.
    private val leuvenAt = LatLon(50.8531975, 4.6581815)
    private val leuvenPairAt = LatLon(50.8530078, 4.6580822)
    private val bertemAt = LatLon(50.8618251, 4.6050292)
    private val leuven = """{"type":"node","ref":10787072889,"role":"device","lat":50.8531975,"lon":4.6581815}"""
    private val leuvenPair = """{"type":"node","ref":10787072890,"role":"device","lat":50.8530078,"lon":4.6580822}"""
    private val bertem = """{"type":"node","ref":10784337380,"role":"device","lat":50.8618251,"lon":4.6050292}"""

    // Relation 15682532, Zaventem-Bertem, 7936 m device to device: the Bertem
    // node again — one node in both relations, which is what makes the route's
    // back-to-back transition testable — plus a device and a `from` node 14 m
    // apart at the Zaventem end.
    private val zaventemAt = LatLon(50.869293, 4.4925685)
    private val zaventemFromAt = LatLon(50.8692936, 4.4923710)
    private val zaventem = """{"type":"node","ref":6763749685,"role":"device","lat":50.869293,"lon":4.4925685}"""
    private val zaventemFrom = """{"type":"node","ref":10810676600,"role":"from","lat":50.8692936,"lon":4.492371}"""

    /** Neither relation tags `maxspeed`; the 120 is on the device nodes. */
    private fun relation(vararg members: String) = jsonObjectOf(
        """{"type":"relation","id":15685856,"members":[${members.joinToString(",")}],""" +
            """"tags":{"type":"enforcement","enforcement":"average_speed","name":"Trajectcontrole E40"}}""",
    )

    @Test
    fun theWholeMemberListGivesTheTrueSpanAndOneClusterPerGantry() {
        val s = SpeedCameras.parseSection(relation(leuven, leuvenPair, bertem))!!
        assertEquals(3852.2, s.spanMeters, absoluteTolerance = 0.5)
        // The 22 m pair is one end — one node per carriageway — and the lone
        // node the other. Geometry decides that, not the roles: all three are
        // tagged `device`.
        assertEquals(listOf(leuvenAt, leuvenPairAt), s.endA)
        assertEquals(listOf(bertemAt), s.endB)
        // No limit for the average to be judged against, which is why the
        // recorded run showed a bare average and not a red chip: the 120 sits
        // on the device nodes, which parseSection never looks at.
        assertNull(s.maxspeedKmh)
    }

    @Test
    fun theOutermostNodeIsAnEndWhateverItsRoleSays() {
        val s = SpeedCameras.parseSection(relation(bertem, zaventem, zaventemFrom))!!
        // 7950, not the 7936 between the two `device` nodes: the `from` node is
        // 14 m further out, so it is the end and the device joins its cluster.
        // Worth pinning because it is the difference the roles would have made.
        assertEquals(7949.8, s.spanMeters, absoluteTolerance = 0.5)
        // Both Zaventem-end nodes are in the cluster, in member order — the
        // span above is what says the `from` node is the outer one of the two.
        assertEquals(listOf(zaventemAt, zaventemFromAt), s.endB)
        assertEquals(listOf(bertemAt), s.endA)
    }

    @Test
    fun aMemberListClippedShortOfTheFarGantryIsRejectedRatherThanReturnedShort() {
        // #22 predicted the opposite: a plausible short section whose far end
        // sits a few hundred metres past the entry, which the tracker would
        // then terminate correctly on wrong data. Both relations refuse
        // instead, because each end cluster is 22 m and 14 m across — an order
        // of magnitude under MIN_SPAN_M — so clipping loses the readout rather
        // than falsifying it, and cannot account for a clear 306 m in.
        assertNull(SpeedCameras.parseSection(relation(leuven, leuvenPair)))
        assertNull(SpeedCameras.parseSection(relation(zaventem, zaventemFrom)))
        // Clipped to the shared gantry alone, from either relation.
        assertNull(SpeedCameras.parseSection(relation(bertem)))
    }

    @Test
    fun aMemberTheAreaDidNotReachReadsTheSameOmittedOrPrintedWithoutCoordinates() {
        // Which shape a clipped answer takes is unverified — Overpass is
        // refusing this IP — so both are pinned. A member with no lat/lon is
        // the same as no member at all.
        val bertemNoGeometry = """{"type":"node","ref":10784337380,"role":"device"}"""
        assertNull(SpeedCameras.parseSection(relation(leuven, leuvenPair, bertemNoGeometry)))
        // And a way member is never a node, even when `out geom` prints its
        // whole geometry: the coordinates that would rescue the span above are
        // ignored because they are not on a node member.
        val carriageway = """{"type":"way","ref":1234,"role":"","geometry":""" +
            """[{"lat":50.8531975,"lon":4.6581815},{"lat":50.8618251,"lon":4.6050292}]}"""
        assertNull(SpeedCameras.parseSection(relation(leuven, leuvenPair, carriageway)))
    }

    @Test
    fun aClippedListDoesSurviveWhenSomeNodeSitsMoreThanTheMinimumSpanInside() {
        // The shape #22 describes is real, just not instantiated by either E40
        // relation: parseSection cannot tell a clipped list from a complete
        // one, so any surviving node past MIN_SPAN_M becomes the far end. A
        // mid-section node 500 m in — the `force` node the KDoc says can sit
        // there — is enough. Synthetic, and therefore not evidence of the
        // recorded clear; it is what a clipping guard would have to catch.
        val midSection = """{"type":"node","ref":1,"role":"force","lat":50.8543173,"lon":4.6512826}"""
        val s = SpeedCameras.parseSection(relation(leuven, leuvenPair, midSection))!!
        assertEquals(500.0, s.spanMeters, absoluteTolerance = 1.0)
        assertEquals(1, s.endB.size)
        // 3352 m short of where the section really ends, and spanMeters is
        // understated by the same amount — which `overshot` also reads.
        assertTrue(
            RoadRoulette.distanceMeters(s.endB[0], bertemAt) > 3000.0,
            "far end should be nowhere near the real gantry: got ${s.endB[0]}",
        )
    }
}

class NavEngineTest {

    /**
     * A straight line north: 0.01° of latitude per step, four steps. Along a
     * meridian the engine's flat-earth approximation is exact by construction
     * — one degree of latitude *is* the 111_320 m it uses — so the lengths
     * below are arithmetic rather than transcribed from a run. They are still
     * compared with a tolerance: the engine sums segments in order, and asking
     * float arithmetic for an exact total is how a correct test goes red on
     * another platform.
     */
    private val straightLine = (0..4).map { LatLon(50.0 + it * 0.01, 3.0) }
    private val straightLineMeters = 4 * 0.01 * 111_320.0

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

    // The geometry the maps fade the driven part of a route with.

    @Test
    fun lengthAddsUpTheSegments() {
        assertEquals(straightLineMeters, NavEngine.lengthMeters(straightLine), absoluteTolerance = 0.5)
        assertEquals(0.0, NavEngine.lengthMeters(emptyList()), absoluteTolerance = 0.0)
        assertEquals(
            0.0, NavEngine.lengthMeters(listOf(LatLon(50.0, 3.0))), absoluteTolerance = 0.0)
    }

    @Test
    fun prefixCutsMidSegment() {
        // An eighth of a four-segment line lands halfway along the first
        // segment, which no vertex sits on — the end point is interpolated.
        val eighth = NavEngine.prefix(straightLine, 0.125)
        assertEquals(2, eighth.size)
        assertEquals(50.005, eighth.last().lat, absoluteTolerance = 1e-9)
        assertEquals(
            straightLineMeters / 8, NavEngine.lengthMeters(eighth), absoluteTolerance = 0.5)
    }

    @Test
    fun prefixKeepsTheVerticesItHasPassed() {
        val half = NavEngine.prefix(straightLine, 0.5)
        assertEquals(50.02, half.last().lat, absoluteTolerance = 1e-9)
        assertEquals(
            straightLineMeters / 2, NavEngine.lengthMeters(half), absoluteTolerance = 0.5)
        // The vertices behind the cut are still in it, in order, so the drawn
        // line follows the road rather than shortcutting across its bends.
        // Only the two the cut is safely past: whether the vertex it lands on
        // is kept or re-emitted as an interpolated copy of itself is down to
        // the last bit of a float, and invisible either way.
        assertEquals(straightLine.take(2), half.take(2))
    }

    @Test
    fun prefixHasNothingToDrawAtTheStart() {
        assertTrue(NavEngine.prefix(straightLine, 0.0).isEmpty())
        assertTrue(NavEngine.prefix(straightLine, -1.0).isEmpty())
        assertTrue(NavEngine.prefix(listOf(LatLon(50.0, 3.0)), 0.5).isEmpty())
        assertTrue(NavEngine.prefix(emptyList(), 0.5).isEmpty())
    }

    @Test
    fun prefixIsTheWholeLineAtTheEnd() {
        for (fraction in listOf(1.0, 2.0)) {
            val whole = NavEngine.prefix(straightLine, fraction)
            assertEquals(straightLine.size, whole.size)
            assertEquals(straightLine.last().lat, whole.last().lat, absoluteTolerance = 1e-9)
            assertEquals(
                straightLineMeters, NavEngine.lengthMeters(whole), absoluteTolerance = 0.5)
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
        assertEquals(0.0, progress(1000.0, 1000.0).drivenFraction, absoluteTolerance = 1e-9)
        assertEquals(0.75, progress(250.0, 1000.0).drivenFraction, absoluteTolerance = 1e-9)
        assertEquals(1.0, progress(0.0, 1000.0).drivenFraction, absoluteTolerance = 1e-9)
        // A route with no measurable length can't have been driven along.
        assertEquals(0.0, progress(0.0, 0.0).drivenFraction, absoluteTolerance = 1e-9)
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

package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        // No limit from the relation alone - it tags none. The 120 sits on the
        // device nodes, and with no camera nodes handed in there is nowhere for
        // it to come from; see
        // [theSectionLimitFallsBackToTheOneTaggedOnItsGantryNodes].
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

    /** A camera node at [at] carrying `maxspeed`, as `out geom` prints one. */
    private fun cameraNode(at: LatLon, maxspeed: String) = SpeedCameras.Camera(at, RoadRoulette.parseMaxSpeed(maxspeed))

    /**
     * The limit the running average is judged against, when the relation does
     * not tag one - which neither real E40 relation does.
     *
     * This is the whole reason the average rendered as a bare number instead of
     * a limit-relative one on the road the feature was developed on: the 120 was
     * in the same Overpass answer the entire time, on the gantry nodes, and was
     * read off the relation only.
     */
    @Test
    fun theSectionLimitFallsBackToTheOneTaggedOnItsGantryNodes() {
        val cameras = listOf(
            cameraNode(leuvenAt, "120"),
            cameraNode(bertemAt, "120"),
        )
        val s = SpeedCameras.parseSection(relation(leuven, leuvenPair, bertem), cameras)!!
        assertEquals(120.0, s.maxspeedKmh!!, absoluteTolerance = 1e-9)
    }

    /** The relation's own tag wins. A mapper who put a section limit on the
     *  relation meant the section, and a gantry node can carry the limit of the
     *  road it stands over instead. */
    @Test
    fun theRelationsOwnMaxspeedBeatsTheOneOnItsGantryNodes() {
        val tagged = jsonObjectOf(
            """{"type":"relation","id":15685856,"members":[$leuven,$leuvenPair,$bertem],""" +
                """"tags":{"type":"enforcement","enforcement":"average_speed","maxspeed":"90"}}""",
        )
        val s = SpeedCameras.parseSection(tagged, listOf(cameraNode(leuvenAt, "120")))!!
        assertEquals(90.0, s.maxspeedKmh!!, absoluteTolerance = 1e-9)
    }

    /**
     * Only a camera standing *at* an end donates its limit. The prefetch returns
     * every camera within 4 km, so without this a spot camera on a 50 road a
     * kilometre off the motorway would set the limit a 120 trajectcontrole is
     * judged against - and the average would read red for the whole section.
     *
     * The Zaventem end is the fixture because it is the one with two nodes 14 m
     * apart: 14 m is inside the end cluster and outside the same-node tolerance,
     * so this also pins that the two are different distances.
     */
    @Test
    fun aCameraThatIsNotOneOfTheGantriesDonatesNothing() {
        val elsewhere = RoadRoulette.offset(leuvenAt, 1_000.0, 0.0)
        val s = SpeedCameras.parseSection(
            relation(leuven, leuvenPair, bertem),
            listOf(cameraNode(elsewhere, "50")),
        )!!
        assertNull(s.maxspeedKmh)
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
    fun progressCarriesTheSnappedPointAndTheSegmentBearing() {
        // Beside the midpoint of a segment, 111 m north of an east-running road:
        // the snap lands *on* the line at the same longitude, and the bearing is
        // the road's rather than the rider's.
        val p = NavEngine.progress(route(), LatLon(50.001, 3.0045))!!
        assertEquals(50.0, p.snappedAt.lat, absoluteTolerance = 1e-6)
        assertEquals(3.0045, p.snappedAt.lon, absoluteTolerance = 1e-6)
        assertEquals(90.0, p.segmentBearingDeg!!, absoluteTolerance = 0.5)
    }

    @Test
    fun aRepeatedRoutePointHasNoBearingRatherThanNorth() {
        // Routers do emit the same point twice. A zero-length segment has no
        // direction, and calling that 0.0 would swing a heading-up camera to
        // north on a road running any other way.
        val doubled = RouteResult(
            polyline = listOf(LatLon(50.0, 3.0), LatLon(50.0, 3.0)),
            waypoints = emptyList(),
            distanceMeters = null,
        )
        val p = NavEngine.progress(doubled, LatLon(50.0, 3.0))!!
        assertNull(p.segmentBearingDeg)
        assertEquals(50.0, p.snappedAt.lat, absoluteTolerance = 1e-9)
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
    fun cutSplitsTheLineInTwoDisjointHalvesThatMeet() {
        val cut = NavEngine.cut(straightLine, 0.3)
        // The whole point: the halves share the cut point and nothing else, so
        // neither can be drawn over a stretch that belongs to the other.
        assertEquals(cut.behind.last(), cut.ahead.first())
        assertEquals(straightLine.first(), cut.behind.first())
        assertEquals(straightLine.last(), cut.ahead.last())
        assertEquals(
            straightLineMeters,
            NavEngine.lengthMeters(cut.behind) + NavEngine.lengthMeters(cut.ahead),
            absoluteTolerance = 0.5,
        )
        assertEquals(
            straightLineMeters * 0.3, NavEngine.lengthMeters(cut.behind), absoluteTolerance = 0.5)
        // No vertex of the line is in both halves.
        assertTrue(cut.behind.dropLast(1).none { it in cut.ahead })
    }

    @Test
    fun cutGivesTheWholeLineToWhicheverSideOwnsIt() {
        // Nothing driven: it is all still ahead, and there is no behind to draw.
        assertTrue(NavEngine.cut(straightLine, 0.0).behind.isEmpty())
        assertEquals(straightLine, NavEngine.cut(straightLine, 0.0).ahead)
        // Finished: all behind, and the ahead half is not a one-point stub.
        assertEquals(straightLine.size, NavEngine.cut(straightLine, 1.0).behind.size)
        assertTrue(NavEngine.cut(straightLine, 1.0).ahead.isEmpty())
        // Not a line at all.
        assertTrue(NavEngine.cut(listOf(LatLon(50.0, 3.0)), 0.5).behind.isEmpty())
        assertTrue(NavEngine.cut(emptyList(), 0.5).ahead.isEmpty())
    }

    @Test
    fun advanceSnapsThePositionOntoTheLine() {
        // Beside the line at its midpoint: the snapped point is the tail's far
        // end, so it has to land *on* the line, not beside it.
        val a = NavEngine.advance(straightLine, LatLon(50.02, 3.001), null)
        assertEquals(3.0, a.at.lon, absoluteTolerance = 1e-9)
        assertEquals(50.02, a.at.lat, absoluteTolerance = 1e-6)
        assertEquals(0.5, a.fraction, absoluteTolerance = 1e-3)
        assertEquals(straightLineMeters, a.lineMeters, absoluteTolerance = 0.5)
        // Mid-segment, so the snap interpolates rather than picking a vertex.
        val mid = NavEngine.advance(straightLine, LatLon(50.005, 3.0), null)
        assertEquals(50.005, mid.at.lat, absoluteTolerance = 1e-6)
        assertEquals(0, mid.index)
    }

    @Test
    fun advanceOnlyEverMovesForwardAlongTheLine() {
        // A line that doubles back on itself: the second half rides the first
        // half's tarmac in reverse, which is what makes a global nearest-point
        // search pick the wrong leg. Continuing from a snap on the outbound leg
        // must stay on it.
        val outAndBack = (0..4).map { LatLon(50.0 + it * 0.01, 3.0) } +
            (3 downTo 0).map { LatLon(50.0 + it * 0.01, 3.0) }
        val outbound = NavEngine.advance(outAndBack, LatLon(50.015, 3.0), null)
        assertEquals(1, outbound.index)
        // The same tarmac, further on — and just as near the return leg, which
        // is what a global search gets wrong. Continued from the outbound snap
        // it stays outbound.
        val next = NavEngine.advance(outAndBack, LatLon(50.025, 3.0), outbound)
        assertEquals(2, next.index)
        assertTrue(next.meters > outbound.meters)
        // And the window never walks backwards past the vertex it opened on.
        val behind = NavEngine.advance(outAndBack, LatLon(50.0, 3.0), next)
        assertTrue(behind.meters >= outbound.meters)
    }

    @Test
    fun advanceTurnsItsBearingWithTheRoadThroughACorner() {
        // North, then a right angle east. A corner is what the camera and the
        // marker's nose are judged on: each has to read the leg it is actually
        // on, not an average of the two or the leg it came from.
        val corner = (0..2).map { LatLon(50.0 + it * 0.01, 3.0) } +
            (1..2).map { LatLon(50.02, 3.0 + it * 0.01) }
        val before = NavEngine.advance(corner, LatLon(50.015, 3.0), null)
        assertEquals(0.0, before.bearingDeg!!, absoluteTolerance = 0.5)
        // Continued from the snap before the corner, so it is the windowed
        // search that has to walk round it.
        val after = NavEngine.advance(corner, LatLon(50.02, 3.015), before)
        assertEquals(90.0, after.bearingDeg!!, absoluteTolerance = 0.5)
    }

    @Test
    fun advanceClosesAGapItCannotSeeInOneStep() {
        // A resumed app: the position jumps far beyond the window. Each call
        // walks a window's worth, so a few frames close it rather than stalling
        // the seam where the app went to sleep.
        val long = (0..200).map { LatLon(50.0 + it * 0.001, 3.0) }
        var a = NavEngine.advance(long, LatLon(50.0, 3.0), null)
        val oneStep = NavEngine.advance(long, LatLon(50.19, 3.0), a)
        // One window is not enough to see it…
        assertTrue(oneStep.fraction < 0.2)
        // …but a handful of frames is, rather than the seam stalling where the
        // app went to sleep.
        repeat(16) { a = NavEngine.advance(long, LatLon(50.19, 3.0), a) }
        assertEquals(0.95, a.fraction, absoluteTolerance = 0.02)
    }

    @Test
    fun advanceSaysWhenThePositionIsBeyondItsWindow() {
        val long = (0..200).map { LatLon(50.0 + it * 0.001, 3.0) }
        val start = NavEngine.advance(long, LatLon(50.0, 3.0), null)
        // Far beyond: the snap clamps at the window's end and says so, and the
        // fresh search the marker loop does next lands where the rider is.
        val clamped = NavEngine.advance(long, LatLon(50.19, 3.0), start)
        assertTrue(clamped.beyondWindow)
        val fresh = NavEngine.advance(long, LatLon(50.19, 3.0), null)
        assertFalse(fresh.beyondWindow)
        assertEquals(0.95, fresh.fraction, absoluteTolerance = 0.02)
        // Inside the window: a snap, not a clamp.
        assertFalse(NavEngine.advance(long, LatLon(50.005, 3.0), start).beyondWindow)
        // Past the end of the line with the window reaching it: the clamp is
        // the destination, not a window running short.
        val nearEnd = NavEngine.advance(long, LatLon(50.195, 3.0), null)
        assertFalse(NavEngine.advance(long, LatLon(50.3, 3.0), nearEnd).beyondWindow)
    }

    @Test
    fun advanceDoesNotDriftAcrossThousandsOfFrames() {
        // A 24 km road running north-east from 51°N, ~600 m between vertices,
        // walked at 30 m/s and 60 frames a second — the marker loop's real
        // cadence over a plausible motorway leg, 39 000 frames of it.
        //
        // Diagonal on purpose, and this is the whole test: [segmentMeters]
        // scales longitude by the cosine of its own midpoint latitude, so a
        // part-segment and its whole are scaled by different numbers only when
        // the segment changes both. Due north or due east, the residual below
        // is identically zero and a fixture on either would pass whatever this
        // code did.
        val segments = 40
        val line = (0..segments).map { LatLon(51.0 + it * 0.004, 4.0 + it * 0.006) }
        // The line is straight in lat/lon, so a point on it is one parameter.
        val perMeter = segments / NavEngine.lengthMeters(line)
        fun at(metres: Double) = (metres * perMeter).let {
            LatLon(51.0 + it * 0.004, 4.0 + it * 0.006)
        }
        val frames = 39_000
        var walked = NavEngine.advance(line, line.first(), null)
        for (frame in 1..frames) walked = NavEngine.advance(line, at(frame * 0.5), walked)

        // Carrying the window's base from frame to frame has to land exactly
        // where a fresh, un-carried search lands. It used to be *recovered*
        // instead, by subtracting a part-segment back off the running total,
        // and that residual is one-signed: sixty times a second over this line
        // it walked the seam ~53 m off the rider — behind, heading north, and
        // ahead heading south, where it dims road not yet ridden.
        val fresh = NavEngine.advance(line, at(frames * 0.5), null)
        assertEquals(fresh.meters, walked.meters, absoluteTolerance = 0.01)
        // And it really did walk the line rather than sitting at the start.
        assertTrue(walked.meters > 19_000.0)
    }

    @Test
    fun drivenFractionIsRemainingTheOtherWayRound() {
        fun progress(remaining: Double, routeMeters: Double) = NavEngine.Progress(
            offRouteMeters = 0.0,
            snappedAt = LatLon(50.0, 3.0),
            segmentBearingDeg = null,
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

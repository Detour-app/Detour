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
    fun advanceReportsHowFarOffItsOwnLegTheRiderIs() {
        // Out and back on two lanes 71 m apart, long enough that the window
        // cannot see the return leg from the outbound one. Standing on the
        // return leg, `progress` — a global nearest-point search — calls this
        // on route, because the *other* leg is under the rider's feet. The
        // windowed snap is still on the outbound leg and says so, which is the
        // only honest answer for anything drawing a marker at that snap.
        val loop = (0..40).map { LatLon(50.0 + it * 0.001, 3.0) } +
            (39 downTo 0).map { LatLon(50.0 + it * 0.001, 3.001) }
        val pos = LatLon(50.02, 3.001)
        val route = RouteResult(polyline = loop, waypoints = emptyList(), distanceMeters = null)
        assertTrue(
            NavEngine.progress(route, pos)!!.offRouteMeters < 5.0,
            "the global snap should land on the return leg",
        )
        val outbound = NavEngine.advance(loop, LatLon(50.001, 3.0), null)
        val windowed = NavEngine.advance(loop, pos, outbound)
        assertTrue(
            windowed.offRouteMeters > 60.0,
            "the windowed snap is still on the outbound leg: got ${windowed.offRouteMeters}",
        )
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

/**
 * [roundaboutTurnDeg] reads the exit direction off the route polyline — the
 * signal the phone's roundabout glyph is drawn to. Synthetic lines along a
 * meridian / parallel so the bearings are exact by construction. Steps are
 * ~33 m so the 25 m sampling on each side clears one segment.
 */
class RoundaboutTurnTest {

    // idx 0..2 approach north, 2..4 the island arc, 4..6 the exit.
    private fun line(exit: List<LatLon>) = listOf(
        LatLon(0.0000, 0.0), LatLon(0.0003, 0.0), LatLon(0.0006, 0.0),
        LatLon(0.00063, 0.0), LatLon(0.00065, 0.0),
    ) + exit

    @Test
    fun straightThroughIsZero() {
        val l = line(listOf(LatLon(0.0009, 0.0), LatLon(0.0012, 0.0)))
        assertEquals(0.0, roundaboutTurnDeg(l, 2, 4)!!, 1.0)
    }

    @Test
    fun exitRightIsPositive() {
        val l = line(listOf(LatLon(0.00065, 0.0003), LatLon(0.00065, 0.0006)))
        assertEquals(90.0, roundaboutTurnDeg(l, 2, 4)!!, 2.0)
    }

    @Test
    fun exitLeftIsNegative() {
        val l = line(listOf(LatLon(0.00065, -0.0003), LatLon(0.00065, -0.0006)))
        assertEquals(-90.0, roundaboutTurnDeg(l, 2, 4)!!, 2.0)
    }

    @Test
    fun tooShortToSampleReturnsNull() {
        assertNull(roundaboutTurnDeg(listOf(LatLon(0.0, 0.0), LatLon(0.001, 0.0)), 0, 1))
    }

    @Test
    fun badIndicesReturnNull() {
        val l = line(listOf(LatLon(0.0009, 0.0)))
        assertNull(roundaboutTurnDeg(l, 4, 2))
        assertNull(roundaboutTurnDeg(l, 0, 99))
    }
}

/**
 * A GraphHopper roundabout instruction's `interval` ends at the *next* maneuver,
 * not where the route leaves the ring — so [RoutingClient.parseRoute] has to take
 * the exit point from the `roundabout` path detail instead. Geometry: approach
 * due north, leave due east (a +90° right turn), then the exit road bends back
 * north well past the exit. Reading the turn at `interval[1]` sees the bend and
 * reports ~0°; reading it at the ring exit reports ~90°.
 */
class RoundaboutParseTest {

    private val coords = listOf(
        "[0.0,51.0000]", "[0.0,51.0004]", "[0.0,51.0008]",   // approach + entry (idx 2)
        "[0.0002,51.0009]", "[0.0006,51.0009]",               // ring, then exit (idx 4)
        "[0.0016,51.0009]", "[0.0026,51.0009]",               // exit road, due east
        "[0.0027,51.0018]", "[0.00272,51.0028]", "[0.00272,51.0038]", // bends back north
    ).joinToString(",")

    private fun json(roundaboutDetail: String) = """
        {"paths":[{
          "points":{"type":"LineString","coordinates":[$coords]},
          "instructions":[
            {"text":"At roundabout, take exit 1 onto Test","distance":120,"sign":6,"exit_number":1,"interval":[2,7]},
            {"text":"Turn left","distance":40,"sign":-2,"interval":[7,9]},
            {"text":"Arrive","distance":0,"sign":4,"interval":[9,9]}
          ],
          "details":{"roundabout":[$roundaboutDetail]}
        }]}
    """.trimIndent()

    @Test
    fun exitAngleComesFromTheRingExitNotTheNextManeuver() {
        val route = RoutingClient.parseRoute(json("[0,2,false],[2,4,true],[4,9,false]"))
        val turn = route.instructions.first { it.sign == 6 }.roundaboutTurnDeg!!
        assertEquals(90.0, turn, 6.0, "right-turn exit should read ~+90°, got $turn")
    }

    @Test
    fun withoutTheDetailItFallsBackToTheInstructionInterval() {
        // interval[1] sits on the northbound bend, so the fallback misreads the
        // same right turn as roughly straight-through — the bug this detail fixes.
        val route = RoutingClient.parseRoute(json("[0,9,false]"))
        val turn = route.instructions.first { it.sign == 6 }.roundaboutTurnDeg!!
        assertTrue(turn < 30.0, "fallback reads the bend, not the exit: got $turn")
    }
}

/**
 * [headingQuery] is the GraphHopper `heading` hint a reroute appends so the
 * fresh line continues in the rider's direction of travel instead of opening
 * with a U-turn.
 */
class HeadingQueryTest {

    @Test
    fun noHintIsAnEmptyFragment() {
        assertEquals("", headingQuery(null))
    }

    @Test
    fun theHintCarriesTheDegreesAndThePenalty() {
        assertEquals("&heading=90&heading_penalty=1200", headingQuery(HeadingHint(90.4, 1200)))
    }

    @Test
    fun degreesWrapIntoTheZeroToThreeSixtyRange() {
        assertEquals(10, headingDegInt(370.0))
        assertEquals(350, headingDegInt(-10.0))
        assertEquals(0, headingDegInt(360.0))
    }
}

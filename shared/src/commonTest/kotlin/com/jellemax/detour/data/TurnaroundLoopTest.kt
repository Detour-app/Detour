package com.jellemax.detour.data

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [TurnaroundLoop] (#889): the overlap metric that decides whether the way
 * back is a different road, the penalty corridor that pushes it onto one, the
 * pick rule over the rolled ways back, how the two legs become one route, and
 * the sentences the rider gets when there is no loop — all against straight
 * synthetic roads and a fake router, so every number is one a reader can
 * check by hand.
 *
 * `runBlocking` rather than `runTest`, for the reason [SingleFlightTest] gives.
 */
class TurnaroundLoopTest {

    private val home = LatLon(50.85, 5.69)

    /** [p] moved [east] and [north] metres. */
    private fun at(p: LatLon, east: Double, north: Double = 0.0) = LatLon(
        p.lat + north / 111_320.0,
        p.lon + east / (111_320.0 * cos(toRadians(p.lat))),
    )

    /** A straight road from [a] to [b] with a vertex every 100 m or so. */
    private fun road(a: LatLon, b: LatLon): List<LatLon> {
        val n = (RoadRoulette.distanceMeters(a, b) / 100).toInt().coerceAtLeast(1)
        return (0..n).map { i -> LatLon(a.lat + (b.lat - a.lat) * i / n, a.lon + (b.lon - a.lon) * i / n) }
    }

    /** Several straight roads end to end, without doubling the joints. */
    private fun roads(vararg corners: LatLon): List<LatLon> =
        corners.toList().zipWithNext().flatMapIndexed { i, (a, b) -> road(a, b).drop(if (i == 0) 0 else 1) }

    private val turn = at(home, east = 20_000.0)
    private val wayOut = road(home, turn)

    /** Home another way: a kilometre north, back west, and down to home. */
    private val parallelBack = roads(turn, at(turn, 0.0, 1_000.0), at(home, 0.0, 1_000.0), home)

    private fun route(line: List<LatLon>, instructions: List<NavInstruction> = emptyList()) = RouteResult(
        polyline = line,
        waypoints = emptyList(),
        distanceMeters = line.zipWithNext().sumOf { (a, b) -> RoadRoulette.distanceMeters(a, b) },
        instructions = instructions,
        timeMs = 1_000L,
    )

    // ---- overlap ---------------------------------------------------------

    @Test
    fun theSameRoadBackIsAllOverlap() {
        assertEquals(1.0, overlapFraction(wayOut, wayOut.reversed()), absoluteTolerance = 1e-9)
    }

    @Test
    fun aParallelRoadAKilometreAwayIsNoOverlap() {
        assertEquals(0.0, overlapFraction(wayOut, parallelBack), absoluteTolerance = 1e-9)
    }

    @Test
    fun theOtherCarriagewayThirtyMetresOverIsStillTheSameRoad() {
        val otherSide = road(at(turn, 0.0, 30.0), at(home, 0.0, 30.0))
        assertEquals(1.0, overlapFraction(wayOut, otherSide), absoluteTolerance = 1e-9)
    }

    @Test
    fun theOneRoadOutOfTheRidersStreetIsNotCounted() {
        // Shares the last 800 m into home with the way out, and nothing else:
        // that stretch is inside the uncounted kilometre at the end.
        val shared = at(home, east = 800.0)
        val back = roads(turn, at(turn, 0.0, 1_000.0), at(shared, 0.0, 1_000.0), shared, home)
        assertEquals(0.0, overlapFraction(wayOut, back), absoluteTolerance = 1e-9)
    }

    @Test
    fun aLongStraightSegmentThatOnlyCrossesTheWayOutCountsOnlyTheCrossing() {
        // One 10 km segment, north to south, through the middle of the way
        // out. Judged by its midpoint alone it would be 100 % overlap.
        val mid = at(home, east = 10_000.0)
        val crossing = listOf(at(mid, 0.0, 5_000.0), at(mid, 0.0, -5_000.0))
        val overlap = overlapFraction(wayOut, crossing)
        assertTrue(overlap in 0.005..0.02, "got $overlap")
    }

    @Test
    fun aWayBackTooShortToJudgeOutsideItsEndsIsNoOverlap() {
        assertEquals(0.0, overlapFraction(wayOut, road(at(home, 1_500.0), home)), absoluteTolerance = 1e-9)
    }

    // ---- the penalty corridor ---------------------------------------------

    /** A long twisty road: 2,000 vertices of sine wave wandering east. */
    private val twisty = (0..2_000).map { i ->
        at(home, east = i * 40.0, north = 600.0 * sin(i / 15.0))
    }

    @Test
    fun aTwistyWayOutIsSimplifiedUnderTheAreaCap() {
        val c = corridor(twisty)
        assertTrue(c.segments.size <= TurnaroundLoop.MAX_AREAS, "got ${c.segments.size}")
        assertTrue(c.halfWidthMeters > TurnaroundLoop.SAME_ROAD_M)
    }

    @Test
    fun theCorridorStillCoversEveryPointOfTheWayOutBySameRoadWidth() {
        // The coverage promise: widening each rectangle by the simplification
        // tolerance means no vertex of the real road sits closer than
        // SAME_ROAD_M to a rectangle's edge. 2 % slack for the flat
        // projection Douglas–Peucker works in.
        val c = corridor(twisty)
        val slack = c.halfWidthMeters - TurnaroundLoop.SAME_ROAD_M
        for (p in twisty) {
            val nearest = c.segments.minOf { (a, b) -> RoadRoulette.distanceToSegmentMeters(p, a, b) }
            assertTrue(nearest <= slack * 1.02 + 0.5, "$p is $nearest m off, corridor allows $slack")
        }
    }

    @Test
    fun aStraightWayOutIsOneArea() {
        val c = corridor(wayOut)
        assertEquals(1, c.segments.size)
        assertEquals(TurnaroundLoop.SAME_ROAD_M * 1.5, c.halfWidthMeters, absoluteTolerance = 1e-9)
    }

    @Test
    fun thePenaltyIsOneClosedRectangleFeaturePerSegmentAndOneRuleOverAllOfThem() {
        val c = corridor(twisty)
        val p = c.penalty(0.1)
        val features = p.areas["features"]!!.jsonArray
        assertEquals(c.segments.size, features.size)
        for (f in features) {
            val ring = f.jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray[0].jsonArray
            // A GeoJSON ring is closed: five positions for four corners.
            assertEquals(5, ring.size)
            assertEquals(ring.first(), ring.last())
        }
        val rule = p.rule["if"]!!.jsonPrimitive.content
        assertEquals(c.segments.size, rule.split(" || ").size)
        assertTrue(rule.startsWith("in_outbound0 || in_outbound1"), rule)
        assertEquals(0.1, p.rule["multiply_by"]!!.jsonPrimitive.double, absoluteTolerance = 1e-9)
    }

    @Test
    fun aRectangleReachesHalfItsWidthPastBothEndsAndToBothSides() {
        val b = at(home, east = 1_000.0)
        val ring = rectangle(home, b, 50.0)
        val inside = listOf(at(home, -45.0, 45.0), at(b, 45.0, -45.0))
        val minEast = ring.minOf { it.lon }
        val maxEast = ring.maxOf { it.lon }
        val minNorth = ring.minOf { it.lat }
        val maxNorth = ring.maxOf { it.lat }
        for (p in inside) assertTrue(p.lon in minEast..maxEast && p.lat in minNorth..maxNorth, "$p outside $ring")
        // 0.2 % slack: distanceMeters is haversine on a 6,371 km sphere, the
        // rectangle is laid out at 111,320 m per degree, and the two differ by
        // 0.11 % — 1.25 m on this long side.
        assertEquals(1_100.0, RoadRoulette.distanceMeters(ring[0], ring[1]), absoluteTolerance = 2.2)
        assertEquals(100.0, RoadRoulette.distanceMeters(ring[1], ring[2]), absoluteTolerance = 0.2)
    }

    @Test
    fun aPenalisedRouteIsAlwaysAPostWithTheAreasAndTheRuleLast() {
        // No avoid option on: without the penalty this is a plain GET, but
        // areas only travel in a custom model, so it must become a POST.
        val penalty = corridor(wayOut).penalty(0.1)
        val request = routeViaRequest(
            "https://gh.example", listOf(turn, home), "moto",
            RoutePreferences(avoidTolls = true), heading = null, penalty = penalty,
        )
        val body = assertNotNull(request.body)
        assertEquals("https://gh.example/route", request.url)
        val json = jsonObjectOf(body)
        val model = assertNotNull(json.optObject("custom_model"))
        val priority = assertNotNull(model.optArray("priority"))
        assertEquals("in_outbound0", priority.optObject(priority.size - 1)?.optString("if"))
        assertEquals("toll == ALL", priority.optObject(0)?.optString("if"))
        assertNotNull(model.optObject("areas"))
        assertTrue(json.optBoolean("ch.disable"))
    }

    @Test
    fun noPenaltyLeavesThePlainRouteAGet() {
        val request = routeViaRequest("https://gh.example", listOf(turn, home), "moto", RoutePreferences(), null)
        assertNull(request.body)
    }

    // ---- the pick rule ----------------------------------------------------

    private fun scored(overlap: Double, curviness: Double, meters: Double = 25_000.0) = ScoredReturn(
        route = route(parallelBack).copy(distanceMeters = meters),
        overlap = overlap,
        curviness = curviness,
    )

    @Test
    fun theCurviestWayBackThatClearsTheOverlapBarWins() {
        val twistyAndDifferent = scored(overlap = 0.1, curviness = 0.4)
        val picked = pickReturn(
            listOf(scored(0.05, 0.2), twistyAndDifferent, scored(0.6, 0.9)),
            outboundMeters = 20_000.0,
        )
        // The 0.9 one is curvier still, but it is mostly the way out again.
        assertEquals(twistyAndDifferent, picked)
    }

    @Test
    fun withNoDifferentRoadTheLeastOverlapWinsWhateverItsBends() {
        val least = scored(overlap = 0.3, curviness = 0.0)
        assertEquals(least, pickReturn(listOf(scored(0.9, 0.8), least, scored(0.5, 0.5)), 20_000.0))
    }

    @Test
    fun aWayBackStretchedPastItsLimitLosesToAnHonestOverlap() {
        // A 60 km way back from 20 km out is a tour, not home another way.
        val tour = scored(overlap = 0.0, curviness = 0.9, meters = 60_000.0)
        val honest = scored(overlap = 0.4, curviness = 0.1)
        assertEquals(honest, pickReturn(listOf(tour, honest), 20_000.0))
        // ...unless the tour is all there is.
        assertEquals(tour, pickReturn(listOf(tour), 20_000.0))
        assertNull(pickReturn(emptyList(), 20_000.0))
    }

    // ---- joining the legs -------------------------------------------------

    @Test
    fun theTwoLegsBecomeOneRouteWithTheArrivalTurnedIntoAVia() {
        val out = route(
            wayOut,
            listOf(
                NavInstruction("Continue", 20_000.0, 0, 0, wayOut.lastIndex),
                NavInstruction("Arrive at destination", 0.0, 4, wayOut.lastIndex, wayOut.lastIndex),
            ),
        ).copy(speedLimits = listOf(SpeedLimitSegment(0, wayOut.lastIndex, 90.0)))
        val back = route(
            parallelBack,
            listOf(
                NavInstruction("Turn right", 1_000.0, 2, 0, 10),
                NavInstruction("Arrive at destination", 0.0, 4, parallelBack.lastIndex, parallelBack.lastIndex),
            ),
        ).copy(speedLimits = listOf(SpeedLimitSegment(0, parallelBack.lastIndex, 70.0)))

        val loop = joinLegs(out, back)
        val shift = wayOut.lastIndex

        // The joint is one point, not two.
        assertEquals(wayOut.size + parallelBack.size - 1, loop.polyline.size)
        assertEquals(home, loop.polyline.first())
        assertEquals(parallelBack.last(), loop.polyline.last())
        assertEquals(5, loop.instructions[1].sign)
        assertEquals(TurnaroundLoop.TURNAROUND_TEXT, loop.instructions[1].text)
        assertEquals(shift, loop.instructions[2].startIndex)
        assertEquals(shift + 10, loop.instructions[2].endIndex)
        // Only the way back's own arrival is still a finish.
        assertEquals(listOf(4), loop.instructions.filter { it.sign == 4 }.map { it.sign })
        assertEquals(loop.polyline.lastIndex, loop.instructions.last().startIndex)
        assertEquals(SpeedLimitSegment(shift, loop.polyline.lastIndex, 70.0), loop.speedLimits[1])
        assertEquals(out.distanceMeters!! + back.distanceMeters!!, loop.distanceMeters!!, absoluteTolerance = 1e-6)
        assertEquals(2_000L, loop.timeMs)
        // Nine hand-off points, the turnaround among them, so an external
        // nav app also rides out and back the two different ways.
        assertEquals(9, loop.waypoints.size)
        assertEquals(wayOut.last(), loop.waypoints[4])
    }

    // ---- planning ---------------------------------------------------------

    private val request = TurnaroundRequest(home, turn, "moto", RoutePreferences())

    private fun strengthOf(penalty: AreaPenalty) = penalty.rule["multiply_by"]!!.jsonPrimitive.double

    @Test
    fun aDifferentRoadHomeIsRiddenWhenTheStrongerPenaltyFindsOne() = runBlocking {
        val starts = mutableListOf<LatLon>()
        val result = TurnaroundLoop.plan(
            request, serverUsable = true,
            outbound = { route(wayOut) },
            back = { start, penalty ->
                starts += start
                // Only the strongest penalty pushes the way back off the valley road.
                if (strengthOf(penalty) < 0.05) route(parallelBack) else route(wayOut.reversed())
            },
        )
        assertEquals(0.0, result.overlap, absoluteTolerance = 1e-9)
        assertNull(result.warning)
        assertEquals(wayOut.size + parallelBack.size - 1, result.route.polyline.size)
        // Every way back starts where the way out ended, so the legs join.
        assertEquals(List(3) { wayOut.last() }, starts)
    }

    @Test
    fun aValleyWithOneRoadStillGivesALoopAndSaysWhy() = runBlocking {
        val result = TurnaroundLoop.plan(
            request, serverUsable = true,
            outbound = { route(wayOut) },
            back = { _, _ -> route(wayOut.reversed()) },
        )
        assertEquals(1.0, result.overlap, absoluteTolerance = 1e-9)
        assertEquals("No separate road back from there — 100 % of the way back retraces the way out.", result.warning)
    }

    @Test
    fun noRoutingServerIsASentenceNotAnOutAndBack() = runBlocking {
        val e = assertFailsWith<SpinFailure> {
            TurnaroundLoop.plan(request, serverUsable = false, { error("no call") }, { _, _ -> error("no call") })
        }
        assertEquals(
            "Couldn't plan the loop: no routing server is set up. Add one in Settings → Servers & sync.",
            e.message,
        )
    }

    @Test
    fun aPointRightNextToHomeIsRefusedBeforeAnyRequest() = runBlocking {
        val e = assertFailsWith<SpinFailure> {
            TurnaroundLoop.plan(
                request.copy(turnaround = at(home, east = 500.0)), serverUsable = true,
                { error("no call") }, { _, _ -> error("no call") },
            )
        }
        assertEquals("That point is too close to turn around at — pick one further out.", e.message)
    }

    @Test
    fun noRoadToThePointSaysSo() = runBlocking {
        val e = assertFailsWith<SpinFailure> {
            TurnaroundLoop.plan(
                request, serverUsable = true,
                outbound = { throw NoRouteException("Routing server error: HTTP 400") },
                back = { _, _ -> error("no call") },
            )
        }
        assertEquals("Couldn't route to that point: no road connects those points. Try moving them.", e.message)
    }

    @Test
    fun everyWayBackFailingIsAFailureAndNeverTheWayOutTwice() = runBlocking {
        val e = assertFailsWith<SpinFailure> {
            TurnaroundLoop.plan(
                request, serverUsable = true,
                outbound = { route(wayOut) },
                back = { _, _ -> throw HttpStatusException(503, "") },
            )
        }
        assertEquals(
            "Couldn't find a road back from that point: the server hit a problem. Try again later.",
            e.message,
        )
    }

    @Test
    fun aStalledRollDoesNotCostTheWayBackThatLanded() = runBlocking {
        val result = TurnaroundLoop.plan(
            request, serverUsable = true,
            outbound = { route(wayOut) },
            back = { _, penalty ->
                if (strengthOf(penalty) < 0.05) route(parallelBack) else awaitCancellation()
            },
            timeoutMs = 300,
        )
        assertEquals(0.0, result.overlap, absoluteTolerance = 1e-9)
    }

    @Test
    fun runningOutOfTimeWithNoWayBackIsTheRetryableSentence() = runBlocking {
        val e = assertFailsWith<SpinFailure> {
            TurnaroundLoop.plan(
                request, serverUsable = true,
                outbound = { route(wayOut) },
                back = { _, _ -> awaitCancellation() },
                timeoutMs = 300,
            )
        }
        assertEquals("The routing server is too slow or unreachable — try again", e.message)
    }
}

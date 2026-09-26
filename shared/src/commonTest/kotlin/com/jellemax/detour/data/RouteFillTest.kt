package com.jellemax.detour.data

import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import okio.IOException

/**
 * [RouteFill]'s geometry, and its calibration loop against a fake router whose
 * travel time is the straight-line length of the points it is given times a
 * road factor, at a fixed speed — close enough to a real router's shape that
 * the rounds have something to converge on, and deterministic.
 *
 * `runBlocking` rather than `runTest`, for the reason [SingleFlightTest] gives.
 */
class RouteFillTest {

    private val home = LatLon(50.85, 5.69)
    private val cafe = RoadRoulette.offset(home, 8_000.0, 0.0) // 8 km due north

    private fun straightMeters(points: List<LatLon>) =
        points.zipWithNext { a, b -> RoadRoulette.distanceMeters(a, b) }.sum()

    /** 1.4 × the straight line at 60 km/h. */
    private fun fakeRouter(calls: MutableList<List<LatLon>> = mutableListOf()): suspend (List<LatLon>) -> RouteResult =
        { points ->
            calls.add(points)
            val meters = straightMeters(points) * 1.4
            RouteResult(
                polyline = points,
                waypoints = emptyList(),
                distanceMeters = meters,
                timeMs = (meters / (60_000.0 / 3_600_000.0)).toLong(),
            )
        }

    @Test
    fun apexOffsetLengthensAStraightLegByExactlyTheExtraAskedFor() {
        val leg = 10_000.0
        val h = RouteFill.apexOffsetMeters(leg, 4_000.0)
        val bent = 2 * kotlin.math.sqrt((leg / 2) * (leg / 2) + h * h)
        assertEquals(leg + 4_000.0, bent, 1e-6)
    }

    @Test
    fun aLegWithNoLengthRidesATriangleOfAThirdTheExtra() {
        assertEquals(1_000.0, RouteFill.apexOffsetMeters(0.0, 3_000.0), 1e-9)
        assertEquals(0.0, RouteFill.apexOffsetMeters(5_000.0, 0.0), 1e-9)
    }

    @Test
    fun insertFillKeepsTheRidersStopsInOrderAndOnlyAddsFillBetweenThem() {
        val stops = listOf(RouteStop(home, "Home"), RouteStop(cafe, "Café"), RouteStop(home, "Home"))
        val filled = RouteFill.insertFill(stops, 6_000.0, RouteFill.Layout(side = 1, along = 0.5, looseBearingDeg = 0.0))
        assertEquals(stops, RouteFill.mandatory(filled))
        assertEquals(5, filled.size)
        assertTrue(filled.filter(RouteFill::isFill).size == 2)
    }

    @Test
    fun anOutAndBackBendsBothLegsToTheSameSideOfTravelSoItBecomesALoop() {
        val stops = listOf(RouteStop(home), RouteStop(cafe), RouteStop(home))
        val filled = RouteFill.insertFill(stops, 6_000.0, RouteFill.Layout(side = 1, along = 0.5, looseBearingDeg = 0.0))
        val (out, back) = filled.filter(RouteFill::isFill)
        // Northbound, right is east; southbound, right is west.
        assertTrue(out.at.lon > home.lon)
        assertTrue(back.at.lon < home.lon)
    }

    @Test
    fun fillingAnOutAndBackLandsWithinToleranceOfTheTargetTime() = runBlocking {
        val stops = listOf(RouteStop(home, "Home"), RouteStop(cafe, "Café"), RouteStop(home, "Home"))
        val result = RouteFill.fill(stops, 60f, fakeRouter(), Random(7))
        assertTrue(LoopDuration.fits(result.route, 60f), "got ${result.route.timeMs}")
        assertEquals(stops, RouteFill.mandatory(result.stops))
    }

    @Test
    fun aLoneStopIsFilledAsALoopFromAndBackToIt() = runBlocking {
        val result = RouteFill.fill(listOf(RouteStop(home, "Home")), 30f, fakeRouter(), Random(3))
        assertTrue(LoopDuration.fits(result.route, 30f), "got ${result.route.timeMs}")
        assertEquals(home, result.stops.first().at)
        assertEquals(home, result.stops.last().at)
        // The closing copy of the stop is fill too, so removing the fill gives
        // back the one stop that was placed, not [home, home].
        assertEquals(listOf(RouteStop(home, "Home")), RouteFill.mandatory(result.stops))
    }

    @Test
    fun refillingIgnoresTheEarlierFill() = runBlocking {
        val stops = listOf(RouteStop(home, "Home"), RouteStop(cafe, "Café"))
        val first = RouteFill.fill(stops, 45f, fakeRouter(), Random(1))
        val second = RouteFill.fill(first.stops, 90f, fakeRouter(), Random(2))
        assertEquals(stops, RouteFill.mandatory(second.stops))
        assertTrue(LoopDuration.fits(second.route, 90f), "got ${second.route.timeMs}")
    }

    @Test
    fun stopsThatAlreadyFitComeBackUnfilled() = runBlocking {
        // 8 km × 1.4 at 60 km/h ≈ 11.2 min.
        val stops = listOf(RouteStop(home), RouteStop(cafe))
        val result = RouteFill.fill(stops, 11f, fakeRouter(), Random(1))
        assertEquals(stops, result.stops)
    }

    @Test
    fun stopsLongerThanTheTargetSayHowLongTheyTake() = runBlocking {
        val far = RoadRoulette.offset(home, 80_000.0, 0.0)
        val e = assertFailsWith<StopsTooLong> {
            RouteFill.fill(listOf(RouteStop(home), RouteStop(far)), 30f, fakeRouter(), Random(1))
        }
        assertTrue(e.stopsMs > 30 * 60_000)
    }

    @Test
    fun aRouterThatRefusesEveryFillIsReportedNotSwallowed() = runBlocking {
        val calls = mutableListOf<List<LatLon>>()
        val router = fakeRouter(calls)
        val refusesFill: suspend (List<LatLon>) -> RouteResult = { points ->
            if (points.size > 2) throw IOException("could not find a valid point") else router(points)
        }
        assertFailsWith<IOException> {
            RouteFill.fill(listOf(RouteStop(home), RouteStop(cafe)), 60f, refusesFill, Random(1))
        }
        Unit
    }
}

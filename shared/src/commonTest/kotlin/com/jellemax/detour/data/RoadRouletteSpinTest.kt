package com.jellemax.detour.data

import com.jellemax.detour.presentation.spinFailureText
import kotlinx.coroutines.runBlocking
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [RoadRoulette.randomRoadPoint] against a fake road fetch: a road spin with no point to give
 * tells the rider the server is down when no fetch got an answer, and that the area has no
 * roads when one did (#1030).
 *
 * `runBlocking` rather than `runTest`, for the reason [SingleFlightTest] gives.
 */
class RoadRouletteSpinTest {

    private val center = LatLon(51.05, 3.72)

    private fun spin(radiusMeters: Double, fetch: suspend (LatLon, Double) -> List<OverpassWay>) = runBlocking {
        RoadRoulette.randomRoadPoint(center, radiusMeters, bearingDeg = null, explored = null, minRadiusMeters = 0.0, fetch)
    }

    @Test
    fun anUnreachableServerOnASmallSpinIsReportedAsTheConnection() {
        val down = IOException("connect failed")
        val e = assertFailsWith<IOException> { spin(1_000.0) { _, _ -> throw down } }
        assertSame(down, e)
        assertFalse(spinFailureText(e).contains(RoadRoulette.NO_ROADS_MESSAGE))
    }

    @Test
    fun anUnreachableServerOnALargeSpinIsReportedAsTheConnection() {
        val down = IOException("connect failed")
        val e = assertFailsWith<IOException> { spin(10_000.0) { _, _ -> throw down } }
        assertSame(down, e)
        assertFalse(e is SpinFailure)
    }

    @Test
    fun aServerThatAnsweredWithNoRoadsStillSaysNoRoads() {
        for (radius in listOf(1_000.0, 10_000.0)) {
            val e = assertFailsWith<SpinFailure> { spin(radius) { _, _ -> emptyList() } }
            assertEquals(RoadRoulette.NO_ROADS_MESSAGE, e.message)
        }
    }

    @Test
    fun aWiderSearchThatGotAnAnswerCountsAsAnswered() {
        // First (600 m) fetch fails, every wider one answers empty: the server is up, the
        // area is empty.
        val e = assertFailsWith<SpinFailure> {
            spin(10_000.0) { _, r -> if (r < 1_000.0) throw IOException("blip") else emptyList() }
        }
        assertEquals(RoadRoulette.NO_ROADS_MESSAGE, e.message)
    }

    @Test
    fun aFailedFirstFetchStillFindsARoadOnTheNext() {
        var calls = 0
        val point = spin(10_000.0) { _, _ ->
            if (calls++ == 0) throw IOException("blip")
            listOf(OverpassWay(listOf(0L, 0L), listOf(center, LatLon(center.lat + 0.0005, center.lon))))
        }
        assertTrue(RoadRoulette.distanceMeters(center, point) < 100.0)
        assertEquals(2, calls)
    }
}

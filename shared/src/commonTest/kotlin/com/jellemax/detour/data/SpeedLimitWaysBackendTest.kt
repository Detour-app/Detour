package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [RoadRoulette.parseSpeedLimitsResponse] (issue #379 backend bbox response). The wire
 * shape is `SpeedLimitWayDto`/`SpeedLimitsBboxResponse`
 * (`backend/Detour/Detour.Api/Contracts/SpeedLimitContracts.cs`), camelCase JSON.
 * [RoadRoulette.speedLimitWays]/`speedLimitWaysViaBackend` cannot be exercised here (need a
 * live backend) — this is the pure half that can, same split [SpeedCamerasTest]'s
 * `parseCamerasResponse` tests use.
 */
class SpeedLimitWaysBackendTest {

    @Test
    fun aWayWithAPolylineAndLimitParses() {
        val body = jsonObjectOf(
            """{"ways":[{"id":"1","maxSpeedKmh":70,"polyline":[[49.6,6.1],[49.61,6.11]]}]}""",
        )
        val ways = RoadRoulette.parseSpeedLimitsResponse(body)
        assertEquals(1, ways.size)
        assertEquals(70.0, ways[0].kmh)
        assertEquals(listOf(LatLon(49.6, 6.1), LatLon(49.61, 6.11)), ways[0].points)
    }

    @Test
    fun anEmptyWaysArrayProducesAnEmptyResult() {
        val body = jsonObjectOf("""{"ways":[]}""")
        assertEquals(emptyList(), RoadRoulette.parseSpeedLimitsResponse(body))
    }

    /** A way with fewer than two polyline points is dropped rather than producing a
     *  zero-length snap target — same rule [SpeedCameras.parseCamerasResponse] applies to a
     *  too-short section polyline. */
    @Test
    fun aWayWithATooShortPolylineIsDropped() {
        val body = jsonObjectOf(
            """{"ways":[{"id":"2","maxSpeedKmh":50,"polyline":[[49.6,6.1]]}]}""",
        )
        assertEquals(emptyList(), RoadRoulette.parseSpeedLimitsResponse(body))
    }

    @Test
    fun multipleWaysAllParse() {
        val body = jsonObjectOf(
            """{"ways":[
                {"id":"1","maxSpeedKmh":70,"polyline":[[49.6,6.1],[49.61,6.11]]},
                {"id":"2","maxSpeedKmh":50,"polyline":[[49.7,6.2],[49.71,6.21]]}
            ]}""",
        )
        val ways = RoadRoulette.parseSpeedLimitsResponse(body)
        assertEquals(2, ways.size)
        assertEquals(setOf(70.0, 50.0), ways.map { it.kmh }.toSet())
    }
}

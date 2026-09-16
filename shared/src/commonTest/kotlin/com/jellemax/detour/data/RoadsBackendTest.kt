package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [RoadRoulette.parseRoadsResponse] (issue #380/#382 backend bbox response, spin's own
 * half — see `RoadTypeTrackerTest`'s `parseRoadsResponse` tests for `RoadTypeTracker`'s own
 * parse of the same wire shape, which keeps every class instead of filtering by regex). The
 * wire shape is `RoadWayDto`/`RoadsBboxResponse`
 * (`backend/Detour/Detour.Api/Contracts/RoadContracts.cs`), camelCase JSON.
 * [RoadRoulette.fetchRoads]/`fetchRoadsViaBackend` cannot be exercised here (need a live
 * backend) — this is the pure half that can, same split [SpeedLimitWaysBackendTest] uses.
 */
class RoadsBackendTest {

    private val carRegex = "^(${RoadRoulette.DRIVABLE_HIGHWAYS})$"

    @Test
    fun aMatchingWayWithAPolylineParses() {
        val body = jsonObjectOf(
            """{"ways":[{"id":"1","highway":"primary","polyline":[[49.6,6.1],[49.61,6.11]]}]}""",
        )
        val ways = RoadRoulette.parseRoadsResponse(body, carRegex)
        assertEquals(1, ways.size)
        assertEquals(listOf(LatLon(49.6, 6.1), LatLon(49.61, 6.11)), ways[0].points)
    }

    @Test
    fun aWayWhoseClassDoesNotMatchTheRegexIsDropped() {
        // "footway" is not in DRIVABLE_HIGHWAYS at all — mirrors what the Overpass tag filter
        // would have excluded server-side, now applied client-side against the full set the
        // backend returns.
        val body = jsonObjectOf(
            """{"ways":[{"id":"1","highway":"footway","polyline":[[49.6,6.1],[49.61,6.11]]}]}""",
        )
        assertEquals(emptyList(), RoadRoulette.parseRoadsResponse(body, carRegex))
    }

    @Test
    fun aModeSpecificRegexExcludesAClassOutsideItsSubset() {
        // Moto's own subset (RoadRoulette.kt's TravelMode.MOTO highwayRegex shape) excludes
        // "residential", which the car regex would keep.
        val motoRegex = "^(primary|secondary|tertiary|unclassified)$"
        val body = jsonObjectOf(
            """{"ways":[{"id":"1","highway":"residential","polyline":[[49.6,6.1],[49.61,6.11]]}]}""",
        )
        assertEquals(emptyList(), RoadRoulette.parseRoadsResponse(body, motoRegex))
    }

    @Test
    fun anEmptyWaysArrayProducesAnEmptyResult() {
        val body = jsonObjectOf("""{"ways":[]}""")
        assertEquals(emptyList(), RoadRoulette.parseRoadsResponse(body, carRegex))
    }

    /** A way with fewer than two polyline points is dropped rather than producing a
     *  zero-length segment — same rule [SpeedLimitWaysBackendTest] applies. */
    @Test
    fun aWayWithATooShortPolylineIsDropped() {
        val body = jsonObjectOf(
            """{"ways":[{"id":"2","highway":"primary","polyline":[[49.6,6.1]]}]}""",
        )
        assertEquals(emptyList(), RoadRoulette.parseRoadsResponse(body, carRegex))
    }

    @Test
    fun multipleMatchingWaysAllParse() {
        val body = jsonObjectOf(
            """{"ways":[
                {"id":"1","highway":"primary","polyline":[[49.6,6.1],[49.61,6.11]]},
                {"id":"2","highway":"residential","polyline":[[49.7,6.2],[49.71,6.21]]}
            ]}""",
        )
        val ways = RoadRoulette.parseRoadsResponse(body, carRegex)
        assertEquals(2, ways.size)
    }
}

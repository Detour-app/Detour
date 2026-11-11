package com.jellemax.detour.data

import kotlin.math.PI
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers RouteOverlap.kt: how much of a turnaround loop's return (#889) runs on
 * its way out. A wrong number here picks a return that rides home the same road.
 */
class RouteOverlapTest {

    private val origin = LatLon(51.0, 4.0)
    private val mPerDeg = 6_371_000.0 * PI / 180.0

    /** Metres east/north of [origin] as a coordinate. */
    private fun at(east: Double, north: Double) = LatLon(
        origin.lat + north / mPerDeg,
        origin.lon + east / (mPerDeg * cos(origin.lat * PI / 180.0)),
    )

    /** A polyline through the given (east, north) corners, a vertex every 50 m. */
    private fun path(vararg corners: Pair<Double, Double>): List<LatLon> {
        val out = mutableListOf(at(corners[0].first, corners[0].second))
        for (i in 1 until corners.size) {
            val (x0, y0) = corners[i - 1]
            val (x1, y1) = corners[i]
            val n = (kotlin.math.hypot(x1 - x0, y1 - y0) / 50.0).toInt().coerceAtLeast(1)
            for (k in 1..n) out += at(x0 + (x1 - x0) * k / n, y0 + (y1 - y0) * k / n)
        }
        return out
    }

    private val outbound = path(0.0 to 0.0, 10_000.0 to 0.0)

    @Test
    fun sameRoadBackIsAllOverlap() {
        assertEquals(1.0, RouteOverlap.fraction(outbound, outbound.reversed()), 0.01)
    }

    @Test
    fun disjointReturnIsNoOverlap() {
        val back = path(10_000.0 to 0.0, 10_000.0 to 2_000.0, 0.0 to 2_000.0, 0.0 to 0.0)
        assertEquals(0.0, RouteOverlap.fraction(outbound, back))
    }

    @Test
    fun sharingOnlyTheLast800MetresIsNoOverlap() {
        val back = path(10_000.0 to 0.0, 10_000.0 to 2_000.0, 800.0 to 2_000.0, 800.0 to 0.0, 0.0 to 0.0)
        assertEquals(0.0, RouteOverlap.fraction(outbound, back))
    }

    @Test
    fun halfTheReturnOnTheWayOutIsHalf() {
        // 12 km back along the 20 km outbound, then 2 km north, 8 km west, 2 km south:
        // outside the ignored ends, 11 km on the road and 11 km off it.
        val long = path(0.0 to 0.0, 20_000.0 to 0.0)
        val back = path(20_000.0 to 0.0, 8_000.0 to 0.0, 8_000.0 to 2_000.0, 0.0 to 2_000.0, 0.0 to 0.0)
        assertEquals(0.5, RouteOverlap.fraction(long, back), 0.02)
    }

    @Test
    fun parallelRoad60MetresAwayIsNoOverlap() {
        val back = path(10_000.0 to 60.0, 0.0 to 60.0)
        assertEquals(0.0, RouteOverlap.fraction(outbound, back))
    }

    @Test
    fun returnShorterThanTheIgnoredEndsCountsNothing() {
        assertEquals(0.0, RouteOverlap.fraction(outbound, path(1_500.0 to 0.0, 0.0 to 0.0)))
    }

    @Test
    fun emptyInputsCountNothing() {
        assertEquals(0.0, RouteOverlap.fraction(emptyList(), outbound))
        assertEquals(0.0, RouteOverlap.fraction(outbound, emptyList()))
    }

    @Test
    fun sparseOutboundIsMatchedAlongItsSegmentsNotJustItsVertices() {
        // A 10 km straight with only its two ends, as a motorway comes back from GraphHopper.
        val sparse = listOf(at(0.0, 0.0), at(10_000.0, 0.0))
        assertEquals(1.0, RouteOverlap.fraction(sparse, outbound.reversed()), 0.01)
    }
}

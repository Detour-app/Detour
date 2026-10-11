package com.jellemax.detour.data

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * The area to pre-fetch map tiles for along a route, so the map keeps drawing
 * when coverage drops mid-ride. #439.
 *
 * One bounding box over a whole loop would fetch everything inside it — a 100 km
 * loop's box is mostly land the route never touches. Instead the line is cut into
 * stretches of at most [CHUNK_M] and each stretch gets its own box, padded by the
 * corridor half-width. The boxes overlap where stretches meet, so the union has
 * no gaps along the line.
 *
 * Pure arithmetic: the MapLibre offline region that consumes these boxes is
 * Android-only (`map/RouteTileCache.kt`).
 */
object RouteCorridor {
    /** How far either side of the line tiles are fetched. Owner decision on #439. */
    const val HALF_WIDTH_M = 5_000.0

    /** Zoom range fetched. Below 8 the ambient cache already has what a rider saw;
     *  above 15 is street detail that costs four times the tiles per level. */
    const val MIN_ZOOM = 8.0
    const val MAX_ZOOM = 15.0

    /** Longest stretch of line one box covers. Short enough that a diagonal stretch
     *  over-fetches at most a few km², long enough to keep a long ride to a few
     *  dozen boxes. */
    const val CHUNK_M = 10_000.0

    private const val METERS_PER_DEGREE_LAT = 111_320.0

    /** Latitude the longitude pad is computed at, at most: cos() heads to zero at a pole. */
    private const val MAX_PAD_LAT = 89.0

    /**
     * Padded boxes covering [polyline], in route order. Empty for an empty line;
     * a single point gets one box around it.
     *
     * A polyline segment longer than [chunkM] (a motorway with sparse vertices)
     * is split at even steps first, so no box ever spans more than [chunkM] of
     * line plus the padding.
     */
    fun boxes(
        polyline: List<LatLon>,
        halfWidthM: Double = HALF_WIDTH_M,
        chunkM: Double = CHUNK_M,
    ): List<LatLonBox> {
        if (polyline.isEmpty()) return emptyList()
        val out = mutableListOf<LatLonBox>()
        var chunk = mutableListOf(polyline.first())
        var chunkLen = 0.0
        for (i in 1 until polyline.size) {
            for (p in densified(polyline[i - 1], polyline[i], chunkM)) {
                val step = RoadRoulette.distanceMeters(chunk.last(), p)
                if (chunkLen + step > chunkM && chunk.size > 1) {
                    out += padded(chunk, halfWidthM)
                    chunk = mutableListOf(chunk.last())
                    chunkLen = 0.0
                }
                chunk += p
                chunkLen += step
            }
        }
        out += padded(chunk, halfWidthM)
        return out
    }

    /** The points after [a] up to and including [b], with extra points so no step exceeds [maxStepM]. */
    private fun densified(a: LatLon, b: LatLon, maxStepM: Double): List<LatLon> {
        val n = max(1, ceil(RoadRoulette.distanceMeters(a, b) / maxStepM).toInt())
        return (1..n).map { k ->
            val t = k.toDouble() / n
            LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
        }
    }

    /** [points] is never empty: every chunk starts with the previous chunk's last point. */
    private fun padded(points: List<LatLon>, halfWidthM: Double): LatLonBox {
        val b = FogGeometry.boxOf(points)!!
        val dLat = halfWidthM / METERS_PER_DEGREE_LAT
        // Widest longitude pad is at the box edge nearest a pole.
        val edgeLat = min(max(abs(b.north), abs(b.south)) + dLat, MAX_PAD_LAT)
        val dLon = halfWidthM / (METERS_PER_DEGREE_LAT * cos(edgeLat * PI / 180))
        return LatLonBox(
            north = min(b.north + dLat, 90.0),
            south = max(b.south - dLat, -90.0),
            east = b.east + dLon,
            west = b.west - dLon,
        )
    }
}

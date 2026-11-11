package com.jellemax.detour.data

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/**
 * A band of road around a route line, as polygons a [PenaltyArea] can carry —
 * what a turnaround loop's return leg (#889) is told to keep off. Not
 * [RouteCorridor], which is #439's kilometres-wide tile pre-fetch boxes; this
 * one is tens of metres and follows the road.
 *
 * Pure geometry in a local flat projection (metres east/north of the line's
 * first point, the same small-distance approximation as [RoadRoulette.offset]),
 * fine for the tens of kilometres a route spans.
 */
object PenaltyCorridor {

    /** Douglas-Peucker tolerance to start from: well under the default half-width. */
    private const val BASE_TOLERANCE_METERS = 10.0
    private const val METERS_PER_DEGREE = 111_320.0

    /**
     * One closed quad (five points, first repeated) per segment of [line]
     * after Douglas-Peucker simplification, reaching [halfWidthMeters] either
     * side of the segment and the same distance past each end, so the bends
     * between quads are covered too. Simplification starts at 10 m; when that
     * leaves more than [maxSegments] segments the tolerance doubles until it
     * doesn't, and every quad widens by the tolerance used, so every vertex of
     * the original [line] stays inside the corridor. Fewer than two distinct
     * points: empty.
     */
    fun polygons(
        line: List<LatLon>,
        halfWidthMeters: Double = 40.0,
        maxSegments: Int = 150,
    ): List<List<LatLon>> {
        val distinct = line.filterIndexed { i, p -> i == 0 || p != line[i - 1] }
        if (distinct.size < 2) return emptyList()
        val origin = distinct.first()
        val scaleX = METERS_PER_DEGREE * cos(origin.lat * PI / 180.0)
        val xy = distinct.map { Point((it.lon - origin.lon) * scaleX, (it.lat - origin.lat) * METERS_PER_DEGREE) }

        var tolerance = BASE_TOLERANCE_METERS
        var kept = simplify(xy, tolerance)
        while (kept.size - 1 > maxSegments.coerceAtLeast(1)) {
            tolerance *= 2
            kept = simplify(xy, tolerance)
        }
        // Never narrower than the tolerance, or a dropped vertex could fall outside.
        val width = (halfWidthMeters + if (tolerance > BASE_TOLERANCE_METERS) tolerance else 0.0)
            .coerceAtLeast(tolerance)

        fun toLatLon(p: Point) = LatLon(origin.lat + p.y / METERS_PER_DEGREE, origin.lon + p.x / scaleX)

        return kept.zipWithNext().map { (ia, ib) ->
            val a = xy[ia]
            val b = xy[ib]
            val len = hypot(b.x - a.x, b.y - a.y)
            // The segment's direction scaled to width; (-uy, ux) is its left normal.
            val ux = (b.x - a.x) / len * width
            val uy = (b.y - a.y) / len * width
            val start = Point(a.x - ux, a.y - uy)
            val end = Point(b.x + ux, b.y + uy)
            val ring = listOf(
                Point(start.x - uy, start.y + ux),
                Point(end.x - uy, end.y + ux),
                Point(end.x + uy, end.y - ux),
                Point(start.x + uy, start.y - ux),
            ).map(::toLatLon)
            ring + ring.first()
        }
    }

    private data class Point(val x: Double, val y: Double)

    /**
     * Indices of [points] Douglas-Peucker keeps at [tolerance] metres, in
     * order, first and last always. Iterative, so a 2000-vertex trace can't
     * run the stack deep.
     */
    private fun simplify(points: List<Point>, tolerance: Double): List<Int> {
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.lastIndex)
        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            var farthest = -1
            var farthestDist = tolerance
            for (i in first + 1 until last) {
                val d = segmentDistance(points[i], points[first], points[last])
                if (d > farthestDist) {
                    farthest = i
                    farthestDist = d
                }
            }
            if (farthest >= 0) {
                keep[farthest] = true
                stack.addLast(first to farthest)
                stack.addLast(farthest to last)
            }
        }
        return points.indices.filter { keep[it] }
    }

    /** Distance from [p] to the segment [a]–[b], not the infinite line through them. */
    private fun segmentDistance(p: Point, a: Point, b: Point): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }
}

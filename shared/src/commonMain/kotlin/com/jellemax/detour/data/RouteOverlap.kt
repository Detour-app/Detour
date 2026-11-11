package com.jellemax.detour.data

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * How much of a loop's way back runs on its way out (#889). Pure geometry: both
 * polylines are projected onto a flat plane around the outbound's first point,
 * which is well under a metre off for a ride-sized loop.
 */
object RouteOverlap {

    /** One sample of the return per this many metres of it. */
    private const val SAMPLE_STEP_M = 25.0

    /** Metres per degree of latitude (mean Earth radius 6 371 km). */
    private const val M_PER_DEG = 6_371_000.0 * PI / 180.0

    /**
     * Share (0..1) of [back]'s length, leaving out its first and last
     * [ignoreEndsMeters] (the rider's own street, which both legs must use),
     * that lies within [toleranceMeters] of [outbound]. 0.0 when nothing is left
     * to count. Sampled every [SAMPLE_STEP_M] along [back]; each sample is
     * matched point-to-segment against a grid of [outbound]'s segments, so three
     * ~2000-point legs stay cheap.
     */
    fun fraction(
        outbound: List<LatLon>,
        back: List<LatLon>,
        toleranceMeters: Double = 30.0,
        ignoreEndsMeters: Double = 1000.0,
    ): Double {
        if (outbound.isEmpty() || back.size < 2) return 0.0
        val plane = Plane(outbound.first())
        val backXy = back.map(plane::project)
        val cumulative = cumulativeLengths(backXy)
        val counted = cumulative.last() - 2 * ignoreEndsMeters
        if (counted <= 0.0) return 0.0
        val grid = SegmentGrid(outbound.map(plane::project), toleranceMeters)
        val samples = max(1, ceil(counted / SAMPLE_STEP_M).toInt())
        var hits = 0
        var seg = 0
        for (i in 0 until samples) {
            val at = ignoreEndsMeters + (i + 0.5) * counted / samples
            while (seg < cumulative.size - 2 && cumulative[seg + 1] < at) seg++
            if (grid.isNear(pointAt(backXy, cumulative, seg, at))) hits++
        }
        return hits.toDouble() / samples
    }

    private class Xy(val x: Double, val y: Double)

    private class Plane(origin: LatLon) {
        private val lat0 = origin.lat
        private val lon0 = origin.lon
        private val lonScale = cos(origin.lat * PI / 180.0)
        fun project(p: LatLon) = Xy((p.lon - lon0) * lonScale * M_PER_DEG, (p.lat - lat0) * M_PER_DEG)
    }

    private fun cumulativeLengths(line: List<Xy>): DoubleArray {
        val out = DoubleArray(line.size)
        for (i in 1 until line.size) out[i] = out[i - 1] + dist(line[i - 1], line[i])
        return out
    }

    /** The point [at] metres along [line], which lies on segment [seg]. */
    private fun pointAt(line: List<Xy>, cumulative: DoubleArray, seg: Int, at: Double): Xy {
        val a = line[seg]
        val b = line[seg + 1]
        val len = cumulative[seg + 1] - cumulative[seg]
        val t = if (len <= 0.0) 0.0 else ((at - cumulative[seg]) / len).coerceIn(0.0, 1.0)
        return Xy(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y))
    }

    private fun dist(a: Xy, b: Xy): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun distToSegment(p: Xy, a: Xy, b: Xy): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 <= 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        return dist(p, Xy(a.x + t * dx, a.y + t * dy))
    }

    /**
     * Each segment of a line filed under every cell its bounding box, grown by
     * [tolerance], touches; so any segment within [tolerance] of a point is filed
     * under that point's own cell, and a lookup reads one cell.
     */
    private class SegmentGrid(private val line: List<Xy>, private val tolerance: Double) {
        private val cell = max(tolerance * CELL_PER_TOLERANCE, MIN_CELL_M)
        private val cells = HashMap<Long, MutableList<Int>>()

        init {
            if (line.size == 1) file(0, line[0], line[0])
            for (i in 0 until line.size - 1) file(i, line[i], line[i + 1])
        }

        private fun file(i: Int, a: Xy, b: Xy) {
            val x0 = index(min(a.x, b.x) - tolerance)
            val x1 = index(max(a.x, b.x) + tolerance)
            val y0 = index(min(a.y, b.y) - tolerance)
            val y1 = index(max(a.y, b.y) + tolerance)
            for (cx in x0..x1) for (cy in y0..y1) cells.getOrPut(key(cx, cy)) { mutableListOf() }.add(i)
        }

        fun isNear(p: Xy): Boolean {
            val candidates = cells[key(index(p.x), index(p.y))] ?: return false
            return candidates.any { i ->
                distToSegment(p, line[i], line[min(i + 1, line.size - 1)]) <= tolerance
            }
        }

        private fun index(v: Double) = floor(v / cell).toInt()
        private fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)

        private companion object {
            /** Cells a few tolerances wide: few cells per segment, few segments per cell. */
            const val CELL_PER_TOLERANCE = 4.0
            const val MIN_CELL_M = 20.0
        }
    }
}

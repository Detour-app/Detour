package com.jellemax.detour.data

import kotlin.math.abs

/**
 * Picks the via points a route hands to Google Maps ([RouteResult.waypoints]),
 * which also become a saved loop's stops. Google snaps each bare `lat,lon` to
 * the nearest road with no direction of travel, so a via on a dual carriageway,
 * a ramp, or at a junction where a ramp splits off can land on the opposite
 * carriageway and send the rider the long way round (#1079).
 *
 * So the vias are spaced by distance along the line, not by vertex index
 * (GraphHopper's vertices cluster at junctions and bends), and each one keeps
 * [TURN_CLEARANCE_M] clear of every instruction and off every motorway/trunk
 * span. Pure: no I/O.
 */
internal object ViaSampler {

    /** How far a via keeps from the start of any instruction, along the line. */
    internal const val TURN_CLEARANCE_M = 150.0

    /**
     * Up to [count] points on [line], in route order, excluding its ends. Point
     * `i` aims for `i / (count + 1)` of the way along and may move by at most
     * half a slot to clear [turnIndices] (polyline indices where an instruction
     * starts) and [avoidSpans] (polyline index ranges to stay off). A slot with
     * no clear spot is dropped; if every slot is, the plain evenly spaced points
     * are used instead, so a loop never loses all its vias.
     */
    fun sample(
        line: List<LatLon>,
        count: Int,
        turnIndices: List<Int> = emptyList(),
        avoidSpans: List<IntRange> = emptyList(),
    ): List<LatLon> {
        if (line.size <= 2 || count <= 0) return emptyList()
        val along = cumulativeMeters(line)
        val total = along.last()
        if (total <= 0.0) return emptyList()
        val last = line.lastIndex
        val blocked = turnIndices.map { i ->
            val at = along[i.coerceIn(0, last)]
            (at - TURN_CLEARANCE_M)..(at + TURN_CLEARANCE_M)
        } + avoidSpans.map { span ->
            along[span.first.coerceIn(0, last)]..along[span.last.coerceIn(0, last)]
        }
        val step = total / (count + 1)
        val targets = (1..count).map { it * step }
        val clear = targets.mapNotNull { nearestClear(it, step / 2, blocked) }
        return clear.ifEmpty { targets }.map { pointAt(line, along, it) }.distinct()
    }

    /**
     * The distance nearest [target], within [reach] of it, that sits inside no
     * [blocked] range; null when there is none. Only [target] itself and the
     * range edges can be that point, so those are the only candidates.
     */
    private fun nearestClear(
        target: Double,
        reach: Double,
        blocked: List<ClosedFloatingPointRange<Double>>,
    ): Double? {
        val candidates = listOf(target) + blocked.flatMap { listOf(it.start, it.endInclusive) }
        return candidates
            .filter { abs(it - target) <= reach }
            .filter { d -> blocked.none { d > it.start && d < it.endInclusive } }
            .minByOrNull { abs(it - target) }
    }

    private fun cumulativeMeters(line: List<LatLon>): DoubleArray {
        val along = DoubleArray(line.size)
        for (i in 1 until line.size) {
            along[i] = along[i - 1] + RoadRoulette.distanceMeters(line[i - 1], line[i])
        }
        return along
    }

    /** The point [meters] along [line], interpolated within its segment. */
    private fun pointAt(line: List<LatLon>, along: DoubleArray, meters: Double): LatLon {
        val found = along.asList().binarySearch(meters)
        if (found >= 0) return line[found]
        val end = (-found - 1).coerceIn(1, line.lastIndex)
        val span = along[end] - along[end - 1]
        val t = if (span <= 0.0) 0.0 else ((meters - along[end - 1]) / span).coerceIn(0.0, 1.0)
        val a = line[end - 1]
        val b = line[end]
        return LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
    }
}

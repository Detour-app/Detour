package com.jellemax.detour.data

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Route-following math for in-app navigation. Pure functions, no state. */
object NavEngine {

    data class Progress(
        /** Distance from the current position to the nearest point on the route. */
        val offRouteMeters: Double,
        /** The upcoming maneuver (arrival instruction near the end). */
        val nextInstruction: NavInstruction?,
        val distanceToTurnMeters: Double,
        val remainingMeters: Double,
        /**
         * Full route length, measured along the polyline rather than taken from
         * the router's reported distance, so it is always present and always
         * consistent with [remainingMeters]. An external display needs both to
         * draw progress along the route.
         */
        val routeMeters: Double,
        val remainingTimeMs: Long?,
        /** Posted speed limit on the road segment closest to the current position. */
        val speedLimitKmh: Double?,
        /** The maneuver after [nextInstruction], for the "then…" pill under the
         *  banner; null past the last turn or when there's only one left. */
        val nextNextInstruction: NavInstruction? = null,
        /** Distance from the current position to [nextNextInstruction], same
         *  basis as [distanceToTurnMeters]. */
        val distanceToNextNextMeters: Double? = null,
    ) {
        /** How much of the route is behind you, 0..1 — [remainingMeters] read
         *  the other way round. A fraction rather than metres because the map
         *  that draws it measures the same polyline with its own arithmetic:
         *  ratios agree between the two where absolute distances need not. */
        val drivenFraction: Double
            get() = if (routeMeters > 0.0)
                ((routeMeters - remainingMeters) / routeMeters).coerceIn(0.0, 1.0)
            else 0.0
    }

    /** Where [pos] is along [route]: snap to the nearest segment, then derive
     *  the upcoming instruction and remaining distance/time. */
    fun progress(route: RouteResult, pos: LatLon): Progress? {
        val line = route.polyline
        if (line.size < 2) return null

        // Local equirectangular projection around pos; fine at route scale.
        val mPerLat = 111_320.0
        val mPerLon = 111_320.0 * cos(pos.lat * PI / 180.0)
        fun x(p: LatLon) = (p.lon - pos.lon) * mPerLon
        fun y(p: LatLon) = (p.lat - pos.lat) * mPerLat

        // One pass: nearest segment plus cumulative distance to each vertex.
        val cumAt = DoubleArray(line.size)
        var bestDist = Double.MAX_VALUE
        var bestIndex = 0
        var bestAlong = 0.0
        for (i in 0 until line.size - 1) {
            val ax = x(line[i]); val ay = y(line[i])
            val bx = x(line[i + 1]); val by = y(line[i + 1])
            val dx = bx - ax; val dy = by - ay
            val segLen2 = dx * dx + dy * dy
            val segLen = sqrt(segLen2)
            // Project pos (the local origin) onto segment A→B, clamped.
            val t = if (segLen2 == 0.0) 0.0
                else max(0.0, min(1.0, -(ax * dx + ay * dy) / segLen2))
            val d = hypot(ax + t * dx, ay + t * dy)
            if (d < bestDist) {
                bestDist = d
                bestIndex = i
                bestAlong = cumAt[i] + t * segLen
            }
            cumAt[i + 1] = cumAt[i] + segLen
        }
        val total = cumAt.last()
        val remaining = max(0.0, total - bestAlong)

        val next = route.instructions.firstOrNull { it.startIndex > bestIndex }
            ?: route.instructions.lastOrNull()
        val distToTurn = next
            ?.let { max(0.0, cumAt[it.startIndex.coerceIn(0, line.size - 1)] - bestAlong) }
            ?: remaining
        val nextNext = next?.let { route.instructions.getOrNull(route.instructions.indexOf(it) + 1) }
        val distToNextNext = nextNext
            ?.let { max(0.0, cumAt[it.startIndex.coerceIn(0, line.size - 1)] - bestAlong) }

        return Progress(
            offRouteMeters = bestDist,
            nextInstruction = next,
            distanceToTurnMeters = distToTurn,
            remainingMeters = remaining,
            routeMeters = total,
            remainingTimeMs = route.timeMs?.let {
                if (total > 0) (it * remaining / total).toLong() else null
            },
            speedLimitKmh = route.speedLimits
                .firstOrNull { bestIndex >= it.fromIndex && bestIndex < it.toIndex }
                ?.kmh,
            nextNextInstruction = nextNext,
            distanceToNextNextMeters = distToNextNext,
        )
    }

    /** Length of [line] in metres, measured along it with the same flat-earth
     *  approximation [progress] uses. */
    fun lengthMeters(line: List<LatLon>): Double {
        var total = 0.0
        for (i in 0 until line.size - 1) total += segmentMeters(line[i], line[i + 1])
        return total
    }

    /**
     * The first [fraction] (0..1) of [line] — the part already driven, for a map
     * that draws the road behind you differently from the road ahead.
     *
     * The cut lands *inside* a segment rather than at the nearest vertex: on a
     * motorway the router can leave kilometres between two points, and a line
     * that only advances when one is passed reads as a stuck map. Empty when
     * there is nothing to draw yet, and never a single point (a one-point
     * LineString is not a line).
     */
    fun prefix(line: List<LatLon>, fraction: Double): List<LatLon> {
        if (line.size < 2) return emptyList()
        val target = lengthMeters(line) * fraction.coerceIn(0.0, 1.0)
        if (target <= 0.0) return emptyList()
        val out = ArrayList<LatLon>(line.size)
        out.add(line[0])
        var walked = 0.0
        for (i in 0 until line.size - 1) {
            val segment = segmentMeters(line[i], line[i + 1])
            if (walked + segment >= target) {
                val t = if (segment <= 0.0) 0.0 else (target - walked) / segment
                out.add(
                    LatLon(
                        line[i].lat + (line[i + 1].lat - line[i].lat) * t,
                        line[i].lon + (line[i + 1].lon - line[i].lon) * t,
                    ),
                )
                return out
            }
            walked += segment
            out.add(line[i + 1])
        }
        // Rounding only: the loop above returns for every fraction under 1.
        return out
    }

    /** Straight-line metres between two neighbouring route points. */
    private fun segmentMeters(a: LatLon, b: LatLon): Double {
        val mPerLat = 111_320.0
        val mPerLon = mPerLat * cos((a.lat + b.lat) / 2.0 * PI / 180.0)
        return hypot((b.lon - a.lon) * mPerLon, (b.lat - a.lat) * mPerLat)
    }

    /**
     * Map camera zoom while following or navigating, expressed as an offset from
     * the user's preferred [baseZoom] (Settings > Map): out a little at speed so
     * you see further ahead, in near a turn so the maneuver is legible. Bounded
     * to ±2 levels so the base zoom is always what you mostly get.
     *
     * Pass [distanceToTurnMeters] = [Double.MAX_VALUE] when there is no route.
     */
    fun cameraZoom(baseZoom: Double, speedMps: Double, distanceToTurnMeters: Double): Double {
        val speedOffset = when {
            speedMps < 3.0 -> 1.0     // stopped / walking pace
            speedMps < 8.0 -> 0.5     // city streets
            speedMps < 14.0 -> 0.0    // arterial
            speedMps < 22.0 -> -0.75  // fast road
            else -> -1.5              // highway
        }
        val turnBoost = when {
            distanceToTurnMeters < 60.0 -> 1.5
            distanceToTurnMeters < 150.0 -> 0.75
            else -> 0.0
        }
        return min(20.0, max(3.0, baseZoom + (speedOffset + turnBoost).coerceIn(-2.0, 2.0)))
    }
}

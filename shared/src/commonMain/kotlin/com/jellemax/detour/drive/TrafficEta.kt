package com.jellemax.detour.drive

import com.jellemax.detour.data.HereTrafficClient.FlowSegment
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * The remaining time to the destination with traffic in it, and how much of
 * that is traffic (#643).
 *
 * **GraphHopper stays the baseline.** The router's own estimate is what the
 * rider sees today, and HERE only says how much slower than free flow each road
 * is right now. So the delay on a leg of the route is the time at HERE's current
 * speed minus the time at HERE's free-flow speed, and the traffic ETA is the
 * router's time plus the sum of those. Taking HERE's absolute speeds instead
 * would change the ETA on a free road too, whenever HERE's free flow disagrees
 * with GraphHopper's motorcycle profile — a jump nobody could explain.
 *
 * A leg is matched to the flow segment that runs within [MATCH_M] of its
 * midpoint *in the same direction* ([HEADING_TOLERANCE_DEG]): HERE reports each
 * carriageway separately, and the jam on the other side of the motorway is not
 * the rider's. A leg nothing matches adds no delay.
 *
 * A closed segment on the route adds no delay either — there is no speed to
 * divide by — and sets [Estimate.closedAhead] instead, so the caller can say so
 * rather than show an ETA that pretends to drive through it.
 */
object TrafficEta {

    /** How far a flow segment may lie from a leg's midpoint and still be that
     *  road: GPS-drawn OSM and HERE geometry differ by a lane or two. */
    const val MATCH_M = 20.0

    /** How far a flow segment's direction may differ from the leg's. */
    const val HEADING_TOLERANCE_DEG = 30.0

    /** A standstill (HERE speed 0 on an open road) is timed at this speed, so
     *  one stopped segment cannot make the ETA infinite. */
    const val MIN_SPEED_MPS = 1.0

    /** Degrees of latitude per metre, for the bounding-box prefilter only. */
    private const val DEG_PER_M = 1.0 / 111_320.0

    /** Keeps the longitude margin finite at the poles, where nobody rides. */
    private const val MIN_COS_LAT = 0.01

    data class Estimate(
        /** The router's remaining time plus [delaySeconds]. */
        val durationSeconds: Double,
        /** Time lost to traffic on the remaining route, never negative. */
        val delaySeconds: Double,
        /** A segment HERE reports closed lies on the remaining route. */
        val closedAhead: Boolean,
        /** How much of the route had flow to match: lets the caller tell "no
         *  delay" from "no data". */
        val matchedMeters: Double,
    )

    /**
     * [route] is what is left of it, from the rider on; [routerSeconds] the
     * router's estimate for that same stretch.
     */
    fun estimate(route: List<LatLon>, routerSeconds: Double, flow: List<FlowSegment>): Estimate {
        val candidates = flow.filter { it.shape.size >= 2 }.map { Indexed(it) }
        var delay = 0.0
        var matched = 0.0
        var closed = false
        for ((a, b) in route.zipWithNext()) {
            val length = RoadRoulette.distanceMeters(a, b)
            val segment = (if (length > 0.0) matchOf(a, b, candidates) else null) ?: continue
            matched += length
            closed = closed || segment.closed
            delay += delayOf(segment, length)
        }
        return Estimate(routerSeconds + delay, delay, closed, matched)
    }

    /** Seconds [segment]'s traffic adds over [length] metres of it. None on
     *  a closed segment, which has no speed to divide by. */
    private fun delayOf(segment: FlowSegment, length: Double): Double {
        if (segment.closed || segment.freeFlowMps <= 0.0) return 0.0
        val atSpeed = length / max(segment.speedMps, MIN_SPEED_MPS)
        return max(0.0, atSpeed - length / segment.freeFlowMps)
    }

    /** A segment with its bounding box, so most of them are ruled out
     *  without any trigonometry. */
    private class Indexed(val segment: FlowSegment) {
        private val south = segment.shape.minOf { it.lat }
        private val north = segment.shape.maxOf { it.lat }
        private val west = segment.shape.minOf { it.lon }
        private val east = segment.shape.maxOf { it.lon }

        fun near(p: LatLon, latMargin: Double, lonMargin: Double): Boolean =
            p.lat in (south - latMargin)..(north + latMargin) &&
                p.lon in (west - lonMargin)..(east + lonMargin)
    }

    private fun matchOf(a: LatLon, b: LatLon, candidates: List<Indexed>): FlowSegment? {
        val mid = LatLon((a.lat + b.lat) / 2, (a.lon + b.lon) / 2)
        val heading = RoadRoulette.bearingDeg(a, b)
        val latMargin = MATCH_M * DEG_PER_M
        val lonMargin = latMargin / max(cos(mid.lat * PI / 180.0), MIN_COS_LAT)
        var best: FlowSegment? = null
        var bestDistance = Double.MAX_VALUE
        for (c in candidates) {
            if (!c.near(mid, latMargin, lonMargin)) continue
            for ((p, q) in c.segment.shape.zipWithNext()) {
                if (!sameDirection(heading, RoadRoulette.bearingDeg(p, q))) continue
                val d = RoadRoulette.distanceToSegmentMeters(mid, p, q)
                if (d <= MATCH_M && d < bestDistance) {
                    best = c.segment
                    bestDistance = d
                }
            }
        }
        return best
    }

    private fun sameDirection(a: Double, b: Double): Boolean {
        val diff = abs(a - b) % 360.0
        return min(diff, 360.0 - diff) <= HEADING_TOLERANCE_DEG
    }
}

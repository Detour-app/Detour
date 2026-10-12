package com.jellemax.detour.presentation

import com.jellemax.detour.data.HereTrafficClient.Incident
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.NavEngine
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.drive.TrafficCorridor
import com.jellemax.detour.drive.TrafficEta
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * How congested a stretch of the route ahead is, by HERE's jam factor (0 free
 * to 10 closed). Free flow is not a level: the route's own colour already says
 * "nothing in the way", so only these three get drawn over it.
 */
enum class Congestion { SLOW, QUEUING, CLOSED }

/** Consecutive route legs at one [congestion], as one line to draw. */
data class CongestedRun(val points: List<LatLon>, val congestion: Congestion)

/**
 * What the map shows of traffic while navigating (#645): the congested
 * stretches of the route ahead, the incidents on it, and the delay the
 * banner and the ETA carry. Built from what [TrafficCorridor] holds; nothing
 * here makes a request.
 */
data class TrafficOnRoute(
    val runs: List<CongestedRun>,
    val incidents: List<Incident>,
    /** Time traffic adds over [TrafficCorridor.LOOKAHEAD_M] of the route
     *  ahead, never negative ([TrafficEta.Estimate.delaySeconds]). */
    val delaySeconds: Double,
    /** HERE reports a closed road on the route ahead. */
    val closedAhead: Boolean,
) {
    /** The banner's "+N min", or null below [BANNER_MIN_DELAY_MIN] — a
     *  minute is the least a rider can do anything about. */
    val bannerDelayMinutes: Int?
        get() = (delaySeconds / SECONDS_PER_MIN).roundToInt().takeIf { it >= BANNER_MIN_DELAY_MIN }

    companion object {
        /** HERE's jam factor from which a road is drawn slow, and queuing. */
        const val SLOW_JAM_FACTOR = 4.0
        const val QUEUING_JAM_FACTOR = 8.0

        /** How close to the route an incident's stretch has to come to be on
         *  it: the same lane-or-two slack as [TrafficEta.MATCH_M], widened
         *  because an incident's shape is often coarser than a flow link's. */
        const val INCIDENT_NEAR_M = 50.0

        const val BANNER_MIN_DELAY_MIN = 1

        private const val SECONDS_PER_MIN = 60.0
    }
}

/**
 * [TrafficOnRoute] for [remaining] — the route from the rider on — out of what
 * [state] holds fresh at [nowMs]. Only the first [TrafficCorridor.LOOKAHEAD_M]
 * of it is looked at: that is all the corridor asks HERE about, so further on
 * there is no data, and a 300 km route is not walked leg by leg on every tick.
 */
fun trafficOnRoute(state: TrafficCorridor.State, remaining: List<LatLon>, nowMs: Long): TrafficOnRoute {
    val ahead = firstMeters(remaining, TrafficCorridor.LOOKAHEAD_M)
    val legs = TrafficEta.matchedLegs(ahead, TrafficCorridor.flowAlong(state, ahead, nowMs))
    val estimate = TrafficEta.estimateOf(legs, routerSeconds = 0.0)
    return TrafficOnRoute(
        runs = runsOf(legs),
        incidents = TrafficCorridor.incidentsAlong(state, ahead, nowMs).let { all ->
            // Most of a tile's incidents are on other roads, nowhere near the
            // route's box; ruled out here without walking the route for each.
            val box = boxOf(ahead, TrafficOnRoute.INCIDENT_NEAR_M)
            all.filter { i -> i.shape.any { box.contains(it) } && onRoute(i, ahead) }
        },
        delaySeconds = estimate.delaySeconds,
        closedAhead = estimate.closedAhead,
    )
}

/**
 * What is left of [line] from the rider on — what [trafficOnRoute] and
 * [com.jellemax.detour.drive.TrafficRound] are handed each tick. All of it
 * before the first fix ([progress] null), nothing with no route.
 */
fun routeAhead(line: List<LatLon>?, progress: NavEngine.Progress?): List<LatLon> {
    if (line == null) return emptyList()
    if (progress == null) return line
    return NavEngine.cut(line, progress.drivenFraction).ahead
}

/**
 * This progress with [traffic]'s delay added to the time left, so the ETA and
 * the "… min left" the nav sheet already format from it carry the traffic —
 * GraphHopper's estimate stays the baseline ([TrafficEta]'s class doc says
 * why). Unchanged with no traffic.
 */
fun NavEngine.Progress.withTrafficDelay(traffic: TrafficOnRoute?): NavEngine.Progress {
    val delayMs = ((traffic?.delaySeconds ?: 0.0) * MS_PER_SECOND).toLong()
    if (delayMs <= 0L) return this
    return copy(remainingTimeMs = remainingTimeMs?.plus(delayMs))
}

private const val MS_PER_SECOND = 1000.0

private fun congestionOf(leg: TrafficEta.MatchedLeg): Congestion? = when {
    leg.segment.closed -> Congestion.CLOSED
    leg.segment.jamFactor >= TrafficOnRoute.QUEUING_JAM_FACTOR -> Congestion.QUEUING
    leg.segment.jamFactor >= TrafficOnRoute.SLOW_JAM_FACTOR -> Congestion.SLOW
    else -> null
}

/** Joins legs that touch and share a level, so a jammed kilometre is one line
 *  rather than a dozen. */
private fun runsOf(legs: List<TrafficEta.MatchedLeg>): List<CongestedRun> {
    val runs = mutableListOf<CongestedRun>()
    var points = mutableListOf<LatLon>()
    var level: Congestion? = null
    fun close() {
        level?.let { if (points.size >= 2) runs.add(CongestedRun(points, it)) }
        points = mutableListOf()
        level = null
    }
    for (leg in legs) {
        val c = congestionOf(leg)
        if (c == null) {
            close()
            continue
        }
        if (c != level || points.lastOrNull() != leg.from) {
            close()
            level = c
            points.add(leg.from)
        }
        points.add(leg.to)
    }
    close()
    return runs
}

private fun onRoute(incident: Incident, route: List<LatLon>): Boolean =
    incident.shape.any { p ->
        route.zipWithNext().any { (a, b) ->
            RoadRoulette.distanceToSegmentMeters(p, a, b) <= TrafficOnRoute.INCIDENT_NEAR_M
        }
    }

private class Box(val south: Double, val north: Double, val west: Double, val east: Double) {
    fun contains(p: LatLon) = p.lat in south..north && p.lon in west..east
}

/** [line]'s bounding box grown by [marginM] on every side. */
private fun boxOf(line: List<LatLon>, marginM: Double): Box {
    if (line.isEmpty()) return Box(1.0, -1.0, 1.0, -1.0)
    val latMargin = marginM / METERS_PER_DEG_LAT
    val maxAbsLat = line.maxOf { abs(it.lat) }
    val lonMargin = latMargin / max(cos(maxAbsLat * PI / 180.0), MIN_COS_LAT)
    return Box(
        south = line.minOf { it.lat } - latMargin,
        north = line.maxOf { it.lat } + latMargin,
        west = line.minOf { it.lon } - lonMargin,
        east = line.maxOf { it.lon } + lonMargin,
    )
}

private const val METERS_PER_DEG_LAT = 111_320.0

/** Keeps the longitude margin finite at the poles, where nobody rides. */
private const val MIN_COS_LAT = 0.01

/** The first [meters] of [line], cut on the leg that crosses it. */
private fun firstMeters(line: List<LatLon>, meters: Double): List<LatLon> {
    if (line.size < 2) return line
    val out = mutableListOf(line.first())
    var walked = 0.0
    for ((a, b) in line.zipWithNext()) {
        val leg = RoadRoulette.distanceMeters(a, b)
        if (walked + leg >= meters) {
            val t = if (leg > 0.0) (meters - walked) / leg else 0.0
            out.add(LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t))
            return out
        }
        walked += leg
        out.add(b)
    }
    return out
}

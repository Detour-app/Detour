package com.jellemax.detour.data

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Route-following math for in-app navigation. Pure functions, no state. */
object NavEngine {

    /** How many segments ahead [advance] looks. Sixteen covers a second of
     *  motorway at any vertex spacing the routers produce, and closes a
     *  background-sized gap in a handful of frames. */
    private const val ADVANCE_SEGMENTS = 16

    /** [NavEngine.cut]'s two halves of a route: the road behind you and the
     *  road ahead, sharing only the point they meet at. */
    data class Cut(val behind: List<LatLon>, val ahead: List<LatLon>)

    data class Progress(
        /** Distance from the current position to the nearest point on the route. */
        val offRouteMeters: Double,
        /** The position snapped onto the route: the point [offRouteMeters] was
         *  measured to. Drawn instead of the fix while on route, so the marker
         *  rides the line rather than wandering off it by the GPS error. */
        val snappedAt: LatLon,
        /**
         * Compass bearing of the route segment [snappedAt] landed on, degrees
         * from north; null when that segment has zero length, because a router
         * may repeat a point and a heading-up camera must not read that as
         * north.
         *
         * The snap behind it is a *global* nearest-point search, so where a
         * route rides the same tarmac twice this can name the other leg and be
         * 180 degrees out. [advance] is the windowed, forward-only answer, and
         * is what the phone's camera and marker follow; anything driving a
         * rotation off this one wants that instead.
         */
        val segmentBearingDeg: Double?,
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
        var bestT = 0.0
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
                bestT = t
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
            snappedAt = interpolate(line[bestIndex], line[bestIndex + 1], bestT),
            segmentBearingDeg = segmentBearing(line, bestIndex),
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
     * [line] cut in two at [fraction] (0..1) of its length: the part already
     * driven and the part still ahead, meeting at exactly one point and
     * sharing nothing else.
     *
     * Disjoint on purpose. A map that draws the road behind you differently
     * has to be given two geometries, not one geometry twice: paint a copy of
     * the first N metres over the whole route and every stretch the route
     * rides twice — a round trip's outbound leg, an out-and-back, a
     * self-crossing — comes out dimmed in both directions, telling the rider
     * the road in front of them is done.
     *
     * The cut lands *inside* a segment rather than at the nearest vertex: on a
     * motorway the router can leave kilometres between two points, and a line
     * that only advances when one is passed reads as a stuck map. Either half
     * is empty rather than a single point when there is nothing to draw — a
     * one-point LineString is not a line.
     */
    fun cut(line: List<LatLon>, fraction: Double): Cut {
        if (line.size < 2) return Cut(emptyList(), emptyList())
        val target = lengthMeters(line) * fraction.coerceIn(0.0, 1.0)
        if (target <= 0.0) return Cut(emptyList(), line)
        val behind = ArrayList<LatLon>(line.size)
        behind.add(line[0])
        var walked = 0.0
        for (i in 0 until line.size - 1) {
            val segment = segmentMeters(line[i], line[i + 1])
            if (walked + segment >= target) {
                val t = if (segment <= 0.0) 0.0 else (target - walked) / segment
                val at = interpolate(line[i], line[i + 1], t)
                behind.add(at)
                // The cut point opens the far half. Skip the vertex it landed
                // on when it landed *on* one, or the ahead line starts with the
                // same point twice — and at the very last vertex that leaves
                // one point, which is not a line at all.
                val ahead = ArrayList<LatLon>(line.size - i)
                ahead.add(at)
                for (j in (if (t >= 1.0) i + 2 else i + 1) until line.size) ahead.add(line[j])
                return Cut(behind, if (ahead.size >= 2) ahead else emptyList())
            }
            walked += segment
            behind.add(line[i + 1])
        }
        // Rounding only: the loop above returns for every fraction under 1.
        return Cut(behind, emptyList())
    }

    /** The part of [line] behind you at [fraction] — [cut]'s near half. */
    fun prefix(line: List<LatLon>, fraction: Double): List<LatLon> = cut(line, fraction).behind

    /**
     * Where a position sits along a route: the segment it snapped to, the
     * snapped point itself, and how far along the line that is. [lineMeters]
     * rides along so [fraction] needs no second walk of the line.
     *
     * Distances are [lengthMeters]' arithmetic, so [fraction] and [cut] agree
     * about where the same point is.
     */
    data class Along(
        /** [at] lies between `line[index]` and `line[index + 1]`. */
        val index: Int,
        val at: LatLon,
        val meters: Double,
        val lineMeters: Double,
        /** Compass bearing of that segment, degrees from north; null on a
         *  zero-length one. The road's own direction, which is what a
         *  heading-up camera and the marker's nose both want: it does not
         *  twitch with GPS noise on a straight, and it turns with the road at
         *  a junction instead of lagging it by the fix's own smoothing. */
        val bearingDeg: Double?,
        /**
         * Distance along the line to `line[index]` — the vertex, not [at].
         *
         * Carried rather than recovered. [advance] needs this to open its
         * window, and subtracting `segmentMeters(line[index], at)` back off
         * [meters] does not return it: [segmentMeters] takes the cosine of its
         * own midpoint latitude, so the part-segment and the whole segment are
         * scaled by different numbers and the difference does not cancel. It is
         * one-signed, and a frame loop re-derives it sixty times a second — at
         * 30 m/s over 20 km of 500 m vertex spacing it walks the seam 56 m
         * ahead of the rider, dimming road not yet ridden.
         */
        val indexMeters: Double = 0.0,
        /**
         * The window ran out before [pos] did: [at] is the far end of the
         * segments [advance] searched, not the nearest point to the rider.
         * The marker loop hands `null` back as the next `from`, so the frame
         * after searches the whole line once — one global search, rather than
         * one recut of two route-sized GeoJSON sources per window of gap.
         */
        val beyondWindow: Boolean = false,
    ) {
        /** [meters] as a share of the whole line, 0..1. */
        val fraction: Double
            get() = if (lineMeters > 0.0) (meters / lineMeters).coerceIn(0.0, 1.0) else 0.0
    }

    /**
     * Re-snap [pos] onto [line], continuing from [from].
     *
     * Given a [from], only the [ADVANCE_SEGMENTS] segments starting at the one
     * it sat on are searched, which is what makes this affordable once per
     * displayed frame and is also what keeps it honest: a global nearest-point
     * search run at frame rate strobes between legs wherever a route rides the
     * same tarmac twice, because both legs are equally near. A forward window
     * cannot pick the wrong one, and cannot go backwards past the vertex it
     * started on.
     *
     * When [pos] is beyond the window — the app was in the background, or a fix
     * jumped — the snap clamps to the far end of the window and says so in
     * [Along.beyondWindow]. A caller that carries on from the clamp closes the
     * gap a window a frame; the marker loop drops it instead and pays one full
     * search on the next frame. Pass a null [from] to search the whole line,
     * which is what seeds the first frame of a drive.
     */
    fun advance(line: List<LatLon>, pos: LatLon, from: Along?): Along {
        if (line.size < 2) return Along(0, pos, 0.0, 0.0, null)
        val windowed = from != null && from.index < line.size - 1
        val first = if (windowed) from!!.index else 0
        val last = if (windowed) min(line.size - 1, first + ADVANCE_SEGMENTS) else line.size - 1
        // How far along the line the vertex the window opens on is.
        var cum = if (windowed) from!!.indexMeters else 0.0
        val total = if (windowed) from!!.lineMeters else lengthMeters(line)

        // Local equirectangular projection around pos, the same one [progress]
        // snaps with; the distances *along* stay [segmentMeters]'.
        val mPerLat = 111_320.0
        val mPerLon = 111_320.0 * cos(pos.lat * PI / 180.0)
        var bestDist = Double.MAX_VALUE
        var bestT = 0.0
        var best = Along(first, line[first], cum, total, segmentBearing(line, first), cum)
        for (i in first until last) {
            val ax = (line[i].lon - pos.lon) * mPerLon
            val ay = (line[i].lat - pos.lat) * mPerLat
            val dx = (line[i + 1].lon - pos.lon) * mPerLon - ax
            val dy = (line[i + 1].lat - pos.lat) * mPerLat - ay
            val segLen2 = dx * dx + dy * dy
            val t = if (segLen2 == 0.0) 0.0
                else max(0.0, min(1.0, -(ax * dx + ay * dy) / segLen2))
            val d = hypot(ax + t * dx, ay + t * dy)
            val segment = segmentMeters(line[i], line[i + 1])
            if (d < bestDist) {
                bestDist = d
                bestT = t
                best = Along(
                    index = i,
                    at = interpolate(line[i], line[i + 1], t),
                    meters = (cum + t * segment).coerceIn(0.0, total),
                    lineMeters = total,
                    bearingDeg = segmentBearing(line, i),
                    indexMeters = cum,
                )
            }
            cum += segment
        }
        // The far end of a window that stops short of the line's end is a
        // clamp, not a snap: the rider is further along than it could see. A
        // window that reaches the end clamps there because the rider is past
        // the destination, which a full search would only confirm.
        if (windowed && last < line.size - 1 && best.index == last - 1 && bestT >= 1.0) {
            best = best.copy(beyondWindow = true)
        }
        return best
    }

    /** The point [t] of the way from [a] to [b], 0..1. */
    private fun interpolate(a: LatLon, b: LatLon, t: Double): LatLon =
        LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)

    /** Compass bearing of the segment starting at `line[i]`, or null where
     *  there is no direction to report: past the last vertex, or on a segment
     *  of zero length — a router can repeat a point, and calling that north
     *  would swing a heading-up camera on a straight road. */
    private fun segmentBearing(line: List<LatLon>, i: Int): Double? {
        val a = line.getOrNull(i) ?: return null
        val b = line.getOrNull(i + 1) ?: return null
        return if (a == b) null else RoadRoulette.bearingDeg(a, b)
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

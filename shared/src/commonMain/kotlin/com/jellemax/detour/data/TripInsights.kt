package com.jellemax.detour.data

import kotlin.math.abs

/** One stretch of [TripInsights.SPLIT_METERS] (the last one shorter). */
data class TripSplit(
    val index: Int,
    val startMeters: Double,
    val distanceMeters: Double,
    val durationMs: Long,
    val avgSpeedMps: Double,
    /** Deepest lean either side, unsigned; null when the vehicle doesn't record lean. */
    val maxLeanDeg: Double?,
    /** [Curviness.traceScore] over the split's own points, 0..1. */
    val twistiness: Double,
)

/** Time spent between [fromKmh] and [toKmh] (open-ended when null). */
data class SpeedBand(val fromKmh: Int, val toKmh: Int?, val ms: Long)

/** Time leaning past [fromDeg] (either side) but not past the next band. */
data class LeanBand(val fromDeg: Int, val toDeg: Int?, val ms: Long)

data class LeanSummary(
    val maxLeftDeg: Double,
    val maxRightDeg: Double,
    val leftMs: Long,
    val rightMs: Long,
    val bands: List<LeanBand>,
)

/** One point of a chart over distance: speed and, on a leaning vehicle, lean. */
data class DistanceSample(val meters: Double, val speedKmh: Double, val leanDeg: Double?)

/** A place the trip passed through, in the order it was first entered. [isNew]
 *  is true when no point recorded before this trip lies inside it. */
data class PlaceVisit(val name: String, val isNew: Boolean)

/**
 * The trip-detail Deep dive (#444), derived from one trip's stored trace.
 *
 * The trace is decimated to 25 m, so a stop is one long interval covering
 * barely 25 m rather than a run of zero-speed points (see the detour-trip-data
 * skill). Every time-based figure here therefore works per *interval* — the
 * distance and time between two stored points — never per point, and an
 * interval slower than [MOVING_MPS] counts as stopped, the same gate the
 * tracker uses to decide a vehicle is moving.
 */
object TripInsights {

    const val SPLIT_METERS = 25_000.0

    /** `TripTrackingService`'s own moving gate: `speed > 2.0`. */
    const val MOVING_MPS = 2.0

    /** Lower edges, km/h. */
    val SPEED_BANDS_KMH = intArrayOf(0, 30, 50, 80, 100, 120)

    /** Lower edges, degrees either side. Below the first is upright riding. */
    val LEAN_BANDS_DEG = intArrayOf(10, 20, 30, 40)

    /** The highlighted "best stretch" on the map is this long. */
    const val BEST_STRETCH_METERS = 5_000.0

    /** Intervals with no usable time (a trace written before points carried
     *  one) or running backwards contribute distance but no time. */
    private fun intervalMs(a: TraceStore.TracePoint, b: TraceStore.TracePoint): Long =
        if (a.timeMs < 0 || b.timeMs < 0) 0L else (b.timeMs - a.timeMs).coerceAtLeast(0L)

    private fun isMoving(meters: Double, ms: Long) = ms > 0 && meters / (ms / 1000.0) > MOVING_MPS

    fun movingMs(points: List<TraceStore.TracePoint>): Long {
        var total = 0L
        for (i in 1 until points.size) {
            val ms = intervalMs(points[i - 1], points[i])
            if (isMoving(RoadRoulette.distanceMeters(points[i - 1].at, points[i].at), ms)) total += ms
        }
        return total
    }

    /** Each point's recorded speed and lean against the distance travelled to it. */
    fun profile(points: List<TraceStore.TracePoint>): List<DistanceSample> {
        var meters = 0.0
        return points.mapIndexed { i, p ->
            if (i > 0) meters += RoadRoulette.distanceMeters(points[i - 1].at, p.at)
            DistanceSample(meters, p.speedKmh, p.leanDeg)
        }
    }

    fun splits(points: List<TraceStore.TracePoint>, splitMeters: Double = SPLIT_METERS): List<TripSplit> {
        if (points.size < 2) return emptyList()
        val out = mutableListOf<TripSplit>()
        var start = 0
        var startMeters = 0.0
        var meters = 0.0
        fun close(end: Int) {
            val slice = points.subList(start, end + 1)
            val dist = meters - startMeters
            val ms = (1 until slice.size).sumOf { intervalMs(slice[it - 1], slice[it]) }
            val leans = slice.mapNotNull { it.leanDeg }
            out += TripSplit(
                index = out.size,
                startMeters = startMeters,
                distanceMeters = dist,
                durationMs = ms,
                avgSpeedMps = if (ms > 0) dist / (ms / 1000.0) else 0.0,
                maxLeanDeg = leans.maxOfOrNull { abs(it) },
                twistiness = Curviness.traceScore(slice.map { it.at }),
            )
            start = end
            startMeters = meters
        }
        for (i in 1 until points.size) {
            meters += RoadRoulette.distanceMeters(points[i - 1].at, points[i].at)
            if (meters - startMeters >= splitMeters) close(i)
        }
        // A tail under a tenth of a split is folded into nothing rather than
        // shown as a 300 m "split" with a meaningless average.
        if (start < points.size - 1 && meters - startMeters >= splitMeters / 10) close(points.size - 1)
        return out
    }

    /** The twistiest split, when there are at least two to choose between and
     *  one of them has any twist at all. */
    fun bestSplit(splits: List<TripSplit>): TripSplit? =
        if (splits.size < 2) null
        else splits.maxBy { it.twistiness }.takeIf { it.twistiness > 0.0 }

    /** Moving time in each of [SPEED_BANDS_KMH], by each interval's average speed. */
    fun speedBands(points: List<TraceStore.TracePoint>): List<SpeedBand> {
        val ms = LongArray(SPEED_BANDS_KMH.size)
        for (i in 1 until points.size) {
            val dt = intervalMs(points[i - 1], points[i])
            val d = RoadRoulette.distanceMeters(points[i - 1].at, points[i].at)
            if (!isMoving(d, dt)) continue
            val kmh = d / (dt / 1000.0) * 3.6
            val band = SPEED_BANDS_KMH.indexOfLast { kmh >= it }.coerceAtLeast(0)
            ms[band] += dt
        }
        return SPEED_BANDS_KMH.mapIndexed { i, from ->
            SpeedBand(from, SPEED_BANDS_KMH.getOrNull(i + 1), ms[i])
        }
    }

    /** Null when no point carries a lean reading. An interval is attributed to
     *  the lean at its far end — the reading the rider held on the way there. */
    fun lean(points: List<TraceStore.TracePoint>): LeanSummary? {
        if (points.none { it.leanDeg != null }) return null
        var maxLeft = 0.0
        var maxRight = 0.0
        var leftMs = 0L
        var rightMs = 0L
        val bandMs = LongArray(LEAN_BANDS_DEG.size)
        for (i in points.indices) {
            val lean = points[i].leanDeg ?: continue
            if (lean < 0) maxLeft = maxOf(maxLeft, -lean) else maxRight = maxOf(maxRight, lean)
            val dt = if (i == 0) 0L else intervalMs(points[i - 1], points[i])
            val moving = i > 0 && isMoving(RoadRoulette.distanceMeters(points[i - 1].at, points[i].at), dt)
            val band = LEAN_BANDS_DEG.indexOfLast { abs(lean) >= it }
            if (moving && band >= 0) {
                bandMs[band] += dt
                if (lean < 0) leftMs += dt else rightMs += dt
            }
        }
        return LeanSummary(
            maxLeftDeg = maxLeft,
            maxRightDeg = maxRight,
            leftMs = leftMs,
            rightMs = rightMs,
            bands = LEAN_BANDS_DEG.mapIndexed { i, from -> LeanBand(from, LEAN_BANDS_DEG.getOrNull(i + 1), bandMs[i]) },
        )
    }

    /**
     * The index range of the twistiest [lengthMeters] of the trip, or null when
     * the trip is shorter than twice that (the whole ride would be its own best
     * stretch) or has no twist in it anywhere. Windows start every few points
     * rather than at each one: a start moved by 100 m doesn't change which
     * stretch wins, and a long ride has thousands of points.
     */
    fun bestStretch(
        points: List<TraceStore.TracePoint>,
        lengthMeters: Double = BEST_STRETCH_METERS,
    ): IntRange? {
        if (points.size < 3) return null
        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) cum[i] = cum[i - 1] + RoadRoulette.distanceMeters(points[i - 1].at, points[i].at)
        if (cum.last() < lengthMeters * 2) return null
        var best: IntRange? = null
        var bestScore = 0.0
        var end = 0
        for (start in points.indices step 4) {
            while (end < points.size - 1 && cum[end] - cum[start] < lengthMeters) end++
            if (cum[end] - cum[start] < lengthMeters) break
            val score = Curviness.traceScore(points.subList(start, end + 1).map { it.at })
            if (score > bestScore) {
                bestScore = score
                best = start..end
            }
        }
        return best
    }

    /**
     * Road mix in plain words, from [DrivingStats.roadTypeMeters]: "mostly back
     * roads" when one class holds [MOSTLY_SHARE] of the classified distance,
     * otherwise the top two. Null when nothing was classified (an old trip, or
     * one where the road-type lookup never resolved).
     */
    fun roadMixWords(roadTypeMeters: Map<HighwayClass, Double>): String? {
        val total = roadTypeMeters.values.sum()
        if (total <= 0.0) return null
        val ranked = roadTypeMeters.entries.filter { it.value > 0 }.sortedByDescending { it.value }
        val top = ranked.first()
        if (top.value / total >= MOSTLY_SHARE) return "mostly ${roadWords(top.key)}"
        val second = ranked.getOrNull(1) ?: return "mostly ${roadWords(top.key)}"
        return "${roadWords(top.key)} and ${roadWords(second.key)}"
    }

    private const val MOSTLY_SHARE = 0.6

    fun roadWords(c: HighwayClass): String = when (c) {
        HighwayClass.MOTORWAY -> "motorway"
        HighwayClass.ARTERIAL -> "main roads"
        HighwayClass.LOCAL -> "back roads"
    }

    /** Metres along [points] to the stored point nearest [at] — where a pinned
     *  moment sits on the ride, to the trace's own 25 m. Null for an empty trace. */
    fun metersAlong(points: List<LatLon>, at: LatLon): Double? {
        if (points.isEmpty()) return null
        var meters = 0.0
        var bestMeters = 0.0
        var bestGap = Double.MAX_VALUE
        for (i in points.indices) {
            if (i > 0) meters += RoadRoulette.distanceMeters(points[i - 1], points[i])
            val gap = RoadRoulette.distanceMeters(points[i], at)
            if (gap < bestGap) {
                bestGap = gap
                bestMeters = meters
            }
        }
        return bestMeters
    }

    fun placeAt(at: LatLon, municipalities: List<Municipality>): String? =
        municipalities.firstOrNull { it.contains(at) }?.name

    /** Jumps the tracker had to bridge: consecutive stored points further apart
     *  than [GAP_METERS], the distance at which it starts a new trace segment. */
    fun signalGaps(points: List<LatLon>): Int =
        (1 until points.size).count { RoadRoulette.distanceMeters(points[it - 1], points[it]) > GAP_METERS }

    /** `TripTrackingService.addTracePoint`'s segment break. */
    private const val GAP_METERS = 500.0

    /**
     * The municipalities [points] passed through, in the order first entered,
     * each marked new when none of [earlierPoints] (everything recorded before
     * this trip) lies inside it. Pure point-in-polygon against the boundaries
     * [MunicipalityStore] already learned; a town the tracker never resolved
     * isn't named.
     */
    fun places(
        points: List<LatLon>,
        municipalities: List<Municipality>,
        earlierPoints: Sequence<LatLon> = emptySequence(),
    ): List<PlaceVisit> {
        val candidates = visitedInOrder(points, municipalities)
        if (candidates.isEmpty()) return emptyList()
        val seenBefore = HashSet<Long>()
        for (p in earlierPoints) {
            for (m in candidates) if (m.id !in seenBefore && m.contains(p)) seenBefore += m.id
            if (seenBefore.size == candidates.size) break
        }
        return candidates.map { PlaceVisit(it.name, it.id !in seenBefore) }
    }

    /** The municipalities [points] enter, each once, in the order first
     *  entered. The last hit is tried first: consecutive points are almost
     *  always in the same town, so most points cost one polygon test. */
    fun visitedInOrder(points: List<LatLon>, municipalities: List<Municipality>): List<Municipality> {
        val visited = LinkedHashMap<Long, Municipality>()
        var last: Municipality? = null
        for (p in points) {
            if (last?.contains(p) == true) continue
            last = municipalities.firstOrNull { it.contains(p) }?.also { visited.getOrPut(it.id) { it } } ?: last
        }
        return visited.values.toList()
    }
}

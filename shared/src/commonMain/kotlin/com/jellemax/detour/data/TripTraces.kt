package com.jellemax.detour.data

/** One decoded trace line: its points plus the timestamp window they span. */
data class TraceSegment(
    val points: List<TraceStore.TracePoint>,
    val startMs: Long,
    val endMs: Long,
)

/** Every trip's trace, thumbnail-sized, plus the towns it entered — one walk of
 *  the trace store for the whole Logbook. */
data class MatchedTraces(
    val thumbnails: Map<Long, List<LatLon>>,
    val visits: List<Pair<Trip, List<Municipality>>>,
)

/** Everything the Logbook renders from, loaded together. Android draws the
 *  thumbnails; iOS has no thumbnail yet and reads only the rest (#669). */
data class LogbookData(
    val trips: List<Trip>,
    val thumbnails: Map<Long, List<LatLon>>,
    val places: Map<Long, List<PlaceVisit>>,
    val titles: Map<Long, String>,
    val milestones: List<LogbookItem.Milestone>,
)

/**
 * Reassembles each trip's recorded route out of [TraceStore], and from it the
 * places the Logbook names. Lived in Android's `HistoryScreen.kt` until #669,
 * which left the iOS Logbook without places.
 */
object TripTraces {

    /** Slack added on both ends of a trip's window when matching it to trace
     *  lines, to cover the tracker's own startup lag between the trip actually
     *  starting and the first point landing in the buffer. */
    private const val TRIP_MATCH_SLACK_MS = 10_000L

    /** Points a Logbook thumbnail is capped to. A multi-hour ride can carry
     *  thousands, all wasted on a thumbnail canvas. */
    private const val THUMBNAIL_MAX_POINTS = 200

    /** Reads the raw trace lines through [TraceStore.loadAllPoints] rather than
     *  [TraceStore.loadAll], which drops the per-point timestamp — the one thing
     *  needed to match a trace back to the trip that was running when it was
     *  recorded, and the one thing a GPX export can't be built without. */
    fun readSegments(): List<TraceSegment> {
        // Parse is cached in TraceStore.loadAllPoints (keyed on its write version),
        // so a second history open / trip detail / GPX export in the same session
        // reuses it instead of re-reading the whole of traces.jsonl.
        val t = Perf.start()
        val segments = segmentsOf(TraceStore.loadAllPoints())
        Perf.end(t, "TripTraces.readSegments") {
            listOf("segments" to segments.size, "points" to segments.sumOf { it.points.size })
        }
        return segments
    }

    /** One [TraceSegment] per stored line, windowed on its timestamped points.
     *  A line with none (written before points carried a time) is dropped. */
    fun segmentsOf(lines: List<List<TraceStore.TracePoint>>): List<TraceSegment> = lines.mapNotNull { points ->
        var start = Long.MAX_VALUE
        var end = Long.MIN_VALUE
        for (p in points) {
            if (p.timeMs < 0) continue // written before points carried a time
            if (p.timeMs < start) start = p.timeMs
            if (p.timeMs > end) end = p.timeMs
        }
        if (start == Long.MAX_VALUE) null else TraceSegment(points, start, end)
    }

    /** Every point recorded during [trip], stitched back together from however
     *  many trace lines it was split across. The tracker doesn't write one line
     *  per trip — it flushes its point buffer to [TraceStore] every 200 points,
     *  on a >500 m GPS gap, and on a STILL activity transition, so a single ride
     *  routinely spans several lines (200 points at the ~25 m decimation
     *  interval is only ~5 km, which is where every longer ride used to stop
     *  being drawn). A trip is therefore matched by
     *  *overlap* rather than by a single line's start falling inside its window:
     *  any segment whose [startMs, endMs] range overlaps the trip's window (with
     *  [TRIP_MATCH_SLACK_MS] slack on both ends) can hold some of the trip's
     *  points. Because the buffer isn't flushed when a trip begins either, the
     *  line that opens a trip can also carry idle points recorded before it — so
     *  once the overlapping segments are pooled and sorted, every point is
     *  re-checked against the trip's own window to trim those leading (and any
     *  trailing) points out. Finally, flushTrace(keepLast = true) repeats the
     *  boundary point as the first point of the next line, so an exact duplicate
     *  of the immediately preceding point (same time and coordinates) is dropped
     *  to avoid a seam in the reassembled trace. Shared by [match] and
     *  [loadTripPoints] so the two never disagree on which points belong to
     *  which trip. */
    fun matchTripPoints(segments: List<TraceSegment>, trip: Trip): List<TraceStore.TracePoint> {
        val from = trip.startTimeMs - TRIP_MATCH_SLACK_MS
        val to = trip.endTimeMs + TRIP_MATCH_SLACK_MS
        val pooled = segments
            .filter { it.startMs <= to && it.endMs >= from }
            .sortedBy { it.startMs }
            .flatMap { it.points }
            .filter { it.timeMs in from..to }
        val result = ArrayList<TraceStore.TracePoint>(pooled.size)
        for (p in pooled) {
            val prev = result.lastOrNull()
            if (prev != null && prev.timeMs == p.timeMs && prev.at == p.at) continue
            result.add(p)
        }
        return result
    }

    /** The full (undecimated) trace of [trip] with its timestamps, loaded for
     *  one trip at a time on demand rather than held for the whole history. */
    fun loadTripPoints(trip: Trip): List<TraceStore.TracePoint> =
        matchTripPoints(readSegments(), trip)

    /** Each trip's thumbnail and the towns it entered, in order. A trip with
     *  no matching trace has neither. */
    fun match(segments: List<TraceSegment>, trips: List<Trip>, municipalities: List<Municipality>): MatchedTraces {
        val thumbnails = HashMap<Long, List<LatLon>>()
        val visits = ArrayList<Pair<Trip, List<Municipality>>>(trips.size)
        for (trip in trips) {
            val points = matchTripPoints(segments, trip).map { it.at }
            if (points.isEmpty()) continue
            visits += trip to TripInsights.visitedInOrder(points, municipalities)
            thumbnails[trip.startTimeMs] = if (points.size > THUMBNAIL_MAX_POINTS) {
                val step = points.size / THUMBNAIL_MAX_POINTS
                points.filterIndexed { i, _ -> i % step == 0 }
            } else points
        }
        return MatchedTraces(thumbnails, visits)
    }

    /** The Logbook for [trips]. The caller loads those itself: Android reads
     *  them with [TripStore.loadStrict] so a corrupt file shows as an error,
     *  and a throwing Kotlin call would crash Swift, so iOS uses [TripStore.load]. */
    fun loadLogbook(trips: List<Trip>): LogbookData {
        val traces = match(readSegments(), trips, MunicipalityStore.load())
        return LogbookData(
            trips = trips,
            thumbnails = traces.thumbnails,
            places = Logbook.placesByTrip(traces.visits),
            titles = TripTitleStore.load(),
            milestones = BadgeStore.earned().map { (def, at) -> LogbookItem.Milestone(def.title, at) },
        )
    }
}

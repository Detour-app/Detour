package com.jellemax.detour.data

import kotlin.concurrent.Volatile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlin.math.pow

/**
 * Persists driven GPS traces (decimated polylines), one JSON array per line.
 * Powers the fog-of-war overlay: every trace is explored territory.
 *
 * A point is `[lat, lon, timeMs, speedKmh, leanDeg]`. The first two are all the
 * fog has ever needed and all older readers look at, so a friend's phone on an
 * older build still draws these lines — it just ignores the tail. Points written
 * before this existed are two long and read back with nulls for the rest.
 *
 * The tail is what the sync server unpacks into per-point rows for Home
 * Assistant; [timeMs] is what ties a point to the trip that was running at that
 * instant, since a trace line carries no trip id of its own.
 */
object TraceStore {

    private const val FILE_NAME = "traces.jsonl"

    /** A recorded point: where you were, when, and what the bike was doing.
     *  [leanDeg] is signed (positive leaning right) and null on a vehicle that
     *  doesn't measure lean. */
    data class TracePoint(
        val at: LatLon,
        val timeMs: Long,
        val speedKmh: Double,
        val leanDeg: Double?,
    )

    /** Bumped on every write so the map reloads traces immediately. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    /** Drops the parsed copy and tells the fog layer the ground moved — it
     *  redraws off [version], so without the bump it keeps showing the previous
     *  rider's territory until something else happens to change it. */
    fun reset() {
        cache = null
        pointsCache = null
        _version.value++
    }

    /**
     * The one parse of [FILE_NAME], tail and all, keyed on the [version] it was
     * read at.
     *
     * This file used to say "nothing is cached here", and that was true until
     * #82: MapScreen's `produceState` is keyed on [version], but a navigation
     * away and back disposes the composition, so the return re-read and
     * re-parsed the whole file for a version it had already parsed. #84 then
     * added a second reader — the Logbook's `TripTraces.readSegments`, which
     * needs the per-point timestamps [loadAll]'s coords projection drops — that
     * had the same problem. Both go through [loadAllPoints] now; [loadAll]
     * projects its result and caches that projection so the fog path still hits
     * a ready coords list. Every write path bumps [version], so a stale parse
     * cannot be served.
     *
     * Each held as one object rather than two fields so a concurrent reader
     * cannot see a matching version paired with the previous parse — the same
     * shape, and the same reason, as `Coverage.Cache`.
     */
    private class PointsCache(val version: Int, val segments: List<List<TracePoint>>)

    @Volatile
    private var pointsCache: PointsCache? = null

    private class Cache(val version: Int, val traces: List<List<LatLon>>)

    @Volatile
    private var cache: Cache? = null

    fun append(trace: List<TracePoint>) {
        if (trace.size < 2) return
        accountFile(FILE_NAME).appendText(encodeLine(trace) + "\n")
        _version.value++
    }

    /** One stored line: `[lat, lon, timeMs, speedKmh, leanDeg|null]` per point.
     *  The format [parsePoints] reads, pinned by PersistedFormatTest. */
    internal fun encodeLine(trace: List<TracePoint>): String = buildJsonArray {
        for (p in trace) addJsonArray {
            add(p.at.lat)
            add(p.at.lon)
            add(p.timeMs)
            add(round(p.speedKmh, 1))
            add(p.leanDeg?.let { JsonPrimitive(round(it, 1)) } ?: JsonNull)
        }
    }.string()

    /** Trace files are synced whole and grow with every ride; a tenth of a km/h
     *  or a degree is all the precision these are read at. */
    private fun round(v: Double, decimals: Int): Double {
        val f = 10.0.pow(decimals)
        return kotlin.math.round(v * f) / f
    }

    /**
     * Every stored line parsed with its tail kept, one entry per line, cached on
     * [version]. This is the one file read + JSON decode; [loadAll] projects
     * from it. Before #84 the history screen's `readTraceSegments` re-read and
     * re-parsed the whole file on every history open, every trip-detail open and
     * every GPX export with nothing memoising it. A write bumps [version], so a
     * stale parse is never served.
     */
    fun loadAllPoints(): List<List<TracePoint>> {
        val t = Perf.start()
        // Version read before the file, deliberately. A write landing between
        // the two stores newer content under the older key, so the next call
        // sees a mismatch and re-reads — one wasted parse. Reading the file
        // first would allow the opposite, which is a stale parse stamped with
        // the new version and served until the next write.
        val version = _version.value
        pointsCache?.let {
            if (it.version == version) {
                Perf.end(t, "TraceStore.loadAllPoints") {
                    listOf("segments" to it.segments.size, "hit" to 1)
                }
                return it.segments
            }
        }
        val f = accountFile(FILE_NAME)
        val lines = if (!f.exists()) emptyList() else f.readLines()
        val parsed = lines.mapNotNull { parsePoints(it) }
        pointsCache = PointsCache(version, parsed)
        Perf.end(t, "TraceStore.loadAllPoints") {
            listOf(
                "segments" to parsed.size,
                "points" to parsed.sumOf { it.size },
                "bytes" to lines.sumOf { it.length + 1 },
                "hit" to 0,
            )
        }
        return parsed
    }

    /** Coords-only view of [loadAllPoints], for the fog overlay and coverage —
     *  every older reader looks only at lat/lon. Projection cached separately so
     *  a fog redraw doesn't re-walk the whole point list, but there is no second
     *  file read or decode behind it. */
    fun loadAll(): List<List<LatLon>> {
        val t = Perf.start()
        val version = _version.value
        cache?.let {
            if (it.version == version) {
                Perf.end(t, "TraceStore.loadAll") {
                    listOf("segments" to it.traces.size, "hit" to 1)
                }
                return it.traces
            }
        }
        val projected = loadAllPoints().map { seg -> seg.map { it.at } }
        cache = Cache(version, projected)
        Perf.end(t, "TraceStore.loadAll") {
            listOf("segments" to projected.size, "points" to projected.sumOf { it.size }, "hit" to 0)
        }
        return projected
    }

    /** Decodes stored JSONL polylines, skipping any line that doesn't decode.
     *  Also used for the traces a friend's device wrote, which arrive in the
     *  same format but have never been near this file. */
    fun parseLines(lines: List<String>): List<List<LatLon>> = lines.mapNotNull { line ->
        try {
            jsonArrayOf(line).arrays()
                .map { p -> LatLon(p.optDouble(0), p.optDouble(1)) }
                .takeIf { it.size >= 2 }
        } catch (e: Exception) {
            null
        }
    }

    /** Decodes one stored line keeping the tail [parseLines] throws away — the
     *  timestamps are what tie a point to a trip, and what a GPX export needs
     *  to be a track rather than a bare shape. Null when the line doesn't
     *  decode or is too short to be a polyline. */
    fun parsePoints(line: String): List<TracePoint>? = try {
        jsonArrayOf(line).arrays().map { p ->
            TracePoint(
                at = LatLon(p.optDouble(0), p.optDouble(1)),
                // Points written before the tail existed are two long; the
                // getters below read those as "unknown" rather than failing.
                timeMs = p.optLong(2, -1L),
                speedKmh = p.optDouble(3, 0.0),
                leanDeg = if (p.isNull(4)) null else p.optDouble(4).takeIf { !it.isNaN() },
            )
        }.takeIf { it.size >= 2 }
    } catch (e: Exception) {
        null
    }

    /**
     * Drop every point recorded in [fromMs]..[toMs], for a deleted trip (#779).
     * A line is a flush of the tracker's buffer, not a trip, so one line can
     * straddle the window: its points either side are kept as separate lines.
     *
     * The bumped [version] is what makes the fog and [Coverage] drop the removed
     * points; both are keyed on it. Same re-read-then-write shape as
     * [replaceLines], with the same narrow window against [append].
     */
    fun removeWindow(fromMs: Long, toMs: Long) {
        val current = rawLines()
        val kept = withoutWindow(current, fromMs, toMs)
        if (kept == current) return
        accountFile(FILE_NAME).writeText(if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n"))
        _version.value++
    }

    /** [lines] with the points in [fromMs]..[toMs] cut out. A line with no
     *  point inside is returned byte-for-byte, so sync still recognises it as
     *  already uploaded; a cut line becomes one line per run of at least two
     *  surviving points. A line that doesn't decode, and a point written before
     *  points carried a time, can't be placed in the window and are kept. */
    internal fun withoutWindow(lines: List<String>, fromMs: Long, toMs: Long): List<String> = lines.flatMap { line ->
        val points = parsePoints(line)
        if (points == null || points.none { it.timeMs in fromMs..toMs }) return@flatMap listOf(line)
        val runs = ArrayList<List<TracePoint>>()
        var run = ArrayList<TracePoint>()
        for (p in points) {
            if (p.timeMs in fromMs..toMs) {
                runs += run
                run = ArrayList()
            } else {
                run += p
            }
        }
        runs += run
        runs.filter { it.size >= 2 }.map { encodeLine(it) }
    }

    fun clear() {
        accountFile(FILE_NAME).deleteIfExists()
        _version.value++
    }

    /** Raw JSONL lines, for server sync. */
    fun rawLines(): List<String> = accountFile(FILE_NAME).readLines().filter { it.isNotBlank() }

    /**
     * Overwrite the store with merged lines from the sync server, keeping every
     * line appended since [uploaded] was read. A recording trip flushes a line
     * every 200 points and the /sync round trip can take minutes; replacing
     * with the server's copy alone dropped a line flushed in between, which the
     * server never received either (#693).
     *
     * The file is re-read here, with no suspension before the write, so the
     * window shrinks from the round trip to this write. [append] takes no lock,
     * so it is narrower, not gone.
     */
    fun replaceLines(lines: List<String>, uploaded: Set<String>) {
        val t = Perf.start()
        val kept = withUnsynced(lines, uploaded, rawLines())
        val text = kept.joinToString("\n", postfix = "\n")
        accountFile(FILE_NAME).writeText(text)
        _version.value++
        Perf.end(t, "TraceStore.replaceLines") {
            listOf("segments" to kept.size, "bytes" to text.length)
        }
    }

    /** [server]'s lines, then each [current] line that was neither in the
     *  upload ([uploaded]) nor already returned by the server. */
    internal fun withUnsynced(server: List<String>, uploaded: Set<String>, current: List<String>): List<String> {
        val serverLines = server.filter { it.isNotBlank() }
        val returned = serverLines.toHashSet()
        return serverLines + current.filter { it.isNotBlank() && it !in uploaded && it !in returned }
    }
}

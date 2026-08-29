package com.jellemax.detour.data

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

    /** Nothing is cached here — [loadAll] reads the file every call — but the
     *  fog layer redraws off [version], so it has to be told the ground moved
     *  or it keeps showing the previous rider's territory until something
     *  else happens to bump it. */
    fun reset() {
        _version.value++
    }

    fun append(trace: List<TracePoint>) {
        if (trace.size < 2) return
        val line = buildJsonArray {
            for (p in trace) addJsonArray {
                add(p.at.lat)
                add(p.at.lon)
                add(p.timeMs)
                add(round(p.speedKmh, 1))
                add(p.leanDeg?.let { JsonPrimitive(round(it, 1)) } ?: JsonNull)
            }
        }
        accountFile(FILE_NAME).appendText(line.string() + "\n")
        _version.value++
    }

    /** Trace files are synced whole and grow with every ride; a tenth of a km/h
     *  or a degree is all the precision these are read at. */
    private fun round(v: Double, decimals: Int): Double {
        val f = 10.0.pow(decimals)
        return kotlin.math.round(v * f) / f
    }

    fun loadAll(): List<List<LatLon>> {
        val f = accountFile(FILE_NAME)
        if (!f.exists()) return emptyList()
        return parseLines(f.readLines())
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

    fun clear() {
        accountFile(FILE_NAME).deleteIfExists()
        _version.value++
    }

    /** Raw JSONL lines, for server sync. */
    fun rawLines(): List<String> = accountFile(FILE_NAME).readLines().filter { it.isNotBlank() }

    /** Overwrite the store with merged lines from the sync server. */
    fun replaceLines(lines: List<String>) {
        accountFile(FILE_NAME).writeText(
            lines.filter { it.isNotBlank() }.joinToString("\n", postfix = "\n"))
        _version.value++
    }
}

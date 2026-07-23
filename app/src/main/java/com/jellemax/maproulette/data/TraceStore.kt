package com.jellemax.maproulette.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

    fun append(context: Context, trace: List<TracePoint>) {
        if (trace.size < 2) return
        val line = JSONArray().apply {
            for (p in trace) put(
                JSONArray()
                    .put(p.at.lat)
                    .put(p.at.lon)
                    .put(p.timeMs)
                    .put(round(p.speedKmh, 1))
                    .put(p.leanDeg?.let { round(it, 1) } ?: JSONObject.NULL)
            )
        }
        file(context).appendText(line.toString() + "\n")
        _version.value++
    }

    /** Trace files are synced whole and grow with every ride; a tenth of a km/h
     *  or a degree is all the precision these are read at. */
    private fun round(v: Double, decimals: Int): Double {
        val f = 10.0.pow(decimals)
        return kotlin.math.round(v * f) / f
    }

    fun loadAll(context: Context): List<List<LatLon>> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return parseLines(f.readLines())
    }

    /** Decodes stored JSONL polylines, skipping any line that doesn't decode.
     *  Also used for the traces a friend's device wrote, which arrive in the
     *  same format but have never been near this file. */
    fun parseLines(lines: List<String>): List<List<LatLon>> = lines.mapNotNull { line ->
        try {
            val arr = JSONArray(line)
            (0 until arr.length()).map { i ->
                val p = arr.getJSONArray(i)
                LatLon(p.getDouble(0), p.getDouble(1))
            }.takeIf { it.size >= 2 }
        } catch (e: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        file(context).delete()
        _version.value++
    }

    /** Raw JSONL lines, for server sync. */
    fun rawLines(context: Context): List<String> {
        val f = file(context)
        return if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList()
    }

    /** Overwrite the store with merged lines from the sync server. */
    fun replaceLines(context: Context, lines: List<String>) {
        file(context).writeText(
            lines.filter { it.isNotBlank() }.joinToString("\n", postfix = "\n"))
        _version.value++
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}

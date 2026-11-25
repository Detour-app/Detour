package com.jellemax.detour.ble

import com.jellemax.detour.data.LatLon
import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Line kinds on the wire, in moto_hud's `map_link.h` numbering. */
internal enum class MinimapKind(val code: Int) { MINOR(0), MAJOR(1), ROUTE(2) }

internal class MinimapLine(val kind: MinimapKind, val points: List<LatLon>)

/**
 * The external display's minimap frame (#1070): nearby roads and the route, projected
 * heading-up in metres with the rider at the origin, so the board only scales and draws.
 *
 * Wire format, little-endian, as moto_hud `src/map_link.h` parses it:
 *
 *     u16 radiusMeters
 *     repeated: u8 kind, u8 count, count x (i16 rightMeters, i16 aheadMeters)
 *
 * Records go minor roads first and the route last: the board draws in wire order and, past
 * its line cap, drops records from the front.
 */
internal object Minimap {

    /** The board's reassembly buffer (`map_link::MAX_BYTES`). */
    const val MAX_BYTES = 4 * 1024
    private const val MAX_LINES = 192
    private const val MAX_RECORD_POINTS = 255
    private const val METERS_PER_DEG = 111_320.0

    /** Wider at speed, so the next turn is on screen roughly 25 s before it arrives. */
    fun radiusFor(speedMps: Double): Int = (speedMps * 25).coerceIn(300.0, 1200.0).roundToInt()

    fun encode(at: LatLon, headingDeg: Double, radiusM: Int, lines: List<MinimapLine>): ByteArray {
        val h = Math.toRadians(headingDeg)
        val sinH = sin(h)
        val cosH = cos(h)
        val cosLat = cos(Math.toRadians(at.lat))
        // Rider-relative (right, ahead) in metres.
        fun project(p: LatLon): DoubleArray {
            val east = (p.lon - at.lon) * METERS_PER_DEG * cosLat
            val north = (p.lat - at.lat) * METERS_PER_DEG
            return doubleArrayOf(east * cosH - north * sinH, east * sinH + north * cosH)
        }

        // Route first so it gets the byte budget, then major, then minor roads.
        val byPriority = lines.sortedByDescending { it.kind.code }
        val kept = mutableListOf<Pair<MinimapKind, List<DoubleArray>>>()
        var bytes = 2
        for (line in byPriority) {
            for (run in clip(line.points.map(::project), radiusM.toDouble())) {
                val cost = 2 + 4 * run.size
                if (kept.size >= MAX_LINES || bytes + cost > MAX_BYTES) continue
                kept += line.kind to run
                bytes += cost
            }
        }

        val out = ByteArrayOutputStream(bytes)
        out.u16(radiusM)
        for ((kind, run) in kept.asReversed()) {
            out.write(kind.code)
            out.write(run.size)
            for (p in run) {
                out.i16(p[0])
                out.i16(p[1])
            }
        }
        return out.toByteArray()
    }

    /**
     * The stretches of [line] inside [radius], each with one point either side so it reaches
     * the screen edge, thinned to a step of radius/100 and cut into records of at most 255
     * points (consecutive records share their joining point).
     */
    internal fun clip(line: List<DoubleArray>, radius: Double): List<List<DoubleArray>> {
        val inside = line.map { hypot(it[0], it[1]) <= radius }
        val minStep = radius / 100
        val runs = mutableListOf<List<DoubleArray>>()
        var i = 0
        while (i < line.size) {
            if (!inside[i]) { i++; continue }
            var end = i
            while (end + 1 < line.size && inside[end + 1]) end++
            val from = maxOf(0, i - 1)
            val to = minOf(line.size - 1, end + 1)
            val run = mutableListOf(line[from])
            for (k in from + 1..to) {
                val last = run.last()
                if (k == to || hypot(line[k][0] - last[0], line[k][1] - last[1]) >= minStep) run += line[k]
            }
            if (run.size >= 2) {
                var start = 0
                while (start < run.size - 1) {
                    val stop = minOf(run.size, start + MAX_RECORD_POINTS)
                    runs += run.subList(start, stop)
                    start = stop - 1
                }
            }
            i = end + 1
        }
        return runs
    }

    private fun ByteArrayOutputStream.u16(v: Int) {
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
    }

    // A point past i16 (32 km out, only the far end of a very long segment) is pinned to the
    // edge of the range rather than wrapping round to the other side of the screen.
    private fun ByteArrayOutputStream.i16(v: Double) =
        u16(v.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
}

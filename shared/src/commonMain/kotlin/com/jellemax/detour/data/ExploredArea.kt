package com.jellemax.detour.data

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor

/**
 * Fast "have I been here?" lookup over the stored fog-of-war traces, on a
 * ~250 m grid. Spins use it to prefer destinations in undiscovered territory;
 * explored places keep a small weight ([EXPLORED_WEIGHT]) so spins still work
 * when everything nearby has been driven.
 */
class ExploredArea(traces: List<List<LatLon>>) {

    private val cells = HashSet<Long>()

    init {
        for (trace in traces) for (p in trace) {
            val (r, c) = cell(p)
            cells.add(key(r, c))
        }
    }

    /** True when [p] lies within roughly one grid cell of any driven trace. */
    fun isExplored(p: LatLon): Boolean {
        if (cells.isEmpty()) return false
        val (r, c) = cell(p)
        for (dr in -1..1) for (dc in -1..1) {
            if (key(r + dr, c + dc) in cells) return true
        }
        return false
    }

    private fun cell(p: LatLon): Pair<Long, Long> {
        val row = floor(p.lat * METERS_PER_DEG / CELL_METERS).toLong()
        val col = floor(
            p.lon * METERS_PER_DEG * cos(p.lat * PI / 180.0) / CELL_METERS).toLong()
        return row to col
    }

    private fun key(row: Long, col: Long) = row * 4_000_000L + col

    companion object {
        private const val CELL_METERS = 250.0
        private const val METERS_PER_DEG = 111_320.0

        /** Selection weight kept by already-explored candidates (1.0 = no bias). */
        const val EXPLORED_WEIGHT = 0.15

        fun load(): ExploredArea = ExploredArea(TraceStore.loadAll())
    }
}

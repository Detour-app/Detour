package com.jellemax.detour.data

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * How much a rider-chosen "Download area" (#510) would cost, worked out before
 * anything is fetched, so the size is shown first and a view too big to fit is
 * refused rather than started and cut short.
 *
 * Pure arithmetic over the slippy-map tile grid OpenFreeMap serves. The download
 * itself is MapLibre's offline region API on Android (`map/RouteTileCache.kt`).
 */
object OfflineArea {
    /** Most the offline map may hold, in bytes. The #439 route corridor stops at the same figure. */
    const val MAX_BYTES = 150L * 1024 * 1024

    /**
     * What every region costs before its first tile: the style, the glyph
     * ranges and the sprites. Measured on the emulator on 2026-10-12: a view of
     * about eight tiles saved 18 MB and one of 50 tiles 19 MB, so nearly all of a
     * small area's size is this. Rounded up from 18.6.
     */
    const val FIXED_BYTES = 20L * 1024 * 1024

    /**
     * Average size of one OpenFreeMap vector tile. The same two runs, plus 294
     * tiles saving 21 MB, put it near 8 KB in Luxembourg city; 10 KB leaves room
     * for denser tiles. A planning figure, not a guarantee: the download itself
     * stops at [MAX_BYTES] whatever the estimate said.
     */
    const val BYTES_PER_TILE = 10L * 1024

    /** Tiles a region covering [box] holds across [minZoom]..[maxZoom], inclusive. */
    fun tileCount(
        box: LatLonBox,
        minZoom: Int = RouteCorridor.MIN_ZOOM.toInt(),
        maxZoom: Int = RouteCorridor.MAX_ZOOM.toInt(),
    ): Long = (minZoom..maxZoom).sumOf { z ->
        val cols = tileX(box.east, z) - tileX(box.west, z) + 1
        val rows = tileY(box.south, z) - tileY(box.north, z) + 1
        cols * rows
    }

    /** What [box] would download, in bytes: [FIXED_BYTES] plus [tileCount] x [BYTES_PER_TILE]. */
    fun estimateBytes(box: LatLonBox): Long = FIXED_BYTES + tileCount(box) * BYTES_PER_TILE

    /** Whether [box] fits under [MAX_BYTES]. A box crossing the antimeridian
     *  (west past east) never does: its tile span would come out negative. */
    fun fits(box: LatLonBox): Boolean = box.west <= box.east && estimateBytes(box) <= MAX_BYTES

    private const val MAX_LAT = 85.05112878

    private fun tileX(lon: Double, zoom: Int): Long {
        val n = 1L shl zoom
        return floor((lon.coerceIn(-180.0, 180.0) + 180.0) / 360.0 * n).toLong().coerceIn(0, n - 1)
    }

    private fun tileY(lat: Double, zoom: Int): Long {
        val n = 1L shl zoom
        val rad = lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180.0
        val y = (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * n
        return floor(y).toLong().coerceIn(0, n - 1)
    }
}

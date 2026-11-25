package com.jellemax.detour.ble

import com.jellemax.detour.data.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Minimap.encode] against moto_hud's `map_link.h` wire format (#1070). */
class MinimapTest {

    private val here = LatLon(51.0, 4.0)
    private val metersPerDegLat = 111_320.0

    /** A point [north] metres due north of [here]. */
    private fun north(m: Double) = LatLon(here.lat + m / metersPerDegLat, here.lon)

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun i16(b: ByteArray, i: Int) = u16(b, i).toShort().toInt()

    /** The records as (kind, [(right, ahead)...]), parsed the way the board does. */
    private fun records(b: ByteArray): List<Pair<Int, List<Pair<Int, Int>>>> {
        val out = mutableListOf<Pair<Int, List<Pair<Int, Int>>>>()
        var i = 2
        while (i < b.size) {
            val kind = b[i].toInt() and 0xFF
            val count = b[i + 1].toInt() and 0xFF
            out += kind to (0 until count).map { k -> i16(b, i + 2 + 4 * k) to i16(b, i + 4 + 4 * k) }
            i += 2 + 4 * count
        }
        return out
    }

    @Test
    fun `heading up - a road due north is ahead facing north and on the left facing east`() {
        val road = listOf(MinimapLine(MinimapKind.MINOR, listOf(here, north(200.0))))
        val facingNorth = records(Minimap.encode(here, 0.0, 300, road)).single().second
        assertEquals(listOf(0 to 0, 0 to 200), facingNorth)
        val facingEast = records(Minimap.encode(here, 90.0, 300, road)).single().second
        assertEquals(listOf(0 to 0, -200 to 0), facingEast)
    }

    @Test
    fun `radius header, route drawn last, far stretches clipped with one point past the edge`() {
        val lines = listOf(
            MinimapLine(MinimapKind.ROUTE, listOf(here, north(100.0))),
            MinimapLine(MinimapKind.MAJOR, listOf(north(-100.0), north(200.0), north(5_000.0), north(6_000.0))),
        )
        val frame = Minimap.encode(here, 0.0, 300, lines)
        assertEquals(300, u16(frame, 0))
        val recs = records(frame)
        assertEquals(listOf(MinimapKind.MAJOR.code, MinimapKind.ROUTE.code), recs.map { it.first })
        assertEquals(listOf(0 to -100, 0 to 200, 0 to 5000), recs[0].second)
    }

    @Test
    fun `stays inside the board's buffer and keeps the route when roads overflow it`() {
        val zigzag = (0 until 2_000).map { k -> north((k % 2) * 250.0 - 100.0) }
        val lines = List(20) { MinimapLine(MinimapKind.MINOR, zigzag) } +
            MinimapLine(MinimapKind.ROUTE, listOf(here, north(250.0)))
        val frame = Minimap.encode(here, 0.0, 300, lines)
        assertTrue(frame.size <= Minimap.MAX_BYTES)
        val recs = records(frame)
        assertEquals(MinimapKind.ROUTE.code, recs.last().first)
        assertTrue(recs.all { it.second.size in 2..255 })
    }
}

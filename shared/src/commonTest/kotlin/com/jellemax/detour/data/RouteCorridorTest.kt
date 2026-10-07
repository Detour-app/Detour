package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers RouteCorridor.kt: the boxes the offline tile pre-fetch downloads along
 * an active route (#439). A gap in them is a stretch of ride where the map goes
 * blank once the signal drops — invisible until the rider is on that road.
 */
class RouteCorridorTest {

    private val metersPerDegLat = 111_320.0

    private fun LatLonBox.contains(p: LatLon) =
        p.lat in south..north && p.lon in west..east

    @Test
    fun emptyLineFetchesNothing() {
        assertEquals(emptyList(), RouteCorridor.boxes(emptyList()))
    }

    @Test
    fun singlePointGetsOneBoxPaddedByHalfWidth() {
        val boxes = RouteCorridor.boxes(listOf(LatLon(0.0, 0.0)), halfWidthM = 5_000.0)
        assertEquals(1, boxes.size)
        val b = boxes.single()
        assertEquals(5_000.0 / metersPerDegLat, b.north, 1e-9)
        assertEquals(-5_000.0 / metersPerDegLat, b.south, 1e-9)
        // At the equator a degree of longitude is a degree of latitude, to within the pad's own latitude.
        assertEquals(b.north, b.east, 1e-4)
    }

    @Test
    fun shortLineIsOneBox() {
        // ~5.5 km north-south, under one 10 km chunk.
        val line = listOf(LatLon(51.0, 4.0), LatLon(51.05, 4.0))
        assertEquals(1, RouteCorridor.boxes(line, chunkM = 10_000.0).size)
    }

    @Test
    fun longLineIsCutIntoChunksThatCoverEveryPoint() {
        // A 1 km-spaced line 60 km long, due north from Brussels.
        val line = (0..60).map { LatLon(50.85 + it * 1_000.0 / metersPerDegLat, 4.35) }
        val boxes = RouteCorridor.boxes(line, halfWidthM = 5_000.0, chunkM = 10_000.0)
        assertEquals(6, boxes.size, "60 km in 10 km chunks")
        line.forEach { p -> assertTrue(boxes.any { it.contains(p) }, "$p outside every box: map blank there offline") }
    }

    @Test
    fun sparseSegmentIsSplitSoNoBoxSpansTheWholeStretch() {
        // A 50 km straight with no vertices in between, like a motorway.
        val a = LatLon(50.0, 5.0)
        val b = LatLon(50.0 + 50_000.0 / metersPerDegLat, 5.0)
        val boxes = RouteCorridor.boxes(listOf(a, b), halfWidthM = 5_000.0, chunkM = 10_000.0)
        assertEquals(5, boxes.size)
        boxes.forEach { box ->
            val spanM = (box.north - box.south) * metersPerDegLat
            assertTrue(spanM <= 10_000.0 + 2 * 5_000.0 + 1.0, "box spans $spanM m, more than one chunk plus padding")
        }
        // The midpoint of the straight, which no vertex sits on, is still covered.
        val mid = LatLon((a.lat + b.lat) / 2, 5.0)
        assertTrue(boxes.any { it.contains(mid) })
    }

    @Test
    fun padIsHalfWidthEitherSideOfTheLine() {
        val line = listOf(LatLon(52.0, 5.0), LatLon(52.0, 5.05))
        val box = RouteCorridor.boxes(line, halfWidthM = 5_000.0).single()
        assertEquals(52.0 + 5_000.0 / metersPerDegLat, box.north, 1e-9)
        assertEquals(52.0 - 5_000.0 / metersPerDegLat, box.south, 1e-9)
        // Longitude pad widens with latitude: at 52° a degree east is ~0.62 of a degree north.
        val eastPadM = (box.east - 5.05) * metersPerDegLat * kotlin.math.cos(52.0 * kotlin.math.PI / 180)
        assertTrue(eastPadM >= 5_000.0, "east pad $eastPadM m narrower than the corridor")
    }
}

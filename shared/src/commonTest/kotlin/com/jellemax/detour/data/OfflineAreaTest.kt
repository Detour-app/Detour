package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineAreaTest {
    private val world = LatLonBox(north = 85.0, south = -85.0, east = 179.9, west = -179.9)

    @Test
    fun theWholeWorldIsOneTileAtZoomZeroAndFourAtOne() {
        assertEquals(1, OfflineArea.tileCount(world, minZoom = 0, maxZoom = 0))
        assertEquals(4, OfflineArea.tileCount(world, minZoom = 1, maxZoom = 1))
        assertEquals(1 + 4 + 16, OfflineArea.tileCount(world, minZoom = 0, maxZoom = 2))
    }

    @Test
    fun aPointIsOneTilePerZoomLevel() {
        val point = LatLonBox(north = 49.6, south = 49.6, east = 6.13, west = 6.13)
        assertEquals(8, OfflineArea.tileCount(point, minZoom = 8, maxZoom = 15))
    }

    @Test
    fun anAreaSpanningTwoTilesColumnsCountsBoth() {
        // Zoom 1 splits the world at longitude 0 and latitude 0.
        val across = LatLonBox(north = 10.0, south = 5.0, east = 10.0, west = -10.0)
        assertEquals(2, OfflineArea.tileCount(across, minZoom = 1, maxZoom = 1))
        val corner = LatLonBox(north = 10.0, south = -10.0, east = 10.0, west = -10.0)
        assertEquals(4, OfflineArea.tileCount(corner, minZoom = 1, maxZoom = 1))
    }

    @Test
    fun aCityViewFitsAndACountryDoesNot() {
        val luxembourgCity = LatLonBox(north = 49.66, south = 49.57, east = 6.22, west = 6.04)
        val belgium = LatLonBox(north = 51.5, south = 49.5, east = 6.4, west = 2.5)
        assertTrue(OfflineArea.fits(luxembourgCity))
        assertFalse(OfflineArea.fits(belgium))
    }

    @Test
    fun theEstimateIsTheFixedCostPlusTilesTimesTheAverageTileSize() {
        val box = LatLonBox(north = 49.66, south = 49.57, east = 6.22, west = 6.04)
        assertEquals(
            OfflineArea.FIXED_BYTES + OfflineArea.tileCount(box) * OfflineArea.BYTES_PER_TILE,
            OfflineArea.estimateBytes(box),
        )
    }

    @Test
    fun aTinyAreaStillCostsTheFixedPart() {
        val point = LatLonBox(north = 49.6, south = 49.6, east = 6.13, west = 6.13)
        assertTrue(OfflineArea.estimateBytes(point) > OfflineArea.FIXED_BYTES)
        assertTrue(OfflineArea.estimateBytes(point) < OfflineArea.FIXED_BYTES + 1024 * 1024)
    }

    @Test
    fun coordinatesPastTheMapEdgeAreClampedNotRejected() {
        val past = LatLonBox(north = 90.0, south = -90.0, east = 190.0, west = -190.0)
        assertEquals(1, OfflineArea.tileCount(past, minZoom = 0, maxZoom = 0))
    }
}

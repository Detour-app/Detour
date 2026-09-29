package com.jellemax.detour.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [withMapVisible] is what [TripTrackingService.setUiVisible] keeps: which maps
 * are on screen. The phone and the car show and hide theirs independently, so
 * one going away must not take the other's navigation-grade fixes with it.
 */
class MapVisibilityTest {

    @Test fun `locking the phone leaves the car map visible`() {
        var visible = emptySet<MapSurface>()
        visible = withMapVisible(visible, MapSurface.PHONE, true)
        visible = withMapVisible(visible, MapSurface.CAR, true)
        visible = withMapVisible(visible, MapSurface.PHONE, false)
        assertEquals(setOf(MapSurface.CAR), visible)
    }

    @Test fun `hiding the last map leaves none visible`() {
        var visible = withMapVisible(emptySet(), MapSurface.CAR, true)
        visible = withMapVisible(visible, MapSurface.CAR, false)
        assertTrue(visible.isEmpty())
    }

    @Test fun `hiding a map that was never shown changes nothing`() {
        val visible = setOf(MapSurface.CAR)
        assertEquals(visible, withMapVisible(visible, MapSurface.PHONE, false))
    }
}

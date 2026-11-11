package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `RouteStore.save` and `RouteStore.rename` stamp `editedAtMs` (#910), so the
 * server can keep the newest copy when the phone and the web planner both edit
 * a route. Tested through their pure halves, since the store's file I/O needs
 * a platform Context.
 */
class RouteEditStampTest {

    private fun route(id: Long, name: String = "Route $id", editedAtMs: Long = 0) = SavedRoute(
        id = id,
        name = name,
        createdMs = id,
        mode = TravelMode.MOTO,
        stops = listOf(RouteStop(LatLon(50.8, 3.2)), RouteStop(LatLon(50.9, 3.3))),
        polyline = emptyList(),
        distanceMeters = null,
        timeMs = null,
        editedAtMs = editedAtMs,
    )

    @Test fun aNewRouteIsStampedOnSave() {
        val saved = routesWithSaved(listOf(route(1)), route(2), nowMs = 500)
        assertEquals(listOf(2L to 500L, 1L to 0L), saved.map { it.id to it.editedAtMs })
    }

    @Test fun aReplacedRouteIsRestampedEvenWhenItCarriedAnOlderStamp() {
        val saved = routesWithSaved(listOf(route(1, editedAtMs = 100)), route(1, "Edited", editedAtMs = 100), nowMs = 500)
        assertEquals(listOf("Edited" to 500L), saved.map { it.name to it.editedAtMs })
    }

    @Test fun aRenameStampsOnlyTheRenamedRoute() {
        val renamed = routesWithRenamed(listOf(route(1), route(2)), id = 1, name = "  Coast  ", nowMs = 700)
        assertEquals(
            listOf(Triple(2L, "Route 2", 0L), Triple(1L, "Coast", 700L)),
            renamed?.map { Triple(it.id, it.name, it.editedAtMs) },
        )
    }

    @Test fun aBlankRenameChangesNothing() {
        assertNull(routesWithRenamed(listOf(route(1)), id = 1, name = "   ", nowMs = 700))
    }
}

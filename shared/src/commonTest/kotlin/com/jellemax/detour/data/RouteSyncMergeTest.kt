package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The write-back half of route sync (#592): what [RouteStore.replaceFromServer]
 * keeps when the server's union comes back. Tested through the pure
 * [mergeServerRoutes], since the store's file I/O needs a platform Context.
 */
class RouteSyncMergeTest {

    private fun route(id: Long, name: String = "Route $id", createdMs: Long = id) = SavedRoute(
        id = id,
        name = name,
        createdMs = createdMs,
        mode = TravelMode.MOTO,
        stops = listOf(RouteStop(LatLon(50.8, 3.2)), RouteStop(LatLon(50.9, 3.3))),
        polyline = emptyList(),
        distanceMeters = null,
        timeMs = null,
    )

    @Test fun aReinstallGetsEveryRouteTheServerHolds() {
        val server = listOf(route(1), route(2))
        assertEquals(listOf(2L, 1L), mergeServerRoutes(server, emptyList(), emptyList(), emptySet()).map { it.id })
    }

    @Test fun aRouteSavedWhileTheSyncWasOutIsKept() {
        val uploaded = listOf(route(1))
        val local = uploaded + route(3)
        val merged = mergeServerRoutes(listOf(route(1)), uploaded, local, emptySet())
        assertEquals(listOf(3L, 1L), merged.map { it.id })
    }

    @Test fun aRenameMadeWhileTheSyncWasOutWinsOverTheServerCopy() {
        val uploaded = listOf(route(1, "Old"))
        val local = listOf(route(1, "New"))
        val merged = mergeServerRoutes(listOf(route(1, "Old")), uploaded, local, emptySet())
        assertEquals(listOf("New"), merged.map { it.name })
    }

    @Test fun anUnchangedLocalRouteTakesTheServerCopy() {
        // Another device renamed it; this one uploaded its stale copy, and the
        // server's answer is what it holds now.
        val uploaded = listOf(route(1, "Stale"))
        val merged = mergeServerRoutes(listOf(route(1, "Renamed elsewhere")), uploaded, uploaded, emptySet())
        assertEquals(listOf("Renamed elsewhere"), merged.map { it.name })
    }

    @Test fun aRouteDeletedHereIsDroppedFromTheServerUnion() {
        val server = listOf(route(1), route(2))
        val merged = mergeServerRoutes(server, emptyList(), emptyList(), setOf(1L))
        assertEquals(listOf(2L), merged.map { it.id })
    }

    @Test fun aRouteDeletedWhileTheSyncWasOutStaysDeleted() {
        val uploaded = listOf(route(1), route(2))
        val local = listOf(route(2))
        val merged = mergeServerRoutes(uploaded, uploaded, local, setOf(1L))
        assertEquals(listOf(2L), merged.map { it.id })
    }

    @Test fun theResultIsNewestFirst() {
        val server = listOf(route(1, createdMs = 10), route(2, createdMs = 30), route(3, createdMs = 20))
        val merged = mergeServerRoutes(server, emptyList(), emptyList(), emptySet())
        assertEquals(listOf(2L, 3L, 1L), merged.map { it.id })
    }
}

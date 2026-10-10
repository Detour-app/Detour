package com.jellemax.detour.data

import com.jellemax.detour.data.SavedSpins.LoopJoin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Saving a spin result and riding a saved loop again (#589). */
class SavedSpinsTest {

    private val home = LatLon(50.80, 3.20)
    private val a = LatLon(50.85, 3.25)
    private val b = LatLon(50.90, 3.20)
    private val c = LatLon(50.85, 3.15)

    private fun loopResult() = RouteResult(
        polyline = listOf(home, a, b, c, home),
        waypoints = listOf(a, b, c),
        distanceMeters = 42_000.0,
        timeMs = 3_600_000L,
    )

    @Test fun aLoopIsStoredAsItsStartViaPointsAndStartAgain() {
        val saved = SavedSpins.fromSpin(
            id = 1_700_000_000_000L, name = "Sunday", mode = TravelMode.MOTO,
            start = home, destination = null, route = loopResult(),
        )
        assertEquals(listOf(home, a, b, c, home), saved.stops.map { it.at })
        assertEquals(RouteOrigin.SPIN, saved.origin)
        assertEquals(listOf(home, a, b, c, home), saved.polyline)
        assertEquals(42_000.0, saved.distanceMeters)
        assertEquals(3_600_000L, saved.timeMs)
        assertEquals(1_700_000_000_000L, saved.createdMs)
        assertTrue(SavedSpins.isLoop(saved))
    }

    @Test fun aDestinationSpinIsStoredAsItsTwoEnds() {
        val saved = SavedSpins.fromSpin(
            id = 1L, name = "Kemmelberg", mode = TravelMode.CAR,
            start = home, destination = b, route = null,
        )
        assertEquals(listOf(home, b), saved.stops.map { it.at })
        assertEquals("Kemmelberg", saved.stops.last().name)
        assertEquals(emptyList(), saved.polyline)
        // Two stops ride through the existing destination path, not as a loop.
        assertFalse(SavedSpins.isLoop(saved))
    }

    @Test fun aSavedLoopSurvivesTheRouteStoreFormat() {
        // The acceptance test is "save, kill the app, reopen": what survives a
        // restart is exactly what routes.json round-trips.
        val saved = SavedSpins.fromSpin(1L, "Sunday", TravelMode.MOTO, home, null, loopResult())
        val reread = routeFromJson(saved.toJson())!!
        assertEquals(saved, reread)
        assertTrue(SavedSpins.isLoop(reread))
    }

    @Test fun aPlannedRouteWithManyStopsIsNotALoop() {
        // Planned multi-stop routes keep their external-maps hand-off.
        val planned = SavedSpins.fromSpin(1L, "x", TravelMode.MOTO, home, null, loopResult())
            .copy(origin = RouteOrigin.PLANNED)
        assertFalse(SavedSpins.isLoop(planned))
    }

    @Test fun atStartIsWithinTheRadiusOfTheFirstStop() {
        val saved = SavedSpins.fromSpin(1L, "x", TravelMode.MOTO, home, null, loopResult())
        // 0.0003° of latitude is about 33 m.
        assertTrue(SavedSpins.atStart(saved, LatLon(50.8003, 3.20), radiusMeters = 40.0))
        assertFalse(SavedSpins.atStart(saved, LatLon(50.8006, 3.20), radiusMeters = 40.0))
    }

    @Test fun viaStartRidesToTheStartThenTheWholeLoop() {
        val here = LatLon(50.70, 3.20)
        assertEquals(
            listOf(here, home, a, b, c, home),
            SavedSpins.loopRoutingPoints(listOf(home, a, b, c, home), here, LoopJoin.VIA_START),
        )
    }

    @Test fun nearestJoinsAtTheClosestStopAndRidesTheLoopOnceInSavedOrder() {
        val nearB = LatLon(50.91, 3.20)
        assertEquals(
            listOf(nearB, b, c, home, a, b),
            SavedSpins.loopRoutingPoints(listOf(home, a, b, c, home), nearB, LoopJoin.NEAREST),
        )
    }

    @Test fun nearestAtTheStartIsTheSameLoopAsViaStart() {
        val nearHome = LatLon(50.79, 3.20)
        assertEquals(
            listOf(nearHome, home, a, b, c, home),
            SavedSpins.loopRoutingPoints(listOf(home, a, b, c, home), nearHome, LoopJoin.NEAREST),
        )
    }
}

package com.jellemax.detour.data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which saved spin a Go is riding (#590). */
class FollowedRouteTest {

    private val home = LatLon(50.80, 3.20)
    private val dest = LatLon(50.90, 3.20)
    private val via = LatLon(50.85, 3.25)

    private val loop = RouteResult(
        polyline = listOf(home, via, home),
        waypoints = listOf(via),
        distanceMeters = 20_000.0,
    )

    private fun saved(id: Long, origin: RouteOrigin = RouteOrigin.SPIN) =
        SavedSpins.fromSpin(id, "x", TravelMode.MOTO, home, dest, null).copy(origin = origin)

    @AfterTest fun reset() {
        FollowedRoute.claim(null, null)
    }

    @Test fun aDestinationSpinIsClaimedByAGoToItsDestination() {
        FollowedRoute.offer(saved(7L), dest, null)
        // Go re-fetches from wherever the rider is, so the route differs.
        assertEquals(7L, FollowedRoute.claim(dest, loop))
    }

    @Test fun aLoopIsClaimedByAGoOnTheRouteItWasSeededWith() {
        FollowedRoute.offer(saved(7L), null, loop)
        assertEquals(7L, FollowedRoute.claim(null, loop.copy()))
    }

    @Test fun aReSpunLoopIsNotTheSavedOne() {
        FollowedRoute.offer(saved(7L), null, loop)
        assertNull(FollowedRoute.claim(null, loop.copy(distanceMeters = 31_000.0)))
    }

    @Test fun aGoSomewhereElseDoesNotClaimIt() {
        FollowedRoute.offer(saved(7L), dest, null)
        assertNull(FollowedRoute.claim(via, null))
    }

    @Test fun aLoopIsNotClaimedByAGoToADestination() {
        FollowedRoute.offer(saved(7L), null, loop)
        assertNull(FollowedRoute.claim(dest, loop))
    }

    @Test fun itIsConsumedByTheFirstGoWhetherItMatchedOrNot() {
        FollowedRoute.offer(saved(7L), dest, null)
        assertNull(FollowedRoute.claim(via, null))
        // A later Go to the spin's destination is a new choice, not the spin.
        assertNull(FollowedRoute.claim(dest, null))
    }

    @Test fun aPlannedRouteReplacesTheSpin() {
        FollowedRoute.offer(saved(7L), dest, null)
        FollowedRoute.offer(saved(8L, RouteOrigin.PLANNED), dest, null)
        assertNull(FollowedRoute.claim(dest, null))
    }

    @Test fun nothingOfferedClaimsNothing() {
        assertNull(FollowedRoute.claim(dest, loop))
    }
}

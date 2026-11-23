package com.jellemax.detour.map

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RouteResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the navigation dock asks for a route before Start, and when a landed one may be kept (#1027). */
class RoutePreviewTest {

    private val dest = LatLon(51.05, 3.72)
    private val route = RouteResult(
        polyline = listOf(LatLon(51.0, 3.7), dest),
        waypoints = emptyList(),
        distanceMeters = 6_000.0,
    )

    private fun wanted(
        destination: LatLon? = dest,
        route: RouteResult? = null,
        navigating: Boolean = false,
        startFetching: Boolean = false,
        serverUsable: Boolean = true,
        hasOrigin: Boolean = true,
    ) = routePreviewWanted(destination, route, navigating, startFetching, serverUsable, hasOrigin)

    @Test
    fun aFreshDestinationAsksForAPreview() = assertTrue(wanted())

    @Test
    fun noDestinationNoPreview() = assertFalse(wanted(destination = null))

    @Test
    fun aRouteAlreadyDrawnIsNotAskedForAgain() = assertFalse(wanted(route = route))

    @Test
    fun startOwnsTheRequestOnceTapped() {
        assertFalse(wanted(startFetching = true))
        assertFalse(wanted(navigating = true))
    }

    @Test
    fun noServerOrNoPositionNoPreview() {
        assertFalse(wanted(serverUsable = false))
        assertFalse(wanted(hasOrigin = false))
    }

    @Test
    fun aLandedPreviewIsKeptOnlyForTheSameUntouchedDestination() {
        assertTrue(routePreviewStillCurrent(dest, dest, null, navigating = false, startFetching = false))
        assertFalse(routePreviewStillCurrent(dest, null, null, navigating = false, startFetching = false))
        assertFalse(routePreviewStillCurrent(dest, LatLon(50.0, 4.0), null, navigating = false, startFetching = false))
        assertFalse(routePreviewStillCurrent(dest, dest, route, navigating = false, startFetching = false))
        assertFalse(routePreviewStillCurrent(dest, dest, null, navigating = false, startFetching = true))
        assertFalse(routePreviewStillCurrent(dest, dest, null, navigating = true, startFetching = false))
    }
}

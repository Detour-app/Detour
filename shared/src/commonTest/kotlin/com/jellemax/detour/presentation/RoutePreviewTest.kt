package com.jellemax.detour.presentation

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RouteResult
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        guidedTo: LatLon? = null,
        route: RouteResult? = null,
        guidanceBusy: Boolean = false,
        serverUsable: Boolean = true,
        hasOrigin: Boolean = true,
    ) = routePreviewWanted(destination, guidedTo, route, guidanceBusy, serverUsable, hasOrigin)

    @Test
    fun aFreshDestinationAsksForAPreview() = assertTrue(wanted())

    @Test
    fun noDestinationNoPreview() = assertFalse(wanted(destination = null))

    @Test
    fun aRouteAlreadyDrawnIsNotAskedForAgain() = assertFalse(wanted(route = route))

    @Test
    fun theDestinationGuidanceJustRanToIsNotPreviewedAgain() {
        assertFalse(wanted(guidedTo = dest))
        assertTrue(wanted(guidedTo = LatLon(50.0, 4.0)))
    }

    @Test
    fun startOwnsTheRequestOnceTapped() = assertFalse(wanted(guidanceBusy = true))

    @Test
    fun noServerOrNoPositionNoPreview() {
        assertFalse(wanted(serverUsable = false))
        assertFalse(wanted(hasOrigin = false))
    }

    @Test
    fun aLandedPreviewIsKeptOnlyForTheSameUntouchedDestination() {
        assertTrue(routePreviewStillCurrent(dest, dest, null, guidanceBusy = false))
        assertFalse(routePreviewStillCurrent(dest, null, null, guidanceBusy = false))
        assertFalse(routePreviewStillCurrent(dest, LatLon(50.0, 4.0), null, guidanceBusy = false))
        assertFalse(routePreviewStillCurrent(dest, dest, route, guidanceBusy = false))
        assertFalse(routePreviewStillCurrent(dest, dest, null, guidanceBusy = true))
    }
}

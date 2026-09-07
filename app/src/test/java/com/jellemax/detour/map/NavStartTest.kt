package com.jellemax.detour.map

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.NavInstruction
import com.jellemax.detour.data.RouteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three cases pressing Go can be in.
 *
 * These were an early-return ladder inside `MapScreen.startNavigation()`,
 * tangled up with a camera dispatch and a service start — so the branch a
 * rider actually hits when their routing server is down (a drawn loop with no
 * turn data) read exactly like the branch that works, and neither had a test.
 */
class NavStartTest {

    private fun loop(withInstructions: Boolean) = RouteResult(
        polyline = listOf(LatLon(51.0, 4.0), LatLon(51.1, 4.1), LatLon(51.0, 4.0)),
        waypoints = emptyList(),
        distanceMeters = 40_000.0,
        instructions = if (withInstructions) {
            listOf(
                NavInstruction(
                    text = "Turn left",
                    distanceMeters = 100.0,
                    sign = -2,
                    startIndex = 0,
                    endIndex = 1,
                ),
            )
        } else {
            emptyList()
        },
    )

    @Test
    fun aDestinationAlwaysFetchesAFreshLine() {
        val dest = LatLon(52.0, 5.0)
        assertEquals(NavStart.FetchTo(dest), navStart(destination = dest, route = null))
        // Even with a route already drawn: it was built from wherever the spin
        // happened, and the rider may have moved since.
        assertEquals(
            NavStart.FetchTo(dest),
            navStart(destination = dest, route = loop(withInstructions = true)),
        )
    }

    @Test
    fun aLoopWithTurnDataStartsOnTheLineTheSpinProduced() {
        assertEquals(
            NavStart.UseExistingRoute,
            navStart(destination = null, route = loop(withInstructions = true)),
        )
    }

    @Test
    fun aLoopWithNoTurnDataIsUnguidableAndSaysSo() {
        // The Overpass fallback draws a line but carries no instructions. This
        // is the case that fires when the routing server is unreachable, which
        // is exactly when a rider is most likely to press Go and wonder.
        assertEquals(
            NavStart.NoTurnData,
            navStart(destination = null, route = loop(withInstructions = false)),
        )
        assertEquals(NavStart.NoTurnData, navStart(destination = null, route = null))
    }
}

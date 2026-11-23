package com.jellemax.detour.data

import com.jellemax.detour.presentation.spinFailureText
import kotlinx.coroutines.runBlocking
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * [RoundTripPlanner.plan] against a fake road fetch: which sentence the rider gets when too
 * few sectors come back depends on whether the server answered at all (#1021).
 *
 * `runBlocking` rather than `runTest`, for the reason [SingleFlightTest] gives.
 */
class RoundTripPlannerTest {

    private val center = LatLon(51.05, 3.72)

    private fun plan(fetch: suspend (LatLon, Double, String) -> List<OverpassWay>) = runBlocking {
        RoundTripPlanner.plan(center, 10_000.0, RoadRoulette.DRIVABLE_HIGHWAYS, bearingDeg = 0.0, fetch)
    }

    @Test
    fun anUnreachableServerIsReportedAsTheConnectionNotTheRadius() {
        val down = IOException("connect failed")
        val e = assertFailsWith<IOException> { plan { _, _, _ -> throw down } }
        // Same failure, not necessarily the same instance: coroutines may copy it across async.
        assertEquals(down.message, e.message)
        assertFalse(e is SpinFailure)
        assertFalse(spinFailureText(e).contains("larger radius"))
    }

    @Test
    fun aServerThatAnsweredWithNoRoadsStillAsksForALargerRadius() {
        val e = assertFailsWith<SpinFailure> { plan { _, _, _ -> emptyList() } }
        assertEquals("Not enough roads for a round trip — try a larger radius", e.message)
    }

    @Test
    fun aSectorWhoseWiderSearchGotAnAnswerCountsAsAnswered() {
        // First (2 km) fetch of every sector fails, the 5 km retry answers empty: the
        // server is up, the area is sparse.
        assertFailsWith<SpinFailure> {
            plan { _, radius, _ -> if (radius < 3_000.0) throw IOException("blip") else emptyList() }
        }
    }

    @Test
    fun sectorsWithRoadsGiveOneWaypointEach() {
        val wps = plan { at, _, _ ->
            listOf(OverpassWay(listOf(1L, 2L, 3L), listOf(at, LatLon(at.lat + 0.001, at.lon), at)))
        }
        assertEquals(6, wps.size)
    }
}

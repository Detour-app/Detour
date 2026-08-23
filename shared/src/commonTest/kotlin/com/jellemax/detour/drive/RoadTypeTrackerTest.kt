package com.jellemax.detour.drive

import com.jellemax.detour.data.HighwayClass
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Characterises [RoadTypeTracker]'s snap-to-class — the road-type-mix stat
 *  (maxke24/Detour#61), a sibling of [SpeedLimitTracker] querying `highway`
 *  tags instead of `maxspeed`, so untagged residential streets aren't
 *  undercounted (see the design doc's §4 for why the two fetches must stay
 *  separate). */
class RoadTypeTrackerTest {

    private val here = LatLon(50.85, 4.35)

    private fun at(meters: Double, bearingDeg: Double) =
        RoadRoulette.offset(here, meters, bearingDeg * PI / 180.0)

    private val motorway = RoadTypeTracker.ClassifiedWay(
        highwayClass = HighwayClass.MOTORWAY,
        points = listOf(at(400.0, 180.0), at(400.0, 0.0)), // north-south through `here`
    )
    private val local = RoadTypeTracker.ClassifiedWay(
        highwayClass = HighwayClass.LOCAL,
        points = listOf(at(1000.0, 90.0), at(1000.0, 270.0)), // east-west, far away
    )

    @Test
    fun snapsToTheAlignedNearbyWayOverAFarAwayOne() {
        val state = RoadTypeTracker.State(ways = listOf(motorway, local))
        val next = RoadTypeTracker.onFix(state, here, headingDeg = 0.0, distanceSinceLastFixMeters = 25.0)
        assertEquals(25.0, next.meters[HighwayClass.MOTORWAY])
        assertNull(next.meters[HighwayClass.LOCAL])
    }

    @Test
    fun distanceAccumulatesAcrossMultipleFixesOnTheSameClass() {
        var state = RoadTypeTracker.State(ways = listOf(motorway))
        state = RoadTypeTracker.onFix(state, here, 0.0, 25.0)
        state = RoadTypeTracker.onFix(state, here, 0.0, 30.0)
        assertEquals(55.0, state.meters[HighwayClass.MOTORWAY])
    }

    @Test
    fun aFixWithNoNearbyWayLeavesMetersUnchanged() {
        val state = RoadTypeTracker.State(ways = emptyList())
        val next = RoadTypeTracker.onFix(state, here, 0.0, 25.0)
        assertEquals(emptyMap(), next.meters)
    }
}

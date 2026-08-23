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

    /** [meters] from [from] along compass [bearingDeg] — [from] defaults to [here] for the
     *  common case, but pass an explicit point when a fixture needs to be centered
     *  somewhere other than [here] itself (two points at opposite bearings *from the same
     *  center* are collinear THROUGH that center, not "a line near it" — the bug an
     *  earlier draft of this file had). */
    private fun at(meters: Double, bearingDeg: Double, from: LatLon = here) =
        RoadRoulette.offset(from, meters, bearingDeg * PI / 180.0)

    // A point 5m due north of `here`, and an east-west line running through it — so the
    // line's perpendicular distance from `here` is exactly 5m, and its direction (east-west)
    // does not align with a north heading.
    private val northOfHere = at(5.0, 0.0)
    private val misalignedLocal = RoadTypeTracker.ClassifiedWay(
        highwayClass = HighwayClass.LOCAL,
        points = listOf(at(50.0, 90.0, from = northOfHere), at(50.0, 270.0, from = northOfHere)),
    )

    // A point 15m due east of `here`, and a north-south line running through it — farther
    // from `here` than `misalignedLocal` (15m vs 5m), but aligned with a north heading.
    private val eastOfHere = at(15.0, 90.0)
    private val motorway = RoadTypeTracker.ClassifiedWay(
        highwayClass = HighwayClass.MOTORWAY,
        points = listOf(at(400.0, 0.0, from = eastOfHere), at(400.0, 180.0, from = eastOfHere)),
    )

    // Genuinely out of snap range, for the "no nearby way" case. Both endpoints on the same
    // bearing from `here` (not opposite bearings, which would be collinear THROUGH `here`).
    private val farAway = RoadTypeTracker.ClassifiedWay(
        highwayClass = HighwayClass.LOCAL,
        points = listOf(at(1000.0, 90.0), at(1050.0, 90.0)),
    )

    @Test
    fun snapsToTheAlignedNearbyWayOverAMisalignedOneEvenCloser() {
        val state = RoadTypeTracker.State(ways = listOf(motorway, misalignedLocal))
        // Heading 0 (north) aligns with `motorway` (15m, north-south) but not
        // `misalignedLocal` (5m, east-west) — despite `misalignedLocal` being nearer, the
        // aligned candidate wins. Deleting the `aligned` branch from `onFix` would make
        // this resolve to `misalignedLocal` instead and fail this assertion.
        val next = RoadTypeTracker.onFix(state, here, headingDeg = 0.0, distanceSinceLastFixMeters = 25.0)
        assertEquals(25.0, next.meters[HighwayClass.MOTORWAY])
        assertNull(next.meters[HighwayClass.LOCAL])
    }

    @Test
    fun fallsBackToNearestWhenHeadingIsUnknown() {
        val state = RoadTypeTracker.State(ways = listOf(motorway, misalignedLocal))
        // No heading to align against — falls back to whichever way is nearest, which is
        // `misalignedLocal` (5m) over `motorway` (15m).
        val next = RoadTypeTracker.onFix(state, here, headingDeg = null, distanceSinceLastFixMeters = 25.0)
        assertEquals(25.0, next.meters[HighwayClass.LOCAL])
        assertNull(next.meters[HighwayClass.MOTORWAY])
    }

    @Test
    fun distanceAccumulatesAcrossMultipleFixesOnTheSameClass() {
        var state = RoadTypeTracker.State(ways = listOf(motorway))
        state = RoadTypeTracker.onFix(state, here, 0.0, 25.0)
        state = RoadTypeTracker.onFix(state, here, 0.0, 30.0)
        assertEquals(55.0, state.meters[HighwayClass.MOTORWAY])
    }

    @Test
    fun aFixWithNoWayWithinSnapRangeLeavesMetersUnchanged() {
        // A way exists but is 1000m away — genuinely out of MAX_SNAP_METERS range, not the
        // trivial "the ways list is empty" case.
        val state = RoadTypeTracker.State(ways = listOf(farAway))
        val next = RoadTypeTracker.onFix(state, here, 0.0, 25.0)
        assertEquals(emptyMap(), next.meters)
    }
}

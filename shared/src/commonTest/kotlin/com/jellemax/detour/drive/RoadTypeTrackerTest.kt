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
    // Also close enough to snap (within MAX_SNAP_METERS), but running east-west — so a
    // heading of 0deg (north) aligns with `motorway`, not this one, even though both are
    // within range. This is what actually discriminates `aligned` from `nearest`, unlike
    // the old fixture where the second way was 1000m away and never entered the candidate
    // set at all.
    private val misalignedLocal = RoadTypeTracker.ClassifiedWay(
        highwayClass = HighwayClass.LOCAL,
        points = listOf(at(10.0, 90.0), at(10.0, 270.0)), // east-west, ~10m away
    )
    // Genuinely out of snap range, for the "no nearby way" case. Both points sit on the
    // same bearing (90 deg) from `here`, at 1000m and 1050m out, so the whole segment is a
    // sub-stretch of that ray, entirely ~1000m away — unlike opposite bearings (90/270),
    // which would put the segment's closest point AT `here` (a straight line through the
    // center), the same mistake the old "far away" fixture made.
    private val farAway = RoadTypeTracker.ClassifiedWay(
        highwayClass = HighwayClass.LOCAL,
        points = listOf(at(1000.0, 90.0), at(1050.0, 90.0)),
    )

    @Test
    fun snapsToTheAlignedNearbyWayOverAMisalignedOneEvenCloser() {
        val state = RoadTypeTracker.State(ways = listOf(motorway, misalignedLocal))
        // Heading 0 (north) aligns with `motorway` (runs north-south), not `misalignedLocal`
        // (runs east-west) — even though both are within MAX_SNAP_METERS.
        val next = RoadTypeTracker.onFix(state, here, headingDeg = 0.0, distanceSinceLastFixMeters = 25.0)
        assertEquals(25.0, next.meters[HighwayClass.MOTORWAY])
        assertNull(next.meters[HighwayClass.LOCAL])
    }

    @Test
    fun fallsBackToNearestWhenHeadingIsUnknown() {
        val state = RoadTypeTracker.State(ways = listOf(motorway, misalignedLocal))
        // No heading to align against — falls back to whichever way is nearest.
        // `misalignedLocal` is ~10m away, `motorway` is ~0m away (runs through `here`).
        val next = RoadTypeTracker.onFix(state, here, headingDeg = null, distanceSinceLastFixMeters = 25.0)
        assertEquals(25.0, next.meters[HighwayClass.MOTORWAY])
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

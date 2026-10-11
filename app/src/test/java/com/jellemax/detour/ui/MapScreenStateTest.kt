package com.jellemax.detour.ui

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RouteCandidate
import com.jellemax.detour.map.CameraAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the camera a restored spin result starts with (#427): reopening the app
 * on a candidate list used to hand it a following camera, which took the map
 * back to the rider with none of the three pins in view.
 */
class MapScreenStateTest {

    private fun candidate(name: String) = RouteCandidate(
        destination = LatLon(51.2, 4.2),
        name = name,
        route = null,
        straightLineMeters = 1.0,
    )

    @Test
    fun seedWithCandidatesStartsParkedLikeTheSpinThatMadeThem() {
        val s = MapScreenState(SpinResult(candidates = listOf(candidate("A"), candidate("B"))), null)

        assertTrue(
            "#427: a restored candidate list must not hand the camera back to the follow loop",
            s.camAuthority.camSuspended,
        )
        assertEquals(CameraAuthority.ParkReason.Spin, s.camAuthority.parkedBy)
        // Same park the spin took: no quiet-window stamp, following intent kept.
        assertEquals(0L, s.camAuthority.lastGestureMs)
        assertTrue(s.camAuthority.followMe)
    }

    @Test
    fun seedWithoutCandidatesStartsFollowing() {
        val s = MapScreenState(SpinResult(destination = LatLon(51.2, 4.2), destinationName = "A"), null)

        assertFalse(s.camAuthority.camSuspended)
        assertTrue(s.camAuthority.following)
    }
}

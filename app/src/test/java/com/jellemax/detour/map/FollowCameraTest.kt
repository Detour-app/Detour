package com.jellemax.detour.map

import com.jellemax.detour.ui.CAM_RESUME_QUIET_MS
import com.jellemax.detour.ui.CAM_RESUME_SPEED_MPS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [FollowCamera] - when a camera parked by a pan, a pinch or a spin goes
 * back to following. Both halves are threshold arithmetic read on every GPS
 * fix: cheap to test, and expensive to get wrong in a way nobody notices until
 * the map yanks itself out from under a two-finger zoom at 80 km/h. No Android
 * APIs involved, so no emulator/Robolectric needed.
 */
class FollowCameraTest {

    private val now = 1_700_000_000_000L

    /** Comfortably outside the quiet window. */
    private val longAgo = now - CAM_RESUME_QUIET_MS - 1

    @Test
    fun aParkedCameraWithNothingOnScreenWatchesForTheDriveOff() {
        assertTrue(FollowCamera.shouldWatch(
            camSuspended = true, spinning = false, hasCandidates = false, hasSpinOffer = false))
    }

    @Test
    fun anUnparkedCameraHasNothingToWatchFor() {
        assertFalse(FollowCamera.shouldWatch(
            camSuspended = false, spinning = false, hasCandidates = false, hasSpinOffer = false))
    }

    /** A spin in flight, its results on screen, or a convoy's offer still being
     *  voted on each hold the park: the map is parked *because* of them. */
    @Test
    fun aSpinOnScreenHoldsThePark() {
        assertFalse(FollowCamera.shouldWatch(true, spinning = true, hasCandidates = false, hasSpinOffer = false))
        assertFalse(FollowCamera.shouldWatch(true, spinning = false, hasCandidates = true, hasSpinOffer = false))
        assertFalse(FollowCamera.shouldWatch(true, spinning = false, hasCandidates = false, hasSpinOffer = true))
    }

    @Test
    fun resumesOnceMovingAndQuietForLongEnough() {
        assertTrue(FollowCamera.shouldResume(speedMps = 10.0, nowMs = now, lastGestureMs = longAgo))
    }

    /** Stopped at a light with the map panned stays panned, however long ago the
     *  pan was. */
    @Test
    fun neverResumesBelowTheSpeedThreshold() {
        assertFalse(FollowCamera.shouldResume(
            speedMps = CAM_RESUME_SPEED_MPS - 0.01, nowMs = now, lastGestureMs = longAgo))
    }

    /** The boundary, stated: exactly the threshold resumes, because the test is `>=`. */
    @Test
    fun exactlyTheSpeedThresholdResumes() {
        assertTrue(FollowCamera.shouldResume(
            speedMps = CAM_RESUME_SPEED_MPS, nowMs = now, lastGestureMs = longAgo))
    }

    @Test
    fun neverResumesInsideTheQuietWindow() {
        assertFalse(FollowCamera.shouldResume(
            speedMps = 25.0, nowMs = now, lastGestureMs = now - CAM_RESUME_QUIET_MS + 1))
    }

    /** The other boundary, stated: exactly the quiet window does *not* resume,
     *  because the test is `>`. It takes one more millisecond. */
    @Test
    fun exactlyTheQuietWindowDoesNotResume() {
        assertFalse(FollowCamera.shouldResume(
            speedMps = 25.0, nowMs = now, lastGestureMs = now - CAM_RESUME_QUIET_MS))
        assertTrue(FollowCamera.shouldResume(
            speedMps = 25.0, nowMs = now, lastGestureMs = now - CAM_RESUME_QUIET_MS - 1))
    }

    /** Both bounds, not either. */
    @Test
    fun needsBothTheSpeedAndTheQuietPeriod() {
        assertFalse(FollowCamera.shouldResume(speedMps = 1.0, nowMs = now, lastGestureMs = longAgo))
        assertFalse(FollowCamera.shouldResume(speedMps = 25.0, nowMs = now, lastGestureMs = now))
    }

    /** A pending convoy offer blocks the resume even when both thresholds are
     *  met - the two functions together, which is how the call site applies
     *  them: guard first, then the per-fix test. */
    @Test
    fun aPendingSpinOfferBlocksResumeEvenAtSpeedAndQuiet() {
        assertTrue(FollowCamera.shouldResume(speedMps = 25.0, nowMs = now, lastGestureMs = longAgo))
        assertFalse(FollowCamera.shouldWatch(
            camSuspended = true, spinning = false, hasCandidates = false, hasSpinOffer = true))
    }
}

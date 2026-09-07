package com.jellemax.detour.map

import com.jellemax.detour.map.CameraAuthority.Action
import com.jellemax.detour.map.CameraAuthority.ParkReason
import com.jellemax.detour.map.CameraAuthority.State
import com.jellemax.detour.map.CameraAuthority.reduce
import com.jellemax.detour.map.CameraAuthority.shouldLevelNorthUp
import com.jellemax.detour.ui.CAM_RESUME_QUIET_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [CameraAuthority.reduce] - the follow/park/resume machine that
 * `MapScreen.kt` used to spread across ten write sites and three `remember`s.
 *
 * Stage 4 of the MapScreen refactor wired it: those ten sites are now ten
 * `reduce` dispatches against one `CameraAuthority.State`, so these tests are
 * the camera's authority rules rather than a proposal for them. They were
 * written before the wiring to pin the behaviour the ten sites had *then*,
 * which is what let the wiring be checked for accidents - including the
 * `lastGestureMs` asymmetry at the bottom of this file, which survived on
 * purpose.
 *
 * No Android APIs involved, so no emulator/Robolectric needed.
 */
class CameraAuthorityTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun startsFollowingAndUnparked() {
        val state = State()
        assertTrue(state.followMe)
        assertFalse(state.camSuspended)
        assertTrue(state.following)
    }

    @Test
    fun aGestureParksAndStampsTheQuietWindow() {
        val parked = reduce(State(), Action.Gesture(atMs = t0))
        assertTrue(parked.camSuspended)
        assertEquals(t0, parked.lastGestureMs)
        assertFalse(parked.following)
    }

    /** A park suspends following; it does not switch it off. That distinction is
     *  what lets the drive-off resume put the camera back without anyone
     *  pressing the follow button. */
    @Test
    fun aParkKeepsTheFollowIntentAndTheResumeRestoresIt() {
        val parked = reduce(State(), Action.Gesture(atMs = t0))
        assertTrue(parked.followMe)
        val resumed = reduce(parked, Action.DriveOffResumed)
        assertTrue(resumed.following)
        assertEquals(t0, resumed.lastGestureMs) // the resume does not restamp
    }

    /** The finger coming up re-stamps the quiet window, so it is measured from
     *  the end of the pan rather than its start. */
    @Test
    fun aGestureEndRestampsAParkedCamera() {
        val parked = reduce(State(), Action.Gesture(atMs = t0))
        val released = reduce(parked, Action.GestureEnd(atMs = t0 + 400))
        assertEquals(t0 + 400, released.lastGestureMs)
        assertTrue(released.camSuspended)
    }

    /** A tap that never left the slop circle was a pin drop or a marker tap, not
     *  a pan: an unparked camera is left completely alone. */
    @Test
    fun aGestureEndOnAnUnparkedCameraChangesNothing() {
        val state = State(lastGestureMs = t0)
        assertEquals(state, reduce(state, Action.GestureEnd(atMs = t0 + 5_000)))
    }

    /** Four call sites - a spin candidate, a convoy commit, a saved-place chip
     *  and a search result - frame a destination, and all four park exactly as a
     *  pan does. One action, so they cannot drift apart.
     *
     *  This used to assert whole-state equality. It now names the two fields it
     *  was ever about, because [State.parkedBy] deliberately differs: both are
     *  parks and neither is levelled to north (see [shouldLevelNorthUp]), but a
     *  framing and a pan are not the same event and #261 will want to tell them
     *  apart. The drift this test guards - a framing quietly getting a different
     *  park or a different quiet window from a pan - is still caught. */
    @Test
    fun framingADestinationParksExactlyLikeAGesture() {
        val panned = reduce(State(), Action.Gesture(atMs = t0))
        val framed = reduce(State(), Action.DestinationFramed(atMs = t0))
        assertEquals(panned.camSuspended, framed.camSuspended)
        assertEquals(panned.lastGestureMs, framed.lastGestureMs)
        assertEquals(panned.followMe, framed.followMe)
    }

    // --- Why the camera stopped, and what that licenses -------------------
    // #260: a pinch or a pan used to snap the map back to north, because the
    // camera loop saw only "not active" and could not tell a park from the
    // rider switching follow off. These pin the distinction.

    @Test
    fun aGestureParkRecordsThatAGestureCausedIt() {
        assertEquals(ParkReason.Gesture, reduce(State(), Action.Gesture(atMs = t0)).parkedBy)
    }

    @Test
    fun aFramingAndASpinRecordTheirOwnParkReasons() {
        assertEquals(
            ParkReason.DestinationFramed,
            reduce(State(), Action.DestinationFramed(atMs = t0)).parkedBy,
        )
        assertEquals(ParkReason.Spin, reduce(State(), Action.SpinStarted).parkedBy)
    }

    @Test
    fun aFollowingCameraIsNotParkedByAnything() {
        assertNull(State().parkedBy)
    }

    /** Every route back to following clears the reason, or the next park would
     *  be judged against a stale one. */
    @Test
    fun everyResumeClearsTheParkReason() {
        val parked = reduce(State(), Action.Gesture(atMs = t0))
        assertNull(reduce(parked, Action.DriveOffResumed).parkedBy)
        assertNull(reduce(parked, Action.NavigationStarted).parkedBy)
        assertNull(reduce(parked, Action.FollowToggled).parkedBy)
    }

    /** The follow button going off is not a park - it is the rider saying they
     *  are done being followed, which is the one case that *does* level. */
    @Test
    fun turningFollowOffIsNotAPark() {
        val off = reduce(State(), Action.FollowToggled)
        assertNull(off.parkedBy)
        assertFalse(off.cameraActive(navigating = false))
    }

    /** #260, at the unit level: the bug was that this returned true. */
    @Test
    fun aGestureParkedCameraIsNotLevelledToNorth() {
        val parked = reduce(State(), Action.Gesture(atMs = t0))
        assertFalse(shouldLevelNorthUp(parked, navigating = false))
    }

    @Test
    fun aFramedOrSpinParkedCameraIsNotLevelledEither() {
        assertFalse(
            shouldLevelNorthUp(reduce(State(), Action.DestinationFramed(atMs = t0)), false),
        )
        assertFalse(shouldLevelNorthUp(reduce(State(), Action.SpinStarted), false))
    }

    @Test
    fun switchingFollowOffLevelsBackToNorth() {
        assertTrue(shouldLevelNorthUp(reduce(State(), Action.FollowToggled), navigating = false))
    }

    /** Nothing to level while the loop is still aiming the camera - it writes
     *  the bearing every frame, so a level would be overwritten immediately. */
    @Test
    fun aCameraStillAimingItselfIsNotLevelled() {
        assertFalse(shouldLevelNorthUp(State(), navigating = false))
        assertFalse(shouldLevelNorthUp(State(), navigating = true))
        val notFollowingButNavigating = reduce(State(), Action.FollowToggled)
        assertFalse(shouldLevelNorthUp(notFollowingButNavigating, navigating = true))
    }

    /** The explicit control offered in place of the automatic levelling. It is
     *  worth offering only when the bearing can actually stay where it is put:
     *  a following camera rewrites it on the next frame. */
    @Test
    fun northUpIsOfferedOnlyWhenTheCameraIsNotAimingItself() {
        assertFalse(State().northUpAvailable(navigating = false))
        assertTrue(reduce(State(), Action.Gesture(atMs = t0)).northUpAvailable(navigating = false))
        // A park stops the loop even while navigating, so the bearing can be
        // held and the control still means something.
        assertTrue(reduce(State(), Action.Gesture(atMs = t0)).northUpAvailable(navigating = true))
        // Navigating and unparked: the route is aiming the camera.
        assertFalse(reduce(State(), Action.FollowToggled).northUpAvailable(navigating = true))
    }

    @Test
    fun startingNavigationClearsAPark() {
        val parked = reduce(State(), Action.Gesture(atMs = t0))
        val navigating = reduce(parked, Action.NavigationStarted)
        assertFalse(navigating.camSuspended)
        assertTrue(navigating.following)
    }

    @Test
    fun theFollowButtonTurnsFollowingOffWhenItIsOn() {
        val off = reduce(State(), Action.FollowToggled)
        assertFalse(off.followMe)
        assertFalse(off.following)
    }

    /** Pressing follow while parked does both jobs in one tap: intent back on,
     *  park cleared. Without the second half the button would appear to do
     *  nothing. */
    @Test
    fun theFollowButtonClearsAParkAsWellAsSettingTheIntent() {
        val parked = reduce(State(), Action.Gesture(atMs = t0))
        val following = reduce(parked, Action.FollowToggled)
        assertTrue(following.followMe)
        assertFalse(following.camSuspended)
        assertTrue(following.following)
    }

    /** Navigation drives the camera whether or not you are following; a park
     *  still stops it. */
    @Test
    fun navigationKeepsTheCameraActiveWithoutTheFollowIntent() {
        val notFollowing = reduce(State(), Action.FollowToggled)
        assertTrue(notFollowing.cameraActive(navigating = true))
        assertFalse(notFollowing.cameraActive(navigating = false))
        assertFalse(reduce(State(), Action.Gesture(atMs = t0)).cameraActive(navigating = true))
    }

    /**
     * **The asymmetry, kept rather than closed.** `spin()`
     * (`MapScreen.kt:1221`) parks without stamping the quiet window, while all
     * six other parks stamp both. The consequence is measurable: a spin-parked
     * camera is already eligible to resume on the next fix above the speed
     * threshold, where a pan-parked one has eight seconds of grace. That takes
     * effect as soon as the candidates are dismissed (`:1341`, `:1547`), which
     * is what unblocks `FollowCamera.shouldWatch`.
     *
     * Stage 4 wired this reducer and left the asymmetry standing, so it is a
     * decision now and not an open question. Both halves of it are true and
     * both belong on the record:
     *
     * - **Why it is defensible.** A spin result is framed for you to *read* -
     *   three candidates the app put on screen, not something you asked to see
     *   - and the camera must not be snatched back while you are reading them.
     *   `shouldWatch` already holds the park for exactly as long as the
     *   candidates are up, however long a passenger at 120 km/h takes over
     *   them, which is protection no timer could give. Once they are dismissed
     *   the reading is over, and stamping would have left the camera parked for
     *   another eight seconds after the user said they were done.
     * - **Why it is still an inconsistency.** The same argument applies word for
     *   word to a pan. Somebody who drags the map at speed is also looking at
     *   something deliberately, yet they get eight seconds measured from the
     *   finger lifting where the spin gets zero measured from the dismissal.
     *   Two parks, two graces, one reason. Someone may well want to close that,
     *   in either direction.
     *
     * Two earlier refactor proposals quietly unified the two. Unifying them is
     * a behaviour change, it belongs to whoever decides it and not to whoever
     * happens to be editing nearby - this test exists so that they have to
     * delete an assertion on purpose.
     */
    @Test
    fun theSpinParkKeepsItsImmediateResumeWhereAPanWaitsOutTheQuietWindow() {
        val before = State(lastGestureMs = t0 - CAM_RESUME_QUIET_MS - 1)
        val spinParked = reduce(before, Action.SpinStarted)
        val panParked = reduce(before, Action.Gesture(atMs = t0))

        assertTrue(spinParked.camSuspended)
        assertTrue(panParked.camSuspended)
        assertEquals(before.lastGestureMs, spinParked.lastGestureMs) // not stamped
        assertEquals(t0, panParked.lastGestureMs)                    // stamped

        assertTrue(FollowCamera.shouldResume(10.0, nowMs = t0, lastGestureMs = spinParked.lastGestureMs))
        assertFalse(FollowCamera.shouldResume(10.0, nowMs = t0, lastGestureMs = panParked.lastGestureMs))
    }
}

package com.jellemax.detour.map

/**
 * The camera's follow/park/resume machine as a pure reducer.
 *
 * **`MapScreen.kt` owns its camera through this.** It was written and tested
 * unwired first, so stage 4 of the refactor could choose between adopting it and
 * the Compose state-holder alternative against real code rather than two
 * proposals; it was adopted, because the holder route would have had to
 * reimplement six `rememberSaveable` values that survive a rotation the manifest
 * does not handle, and because `car/` can consume a reducer and cannot consume a
 * holder. The ten write sites that were three `remember`s now dispatch actions
 * here, and `CameraAuthorityTest` pins the behaviour they had before the move.
 *
 * The actions are named after the call sites they came from so the mapping can
 * be checked by grep rather than by memory - the table is in the stage-2 plan.
 */
internal object CameraAuthority {

    data class State(
        /** The resting intent: follow me around the map. Only the follow button
         *  turns this off. */
        val followMe: Boolean = true,
        /** A park. Does not switch following off - it suspends it until you are
         *  moving again. */
        val camSuspended: Boolean = false,
        /** When the last gesture, or gesture-equivalent park, happened: the start
         *  of the quiet window [FollowCamera.shouldResume] measures. */
        val lastGestureMs: Long = 0L,
        /** Why the camera stopped following, or null while it is following. */
        val parkedBy: ParkReason? = null,
    ) {
        /** What the follow button reflects. */
        val following: Boolean get() = followMe && !camSuspended

        /** Whether the frame loop should be aiming the camera at all. Navigation
         *  drives it regardless of [followMe]; a park still stops it. */
        fun cameraActive(navigating: Boolean): Boolean = (followMe || navigating) && !camSuspended

        /**
         * Whether to offer the rider the explicit "face north" control.
         *
         * The inverse of [cameraActive], and that is the whole rule: while the
         * frame loop is aiming the camera it rewrites the bearing every frame,
         * so levelling would be undone before the finger left the glass. Once
         * the camera is idle - parked, or not followed - a bearing stays where
         * it is put, which is what makes the control worth showing.
         */
        fun northUpAvailable(navigating: Boolean): Boolean = !cameraActive(navigating)
    }

    /**
     * Why the camera stopped following.
     *
     * Recorded rather than inferred, because [State.camSuspended] alone cannot
     * distinguish the cases and #260 was exactly that confusion: the camera
     * loop saw only `!cameraActive` and levelled the map to north on a pinch,
     * discarding a rotation the rider had set. Each park keeps its own reason so
     * the decision can be made per-case, and so #261's rotation modes have the
     * vocabulary to say which parks respect a rider's heading.
     */
    enum class ParkReason { Gesture, DestinationFramed, Spin }

    /**
     * Whether the map should be levelled back to north now that the frame loop
     * has stopped aiming the camera.
     *
     * True for exactly one case: the rider switched following **off**. That is
     * someone saying they are done being followed, and returning the map to a
     * north-up reading of it is the courtesy this has always paid.
     *
     * False for every park. A pinch, a pan, a framed destination and a spin all
     * stop the loop, and in none of them did the rider ask for the map to be
     * re-oriented - a zoom is a request to change zoom. #260 is what levelling
     * them cost: the rotation went, on every gesture, with no way to get it
     * back. [State.northUpAvailable] is that way back, offered rather than
     * imposed.
     */
    fun shouldLevelNorthUp(state: State, navigating: Boolean): Boolean =
        !state.cameraActive(navigating) && state.parkedBy == null

    sealed interface Action {
        /** A drag past the touch slop, or a second finger down. */
        data class Gesture(val atMs: Long) : Action

        /** The finger coming up after a gesture, re-stamping the quiet window so
         *  it runs from the end of the pan. Leaves an unparked camera alone: a
         *  tap inside the slop circle was a pin drop or a marker tap, not a pan. */
        data class GestureEnd(val atMs: Long) : Action

        /** A destination picked and framed - a spin candidate, a convoy commit, a
         *  saved-place chip, a search result. Parks exactly as a gesture does,
         *  stamp included, so a pick made at speed is not re-centered before you
         *  have seen the route you just chose. */
        data class DestinationFramed(val atMs: Long) : Action

        /** A spin starting. Parks so the result can be framed, and does *not*
         *  stamp the quiet window - see [reduce]. */
        data object SpinStarted : Action

        /** The drive-off test passed. */
        data object DriveOffResumed : Action

        /** Navigation started; the route drives the camera from here. */
        data object NavigationStarted : Action

        /** The follow button. Following → stop following; not following → follow,
         *  and clear any park in the same tap. */
        data object FollowToggled : Action
    }

    /**
     * **The `lastGestureMs` asymmetry is encoded here, not fixed.**
     * [Action.SpinStarted] parks without stamping the quiet window while every
     * other park stamps both, which is what `spin()` does today. The
     * consequence is that a spin-parked camera may resume on the next fix above
     * the speed threshold once the candidates are dismissed, where a pan-parked
     * one gets its eight seconds. Two earlier proposals quietly unified the two;
     * unifying them is a behaviour change, it belongs to whoever wires this
     * reducer, and it is a `detour-staged-refactor` §4 rule that `camSuspended`
     * and `lastGestureMs` never change in the same commit.
     */
    fun reduce(state: State, action: Action): State = when (action) {
        is Action.Gesture -> state.copy(
            camSuspended = true,
            lastGestureMs = action.atMs,
            parkedBy = ParkReason.Gesture,
        )
        is Action.GestureEnd ->
            if (state.camSuspended) state.copy(lastGestureMs = action.atMs) else state
        is Action.DestinationFramed -> state.copy(
            camSuspended = true,
            lastGestureMs = action.atMs,
            parkedBy = ParkReason.DestinationFramed,
        )
        Action.SpinStarted -> state.copy(camSuspended = true, parkedBy = ParkReason.Spin)
        Action.DriveOffResumed -> state.copy(camSuspended = false, parkedBy = null)
        Action.NavigationStarted -> state.copy(camSuspended = false, parkedBy = null)
        // No `parkedBy` on the way off: turning following off is not a park, and
        // that is the distinction [shouldLevelNorthUp] reads. It stays null
        // because `following` already implies `!camSuspended`, and every park
        // sets a reason while every resume clears one - so a following camera
        // never carries a stale one.
        Action.FollowToggled ->
            if (state.following) state.copy(followMe = false)
            else state.copy(followMe = true, camSuspended = false, parkedBy = null)
    }
}

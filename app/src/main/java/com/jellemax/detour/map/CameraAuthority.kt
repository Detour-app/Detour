package com.jellemax.detour.map

/**
 * The camera's follow/park/resume machine as a pure reducer.
 *
 * **Nothing calls this.** It is written, tested and deliberately unwired: stage
 * 4 of the MapScreen refactor decides whether to adopt it, take the Compose
 * state-holder route instead, or discard it, and that decision is cheaper to
 * make against real code than against two proposals. `MapScreen.kt` still owns
 * `followMe`, `camSuspended` and `lastGestureMs` as three `remember`s with nine
 * `camSuspended` write sites between them; this is what those sites would
 * collapse into, and `CameraAuthorityTest` pins the behaviour they have today.
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
    ) {
        /** What the follow button reflects. */
        val following: Boolean get() = followMe && !camSuspended

        /** Whether the frame loop should be aiming the camera at all. Navigation
         *  drives it regardless of [followMe]; a park still stops it. */
        fun cameraActive(navigating: Boolean): Boolean = (followMe || navigating) && !camSuspended
    }

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
     * other park stamps both, which is what `MapScreen.kt:1118` does today. The
     * consequence is that a spin-parked camera may resume on the next fix above
     * the speed threshold once the candidates are dismissed, where a pan-parked
     * one gets its eight seconds. Two earlier proposals quietly unified the two;
     * unifying them is a behaviour change, it belongs to whoever wires this
     * reducer, and it is a `detour-staged-refactor` §4 rule that `camSuspended`
     * and `lastGestureMs` never change in the same commit.
     */
    fun reduce(state: State, action: Action): State = when (action) {
        is Action.Gesture -> state.copy(camSuspended = true, lastGestureMs = action.atMs)
        is Action.GestureEnd ->
            if (state.camSuspended) state.copy(lastGestureMs = action.atMs) else state
        is Action.DestinationFramed -> state.copy(camSuspended = true, lastGestureMs = action.atMs)
        Action.SpinStarted -> state.copy(camSuspended = true)
        Action.DriveOffResumed -> state.copy(camSuspended = false)
        Action.NavigationStarted -> state.copy(camSuspended = false)
        Action.FollowToggled ->
            if (state.following) state.copy(followMe = false)
            else state.copy(followMe = true, camSuspended = false)
    }
}

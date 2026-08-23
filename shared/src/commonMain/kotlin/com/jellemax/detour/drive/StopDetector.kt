package com.jellemax.detour.drive

/**
 * Mid-trip pause/resume detection for maxke24/Detour#61. The existing
 * `pendingStopAtMs`/`STATIONARY_END_MS` machinery in `TripTrackingService`
 * only detects a stop long enough to *end* the trip; this detects a stop
 * that resumes within the same trip (a fuel stop on a manually-tracked
 * drive). Clock-free: timestamps are parameters.
 */
object StopDetector {
    const val STOP_SPEED_FLOOR_MPS = 2.0   // matches onTripLocation's own moving floor
                                            // (note: that floor tests raw `speed`, not
                                            // effectiveSpeedMps, and is a `>` gate where
                                            // this is `<`, so exactly 2.0 classifies
                                            // oppositely in the two places — harmless)
    const val MIN_STOP_DWELL_MS = 20_000L  // provisional — filters a traffic light

    data class State(
        val candidateSince: Long? = null,
        val stopCount: Int = 0,
        val idleMs: Long = 0,
        /** True once this trip has recorded at least one above-floor fix. A
         *  manually-started trip begins parked (`beginTrip` resets to a fresh
         *  [State]), and the pre-departure dwell before the rider pulls away
         *  is not a stop — there is nothing to have paused yet. Without this
         *  guard the very first fix (parked, speed 0) opens a candidate and
         *  the first fix on pulling away resolves it as a real stop. */
        val hasMoved: Boolean = false,
    )

    fun onFix(state: State, speedMps: Double, fixMs: Long): State {
        if (speedMps < STOP_SPEED_FLOOR_MPS) {
            if (!state.hasMoved) return state // parked before the trip has moved at all
            return state.copy(candidateSince = state.candidateSince ?: fixMs)
        }
        val moved = if (state.hasMoved) state else state.copy(hasMoved = true)
        val since = moved.candidateSince ?: return moved
        val dwell = fixMs - since
        return if (dwell >= MIN_STOP_DWELL_MS) {
            moved.copy(candidateSince = null, stopCount = moved.stopCount + 1, idleMs = moved.idleMs + dwell)
        } else {
            moved.copy(candidateSince = null)
        }
    }
}

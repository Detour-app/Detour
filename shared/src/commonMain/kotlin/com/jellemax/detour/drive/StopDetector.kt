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
    const val MIN_STOP_DWELL_MS = 20_000L  // provisional — filters a traffic light

    data class State(
        val candidateSince: Long? = null,
        val stopCount: Int = 0,
        val idleMs: Long = 0,
    )

    fun onFix(state: State, speedMps: Double, fixMs: Long): State {
        if (speedMps < STOP_SPEED_FLOOR_MPS) {
            return state.copy(candidateSince = state.candidateSince ?: fixMs)
        }
        val since = state.candidateSince ?: return state
        val dwell = fixMs - since
        return if (dwell >= MIN_STOP_DWELL_MS) {
            state.copy(candidateSince = null, stopCount = state.stopCount + 1, idleMs = state.idleMs + dwell)
        } else {
            state.copy(candidateSince = null)
        }
    }
}

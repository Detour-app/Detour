package com.jellemax.detour.drive

import kotlin.test.Test
import kotlin.test.assertEquals

/** Characterises [StopDetector] — a mid-trip pause/resume the existing
 *  auto-stop machinery in TripTrackingService never computes, since that
 *  only detects a stop long enough to *end* the trip. */
class StopDetectorTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun briefDwellUnderTheMinimumIsNotCountedAsAStop() {
        var state = StopDetector.State()
        state = StopDetector.onFix(state, 0.0, t0) // stopped
        // Resumes after 10s, under MIN_STOP_DWELL_MS (20s) — a traffic light.
        state = StopDetector.onFix(state, 10.0, t0 + 10_000)
        assertEquals(0, state.stopCount)
        assertEquals(0L, state.idleMs)
    }

    @Test
    fun dwellPastTheMinimumCountsAsAStopAndAccumulatesIdleTime() {
        var state = StopDetector.State()
        state = StopDetector.onFix(state, 0.0, t0)
        // Resumes after 45s, past MIN_STOP_DWELL_MS (20s) — a real stop.
        state = StopDetector.onFix(state, 10.0, t0 + 45_000)
        assertEquals(1, state.stopCount)
        assertEquals(45_000L, state.idleMs)
    }

    @Test
    fun aStopThatNeverResumesWithinTheTripIsNotCounted() {
        // The trip ends while still stopped (engine off) — endTrip's own logic
        // owns that boundary, not this detector. Documented limitation: a stop
        // is only counted once motion resumes within the same trip.
        var state = StopDetector.State()
        state = StopDetector.onFix(state, 0.0, t0)
        state = StopDetector.onFix(state, 0.0, t0 + 60_000)
        assertEquals(0, state.stopCount)
        assertEquals(0L, state.idleMs)
    }

    @Test
    fun multipleStopsAccumulateIndependently() {
        var state = StopDetector.State()
        state = StopDetector.onFix(state, 0.0, t0)
        state = StopDetector.onFix(state, 10.0, t0 + 30_000) // stop 1: 30s
        state = StopDetector.onFix(state, 0.0, t0 + 40_000)
        state = StopDetector.onFix(state, 10.0, t0 + 100_000) // stop 2: 60s
        assertEquals(2, state.stopCount)
        assertEquals(90_000L, state.idleMs)
    }
}

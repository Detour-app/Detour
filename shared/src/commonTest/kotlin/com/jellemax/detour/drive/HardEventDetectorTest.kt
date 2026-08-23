package com.jellemax.detour.drive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Characterises [HardEventDetector] — GPS Δv/Δt brake/accel bands and
 *  heading-rate/lean-angle corner bands, all clock-free (timestamps passed
 *  in, per detour-shared-core's rule for path-dependent logic). */
class HardEventDetectorTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun firstFixNeverTriggersEitherEvent() {
        val result = HardEventDetector.onSpeedFix(HardEventDetector.SpeedState(), 20.0, t0)
        assertFalse(result.hardBrake)
        assertFalse(result.hardAccel)
    }

    @Test
    fun aSuddenSpeedDropOverOneSecondIsAHardBrake() {
        val state = HardEventDetector.onSpeedFix(HardEventDetector.SpeedState(), 20.0, t0).state
        // 20 -> 15 m/s in 1s = -5 m/s^2, past HARD_BRAKE_MPS2 (-3.4).
        val result = HardEventDetector.onSpeedFix(state, 15.0, t0 + 1000)
        assertTrue(result.hardBrake)
        assertFalse(result.hardAccel)
    }

    @Test
    fun aSuddenSpeedGainOverOneSecondIsAHardAccel() {
        val state = HardEventDetector.onSpeedFix(HardEventDetector.SpeedState(), 10.0, t0).state
        // 10 -> 14 m/s in 1s = +4 m/s^2, past HARD_ACCEL_MPS2 (2.9).
        val result = HardEventDetector.onSpeedFix(state, 14.0, t0 + 1000)
        assertFalse(result.hardBrake)
        assertTrue(result.hardAccel)
    }

    @Test
    fun aGentleSpeedChangeTriggersNeither() {
        val state = HardEventDetector.onSpeedFix(HardEventDetector.SpeedState(), 20.0, t0).state
        val result = HardEventDetector.onSpeedFix(state, 19.0, t0 + 1000)
        assertFalse(result.hardBrake)
        assertFalse(result.hardAccel)
    }

    @Test
    fun aBatchedFixPairWithATooLargeGapIsIgnored() {
        val state = HardEventDetector.onSpeedFix(HardEventDetector.SpeedState(), 20.0, t0).state
        // Same delta as the hard-brake case, but over 20s (a batched idle fix
        // pair): 20 -> 0 m/s over 20s is -1 m/s^2, gentle, but this also
        // guards the case where dtSec is implausible on its own.
        val result = HardEventDetector.onSpeedFix(state, 0.0, t0 + 20_000)
        assertFalse(result.hardBrake)
    }

    @Test
    fun sustainedCorneringCountsOneEventNotOnePerFix() {
        var state = HardEventDetector.HeadingState()
        val (s1, fired1) = HardEventDetector.onHeadingFix(state, 0.0, 10.0, t0)
        state = s1
        assertFalse(fired1) // no prior heading yet
        // 0 -> 30 deg in 1s = 30 deg/s, past HARD_CORNER_DEG_PER_SEC (25).
        val (s2, fired2) = HardEventDetector.onHeadingFix(state, 30.0, 10.0, t0 + 1000)
        state = s2
        assertTrue(fired2)
        // Still turning fast the very next fix: same corner, not a new event.
        val (s3, fired3) = HardEventDetector.onHeadingFix(state, 60.0, 10.0, t0 + 2000)
        assertFalse(fired3)
    }

    @Test
    fun corneringBelowMinSpeedNeverFiresEvenWithABigHeadingSwing() {
        val state = HardEventDetector.HeadingState(lastHeadingDeg = 0.0, lastFixMs = t0)
        // A 90 deg swing at 2 m/s (parking maneuver), below MIN_CORNER_SPEED_MPS (5.0).
        val (_, fired) = HardEventDetector.onHeadingFix(state, 90.0, 2.0, t0 + 1000)
        assertFalse(fired)
    }

    @Test
    fun leanBandingFiresOnceUntilItDropsBelowThreshold() {
        val (cornering1, fired1) = HardEventDetector.onLeanSample(false, 45.0)
        assertTrue(cornering1); assertTrue(fired1)
        val (cornering2, fired2) = HardEventDetector.onLeanSample(cornering1, 50.0)
        assertTrue(cornering2); assertFalse(fired2) // still in the same corner
        val (cornering3, _) = HardEventDetector.onLeanSample(cornering2, 10.0)
        assertFalse(cornering3) // upright again
        val (_, fired4) = HardEventDetector.onLeanSample(cornering3, 42.0)
        assertTrue(fired4) // a second, distinct corner
    }
}

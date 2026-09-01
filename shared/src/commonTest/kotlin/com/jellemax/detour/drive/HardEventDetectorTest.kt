package com.jellemax.detour.drive

import kotlin.test.Test
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
    fun aFixPairFasterThanTheDtFloorIsIgnoredEvenThoughTheRateWouldFire() {
        val state = HardEventDetector.onSpeedFix(HardEventDetector.SpeedState(), 20.0, t0).state
        // 20 -> 0 m/s over 0.1s is -200 m/s^2 — would fire many times over on rate
        // alone, but 0.1s is under MIN_DT_SEC (0.2), so the pair is rejected.
        val result = HardEventDetector.onSpeedFix(state, 0.0, t0 + 100)
        assertFalse(result.hardBrake)
    }

    @Test
    fun aBatchedFixPairSlowerThanTheDtCeilingIsIgnoredEvenThoughTheRateWouldFire() {
        val state = HardEventDetector.onSpeedFix(HardEventDetector.SpeedState(), 60.0, t0).state
        // 60 -> 0 m/s over 16s is -3.75 m/s^2, past HARD_BRAKE_MPS2 (-3.4) on rate
        // alone, but 16s is over MAX_DT_SEC (15) — a batched idle fix pair, not a
        // real brake.
        val result = HardEventDetector.onSpeedFix(state, 0.0, t0 + 16_000)
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
    fun anUnmeasurableFixMidCornerDoesNotResetTheLatchToDoubleCount() {
        // Speed dips below MIN_CORNER_SPEED_MPS for one fix in the middle of a
        // sustained corner (e.g. flapping around the gate), then recovers. The
        // dip must NOT clear corneringNow, or the recovery re-fires as a "new"
        // corner that is really the same one.
        var state = HardEventDetector.HeadingState()
        val (s1, _) = HardEventDetector.onHeadingFix(state, 0.0, 10.0, t0)
        state = s1
        val (s2, fired2) = HardEventDetector.onHeadingFix(state, 30.0, 10.0, t0 + 1000) // fires
        state = s2
        assertTrue(fired2)
        // Slow fix: below MIN_CORNER_SPEED_MPS, unmeasurable — must not clear the latch.
        val (s3, fired3) = HardEventDetector.onHeadingFix(state, 45.0, 2.0, t0 + 1500)
        state = s3
        assertFalse(fired3)
        // Back above speed, still turning fast: same corner, must not re-fire.
        val (_, fired4) = HardEventDetector.onHeadingFix(state, 75.0, 10.0, t0 + 2000)
        assertFalse(fired4)
    }

    @Test
    fun headingWraparoundIsTheShortWayRoundNotTheLongWay() {
        // 359 -> 5 deg is a 6 deg swing the short way, not 354 the long way.
        // Over 0.1s that is 60 deg/s either way, so use 1s: 6 deg/s (below
        // threshold) versus 354 deg/s (grossly above) — the two readings this
        // bug would conflate.
        val state = HardEventDetector.HeadingState(lastHeadingDeg = 359.0, lastFixMs = t0)
        val (_, fired) = HardEventDetector.onHeadingFix(state, 5.0, 10.0, t0 + 1000)
        assertFalse(fired) // 6 deg/s, well under HARD_CORNER_DEG_PER_SEC (25)
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

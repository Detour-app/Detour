package com.jellemax.detour.drive

import kotlin.math.abs

/**
 * GPS-speed-delta brake/accel detection and heading-rate/lean-angle corner
 * detection for maxke24/Detour#61. Orientation-independent by design — the
 * car IMU isn't trusted (phone slides in a cradle, see `TravelMode.kt`'s
 * `tracksGForce` KDoc), so brake/accel comes from consecutive GPS speeds
 * rather than the accelerometer.
 *
 * All thresholds are provisional defaults (#61's own open question — no
 * recorded-trip data exists yet to calibrate against).
 *
 * Clock-free: every function takes its timestamps as parameters, so it is
 * testable without a fake clock and portable to `commonTest`'s JVM/Native
 * targets.
 */
object HardEventDetector {
    const val HARD_BRAKE_MPS2 = -3.4 // ~0.35g, provisional
    const val HARD_ACCEL_MPS2 = 2.9  // ~0.30g, provisional
    const val HARD_CORNER_DEG_PER_SEC = 25.0 // car heading-rate, provisional
    const val HARD_CORNER_LEAN_DEG = 40.0    // moto, provisional
    const val MIN_CORNER_SPEED_MPS = 5.0     // provisional — below this a heading
                                              // swing is a parking maneuver, not a
                                              // corner
    private const val MIN_DT_SEC = 0.2
    private const val MAX_DT_SEC = 15.0 // a batched/stale fix pair, not a real delta

    data class SpeedState(val lastSpeedMps: Double? = null, val lastFixMs: Long = 0L)
    data class SpeedResult(val state: SpeedState, val hardBrake: Boolean, val hardAccel: Boolean)

    /** GPS Δv/Δt between consecutive fixes. */
    fun onSpeedFix(state: SpeedState, speedMps: Double, fixMs: Long): SpeedResult {
        val prevSpeed = state.lastSpeedMps
        val next = SpeedState(speedMps, fixMs)
        if (prevSpeed == null) return SpeedResult(next, false, false)
        val dtSec = (fixMs - state.lastFixMs) / 1000.0
        if (dtSec < MIN_DT_SEC || dtSec > MAX_DT_SEC) return SpeedResult(next, false, false)
        val accelMps2 = (speedMps - prevSpeed) / dtSec
        return SpeedResult(next, accelMps2 <= HARD_BRAKE_MPS2, accelMps2 >= HARD_ACCEL_MPS2)
    }

    data class HeadingState(
        val lastHeadingDeg: Double? = null,
        val lastFixMs: Long = 0L,
        val corneringNow: Boolean = false,
    )

    /** Heading-rate corner detection (car). [corneringNow] gives hysteresis so a
     *  sustained turn counts as one corner, not one event per fix inside it. An
     *  unmeasurable fix (too slow, no prior heading, or a dt outside the guard
     *  band) must NOT clear the latch — only update [HeadingState.lastHeadingDeg]/
     *  [HeadingState.lastFixMs] and leave [HeadingState.corneringNow] as it was,
     *  otherwise a corner that dips through the guard mid-turn (e.g. speed
     *  flapping around [MIN_CORNER_SPEED_MPS]) re-fires as a second event. */
    fun onHeadingFix(
        state: HeadingState,
        headingDeg: Double,
        speedMps: Double,
        fixMs: Long,
    ): Pair<HeadingState, Boolean> {
        val prevHeading = state.lastHeadingDeg
        if (speedMps < MIN_CORNER_SPEED_MPS || prevHeading == null) {
            return state.copy(lastHeadingDeg = headingDeg, lastFixMs = fixMs) to false
        }
        val dtSec = (fixMs - state.lastFixMs) / 1000.0
        if (dtSec < MIN_DT_SEC || dtSec > MAX_DT_SEC) {
            return state.copy(lastHeadingDeg = headingDeg, lastFixMs = fixMs) to false
        }
        var diff = abs(headingDeg - prevHeading) % 360.0
        if (diff > 180.0) diff = 360.0 - diff
        val above = (diff / dtSec) >= HARD_CORNER_DEG_PER_SEC
        val newEvent = above && !state.corneringNow
        return HeadingState(headingDeg, fixMs, above) to newEvent
    }

    /** Moto: bands the existing per-sample lean stream
     *  (`TripTrackingService.recordLean`'s `deg`). Same hysteresis shape as
     *  [onHeadingFix]; the caller threads [corneringNow] itself since the lean
     *  pipeline already holds its own per-trip mutable state. */
    fun onLeanSample(corneringNow: Boolean, leanDeg: Double): Pair<Boolean, Boolean> {
        val above = abs(leanDeg) >= HARD_CORNER_LEAN_DEG
        val newEvent = above && !corneringNow
        return above to newEvent
    }
}

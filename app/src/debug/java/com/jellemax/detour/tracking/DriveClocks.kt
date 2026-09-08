package com.jellemax.detour.tracking

/**
 * The debug build's half of `DriveClocks`: the drive's clock is one
 * [ScaledDriveClock] the replay rig can re-pace, through `DebugReplayReceiver`.
 *
 * The release variant compiles `app/src/release/.../DriveClocks.kt` instead,
 * whose `current` is [SystemDriveClock] and has no `setScale` to reach. Split by
 * source set rather than by a runtime flag, the same way [ReplayFixGate] is —
 * see that class for why the app draws this line where it does.
 *
 * **At rest this is not a different clock.** A [ScaledDriveClock] at scale 1
 * returns `System.currentTimeMillis()`, the provider's own fix timestamp and
 * `SystemClock.elapsedRealtime()` — the three readings [SystemDriveClock]
 * returns, by the same arithmetic. So a debug build with no replay running keeps
 * exactly the behaviour it had, and the only observable difference is that
 * something can move it.
 *
 * A `val`, so the instance never swaps. That is load-bearing rather than tidy:
 * a `withFrameNanos` loop in `MapCamera` captures the clock as a plain value at
 * composition, and `.claude/skills/detour-compose-state-hazards` §2 is about
 * exactly that capture going stale. It cannot here, because re-pacing changes
 * the rate inside this object rather than replacing it.
 *
 * The declared type is narrower than the release twin's on purpose: main-source
 * code only ever uses it as a [DriveClock], and the extra width is what lets
 * `DebugReplayReceiver` — which exists only in this variant — call
 * [ScaledDriveClock.setScale] without a cast.
 */
object DriveClocks {
    val current: ScaledDriveClock = ScaledDriveClock()
}

package com.jellemax.detour.tracking

/**
 * The release build's half of `DriveClocks`: the drive's clock is real time, and
 * there is no way to move it.
 *
 * The debug variant compiles `app/src/debug/.../DriveClocks.kt` instead, whose
 * [current] is a [ScaledDriveClock] the replay rig can re-pace. The split is by
 * source set rather than by a `BuildConfig.DEBUG` branch, the same way
 * [ReplayFixGate] does it: a shipped app does not contain a compressed clock it
 * declines to use at runtime, it contains no route to one at all.
 *
 * `app/build.gradle.kts` points `githubRelease` at `src/release/java`, so that
 * variant takes this half too.
 *
 * A `val`, deliberately, and the debug twin's is a `val` as well: consumers are
 * handed this once — at service construction, into `LocalDriveClock`, into a car
 * screen's field — and an instance that could be swapped underneath them would
 * make every one of those captures stale. Re-pacing a replay moves the rate
 * *inside* the debug instance instead.
 */
object DriveClocks {
    val current: DriveClock = SystemDriveClock
}

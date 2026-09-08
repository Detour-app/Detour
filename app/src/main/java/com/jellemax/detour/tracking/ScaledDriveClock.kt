package com.jellemax.detour.tracking

import android.os.SystemClock
import android.util.Log

/**
 * The drive's clock, running faster than the wall clock, for a compressed replay.
 *
 * `tools/mocklocation` compresses the wall clock: at 5x it emits the route's
 * fixes five times as fast while still reporting the speed the recorded drive
 * was actually done at, because point spacing divided by the *nominal* interval
 * is that speed. Position and speed therefore stay honest, and everything the
 * app times for itself does not: a 24-minute route arrives in 5 minutes, so an
 * unscaled trip records a fifth of the duration and five times the average
 * speed, and every dwell window trips five times early.
 *
 * Scaling the app's own clock by the same factor puts those back in agreement.
 * [nowMs] advances five times per wall millisecond, so the trip records the
 * 24 minutes it would have taken, `distance / duration` matches the reported
 * speed, and an auto-stop grace period is the grace period it claims to be.
 *
 * **The instance is stable; only [setScale] moves.** Every consumer is handed
 * this object once — at service construction, at `CompositionLocalProvider`, in
 * a car screen's field — and a mid-replay re-pace changes the rate inside it
 * rather than swapping it for another. That is what lets a `withFrameNanos`
 * loop capture the clock as a plain value without going stale, which
 * `.claude/skills/detour-compose-state-hazards` §2 would otherwise forbid.
 *
 * **A release build cannot get here, and not by a runtime check.** The only
 * thing that installs this is the debug twin of [DriveClocks]; the release twin
 * has no `install` at all and holds [SystemDriveClock] as a `val`. This class
 * stays in `main` only so `ScaledClockTest` can reach its arithmetic from the
 * shared unit-test source set — nothing in a release build references it, and
 * R8 drops it.
 *
 * The multiplier arrives out of band, through `DebugReplayReceiver` in the debug
 * source set, because it cannot travel on the fix: `MockService` stamped it into
 * `Location.extras` and Play Services' fused provider delivered every one of
 * those fixes with `extras=null` (measured on an Android 15 emulator).
 */
class ScaledDriveClock : DriveClock {

    @Volatile
    private var clock: ScaledClock = ScaledClock.real()

    override fun nowMs(): Long = clock.at(System.currentTimeMillis())

    /** The factor in force, 1 when nothing is replaying. */
    fun scale(): Int = clock.scale

    /**
     * Adopts [scale], anchored so the reading does not jump.
     *
     * The early return is what keeps the anchor from creeping: the rig re-sends
     * the factor it already set (`replay-speed.sh` mid-run, and `start-replay.sh`
     * ahead of a run that is already paced), so this is routinely called with the
     * value it holds.
     */
    fun setScale(scale: Int) {
        val wanted = scale.coerceIn(1, ScaledClock.MAX_SCALE)
        if (wanted == clock.scale) return
        clock = clock.rescaled(System.currentTimeMillis(), wanted)
        Log.i("DetourReplay", "drive clock now ${wanted}x")
    }

    override fun fixTimeMs(providerTimeMs: Long): Long =
        if (clock.scale == 1) providerTimeMs else nowMs()

    override fun predictionNowMs(fixElapsedMs: Long): Long {
        val wallNow = SystemClock.elapsedRealtime()
        val factor = clock.scale
        if (factor == 1) return wallNow
        return fixElapsedMs + (wallNow - fixElapsedMs) * factor
    }

    /** Back to real time. For the end of a replay, and for tests. */
    fun reset() {
        clock = ScaledClock.real()
    }
}

/**
 * The arithmetic of [ScaledDriveClock], with no clock in it: a reading is a pure
 * function of the wall time handed in, so the continuity that matters — a
 * rescale mid-drive must not move the reading — is checkable with literals.
 */
internal class ScaledClock private constructor(
    private val anchorWallMs: Long,
    private val anchorReadingMs: Long,
    val scale: Int,
) {

    /** The reading at wall time [wallMs]. */
    fun at(wallMs: Long): Long = anchorReadingMs + (wallMs - anchorWallMs) * scale

    /**
     * The same clock from [wallMs] onwards, running at [scale].
     *
     * Anchored on this clock's own reading at that instant, so the reading is
     * continuous across the change: only the rate ahead of it differs. Without
     * that, adopting a multiplier part-way through a trip would jump the
     * recorded duration by however long the app had been running.
     */
    fun rescaled(wallMs: Long, scale: Int): ScaledClock =
        ScaledClock(wallMs, at(wallMs), scale)

    companion object {
        /**
         * The ceiling on the multiplier.
         *
         * A stop against a typo, not a claim about where replay stops being
         * useful — that is measured per rig, and the ramp in
         * `detour-gps-replay` is how. It mirrors `MockService.MAX_SPEEDUP`, and
         * the two must agree: a run paced at a factor the app clamped away
         * scales the drive and the clock differently.
         *
         * What degrades as the factor rises, and why the scripts warn past 10x:
         * everything deliberately left on the platform clock (see [DriveClock])
         * is asked to tolerate the factor, and the Overpass prefetch issues a
         * whole drive's requests that much faster into a mirror that rate-limits
         * by IP.
         */
        const val MAX_SCALE = 50

        /** Wall time, unscaled: `at(t) == t` for every `t`. */
        fun real(): ScaledClock = ScaledClock(0L, 0L, 1)
    }
}

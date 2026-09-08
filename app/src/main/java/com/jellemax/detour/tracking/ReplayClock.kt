package com.jellemax.detour.tracking

import android.util.Log
import com.jellemax.detour.BuildConfig

/**
 * The clock everything that measures *the drive* reads, so a replay can be run
 * faster than real time without the drive itself appearing to speed up.
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
 * `nowMs()` advances five times per wall millisecond, so the trip records the
 * 24 minutes it would have taken, `distance / duration` matches the reported
 * speed, and an auto-stop grace period is the grace period it claims to be.
 *
 * **What deliberately does not read this clock.** Only quantities that describe
 * the replayed drive belong here. Anything measuring the real device, the real
 * network or a real OS event keeps the platform clock, because compressing it
 * would be a lie in the other direction:
 *
 * - Fix staleness (`Fix.elapsedRealtimeMs` and its comparisons) — a compressed
 *   replay's fixes genuinely are fresher, and every use of an age is a gate that
 *   fresher only ever satisfies.
 * - Overpass and municipality throttles — these bound requests per wall second,
 *   and scaling them would multiply the real request rate by the multiplier.
 * - Geofence wake grace, BLE board telemetry age, the sensor publish throttle —
 *   real events on a real clock.
 *
 * The multiplier arrives out of band, through
 * `DebugReplayClockReceiver` in the debug source set, because it cannot travel
 * on the fix: `MockService` stamped it into `Location.extras` and Play
 * Services' fused provider delivered every one of those fixes with
 * `extras=null` (measured on an Android 15 emulator). The app reads fused, so
 * anything the harness attaches to a `Location` beyond the standard fields is
 * lost before it arrives.
 *
 * The multiplier is only ever written in a debug build ([setScale] refuses
 * otherwise), and a release install therefore has exactly the behaviour it had
 * before this existed: [scale] is 1, and `nowMs()` is
 * `System.currentTimeMillis()` plus an addition of zero.
 */
object ReplayClock {

    @Volatile
    private var clock: ScaledClock = ScaledClock.real()

    /** `System.currentTimeMillis()` at 1x, and the scaled reading above it. */
    fun nowMs(): Long = clock.at(System.currentTimeMillis())

    /** The factor in force, 1 when nothing is replaying. */
    fun scale(): Int = clock.scale

    /**
     * Adopts [scale], anchored so the reading does not jump.
     *
     * Called from the fix-ingest path, which sees the multiplier on every
     * replayed fix, so this runs repeatedly with the value it already holds —
     * hence the early return, which is what keeps the anchor from creeping.
     * A no-op outside a debug build: nothing in a release install can put this
     * clock anywhere but real time.
     */
    fun setScale(scale: Int) {
        if (!BuildConfig.DEBUG) return
        val wanted = scale.coerceIn(1, ScaledClock.MAX_SCALE)
        if (wanted == clock.scale) return
        clock = clock.rescaled(System.currentTimeMillis(), wanted)
        Log.i("DetourReplay", "replay clock now ${wanted}x")
    }

    /** Back to real time. For the end of a replay, and for tests. */
    fun reset() {
        clock = ScaledClock.real()
    }
}

/**
 * The arithmetic of [ReplayClock], with no clock in it: a reading is a pure
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
         * Not a performance limit — the app keeps up well past this — but the
         * point where a replay stops resembling a drive. Everything left on the
         * platform clock by design (see [ReplayClock]) is being asked to
         * tolerate a factor of this much: at 20x a 1 Hz route is a 20 Hz fix
         * stream, and the Overpass prefetch is issuing a real drive's requests
         * twenty times faster into a public mirror that rate-limits by IP.
         */
        const val MAX_SCALE = 20

        /** Wall time, unscaled: `at(t) == t` for every `t`. */
        fun real(): ScaledClock = ScaledClock(0L, 0L, 1)
    }
}

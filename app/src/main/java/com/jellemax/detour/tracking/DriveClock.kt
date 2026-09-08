package com.jellemax.detour.tracking

import android.os.SystemClock

/**
 * The clock everything that measures *the drive* reads, so a replay can be run
 * faster than real time without the drive itself appearing to speed up.
 *
 * A port, not a singleton, and that is the whole point of it: `CONTRIBUTING.md`
 * states the rule this replaced — "the core is handed things, it never reaches
 * for them" — and `shared/` already obeyed it, which is why teaching the app to
 * run on a compressed clock needed no changes there at all.
 * `SectionAverageTracker.onFix(nowMs = …)` takes time as a parameter and says
 * why in its own KDoc: a machine that is path-dependent over timestamps and
 * reads the clock itself has no reproducible test.
 *
 * Two implementations, which is what earns this an interface under
 * `docs/guidelines/multiplatform.md` §5.2: [SystemDriveClock] is what a shipped
 * app runs on, [ScaledDriveClock] is the compressed clock a replay installs,
 * and a test fake is the third. [DriveClocks] is the one place either is chosen.
 *
 * ## What deliberately does not read this clock
 *
 * Only quantities that describe the replayed drive belong here. Anything
 * measuring the real device, the real network or a real OS event keeps the
 * platform clock, because compressing it would be a lie in the other direction:
 *
 * - Fix staleness (`Fix.elapsedRealtimeMs` and its comparisons) — a compressed
 *   replay's fixes genuinely are fresher, and every use of an age is a gate that
 *   fresher only ever satisfies.
 * - Overpass and municipality throttles — these bound requests per wall second,
 *   and scaling them would multiply the real request rate by the multiplier.
 * - Geofence wake grace, BLE board telemetry age, the sensor publish throttle —
 *   real events on a real clock.
 *
 * One consequence to expect rather than debug: the readings are absolute
 * timestamps, so a trip recorded at 5x ends in what is still the future by the
 * wall clock — a 560-second drive replayed in 112 seconds stamps an end time
 * about seven minutes ahead of real time. A debug install's history is a test
 * rig, and a duration that matches the drive is worth more there than an end
 * time that matches the wall.
 */
interface DriveClock {

    /** The drive's now. `System.currentTimeMillis()` at 1x. */
    fun nowMs(): Long

    /**
     * The timestamp to record for a fix the provider stamped [providerTimeMs].
     *
     * Unscaled, this is the provider's own number and nothing changes: when the
     * position was measured beats when we got round to processing it, and a
     * real drive should keep it. Under compression it cannot be kept — the
     * stored trace would carry the replay's 112 seconds while the trip it
     * belongs to carries the drive's 560, and every tool that derives a speed
     * or a stop length from those timestamps (`profile-trace.py`, and the stop
     * detection in `gpx2route.py`'s heuristics) would read the factor back as
     * five times the speed.
     */
    fun fixTimeMs(providerTimeMs: Long): Long

    /**
     * The `nowElapsedMs` to hand `MapMotion.predict`, given the fix's own
     * `elapsedRealtime` stamp.
     *
     * Prediction advances a position by `speed × (now - fixStamp + lead)`, and the
     * speed on a replayed fix is the *drive's* speed — spacing over the nominal
     * interval, unchanged by the factor. The age therefore has to be in drive
     * time too, or the two disagree: at 5x, fixes arrive every 200 ms of wall
     * time and describe 1000 ms of driving, so a wall-clock age advances the
     * marker a fifth of the way to the next fix and the fix itself then snaps it
     * the rest. That is the position marker stuttering at high factors (#305).
     *
     * Deliberately *not* the same decision as fix staleness, which stays on the
     * platform clock (see above): "how old is this fix" and "how far has the
     * vehicle gone since it" are different questions about the same number, and
     * only the second one scales.
     */
    fun predictionNowMs(fixElapsedMs: Long): Long

    /**
     * A monotonic drive-time reading, for measuring an interval of the drive.
     *
     * For the frame loops. An exponential ease — `1 - exp(-dt / TAU)` — converges
     * with a time constant in whatever unit `dt` is, and every `TAU` in
     * `MapCamera` was tuned against fixes arriving once a *drive* second. Left on
     * wall time, the camera converges at a fixed rate toward a target moving N
     * times faster, trails it, and then `MapMotion.shouldSnap` fires: at 20x that
     * reads as the marker lagging back and then jumping.
     *
     * The same argument #305 accepted for [predictionNowMs], one step further on:
     * "how far has the vehicle gone since the fix" and "how far should the camera
     * have caught up this frame" are both questions about the drive.
     *
     * **A reading to difference, not a wall delta to multiply.** Multiplying a
     * frame's wall delta by the factor in force *now* mis-attributes any frame
     * that straddles a rescale, because the factor changed part-way through it.
     * The scaled clock is anchored so its readings stay continuous across a
     * rescale (that is what `ScaledClock.rescaled` is for), so the difference of
     * two readings is exact where the multiplication is an approximation.
     *
     * **On `elapsedRealtime`, not `currentTimeMillis`.** An interval must not go
     * backwards, and the wall clock steps under NTP. Same reason
     * [predictionNowMs] is built on it.
     *
     * Exactly `SystemClock.elapsedRealtime()` at 1x, so nothing about a real
     * drive changes.
     */
    fun driveElapsedMs(): Long
}

/**
 * Real time. What a shipped app runs on, and what every surface holds until a
 * debug build's replay rig installs something else.
 *
 * An `object` rather than a class: it has no state, and one instance for the
 * process is the same decision `docs/guidelines/architecture.md` records for
 * the other stateless singletons.
 */
object SystemDriveClock : DriveClock {
    override fun nowMs(): Long = System.currentTimeMillis()
    override fun fixTimeMs(providerTimeMs: Long): Long = providerTimeMs
    override fun predictionNowMs(fixElapsedMs: Long): Long = SystemClock.elapsedRealtime()
    override fun driveElapsedMs(): Long = SystemClock.elapsedRealtime()
}

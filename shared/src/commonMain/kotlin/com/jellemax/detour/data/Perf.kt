package com.jellemax.detour.data

import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/**
 * Records how long a function took **and how much data it ran over**, so growth
 * shows up as a curve rather than as an argument about shape. #84.
 *
 * The core cannot record anything itself: `commonMain` has no logger, no
 * `println` and no `Dispatchers`. So this only produces [Sample]s and each
 * platform decides what to do with them — [sink] is pushed in from the platform
 * side rather than reached for, the same way location, audio and notifications
 * already are (`Platform.kt:11-14`, CONTRIBUTING.md:23-31). A settable function
 * property rather than an interface, because there is one implementation today
 * (`app/.../PerfSink.kt`) and CONTRIBUTING.md:40 is the bar for adding a fourth
 * interface to `commonMain`.
 *
 * The platform half of this already existed for cold start —
 * `app/.../ColdStartTiming.kt`, written for #54 — with no size covariate, no
 * series, and no way to reach it from the core. This is that seam with the
 * covariate the curve needs.
 *
 * With no sink installed the whole thing is a volatile read and a branch: the
 * [Sample] is never built and the covariate lambda is never run. That is what
 * makes it safe to ship in release — a debug-only seam never sees the history
 * growth actually shows up in — and safe on the GPS and frame paths.
 *
 * ### Reading a series
 *
 * Duration alone answers nothing. `59 trips -> 100 ms` then `68 trips -> 450 ms`
 * is a superlinear term worth fixing; `100 -> 105 -> 110 ms` over the same
 * growth is device noise. Only the size covariate separates the two, which is
 * why [end] takes sizes and not just a label.
 */
object Perf {

    /**
     * One measurement: what ran, how long it took, and the sizes it ran over.
     *
     * [sizes] is a list rather than a single scalar because some calls have more
     * than one growth term — `Coverage.compute` walks trace points *against*
     * municipalities, and a record assuming one number could not say which of
     * the two moved.
     */
    data class Sample(
        val label: String,
        val durationUs: Long,
        val sizes: List<Pair<String, Int>>,
    )

    /**
     * Where samples go. Null — the default, and iOS's state for now — means
     * nothing is recorded.
     *
     * `@Volatile` because it is installed on the main thread and read from the
     * GPS callback, the sync scope and the fog view's draw pass.
     */
    @Volatile
    var sink: ((Sample) -> Unit)? = null

    /** Whether anything is listening, for a caller deciding whether a covariate
     *  is worth computing outside an [end] lambda. */
    val enabled: Boolean get() = sink != null

    /**
     * Starts a measurement. Unconditional and unguarded: one monotonic clock
     * read is cheaper than the boxing a nullable mark would cost on
     * Kotlin/Native, and [end] is where the sink is checked.
     */
    fun start(): TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow()

    /**
     * Records the time since [from] under [label], with whatever sizes [sizes]
     * returns.
     *
     * Deliberately a separate call rather than a `timed { }` wrapper. Most of the
     * instrumented functions have early returns — a cache hit, an absent file, a
     * fresh record — and a non-local `return` out of an inlined block would skip
     * the recording silently. An explicit [end] per path also lets a cache hit be
     * recorded *as* a hit rather than vanishing.
     *
     * Microseconds, not milliseconds: two of the targets (`needsLookup` per GPS
     * fix, `FogView.onDraw` per frame) are sub-millisecond until they aren't,
     * and a series of zeroes would hide exactly the growth being watched for.
     *
     * `inline`, so [sizes] costs nothing when no sink is installed.
     */
    inline fun end(
        from: TimeSource.Monotonic.ValueTimeMark,
        label: String,
        sizes: () -> List<Pair<String, Int>>,
    ) {
        val s = sink ?: return
        s(Sample(label, from.elapsedNow().inWholeMicroseconds, sizes()))
    }

    /**
     * Records an interval this process did **not** time itself.
     *
     * [start]/[end] assume the measured work happens between two marks on our
     * own clock. Some of the intervals worth a series do not: the OS batches a
     * geofence transition and reports how stale the triggering fix was by the
     * time the callback ran (`GeofenceWakeReceiver`, #90/#140), and the
     * interesting part of that interval is over before this process is even
     * awake to mark it. Timing it here would measure the wake, not the delay.
     *
     * Same [Sample], same sink, so such an interval joins the ordinary series
     * and needs no second file — see `PerfSink`.
     */
    fun record(label: String, durationUs: Long, sizes: List<Pair<String, Int>>) {
        sink?.invoke(Sample(label, durationUs, sizes))
    }
}

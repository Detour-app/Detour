package com.jellemax.detour.data

import kotlin.math.abs

/**
 * A round trip sized by riding time instead of length — "half an hour out and
 * back" for a test ride, where the rider knows how long they have, not how far
 * that is.
 *
 * GraphHopper's `round_trip` only takes a distance, so time is reached in two
 * steps: ask for [guessMeters], then, if none of the loops that came back lands
 * within [TOLERANCE] of the target, scale the request by how far off their
 * reported travel time was ([rescaledMeters]) and roll once more. The router's
 * own time estimate is the reference throughout — it is what the rider sees on
 * the result and what the in-app ETA counts down from.
 *
 * Pure and in commonMain so the calibration has one implementation and its
 * tests, whichever surface ends up spinning a loop.
 */
object LoopDuration {

    const val MIN_MINUTES = 15f
    const val MAX_MINUTES = 240f
    const val DEFAULT_MINUTES = 30f
    const val STEP_MINUTES = 5f

    /** Opening guess at a loop's average speed. Only sets the first request;
     *  the second is scaled off what the router actually reported. [RouteFill]
     *  starts from the same guess when it has no base route to measure. */
    internal const val GUESS_KMH = 50.0
    internal const val GUESS_METERS_PER_MS = GUESS_KMH * 1000.0 / 3_600_000.0

    /** How far off the target a loop's time may be and still count as "the
     *  half hour" asked for. */
    const val TOLERANCE = 0.15

    /** Bounds on one rescale: a single wild estimate (a loop that hit a ferry,
     *  a server that shrank the request after an unroutable roll) must not
     *  swing the next request to a tenth or ten times the length. [RouteFill]'s
     *  rounds are bounded by the same pair. */
    internal const val MIN_SCALE = 0.4
    internal const val MAX_SCALE = 2.5

    fun targetMs(minutes: Float): Long = (minutes * 60_000.0).toLong()

    /** First loop length to ask the router for. */
    fun guessMeters(minutes: Float): Double = minutes / 60.0 * GUESS_KMH * 1000.0

    /**
     * The length to ask for next, given that asking for [requestedMeters]
     * produced [loops]. Scales by target over the median reported time — the
     * median so one outlier roll does not steer the retry. Null when no loop
     * carried a time, so there is nothing to calibrate against.
     */
    fun rescaledMeters(minutes: Float, requestedMeters: Double, loops: List<RouteResult>): Double? {
        val times = loops.mapNotNull { it.timeMs?.takeIf { t -> t > 0 } }.sorted()
        if (times.isEmpty()) return null
        val median = if (times.size % 2 == 1) times[times.size / 2].toDouble()
        else (times[times.size / 2 - 1] + times[times.size / 2]) / 2.0
        val scale = (targetMs(minutes) / median).coerceIn(MIN_SCALE, MAX_SCALE)
        return requestedMeters * scale
    }

    /** Whether [loop]'s reported time is within [TOLERANCE] of [minutes]. */
    fun fits(loop: RouteResult, minutes: Float): Boolean {
        val t = loop.timeMs ?: return false
        val target = targetMs(minutes)
        return abs(t - target) <= target * TOLERANCE
    }

    /**
     * Which of the [scored] loops (loop to curviness) to ride: the curviest of
     * those that fit the time, or — when none does — the one closest to it,
     * since a loop that is ten minutes long is not a half-hour ride however
     * twisty it is. Null only for an empty list.
     */
    fun pick(scored: List<Pair<RouteResult, Double>>, minutes: Float): Pair<RouteResult, Double>? {
        val fitting = scored.filter { fits(it.first, minutes) }
        if (fitting.isNotEmpty()) return fitting.maxBy { it.second }
        val target = targetMs(minutes)
        return scored.minByOrNull { (loop, _) ->
            loop.timeMs?.let { abs(it - target) } ?: Long.MAX_VALUE
        }
    }
}

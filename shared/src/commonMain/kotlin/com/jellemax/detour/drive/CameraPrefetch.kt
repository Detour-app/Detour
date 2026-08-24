package com.jellemax.detour.drive

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.data.SpeedCameras

/**
 * *When* to ask Overpass for the cameras and average-speed sections around you.
 * One answer carries both, so this is one cadence rather than two.
 *
 * **Cadence only** - the markers and the sections themselves stay on the
 * surface that draws them. What was duplicated between the phone and the head
 * unit was the *timing*, with different constants on each side; the holders were
 * never the problem, and pulling them in here would have made every marker
 * update a copy of a list this object has no use for.
 *
 * **Split like [SpeedLimitTracker], and for the same reason**: commonMain has no
 * coroutine dispatcher to hand a network call to, so the caller owns the I/O and
 * the ordering is
 *
 * ```
 * if (needsFetch(st, pos, now) && <platform in-flight guard>) {
 *     st = fetchStarted(st, now)                     // stamp before awaiting anything
 *     <platform coroutine> { st = fetched(st, SpeedCameras.near(pos), pos) }
 * }
 * ```
 *
 * The in-flight guard stays at the call site: both surfaces' is a `Job`, an
 * Android coroutine handle, which cannot cross into common code.
 *
 * **The backoff is the point of this file.** Both surfaces used to implement the
 * cadence inline — the phone with literals, the head unit with named constants —
 * and neither backed off. A failed fetch leaves [State.center] where it was, so
 * the distance trigger stays true forever and a flat throttle becomes a retry
 * timer: one refused request every 15 s, each one trying *every* mirror in
 * `RoadRoulette.rawQuery`, for the whole drive. maxke24/Detour#22 measured
 * roughly 143 requests out of a single 17 km replay, which is enough to get the
 * IP rate-limited — and a rate-limited IP silently takes the camera markers, the
 * sections *and* the ambient speed-limit sign down with it for hours. Doubling
 * the wait per consecutive failure turns that 17 km into single figures, and one
 * success puts the cadence straight back to normal.
 *
 * **No clock**, like every other machine here: [nowMs] is the caller's, so the
 * backoff schedule is testable without waiting for it.
 */
object CameraPrefetch {

    /** Refetch once you are within this much of the edge of the area you hold. */
    const val FETCH_MARGIN_M = 1000.0

    /** Minimum gap between fetch *attempts* while everything is working. */
    const val FETCH_THROTTLE_MS = 15_000L

    /** Ceiling on the backed-off gap. Long enough to stop being the reason an IP
     *  is refused, short enough that a tunnel or a dead mirror doesn't cost you
     *  the rest of the drive. */
    const val MAX_BACKOFF_MS = 5 * 60_000L

    data class State(
        val center: LatLon? = null,
        val lastFetchMs: Long = 0L,
        /** Consecutive failed attempts. Reset by any answer at all, including an
         *  empty one — an area with no cameras is a success, not a blip. */
        val failures: Int = 0,
    )

    /** How long to wait after [failures] consecutive failures. */
    fun retryDelayMs(failures: Int): Long = backoffDelayMs(FETCH_THROTTLE_MS, MAX_BACKOFF_MS, failures)

    /**
     * Whether this fix is far enough from [State.center] and late enough past
     * [State.lastFetchMs] to be worth a prefetch. **The fetch is the caller's**,
     * and so is any in-flight guard.
     *
     * A null [State.center] means no area held, not distance zero.
     */
    fun needsFetch(state: State, at: LatLon, nowMs: Long): Boolean {
        val fromCenter = state.center?.let { RoadRoulette.distanceMeters(it, at) }
            ?: Double.MAX_VALUE
        return fromCenter > SpeedCameras.PREFETCH_RADIUS_M - FETCH_MARGIN_M &&
            nowMs - state.lastFetchMs > retryDelayMs(state.failures)
    }

    /** Stamp the attempt. Called *before* the fetch, so a failure is throttled
     *  too. Nothing else changes. */
    fun fetchStarted(state: State, nowMs: Long): State = state.copy(lastFetchMs = nowMs)

    /**
     * Fold a completed attempt in. A null [result] is `SpeedCameras.near`'s
     * network error: leave [State.center] where it is, since we do not hold
     * [center], and lengthen the next wait. The caller keeps the markers it
     * already has - `near` returns null precisely so it can.
     */
    fun fetched(state: State, result: SpeedCameras.Result?, center: LatLon): State =
        if (result == null) {
            state.copy(failures = state.failures + 1)
        } else {
            state.copy(center = center, failures = 0)
        }
}

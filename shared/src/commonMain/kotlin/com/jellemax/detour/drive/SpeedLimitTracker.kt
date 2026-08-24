package com.jellemax.detour.drive

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette

/**
 * The ambient speed-limit sign while just driving: one Overpass fetch covers a
 * wide circle, then every fix snaps locally against that set, so the sign flips
 * the instant you cross onto a new road instead of lagging a throttled
 * round-trip behind you. The fetch refreshes only as you near the edge of what
 * you hold, throttled on failure too — and *backed off* on a run of failures, so
 * a refused mirror cannot turn the throttle into a retry timer for the rest of
 * the drive. [CameraPrefetch] shares the Overpass budget this one spends and
 * backs off the same way.
 *
 * **Split in five, because the fetch cannot come along.** commonMain has no
 * coroutine dispatcher to hand a network call to — that is a verified constraint
 * of this module, not a style choice — so the caller owns the I/O and the
 * ordering is:
 *
 * ```
 * if (needsWays(st, pos, now) && <platform in-flight guard>) {
 *     st = fetchStarted(st, now)          // stamp before awaiting anything
 *     <platform coroutine> { st = withWays(st, RoadRoulette.speedLimitWays(pos), pos) }
 *                                         // a null there is the failure, and backs off
 * }
 * st = onFix(st, pos, heading, speedMps)
 * ```
 *
 * The in-flight guard stays at the call site: both surfaces' is a `Job`, an
 * Android coroutine handle, which cannot cross into common code. The throttle is
 * shared; the guard is not.
 *
 * **This machine decides whether a posted limit *exists*, never whether a sign is
 * *shown*.** There is no `visible` field and nothing about a standstill: the
 * phone fades its HUD at rest, the head unit does not, and both are defensible.
 * A readout makes that call (register entry 18).
 */
object SpeedLimitTracker {

    /** Below this the heading is noise and you are probably parked, so the snap —
     *  which leans on heading to reject the cross street — is skipped. */
    const val MIN_MPS = 2.0

    /** Refetch once you are within this much of the edge of the area you hold. */
    const val FETCH_MARGIN_M = 500.0

    /** Minimum gap between fetch *attempts*. Stamped before the request, so a
     *  failing mirror is throttled like a succeeding one. */
    const val FETCH_THROTTLE_MS = 10_000L

    /** Ceiling on the backed-off gap. Deliberately far below
     *  [CameraPrefetch.MAX_BACKOFF_MS]: a camera you miss is one camera, but the
     *  sign is on screen the whole drive, so a minute is as long as this one may
     *  go quiet once the mirrors come back. */
    const val MAX_BACKOFF_MS = 60_000L

    /** Misses in a row before the sign is cleared. One gap is an untagged stretch;
     *  three is the limit really having ended. */
    const val MISSES_TO_CLEAR = 3

    data class State(
        val ways: List<RoadRoulette.SpeedLimitWay> = emptyList(),
        val waysCenter: LatLon? = null,
        val lastFetchMs: Long = 0L,
        val misses: Int = 0,
        val limitKmh: Double? = null,
        /** Consecutive failed fetches. Any answer at all resets it, including an
         *  empty one - an area with no tagged road is a success, not a blip. */
        val failures: Int = 0,
    )

    /** How long to wait after [failures] consecutive failures. */
    fun retryDelayMs(failures: Int): Long = backoffDelayMs(FETCH_THROTTLE_MS, MAX_BACKOFF_MS, failures)

    /**
     * Whether this fix is far enough from [State.waysCenter] and late enough past
     * [State.lastFetchMs] to be worth a prefetch. **The fetch is the caller's**,
     * and so is any in-flight guard.
     *
     * A null [State.waysCenter] means no area held, not distance zero.
     */
    fun needsWays(state: State, at: LatLon, nowMs: Long): Boolean {
        val fromCenter = state.waysCenter?.let { RoadRoulette.distanceMeters(it, at) }
            ?: Double.MAX_VALUE
        return fromCenter > RoadRoulette.SPEED_PREFETCH_RADIUS_M - FETCH_MARGIN_M &&
            nowMs - state.lastFetchMs > retryDelayMs(state.failures)
    }

    /** Stamp the attempt. Called *before* the fetch, so a failure is throttled
     *  too. Nothing else changes. */
    fun fetchStarted(state: State, nowMs: Long): State = state.copy(lastFetchMs = nowMs)

    /**
     * Fold a completed prefetch in.
     *
     * A **null** [ways] is [RoadRoulette.speedLimitWays]'s failure: keep what we
     * hold rather than flickering the sign off, leave [State.waysCenter] where it
     * was since we do not hold [center], and lengthen the next wait. An **empty**
     * [ways] is the area really having no tagged road - [State.ways] is left as a
     * no-op, exactly as an empty result always was, but [State.waysCenter] still
     * moves to [center], the same way [CameraPrefetch.fetched] moves its center
     * on an empty answer: otherwise the distance trigger in [needsWays] stays
     * true forever over a real untagged stretch, and a *valid* empty answer
     * re-queries Overpass every [FETCH_THROTTLE_MS] for as long as you're on it.
     *
     * The two used to be the same value, because `speedLimitWays` returned an
     * empty list for both. Telling them apart is what makes the backoff possible.
     */
    fun withWays(
        state: State,
        ways: List<RoadRoulette.SpeedLimitWay>?,
        center: LatLon,
    ): State = when {
        ways == null -> state.copy(failures = state.failures + 1)
        ways.isEmpty() -> state.copy(waysCenter = center, failures = 0)
        else -> state.copy(ways = ways, waysCenter = center, failures = 0)
    }

    /**
     * The per-fix snap and the three-miss clear hysteresis. Clock-free.
     *
     * Below [MIN_MPS] the fix is skipped and does **not** count as a miss, so a
     * long wait at a light cannot clear the sign. The call sites gate on the same
     * floor before they reach here, which is what also stops a prefetch while
     * stopped; this guard is what stops a miss being counted.
     *
     * [headingDeg] lets the snap reject the cross street and the frontage road,
     * which is most of why the sign used to show nonsense.
     */
    fun onFix(state: State, at: LatLon, headingDeg: Double?, speedMps: Double): State {
        if (speedMps < MIN_MPS) return state
        val snapped = RoadRoulette.snapSpeedLimitKmh(at, headingDeg, state.ways)
        if (snapped != null) return state.copy(limitKmh = snapped, misses = 0)
        val misses = state.misses + 1
        // A few misses in a row means the limit really ended (or the road
        // isn't tagged), not a one-fix gap — only then clear the sign.
        return if (misses >= MISSES_TO_CLEAR) {
            state.copy(misses = misses, limitKmh = null)
        } else {
            state.copy(misses = misses)
        }
    }

    /**
     * Crossing into or out of navigation. Clears the sign *and* the miss counter,
     * and keeps the held area — the geometry is still valid, only the derived
     * sign is stale, and re-clearing the throttle would let a navigation toggle
     * punch straight through it.
     *
     * The phone's producer doesn't run while navigating, so without this the
     * value would be the limit from wherever the route began and would survive
     * the whole session — and the trip after it. Stale in both directions: the
     * camera chime falls back to it while navigating, and the HUD switches back
     * to it on the way out. The head unit has done this since it shipped
     * (`car/SpinScreen.kt`'s `onStart`). The misses counter goes with it, or the
     * first miss after the switch would clear a sign that was already cleared.
     */
    fun reset(state: State): State = state.copy(misses = 0, limitKmh = null)
}

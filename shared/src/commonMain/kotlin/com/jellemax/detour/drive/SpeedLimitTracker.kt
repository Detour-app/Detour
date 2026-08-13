package com.jellemax.detour.drive

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette

/**
 * The ambient speed-limit sign while just driving: one Overpass fetch covers a
 * wide circle, then every fix snaps locally against that set, so the sign flips
 * the instant you cross onto a new road instead of lagging a throttled
 * round-trip behind you. The fetch refreshes only as you near the edge of what
 * you hold, throttled on failure too so a network blip doesn't hammer the
 * mirrors.
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

    /** Misses in a row before the sign is cleared. One gap is an untagged stretch;
     *  three is the limit really having ended. */
    const val MISSES_TO_CLEAR = 3

    data class State(
        val ways: List<RoadRoulette.SpeedLimitWay> = emptyList(),
        val waysCenter: LatLon? = null,
        val lastFetchMs: Long = 0L,
        val misses: Int = 0,
        val limitKmh: Double? = null,
    )

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
            nowMs - state.lastFetchMs > FETCH_THROTTLE_MS
    }

    /** Stamp the attempt. Called *before* the fetch, so a failure is throttled
     *  too. Nothing else changes. */
    fun fetchStarted(state: State, nowMs: Long): State = state.copy(lastFetchMs = nowMs)

    /** Fold a completed prefetch in. An empty [ways] is a network blip: keep what
     *  we hold rather than flickering the sign off, and leave [State.waysCenter]
     *  where it was, since we do not hold [center]. */
    fun withWays(
        state: State,
        ways: List<RoadRoulette.SpeedLimitWay>,
        center: LatLon,
    ): State =
        if (ways.isEmpty()) state else state.copy(ways = ways, waysCenter = center)

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

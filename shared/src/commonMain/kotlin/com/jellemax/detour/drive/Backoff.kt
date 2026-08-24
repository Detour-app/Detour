package com.jellemax.detour.drive

/** Doubling stops here so the shift below cannot overflow a Long, whatever a
 *  long enough offline stretch does to the failure count. */
private const val MAX_DOUBLINGS = 5

/**
 * How long to wait after [failures] consecutive failures: [throttleMs] doubled
 * once per failure, capped at [ceilingMs]. Shared by [CameraPrefetch] and
 * [SpeedLimitTracker] — the shape of the backoff is what they had in common,
 * not the floor or ceiling, which stay theirs to tune independently.
 */
internal fun backoffDelayMs(throttleMs: Long, ceilingMs: Long, failures: Int): Long {
    if (failures <= 0) return throttleMs
    val doubled = throttleMs shl minOf(failures, MAX_DOUBLINGS)
    return minOf(doubled, ceilingMs)
}

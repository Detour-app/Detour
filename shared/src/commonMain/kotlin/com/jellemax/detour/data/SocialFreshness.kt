package com.jellemax.detour.data

/**
 * How long a social list stays good enough to show without asking the server
 * again (#82).
 *
 * Thirty seconds, and the number is chosen against navigation rather than
 * against how fast a friend list changes. What it has to cover is a rider
 * stepping Hub -> Circles -> Hub -> Circles, or tapping into a circle and
 * backing out; anything longer than that is a genuinely new visit and worth a
 * round trip. Membership changes arrive by push or on the next visit either
 * way, and every mutation reloads explicitly rather than waiting for this to
 * lapse.
 */
const val SOCIAL_TTL_MS = 30_000L

/**
 * Whether a list stamped at [loadedAtMs] should be fetched again.
 *
 * A pure decision so it can be tested without a clock or a server — the stores
 * call it with `nowMs()`, the same split [RiderTotals.freshness] uses.
 *
 * Never loaded is stale, so a first entry always fetches. A stamp in the
 * future is stale too: the stamp outlives a clock change, and `>` alone would
 * read a future date as the freshest thing there is and pin the list until the
 * clock caught up.
 */
internal fun isStale(loadedAtMs: Long?, nowMs: Long, ttlMs: Long = SOCIAL_TTL_MS): Boolean {
    if (loadedAtMs == null) return true
    return nowMs - loadedAtMs !in 0L..ttlMs
}

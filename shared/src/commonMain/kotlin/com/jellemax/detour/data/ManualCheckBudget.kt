package com.jellemax.detour.data

/**
 * A burst budget for rider-initiated update checks (#147).
 *
 * Immutable, and time arrives as a parameter rather than from a clock this type
 * reads — a bucket that reads the clock has no reproducible test, which is the
 * same reason `GeofenceEvaluator` and `RouteGpx.parseGpx` take their timestamps
 * as arguments.
 *
 * Sized against GitHub's unauthenticated REST ceiling of 60 requests an hour
 * per IP, which riders share because carrier NAT puts many of them behind one
 * address. One check costs exactly one api.github.com request — the manifest
 * comes from a `browser_download_url` on a different host — so a capacity of
 * three refilling one per five minutes settles at twelve an hour.
 */
data class ManualCheckBudget(
    val tokens: Int = CAPACITY,
    val refilledAtMs: Long = 0L,
) {

    sealed interface Spend {
        data class Granted(val budget: ManualCheckBudget) : Spend
        /** When the next token lands. */
        data class Denied(val retryAtMs: Long) : Spend
    }

    fun spend(nowMs: Long): Spend {
        val refilled = refill(nowMs)
        return if (refilled.tokens > 0) {
            Spend.Granted(refilled.copy(tokens = refilled.tokens - 1))
        } else {
            Spend.Denied(refilled.refilledAtMs + REFILL_MS)
        }
    }

    private fun refill(nowMs: Long): ManualCheckBudget {
        // At capacity the clock tracks now. Left where it was, a long idle
        // would still be on the books after the next spend and would refill
        // the token that spend just took.
        if (tokens >= CAPACITY) return copy(refilledAtMs = nowMs)
        val earned = (nowMs - refilledAtMs) / REFILL_MS
        if (earned <= 0) return this
        val filled = minOf(CAPACITY.toLong(), tokens + earned).toInt()
        // Advance by whole consumed intervals, never to nowMs: snapping
        // forward discards progress toward the next token on every spend, so a
        // rider checking every four minutes would never refill at all.
        val advanced = refilledAtMs + earned * REFILL_MS
        return ManualCheckBudget(filled, if (filled >= CAPACITY) nowMs else advanced)
    }

    companion object {
        const val CAPACITY = 3
        const val REFILL_MS = 5 * 60 * 1000L
    }
}

package com.jellemax.detour.data

/**
 * A burst budget for rider-initiated update checks (#147).
 *
 * Immutable, and time arrives as a parameter rather than from a clock this type
 * reads — a bucket that reads the clock has no reproducible test, which is the
 * same reason `GeofenceEvaluator` and `RouteGpx.parseGpx` take their timestamps
 * as arguments.
 *
 * Sized to stop one rider mashing the button, not to protect GitHub's
 * unauthenticated REST ceiling of 60 requests an hour per IP — that ceiling is
 * shared with everyone behind the same carrier-NAT address, so this budget's
 * twelve-an-hour settle point is per rider and does nothing for a shared IP
 * that five riders have already exhausted between them. That failure mode
 * surfaces as an ordinary failed check, which the UI already reports.
 */
data class ManualCheckBudget(
    val tokens: Int = CAPACITY,
    // Only correct paired with a full bucket: `ManualCheckBudget(tokens = 1)`
    // silently backdates that one token's clock to the epoch and refills to
    // full on the very next spend. `refill`'s `return ManualCheckBudget(filled,
    // ...)` is a direct constructor call, not a `copy` — but it pairs `filled`
    // with an anchor computed for that same `filled`, so the pairing stays
    // consistent even though it is not an invariant the type itself enforces.
    val refilledAtMs: Long = 0L,
) {
    init {
        require(tokens in 0..CAPACITY) { "tokens ($tokens) must be within 0..$CAPACITY" }
    }

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
        // A clock that moved backwards (an NTP correction after a bogus RTC read, a
        // rider changing the date) would otherwise leave refilledAtMs in the future
        // and freeze the bucket for the whole size of the jump. Re-anchor: the rider
        // gets no free token, but no lockout either.
        if (nowMs < refilledAtMs) return copy(refilledAtMs = nowMs)
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

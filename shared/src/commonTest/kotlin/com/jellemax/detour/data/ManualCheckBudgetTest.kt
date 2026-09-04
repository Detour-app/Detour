package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Characterises [ManualCheckBudget] — the burst budget behind the rider's
 *  "check for updates" button (#147). Time is an argument here and never a
 *  clock this type reads, so every case below is reproducible. */
class ManualCheckBudgetTest {

    private val minute = 60_000L

    private fun budget() = ManualCheckBudget()

    /** Spends one token and fails the test if it was refused, so the cases
     *  below read as the sequence of taps they describe. */
    private fun grant(b: ManualCheckBudget, atMs: Long): ManualCheckBudget {
        val spend = b.spend(atMs)
        assertIs<ManualCheckBudget.Spend.Granted>(spend)
        return spend.budget
    }

    @Test
    fun aFreshBudgetStartsFull() {
        assertEquals(ManualCheckBudget.CAPACITY, budget().tokens)
    }

    @Test
    fun tokensOutsideZeroToCapacityIsRejected() {
        assertFailsWith<IllegalArgumentException> { ManualCheckBudget(tokens = -1) }
        assertFailsWith<IllegalArgumentException> {
            ManualCheckBudget(tokens = ManualCheckBudget.CAPACITY + 1)
        }
    }

    @Test
    fun threeChecksInARowAreAllGranted() {
        var b = budget()
        repeat(ManualCheckBudget.CAPACITY) { b = grant(b, it * 1000L) }
        assertEquals(0, b.tokens)
    }

    @Test
    fun theFourthCheckInARowIsDenied() {
        var b = budget()
        repeat(3) { b = grant(b, it * 1000L) }
        assertIs<ManualCheckBudget.Spend.Denied>(b.spend(3_000L))
    }

    @Test
    fun aDeniedCheckReportsOneRefillIntervalAfterTheLastRefill() {
        var b = budget()
        repeat(ManualCheckBudget.CAPACITY) { b = grant(b, 0L) }
        val denied = b.spend(1_000L)
        assertIs<ManualCheckBudget.Spend.Denied>(denied)
        assertEquals(ManualCheckBudget.REFILL_MS, denied.retryAtMs)
    }

    @Test
    fun aTokenReturnsAfterTheRefillInterval() {
        var b = budget()
        repeat(ManualCheckBudget.CAPACITY) { b = grant(b, 0L) }
        b = grant(b, ManualCheckBudget.REFILL_MS)
        assertEquals(0, b.tokens)
    }

    @Test
    fun theDeniedBoundarySitsOneMillisecondBeforeAnyTokenReturns() {
        // An implementation that rounds the earned-token division up instead
        // of truncating it — e.g. (elapsed + REFILL_MS - 1) / REFILL_MS —
        // would hand back a token a whole interval early and still pass every
        // test above; this is the one millisecond that catches it.
        var b = budget()
        repeat(ManualCheckBudget.CAPACITY) { b = grant(b, 0L) }
        assertIs<ManualCheckBudget.Spend.Denied>(b.spend(ManualCheckBudget.REFILL_MS - 1))
    }

    @Test
    fun spendingFasterThanTheRefillIntervalStillAccumulatesPartialProgress() {
        // Three taps at t=0, one at t=8min. That fourth tap consumes the token
        // earned at t=5min and must leave the next one due at t=10min — not at
        // t=13min, which is what snapping refilledAtMs forward to now would do,
        // silently throwing away three minutes of progress on every spend.
        var b = budget()
        repeat(ManualCheckBudget.CAPACITY) { b = grant(b, 0L) }
        b = grant(b, 8 * minute)
        val denied = b.spend(9 * minute)
        assertIs<ManualCheckBudget.Spend.Denied>(denied)
        assertEquals(10 * minute, denied.retryAtMs)
    }

    @Test
    fun elevenMinutesIdleFromEmptyGrantsExactlyTwoTokens() {
        // Two whole five-minute intervals fit inside eleven minutes, with one
        // minute of partial progress left over toward a third; refilledAtMs
        // must land on that second whole interval (+10min) and not snap
        // forward to +11min and discard the trailing minute. This is the
        // earned > 1 branch that lands below CAPACITY, so the clamp above
        // never gets a chance to paper over a wrong answer here.
        var b = budget()
        repeat(ManualCheckBudget.CAPACITY) { b = grant(b, 0L) }
        b = grant(b, 11 * minute)
        assertEquals(10 * minute, b.refilledAtMs)
        b = grant(b, 11 * minute)
        assertIs<ManualCheckBudget.Spend.Denied>(b.spend(11 * minute))
    }

    @Test
    fun theBudgetNeverExceedsCapacityHoweverLongItIdles() {
        var b = budget()
        repeat(ManualCheckBudget.CAPACITY) { b = grant(b, 0L) }
        b = grant(b, 24 * 60 * minute)
        // CAPACITY, minus the one token this very spend just took.
        assertEquals(ManualCheckBudget.CAPACITY - 1, b.tokens)
    }

    @Test
    fun aSpendAfterALongIdleDoesNotHandOutAnInstantRefillOnTheNext() {
        // At capacity the refill clock has to track now. Left at its old value,
        // the hour idled below would still be on the books one minute later and
        // would hand back the very token this spend just took.
        val b = grant(budget(), 60 * minute)
        assertEquals(ManualCheckBudget.CAPACITY - 1, b.tokens)
        val next = grant(b, 61 * minute)
        assertEquals(ManualCheckBudget.CAPACITY - 2, next.tokens)
    }

    @Test
    fun aClockThatJumpsBackwardsAtCapacityStillGrants() {
        // A full bucket already re-anchors refilledAtMs to now on every spend
        // (see aSpendAfterALongIdleDoesNotHandOutAnInstantRefillOnTheNext), so
        // a clock moving backwards must not disturb that: the rider still gets
        // a token immediately, exactly as if the clock had not moved at all.
        assertIs<ManualCheckBudget.Spend.Granted>(budget().spend(-1_000L))
    }

    @Test
    fun aClockThatJumpsBackwardsAtZeroTokensDeniesWithABoundedRetry() {
        // A dead RTC boots at a bogus far-future time, three checks empty the
        // bucket, and NITZ/NTP then corrects the clock backwards by three
        // hours. Without re-anchoring on a backwards jump, refilledAtMs would
        // still read its old value and the reported retry would be three
        // hours away instead of one refill interval from now — the bucket
        // would look dead for the whole size of the correction.
        var b = budget()
        repeat(ManualCheckBudget.CAPACITY) { b = grant(b, 0L) }
        val nowMs = 0L - 3 * 60 * minute
        val denied = b.spend(nowMs)
        assertIs<ManualCheckBudget.Spend.Denied>(denied)
        assertTrue(denied.retryAtMs <= nowMs + ManualCheckBudget.REFILL_MS)
    }
}

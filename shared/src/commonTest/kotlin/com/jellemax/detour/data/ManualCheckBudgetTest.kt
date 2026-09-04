package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
    fun threeChecksInARowAreAllGranted() {
        var b = budget()
        repeat(3) { b = grant(b, it * 1000L) }
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
        repeat(3) { b = grant(b, 0L) }
        val denied = b.spend(1_000L)
        assertIs<ManualCheckBudget.Spend.Denied>(denied)
        assertEquals(ManualCheckBudget.REFILL_MS, denied.retryAtMs)
    }

    @Test
    fun aTokenReturnsAfterTheRefillInterval() {
        var b = budget()
        repeat(3) { b = grant(b, 0L) }
        b = grant(b, ManualCheckBudget.REFILL_MS)
        assertEquals(0, b.tokens)
    }

    @Test
    fun spendingFasterThanTheRefillIntervalStillAccumulatesPartialProgress() {
        // Three taps at t=0, one at t=8min. That fourth tap consumes the token
        // earned at t=5min and must leave the next one due at t=10min — not at
        // t=13min, which is what snapping refilledAtMs forward to now would do,
        // silently throwing away three minutes of progress on every spend.
        var b = budget()
        repeat(3) { b = grant(b, 0L) }
        b = grant(b, 8 * minute)
        val denied = b.spend(9 * minute)
        assertIs<ManualCheckBudget.Spend.Denied>(denied)
        assertEquals(10 * minute, denied.retryAtMs)
    }

    @Test
    fun theBudgetNeverExceedsCapacityHoweverLongItIdles() {
        var b = budget()
        repeat(3) { b = grant(b, 0L) }
        b = grant(b, 24 * 60 * minute)
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
}

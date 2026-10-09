package com.jellemax.detour.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Characterises [collectRolls], the composition rule [pickThreeCandidates]
 * applies to its three concurrent rolls, and the spin's time cap around them.
 * [pickCandidate] itself does real network I/O (Overpass, the routing server)
 * with no offline branch and no `MockEngine` in this module's dependencies, so
 * the cap is tested through the internal overload that takes the roll as an
 * argument, with a short `timeoutMs` standing in for [SPIN_TIMEOUT_MS].
 *
 * `runBlocking` rather than `runTest`, for the reason [SingleFlightTest] gives.
 */
class SpinPickerTest {

    private fun candidate(name: String) =
        RouteCandidate(destination = LatLon(0.0, 0.0), name = name, route = null, straightLineMeters = 0.0)

    @Test
    fun allThreeSucceedingReturnsAllThreeInOrder() {
        val rolls = listOf(
            Result.success(candidate("a")),
            Result.success(candidate("b")),
            Result.success(candidate("c")),
        )
        assertEquals(listOf("a", "b", "c"), collectRolls(rolls).map { it.name })
    }

    @Test
    fun oneFailureStillReturnsTheOtherTwo() {
        val rolls = listOf(
            Result.success(candidate("a")),
            Result.failure(IllegalStateException("no road here")),
            Result.success(candidate("c")),
        )
        assertEquals(listOf("a", "c"), collectRolls(rolls).map { it.name })
    }

    @Test
    fun allThreeFailingThrowsTheFirstRealFailure() {
        val first = IllegalStateException("first")
        val rolls = listOf(
            Result.failure<RouteCandidate>(first),
            Result.failure(IllegalStateException("second")),
            Result.failure(IllegalStateException("third")),
        )
        val thrown = assertFailsWith<IllegalStateException> { collectRolls(rolls) }
        assertSame(first, thrown)
    }

    @Test
    fun aCancelledRollPropagatesEvenWhenOthersSucceeded() {
        // The rule collectRolls exists to enforce: a cancellation is never a
        // failed roll, so it must win over two real successes rather than
        // being silently dropped like an ordinary failure would be.
        val rolls = listOf(
            Result.success(candidate("a")),
            Result.failure(CancellationException("spin cancelled")),
            Result.success(candidate("c")),
        )
        assertFailsWith<CancellationException> { collectRolls(rolls) }
    }

    @Test
    fun allThreeCancelledPropagatesCancellation() {
        val rolls = listOf(
            Result.failure<RouteCandidate>(CancellationException("a")),
            Result.failure(CancellationException("b")),
            Result.failure(CancellationException("c")),
        )
        assertFailsWith<CancellationException> { collectRolls(rolls) }
    }

    @Test
    fun oneStalledRollDoesNotCostTheTwoThatLanded() = runBlocking {
        // #507 review: the rolls used to be awaited all together, so one roll
        // stalling past the cap threw away two candidates already in hand and
        // told the rider the server was too slow.
        var calls = 0
        val picks = pickThreeCandidates(serverUsable = true, timeoutMs = 200) {
            val n = ++calls
            if (n == 2) awaitCancellation()
            candidate("roll $n")
        }
        assertEquals(setOf("roll 1", "roll 3"), picks.map { it.name }.toSet())
    }

    @Test
    fun aSpinWithNothingLandedByTheCapFailsWithTheSlowServerSentence() = runBlocking {
        val e = assertFailsWith<SpinFailure> {
            pickThreeCandidates(serverUsable = true, timeoutMs = 50) { awaitCancellation() }
        }
        assertEquals("The routing server is too slow or unreachable — try again", e.message)
    }

    @Test
    fun aSpinThatFinishesInsideTheCapReturnsEveryRoll() = runBlocking {
        var calls = 0
        val picks = pickThreeCandidates(serverUsable = true, timeoutMs = 5_000) { candidate("roll ${++calls}") }
        assertEquals(3, picks.size)
    }
}

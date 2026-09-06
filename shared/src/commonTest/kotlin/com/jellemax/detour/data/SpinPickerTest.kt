package com.jellemax.detour.data

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Characterises [collectRolls], the composition rule [pickThreeCandidates]
 * applies to its three concurrent rolls. [pickCandidate] itself does real
 * network I/O (Overpass, the routing server) with no offline branch and no
 * `MockEngine` in this module's dependencies, so the roll itself — and the
 * `withTimeout` wrapped around it — is not exercisable here. What is pulled
 * out and tested is the pure part: what a spin does with three already-
 * completed [Result]s.
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
}

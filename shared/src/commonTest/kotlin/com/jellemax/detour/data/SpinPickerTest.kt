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

    private val start = LatLon(52.0, 5.0)
    private val dest = LatLon(52.1, 5.2)
    private val routed = RouteCandidate(
        destination = dest,
        name = "Lake view",
        route = RouteResult(
            polyline = listOf(start, dest),
            waypoints = emptyList(),
            distanceMeters = 31_000.0,
            timeMs = 2_400_000L,
        ),
        straightLineMeters = 20_000.0,
    )

    @Test
    fun aKeptSpinIsAnOriginTaggedTwoStopRoute() {
        val saved = routed.toSavedSpin(start, TravelMode.MOTO, "Sunday loop", nowMs = 77L)
        assertEquals(RouteOrigin.SPIN, saved.origin)
        assertEquals(77L, saved.id)
        assertEquals("Sunday loop", saved.name)
        assertEquals(TravelMode.MOTO, saved.mode)
        assertEquals(listOf(RouteStop(start), RouteStop(dest, "Sunday loop")), saved.stops)
        assertEquals(routed.route!!.polyline, saved.polyline)
        assertEquals(31_000.0, saved.distanceMeters)
        assertEquals(2_400_000L, saved.timeMs)
    }

    @Test
    fun aBlankNameFallsBackToTheCandidatesOwn() {
        assertEquals("Lake view", routed.toSavedSpin(start, TravelMode.CAR, "  ", 1L).name)
        assertEquals("", routed.copy(name = null).toSavedSpin(start, TravelMode.CAR, "", 1L).name)
    }

    @Test
    fun aCandidateWithoutARouteKeepsEmptyGeometryForGoToRoute() {
        val saved = routed.copy(route = null).toSavedSpin(start, TravelMode.CAR, "x", 1L)
        assertEquals(emptyList(), saved.polyline)
        assertEquals(null, saved.distanceMeters)
        assertEquals(null, saved.timeMs)
    }

    @Test
    fun aKeptSpinSurvivesTheRoutesJsonRoundTrip() {
        val saved = routed.toSavedSpin(start, TravelMode.CAR, "x", 5L)
        assertEquals(saved, routeFromJson(saved.toJson()))
    }
}

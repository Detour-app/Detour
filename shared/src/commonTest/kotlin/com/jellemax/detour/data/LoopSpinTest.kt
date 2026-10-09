package com.jellemax.detour.data

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LoopSpin]'s choosing rules against a fake router, and the two decisions
 * inside a loop spin that do no I/O: which sentence a timeout gets, and which
 * roll's failure is reported.
 *
 * The message tests moved here from the Android app's `SpinRunTest` with
 * [LoopSpin], so iOS's spin is held to the same wording. The one that matters
 * most: a fallback timeout must not hide that the rider's own routing server
 * was the thing that failed, because that is the message that tells them where
 * to look.
 *
 * `runBlocking` rather than `runTest`, for the reason [SingleFlightTest] gives.
 */
class LoopSpinTest {

    @Test
    fun aServerFailureSurvivesTheFallbackTimingOutToo() {
        assertEquals(
            "Server route failed: the server hit a problem. Try again later. The fallback timed out too.",
            spinTimeoutMessage(
                serverError = "the server hit a problem. Try again later.",
                roundTrip = true,
                serverUsable = true,
            ),
        )
    }

    @Test
    fun aRoundTripWithNoServerConfiguredNamesThatRatherThanBlamingSpeed() {
        // The rider has not set a routing server at all, so "servers are slow"
        // would send them to look at the wrong thing entirely.
        val m = spinTimeoutMessage(serverError = null, roundTrip = true, serverUsable = false)
        assertEquals("No routing server configured — public servers timed out", m)
    }

    @Test
    fun everythingElseIsTheRetryableMessage() {
        assertEquals(
            "The routing server is too slow or unreachable — try again",
            spinTimeoutMessage(serverError = null, roundTrip = true, serverUsable = true),
        )
        // Point-to-point never consults the round-trip server, so an unusable
        // config is not the interesting fact about its timeout.
        assertEquals(
            "The routing server is too slow or unreachable — try again",
            spinTimeoutMessage(serverError = null, roundTrip = false, serverUsable = false),
        )
    }

    @Test
    fun theFirstRollsReasonIsTheOneReported() {
        // Independent requests against the same server almost always fail the
        // same way; three copies of one sentence helps nobody.
        val reason = loopFailureReason(
            listOf(HttpStatusException(503, ""), IOException("connect timed out"), null),
        )
        assertEquals("the server hit a problem. Try again later.", reason)
    }

    @Test
    fun aRollsReasonNeverCarriesTheExceptionsOwnText() {
        // #487: this lands in the rider's warning, and the raw text can be a
        // URL or a parser's internals.
        val raw = "Unable to resolve host nas.local: No address associated with hostname"
        val reason = loopFailureReason(listOf(IOException(raw)))
        assertFalse(reason.contains(raw), reason)
        // Nothing failed at all: the caller only asks when the list is empty of
        // successes, so this is the "we have no idea" wording.
        assertEquals("no route came back.", loopFailureReason(listOf(null, null)))
        assertEquals("no route came back.", loopFailureReason(emptyList()))
    }

    private val home = LatLon(50.85, 5.69)

    private fun request(minutes: Float?) = LoopRequest(
        from = home, lengthMeters = 100_000.0, minutes = minutes,
        headingDeg = null, preferences = RoutePreferences(), highwayRegex = ".*",
    )

    /** A router whose loops ride at [kmh], recording each length asked for. */
    private fun router(kmh: Double, asked: MutableList<Double>): suspend (Double) -> RouteResult = { meters ->
        asked.add(meters)
        RouteResult(
            polyline = listOf(home, home),
            waypoints = emptyList(),
            distanceMeters = meters,
            timeMs = (meters / (kmh * 1000.0 / 3_600_000.0)).toLong(),
        )
    }

    private val noFallback: suspend (Double) -> List<LatLon> = { error("fallback not expected") }

    @Test
    fun aTimedSpinKeepsAFirstRollThatFitsWithoutRollingAgain() = runBlocking {
        val asked = mutableListOf<Double>()
        // The router rides exactly the 50 km/h the first guess assumes.
        val result = LoopSpin.spin(request(30f), serverUsable = true, router(50.0, asked), noFallback)
        assertTrue(LoopDuration.fits(result.route, 30f), "got ${result.route.timeMs}")
        assertEquals(3, asked.size)
        assertNull(result.warning)
    }

    @Test
    fun aTimedSpinThatMissesReRollsOnceAtTheLengthTheReportedTimesSuggest() = runBlocking {
        val asked = mutableListOf<Double>()
        // At 25 km/h the first rolls come back an hour long; the re-roll asks
        // for half the length, which rides the half hour.
        val result = LoopSpin.spin(request(30f), serverUsable = true, router(25.0, asked), noFallback)
        assertEquals(6, asked.size)
        assertEquals(LoopDuration.guessMeters(30f) / 2, asked.last(), 1.0)
        assertTrue(LoopDuration.fits(result.route, 30f), "got ${result.route.timeMs}")
    }

    @Test
    fun aDistanceSpinAsksForTheSliderLengthAndNeverReRolls() = runBlocking {
        val asked = mutableListOf<Double>()
        LoopSpin.spin(request(null), serverUsable = true, router(25.0, asked), noFallback)
        assertEquals(listOf(100_000.0, 100_000.0, 100_000.0), asked)
    }

    @Test
    fun anUnusableServerGoesStraightToTheFallbackWithNoWarning() = runBlocking {
        val asked = mutableListOf<Double>()
        val waypoint = LatLon(50.9, 5.7)
        val result = LoopSpin.spin(
            request(null), serverUsable = false, router(50.0, asked), fallback = { listOf(waypoint) },
        )
        assertTrue(asked.isEmpty())
        assertEquals(listOf(home, waypoint, home), result.route.polyline)
        assertNull(result.warning)
    }

    @Test
    fun rollsThatAllFailFallBackAndSayWhatTheServerDid() = runBlocking {
        val result = LoopSpin.spin(
            request(30f), serverUsable = true,
            roll = { throw HttpStatusException(503, "") },
            fallback = { listOf(LatLon(50.9, 5.7)) },
        )
        assertEquals(
            "Server route failed: the server hit a problem. Try again later. " +
                "Showing an approximate loop instead.",
            result.warning,
        )
    }

    // #507: one cap over the whole spin. A short timeoutMs stands in for
    // SPIN_TIMEOUT_MS so the test does not wait 20 s of real time.

    private val hang: suspend (Double) -> RouteResult = { awaitCancellation() }
    private val hangingFallback: suspend (Double) -> List<LatLon> = { awaitCancellation() }
    private val noRoll: suspend (Double) -> RouteResult = { error("roll not expected") }

    @Test
    fun aSpinWhoseServerNeverAnswersFailsWithTheSlowServerSentence() = runBlocking {
        // #507: the server rolls themselves used up the time, so the fallback
        // never ran; the rider is told the server is the slow part, not shown
        // a raw TimeoutCancellationException.
        val e = assertFailsWith<SpinFailure> {
            LoopSpin.spin(request(null), serverUsable = true, hang, hangingFallback, timeoutMs = 50)
        }
        assertEquals("The routing server is too slow or unreachable — try again", e.message)
    }

    @Test
    fun theCapCoversTheFallbackAndKeepsTheServersEarlierFailure() = runBlocking {
        // #507: the fallback used to get its own 45 s on top of the rolls; now
        // it runs inside the same cap, and when the cap fires there the
        // server's failure that sent the spin to the fallback is still named.
        val e = assertFailsWith<SpinFailure> {
            LoopSpin.spin(
                request(null), serverUsable = true,
                roll = { throw HttpStatusException(503, "") },
                fallback = hangingFallback, timeoutMs = 50,
            )
        }
        assertEquals(
            "Server route failed: the server hit a problem. Try again later. The fallback timed out too.",
            e.message,
        )
    }

    @Test
    fun aTimedReRollThatRunsOutOfTimeKeepsTheFirstRoundsLoop() = runBlocking {
        // #507 review: the first rolls (25 km/h, an hour for a half-hour ask)
        // miss the time, so the spin re-rolls; the re-roll hangs past the cap.
        // The rider gets the first round's loop, as before the cap, not a
        // "too slow or unreachable" error with a loop already in hand.
        val asked = mutableListOf<Double>()
        val firstRound = router(25.0, asked)
        val result = LoopSpin.spin(
            request(30f), serverUsable = true,
            roll = { meters -> if (asked.size < 3) firstRound(meters) else awaitCancellation() },
            fallback = noFallback, timeoutMs = 200,
        )
        assertEquals(LoopDuration.guessMeters(30f), result.route.distanceMeters!!, 1.0)
        assertNull(result.warning)
    }

    @Test
    fun oneStalledRollDoesNotCostTheTwoThatLanded() = runBlocking {
        // #507 review: the rolls run in parallel and used to be awaited all
        // together, so one roll stalling past the cap threw away two loops
        // that came back at once and told the rider the server was too slow.
        var calls = 0
        val result = LoopSpin.spin(
            request(null), serverUsable = true,
            roll = { meters ->
                val n = ++calls
                if (n == 2) awaitCancellation()
                router(50.0, mutableListOf())(meters).copy(distanceMeters = n.toDouble())
            },
            fallback = noFallback, timeoutMs = 200,
        )
        assertTrue(result.route.distanceMeters in setOf(1.0, 3.0), "got ${result.route.distanceMeters}")
        assertNull(result.warning)
    }

    @Test
    fun aTimedSpinWithOneStalledFirstRollRidesTheBestThatLanded() = runBlocking {
        // Same #507 defect on the timed path: the first round never finishes,
        // so there is no re-roll, but the two loops that landed are ridden.
        val asked = mutableListOf<Double>()
        val fast = router(50.0, asked)
        val result = LoopSpin.spin(
            request(30f), serverUsable = true,
            roll = { meters -> if (asked.size == 1) { asked.add(meters); awaitCancellation() } else fast(meters) },
            fallback = noFallback, timeoutMs = 200,
        )
        assertTrue(LoopDuration.fits(result.route, 30f), "got ${result.route.timeMs}")
        assertEquals(3, asked.size)
        assertNull(result.warning)
    }

    @Test
    fun aSpinWithNoServerThatRunsOutOfTimeSaysNoServerIsConfigured() = runBlocking {
        val e = assertFailsWith<SpinFailure> {
            LoopSpin.spin(request(null), serverUsable = false, noRoll, hangingFallback, timeoutMs = 50)
        }
        assertEquals("No routing server configured — public servers timed out", e.message)
    }

    @Test
    fun aSpinThatFinishesInsideTheCapIsUntouched() = runBlocking<Unit> {
        val result = LoopSpin.spin(
            request(null), serverUsable = true, router(50.0, mutableListOf()), noFallback, timeoutMs = 5_000,
        )
        assertNull(result.warning)
    }

    @Test
    fun theRiderCancellingIsNotReportedAsATimeout() = runBlocking<Unit> {
        // A cancellation from outside the spin (the rider pressing Cancel, or
        // a caller's own enclosing timeout, which arrives as the same
        // exception type as the spin's own) must propagate, not become a
        // SpinFailure sentence on a screen the rider has left.
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(50) {
                LoopSpin.spin(request(null), serverUsable = true, hang, hangingFallback, timeoutMs = 5_000)
            }
        }
    }
}

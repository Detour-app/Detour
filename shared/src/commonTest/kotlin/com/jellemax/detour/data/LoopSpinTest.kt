package com.jellemax.detour.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
            "Server route failed (503 Service Unavailable); fallback timed out too",
            spinTimeoutMessage(
                serverError = "503 Service Unavailable",
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
            "Road servers are slow right now — try again",
            spinTimeoutMessage(serverError = null, roundTrip = true, serverUsable = true),
        )
        // Point-to-point never consults the round-trip server, so an unusable
        // config is not the interesting fact about its timeout.
        assertEquals(
            "Road servers are slow right now — try again",
            spinTimeoutMessage(serverError = null, roundTrip = false, serverUsable = false),
        )
    }

    @Test
    fun theFirstRollsReasonIsTheOneReported() {
        // Independent requests against the same server almost always fail the
        // same way; three copies of one sentence helps nobody.
        val reason = loopFailureReason(
            listOf(IOException("connect timed out"), IOException("connect timed out"), null),
        )
        assertEquals("connect timed out", reason)
    }

    @Test
    fun anExceptionWithNoMessageFallsBackToItsTypeThenToAPlainSentence() {
        // A bare IOException has a null message, and "null" is not a reason.
        assertEquals("IOException", loopFailureReason(listOf(IOException())))
        // Nothing failed at all: the caller only asks when the list is empty of
        // successes, so this is the "we have no idea" wording.
        assertEquals("no route", loopFailureReason(listOf(null, null)))
        assertEquals("no route", loopFailureReason(emptyList()))
    }

    private val home = LatLon(50.85, 5.69)

    private fun request(minutes: Float?) = LoopRequest(
        from = home, lengthMeters = 100_000.0, minutes = minutes,
        headingDeg = null, avoidSmallRoads = false, highwayRegex = ".*",
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
            roll = { throw IOException("HTTP 503") },
            fallback = { listOf(LatLon(50.9, 5.7)) },
        )
        assertEquals("Server route failed (HTTP 503) — approximate loop instead", result.warning)
    }

    @Test
    fun aFallbackTimeoutAfterAServerFailureNamesTheServer() = runBlocking {
        val e = assertFailsWith<IOException> {
            LoopSpin.spin(
                request(null), serverUsable = true,
                roll = { throw IOException("HTTP 503") },
                fallback = { withTimeout(1) { delay(1_000) }; emptyList() },
            )
        }
        assertEquals("Server route failed (HTTP 503); fallback timed out too", e.message)
    }
}

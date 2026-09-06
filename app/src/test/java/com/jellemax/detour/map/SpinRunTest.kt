package com.jellemax.detour.map

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions that used to be unreachable inside `MapScreen.spin()`.
 *
 * Neither does I/O; both were welded to a `scope.launch` inside a composable,
 * which is why four distinct failure sentences shipped with no test between
 * them. The one that matters most is the first case below: a fallback timeout
 * must not hide that the rider's own routing server was the thing that failed,
 * because that is the message that tells them where to look.
 */
class SpinRunTest {

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
}

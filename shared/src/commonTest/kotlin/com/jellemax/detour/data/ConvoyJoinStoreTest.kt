package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** The pure halves of [ConvoyJoinStore]: which step a failure earns, and which answers commit. */
class ConvoyJoinStoreTest {

    private val preview = ConvoyInvitePreview("Ardennes", "jelle", 3, 1791100800000, alreadyMember = false)

    @Test
    fun aRefusalTheSpecNamesShowsAsThatRefusal() {
        val refused = ConvoyJoinException(ConvoyJoinRefusal.EXPIRED, HttpStatusException(410, body = ""))

        assertEquals(ConvoyJoinStep.Refused(ConvoyJoinRefusal.EXPIRED), joinStepFor(refused))
    }

    @Test
    fun anyOtherFailureShowsItsMessageOrTheFallback() {
        assertEquals(ConvoyJoinStep.Failed("HTTP 502"), joinStepFor(HttpStatusException(502, body = "")))
        assertEquals(ConvoyJoinStep.Failed(ConvoyJoinStore.FALLBACK_ERROR), joinStepFor(okio.IOException("")))
    }

    @Test
    fun anAnswerForTheHeldLinkCommits() {
        val checking = ConvoyJoinState("abc", ConvoyJoinStep.Checking)
        val ready = ConvoyJoinState("abc", ConvoyJoinStep.Ready(preview))

        assertEquals(ready, checking.committed(sameSession = true, token = "abc", next = ready))
    }

    @Test
    fun anAnswerAfterTheRiderOpenedAnotherLinkIsDropped() {
        val other = ConvoyJoinState("xyz")

        assertSame(other, other.committed(sameSession = true, token = "abc", next = ConvoyJoinState("abc")))
    }

    @Test
    fun anAnswerAfterTheRiderBackedOutIsDropped() {
        val dismissed = ConvoyJoinState()

        assertSame(
            dismissed,
            dismissed.committed(sameSession = true, token = "abc", next = ConvoyJoinState("abc")),
        )
    }

    @Test
    fun anAnswerFromAnEarlierSessionIsDropped() {
        val checking = ConvoyJoinState("abc", ConvoyJoinStep.Checking)

        assertSame(
            checking,
            checking.committed(
                sameSession = false,
                token = "abc",
                next = ConvoyJoinState("abc", ConvoyJoinStep.Ready(preview)),
            ),
        )
    }
}

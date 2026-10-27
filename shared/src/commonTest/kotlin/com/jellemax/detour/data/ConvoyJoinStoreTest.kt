package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** The pure halves of [ConvoyJoinStore]: which step a failure earns, and which answers commit. */
class ConvoyJoinStoreTest {

    private val preview = ConvoyInvitePreview("Ardennes", "jelle", 3, 1791100800000, alreadyMember = false)
    private val abc = ConvoyInviteLink("https://a.example/api", "abc")
    private val xyz = ConvoyInviteLink("https://a.example/api", "xyz")

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
        val checking = ConvoyJoinState(abc, ConvoyJoinStep.Checking)
        val ready = ConvoyJoinState(abc, ConvoyJoinStep.Ready(preview))

        assertEquals(ready, checking.committed(sameSession = true, link = abc, next = ready))
    }

    @Test
    fun anAnswerAfterTheRiderOpenedAnotherLinkIsDropped() {
        val other = ConvoyJoinState(xyz)

        assertSame(other, other.committed(sameSession = true, link = abc, next = ConvoyJoinState(abc)))
    }

    @Test
    fun anAnswerAfterTheRiderBackedOutIsDropped() {
        val dismissed = ConvoyJoinState()

        assertSame(
            dismissed,
            dismissed.committed(sameSession = true, link = abc, next = ConvoyJoinState(abc)),
        )
    }

    @Test
    fun anAnswerFromAnEarlierSessionIsDropped() {
        val checking = ConvoyJoinState(abc, ConvoyJoinStep.Checking)

        assertSame(
            checking,
            checking.committed(
                sameSession = false,
                link = abc,
                next = ConvoyJoinState(abc, ConvoyJoinStep.Ready(preview)),
            ),
        )
    }

    @Test
    fun aPreviewCutOffMidFlightStepsBackSoTheNextCheckAsks() {
        val checking = ConvoyJoinState(abc, ConvoyJoinStep.Checking)

        assertEquals(ConvoyJoinState(abc), checking.cancelled(abc))
    }

    @Test
    fun aJoinCutOffMidFlightStepsBackToReady() {
        val joining = ConvoyJoinState(abc, ConvoyJoinStep.Joining(preview))

        assertEquals(ConvoyJoinState(abc, ConvoyJoinStep.Ready(preview)), joining.cancelled(abc))
    }

    @Test
    fun aCancelLeavesAnAnswerOrAnotherLinkAlone() {
        val ready = ConvoyJoinState(abc, ConvoyJoinStep.Ready(preview))
        val otherChecking = ConvoyJoinState(xyz, ConvoyJoinStep.Checking)

        assertSame(ready, ready.cancelled(abc))
        assertSame(otherChecking, otherChecking.cancelled(abc))
    }

    @Test
    fun theSameTokenOnAnotherServerIsAnotherLink() {
        val checking = ConvoyJoinState(abc, ConvoyJoinStep.Checking)
        val sameTokenOnB = ConvoyInviteLink("https://b.example/api", "abc")

        assertSame(checking, checking.committed(sameSession = true, link = sameTokenOnB, next = ConvoyJoinState()))
        assertSame(checking, checking.cancelled(sameTokenOnB))
    }

    @Test
    fun aLinkHeldThroughASwitchOfServerStopsAtOtherServer() {
        // Pasted signed out for server A, then the rider points Detour at B and signs in (#763).
        val held = ConvoyJoinState(abc)

        assertEquals(
            ConvoyJoinState(abc, ConvoyJoinStep.OtherServer("https://a.example/api")),
            held.checkedAgainst("https://b.example/api"),
        )
        assertEquals(ConvoyJoinStep.OtherServer("https://a.example/api"), held.checkedAgainst(null).step)
    }

    @Test
    fun aLinkOnTheCurrentServerOrNoLinkIsLeftAlone() {
        val ready = ConvoyJoinState(abc, ConvoyJoinStep.Ready(preview))
        val nothing = ConvoyJoinState()

        assertSame(ready, ready.checkedAgainst("https://a.example/api/"))
        assertSame(nothing, nothing.checkedAgainst("https://b.example/api"))
    }
}

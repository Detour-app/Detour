package com.jellemax.detour.drive

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What every test below actually runs under, in place of a bare
 *  `runBlocking` - generous next to the one legitimate real-time wait in
 *  this file (a single backoff step, MIN_BACKOFF_MS = 1s, in
 *  `aSocketThatClosesUnexpectedlyReconnectsWithBackoffAndConnectedReflectsIt`),
 *  but bounded. Every test here waits on a `StateFlow`/`Channel` reaching a
 *  particular state via `first { }` - correct exactly because it suspends
 *  rather than racing a `.value` read (see the class doc above), but that
 *  same suspend never returns on its own if a regression means the state it
 *  is waiting for never arrives. A bare `runBlocking` then hangs forever:
 *  not a red test, a stuck CI job someone has to notice and kill by hand.
 *  `switchingConvoysReopensTheSocketAndJoinsOnlyTheNewOne` did exactly that
 *  when `setConvoy`'s removal close briefly regressed during this task - see
 *  the task report. `withTimeout` turns that hang into an ordinary failing
 *  assertion instead. */
private const val TEST_TIMEOUT_MS = 10_000L

private fun testBody(block: suspend CoroutineScope.() -> Unit) = runBlocking {
    withTimeout(TEST_TIMEOUT_MS, block)
}

/**
 * Covers ConvoyRelay.kt: the convoy live-relay's state machine, ported from
 * two independent hand-rolled loops (Android's `net/ConvoyLiveClient.kt` and
 * iOS's `ConvoyLiveClient.swift`) that agreed on how peers, push-to-talk
 * membership, the spin vote and the reconnect loop behave only by
 * construction. This is the first time any of it runs against something
 * other than two phones and a live relay.
 *
 * [FakeRelaySocket] is what makes that possible: a [RelaySocket] backed by an
 * in-memory [Channel], so a test controls exactly which frames "arrive" and
 * when, and can inspect exactly what got "sent". Every assertion that depends
 * on the relay's own coroutine having processed a frame waits on the
 * relevant `StateFlow` via `first { }` rather than reading `.value`
 * immediately after handing the fake a frame - `first` suspends until the
 * flow actually reaches the state being waited for, so it is correct
 * regardless of how `runBlocking`'s scheduler happens to interleave the test
 * coroutine and the relay's own, whereas reading `.value` right after a
 * `Channel` send would be racing it. No coroutine test dispatcher is used,
 * per this module's house style - `runBlocking` and a real (if short)
 * `delay` are what a genuine reconnect-with-backoff test needs, and nothing
 * here waits longer than one backoff step.
 *
 * The spin-vote rule itself ([SpinRoundOutcome]) was a straight port of
 * `app/.../map/GroupSpinRules.kt`, which had its own pure-function test
 * (`GroupSpinRulesTest.kt`) covering `leadingSpinIndex`/`resolveSpinRound` in
 * isolation - tie-breaks, pruned-peer edge cases, and so on. Task 5 deleted
 * both files once Android and iOS were repointed at this shared copy; the
 * handful of cases that file covered and this one did not yet - ties, a
 * stale out-of-range vote, an empty tally, a signed-out device's own blank
 * username never being waited for - are ported into the "spin rule" section
 * below rather than dropped, driven through [ConvoyRelay.currentLeadIndex]
 * and [ConvoyRelay.spinRoundOutcome] exactly as production code now does,
 * rather than through the private `leadingSpinIndex`/`resolveSpinRound`
 * functions `GroupSpinRulesTest.kt` used to call directly. What only this
 * file could ever cover is still below them, unchanged: that a relay driven
 * entirely off its own state reaches the identical outcome as a second relay
 * with a *different* peer set, given the same wire frames.
 *
 * Most tests below call [ConvoyRelay.setConvoy] (and/or
 * [ConvoyRelay.setNotifyingCircles]) *before* launching [ConvoyRelay.run] -
 * membership is state, independent of the connection loop, so it can be set
 * ahead of a connection exactly as `CircleNotifyService`/`MapScreen` do on
 * the real clients. See the "additive membership" group of tests near the
 * bottom for the property a one-`groupId`-per-`run()` signature made
 * impossible: the socket serving a convoy and any number of circles at once,
 * and an *addition* to either joining without disturbing the other. See the
 * "removal reopens" group right after it for the other half: a *removal* -
 * leaving a convoy, switching convoys, or dropping a circle - has no wire
 * frame to do it with, so it closes and reopens the socket instead.
 */
class ConvoyRelayTest {

    // --- the fake socket ------------------------------------------------------

    /**
     * A [RelaySocket] good enough to drive [ConvoyRelay]'s whole state
     * machine without a phone or a relay. [receive] suspends on an in-memory
     * [Channel] that a test feeds via [push]; closing that channel (from
     * [close], or a test calling it directly to simulate an unexpected
     * drop) is what makes a blocked [receive] return `null`, the same signal
     * a real closed socket gives. [connect] opens a fresh channel each time,
     * since a real reconnect opens a fresh underlying connection too.
     */
    private class FakeRelaySocket : RelaySocket {
        val sent = mutableListOf<String>()
        val connectCount = MutableStateFlow(0)
        var connectFailsWith: Exception? = null
        var lastBearer: String? = null
        private var inbox = Channel<String>(Channel.UNLIMITED)

        override suspend fun connect(bearer: String) {
            lastBearer = bearer
            connectFailsWith?.let { throw it }
            inbox = Channel(Channel.UNLIMITED)
            connectCount.update { it + 1 }
        }

        override suspend fun receive(): String? = inbox.receiveCatching().getOrNull()

        override fun send(text: String) {
            sent += text
        }

        override fun close() {
            inbox.close()
        }

        /** Delivers [text] as the next frame [receive] returns. */
        suspend fun push(text: String) = inbox.send(text)
    }

    // --- fixtures ---------------------------------------------------------

    private fun joinedFrame() = """{"type":"joined"}"""

    private fun positionsFrame(username: String, lat: Double, lon: Double, ttl: Int = 10) =
        """{"type":"positions","peers":[{"u":"$username","lat":$lat,"lon":$lon,"ts":0,"ttl":$ttl}]}"""

    private fun errorFrame(message: String?) =
        if (message == null) """{"type":"error"}""" else """{"type":"error","message":"$message"}"""

    private fun friendPosition(username: String, expiresAtMs: Long) = FriendPosition(
        username = username,
        lat = 51.0,
        lon = 4.0,
        headingDeg = null,
        speedKmh = null,
        tsMs = 0L,
        expiresAtMs = expiresAtMs,
    )

    private fun tokenSupplier(token: String = "test-token") = BearerSource { token }

    // --- connection lifecycle, against the fake socket ---------------------

    @Test
    fun joiningEmitsAJoinFrameCarryingTheGroupId() = testBody {
        val socket = FakeRelaySocket()
        val relay = ConvoyRelay()
        relay.setConvoy("convoy-1")
        val job = launch { relay.run(socket, tokenSupplier()) }

        socket.connectCount.first { it >= 1 }
        socket.push(joinedFrame())
        // Only true once the receive loop is running, which is after the
        // join frame was already sent - a solid sync point with no race.
        relay.connected.first { it }

        assertEquals(listOf(RelayProtocol.buildJoin("convoy-1")), socket.sent)

        relay.stop()
        job.join()
    }

    @Test
    fun positionsFramePopulatesPeersAndALaterOneForTheSamePeerReplaces() = testBody {
        val socket = FakeRelaySocket()
        val relay = ConvoyRelay()
        relay.setConvoy("convoy-1")
        val job = launch { relay.run(socket, tokenSupplier()) }
        socket.connectCount.first { it >= 1 }
        socket.push(joinedFrame())
        relay.connected.first { it }

        socket.push(positionsFrame("bob", lat = 51.0, lon = 4.0))
        val firstPeers = relay.peers.first { it.containsKey("bob") }
        assertEquals(51.0, firstPeers.getValue("bob").lat)

        socket.push(positionsFrame("bob", lat = 52.0, lon = 5.0))
        val updatedPeers = relay.peers.first { it["bob"]?.lat == 52.0 }
        // Replaced, not duplicated: still exactly one entry for "bob".
        assertEquals(1, updatedPeers.size)

        relay.stop()
        job.join()
    }

    @Test
    fun stopEndsRunWithoutCancellingItsCoroutine() = testBody {
        val socket = FakeRelaySocket()
        val relay = ConvoyRelay()
        relay.setConvoy("convoy-1")
        var returnedNormally = false
        val job = launch {
            relay.run(socket, tokenSupplier())
            // Only reached if run() actually returned - a cancelled
            // coroutine never runs the statement after a cancelled suspend.
            returnedNormally = true
        }

        socket.connectCount.first { it >= 1 }
        socket.push(joinedFrame())
        relay.connected.first { it } // run() is genuinely blocked inside receive() right now

        relay.stop()
        job.join()

        // The point of this test, stated directly: stop() ends run() by its
        // own flag, not by cancelling the coroutine running it. Deliberately
        // not proven by calling job.cancel() ourselves - that is exactly the
        // mechanism a Swift caller cannot rely on (see ConvoyRelay's class
        // doc), so a test that used it would prove nothing about this case.
        assertFalse(job.isCancelled, "run() should complete normally, not be cancelled, when stop() is called")
        assertTrue(returnedNormally)
    }

    @Test
    fun aSocketThatClosesUnexpectedlyReconnectsWithBackoffAndConnectedReflectsIt() = testBody {
        val socket = FakeRelaySocket()
        val relay = ConvoyRelay()
        relay.setConvoy("convoy-1")
        val job = launch { relay.run(socket, tokenSupplier()) }

        socket.connectCount.first { it >= 1 }
        socket.push(joinedFrame())
        relay.connected.first { it }

        val elapsedMs = measureTimeMillis {
            // Drops the connection the way a network blip or a server
            // restart would - not relay.stop(), a different exit path
            // covered by the test above.
            socket.close()
            relay.connected.first { !it }
            socket.connectCount.first { it >= 2 }
        }
        // MIN_BACKOFF_MS is 1000ms; a reconnect faster than that would mean
        // the wait was skipped rather than honoured.
        assertTrue(elapsedMs >= 900, "expected the reconnect to wait out a backoff, took ${elapsedMs}ms")

        socket.push(joinedFrame())
        relay.connected.first { it }

        relay.stop()
        job.join()
    }

    @Test
    fun stopPublishesTheDisconnectAndNotJustTheClosedSocket() = testBody {
        val socket = FakeRelaySocket()
        val relay = ConvoyRelay()
        relay.setConvoy("convoy-1")
        val job = launch { relay.run(socket, tokenSupplier()) }
        socket.connectCount.first { it >= 1 }
        socket.push(joinedFrame())
        relay.connected.first { it }

        relay.stop()
        job.join()

        // A screen reads `connected`, not the socket. Leaving this true on a
        // terminal exit had FriendsScreen still showing "Connected" after a
        // sign-out or a 401, with the convoy notification up and the toggle
        // button's first tap calling stop() again instead of reconnecting.
        assertFalse(relay.connected.value)
    }

    @Test
    fun lastErrorReportsWhenTheRelayCannotBeReached() = testBody {
        val socket = FakeRelaySocket()
        // A socket words its own failures - see RelaySocket.connect. This one
        // stands in for a refused upgrade, whose status code the rider needs
        // and which a generic prefix would have buried.
        socket.connectFailsWith = Exception("Live server refused the connection (401)")
        val relay = ConvoyRelay()
        relay.setConvoy("convoy-1")
        val job = launch { relay.run(socket, tokenSupplier()) }

        val error = relay.lastError.first { it != null }
        assertEquals("Live server refused the connection (401)", error)

        relay.stop()
        job.join()
    }

    @Test
    fun lastErrorFallsBackToItsOwnWordingWhenTheSocketGivesNone() = testBody {
        val socket = FakeRelaySocket()
        socket.connectFailsWith = Exception()
        val relay = ConvoyRelay()
        relay.setConvoy("convoy-1")
        val job = launch { relay.run(socket, tokenSupplier()) }

        assertEquals("Can't reach the live server", relay.lastError.first { it != null })

        relay.stop()
        job.join()
    }

    @Test
    fun lastErrorStaysNullWhenJoinedButNobodyIsSendingAnything() = testBody {
        val socket = FakeRelaySocket()
        val relay = ConvoyRelay()
        relay.setConvoy("convoy-1")
        val job = launch { relay.run(socket, tokenSupplier()) }

        socket.connectCount.first { it >= 1 }
        socket.push(joinedFrame())
        relay.connected.first { it }

        // The distinction lastError exists for: connected, joined, and quiet
        // is not an error - it is just a convoy where nobody has moved yet.
        assertNull(relay.lastError.value)
        assertTrue(relay.peers.value.isEmpty())

        relay.stop()
        job.join()
    }

    @Test
    fun anErrorFrameSetsLastErrorAndEndsTheAttemptWithoutCrashing() = testBody {
        val socket = FakeRelaySocket()
        val relay = ConvoyRelay()
        relay.setConvoy("convoy-1")
        val job = launch { relay.run(socket, tokenSupplier()) }

        socket.connectCount.first { it >= 1 }
        socket.push(joinedFrame())
        relay.connected.first { it }

        // "error" is deliberately not one of RelayProtocol.decode's nine
        // frame types (see Task 1's report) - this is the special case that
        // reads it straight off the wire instead, same as both existing
        // clients do outside their own per-frame switch.
        socket.push(errorFrame("membership removed"))
        val error = relay.lastError.first { it != null }
        assertEquals("membership removed", error)

        relay.stop()
        job.join()
    }

    // --- state transitions, applied directly - no socket or coroutine needed ---
    //
    // applyEvent/prunePeers are what the receive loop above calls for every
    // frame; driving them directly here proves the same state transitions
    // without needing a live connection for every single case.

    @Test
    fun pruningRemovesExactlyTheExpiredPeersAndLeavesTheRest() {
        val relay = ConvoyRelay()
        relay.applyEvent(
            RelayEvent.Positions(
                listOf(
                    friendPosition("longExpired", expiresAtMs = 1_000L),
                    // Expiring exactly at nowMs is expired too - matching
                    // both existing clients' `expiresAtMs > now`, a strict
                    // inequality rather than "still good at".
                    friendPosition("expiringNow", expiresAtMs = 2_000L),
                    friendPosition("fresh", expiresAtMs = 2_001L),
                ),
            ),
        )

        relay.prunePeers(nowMs = 2_000L)

        assertEquals(setOf("fresh"), relay.peers.value.keys)
    }

    @Test
    fun pttStartAndPttEndAddAndRemoveFromTalking() {
        val relay = ConvoyRelay()
        relay.applyEvent(RelayEvent.PttStart("bob"))
        assertEquals(setOf("bob"), relay.talking.value)

        relay.applyEvent(RelayEvent.PttStart("carol"))
        assertEquals(setOf("bob", "carol"), relay.talking.value)

        relay.applyEvent(RelayEvent.PttEnd("bob"))
        assertEquals(setOf("carol"), relay.talking.value)
    }

    @Test
    fun leftRemovesThePeerAndClearsThemFromTalking() {
        val relay = ConvoyRelay()
        relay.applyEvent(RelayEvent.Positions(listOf(friendPosition("bob", expiresAtMs = Long.MAX_VALUE))))
        relay.applyEvent(RelayEvent.PttStart("bob"))

        relay.applyEvent(RelayEvent.Left("bob"))

        assertTrue(relay.peers.value.isEmpty())
        assertTrue(relay.talking.value.isEmpty())
    }

    // --- the spin rule: the point of this file -----------------------------

    @Test
    fun aOneCandidateOfferCommitsOnEveryDeviceRegardlessOfPeersOrVotes() {
        val relay = ConvoyRelay()
        // No peers known at all, and nobody has voted - still commits,
        // because a one-candidate offer *is* the decision, not a ballot.
        relay.applyEvent(RelayEvent.SpinOffer(listOf(SpinCandidate(51.0, 4.0, null, null, "Only option"))))

        assertEquals(SpinRoundOutcome.CommitOnly, relay.spinRoundOutcome(myUsername = "dave"))
    }

    @Test
    fun aMultiCandidateOfferTalliesVotesAndOnlyClosesOnceEveryExpectedVoterHasVoted() {
        val relay = ConvoyRelay()
        relay.applyEvent(
            RelayEvent.Positions(
                listOf(
                    friendPosition("bob", expiresAtMs = Long.MAX_VALUE),
                    friendPosition("carol", expiresAtMs = Long.MAX_VALUE),
                ),
            ),
        )
        // This device opens the round - only the opener ever closes one,
        // see resolveSpinRound's doc in ConvoyRelay.kt.
        relay.sendSpinOffer(
            listOf(
                SpinCandidate(51.0, 4.0, null, null, "A"),
                SpinCandidate(52.0, 5.0, null, null, "B"),
            ),
        )

        relay.applyEvent(RelayEvent.SpinVote("bob", 1))
        // dave (this device) has not voted yet - still waiting, however
        // lopsided the tally already looks.
        assertEquals(SpinRoundOutcome.Wait, relay.spinRoundOutcome(myUsername = "dave"))

        relay.applyEvent(RelayEvent.SpinVote("carol", 1))
        relay.sendSpinVote("dave", 0)

        assertEquals(SpinRoundOutcome.CloseRound(leadIndex = 1), relay.spinRoundOutcome(myUsername = "dave"))
    }

    /**
     * The property this whole file exists to test, exercised for real: a
     * *receiver* handed the same multi-candidate offer the sharer just sent
     * - never sent by the receiver itself - must not resolve it on its own,
     * however complete its own tally looks, because a different peer set
     * (or different prune timing, or a dropped frame) can make "complete"
     * mean something different on each device. Only the sharer's *closing*
     * one-candidate offer may commit anything, and every device - sharer and
     * receiver alike, different peer sets and all - must land on the same
     * destination off that one frame.
     *
     * Every earlier test in this file only ever fed a relay either a
     * multi-candidate offer it sent itself ([sendSpinOffer], `fromMe = true`)
     * or a one-candidate *closing* offer, which commits before `fromMe` is
     * ever consulted (`resolveSpinRound`'s `candidateCount == 1` branch).
     * Neither exercises the `if (!fromMe) return Wait` branch a receiver
     * actually depends on - delete that branch and every test up to this one
     * still passes. This test fails without it: relayB below is fed a full
     * tally (bob, carol, and its own zoe, matching every voter its own peer
     * set expects) for a multi-candidate offer it never sent, and asserts
     * `Wait` - remove the branch and `resolveSpinRound` falls through to the
     * same tally check `fromMe = true` uses, sees `expected` fully covered,
     * and returns `CloseRound(leadIndex = 1)` instead.
     */
    @Test
    fun aReceiverWithADifferentPeerSetNeverResolvesAMultiCandidateOfferItDidNotSendThenCommitsTheSharersClosingOne() {
        val candidates = listOf(
            SpinCandidate(51.0, 4.0, null, null, "A"),
            SpinCandidate(53.0, 6.0, distanceM = 9_000.0, durationS = 800.0, name = "Coast road"),
        )

        // relayA is the sharer: it alone may tally the vote and close the
        // round, among its own peers.
        val relayA = ConvoyRelay()
        relayA.applyEvent(
            RelayEvent.Positions(
                listOf(
                    friendPosition("bob", expiresAtMs = Long.MAX_VALUE),
                    friendPosition("carol", expiresAtMs = Long.MAX_VALUE),
                ),
            ),
        )
        relayA.sendSpinOffer(candidates)

        // relayB is a member with a *completely different* peer set - it has
        // never seen bob or carol, only zoe - and receives this same
        // multi-candidate offer over the wire, never having sent it itself.
        val relayB = ConvoyRelay()
        relayB.applyEvent(RelayEvent.Positions(listOf(friendPosition("zoe", expiresAtMs = Long.MAX_VALUE))))
        relayB.applyEvent(RelayEvent.SpinOffer(candidates))

        // Feed relayB every vote out there - bob's and carol's (relayA's
        // peers, not relayB's own) plus zoe's own - a tally that is complete
        // by every measure relayB can see, expected voters included.
        relayB.applyEvent(RelayEvent.SpinVote("bob", 1))
        relayB.applyEvent(RelayEvent.SpinVote("carol", 1))
        relayB.sendSpinVote("zoe", 1)

        // The property under test, stated directly: relayB never closes this
        // round itself, no matter how complete its own tally looks - only
        // the sharer may.
        assertEquals(SpinRoundOutcome.Wait, relayB.spinRoundOutcome(myUsername = "zoe"))

        // Now the sharer actually completes its own round, among its own peers.
        relayA.applyEvent(RelayEvent.SpinVote("bob", 1))
        relayA.applyEvent(RelayEvent.SpinVote("carol", 1))
        relayA.sendSpinVote("dave", 0)

        val outcome = relayA.spinRoundOutcome(myUsername = "dave")
        assertEquals(SpinRoundOutcome.CloseRound(leadIndex = 1), outcome)

        // What MapScreen's own vote-round effect does on seeing CloseRound:
        // re-offer just the winner, which is what actually commits the
        // round everywhere, including here on the sharer's own device.
        val winner = candidates[(outcome as SpinRoundOutcome.CloseRound).leadIndex]
        relayA.sendSpinOffer(listOf(winner))

        // relayB receives that same closing frame over the wire - still with
        // its own, still-different, peer set.
        relayB.applyEvent(RelayEvent.SpinOffer(listOf(winner)))

        assertEquals(SpinRoundOutcome.CommitOnly, relayA.spinRoundOutcome(myUsername = "dave"))
        assertEquals(SpinRoundOutcome.CommitOnly, relayB.spinRoundOutcome(myUsername = "zoe"))
        // The point, stated directly: two relays with genuinely different
        // peer sets land on an identical destination.
        assertEquals(relayA.spinOffer.value!!.candidates.single(), relayB.spinOffer.value!!.candidates.single())
        assertEquals("Coast road", relayB.spinOffer.value!!.candidates.single().name)
    }

    // --- ported from the deleted GroupSpinRulesTest.kt ----------------------
    //
    // `app/.../map/GroupSpinRules.kt`'s own test covered `leadingSpinIndex`
    // and `resolveSpinRound` as bare pure functions - Task 5 deleted both
    // files once Android and iOS were repointed at this class, so the cases
    // below that this file's own tests (above) never happened to exercise are
    // ported here instead of lost, driven through the same public API
    // production code now calls: [ConvoyRelay.currentLeadIndex] for the
    // tie-break itself, [ConvoyRelay.spinRoundOutcome] for the "who is
    // waited for" rule. `theMostVotedCandidateLeads`,
    // `aOneCandidateOfferCommitsRegardlessOfVotes`,
    // `theSharerClosesTheRoundOnceEveryoneHasVoted`,
    // `theSharerWaitsWhileAVoteIsStillOut`, `aReceivingDeviceNeverClosesARound`
    // and `aPrunedPeerCannotCompleteTheRoundEarly` are not ported - each pins
    // a property already covered above, most thoroughly by
    // `aReceiverWithADifferentPeerSetNeverResolvesAMultiCandidateOfferItDidNotSendThenCommitsTheSharersClosingOne`.
    // `anEmptyExpectedSetNeverClosesARound` *is* ported, as
    // `anEmptyExpectedSetNeverClosesARoundEvenForTheSharer` just below - a
    // previous accounting of this file named four ported cases and six
    // already-covered ones, ten of this file's eleven, and left this
    // eleventh in neither list. Nothing above exercises `expected.isEmpty()`
    // on `resolveSpinRound`'s own guard: every other test that reaches
    // `CloseRound` does so with at least one live peer or a non-blank
    // `myUsername` in `expected`. Deleting that guard alone still leaves all
    // 293 other shared tests green - this is the one that catches it.

    @Test
    fun noVotesAtAllLeadsWithTheFirstCandidate() {
        val relay = ConvoyRelay()
        assertEquals(0, relay.currentLeadIndex(candidateCount = 3))
    }

    @Test
    fun tiesInTheCurrentTallyGoToTheLowestIndex() {
        val relayA = ConvoyRelay()
        relayA.sendSpinVote("a", 0)
        relayA.sendSpinVote("b", 2)
        assertEquals(0, relayA.currentLeadIndex(candidateCount = 3))

        val relayB = ConvoyRelay()
        relayB.sendSpinVote("a", 1)
        relayB.sendSpinVote("b", 2)
        assertEquals(1, relayB.currentLeadIndex(candidateCount = 3))
    }

    /** A vote for a candidate this device does not have - a frame from a
     *  newer offer, an index past the end - is ignored rather than breaking
     *  the tally. */
    @Test
    fun votesOutsideTheCandidateRangeAreIgnoredInTheCurrentTally() {
        val relay = ConvoyRelay()
        relay.sendSpinVote("a", 7)
        relay.sendSpinVote("b", -1)
        relay.sendSpinVote("c", 0)
        assertEquals(0, relay.currentLeadIndex(candidateCount = 3))
    }

    /** A blank username means not signed in, and must not be waited for - a
     *  round the local, signed-out device itself opened would otherwise
     *  never be able to close. */
    @Test
    fun aBlankUsernameIsNotWaitedForWhenClosingARound() {
        val relay = ConvoyRelay()
        relay.applyEvent(RelayEvent.Positions(listOf(friendPosition("alice", expiresAtMs = Long.MAX_VALUE))))
        relay.sendSpinOffer(
            listOf(
                SpinCandidate(51.0, 4.0, null, null, "A"),
                SpinCandidate(52.0, 5.0, null, null, "B"),
            ),
        )
        relay.applyEvent(RelayEvent.SpinVote("alice", 1))

        assertEquals(SpinRoundOutcome.CloseRound(leadIndex = 1), relay.spinRoundOutcome(myUsername = ""))
    }

    /**
     * No known live voter at all - no peers, and a blank (signed-out)
     * `myUsername` - is not a complete round. Without `resolveSpinRound`'s
     * `expected.isEmpty() ||` guard, an empty `expected` vacuously satisfies
     * `votes.keys.containsAll(expected)` (every element of the empty set is
     * trivially "contained"), so a sharer with nobody around it yet would
     * close its own round on zero votes - `CloseRound(0)`, an arbitrary
     * candidate committed for the whole convoy, instead of `Wait`. Ported
     * from the deleted `GroupSpinRulesTest.kt`'s `anEmptyExpectedSetNeverClosesARound` -
     * see this section's own header comment for why it was missing from both
     * of that porting task's accounting lists.
     */
    @Test
    fun anEmptyExpectedSetNeverClosesARoundEvenForTheSharer() {
        val relay = ConvoyRelay()
        // No peers ever seen, and sendSpinOffer opens the round as this
        // device itself (fromMe = true) - the sharer's own case, since a
        // receiver already never closes anything regardless of expected
        // (see aReceivingDeviceNeverClosesARound's note above).
        relay.sendSpinOffer(
            listOf(
                SpinCandidate(51.0, 4.0, null, null, "A"),
                SpinCandidate(52.0, 5.0, null, null, "B"),
            ),
        )

        // myUsername blank too, so expected = _peers.value.keys (empty) +
        // nothing = the empty set this guard exists for.
        assertEquals(SpinRoundOutcome.Wait, relay.spinRoundOutcome(myUsername = ""))
    }

    /** [ConvoyRelay.spinRoundIsReadyToClose] is a `Boolean`-only projection of
     *  [ConvoyRelay.spinRoundOutcome] for Swift - added because this codebase
     *  has no precedent for switching over a Kotlin sealed interface from
     *  Swift (see its own doc). Pinned directly since nothing on the iOS side
     *  can be compiled here to catch a drift between the two. */
    @Test
    fun spinRoundIsReadyToCloseAgreesWithSpinRoundOutcome() {
        val relay = ConvoyRelay()
        relay.applyEvent(RelayEvent.Positions(listOf(friendPosition("bob", expiresAtMs = Long.MAX_VALUE))))
        relay.sendSpinOffer(
            listOf(
                SpinCandidate(51.0, 4.0, null, null, "A"),
                SpinCandidate(52.0, 5.0, null, null, "B"),
            ),
        )
        assertFalse(relay.spinRoundIsReadyToClose(myUsername = "dave"))

        relay.applyEvent(RelayEvent.SpinVote("bob", 1))
        relay.sendSpinVote("dave", 1)

        assertTrue(relay.spinRoundIsReadyToClose(myUsername = "dave"))
        assertEquals(1, relay.currentLeadIndex(candidateCount = 2))
    }

    // --- additive membership: the point of this task's fix -----------------
    //
    // The socket serves a convoy and any number of circles at once - what a
    // required `groupId` parameter on run() made impossible to express at
    // all. setConvoy/setNotifyingCircles are state, independent of run()'s
    // own lifecycle: set before run() starts (as CircleNotifyService and
    // MapScreen each do today) or changed while it is live.

    @Test
    fun circlesAloneKeepTheSocketConnectedWithNoConvoyJoined() = testBody {
        val socket = FakeRelaySocket()
        val relay = ConvoyRelay()
        // The CircleNotifyService case: no convoy at all, ever - exactly
        // what run(groupId, ...) could not express.
        relay.setNotifyingCircles(setOf("circle-1"))
        val job = launch { relay.run(socket, tokenSupplier()) }

        socket.connectCount.first { it >= 1 }
        socket.push(joinedFrame())
        relay.connected.first { it }

        assertEquals(listOf(RelayProtocol.buildJoin("circle-1")), socket.sent)

        relay.stop()
        job.join()
    }

    @Test
    fun aConvoyAndCirclesEachJoinAsTheirOwnFrameOnConnect() = testBody {
        val socket = FakeRelaySocket()
        val relay = ConvoyRelay()
        relay.setConvoy("convoy-1")
        relay.setNotifyingCircles(setOf("circle-1"))
        val job = launch { relay.run(socket, tokenSupplier()) }

        socket.connectCount.first { it >= 1 }
        socket.push(joinedFrame())
        relay.connected.first { it }

        // Both existing clients send one `join` per group, never a combined
        // frame - `net/ConvoyLiveClient.kt`'s `onOpen` and
        // `ConvoyLiveClient.swift`'s `connectAndAwaitClose` each loop over
        // "the convoy, then every circle", one send per id.
        assertEquals(
            listOf(RelayProtocol.buildJoin("convoy-1"), RelayProtocol.buildJoin("circle-1")),
            socket.sent,
        )

        relay.stop()
        job.join()
    }

    @Test
    fun addingACircleWhileConnectedJoinsOnTheLiveSocketWithoutReopening() = testBody {
        val socket = FakeRelaySocket()
        val relay = ConvoyRelay()
        relay.setConvoy("convoy-1")
        val job = launch { relay.run(socket, tokenSupplier()) }

        socket.connectCount.first { it >= 1 }
        socket.push(joinedFrame())
        relay.connected.first { it }

        relay.setNotifyingCircles(setOf("circle-1"))

        assertEquals(
            listOf(RelayProtocol.buildJoin("convoy-1"), RelayProtocol.buildJoin("circle-1")),
            socket.sent,
        )
        // The point: no new underlying connection was opened for this.
        assertEquals(1, socket.connectCount.value)

        relay.stop()
        job.join()
    }

    @Test
    fun addingAConvoyWhileConnectedForCirclesJoinsOnTheLiveSocketWithoutReopening() = testBody {
        val socket = FakeRelaySocket()
        val relay = ConvoyRelay()
        relay.setNotifyingCircles(setOf("circle-1"))
        val job = launch { relay.run(socket, tokenSupplier()) }

        socket.connectCount.first { it >= 1 }
        socket.push(joinedFrame())
        relay.connected.first { it }

        // A fresh convoy - there was none before - is an addition exactly
        // like a fresh circle id: joins on the live socket, no reconnect.
        relay.setConvoy("convoy-1")

        assertEquals(
            listOf(RelayProtocol.buildJoin("circle-1"), RelayProtocol.buildJoin("convoy-1")),
            socket.sent,
        )
        assertEquals(1, socket.connectCount.value)

        relay.stop()
        job.join()
    }

    @Test
    fun anAdditionBeforeTheJoinedReplyArrivesIsSentRatherThanLost() = testBody {
        val socket = FakeRelaySocket()
        val relay = ConvoyRelay()
        relay.setConvoy("convoy-1")
        val job = launch { relay.run(socket, tokenSupplier()) }

        // The connect has succeeded and this attempt's own join batch is
        // already on the wire - joinEverythingWanted only runs after
        // connect() returns - but the server's "joined" reply has not been
        // pushed yet. This is exactly the window an addition used to be
        // silently lost in, gated on `connected` staying false until a
        // reply nothing here has sent.
        socket.connectCount.first { it >= 1 }
        assertFalse(relay.connected.value, "the joined reply has not been pushed yet")

        relay.setNotifyingCircles(setOf("circle-1"))

        assertEquals(
            listOf(RelayProtocol.buildJoin("convoy-1"), RelayProtocol.buildJoin("circle-1")),
            socket.sent,
        )
        // Still the one connection - sent straight onto the live socket,
        // not queued for a reconnect that would never come for an addition.
        assertEquals(1, socket.connectCount.value)

        socket.push(joinedFrame())
        relay.connected.first { it }

        relay.stop()
        job.join()
    }

    // --- removal reopens: the point of *this* task's fix -------------------
    //
    // The relay's outbound protocol is exactly seven frame types - join,
    // location, ptt_start, ptt_end, ptt_audio, spin_offer, spin_vote - and
    // none of them detaches a socket from a single group. Parting any one
    // membership therefore has no cheap path: the whole socket has to close
    // and reopen, then rejoin everything still wanted. Leaving this additive
    // for a removal - as ConvoyRelay did before this fix, and as iOS still
    // does for a dropped circle - leaves the device joined to a group it has
    // left, and sendLocation() keeps broadcasting this device's GPS onto it:
    // the same shape of leak already fixed once for a socket that outlived a
    // sign-out (see ConvoyRelay's class doc). The three tests below replace
    // three from the previous task that asserted exactly the behaviour this
    // task exists to correct.

    @Test
    fun removingACircleWhileConnectedReopensTheSocketAndRejoinsExactlyTheRemainder() = testBody {
        val socket = FakeRelaySocket()
        val relay = ConvoyRelay()
        relay.setConvoy("convoy-1")
        relay.setNotifyingCircles(setOf("circle-1", "circle-2"))
        val job = launch { relay.run(socket, tokenSupplier()) }

        socket.connectCount.first { it >= 1 }
        socket.push(joinedFrame())
        relay.connected.first { it }
        val sentBeforeDrop = socket.sent.size

        relay.setNotifyingCircles(setOf("circle-2"))

        // No wire "leave a single group" exists (see ConvoyRelay's class
        // doc) - dropping circle-1 alone forces a full reconnect.
        socket.connectCount.first { it >= 2 }
        socket.push(joinedFrame())
        relay.connected.first { it }

        // The join batch sent on the fresh connection covers exactly what's
        // still wanted - convoy-1 and circle-2 - and not circle-1.
        assertEquals(
            listOf(RelayProtocol.buildJoin("convoy-1"), RelayProtocol.buildJoin("circle-2")),
            socket.sent.drop(sentBeforeDrop),
        )

        relay.stop()
        job.join()
    }

    @Test
    fun switchingConvoysReopensTheSocketAndJoinsOnlyTheNewOne() = testBody {
        val socket = FakeRelaySocket()
        val relay = ConvoyRelay()
        relay.setConvoy("convoy-1")
        val job = launch { relay.run(socket, tokenSupplier()) }

        socket.connectCount.first { it >= 1 }
        socket.push(joinedFrame())
        relay.connected.first { it }

        relay.setConvoy("convoy-2")

        // A convoy switch is a removal (of convoy-1) plus an addition (of
        // convoy-2) - the exact case ConvoyLiveClient.swift's join(convoyId:)
        // comment is about: simply joining convoy-2 on top of convoy-1 would
        // leave this device receiving both convoys' traffic. The only way to
        // actually stop being joined to convoy-1 is a full reconnect.
        socket.connectCount.first { it >= 2 }
        socket.push(joinedFrame())
        relay.connected.first { it }

        // Only convoy-2 is ever joined on the fresh connection - convoy-1's
        // join from before the reconnect is the only place it appears.
        assertEquals(
            listOf(RelayProtocol.buildJoin("convoy-1"), RelayProtocol.buildJoin("convoy-2")),
            socket.sent,
        )

        relay.stop()
        job.join()
    }

    @Test
    fun leavingTheConvoyWhileCirclesRemainReopensTheSocketAndClearingBothLetsRunFinish() = testBody {
        val socket = FakeRelaySocket()
        val relay = ConvoyRelay()
        relay.setConvoy("convoy-1")
        relay.setNotifyingCircles(setOf("circle-1"))
        var returnedNormally = false
        val job = launch {
            relay.run(socket, tokenSupplier())
            returnedNormally = true
        }

        socket.connectCount.first { it >= 1 }
        socket.push(joinedFrame())
        relay.connected.first { it }

        relay.setConvoy(null)

        // Leaving the convoy is a removal - the remaining circle keeps the
        // socket up across it (run() must not return), but there is nothing
        // that lets convoy-1 be parted from this socket short of a
        // reconnect, so one happens despite circle-1 still being wanted.
        socket.connectCount.first { it >= 2 }
        socket.push(joinedFrame())
        relay.connected.first { it }
        assertFalse(job.isCompleted, "the remaining circle should keep run() alive across the reconnect")

        // Rejoined exactly what's still wanted on the fresh connection -
        // circle-1, and not convoy-1.
        assertEquals(
            listOf(
                RelayProtocol.buildJoin("convoy-1"), RelayProtocol.buildJoin("circle-1"),
                RelayProtocol.buildJoin("circle-1"),
            ),
            socket.sent,
        )

        // Now nothing wants the socket at all - this is what actually ends
        // run(), without stop() ever being called.
        relay.setNotifyingCircles(emptySet())
        job.join()

        assertTrue(returnedNormally, "run() should return once nothing wants the socket, without needing stop()")
    }

    /**
     * The last notifying circle dropping with no convoy ever joined is the
     * one reachable case where [run] itself - not [stop] - decides nothing
     * is wanted any more (see the test just above). The pruner that would
     * otherwise age [peers] out over [RelayProtocol.FALLBACK_PEER_TTL_MS]
     * dies with this same coroutine at that exact moment, so without
     * clearing them directly here, a departed circle's members - and
     * whoever was mid-transmission - would sit in [peers]/[talking] until
     * the next [run], long after there is any membership left for them to
     * belong to. Distinct from a plain [stop] with membership still wanted,
     * which must not touch either - see [run]'s own doc for where this is
     * gated.
     */
    @Test
    fun theLastNotifyingCircleDroppingWithNoConvoyJoinedClearsPeersAndTalkingNotJustTheSocket() = testBody {
        val socket = FakeRelaySocket()
        val relay = ConvoyRelay()
        relay.setNotifyingCircles(setOf("circle-1"))
        val job = launch { relay.run(socket, tokenSupplier()) }

        socket.connectCount.first { it >= 1 }
        socket.push(joinedFrame())
        relay.connected.first { it }

        socket.push(positionsFrame("bob", lat = 51.0, lon = 4.0))
        relay.peers.first { it.containsKey("bob") }
        relay.applyEvent(RelayEvent.PttStart("bob"))

        relay.setNotifyingCircles(emptySet())
        job.join()

        assertTrue(relay.peers.value.isEmpty(), "a departed circle's members must not survive the exit that dropped them")
        assertTrue(relay.talking.value.isEmpty())
    }

    // --- session change clears membership: the point of *this* task's fix --
    //
    // stop() is deliberately not this: a plain stop() (a button press, or
    // run() itself noticing nothing is wanted any more) must leave
    // _convoyId/_notifyingCircleIds alone so a caller-initiated reconnect
    // resumes the same membership - see stop()'s own doc. A *session*
    // change - what run()'s own Auth.sessionEpoch watcher reacts to - is not
    // a reconnect: the membership itself is gone, so it must clear rather
    // than preserve it, or a later setNotifyingCircles call (the
    // notify-circle refresh, driven by the *next* rider's own circles) makes
    // shouldStayConnected() true again with the departed rider's stale
    // _convoyId still sitting there, and the next run() rejoins it - sending
    // the new rider's GPS onto the previous rider's convoy. See
    // clearMembershipForSessionChange's own doc for why this is driven
    // directly here rather than by actually bumping Auth.sessionEpoch.

    @Test
    fun aSessionChangeClearsConvoyScopedDisplayStateAndStopsTheRelay() = testBody {
        val socket = FakeRelaySocket()
        val relay = ConvoyRelay()
        relay.setConvoy("convoy-1")
        relay.setNotifyingCircles(setOf("circle-1"))
        var returnedNormally = false
        val job = launch {
            relay.run(socket, tokenSupplier())
            returnedNormally = true
        }

        socket.connectCount.first { it >= 1 }
        socket.push(joinedFrame())
        relay.connected.first { it }

        // Populate every piece of convoy-scoped display state, so this test
        // proves all of it is cleared - not just membership.
        socket.push(positionsFrame("bob", lat = 51.0, lon = 4.0))
        relay.peers.first { it.containsKey("bob") }
        relay.applyEvent(RelayEvent.PttStart("bob"))
        relay.applyEvent(
            RelayEvent.SpinOffer(
                listOf(SpinCandidate(51.0, 4.0, null, null, "A"), SpinCandidate(52.0, 5.0, null, null, "B")),
            ),
        )
        relay.applyEvent(RelayEvent.SpinVote("bob", 1))

        // What run()'s own sessionEpoch watcher calls once Auth.sessionEpoch
        // actually moves.
        relay.clearMembershipForSessionChange()
        job.join()

        assertTrue(returnedNormally, "a session change should end run() cleanly, same as stop()")
        assertFalse(relay.connected.value)
        assertTrue(relay.peers.value.isEmpty())
        assertTrue(relay.talking.value.isEmpty())
        assertNull(relay.spinOffer.value)
        assertTrue(relay.spinVotes.value.isEmpty())
    }

    @Test
    fun aSessionChangeClearsMembershipSoALaterRunNeverRejoinsTheDepartedConvoy() = testBody {
        val socket = FakeRelaySocket()
        val relay = ConvoyRelay()
        relay.setConvoy("convoy-1")
        relay.setNotifyingCircles(setOf("circle-1"))
        val job = launch { relay.run(socket, tokenSupplier()) }

        socket.connectCount.first { it >= 1 }
        socket.push(joinedFrame())
        relay.connected.first { it }

        relay.clearMembershipForSessionChange()
        job.join()

        // The next rider signs in on this device - the notify-circle refresh
        // calling setNotifyingCircles with *their* circles is what
        // actually fires ensureRunning() again in the real app (see
        // net/ConvoyLiveClient.kt's setNotifyCircles), not a fresh setConvoy
        // call - the failure this test guards against never involves the new
        // rider joining a convoy at all.
        relay.setNotifyingCircles(setOf("circle-b"))
        val socket2 = FakeRelaySocket()
        val job2 = launch { relay.run(socket2, tokenSupplier()) }
        socket2.connectCount.first { it >= 1 }
        socket2.push(joinedFrame())
        relay.connected.first { it }

        // Only what the new rider actually asked for - never convoy-1, which
        // a stale _convoyId left over from the departed session would have
        // rejoined here, broadcasting the new rider's GPS onto it.
        assertEquals(listOf(RelayProtocol.buildJoin("circle-b")), socket2.sent)

        relay.stop()
        job2.join()
    }

    // --- the epoch watcher outliving run(): the point of *this* task's fix -
    //
    // run()'s own Auth.sessionEpoch watcher above is scoped to run()'s own
    // coroutine and is cancelled in run()'s finally the instant run() returns
    // - including an ordinary stop(), the "go offline" button case stop()'s
    // own doc describes, which deliberately preserves membership for its own
    // reconnect. Nothing outside run() was watching the epoch in the tests
    // above either, but every one of them bumps the epoch (via
    // clearMembershipForSessionChange, the direct-call shortcut both take)
    // while run() is still live. The test below is the one case none of them
    // cover: the epoch moving after run() has already returned, with nothing
    // live to react to it - see discardMembershipIfSessionChanged's own doc
    // for why setConvoy/setNotifyingCircles close that window themselves.

    @Test
    fun aSessionChangeWhileRunIsNotLiveIsCaughtByTheNextMembershipCallRatherThanRejoiningTheDepartedConvoy() = testBody {
        val socket = FakeRelaySocket()
        val relay = ConvoyRelay()
        relay.setConvoy("convoy-1")
        val job = launch { relay.run(socket, tokenSupplier()) }

        socket.connectCount.first { it >= 1 }
        socket.push(joinedFrame())
        relay.connected.first { it }

        // The "go offline" button stop()'s own doc describes: an ordinary
        // stop() deliberately preserves membership for a caller-initiated
        // reconnect, unlike a session change. run()'s own sessionWatcher is
        // cancelled the instant run() returns either way - nothing is
        // watching the epoch from here until something calls setConvoy/
        // setNotifyingCircles again.
        relay.stop()
        job.join()
        assertEquals("convoy-1", relay.convoyId.value, "stop() must still preserve membership on its own")

        // The rider signs out - Auth.sessionEpoch moves - while run() is not
        // live at all to react to it. Simulated directly rather than by
        // actually bumping the real Auth.sessionEpoch, the same shortcut
        // clearMembershipForSessionChange's own tests take above - see
        // membershipEpoch's own doc for why: this module's tests stay
        // isolated from the real Auth/Settings singletons. run() has already
        // stamped membershipEpoch once by this point (it calls
        // discardMembershipIfSessionChanged too - see run()'s own doc), so
        // this is never null here.
        relay.membershipEpoch = requireNotNull(relay.membershipEpoch) - 1

        // The next rider's own notify-circle refresh - setNotifyingCircles,
        // not a fresh setConvoy call - is what actually rejoins a stale convoy in
        // the real leak (see the class doc's Auth.sessionEpoch paragraph).
        relay.setNotifyingCircles(setOf("circle-b"))

        assertNull(relay.convoyId.value, "a stale convoy must not survive the next rider's own membership call")

        val socket2 = FakeRelaySocket()
        val job2 = launch { relay.run(socket2, tokenSupplier()) }
        socket2.connectCount.first { it >= 1 }
        socket2.push(joinedFrame())
        relay.connected.first { it }

        // Only what the new rider actually asked for - never convoy-1, which
        // a stale _convoyId would have rejoined here had it survived.
        assertEquals(listOf(RelayProtocol.buildJoin("circle-b")), socket2.sent)

        relay.stop()
        job2.join()
    }
}

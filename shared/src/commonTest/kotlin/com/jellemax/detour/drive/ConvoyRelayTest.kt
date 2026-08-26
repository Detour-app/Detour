package com.jellemax.detour.drive

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
 * The spin-vote rule itself ([SpinRoundOutcome]) is a straight port of
 * `app/.../map/GroupSpinRules.kt`, which already has its own pure-function
 * test (`GroupSpinRulesTest.kt`) covering `leadingSpinIndex`/`resolveSpinRound`
 * in isolation - tie-breaks, pruned-peer edge cases, and so on. This file
 * does not re-derive those; it covers the property that test suite never
 * could, because it needs two devices: that a relay driven entirely off its
 * own state reaches the identical outcome as a second relay with a
 * *different* peer set, given the same wire frames.
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

    private fun tokenSupplier(token: String = "test-token"): suspend () -> String = { token }

    // --- connection lifecycle, against the fake socket ---------------------

    @Test
    fun joiningEmitsAJoinFrameCarryingTheGroupId() = runBlocking {
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
    fun positionsFramePopulatesPeersAndALaterOneForTheSamePeerReplaces() = runBlocking {
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
    fun stopEndsRunWithoutCancellingItsCoroutine() = runBlocking {
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
    fun aSocketThatClosesUnexpectedlyReconnectsWithBackoffAndConnectedReflectsIt() = runBlocking {
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
    fun lastErrorReportsWhenTheRelayCannotBeReached() = runBlocking {
        val socket = FakeRelaySocket()
        socket.connectFailsWith = Exception("connection refused")
        val relay = ConvoyRelay()
        relay.setConvoy("convoy-1")
        val job = launch { relay.run(socket, tokenSupplier()) }

        val error = relay.lastError.first { it != null }
        assertEquals("Can't reach the live server: connection refused", error)

        relay.stop()
        job.join()
    }

    @Test
    fun lastErrorStaysNullWhenJoinedButNobodyIsSendingAnything() = runBlocking {
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
    fun anErrorFrameSetsLastErrorAndEndsTheAttemptWithoutCrashing() = runBlocking {
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

    // --- additive membership: the point of this task's fix -----------------
    //
    // The socket serves a convoy and any number of circles at once - what a
    // required `groupId` parameter on run() made impossible to express at
    // all. setConvoy/setNotifyingCircles are state, independent of run()'s
    // own lifecycle: set before run() starts (as CircleNotifyService and
    // MapScreen each do today) or changed while it is live.

    @Test
    fun circlesAloneKeepTheSocketConnectedWithNoConvoyJoined() = runBlocking {
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
    fun aConvoyAndCirclesEachJoinAsTheirOwnFrameOnConnect() = runBlocking {
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
    fun addingACircleWhileConnectedJoinsOnTheLiveSocketWithoutReopening() = runBlocking {
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
    fun addingAConvoyWhileConnectedForCirclesJoinsOnTheLiveSocketWithoutReopening() = runBlocking {
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
    fun anAdditionBeforeTheJoinedReplyArrivesIsSentRatherThanLost() = runBlocking {
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
    fun removingACircleWhileConnectedReopensTheSocketAndRejoinsExactlyTheRemainder() = runBlocking {
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
    fun switchingConvoysReopensTheSocketAndJoinsOnlyTheNewOne() = runBlocking {
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
    fun leavingTheConvoyWhileCirclesRemainReopensTheSocketAndClearingBothLetsRunFinish() = runBlocking {
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
}

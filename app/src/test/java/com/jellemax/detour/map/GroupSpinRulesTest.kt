package com.jellemax.detour.map

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [leadingSpinIndex], [expectedSpinVoters] and [resolveSpinRound] - how
 * a convoy's shared spin resolves to one destination. The failure these rules
 * exist to prevent is a convoy splitting across two destinations, and until
 * this file the argument for why they prevent it existed only as a comment.
 * Read the comment above [resolveSpinRound] first; these tests pin what it
 * claims.
 *
 * Pure rules, plain JUnit4: no Android APIs, so no emulator/Robolectric needed.
 * Note that `MapScreen.kt`'s vote-round effect is deliberately *not* wired to
 * [resolveSpinRound] - verifying the convoy path needs two devices, so the
 * stage-2 plan extracts and tests the rule without moving the call site.
 */
class GroupSpinRulesTest {

    @Test
    fun anEmptyVoteMapLeadsWithTheFirstCandidate() {
        assertEquals(0, leadingSpinIndex(emptyMap(), candidateCount = 3))
    }

    @Test
    fun theMostVotedCandidateLeads() {
        assertEquals(2, leadingSpinIndex(mapOf("a" to 2, "b" to 2, "c" to 1), candidateCount = 3))
    }

    /** A tie goes to the lowest index, on every device, without anyone having to
     *  agree on who voted first. */
    @Test
    fun tiesGoToTheLowestIndex() {
        assertEquals(0, leadingSpinIndex(mapOf("a" to 0, "b" to 2), candidateCount = 3))
        assertEquals(1, leadingSpinIndex(mapOf("a" to 1, "b" to 2), candidateCount = 3))
    }

    /** A vote for a candidate this device does not have - a frame from a newer
     *  offer, an index past the end - is ignored rather than breaking the tally. */
    @Test
    fun votesOutsideTheCandidateRangeAreIgnored() {
        assertEquals(0, leadingSpinIndex(mapOf("a" to 7, "b" to -1, "c" to 0), candidateCount = 3))
    }

    /** A blank username means not signed in, and cannot appear in the vote map's
     *  keys, so the round must not wait for it. */
    @Test
    fun aSignedOutDeviceIsNotWaitedFor() {
        assertEquals(setOf("alice"), expectedSpinVoters(setOf("alice"), myUsername = ""))
        assertEquals(setOf("alice", "me"), expectedSpinVoters(setOf("alice"), myUsername = "me"))
    }

    /** A one-candidate offer is the sharer announcing the winner. Everyone
     *  commits it - votes or no votes, sharer or receiver. */
    @Test
    fun aOneCandidateOfferCommitsRegardlessOfVotes() {
        assertEquals(
            SpinRoundOutcome.CommitOnly,
            resolveSpinRound(candidateCount = 1, fromMe = false,
                expected = setOf("alice", "me"), votes = emptyMap()),
        )
        assertEquals(
            SpinRoundOutcome.CommitOnly,
            resolveSpinRound(candidateCount = 1, fromMe = true,
                expected = setOf("me"), votes = mapOf("me" to 0)),
        )
    }

    @Test
    fun theSharerClosesTheRoundOnceEveryoneHasVoted() {
        assertEquals(
            SpinRoundOutcome.CloseRound(leadIndex = 1),
            resolveSpinRound(
                candidateCount = 3,
                fromMe = true,
                expected = setOf("me", "alice"),
                votes = mapOf("me" to 1, "alice" to 1),
            ),
        )
    }

    @Test
    fun theSharerWaitsWhileAVoteIsStillOut() {
        assertEquals(
            SpinRoundOutcome.Wait,
            resolveSpinRound(3, fromMe = true, expected = setOf("me", "alice"), votes = mapOf("me" to 1)),
        )
    }

    /** The whole point of `fromMe`: a receiving device never resolves the votes
     *  itself, however complete its own view looks. */
    @Test
    fun aReceivingDeviceNeverClosesARound() {
        assertEquals(
            SpinRoundOutcome.Wait,
            resolveSpinRound(3, fromMe = false, expected = setOf("me", "alice"),
                votes = mapOf("me" to 2, "alice" to 2)),
        )
    }

    /**
     * A peer pruned mid-round - quiet for 20 s and dropped from
     * `ConvoyLiveClient.peers` - shrinks `expected` on whichever device noticed
     * first. That divergence is exactly what could close one round on two
     * different candidates, and it cannot, because only the sharer closes
     * anything and there is exactly one sharer. Both halves are pinned: the
     * receiver with the *shorter* list still waits, and the sharer still
     * expecting the pruned peer keeps the round open for everyone.
     */
    @Test
    fun aPrunedPeerCannotCompleteTheRoundEarly() {
        assertEquals(
            SpinRoundOutcome.Wait,
            resolveSpinRound(3, fromMe = false, expected = setOf("me", "alice"),
                votes = mapOf("me" to 0, "alice" to 2)),
        )
        assertEquals(
            SpinRoundOutcome.Wait,
            resolveSpinRound(3, fromMe = true, expected = setOf("me", "alice", "bob"),
                votes = mapOf("me" to 0, "alice" to 2)),
        )
    }

    /** No known live voter - not even this device's own username - is not a
     *  complete round. Closing on zero votes would commit an arbitrary candidate
     *  for the whole convoy. */
    @Test
    fun anEmptyExpectedSetNeverClosesARound() {
        assertEquals(
            SpinRoundOutcome.Wait,
            resolveSpinRound(3, fromMe = true, expected = emptySet(), votes = emptyMap()),
        )
    }
}

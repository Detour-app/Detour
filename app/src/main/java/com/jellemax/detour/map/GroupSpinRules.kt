package com.jellemax.detour.map

/** Tie-break rule for a group spin's leader: ties (including "nobody's voted
 *  yet", every count 0) go to the lowest index. `>` rather than `>=` is what
 *  makes that deterministic - every device tallying the same votes lands on
 *  the same leader without needing to compare who voted when. */
internal fun leadingSpinIndex(votes: Map<String, Int>, candidateCount: Int): Int {
    val counts = IntArray(candidateCount)
    votes.values.forEach { if (it in counts.indices) counts[it]++ }
    var lead = 0
    for (i in 1 until candidateCount) if (counts[i] > counts[lead]) lead = i
    return lead
}

/** What a device should do about the offer currently on the table. */
internal sealed interface SpinRoundOutcome {
    /** Nothing: not this device's round to close, or votes still out. */
    data object Wait : SpinRoundOutcome
    /** This offer *is* the decision - commit its only candidate. */
    data object CommitOnly : SpinRoundOutcome
    /** Every expected voter has voted and this device opened the round: re-offer
     *  [leadIndex] on its own, which is what commits it everywhere. */
    data class CloseRound(val leadIndex: Int) : SpinRoundOutcome
}

/** Who a round waits for: everyone currently live in the convoy, plus this
 *  device, which votes too. A blank [myUsername] means not signed in and can
 *  never appear in the vote map's keys, so it is not waited for. */
internal fun expectedSpinVoters(peerUsernames: Set<String>, myUsername: String): Set<String> =
    peerUsernames + setOfNotNull(myUsername.takeIf { it.isNotBlank() })

// How a vote round ends. Two halves, and which one runs depends on
// whether this device opened the round (see GroupSpin.fromMe):
//
//  - Anyone receiving a one-candidate offer commits it. That offer *is*
//    the decision, so every member lands on the same destination off the
//    same frame instead of each resolving the votes themselves.
//  - The sharer, once everyone currently live (convoyPeers plus itself)
//    has voted, sends the leader back out as exactly that one-candidate
//    offer — which then commits here too, through the branch above.
//
// Tallying independently on each device would have been simpler and
// wrong: convoyPeers prunes a member who's been quiet for 20s, so one
// phone can consider the round complete on two votes while another is
// still waiting for a third, and the two can resolve to different
// candidates. Splitting a convoy across two destinations is the exact
// failure this feature exists to prevent.
internal fun resolveSpinRound(
    candidateCount: Int,
    fromMe: Boolean,
    expected: Set<String>,
    votes: Map<String, Int>,
): SpinRoundOutcome {
    if (candidateCount <= 0) return SpinRoundOutcome.Wait
    if (candidateCount == 1) return SpinRoundOutcome.CommitOnly
    if (!fromMe) return SpinRoundOutcome.Wait
    if (expected.isEmpty() || !votes.keys.containsAll(expected)) return SpinRoundOutcome.Wait
    return SpinRoundOutcome.CloseRound(leadingSpinIndex(votes, candidateCount))
}

package com.jellemax.detour.presentation

import com.jellemax.detour.data.FriendLists
import com.jellemax.detour.data.FriendStats
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.data.RiderRef

/**
 * One ranked leaderboard row, ready to render: the raw [FriendStats] pair is
 * gone by this point, replaced with exactly the strings and flag a row draws.
 */
data class LeaderboardRow(
    val riderId: RiderId,
    val username: String,
    val avatarInitial: String,
    val distanceLabel: String,
    val statsLine: String,
    val isMe: Boolean,
)

/**
 * The Friends screen's display state: the ranked leaderboard and the two
 * request lists, with the "Waiting on: …" summary line pre-joined.
 *
 * Named [FriendsBoardState], not `FriendsState` — `com.jellemax.detour.data.FriendsStore`
 * already owns that name for its own load/busy/error/`own` state (see
 * FriendsStore.kt:16), and the two must never be confused: this is the pure,
 * callable-with-literals *display* shape produced by [friendsBoardStateFrom];
 * that one is the mutable, network-backed source of truth a screen collects
 * directly. See [FriendsPresenter]'s KDoc for why this type carries no
 * busy/error/loaded fields of its own.
 */
data class FriendsBoardState(
    val rows: List<LeaderboardRow> = emptyList(),
    val incoming: List<RiderRef> = emptyList(),
    val outgoing: List<RiderRef> = emptyList(),
    val waitingOnLabel: String? = null,
)

/**
 * Pure map from the store's raw pieces to [FriendsBoardState]. Takes the
 * already-loaded pieces rather than the store's nullable `FriendsState`
 * wholesale — [lists] is non-null here on purpose, matching how the old
 * screen only ever built the leaderboard after its own `state.lists == null`
 * loading gate had passed. No I/O, callable with literals.
 *
 * Ranking: distance descending, with [own] (when present) merged into the
 * same ranking as every friend rather than pinned to a fixed position — a
 * rider a fraction behind their friends still ranks below them. Ties (equal
 * `totalDistanceMeters`, e.g. two friends who have not ridden yet) break on
 * username, case-insensitively, then on rider id. `Friends.stats()`'s wire
 * order carries no ordering guarantee, and without a deterministic tiebreaker
 * two tied riders would swap rows on any refresh whose response happened to
 * come back reshuffled — a leaderboard that visibly jumps for no reason a
 * rider could see.
 */
fun friendsBoardStateFrom(
    leaderboard: List<FriendStats>,
    own: FriendStats?,
    lists: FriendLists,
): FriendsBoardState {
    val entries = leaderboard.map { it to false } + listOfNotNull(own?.let { it to true })
    val rows = entries
        .sortedWith(
            compareByDescending<Pair<FriendStats, Boolean>> { it.first.stats.totalDistanceMeters }
                .thenBy { it.first.rider.username.lowercase() }
                .thenBy { it.first.rider.id.value }
        )
        .map { (stats, isMe) -> stats.toRow(isMe) }
    return FriendsBoardState(
        rows = rows,
        incoming = lists.incoming,
        outgoing = lists.outgoing,
        waitingOnLabel = lists.outgoing.takeIf { it.isNotEmpty() }
            ?.joinToString(", ") { it.username }
            ?.let { "Waiting on: $it" },
    )
}

private fun FriendStats.toRow(isMe: Boolean) = LeaderboardRow(
    riderId = rider.id,
    username = rider.username,
    avatarInitial = avatarInitialOf(rider.username),
    distanceLabel = "${groupThousands(formatFixed(stats.totalDistanceMeters / 1000.0, 0).toLong())} km",
    statsLine = "${stats.tripCount} rides · ${badgeIds.size} badges",
    isMe = isMe,
)

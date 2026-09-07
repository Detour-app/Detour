package com.jellemax.detour.presentation

import com.jellemax.detour.data.FriendLists
import com.jellemax.detour.data.FriendStats
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.data.RiderRef
import com.jellemax.detour.data.RiderStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The pure mapping behind the Friends screen: the ranked leaderboard, its
 * per-row strings, and the "Waiting on: …" summary. None of this had a test
 * before this file — see FriendsStore.kt for the mutable store this reads
 * pieces of, and FriendsPresenter for the thin load kick that feeds it.
 */
class FriendsStateTest {

    private fun friend(
        id: String,
        username: String,
        distanceMeters: Double = 0.0,
        trips: Int = 0,
        badges: Int = 0,
    ) = FriendStats(
        rider = RiderRef(RiderId(id), username),
        stats = RiderStats(totalDistanceMeters = distanceMeters, tripCount = trips),
        badgeIds = List(badges) { "badge$it" },
    )

    private fun lists(
        incoming: List<RiderRef> = emptyList(),
        outgoing: List<RiderRef> = emptyList(),
    ) = FriendLists(friends = emptyList(), incoming = incoming, outgoing = outgoing)

    @Test fun rowsSortByDistanceDescending() {
        val board = friendsBoardStateFrom(
            leaderboard = listOf(
                friend("1", "ada", distanceMeters = 5_000.0),
                friend("2", "bob", distanceMeters = 15_000.0),
            ),
            own = null,
            lists = lists(),
        )
        assertEquals(listOf("bob", "ada"), board.rows.map { it.username })
    }

    @Test fun theRidersOwnRowIsIncludedAndFlagged() {
        val board = friendsBoardStateFrom(
            leaderboard = listOf(friend("1", "ada", distanceMeters = 5_000.0)),
            own = friend("me", "mika", distanceMeters = 10_000.0),
            lists = lists(),
        )
        assertEquals(true, board.rows.first { it.username == "mika" }.isMe)
        assertEquals(false, board.rows.first { it.username == "ada" }.isMe)
    }

    @Test fun rowsHoldOnlyTheOwnRowWhenThereAreNoFriends() {
        // Regression case: FriendsScreen's empty-state gate must read "no
        // friends" (rows.none { !it.isMe }), not "no rows" (rows.isEmpty()).
        // `own` alone always keeps `rows` non-empty for any signed-in rider,
        // friends or not, so the latter predicate never fires and the "No
        // friends yet" nudge — the app's only copy of the privacy promise —
        // was unreachable. See FriendsScreen.kt's leaderboard gate.
        val board = friendsBoardStateFrom(
            leaderboard = emptyList(),
            own = friend("me", "mika", distanceMeters = 10_000.0),
            lists = lists(),
        )
        assertEquals(1, board.rows.size)
        assertEquals(true, board.rows.single().isMe)
        assertEquals(true, board.rows.none { !it.isMe })
    }

    @Test fun tiedDistancesBreakByUsernameCaseInsensitively() {
        // Same distance, different names — order must not depend on the
        // server's response order, or the board would jump on every refresh
        // that happened to come back reshuffled.
        val board = friendsBoardStateFrom(
            leaderboard = listOf(
                friend("2", "Zara", distanceMeters = 1_000.0),
                friend("1", "ada", distanceMeters = 1_000.0),
            ),
            own = null,
            lists = lists(),
        )
        assertEquals(listOf("ada", "Zara"), board.rows.map { it.username })
    }

    @Test fun tiedDistanceAndUsernameBreakByRiderId() {
        val board = friendsBoardStateFrom(
            leaderboard = listOf(
                friend("b", "ada", distanceMeters = 1_000.0),
                friend("a", "ada", distanceMeters = 1_000.0),
            ),
            own = null,
            lists = lists(),
        )
        assertEquals(listOf("a", "b"), board.rows.map { it.riderId.value })
    }

    @Test fun aFriendWithZeroDistanceStillRenders() {
        val board = friendsBoardStateFrom(
            leaderboard = listOf(friend("1", "ada", distanceMeters = 0.0)),
            own = null,
            lists = lists(),
        )
        assertEquals("0 km", board.rows.single().distanceLabel)
    }

    @Test fun theDistanceLabelIsThousandsGroupedWithAPlainAsciiSpace() {
        val board = friendsBoardStateFrom(
            leaderboard = listOf(friend("1", "ada", distanceMeters = 12_480_000.0)),
            own = null,
            lists = lists(),
        )
        assertEquals("12 480 km", board.rows.single().distanceLabel)
    }

    @Test fun theStatsLineReadsRideCountThenBadgeCount() {
        val board = friendsBoardStateFrom(
            leaderboard = listOf(friend("1", "ada", trips = 7, badges = 3)),
            own = null,
            lists = lists(),
        )
        assertEquals("7 rides · 3 badges", board.rows.single().statsLine)
    }

    @Test fun theAvatarInitialComesFromTheSharedHelper() {
        val board = friendsBoardStateFrom(
            leaderboard = listOf(friend("1", "ada")),
            own = null,
            lists = lists(),
        )
        assertEquals("A", board.rows.single().avatarInitial)
    }

    @Test fun aLeadingSpaceUsernameStillYieldsItsRealInitial() {
        val board = friendsBoardStateFrom(
            leaderboard = listOf(friend("1", " mika")),
            own = null,
            lists = lists(),
        )
        assertEquals("M", board.rows.single().avatarInitial)
    }

    @Test fun aBlankUsernameFallsBackToAQuestionMark() {
        val board = friendsBoardStateFrom(
            leaderboard = listOf(friend("1", "   ")),
            own = null,
            lists = lists(),
        )
        assertEquals("?", board.rows.single().avatarInitial)
    }

    @Test fun waitingOnJoinsOutgoingUsernames() {
        val board = friendsBoardStateFrom(
            leaderboard = emptyList(),
            own = null,
            lists = lists(outgoing = listOf(RiderRef(RiderId("1"), "ada"), RiderRef(RiderId("2"), "bob"))),
        )
        assertEquals("Waiting on: ada, bob", board.waitingOnLabel)
    }

    @Test fun waitingOnIsAbsentWhenThereAreNoOutgoingRequests() {
        val board = friendsBoardStateFrom(leaderboard = emptyList(), own = null, lists = lists())
        assertNull(board.waitingOnLabel)
    }

    @Test fun incomingAndOutgoingMapToRequestRowsById() {
        val incoming = listOf(RiderRef(RiderId("3"), "cleo"))
        val outgoing = listOf(RiderRef(RiderId("4"), "dev"))
        val board = friendsBoardStateFrom(
            leaderboard = emptyList(), own = null, lists = lists(incoming = incoming, outgoing = outgoing),
        )
        assertEquals(listOf(FriendRequestRow(RiderId("3"), "cleo")), board.incoming)
        assertEquals(listOf(FriendRequestRow(RiderId("4"), "dev")), board.outgoing)
    }
}

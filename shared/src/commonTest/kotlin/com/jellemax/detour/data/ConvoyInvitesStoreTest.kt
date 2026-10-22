package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The host's invite sheet state (#761): which link can be shared, what a mint or a guest re-read
 * commits, and who counts as host.
 */
class ConvoyInvitesStoreTest {

    private val now = 1_791_090_000_000L
    private val link = ConvoyInvite(token = "q9X_a-0bC", expiresAtMs = now + 3_600_000L)
    private val guest = ConvoyGuest(RiderRef(RiderId("g1"), "eva"), joinedAtMs = now - 60_000L)

    private fun state(guests: ConvoyGuests?) =
        ConvoyInvitesState(convoyId = "c1", guests = guests, minted = mapOf("c1" to link))

    @Test
    fun aLinkMintedHereIsShareableWhileTheServerStillHasIt() {
        assertEquals(link, state(ConvoyGuests(link.expiresAtMs, emptyList())).shareableLink(now))
    }

    @Test
    fun aMintedLinkIsShareableBeforeTheGuestListHasLoaded() {
        assertEquals(link, state(guests = null).shareableLink(now))
    }

    @Test
    fun aLinkTheServerNoLongerHasIsNotShareable() {
        assertNull(state(ConvoyGuests(inviteExpiresAtMs = null, guests = emptyList())).shareableLink(now))
    }

    @Test
    fun aLinkReplacedFromAnotherDeviceIsNotShareable() {
        val replaced = ConvoyGuests(inviteExpiresAtMs = link.expiresAtMs + 60_000L, guests = emptyList())

        assertNull(state(replaced).shareableLink(now))
    }

    @Test
    fun anExpiredLinkIsNotShareable() {
        assertNull(state(guests = null).shareableLink(link.expiresAtMs))
    }

    @Test
    fun anotherConvoysLinkIsNotShareable() {
        assertNull(state(guests = null).copy(convoyId = "c2").shareableLink(now))
    }

    @Test
    fun openingAnotherConvoyDropsTheGuestsButKeepsEveryMintedLink() {
        val opened = state(ConvoyGuests(link.expiresAtMs, listOf(guest))).copy(error = "x").opened("c2")

        assertEquals("c2", opened.convoyId)
        assertNull(opened.guests)
        assertNull(opened.error)
        assertEquals(mapOf("c1" to link), opened.minted)
    }

    @Test
    fun reopeningTheSameConvoyKeepsItsGuestsOnScreen() {
        val open = state(ConvoyGuests(link.expiresAtMs, listOf(guest)))

        assertSame(open, open.opened("c1"))
    }

    @Test
    fun aCommitForTheOpenConvoyAppliesTheChangeAndTheAnswer() {
        val loaded = ConvoyGuests(link.expiresAtMs, listOf(guest))
        val committed = ConvoyInvitesState(convoyId = "c1", busy = true).committed(
            sameSession = true,
            convoyId = "c1",
            change = { it.copy(minted = it.minted + ("c1" to link)) },
            answer = { it.copy(guests = loaded, busy = false) },
        )

        assertEquals(mapOf("c1" to link), committed.minted)
        assertEquals(loaded, committed.guests)
        assertFalse(committed.busy)
    }

    @Test
    fun aMintLandingAfterTheSheetMovedOnKeepsItsTokenButNotItsGuests() {
        val other = ConvoyInvitesState(convoyId = "c2", busy = true)
        val committed = other.committed(
            sameSession = true,
            convoyId = "c1",
            change = { it.copy(minted = it.minted + ("c1" to link)) },
            answer = { it.copy(guests = ConvoyGuests(link.expiresAtMs, listOf(guest)), busy = false) },
        )

        assertEquals(mapOf("c1" to link), committed.minted)
        assertNull(committed.guests)
        assertTrue(committed.busy, "c2's own load still owns busy")
    }

    @Test
    fun nothingCommitsAfterASignOut() {
        val cleared = ConvoyInvitesState()
        val committed = cleared.committed(
            sameSession = false,
            convoyId = "c1",
            change = { it.copy(minted = it.minted + ("c1" to link)) },
            answer = { it.copy(error = "boom") },
        )

        assertSame(cleared, committed)
    }

    @Test
    fun onlyTheOwnerIsHost() {
        val host = RiderId("h1")
        val convoy = Group("c1", "Ride", "convoy", "accepted", emptyList(), ownerId = host)

        assertTrue(convoy.isOwnedBy(host))
        assertFalse(convoy.isOwnedBy(RiderId("g1")))
    }

    @Test
    fun aBlankRiderOrAnUnknownOwnerIsNeverHost() {
        val ownerless = Group("c1", "Ride", "convoy", "accepted", emptyList(), ownerId = null)

        assertFalse(ownerless.isOwnedBy(RiderId("")))
        assertFalse(ownerless.copy(ownerId = RiderId("")).isOwnedBy(RiderId("")))
        assertFalse(ownerless.isOwnedBy(RiderId("h1")))
    }
}

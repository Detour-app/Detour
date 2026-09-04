package com.jellemax.detour.presentation

import com.jellemax.detour.data.CirclePlace
import com.jellemax.detour.data.Group
import com.jellemax.detour.data.GroupMember
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.PlaceEvent
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.data.SavedPlace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pure mapping behind the circle-detail pane: the member-row suffixes,
 * shared-place lines and event rows `CircleDetailSection` has always built by
 * hand. See CirclesStore.kt for the mutable store this reads places/events
 * from, and CircleDetailPresenter for the thin load kick that feeds it.
 */
class CircleDetailStateTest {

    private val me = RiderId("me")

    private fun member(
        id: String,
        username: String,
        status: String = "accepted",
        sharing: Boolean = true,
    ) = GroupMember(id = RiderId(id), username = username, status = status, sharing = sharing)

    private fun circle(
        id: String = "c1",
        name: String = "Family",
        members: List<GroupMember> = emptyList(),
    ) = Group(id = id, name = name, kind = "circle", status = "accepted", members = members)

    private fun place(
        serverId: String = "p1",
        ownerId: RiderId,
        radiusM: Double = 150.0,
        placeId: Long = 1L,
        placeName: String = "Home",
    ) = CirclePlace(
        serverId = serverId,
        groupId = "c1",
        ownerId = ownerId,
        radiusM = radiusM,
        createdMs = 0L,
        place = SavedPlace(id = placeId, name = placeName, location = LatLon(0.0, 0.0)),
    )

    private fun event(
        placeId: Long = 1L,
        riderId: RiderId,
        kind: String = "arrive",
        tsMs: Long = 0L,
    ) = PlaceEvent(id = "e1", placeId = placeId, placeName = "", riderId = riderId, kind = kind, tsMs = tsMs)

    @Test fun memberSuffixIsYouForTheRiderThemselves() {
        val state = circleDetailStateFrom(
            circle(members = listOf(member("me", "rider"))),
            riderId = me, places = emptyList(), events = emptyList(), nowMs = 0L,
        )
        assertEquals("rider (you)", state.members.single().displayName)
    }

    @Test fun memberSuffixIsInvitedForAnInvitedMember() {
        val state = circleDetailStateFrom(
            circle(members = listOf(member("bob", "bob", status = "invited"))),
            riderId = me, places = emptyList(), events = emptyList(), nowMs = 0L,
        )
        assertEquals("bob · invited", state.members.single().displayName)
    }

    @Test fun memberSuffixIsBlankForAPlainAcceptedMember() {
        val state = circleDetailStateFrom(
            circle(members = listOf(member("bob", "bob"))),
            riderId = me, places = emptyList(), events = emptyList(), nowMs = 0L,
        )
        assertEquals("bob", state.members.single().displayName)
    }

    @Test fun memberRowCarriesItsSharingFlagUnchanged() {
        val state = circleDetailStateFrom(
            circle(members = listOf(member("bob", "bob", sharing = false))),
            riderId = me, places = emptyList(), events = emptyList(), nowMs = 0L,
        )
        assertFalse(state.members.single().sharing)
    }

    @Test fun sharedPlaceLineNamesTheOwnersHandleAndTheRadiusInMetres() {
        val owner = RiderId("owner")
        val state = circleDetailStateFrom(
            circle(members = listOf(member("owner", "rider"), member("me", "mover"))),
            riderId = me,
            places = listOf(place(ownerId = owner, radiusM = 150.0, placeName = "Home")),
            events = emptyList(), nowMs = 0L,
        )
        val row = state.places.single()
        assertEquals("Home", row.name)
        assertEquals("Shared by rider · 150 m radius", row.subtitle)
    }

    @Test fun theSharedPlaceIsRemovableForItsOwner() {
        val state = circleDetailStateFrom(
            circle(members = listOf(member("me", "mover"))),
            riderId = me,
            places = listOf(place(ownerId = me)),
            events = emptyList(), nowMs = 0L,
        )
        assertTrue(state.places.single().removable)
    }

    @Test fun theSharedPlaceIsNotRemovableForAnotherMember() {
        val state = circleDetailStateFrom(
            circle(members = listOf(member("me", "mover"))),
            riderId = me,
            places = listOf(place(ownerId = RiderId("owner"))),
            events = emptyList(), nowMs = 0L,
        )
        assertFalse(state.places.single().removable)
    }

    @Test fun anEventWhosePlaceHasSinceBeenRemovedFallsBackToASinceRemovedPlace() {
        val state = circleDetailStateFrom(
            circle(members = listOf(member("mover", "mover"))),
            riderId = me,
            places = emptyList(),
            events = listOf(event(placeId = 99L, riderId = RiderId("mover"), kind = "arrive", tsMs = 0L)),
            nowMs = 0L,
        )
        assertEquals("mover arrived at a since-removed place — just now", state.events.single().text)
    }

    @Test fun anArriveEventUsesArrivedAtWording() {
        val state = circleDetailStateFrom(
            circle(members = listOf(member("mover", "mover"))),
            riderId = me,
            places = listOf(place(placeId = 1L, ownerId = me, placeName = "Home")),
            events = listOf(event(placeId = 1L, riderId = RiderId("mover"), kind = "arrive", tsMs = 0L)),
            nowMs = 0L,
        )
        assertEquals("mover arrived at Home — just now", state.events.single().text)
    }

    @Test fun aDepartEventUsesLeftWording() {
        val state = circleDetailStateFrom(
            circle(members = listOf(member("mover", "mover"))),
            riderId = me,
            places = listOf(place(placeId = 1L, ownerId = me, placeName = "Home")),
            events = listOf(event(placeId = 1L, riderId = RiderId("mover"), kind = "depart", tsMs = 0L)),
            nowMs = 0L,
        )
        assertEquals("mover left Home — just now", state.events.single().text)
    }

    @Test fun eventRelativeAgeUsesTheSuppliedNowMsNeverAClock() {
        val nowMs = 2 * 60 * 60 * 1000L
        val state = circleDetailStateFrom(
            circle(members = listOf(member("mover", "mover"))),
            riderId = me,
            places = listOf(place(placeId = 1L, ownerId = me, placeName = "Home")),
            events = listOf(event(placeId = 1L, riderId = RiderId("mover"), kind = "arrive", tsMs = 0L)),
            nowMs = nowMs,
        )
        assertEquals("mover arrived at Home — 2h ago", state.events.single().text)
    }

    @Test fun anEmptyCircleWithJustTheRiderProducesAUsableState() {
        val state = circleDetailStateFrom(
            circle(members = listOf(member("me", "rider"))),
            riderId = me, places = emptyList(), events = emptyList(), nowMs = 0L,
        )
        assertEquals(1, state.members.size)
        assertEquals("rider (you)", state.members.single().displayName)
        assertTrue(state.places.isEmpty())
        assertTrue(state.events.isEmpty())
    }
}

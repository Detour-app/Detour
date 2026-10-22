package com.jellemax.detour.presentation

import com.jellemax.detour.data.Group
import com.jellemax.detour.data.GroupMember
import com.jellemax.detour.data.MeetingPoint
import com.jellemax.detour.data.PlannedRide
import com.jellemax.detour.data.PlannedRideInvitee
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.data.RiderRef
import com.jellemax.detour.data.RsvpAnswer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlannedRideStateTest {

    private val host = RiderRef(RiderId("host"), "jelle")
    private val me = RiderId("me")

    private fun ride(
        owner: RiderRef = host,
        myAnswer: RsvpAnswer? = RsvpAnswer.PENDING,
        invitees: List<PlannedRideInvitee> = emptyList(),
    ) = PlannedRide(
        id = "r1",
        owner = owner,
        title = "Sunday Ardennes",
        startsAtMs = 1_791_100_800_000,
        meetingPoint = MeetingPoint(50.85, 4.35, label = null),
        routeId = null,
        description = "Coffee first",
        createdAtMs = 1_790_000_000_000,
        myAnswer = myAnswer,
        invitees = invitees,
    )

    private fun invitee(name: String, answer: RsvpAnswer) = PlannedRideInvitee(RiderRef(RiderId(name), name), answer)

    @Test
    fun anInviteeRowCarriesTheirOwnAnswer() {
        val row = plannedRideRowsFrom(listOf(ride(myAnswer = RsvpAnswer.MAYBE)), me).single()
        assertEquals("jelle", row.hostName)
        assertFalse(row.hosting)
        assertEquals(RsvpAnswer.MAYBE, row.myAnswer)
    }

    @Test
    fun theOwnersRowIsHostingWithNoAnswer() {
        val row = plannedRideRowsFrom(listOf(ride(owner = RiderRef(me, "me"), myAnswer = null)), me).single()
        assertTrue(row.hosting)
        assertNull(row.myAnswer)
    }

    @Test
    fun aBlankRiderIdHostsNothingEvenOnABlankOwnerRide() {
        val blank = RiderId("")
        val row = plannedRideRowsFrom(listOf(ride(owner = RiderRef(blank, ""))), blank).single()
        assertFalse(row.hosting)
    }

    @Test
    fun rowsKeepTheServersOrder() {
        val rides = listOf(ride().copy(id = "b"), ride().copy(id = "a"))
        assertEquals(listOf("b", "a"), plannedRideRowsFrom(rides, me).map { it.id })
    }

    @Test
    fun inviteesAreGroupedComingFirstThenByName() {
        val detail = plannedRideDetailStateFrom(
            ride(
                invitees = listOf(
                    invitee("zoe", RsvpAnswer.DECLINED),
                    invitee("eva", RsvpAnswer.PENDING),
                    invitee("Bram", RsvpAnswer.GOING),
                    invitee("max", RsvpAnswer.MAYBE),
                    invitee("anna", RsvpAnswer.GOING),
                ),
            ),
            me,
        )
        assertEquals(listOf("anna", "Bram", "max", "eva", "zoe"), detail.invitees.map { it.name })
    }

    @Test
    fun anInviteeMayAnswerGoingMaybeOrCantButNotPending() {
        val detail = plannedRideDetailStateFrom(ride(), me)
        assertTrue(detail.canAnswer)
        assertEquals(listOf(RsvpAnswer.GOING, RsvpAnswer.MAYBE, RsvpAnswer.DECLINED), detail.answers)
        assertEquals(RsvpAnswer.PENDING, detail.myAnswer)
    }

    @Test
    fun theOwnerGetsNoAnswerButtons() {
        val detail = plannedRideDetailStateFrom(ride(owner = RiderRef(me, "me"), myAnswer = null), me)
        assertTrue(detail.hosting)
        assertFalse(detail.canAnswer)
        assertEquals(emptyList(), detail.answers)
    }

    @Test
    fun theDetailCarriesTheMeetingPointAndDescription() {
        val detail = plannedRideDetailStateFrom(ride(), me)
        assertNull(detail.meetingLabel)
        assertEquals(50.85, detail.meetingLatitude)
        assertEquals(4.35, detail.meetingLongitude)
        assertEquals("Coffee first", detail.description)
    }

    private fun friend(name: String) = RiderRef(RiderId(name), name)

    private fun member(name: String, status: String = "accepted") = GroupMember(RiderId(name), name, status, sharing = true)

    private fun circle(
        name: String,
        members: List<GroupMember>,
        status: String = "accepted",
    ) = Group(id = "c-$name", name = name, kind = "circle", status = status, members = members)

    private val hosted = ride(owner = RiderRef(me, "me"), myAnswer = null)

    @Test
    fun onlyTheOwnerMayInvite() {
        assertTrue(plannedRideDetailStateFrom(hosted, me).canInvite)
        assertFalse(plannedRideDetailStateFrom(ride(), me).canInvite)
    }

    @Test
    fun friendsAlreadyInvitedAreNotOffered() {
        val ride = hosted.copy(invitees = listOf(invitee("bram", RsvpAnswer.DECLINED)))
        val choices = plannedInviteChoicesFrom(ride, listOf(friend("bram"), friend("Anna"), friend("cas")), emptyList(), me)
        assertEquals(
            listOf("Anna", "cas"),
            choices.friends.map { it.name },
            "a friend already asked was offered again, reading as not asked yet",
        )
        assertEquals("Anna", choices.friends.first().id)
    }

    @Test
    fun aCircleWithNobodyNewToAskIsNotOffered() {
        val ride = hosted.copy(invitees = listOf(invitee("bram", RsvpAnswer.PENDING)))
        val circles = listOf(
            circle("Done", listOf(member("me"), member("bram"))),
            circle("Fresh", listOf(member("me"), member("bram"), member("cas"))),
            circle("OnlyInvited", listOf(member("me"), member("dirk", status = "invited"))),
        )
        assertEquals(listOf("Fresh"), plannedInviteChoicesFrom(ride, emptyList(), circles, me).circles.map { it.name })
    }

    @Test
    fun aCircleTheHostHasNotAcceptedIsNotOffered() {
        val circles = listOf(circle("Pending", listOf(member("cas")), status = "invited"))
        assertTrue(plannedInviteChoicesFrom(hosted, emptyList(), circles, me).isEmpty)
    }

    @Test
    fun circleOptionsCarryTheCircleIdSortedByName() {
        val circles = listOf(circle("zeta", listOf(member("cas"))), circle("Alpha", listOf(member("dirk"))))
        val choices = plannedInviteChoicesFrom(hosted, emptyList(), circles, me)
        assertEquals(listOf("c-Alpha", "c-zeta"), choices.circles.map { it.id })
    }
}

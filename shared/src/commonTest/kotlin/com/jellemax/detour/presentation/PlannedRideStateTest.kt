package com.jellemax.detour.presentation

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.MeetingPoint
import com.jellemax.detour.data.PlannedRide
import com.jellemax.detour.data.PlannedRideInvitee
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.data.RiderRef
import com.jellemax.detour.data.RouteStop
import com.jellemax.detour.data.RsvpAnswer
import com.jellemax.detour.data.SavedRoute
import com.jellemax.detour.data.TravelMode
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
        route: SavedRoute? = null,
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
        route = route,
    )

    private fun route(polyline: List<LatLon>) = SavedRoute(
        id = 7, name = "Ourthe valley", createdMs = 0, mode = TravelMode.CAR,
        stops = listOf(RouteStop(LatLon(50.0, 5.0)), RouteStop(LatLon(50.2, 5.2))),
        polyline = polyline, distanceMeters = null, timeMs = null,
    )

    @Test
    fun anAttachedRouteIsPreviewedFromItsPolylineOrElseItsStops() {
        val routed = listOf(LatLon(50.0, 5.0), LatLon(50.1, 5.05), LatLon(50.2, 5.2))
        val withLine = plannedRideDetailStateFrom(ride(route = route(routed)), me)
        assertEquals("Ourthe valley", withLine.routeName)
        assertEquals(routed, withLine.routePreview)

        val unrouted = plannedRideDetailStateFrom(ride(route = route(emptyList())), me)
        assertEquals(listOf(LatLon(50.0, 5.0), LatLon(50.2, 5.2)), unrouted.routePreview)

        val none = plannedRideDetailStateFrom(ride(), me)
        assertNull(none.routeName)
        assertEquals(emptyList(), none.routePreview)
    }

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
}

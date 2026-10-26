package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The client half of BACKEND_SPEC.md §8.1: parsing the planned-ride wire shapes, the request
 * bodies, the start rule (owner only, no time window), which RSVP moves a rider may make, and
 * [PlannedRidesStore]'s reducers.
 */
class PlannedRidesTest {

    private val owner = RiderId("11111111-1111-1111-1111-111111111111")
    private val invitee = RiderId("22222222-2222-2222-2222-222222222222")

    /** The shape `PlannedRidesController` answers, as the owner sees it. */
    private val listFixture = """
        {
          "rides": [
            {
              "id": "9b2e0c1a-5d3f-4e8a-b7c6-0a1b2c3d4e5f",
              "owner": { "id": "11111111-1111-1111-1111-111111111111", "username": "jelle" },
              "title": "Sunday Ardennes",
              "startsAtMs": 1791100800000,
              "meetingPoint": { "latitude": 50.8503, "longitude": 4.3517, "label": "Shell A4" },
              "routeId": 1760000000123,
              "route": {
                "id": 1760000000123, "name": "Ourthe valley", "mode": "CAR",
                "stops": [ { "lat": 50.36, "lon": 5.45 }, { "lat": 50.25, "lon": 5.6, "name": "Durbuy" } ],
                "polyline": [ 50.36, 5.45, 50.3, 5.5, 50.25, 5.6 ]
              },
              "description": "Coffee first",
              "createdAtMs": 1790000000000,
              "myAnswer": null,
              "invitees": [
                { "rider": { "id": "22222222-2222-2222-2222-222222222222", "username": "max" }, "answer": "going" },
                { "rider": { "id": "33333333-3333-3333-3333-333333333333", "username": "eva" }, "answer": "pending" }
              ]
            },
            {
              "id": "0c1d2e3f-4a5b-6c7d-8e9f-a0b1c2d3e4f5",
              "owner": { "id": "22222222-2222-2222-2222-222222222222", "username": "max" },
              "title": "Evening loop",
              "startsAtMs": 1791200000000,
              "meetingPoint": { "latitude": 51.0, "longitude": 4.0, "label": null },
              "routeId": null,
              "route": null,
              "description": null,
              "createdAtMs": 1790000500000,
              "myAnswer": "maybe",
              "invitees": [
                { "rider": { "id": "11111111-1111-1111-1111-111111111111", "username": "jelle" }, "answer": "maybe" }
              ]
            }
          ]
        }
    """.trimIndent()

    private fun ride() = plannedRideFromJson(jsonObjectOf(listFixture).optArray("rides")!!.objects()[0])

    @Test
    fun aListParsesEveryFieldOfEachRide() {
        val rides = plannedRidesFromJson(jsonObjectOf(listFixture))

        assertEquals(2, rides.size)
        val first = rides[0]
        assertEquals("9b2e0c1a-5d3f-4e8a-b7c6-0a1b2c3d4e5f", first.id)
        assertEquals(RiderRef(owner, "jelle"), first.owner)
        assertEquals("Sunday Ardennes", first.title)
        assertEquals(1791100800000L, first.startsAtMs)
        assertEquals(50.8503, first.meetingPoint.latitude, absoluteTolerance = 1e-9)
        assertEquals(4.3517, first.meetingPoint.longitude, absoluteTolerance = 1e-9)
        assertEquals("Shell A4", first.meetingPoint.label)
        assertEquals(1760000000123L, first.routeId)
        val route = first.route!!
        assertEquals("Ourthe valley", route.name)
        assertEquals(listOf(LatLon(50.36, 5.45), LatLon(50.25, 5.6)), route.stops.map { it.at })
        assertEquals(3, route.polyline.size)
        assertEquals("Coffee first", first.description)
        assertEquals(1790000000000L, first.createdAtMs)
        assertNull(first.myAnswer, "the owner holds no invitation, so has no answer")
        assertEquals(
            listOf(RsvpAnswer.GOING, RsvpAnswer.PENDING),
            first.invitees.map { it.answer },
        )
        assertEquals("max", first.invitees[0].rider.username)
    }

    @Test
    fun nullOptionalFieldsParseAsNullNotAsEmptyOrZero() {
        val second = plannedRidesFromJson(jsonObjectOf(listFixture))[1]

        assertNull(second.meetingPoint.label)
        assertNull(second.routeId, "a null routeId must not read as route 0")
        assertNull(second.route)
        assertNull(second.description)
        assertEquals(RsvpAnswer.MAYBE, second.myAnswer)
    }

    @Test
    fun anEmptyListParsesAsNoRides() {
        assertEquals(emptyList(), plannedRidesFromJson(jsonObjectOf("""{ "rides": [] }""")))
    }

    @Test
    fun anInviteeWithAnUnknownAnswerIsDroppedRatherThanShownAsPending() {
        val o = jsonObjectOf(
            listFixture.replace("\"answer\": \"pending\"", "\"answer\": \"tentative\"")
        ).optArray("rides")!!.objects()[0]

        assertEquals(listOf("max"), plannedRideFromJson(o).invitees.map { it.rider.username })
    }

    @Test
    fun answersRoundTripThroughTheirLowerCaseWireNames() {
        RsvpAnswer.entries.forEach { assertEquals(it, RsvpAnswer.fromWire(it.wire)) }
        assertNull(RsvpAnswer.fromWire("Going"), "the wire is lower-case (BACKEND_SPEC.md §8.1)")
        assertNull(RsvpAnswer.fromWire(""))
    }

    @Test
    fun aDraftSerialisesEveryFieldTheServerReads() {
        val body = PlannedRideDraft(
            title = "Sunday Ardennes",
            startsAtMs = 1791100800000L,
            meetingPoint = MeetingPoint(50.85, 4.35, "Shell A4"),
            route = SavedRoute(
                id = 42L, name = "Ourthe valley", createdMs = 5L, mode = TravelMode.CAR,
                stops = listOf(RouteStop(LatLon(50.36, 5.45)), RouteStop(LatLon(50.25, 5.6), "Durbuy")),
                polyline = listOf(LatLon(50.36, 5.45), LatLon(50.25, 5.6)),
                distanceMeters = 21_000.0, timeMs = 1_200_000L, sharedBy = "eva",
            ),
            description = "Coffee first",
        ).toJson()

        assertEquals("Sunday Ardennes", body.optString("title"))
        assertEquals(1791100800000L, body.optLong("startsAtMs"))
        val meeting = body.optObject("meetingPoint")!!
        assertEquals(50.85, meeting.optDouble("latitude"), absoluteTolerance = 1e-9)
        assertEquals(4.35, meeting.optDouble("longitude"), absoluteTolerance = 1e-9)
        assertEquals("Shell A4", meeting.optString("label"))
        assertEquals(42L, body.optLong("routeId"))
        val route = routeFromJson(body.optObject("route")!!)!!
        assertEquals(42L, route.id)
        assertEquals("Durbuy", route.stops[1].name)
        assertEquals(2, route.polyline.size)
        assertFalse(body.optObject("route")!!.has("sharedBy"), "invitees don't learn who the owner got it from")
        assertEquals("Coffee first", body.optString("description"))
    }

    @Test
    fun aDraftOmitsOptionalFieldsThatAreUnset() {
        val body = PlannedRideDraft("Loop", 1L, MeetingPoint(51.0, 4.0)).toJson()

        assertFalse(body.has("routeId"))
        assertFalse(body.has("route"))
        assertFalse(body.has("description"))
        assertFalse(body.optObject("meetingPoint")!!.has("label"))
    }

    @Test
    fun anInviteBodyCarriesBothListsEvenWhenOneIsEmpty() {
        val body = inviteBody(listOf(invitee), emptyList())

        val riderIds = body.optArray("riderIds")!!
        assertEquals(1, riderIds.size)
        assertEquals(invitee.value, riderIds.optString(0))
        assertEquals(0, body.optArray("circleIds")!!.size)
    }

    // --- start rule -----------------------------------------------------------

    @Test
    fun theOwnerMayStartTheRideLongBeforeItsStartTime() {
        val farFuture = ride().copy(startsAtMs = Long.MAX_VALUE)

        assertTrue(farFuture.canStart(owner), "owner decision on #640: no time window")
    }

    @Test
    fun theOwnerMayStartTheRideLongAfterItsStartTime() {
        assertTrue(ride().copy(startsAtMs = 0L).canStart(owner))
    }

    @Test
    fun anInviteeMayNeverStartTheRide() {
        assertFalse(ride().canStart(invitee))
    }

    @Test
    fun aBlankRiderIdStartsNothingEvenAgainstABlankOwner() {
        val ownerless = ride().copy(owner = RiderRef(RiderId(""), ""))

        assertFalse(ownerless.canStart(RiderId("")), "a /me that has not answered is nobody")
    }

    // --- reminder rule ------------------------------------------------------------

    @Test
    fun goingAndMaybeAreRemindedOfARideStillAhead() {
        val ahead = ride().copy(startsAtMs = 2_000L)

        assertTrue(ahead.copy(myAnswer = RsvpAnswer.GOING).wantsReminder(nowMs = 1_000L))
        assertTrue(ahead.copy(myAnswer = RsvpAnswer.MAYBE).wantsReminder(nowMs = 1_000L))
    }

    @Test
    fun pendingDeclinedAndTheOwnerAreNotReminded() {
        val ahead = ride().copy(startsAtMs = 2_000L)

        assertFalse(ahead.copy(myAnswer = RsvpAnswer.PENDING).wantsReminder(nowMs = 1_000L))
        assertFalse(ahead.copy(myAnswer = RsvpAnswer.DECLINED).wantsReminder(nowMs = 1_000L))
        assertFalse(ahead.copy(myAnswer = null).wantsReminder(nowMs = 1_000L), "the owner holds no answer")
    }

    @Test
    fun aRideThatHasStartedIsNotReminded() {
        val going = ride().copy(myAnswer = RsvpAnswer.GOING, startsAtMs = 2_000L)

        assertFalse(going.wantsReminder(nowMs = 2_000L))
        assertFalse(going.wantsReminder(nowMs = 3_000L))
    }

    // --- RSVP moves -------------------------------------------------------------

    @Test
    fun anInviteeMayMoveFromPendingToAnyRealAnswer() {
        val pending = ride().copy(myAnswer = RsvpAnswer.PENDING)

        listOf(RsvpAnswer.GOING, RsvpAnswer.MAYBE, RsvpAnswer.DECLINED).forEach {
            assertTrue(pending.acceptsAnswer(invitee, it), "pending -> $it")
        }
    }

    @Test
    fun anInviteeMayChangeAnAnswerAlreadyGiven() {
        assertTrue(ride().copy(myAnswer = RsvpAnswer.GOING).acceptsAnswer(invitee, RsvpAnswer.DECLINED))
        assertTrue(ride().copy(myAnswer = RsvpAnswer.DECLINED).acceptsAnswer(invitee, RsvpAnswer.MAYBE))
    }

    @Test
    fun nobodyMayAnswerPending() {
        RsvpAnswer.entries.forEach { current ->
            assertFalse(ride().copy(myAnswer = current).acceptsAnswer(invitee, RsvpAnswer.PENDING), "$current -> pending")
        }
    }

    @Test
    fun theOwnerHasNothingToAnswer() {
        assertFalse(ride().acceptsAnswer(owner, RsvpAnswer.GOING))
        assertFalse(ride().copy(myAnswer = RsvpAnswer.PENDING).acceptsAnswer(owner, RsvpAnswer.GOING))
    }

    // --- store reducers ---------------------------------------------------------

    @Test
    fun ridesAreNullUntilTheFirstLoadAndEmptyAfterAnEmptyOne() {
        assertNull(PlannedRidesState().rides, "not loaded must not read as nothing planned")
        assertEquals(emptyList(), PlannedRidesState().loaded(emptyList(), nowMs = 5L).rides)
    }

    @Test
    fun aLoadClearsBusyAndErrorAndStampsTheTime() {
        val s = PlannedRidesState(error = "old").starting().loaded(listOf(ride()), nowMs = 1_000L)

        assertFalse(s.busy)
        assertNull(s.error)
        assertEquals(1_000L, s.loadedAtMs)
        assertEquals(1, s.rides?.size)
    }

    @Test
    fun aFailedRefreshKeepsTheRidesAlreadyShownAndDoesNotStamp() {
        val loaded = PlannedRidesState().loaded(listOf(ride()), nowMs = 1_000L)

        val failed = loaded.starting().failed(Exception("timeout"))

        assertEquals(loaded.rides, failed.rides)
        assertEquals("timeout", failed.error)
        assertEquals(1_000L, failed.loadedAtMs)
        assertFalse(failed.busy)
    }

    @Test
    fun aFailureWithoutAMessageFallsBackToAReadableOne() {
        assertEquals(PlannedRidesStore.FALLBACK_ERROR, PlannedRidesState().failed(Exception()).error)
    }

    @Test
    fun aResultFromAnEndedSessionIsDiscarded() {
        val current = PlannedRidesState()
        val stale = current.loaded(listOf(ride()), nowMs = 1L)

        assertEquals(current, current.commitIfCurrent(epoch = 1, currentEpoch = 2, result = stale))
        assertEquals(stale, current.commitIfCurrent(epoch = 2, currentEpoch = 2, result = stale))
    }

    @Test
    fun clearingDropsEveryField() {
        val s = PlannedRidesState(listOf(ride()), busy = true, error = "x", loadedAtMs = 1L)

        assertEquals(PlannedRidesState(), s.cleared())
    }
}

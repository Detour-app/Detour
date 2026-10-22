package com.jellemax.detour.presentation

import com.jellemax.detour.data.MeetingPoint
import com.jellemax.detour.data.PlannedRide
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.data.RiderRef
import com.jellemax.detour.presentation.PlannedRideFormProblem.DESCRIPTION_TOO_LONG
import com.jellemax.detour.presentation.PlannedRideFormProblem.MEETING_POINT_MISSING
import com.jellemax.detour.presentation.PlannedRideFormProblem.START_MISSING
import com.jellemax.detour.presentation.PlannedRideFormProblem.START_NOT_IN_FUTURE
import com.jellemax.detour.presentation.PlannedRideFormProblem.TITLE_MISSING
import com.jellemax.detour.presentation.PlannedRideFormProblem.TITLE_TOO_LONG
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlannedRideFormTest {

    private val now = 1_791_000_000_000
    private val point = MeetingPoint(50.85, 4.35, label = "Grote Markt")

    private fun valid() = PlannedRideForm(
        title = "  Sunday Ardennes ",
        startsAtMs = now + 3_600_000,
        meetingPoint = point,
        description = "  ",
        routeId = 7,
    )

    @Test
    fun emptyFormNamesEveryMissingField() {
        assertEquals(listOf(TITLE_MISSING, START_MISSING, MEETING_POINT_MISSING), PlannedRideForm().problems(now))
    }

    @Test
    fun aBlankTitleIsMissing() {
        assertEquals(listOf(TITLE_MISSING), valid().copy(title = "   ").problems(now))
    }

    @Test
    fun aStartNowOrEarlierIsRefused() {
        assertEquals(listOf(START_NOT_IN_FUTURE), valid().copy(startsAtMs = now).problems(now))
        assertEquals(listOf(START_NOT_IN_FUTURE), valid().copy(startsAtMs = now - 1).problems(now))
    }

    @Test
    fun overlongTextIsRefusedAfterTrimming() {
        assertEquals(listOf(TITLE_TOO_LONG), valid().copy(title = "x".repeat(81)).problems(now))
        assertEquals(emptyList(), valid().copy(title = " " + "x".repeat(80) + " ").problems(now))
        assertEquals(listOf(DESCRIPTION_TOO_LONG), valid().copy(description = "x".repeat(2001)).problems(now))
    }

    @Test
    fun aValidFormBecomesATrimmedDraftKeepingTheRoute() {
        val draft = valid().toDraft(now)!!
        assertEquals("Sunday Ardennes", draft.title)
        assertEquals(now + 3_600_000, draft.startsAtMs)
        assertEquals(point, draft.meetingPoint)
        assertEquals(7L, draft.routeId)
        assertNull(draft.description)
    }

    @Test
    fun anInvalidFormHasNoDraft() {
        assertNull(valid().copy(meetingPoint = null).toDraft(now))
    }

    @Test
    fun editingStartsFromTheRide() {
        val ride = PlannedRide(
            id = "r1",
            owner = RiderRef(RiderId("host"), "jelle"),
            title = "Sunday Ardennes",
            startsAtMs = now,
            meetingPoint = point,
            routeId = 3,
            description = null,
            createdAtMs = 0,
            myAnswer = null,
            invitees = emptyList(),
        )
        assertEquals(PlannedRideForm("Sunday Ardennes", now, point, "", 3), plannedRideFormFrom(ride))
    }

    @Test
    fun aSavedPlaceNameIsCutToTheServerLabelLimit() {
        assertEquals("x".repeat(60), meetingPointAt("x".repeat(70), 1.0, 2.0).label)
        assertNull(meetingPointAt("  ", 1.0, 2.0).label)
    }

    @Test
    fun theStartIsThePickedDayAtThePickedLocalTime() {
        // 2026-10-25 00:00 UTC, the day Brussels leaves summer time at 03:00.
        val day = 1_792_886_400_000
        // 09:30 CET (UTC+1) after the change = 08:30 UTC.
        assertEquals(day + 8 * 3_600_000 + 30 * 60_000, plannedStartMs(day, 9, 30, "Europe/Brussels"))
        // West of Greenwich the picked day must not slip to the day before.
        assertEquals(day + 13 * 3_600_000, plannedStartMs(day, 9, 0, "America/New_York"))
    }

    @Test
    fun partsRoundTripThroughTheStart() {
        val day = 1_792_886_400_000
        val start = plannedStartMs(day, 23, 45, "America/New_York")
        assertEquals(PlannedStartParts(day, 23, 45), plannedStartParts(start, "America/New_York"))
    }
}

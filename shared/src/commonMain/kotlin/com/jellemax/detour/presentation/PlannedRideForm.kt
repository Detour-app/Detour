package com.jellemax.detour.presentation

import com.jellemax.detour.data.MeetingPoint
import com.jellemax.detour.data.PlannedRide
import com.jellemax.detour.data.PlannedRideDraft
import com.jellemax.detour.data.SavedRoute
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** The server's limits (backend `DetourLimits`), checked here so the form says why before the
 *  server refuses with a 400 the rider cannot act on. */
const val PLANNED_TITLE_MAX_CHARS = 80
const val PLANNED_DESCRIPTION_MAX_CHARS = 2000
internal const val MEETING_LABEL_MAX = 60

/**
 * What the host has typed into the create/edit form (#755). Raw text, not trimmed: trimming
 * while typing would eat the space between two words. [startsAtMs] and [meetingPoint] are null
 * until picked — a new ride has no sensible default for either.
 *
 * [route] is the attached saved route, or null for none (#757). Pre-filled from the ride's own
 * copy on an edit, so editing the title does not swap in whatever the saved route has become since.
 */
data class PlannedRideForm(
    val title: String = "",
    val startsAtMs: Long? = null,
    val meetingPoint: MeetingPoint? = null,
    val description: String = "",
    val route: SavedRoute? = null,
)

enum class PlannedRideFormProblem {
    TITLE_MISSING,
    TITLE_TOO_LONG,
    START_MISSING,
    START_NOT_IN_FUTURE,
    MEETING_POINT_MISSING,
    DESCRIPTION_TOO_LONG,
}

/** Every reason the form cannot be sent yet, in field order; empty when it can. */
fun PlannedRideForm.problems(nowMs: Long): List<PlannedRideFormProblem> = buildList {
    val trimmedTitle = title.trim()
    if (trimmedTitle.isEmpty()) add(PlannedRideFormProblem.TITLE_MISSING)
    if (trimmedTitle.length > PLANNED_TITLE_MAX_CHARS) add(PlannedRideFormProblem.TITLE_TOO_LONG)
    when {
        startsAtMs == null -> add(PlannedRideFormProblem.START_MISSING)
        startsAtMs <= nowMs -> add(PlannedRideFormProblem.START_NOT_IN_FUTURE)
    }
    if (meetingPoint == null) add(PlannedRideFormProblem.MEETING_POINT_MISSING)
    if (description.trim().length > PLANNED_DESCRIPTION_MAX_CHARS) add(PlannedRideFormProblem.DESCRIPTION_TOO_LONG)
}

/** The draft to send, or null while [problems] is not empty. A blank description is no description. */
fun PlannedRideForm.toDraft(nowMs: Long): PlannedRideDraft? {
    if (problems(nowMs).isNotEmpty()) return null
    return PlannedRideDraft(
        title = title.trim(),
        startsAtMs = startsAtMs ?: return null,
        meetingPoint = meetingPoint ?: return null,
        route = route,
        description = description.trim().ifEmpty { null },
    )
}

/** The form pre-filled from a ride the host is editing. */
fun plannedRideFormFrom(ride: PlannedRide): PlannedRideForm = PlannedRideForm(
    title = ride.title,
    startsAtMs = ride.startsAtMs,
    meetingPoint = ride.meetingPoint,
    description = ride.description.orEmpty(),
    route = ride.route,
)

/** A meeting point at a saved place, its name as the label, cut to what the server accepts. */
fun meetingPointAt(name: String, latitude: Double, longitude: Double): MeetingPoint =
    MeetingPoint(latitude, longitude, label = name.trim().take(MEETING_LABEL_MAX).trim().ifEmpty { null })

/** A start split into the date picker's value (UTC midnight of the local date) and a local
 *  hour and minute — the three things the phone's date and time pickers read and write. */
data class PlannedStartParts(val dateUtcMidnightMs: Long, val hour: Int, val minute: Int)

/**
 * The instant the host means by a date from a date picker and a time from a time picker, in
 * the zone named by [zoneId]. Date pickers hand back UTC midnight of the picked day, so the day
 * is read in UTC and the time is placed in the rider's own zone — reading the day in the local
 * zone would land a pick west of Greenwich on the day before.
 *
 * The zone is an id rather than a `TimeZone` so app/ need not see kotlinx-datetime (see the
 * dependency note in shared/build.gradle.kts).
 */
fun plannedStartMs(dateUtcMidnightMs: Long, hour: Int, minute: Int, zoneId: String): Long {
    val date = Instant.fromEpochMilliseconds(dateUtcMidnightMs).toLocalDateTime(TimeZone.UTC).date
    return LocalDateTime(date, LocalTime(hour, minute)).toInstant(TimeZone.of(zoneId)).toEpochMilliseconds()
}

/** The inverse of [plannedStartMs], for pre-filling the pickers from a ride's start. */
fun plannedStartParts(startsAtMs: Long, zoneId: String): PlannedStartParts {
    val local = Instant.fromEpochMilliseconds(startsAtMs).toLocalDateTime(TimeZone.of(zoneId))
    return PlannedStartParts(
        dateUtcMidnightMs = local.date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
        hour = local.hour,
        minute = local.minute,
    )
}

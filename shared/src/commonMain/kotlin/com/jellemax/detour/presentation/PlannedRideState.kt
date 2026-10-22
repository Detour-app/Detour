package com.jellemax.detour.presentation

import com.jellemax.detour.data.PlannedRide
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.data.RsvpAnswer

/**
 * One planned ride in the Planned list. [myAnswer] is null when [hosting]: the owner holds no
 * invitation to their own ride (BACKEND_SPEC.md §8.1), so there is no answer to show, and the
 * row says "you're hosting" instead. Answers stay [RsvpAnswer]s rather than strings, because the
 * wording is a platform string resource (EN + NL on the phone).
 */
data class PlannedRideRow(
    val id: String,
    val title: String,
    val startsAtMs: Long,
    val hostName: String,
    val hosting: Boolean,
    val myAnswer: RsvpAnswer?,
)

data class PlannedInviteeRow(
    val name: String,
    val answer: RsvpAnswer,
)

/**
 * One planned ride's detail screen.
 *
 * [meetingLabel] is the owner's label, null when they gave none — the screen falls back to the
 * coordinates. [canAnswer] is [PlannedRide.canAnswer]; [answers] are the buttons it shows, in
 * order, and never include [RsvpAnswer.PENDING], which a rider cannot send.
 */
data class PlannedRideDetailState(
    val id: String = "",
    val title: String = "",
    val startsAtMs: Long = 0,
    val hostName: String = "",
    val hosting: Boolean = false,
    val description: String? = null,
    val meetingLabel: String? = null,
    val meetingLatitude: Double = 0.0,
    val meetingLongitude: Double = 0.0,
    val invitees: List<PlannedInviteeRow> = emptyList(),
    val canAnswer: Boolean = false,
    val myAnswer: RsvpAnswer? = null,
    val answers: List<RsvpAnswer> = emptyList(),
)

/**
 * Pure map from the store's rides to list rows, in the server's order (soonest start first,
 * past rides included — §8.1). No I/O, callable with literals.
 *
 * [hosting] is decided by [PlannedRide.canStart], which already refuses a blank [me]: a rider
 * whose `/me` has not answered yet sees every ride as someone else's rather than every
 * blank-owner ride as theirs.
 */
fun plannedRideRowsFrom(rides: List<PlannedRide>, me: RiderId): List<PlannedRideRow> = rides.map { ride ->
    val hosting = ride.canStart(me)
    PlannedRideRow(
        id = ride.id,
        title = ride.title,
        startsAtMs = ride.startsAtMs,
        hostName = ride.owner.username,
        hosting = hosting,
        myAnswer = if (hosting) null else ride.myAnswer,
    )
}

/**
 * Pure map from one ride to its detail screen.
 *
 * Invitees are grouped by answer — going, maybe, not answered, can't — and alphabetical within
 * each group, so the owner reads who is coming before who is not. The server's order is
 * invitation order, which tells a reader nothing.
 */
fun plannedRideDetailStateFrom(ride: PlannedRide, me: RiderId): PlannedRideDetailState {
    val hosting = ride.canStart(me)
    val canAnswer = ride.canAnswer(me)
    return PlannedRideDetailState(
        id = ride.id,
        title = ride.title,
        startsAtMs = ride.startsAtMs,
        hostName = ride.owner.username,
        hosting = hosting,
        description = ride.description,
        meetingLabel = ride.meetingPoint.label,
        meetingLatitude = ride.meetingPoint.latitude,
        meetingLongitude = ride.meetingPoint.longitude,
        invitees = ride.invitees
            .sortedWith(compareBy({ INVITEE_ORDER.indexOf(it.answer) }, { it.rider.username.lowercase() }))
            .map { PlannedInviteeRow(it.rider.username, it.answer) },
        canAnswer = canAnswer,
        myAnswer = if (hosting) null else ride.myAnswer,
        answers = if (canAnswer) RsvpAnswer.entries.filter { it.isAnswer } else emptyList(),
    )
}

private val INVITEE_ORDER = listOf(RsvpAnswer.GOING, RsvpAnswer.MAYBE, RsvpAnswer.PENDING, RsvpAnswer.DECLINED)

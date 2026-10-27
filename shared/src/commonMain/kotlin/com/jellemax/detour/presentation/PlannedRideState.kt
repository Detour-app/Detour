package com.jellemax.detour.presentation

import com.jellemax.detour.data.Group
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.PlannedRide
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.data.RiderRef
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

/** One friend or circle the host can tick in the invite picker. [id] is a rider id for a
 *  friend and a circle id for a circle; which list it sits in says which. */
data class PlannedInviteOption(
    val id: String,
    val name: String,
)

/**
 * What the host's invite picker offers on one ride (#756). Both lists empty means there is
 * nobody left to ask, which the picker says rather than showing two empty headings.
 */
data class PlannedInviteChoices(
    val friends: List<PlannedInviteOption> = emptyList(),
    val circles: List<PlannedInviteOption> = emptyList(),
) {
    val isEmpty: Boolean get() = friends.isEmpty() && circles.isEmpty()
}

/**
 * One planned ride's detail screen.
 *
 * [meetingLabel] is the owner's label, null when they gave none — the screen falls back to the
 * coordinates. [canAnswer] is [PlannedRide.canAnswer]; [answers] are the buttons it shows, in
 * order, and never include [RsvpAnswer.PENDING], which a rider cannot send. [routeName] is null
 * when no route is attached; [routePreview] is the line to draw for it — the routed polyline, or
 * the stops joined straight when the owner's copy was never routed — and empty without a route.
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
    /** Owner only: the server refuses an invitee's invite with a 403 (BACKEND_SPEC.md §8.1). */
    val canInvite: Boolean = false,
    val canAnswer: Boolean = false,
    /** Owner only, at any time (#758): [PlannedRide.canStart]. */
    val canStart: Boolean = false,
    val myAnswer: RsvpAnswer? = null,
    val answers: List<RsvpAnswer> = emptyList(),
    val routeName: String? = null,
    val routePreview: List<LatLon> = emptyList(),
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
        canInvite = hosting,
        canAnswer = canAnswer,
        canStart = hosting,
        myAnswer = if (hosting) null else ride.myAnswer,
        answers = if (canAnswer) RsvpAnswer.entries.filter { it.isAnswer } else emptyList(),
        routeName = ride.route?.name,
        routePreview = ride.route?.let { r -> r.polyline.ifEmpty { r.stops.map { it.at } } }.orEmpty(),
    )
}

/**
 * Pure map from the host's friends and circles to the invite picker for [ride] (#756).
 *
 * A friend already invited is left out — inviting them again only keeps their answer (§8.1), so
 * offering them reads as "not asked yet". Only circles the host has accepted are offered: the
 * server takes a circle only from an accepted member, and one refused circle refuses the whole
 * request. A circle whose every other accepted member is already invited is left out too, since
 * ticking it would ask nobody new; members still at "invited" are not counted, because the
 * server expands a circle to its accepted members only.
 *
 * Alphabetical within each list. [me] and the owner are never offered: the owner holds no
 * invitation to their own ride.
 */
fun plannedInviteChoicesFrom(
    ride: PlannedRide,
    friends: List<RiderRef>,
    circles: List<Group>,
    me: RiderId,
): PlannedInviteChoices {
    val asked = ride.invitees.mapTo(mutableSetOf()) { it.rider.id } + ride.owner.id + me
    return PlannedInviteChoices(
        friends = friends
            .filter { it.id !in asked }
            .distinctBy { it.id }
            .sortedBy { it.username.lowercase() }
            .map { PlannedInviteOption(it.id.value, it.username) },
        circles = circles
            .filter { circle ->
                circle.status == ACCEPTED &&
                    circle.members.any { it.status == ACCEPTED && it.id !in asked }
            }
            .sortedBy { it.name.lowercase() }
            .map { PlannedInviteOption(it.id, it.name) },
    )
}

private const val ACCEPTED = "accepted"

private val INVITEE_ORDER = listOf(RsvpAnswer.GOING, RsvpAnswer.MAYBE, RsvpAnswer.PENDING, RsvpAnswer.DECLINED)

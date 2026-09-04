package com.jellemax.detour.presentation

import com.jellemax.detour.data.Group
import com.jellemax.detour.data.RiderId

/**
 * One circle row, ready to render: the raw [Group] and its member list are
 * gone by this point, replaced with exactly the strings and flags a row
 * draws in `CircleListSection`.
 */
data class CircleRow(
    val id: String,
    val name: String,
    val memberLine: String,
    val isInvited: Boolean,
    val sharing: Boolean,
)

/**
 * The Circles list screen's display state: circles split into the ones this
 * device has been invited to and not yet answered, and the ones it belongs
 * to.
 *
 * Named [CirclesListState], not `CirclesState` — `com.jellemax.detour.data.CirclesStore`
 * already owns that name for its own load/busy/error/detail state (see that
 * type's KDoc at CirclesStore.kt:13-19 on why the list and detail busy/error
 * pairs are kept apart). This is the pure, callable-with-literals *display*
 * shape produced by [circlesListStateFrom]; that one is the mutable,
 * network-backed source of truth a screen collects directly. See
 * [CirclesListPresenter]'s KDoc for why this type carries no busy/error
 * fields of its own — `CirclesStore.state` already has them, twice over, and
 * a screen reads those, not these.
 */
data class CirclesListState(
    val invited: List<CircleRow> = emptyList(),
    val accepted: List<CircleRow> = emptyList(),
)

/**
 * Pure map from the store's raw circle list to [CirclesListState]. No I/O,
 * callable with literals.
 *
 * The invited/accepted split, and each row's [CircleRow.isInvited], both
 * read [Group.status] — this device's own membership state in that circle —
 * never a member's own `status`, which is a different field describing a
 * *different* rider's answer to *their* invite.
 *
 * [CircleRow.memberLine] joins every member's username with `", "`, in
 * membership order, appending `" (invited)"` to a member whose own `status`
 * is `"invited"` — the exact join `CircleListSection` did inline before this
 * mapper existed.
 *
 * [CircleRow.sharing] reads [riderId]'s own row in [Group.members] — never
 * merely "any member sharing", which would also be true for a circle where a
 * housemate shares and this rider does not. Matches the "N sharing"
 * definition in `SocialScreen.kt` and `CirclePresence.sharingCircles`
 * (`circle.members.find { it.id == riderId }?.sharing == true`), so this
 * mapper's rows and that count never disagree about what counts as sharing.
 * A rider with no membership row at all reads as not sharing.
 */
fun circlesListStateFrom(circles: List<Group>, riderId: RiderId): CirclesListState {
    val (invited, accepted) = circles.map { it.toRow(riderId) }.partition { it.isInvited }
    return CirclesListState(invited = invited, accepted = accepted)
}

private fun Group.toRow(riderId: RiderId) = CircleRow(
    id = id,
    name = name,
    memberLine = members.joinToString(", ") { m ->
        m.username + if (m.status == "invited") " (invited)" else ""
    },
    isInvited = status == "invited",
    sharing = members.find { it.id == riderId }?.sharing == true,
)

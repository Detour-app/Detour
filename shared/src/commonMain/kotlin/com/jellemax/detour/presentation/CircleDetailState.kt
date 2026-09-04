package com.jellemax.detour.presentation

import com.jellemax.detour.data.CirclePlace
import com.jellemax.detour.data.Group
import com.jellemax.detour.data.PlaceEvent
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.data.handleFor

/**
 * One row in the Members section: a member's handle plus the exact suffixes
 * `MemberRow` built by hand in `CirclesScreen.kt` — `" (you)"` for the
 * viewing rider's own row, `" · invited"` for a member who hasn't accepted
 * yet, both, or neither. [sharing] passes a member's own flag straight
 * through for the row's visibility icon; it needs no mapping.
 */
data class CircleMemberRow(
    val id: RiderId,
    val displayName: String,
    val sharing: Boolean,
)

/**
 * One row in the Shared places section. [subtitle] is the exact
 * "Shared by X · N m radius" line the old inline `Text` built — the owner's
 * handle from membership, the radius rounded to a whole metre the same way
 * `place.radiusM.toInt()` always did (no locale formatting to get wrong, so
 * no need for `groupThousands`/`formatFixed` here). [removable] mirrors the
 * old `place.ownerId == riderId` check that gated the delete icon: only an
 * owner may unshare their own place.
 */
data class SharedPlaceRow(
    val serverId: String,
    val name: String,
    val subtitle: String,
    val removable: Boolean,
)

/**
 * One row in the Recent activity section, already the full line
 * ("mover arrived at Home — 8h ago") — there is nothing else a caller needs
 * to recompose from parts.
 */
data class CircleEventRow(val text: String)

/**
 * The circle-detail pane's display state: one circle's members, its shared
 * places and its recent arrival/departure events, each row pre-built to the
 * exact strings `CircleDetailSection` used to build inline.
 *
 * Named [CircleDetailState], not `CirclesState` (`com.jellemax.detour.data.CirclesStore`'s
 * own load/busy/error/detail state, see CirclesStore.kt:13-19) nor
 * [CirclesListState] (the previous task's list-screen display shape) — three
 * names, three shapes, none of them this one. [CirclesState.detailBusy] and
 * [CirclesState.detailError] are deliberately not reproduced here: the
 * screen already reads them straight off `CirclesStore.state`, same as
 * today, and folding them into this type would just be a second copy of the
 * pair CirclesStore.kt:13-19 explains keeping apart from the list's own.
 */
data class CircleDetailState(
    val members: List<CircleMemberRow> = emptyList(),
    val places: List<SharedPlaceRow> = emptyList(),
    val events: List<CircleEventRow> = emptyList(),
)

/**
 * Pure map from one circle's raw membership plus its detail pane's shared
 * places and events (`CirclesState.places`/`CirclesState.events`, scoped to
 * [circle] by `CirclesStore.select`) to [CircleDetailState]. No I/O, callable
 * with literals — [nowMs] is a plain argument, exactly [relativeAge]'s own
 * contract, and this function never reads a clock itself.
 *
 * An event's place lookup matches `CirclePlace.place.id` — a place can be
 * unshared out from under an event that already happened, and
 * [CircleEventRow.text] then falls back to "a since-removed place" rather
 * than dropping the row, verbatim from the old inline `?:` in
 * `CircleDetailSection`.
 */
fun circleDetailStateFrom(
    circle: Group,
    riderId: RiderId,
    places: List<CirclePlace>,
    events: List<PlaceEvent>,
    nowMs: Long,
): CircleDetailState {
    val members = circle.members.map { m ->
        val suffix = buildString {
            if (m.id == riderId) append(" (you)")
            if (m.status == "invited") append(" · invited")
        }
        CircleMemberRow(id = m.id, displayName = m.username + suffix, sharing = m.sharing)
    }
    val placeRows = places.map { p ->
        SharedPlaceRow(
            serverId = p.serverId,
            name = p.place.name,
            subtitle = "Shared by ${circle.members.handleFor(p.ownerId)} · ${p.radiusM.toInt()} m radius",
            removable = p.ownerId == riderId,
        )
    }
    val eventRows = events.map { e ->
        val placeName = places.find { it.place.id == e.placeId }?.place?.name ?: "a since-removed place"
        val verb = if (e.kind == "arrive") "arrived at" else "left"
        CircleEventRow("${circle.members.handleFor(e.riderId)} $verb $placeName — ${relativeAge(e.tsMs, nowMs)}")
    }
    return CircleDetailState(members = members, places = placeRows, events = eventRows)
}

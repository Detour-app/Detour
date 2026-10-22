package com.jellemax.detour.presentation

import com.jellemax.detour.data.Group
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.data.handleFor
import com.jellemax.detour.data.isOwnedBy
import com.jellemax.detour.drive.BreakRequest
import com.jellemax.detour.drive.RegroupPin

/**
 * What a convoy's break-and-regroup controls show on one device (#632, #649):
 * the break requests still worth a pill, the host's meeting point with who has
 * reached it, and whether this rider may drop one. Pure — see
 * [convoyBreakRegroupStateFrom]. Phone today; the car (#657) and iOS (#658)
 * slices read the same state, so none of these rules is re-derived per surface.
 */
data class ConvoyBreakRegroupState(
    /** Requests younger than [BREAK_SHOWN_MS], newest first. */
    val breaks: List<BreakPill>,
    /** Null while no meeting point is set. */
    val regroup: RegroupBanner?,
    /** Only the convoy's host may drop, replace or clear the pin (owner
     *  decision, #632) — the relay drops anyone else's, so a member offered
     *  the action would see a pin nobody else does. */
    val isHost: Boolean,
)

data class BreakPill(
    val riderId: RiderId,
    /** The sender's handle; the caller draws its own "You" when [mine]. */
    val name: String,
    val location: LatLon,
    val mine: Boolean,
)

data class RegroupBanner(
    val location: LatLon,
    val label: String?,
    /** Straight-line metres from this device, or null with no fix of its own. */
    val distanceM: Double?,
    /** Handles of accepted riders within [REGROUP_ARRIVED_M] of the pin. */
    val arrived: List<String>,
    /** Everyone else: further out, or with no position this device has seen. */
    val notArrived: List<String>,
    /** This device is one of the [arrived]. */
    val hereAlready: Boolean,
)

/** How long a break request keeps its pill and marker. The relay keeps none and
 *  there is no cancel frame, so the request ages out on the receiving side;
 *  ten minutes is long enough for a group to find somewhere to pull in. */
const val BREAK_SHOWN_MS = 10 * 60_000L

/** How often a surface re-checks the age of the break pills while nothing else
 *  changes. Well under a minute, so a request is gone within one tick of
 *  [BREAK_SHOWN_MS]; read by the phone and iOS maps so neither holds its own. */
const val BREAK_AGE_TICK_MS = 15_000L

/** Within this of the pin a rider counts as there. A car park or a fuel
 *  station forecourt, plus GPS scatter, without reaching the next junction. */
const val REGROUP_ARRIVED_M = 150.0

/**
 * Pure map to [ConvoyBreakRegroupState]. [nowMs] is the wall clock the relay
 * stamps [BreakRequest.receivedAtMs] with; this never reads one.
 *
 * [positions] is every rider's last position this device has, its own under
 * [selfId] — that one also gives the banner its distance. A null [convoy] — the membership list has not loaded, or
 * this device is in no convoy — shows requests by id fallback ("Someone") and
 * never offers the host action.
 */
fun convoyBreakRegroupStateFrom(
    convoy: Group?,
    selfId: RiderId,
    breakRequests: Map<RiderId, BreakRequest>,
    pin: RegroupPin?,
    positions: Map<RiderId, LatLon>,
    nowMs: Long,
): ConvoyBreakRegroupState {
    val ownLocation = positions[selfId]
    val members = convoy?.members.orEmpty()
    val breaks = breakRequests.values
        .filter { nowMs - it.receivedAtMs < BREAK_SHOWN_MS }
        .sortedByDescending { it.receivedAtMs }
        .map { BreakPill(it.riderId, members.handleFor(it.riderId), it.location, mine = it.riderId == selfId) }
    val regroup = pin?.let { p ->
        val accepted = members.filter { it.status == "accepted" }
        val (arrived, notArrived) = accepted.partition { m ->
            val at = positions[m.id]
            at != null && RoadRoulette.distanceMeters(at, p.location) <= REGROUP_ARRIVED_M
        }
        RegroupBanner(
            location = p.location,
            label = p.label,
            distanceM = ownLocation?.let { RoadRoulette.distanceMeters(it, p.location) },
            arrived = arrived.map { members.handleFor(it.id) },
            notArrived = notArrived.map { members.handleFor(it.id) },
            hereAlready = arrived.any { it.id == selfId },
        )
    }
    return ConvoyBreakRegroupState(
        breaks = breaks,
        regroup = regroup,
        isHost = convoy?.isOwnedBy(selfId) == true,
    )
}

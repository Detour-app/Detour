package com.jellemax.detour.ui

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RouteCandidate
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.drive.GroupSpin
import com.jellemax.detour.drive.SpinCandidate

/** One color per spin candidate, so the pin on the map and the row in the card
 *  are recognizably the same place. Kept clear of the blue radius circle, the
 *  orange direction wedge and the pink route line. */
internal val CANDIDATE_COLORS = listOf(0xFF7E57C2, 0xFF00897B, 0xFFF4511E)
    .map { it.toInt() }

/** A [GroupSpin]'s wire candidates, reshaped into the same [RouteCandidate]
 *  list a local spin produces, so the map pins and CandidatesCard don't need
 *  a second code path for "I received this" vs "I rolled this". [route] is
 *  a placeholder carrying only the numbers the sharer already had - a
 *  receiving member has no polyline for it, and doesn't need one: committing
 *  just sets `route = null` and lets startNavigation() fetch a real one, the
 *  same as tapping a long-pressed pin. */
internal fun GroupSpin.asRouteCandidates(): List<RouteCandidate> = candidates.map { sc ->
    RouteCandidate(
        destination = LatLon(sc.lat, sc.lon),
        name = sc.name,
        route = sc.distanceM?.let {
            RouteResult(
                polyline = emptyList(),
                waypoints = emptyList(),
                distanceMeters = it,
                timeMs = sc.durationS?.let { s -> (s * 1000).toLong() },
            )
        },
        straightLineMeters = sc.distanceM ?: 0.0,
    )
}

/** Wire shape for sharing a local spin's results with the convoy - see
 *  ConvoyLiveClient.sendSpinOffer. */
internal fun List<RouteCandidate>.asSpinCandidates(): List<SpinCandidate> = map { c ->
    SpinCandidate(
        lat = c.destination.lat,
        lon = c.destination.lon,
        distanceM = c.route?.distanceMeters ?: c.straightLineMeters,
        durationS = c.route?.timeMs?.let { it / 1000.0 },
        name = c.name,
    )
}

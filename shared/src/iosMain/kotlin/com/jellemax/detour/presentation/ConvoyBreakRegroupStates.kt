package com.jellemax.detour.presentation

import com.jellemax.detour.data.Group
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.drive.BreakRequest
import com.jellemax.detour.drive.RegroupPin

/**
 * [convoyBreakRegroupStateFrom] for Swift (#658). The shared function keys its
 * maps on [RiderId], and Kotlin/Native gives a value class no Swift-visible
 * spelling (see `FlowWatcher.kt`'s "Model properties..." section), so a
 * `Map<RiderId, …>` argument cannot be built there. This takes the
 * `String`-keyed maps the relay watchers already hand Swift
 * (`BreakRequestsWatcher`, `FriendPositionsWatcher`) and rekeys them; every
 * rule stays in commonMain.
 */
object ConvoyBreakRegroupStates {
    fun from(
        convoy: Group?,
        selfId: String,
        breakRequests: Map<String, BreakRequest>,
        pin: RegroupPin?,
        positions: Map<String, LatLon>,
        nowMs: Long,
    ): ConvoyBreakRegroupState = convoyBreakRegroupStateFrom(
        convoy = convoy,
        selfId = RiderId(selfId),
        breakRequests = breakRequests.mapKeys { RiderId(it.key) },
        pin = pin,
        positions = positions.mapKeys { RiderId(it.key) },
        nowMs = nowMs,
    )
}

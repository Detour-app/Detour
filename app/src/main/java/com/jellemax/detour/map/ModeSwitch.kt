package com.jellemax.detour.map

import com.jellemax.detour.data.RouteCandidate
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.TravelMode

/**
 * What survives a change of travel mode, and what does not.
 *
 * Switching from Moto to Car invalidates a spin four ways at once — the radius
 * bounds are per-mode, and the destination, the route and the candidate spread
 * were all drawn for roads the other mode may not take. `MapScreen.selectMode`
 * used to state that by clearing six `var`s in a row, which is a rule written
 * as six assignments: correct only for as long as nobody adds a seventh piece
 * of spin state and forgets this list.
 *
 * Stating it as one value makes the rule reviewable and lets a test hold it,
 * which the six assignments could not. Pure: no Settings write, no convoy call
 * — the caller still does both, because both are side effects on other
 * systems and neither belongs in a rule about what a spin result means.
 */
data class ModeSwitch(
    val radiusKm: Float,
    val minRadiusKm: Float,
    val destination: LatLon?,
    val destinationName: String?,
    val route: RouteResult?,
    val candidates: List<RouteCandidate>,
    /** Whether the caller must also clear a convoy vote round. A spin's
     *  candidates are mode-specific, so a switch away must not leave a stale
     *  round on everyone else's screen. */
    val clearSpinOffer: Boolean,
)

/**
 * The state a switch to [to] leaves behind, or null when [to] is already the
 * current mode and nothing should change at all.
 *
 * Returning null rather than an unchanged [ModeSwitch] keeps the no-op
 * explicit: `selectMode` opened with `if (m == mode) return` precisely because
 * re-selecting the mode you are already in must not wipe a spin you just did,
 * and that guard is the easiest line in the function to delete by accident.
 */
fun modeSwitch(from: TravelMode, to: TravelMode, hasSpinOffer: Boolean): ModeSwitch? {
    if (from == to) return null
    return ModeSwitch(
        radiusKm = to.defaultKm,
        minRadiusKm = 0f,
        destination = null,
        destinationName = null,
        route = null,
        candidates = emptyList(),
        clearSpinOffer = hasSpinOffer,
    )
}

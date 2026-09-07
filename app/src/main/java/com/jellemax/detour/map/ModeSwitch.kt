package com.jellemax.detour.map

import com.jellemax.detour.data.RouteCandidate
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.data.TravelMode

/**
 * What survives a change of travel mode, and what does not.
 *
 * Switching mode invalidates the parts of a *spin* that were drawn for one
 * mode's roads — the radius bounds are per-mode, and a loop route and a
 * candidate spread were both planned on a profile the other mode may not
 * take. It does **not** clear a concrete `destination`: a place is a place
 * whichever vehicle you take to it, and the navigation dock (#254) is where
 * a rider switches Moto/Car for a destination they have already picked. The
 * dock's own `route` is nulled by the caller so the next Start re-fetches on
 * the new profile; that is not this rule's concern.
 *
 * `MapScreen.selectMode` used to state this as a row of assignments, a rule
 * written as assignments: correct only for as long as nobody adds another
 * piece of spin state and forgets the list. Stating it as one value makes the
 * rule reviewable and lets a test hold it. Pure: no Settings write, no convoy
 * call — the caller still does both, because both are side effects on other
 * systems and neither belongs in a rule about what a spin result means.
 */
data class ModeSwitch(
    val radiusKm: Float,
    val minRadiusKm: Float,
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
        route = null,
        candidates = emptyList(),
        clearSpinOffer = hasSpinOffer,
    )
}

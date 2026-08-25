package com.jellemax.detour.map

import com.jellemax.detour.data.TravelMode

/**
 * Every decision the spin dock's mode swipe makes. Pure: values in, one answer
 * out, no clock of its own, no Compose and no Android.
 *
 * It lives here rather than inside `SpinDock` because this repo has no Compose
 * test infrastructure - CI runs `:app:testDebugUnitTest` and
 * `:shared:testDebugUnitTest` only, with no Robolectric and no `androidTest`
 * source set. Arithmetic left inside a composable is arithmetic no test can
 * reach. Same reasoning as [NavPolicy] and [CameraAuthority].
 *
 * All distances are in **dp** and all velocities in **dp/s**. The caller owns
 * the px conversion, because only it has a Density.
 */
internal object ModeSwipePolicy {

    /** Travel past this commits the switch on release. */
    const val COMMIT_DP = 84f

    /** ...or a release faster than this, however short the travel. */
    const val FLING_DP_PER_S = 400f

    /** A blocked drag is allowed this much travel before it stops following the
     *  finger: enough to read as received, not enough to read as working. */
    const val BLOCKED_CLAMP_DP = 8f

    /** How much of the finger's travel past the limit still reaches the card. */
    const val RESISTANCE = 0.35f

    fun blockedReason(spinning: Boolean, tracking: Boolean): String? = when {
        // The dice button cancels a spin, so name the one with a visible exit
        // first when both are true.
        spinning -> "Cancel the spin to change mode"
        tracking -> "Stop the trip to change mode"
        else -> null
    }

    /** With two modes there is no third target to swipe toward: either
     *  direction means "the other one". */
    fun other(mode: TravelMode): TravelMode =
        if (mode == TravelMode.MOTO) TravelMode.CAR else TravelMode.MOTO
}

package com.jellemax.detour.map

import com.jellemax.detour.data.TravelMode
import kotlin.math.abs

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

    /** Below this, a release is a tap that jittered, not a swipe. The gesture
     *  detector only fires past touch slop, so this is a floor under the
     *  arithmetic rather than the slop threshold itself. */
    const val MIN_INTENT_DP = 1f

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

    /**
     * How far the card's left cell should sit for a raw finger travel of
     * [rawDp]. Linear up to the limit, then compressed by [RESISTANCE], so the
     * card never runs away from the finger and the threshold is felt.
     *
     * [blocked] only changes where the compression starts: a refused swipe still
     * moves, it just stops tracking almost immediately.
     */
    fun dragOffsetDp(rawDp: Float, blocked: Boolean): Float {
        val limit = if (blocked) BLOCKED_CLAMP_DP else COMMIT_DP
        val magnitude = abs(rawDp)
        if (magnitude <= limit) return rawDp
        val sign = if (rawDp < 0f) -1f else 1f
        return sign * (limit + (magnitude - limit) * RESISTANCE)
    }

    /**
     * Whether releasing here switches the mode. [offsetDp] is the *resisted*
     * offset the card is actually showing, not the raw finger travel.
     *
     * The velocity arm is what makes a quick flick work without a long pull.
     */
    fun commits(offsetDp: Float, velocityDpPerS: Float, blocked: Boolean): Boolean {
        if (blocked) return false
        if (abs(offsetDp) < MIN_INTENT_DP) return false
        return abs(offsetDp) >= COMMIT_DP || abs(velocityDpPerS) >= FLING_DP_PER_S
    }
}

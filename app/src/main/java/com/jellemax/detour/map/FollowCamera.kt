package com.jellemax.detour.map

import com.jellemax.detour.ui.CAM_RESUME_QUIET_MS
import com.jellemax.detour.ui.CAM_RESUME_SPEED_MPS

/**
 * When a parked camera goes back to following.
 *
 * Two functions rather than one predicate, because the call site asks the
 * question in two places: an effect guard whose key list restarts the effect
 * when the answer changes, and a per-fix test inside a `lastFix` collector.
 * Collapsing them would read the blocker flags live per fix instead of at
 * restart, which is a behaviour change - see `detour-compose-state-hazards` §1.
 */
internal object FollowCamera {

    /**
     * Whether a parked camera should be watching for a drive-off at all. Not
     * while a spin is on screen (own or a convoy's, still being voted on): the
     * candidates are the whole reason the map is parked where it is, and a
     * passenger spinning at speed would otherwise never get to read them.
     */
    fun shouldWatch(
        camSuspended: Boolean,
        spinning: Boolean,
        hasCandidates: Boolean,
        hasSpinOffer: Boolean,
    ): Boolean = camSuspended && !spinning && !hasCandidates && !hasSpinOffer

    /**
     * Whether this fix ends the park: moving, and long enough since the last
     * gesture. The quiet period is what stops a two-finger zoom at 80 km/h from
     * being yanked out from under you mid-gesture.
     */
    fun shouldResume(speedMps: Double, nowMs: Long, lastGestureMs: Long): Boolean =
        speedMps >= CAM_RESUME_SPEED_MPS && nowMs - lastGestureMs > CAM_RESUME_QUIET_MS
}

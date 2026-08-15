package com.jellemax.detour.drive

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.data.SpeedCameras

/**
 * Whether a speed camera ahead is worth interrupting for, and the wording if it
 * is. Chime when one lies ahead, close, and we're over the posted limit - the one
 * case worth interrupting for. One chime per camera: [State.warnedAt] holds the
 * camera last sounded for and clears once nothing is in range, re-arming for the
 * next. Silent when the limit is unknown: we can't judge "too fast".
 *
 * **Decision and wording here, delivery per platform** - the `CircleEvents.kt`
 * shape. This machine knows nothing about tones, speech or toasts, which is why
 * it returns a [Step] rather than taking a callback: the phone chimes and speaks,
 * the head unit chimes, speaks and shows a car toast, and iOS will do whatever
 * iOS does, without any of that leaking into a shared decision.
 *
 * **No clock**, unlike [SectionAverageTracker]. The latch is positional, not
 * temporal: there is no cooldown, and nothing here measures an interval, so there
 * is no timestamp to inject.
 *
 * The posted limit is resolved by the caller, deliberately. The phone passes
 * `navProgress?.speedLimitKmh ?: ambientSpeedLimitKmh` because it has an ambient
 * sign to fall back on; the head unit passes the route's limit alone because it
 * does not. "Does this surface have an ambient sign" is a per-surface fact, and
 * keeping it at the call site is what stops it becoming a branch in here.
 */
object CameraWarner {

    /** How far off the heading a camera may lie and still count as ahead. */
    const val AHEAD_WEDGE_DEG = 45.0

    /** Over the posted limit by this much before a camera is worth interrupting
     *  for. Under it you are not the driver the camera is about to photograph. */
    const val OVER_LIMIT_KMH = 3.0

    /** [warnedAt] is the camera last sounded for, or null when nothing is in
     *  range. A position, not a timestamp - see the class KDoc. */
    data class State(val warnedAt: LatLon? = null)

    sealed interface Outcome {
        data object Silent : Outcome

        /** [text] is the wording. Delivery - tone, speech, toast - is the caller's. */
        data class Warn(val at: LatLon, val text: String) : Outcome
    }

    data class Step(val state: State, val outcome: Outcome)

    /**
     * One GPS fix. [cameras] is whatever the caller's prefetch holds - this
     * machine never fetches. [speedKmh] and [limitKmh] are both km/h, and a null
     * [limitKmh] means the limit here is unknown, so nothing is worth
     * interrupting for. A null [headingDeg] skips the wedge and judges on
     * distance alone: with no bearing there is no "behind".
     */
    fun onFix(
        state: State,
        cameras: List<SpeedCameras.Camera>,
        at: LatLon,
        headingDeg: Double?,
        speedKmh: Double,
        limitKmh: Double?,
    ): Step {
        val ahead = cameras.filter { cam ->
            RoadRoulette.distanceMeters(at, cam.at) <= SpeedCameras.WARN_METERS &&
                (headingDeg == null ||
                    RoadRoulette.withinWedge(at, cam.at, headingDeg, AHEAD_WEDGE_DEG))
        }.minByOrNull { RoadRoulette.distanceMeters(at, it.at) }
            // Nothing in range clears the latch, which is what re-arms it for the
            // next camera. Being in range and *not* too fast does not.
            ?: return Step(State(warnedAt = null), Outcome.Silent)

        val tooFast = limitKmh != null && speedKmh > limitKmh + OVER_LIMIT_KMH
        if (!tooFast || ahead.at == state.warnedAt) return Step(state, Outcome.Silent)
        return Step(State(warnedAt = ahead.at), Outcome.Warn(ahead.at, WARNING_TEXT))
    }

    /** The wording, declared once for every surface. The phone's own comment said
     *  this literal was waiting for this machine to own it. */
    private const val WARNING_TEXT = "Speed camera ahead"
}

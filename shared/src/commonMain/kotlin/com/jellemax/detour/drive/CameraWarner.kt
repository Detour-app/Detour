package com.jellemax.detour.drive

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.SpeedCameras
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Whether a speed camera ahead is worth interrupting for, and the wording if it
 * is. Chime when one lies ahead, close, and we're over the posted limit - the one
 * case worth interrupting for. One chime per camera: [State.warnedAt] holds the
 * camera last sounded for and clears once nothing is in range, re-arming for the
 * next. Silent when the limit is unknown: we can't judge "too fast". The rider
 * can widen or mute this from Settings - see [Options] (#496).
 *
 * **Decision and wording here, delivery per platform** - the `CircleEvents.kt`
 * shape. This machine knows nothing about tones, speech or toasts, which is why
 * it returns a [Step] rather than taking a callback: the phone chimes, and
 * speaks only while voice guidance is on; the head unit chimes, speaks and shows
 * a car toast; iOS speaks through `NavVoice` while voice guidance is on. None of
 * that leaks into a shared decision.
 *
 * [Step.countdown] is the distance left, for a surface that shows it: the
 * phone's camera banner (#549).
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
 *
 * The camera itself can also tag a limit ([SpeedCameras.Camera.maxspeedKmh]),
 * and that one wins over the caller's when both exist - it's the limit the
 * camera is actually enforcing, and it's how a camera on an otherwise-untagged
 * road gets judged at all.
 */
object CameraWarner {

    /** How far off the heading a camera may lie and still count as ahead. */
    const val AHEAD_WEDGE_DEG = 45.0

    /** How far a tagged camera's facing bearing may sit from dead-opposite your
     *  heading and still count as pointed at you (issue #318). Same magnitude as
     *  [AHEAD_WEDGE_DEG] for the same reason - a road bends under both. Only
     *  applies when the camera is tagged; an untagged one keeps the wedge as its
     *  only test. */
    const val CAMERA_FACING_TOLERANCE_DEG = 45.0

    /** Over the posted limit by this much before a camera is worth interrupting
     *  for. Under it you are not the driver the camera is about to photograph. */
    const val OVER_LIMIT_KMH = 3.0

    /** Seconds of notice a warning aims for (#497). A fixed 400 m gave 12 s at
     *  120 km/h and 29 s at 50; this scales the reach with speed instead, and
     *  [SpeedCameras.WARN_METERS] floors it so slow roads keep today's reach. */
    const val WARN_LEAD_S = 15.0

    /** How far ahead a camera counts at [speedKmh]: [WARN_LEAD_S] of travel at
     *  the current speed, never under [SpeedCameras.WARN_METERS]. Distance from
     *  speed, not a timer - the class still holds no clock. */
    internal fun warnMeters(speedKmh: Double): Double =
        max(SpeedCameras.WARN_METERS, speedKmh / 3.6 * WARN_LEAD_S)

    /** [warnedAt] is the camera last sounded for, or null when nothing is in
     *  range. A position, not a timestamp - see the class KDoc. [warnedReachMeters]
     *  is the reach it was warned at: braking shrinks [warnMeters] faster than
     *  the distance, and without it the camera would leave range, clear the
     *  latch and chime again on re-entry. [options] are the rider's settings,
     *  refreshed by the caller on every fix; they ride on the state to keep
     *  [onFix] under detekt's seven-parameter limit. */
    data class State(
        val warnedAt: LatLon? = null,
        val warnedReachMeters: Double = 0.0,
        val options: Options = Options(),
    )

    sealed interface Outcome {
        data object Silent : Outcome

        /** [text] is the wording. Delivery - tone, speech, toast - is the caller's. */
        data class Warn(val at: LatLon, val text: String) : Outcome
    }

    /** The camera last warned for, while it is still ahead and in reach, with
     *  [distanceMeters] left to it - recomputed every fix, so a banner can count
     *  down (#548). Straight-line distance, like the reach test. [kind] is
     *  there so a surface can word it in the rider's language; [text] is the
     *  English wording the voice speaks. */
    data class Countdown(
        val at: LatLon,
        val text: String,
        val distanceMeters: Double,
        val kind: SpeedCameras.CameraKind,
    )

    /** [outcome] fires once per camera; [countdown] holds on every fix after
     *  that until the camera is passed (leaves the wedge), drops out of reach,
     *  or warnings are switched off. Slowing down under the limit does not end
     *  it: the warning was given, and slowing is what it asked for. */
    data class Step(val state: State, val outcome: Outcome, val countdown: Countdown? = null)

    /** The rider's camera-warning settings (#496). Callers build them with
     *  [cameraWarnerOptions] and set them on [State] so this machine stays free of storage. The defaults are the
     *  behaviour before the settings existed. [enabled] mutes every kind - a
     *  red-light camera shares the speed-camera switch. [whenNotSpeeding] warns
     *  for a speed camera at or under a known limit; [whenLimitUnknown] warns
     *  for one where no limit is known. */
    data class Options(
        val enabled: Boolean = true,
        val whenNotSpeeding: Boolean = false,
        val whenLimitUnknown: Boolean = false,
    )

    /**
     * One GPS fix. [cameras] is whatever the caller's prefetch holds - this
     * machine never fetches. [speedKmh] and [limitKmh] are both km/h, and a null
     * [limitKmh] means the limit here is unknown, so nothing is worth
     * interrupting for. A null [headingDeg] skips the wedge and judges on
     * distance alone: with no bearing there is no "behind". The rider's settings
     * come from [State.options]; the default is the rule as it stood before them.
     */
    fun onFix(
        state: State,
        cameras: List<SpeedCameras.Camera>,
        at: LatLon,
        headingDeg: Double?,
        speedKmh: Double,
        limitKmh: Double?,
    ): Step {
        // Off clears the latch too, so switching back on mid-approach warns
        // for the camera ahead rather than treating it as already sounded.
        if (!state.options.enabled) return Step(State(options = state.options), Outcome.Silent)
        val reachMeters = warnMeters(speedKmh)
        val inRange = cameras.filter { cam ->
            val reach = if (cam.at == state.warnedAt) max(reachMeters, state.warnedReachMeters) else reachMeters
            RoadRoulette.distanceMeters(at, cam.at) <= reach &&
                (headingDeg == null ||
                    RoadRoulette.withinWedge(at, cam.at, headingDeg, AHEAD_WEDGE_DEG)) &&
                (cam.facingDeg == null || headingDeg == null || facesYou(cam.facingDeg, headingDeg))
        }
        val ahead = inRange.minByOrNull { RoadRoulette.distanceMeters(at, it.at) }
            // Nothing in range clears the latch, which is what re-arms it for the
            // next camera. Being in range and *not* too fast does not.
            ?: return Step(State(options = state.options), Outcome.Silent)

        // The camera's own tagged limit is the one it enforces, so it beats the
        // caller's ambient/route limit where both exist (#319) - and is the only
        // limit at all on an otherwise-untagged road.
        val effectiveLimitKmh = ahead.maxspeedKmh ?: limitKmh
        if (!worthWarning(ahead.kind, speedKmh, effectiveLimitKmh, state.options) || ahead.at == state.warnedAt) {
            return Step(state, Outcome.Silent, countdownFor(state.warnedAt, inRange, at))
        }
        val warned = State(ahead.at, reachMeters, state.options)
        return Step(warned, Outcome.Warn(ahead.at, warningTextFor(ahead.kind)), countdownFor(ahead.at, inRange, at))
    }

    /** The countdown for the camera at [warnedAt], if it is still among the
     *  cameras [inRange] - looked up by position rather than taken from the
     *  nearest, so a closer camera not worth warning for doesn't hide it. */
    private fun countdownFor(warnedAt: LatLon?, inRange: List<SpeedCameras.Camera>, at: LatLon): Countdown? {
        val cam = inRange.firstOrNull { it.at == warnedAt } ?: return null
        return Countdown(cam.at, warningTextFor(cam.kind), RoadRoulette.distanceMeters(at, cam.at), cam.kind)
    }

    private fun worthWarning(
        kind: SpeedCameras.CameraKind,
        speedKmh: Double,
        effectiveLimitKmh: Double?,
        options: Options,
    ): Boolean =
        // A red-light camera doesn't measure speed - it's worth announcing on
        // approach regardless, which is exactly when a speed-only camera stays
        // silent (maxke24/Detour#317). Combined inherits the same rule: a device
        // that's also a red-light camera is worth announcing even at the limit.
        when (kind) {
            SpeedCameras.CameraKind.SPEED -> if (effectiveLimitKmh == null) {
                options.whenLimitUnknown
            } else {
                options.whenNotSpeeding || speedKmh > effectiveLimitKmh + OVER_LIMIT_KMH
            }
            SpeedCameras.CameraKind.RED_LIGHT, SpeedCameras.CameraKind.COMBINED -> true
        }

    /** The wording, declared once for every surface. The phone's own comment said
     *  this literal was waiting for this machine to own it. */
    private fun warningTextFor(kind: SpeedCameras.CameraKind): String = when (kind) {
        SpeedCameras.CameraKind.SPEED -> "Speed camera ahead"
        SpeedCameras.CameraKind.RED_LIGHT -> "Red light camera ahead"
        SpeedCameras.CameraKind.COMBINED -> "Speed and red light camera ahead"
    }

    /** Whether a camera tagged facing [facingDeg] is pointed at someone heading
     *  [headingDeg] - i.e. its facing bearing is roughly opposite your own,
     *  within [CAMERA_FACING_TOLERANCE_DEG]. A camera facing north watches
     *  southbound traffic. */
    private fun facesYou(facingDeg: Double, headingDeg: Double): Boolean {
        val diff = abs((facingDeg + 180.0) - headingDeg) % 360.0
        return min(diff, 360.0 - diff) <= CAMERA_FACING_TOLERANCE_DEG
    }
}

/** The rider's [CameraWarner.Options] as `Settings` holds them right now - the
 *  one mapping the phone, the car and iOS all put on the state they pass to
 *  [CameraWarner.onFix]. */
fun cameraWarnerOptions(): CameraWarner.Options = CameraWarner.Options(
    enabled = Settings.cameraWarnings.value,
    whenNotSpeeding = Settings.cameraWarnNotSpeeding.value,
    whenLimitUnknown = Settings.cameraWarnLimitUnknown.value,
)

package com.jellemax.detour.map

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.ui.CAM_BEARING_EPS_DEG
import com.jellemax.detour.ui.CAM_POS_EPS_DEG
import com.jellemax.detour.ui.CAM_SNAP_METERS
import com.jellemax.detour.ui.CAM_ZOOM_EPS
import kotlin.math.PI
import kotlin.math.abs

/**
 * Where the vehicle is *now*, and whether the camera has any work to do this frame.
 *
 * A fix is old before it is ever drawn — it takes time to acquire and arrives about once a
 * second — and a first-order ease chasing it settles a further `v * tau` behind. Easing
 * toward the raw fix therefore guarantees a lag that no value of tau removes: lowering it
 * trades the lag back for the per-fix jerk the ease exists to smooth. Predicting forward
 * converts a guaranteed lag into a bounded error that each new fix re-anchors.
 *
 * Kept here rather than in `shared/` deliberately: iOS has no easing loop — MapLibre iOS
 * animates its own camera — so the consumers are MapScreen and, later, CarMapRenderer, both
 * under `app/`. This belongs beside [FollowCamera] and [CameraAuthority].
 */
object MapMotion {

    /** Below this the reported bearing is noise, so there is no direction to predict along. */
    const val MIN_PREDICT_MPS = 2.0

    /**
     * Longest gap worth extrapolating across. Bounds a tunnel and a GPS dropout: past this
     * the last bearing and speed are no longer evidence of where the vehicle is, and
     * extrapolating further invents distance. 1.5 s is 50 m at 120 km/h.
     *
     * This used to also bound a skewed device clock, back when the age was a provider wall
     * clock subtracted from ours. It no longer has to — [predict] takes a monotonic pair —
     * but the tunnel and dropout cases are reason enough on their own.
     */
    const val MAX_PREDICT_MS = 1500L

    /**
     * [at] carried forward along [bearingDeg] by however far [speedMps] covers in the fix's
     * age plus [leadSeconds].
     *
     * Both timestamps must come from the **monotonic** clock —
     * [android.os.SystemClock.elapsedRealtime] and the fix's own
     * [android.location.Location.getElapsedRealtimeNanos], which is what
     * [com.jellemax.detour.tracking.Fix.elapsedRealtimeMs] carries. Passing a wall clock
     * compiles and is wrong: a device clock running persistently fast then biases every
     * prediction forward by a constant, which does not average out.
     *
     * The lead is what cancels the ease's own lag. A first-order lag driven at constant
     * velocity settles `v * tau` behind its input, so a target that leads by exactly tau
     * puts the *camera* on the true position rather than the target. Pass 0.0 for anything
     * drawn directly, such as the position marker.
     *
     * Returns [at] unchanged when there is nothing to predict from.
     */
    fun predict(
        at: LatLon,
        bearingDeg: Float?,
        speedMps: Double,
        fixElapsedMs: Long,
        nowElapsedMs: Long,
        leadSeconds: Double,
    ): LatLon {
        if (bearingDeg == null || speedMps < MIN_PREDICT_MPS) return at
        val ageMs = (nowElapsedMs - fixElapsedMs).coerceIn(0L, MAX_PREDICT_MS)
        val seconds = ageMs / 1000.0 + leadSeconds
        if (seconds <= 0.0) return at
        return RoadRoulette.offset(at, speedMps * seconds, bearingDeg * PI / 180.0)
    }

    /**
     * True when [to] is too far from [from] to be continuous motion, so the camera should be
     * placed there rather than eased toward it.
     *
     * Without this, returning to the app after driving away with it backgrounded eases the
     * camera across the whole distance: `camTarget` keeps tracking from a raw collector on a
     * foreground service, while the frame loop's position is a coroutine local that never
     * re-anchors, and the 0.1 s dt clamp still closes ~25% of the gap per frame.
     */
    fun shouldSnap(from: LatLon, to: LatLon): Boolean =
        RoadRoulette.distanceMeters(from, to) > CAM_SNAP_METERS

    /**
     * True while the camera still has work: it has not converged on its target, or the
     * target itself moved since the last frame.
     *
     * The question this replaces was "did *this frame* move enough to be worth a redraw",
     * which cannot tell a slow camera from a settled one. At CAM_POS_EPS_DEG — 0.14 m of
     * longitude at 51N — a 20 km/h camera moves 0.09 m per frame and so was pushed only
     * every third frame, which is the visible stepping. Asking instead whether the camera is
     * *at rest* keeps the idle-map optimisation (a parked map still does no work) without
     * quantising a moving one.
     */
    fun shouldPush(
        camLat: Double,
        camLon: Double,
        camZoom: Double,
        camBearing: Float,
        tgtLat: Double,
        tgtLon: Double,
        tgtZoom: Double,
        tgtBearing: Float,
        targetMoved: Boolean,
        neverPushed: Boolean,
    ): Boolean {
        if (neverPushed || targetMoved) return true
        var dBearing = (camBearing - tgtBearing) % 360f
        if (dBearing > 180f) dBearing -= 360f
        if (dBearing < -180f) dBearing += 360f
        val converged = abs(camLat - tgtLat) <= CAM_POS_EPS_DEG &&
            abs(camLon - tgtLon) <= CAM_POS_EPS_DEG &&
            abs(camZoom - tgtZoom) <= CAM_ZOOM_EPS &&
            abs(dBearing) <= CAM_BEARING_EPS_DEG
        return !converged
    }
}

package com.jellemax.detour.map

import kotlin.math.abs

/**
 * Compass-wrap arithmetic for the heading-up camera. One home rather than the
 * two verbatim copies it replaced — `ui/MapCameraTuning` and a private block in
 * `car/CarMapRenderer` — so retuning [CAM_BEARING_EPS_DEG] can't leave the
 * camera and the marker in the same frame disagreeing about what a turn is.
 *
 * Kept under `app/.../map/` beside [MapMotion] for the reason MapMotion gives:
 * iOS has no easing loop — MapLibre iOS animates its own camera — so the only
 * consumers are MapScreen and CarMapRenderer, both here.
 */

/** Exponentially smooths a compass bearing toward [target], taking the shortest
 *  way round the 0/360 wrap, so heading-up rotation eases instead of snapping to
 *  each noisy raw GPS fix. A null [current] — no bearing yet — snaps to [target]. */
internal fun smoothBearing(current: Float?, target: Float, alpha: Float = 0.3f): Float {
    if (current == null) return target
    var delta = (target - current) % 360f
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return (current + delta * alpha + 360f) % 360f
}

/** Shortest angular distance between two bearings, 0..180 — so a 359 to 1 turn
 *  reads as 2 degrees rather than 358. */
internal fun bearingDelta(a: Float, b: Float): Float {
    var d = (a - b) % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return abs(d)
}

// Camera bearing easing time constant, in seconds: each frame the camera closes
// the same fraction of its gap to the latest fix, covering ~63% of it in one tau.
internal const val CAM_BEARING_TAU = 0.5

// Below a tenth of a degree of remaining rotation, stop pushing the camera — the
// turn is done and a parked map should do no work. Also gates the marker in
// CarMapRenderer, which is the whole point of these living in one place.
internal const val CAM_BEARING_EPS_DEG = 0.1f

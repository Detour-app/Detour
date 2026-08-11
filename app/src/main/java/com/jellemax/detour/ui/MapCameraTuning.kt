package com.jellemax.detour.ui

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.data.SpeedCameras

/** Exponentially smooths a compass bearing toward [target], taking the
 *  shortest way round the 0/360 wrap, so heading-up rotation eases instead
 *  of snapping to each noisy raw GPS fix. */
internal fun smoothBearing(current: Float?, target: Float, alpha: Float = 0.3f): Float {
    if (current == null) return target
    var delta = (target - current) % 360f
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return (current + delta * alpha + 360f) % 360f
}

// Camera easing time constants, in seconds: each frame the camera closes the
// same fraction of its gap to the latest fix, covering ~63% of it in one tau.
// Small enough that the map never visibly lags the road, large enough that a
// noisy fix can't yank it.
internal const val CAM_POS_TAU = 0.35
internal const val CAM_BEARING_TAU = 0.5
internal const val CAM_ZOOM_TAU = 1.2

// The speed readout is eased the same way, per frame rather than per fix: GPS
// speed arrives about once a second, and a number that jumps once a second
// reads as a laggy app even when the fix behind it is current. Short tau — the
// readout has to be honest about braking, not just smooth.
internal const val SPEED_TAU = 0.30
// Below ~0.15 km/h of remaining gap the rounded number can't change; snap and
// stop recomposing so a steady cruise doesn't repaint the HUD every frame.
internal const val SPEED_EPS_KMH = 0.15

// Below these, an eased camera step isn't worth a redraw: ~0.2 m of pan (well
// sub-pixel at driving zooms), a hair of zoom, a tenth of a degree of rotation.
// Once the ease settles inside all three, setCamera is skipped and the map —
// and the fog view riding on its camera-move callback — goes quiet.
internal const val CAM_POS_EPS_DEG = 2e-6
internal const val CAM_ZOOM_EPS = 2e-3
internal const val CAM_BEARING_EPS_DEG = 0.1f

// Padding kept around a fitted route/candidate spread so pins and the trip card
// don't sit against the screen edge.
internal const val FIT_PADDING_PX = 140

// How many round trips to roll before picking one. GraphHopper's round_trip is
// seed-driven and its curvature weighting only biases the search, so seeds
// differ a lot in how much of the loop is actually bends — rolling a few and
// keeping the curviest is what turns "avoids motorways" into a ride worth
// taking. Three: the requests run in parallel, so this costs latency only when
// the server is already saturated, and the gain flattens out after ~3 rolls.
internal const val CURVY_CANDIDATES = 3

// Panning or pinching parks the camera instead of forcing you to hunt for the
// follow button. Driving off takes it back: above this speed, this long after
// you last touched the map. The quiet period is what stops a two-finger zoom at
// 80 km/h from being yanked out from under you mid-gesture.
internal const val CAM_RESUME_SPEED_MPS = 3.0
internal const val CAM_RESUME_QUIET_MS = 8_000L

// Circle members post a fix every CIRCLE_SYNC_INTERVAL_MS (TripTrackingService)
// at most, so polling faster than that would just re-fetch the same row —
// this matches that cadence rather than guessing a separate one.
internal const val CIRCLE_FIX_POLL_MS = 120_000L

// How close to a section's device node counts as passing it, for entering and
// leaving a trajectcontrole average-speed measurement.
internal const val SECTION_GATE_METERS = 60.0

// How far off your heading the far end of a section may lie and still count as
// driving into it. Wide, because a long section can curve away — it only has to
// separate "the other end is ahead of me" from "behind me, I'm on my way out".
internal const val SECTION_WEDGE_DEG = 75.0

/**
 * The far end of [section], if this fix is entering it: within the gate of one
 * end and heading towards the other. Null otherwise.
 *
 * The heading test is what makes the gate mean "driving the section". Passing a
 * device node says nothing on its own — you pass one on the way *out* too, and
 * on every side street that crosses one — and matching on that alone used to
 * start a measurement as you left a section, which is what put an average on
 * screen after the trajectcontrole instead of during it.
 */
internal fun sectionExitGate(
    section: SpeedCameras.Section,
    pos: LatLon,
    headingDeg: Double,
): List<LatLon>? {
    fun atGate(end: List<LatLon>) =
        end.any { RoadRoulette.distanceMeters(pos, it) < SECTION_GATE_METERS }
    fun ahead(end: List<LatLon>) =
        end.any { RoadRoulette.withinWedge(pos, it, headingDeg, SECTION_WEDGE_DEG) }
    return when {
        atGate(section.endA) && ahead(section.endB) -> section.endB
        atGate(section.endB) && ahead(section.endA) -> section.endA
        else -> null
    }
}

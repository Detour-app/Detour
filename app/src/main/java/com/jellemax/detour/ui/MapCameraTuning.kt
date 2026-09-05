package com.jellemax.detour.ui

import com.jellemax.detour.data.CirclePresence

// The bearing-wrap helpers and CAM_BEARING_* constants live in
// `map/BearingMath.kt` — the car camera loop needs them too, and a second copy
// there was how the camera and the marker ended up reading different constants.

// Camera easing time constants, in seconds: each frame the camera closes the
// same fraction of its gap to the latest fix, covering ~63% of it in one tau.
// Small enough that the map never visibly lags the road, large enough that a
// noisy fix can't yank it.
internal const val CAM_POS_TAU = 0.35
internal const val CAM_ZOOM_TAU = 1.2

// The speed readout is eased the same way, per frame rather than per fix: GPS
// speed arrives about once a second, and a number that jumps once a second
// reads as a laggy app even when the fix behind it is current. Short tau — the
// readout has to be honest about braking, not just smooth. Kept deliberately
// low: a first-order filter trails a ramp by tau x rate, so at hard
// acceleration every extra 0.1s here is a few km/h of visible lag before the
// number snaps back.
internal const val SPEED_TAU = 0.20
// Below ~0.15 km/h of remaining gap the rounded number can't change; snap and
// stop recomposing so a steady cruise doesn't repaint the HUD every frame.
internal const val SPEED_EPS_KMH = 0.15

// How far over the posted limit the speed readout tolerates before it turns
// red. One home for a threshold two surfaces draw: this HUD's dial and the
// Android Auto renderer's, which paints its own disc rather than sharing a
// composable. `:shared`'s speedHudStateFrom takes it as an argument and the
// call sites name it, so the mapper stays pure and this stays the only value.
internal const val OVER_LIMIT_TOLERANCE_KMH = 5.0

// A standstill-only optimisation: these decide when a camera that has converged on a
// target that is itself not moving may stop doing work — ~0.2 m of pan (well sub-pixel
// at driving zooms), a hair of zoom (rotation is gated by CAM_BEARING_EPS_DEG in
// map/BearingMath.kt). They no longer gate a moving camera — MapMotion.shouldPush also
// pushes whenever the target moved this frame, so the camera is pushed on every frame
// while driving (measured pushes == frames in 198/198 samples). Once the ease settles
// inside all three *and* the target is still, setCamera is skipped and the map — and the
// fog view riding on its camera-move callback — goes quiet.
internal const val CAM_POS_EPS_DEG = 2e-6
internal const val CAM_ZOOM_EPS = 2e-3

// Past this, a jump is not continuous motion and easing toward it would sweep the
// camera — and MapLibre's tile requests — across everything in between. A frame can
// legitimately move 3.3 m at most (120 km/h against the 0.1 s dt clamp), and GPS
// scatter stays well under this, so anything above it is a resume from background, a
// tunnel exit, or a first fix after an outage. Those all want a teleport.
internal const val CAM_SNAP_METERS = 250.0

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

// Circle members post a fix every CirclePresence.ACTIVE_INTERVAL_MS (shared/)
// at most, so polling faster than that would just re-fetch the same row —
// read from there rather than retyped, so the two can't drift apart.
internal const val CIRCLE_FIX_POLL_MS = CirclePresence.ACTIVE_INTERVAL_MS

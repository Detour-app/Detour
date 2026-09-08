package com.jellemax.detour.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.withFrameNanos
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.NavEngine
import com.jellemax.detour.data.Settings
import com.jellemax.detour.map.CAM_BEARING_EPS_DEG
import com.jellemax.detour.map.CAM_BEARING_TAU
import com.jellemax.detour.map.CameraAuthority
import com.jellemax.detour.map.MapMotion
import com.jellemax.detour.map.NavPolicy
import com.jellemax.detour.map.bearingDelta
import com.jellemax.detour.map.smoothBearing
import com.jellemax.detour.tracking.Fix
import com.jellemax.detour.tracking.TripTrackingService
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.abs
import kotlin.math.exp

/**
 * The two per-frame loops: the camera easing toward the last fix, and the
 * position dot interpolating between fixes.
 *
 * Deliberately two loops and not one. The camera returns early when it is not
 * active, and a parked camera — after a pan, with follow off, or with a spin
 * result up — is exactly the case the marker loop exists to serve, so a shared
 * bearing accumulator would freeze the icon precisely when it still needs to
 * turn. The measured effect of separating them is in the marker loop's own
 * comment below.
 *
 * These cannot leave a composable: `withFrameNanos` is Compose's frame clock,
 * which is also what makes them free with the screen off — Compose produces no
 * frames while the activity is not resumed.
 */
/**
 * The speedometer, eased per frame toward the display speed.
 *
 * Keyed on nothing: it runs for as long as the map is composed, so the number
 * is always gliding rather than stepping once per fix. It reads
 * `displaySpeedMps` rather than the fix's own speed because a paired OBD2
 * adapter refreshes that between GPS fixes.
 */
@Composable
internal fun MapSpeedEase(retained: RetainedMap) {
    val displaySpeedMps by TripTrackingService.displaySpeedMps.collectAsStateWithLifecycle()
    val speedTarget = rememberUpdatedState(displaySpeedMps * 3.6)
    LaunchedEffect(Unit) {
        var lastNs = withFrameNanos { it }
        while (true) {
            val ns = withFrameNanos { it }
            // Cap only guards a post-resume gap (the frame clock pauses while
            // backgrounded); 0.25s ~= one tau, enough that heavy frame jank
            // during fast motion no longer starves the ease. exp() form is
            // stable at any dt, so this is a smoothness knob, not a safety one.
            val dt = ((ns - lastNs) / 1_000_000_000.0).coerceIn(0.0, 0.25)
            lastNs = ns
            val target = speedTarget.value
            val gap = target - retained.displaySpeedKmh
            retained.displaySpeedKmh =
                if (abs(gap) < SPEED_EPS_KMH) target
                else retained.displaySpeedKmh + gap * (1.0 - exp(-dt / SPEED_TAU))
        }
    }
}

@Composable
internal fun MapCameraLoops(s: MapScreenState, retained: RetainedMap) {
    // Read off the holder rather than taken as parameters: all four were
    // aliases for a field on it, and passing an alias is drilling with extra
    // steps (state-holders.md §14.1).
    val mapLibreMap = retained.map
    val mapOverlays = retained.overlays
    val fogView = retained.fogView
    val cameraActive = s.camAuthority.cameraActive(s.navigating)
    val liveFix by TripTrackingService.lastFix.collectAsStateWithLifecycle()
    // Hoisted out of the frame loop below, and it has to be: a CompositionLocal
    // is only readable in a @Composable. Safe to hold as a plain value across the
    // life of the effect — compose-state-hazards §2's stale-capture rule is about
    // values that change, and DriveClocks holds one instance for the process; a
    // replay re-paces the clock inside it rather than swapping it.
    val clock = LocalDriveClock.current

    val defaultZoom by Settings.defaultZoom.collectAsStateWithLifecycle()
    // The camera itself: one loop, one frame at a time, easing toward whatever
    // the last fix asked for. Compose only produces frames while the activity is
    // resumed, so this costs nothing with the screen off.
    // `haveFix` is a key so that turning follow on before the first fix arrives
    // still starts the loop once it does, instead of leaving it returned-out.
    val haveFix = retained.camTarget != null || s.myLocation != null
    LaunchedEffect(cameraActive, haveFix, mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        if (!cameraActive) {
            // Level back to north-up when the rider switched following *off* -
            // and only then. Every park keeps the bearing it had: a pinch or a
            // pan is a request to change zoom or centre, never to re-orient the
            // map, and levelling one threw away a rotation the rider had set
            // with nothing in the UI to get it back (#260). The rail's compass
            // button is that way back now, offered rather than imposed, and
            // `CameraAuthority.State.northUpAvailable` decides when to show it.
            //
            // The old unconditional write also had to be guarded against
            // pinning a destination framing mid-flight - the pick dispatches
            // DestinationFramed, `cameraActive` flips false, this effect
            // restarts, and the camera would stop wherever the animation had
            // reached. `shouldLevelNorthUp` answers that by naming the park
            // rather than by leaning on levelToNorthUp's bearing == 0 guard.
            if (CameraAuthority.shouldLevelNorthUp(s.camAuthority, s.navigating)) {
                levelToNorthUp(map)
            }
            return@LaunchedEffect
        }
        val start = retained.camTarget ?: s.myLocation ?: return@LaunchedEffect
        var lat = start.lat
        var lon = start.lon
        var bearing = retained.camTargetBearing ?: 0f
        var zoom = map.cameraPosition.zoom.takeIf { it > 1.0 }
            ?: retained.camTargetZoom ?: defaultZoom.toDouble()
        // Whether the camera has ever actually been pushed to the map. MapMotion.shouldPush
        // needs only this as a "first frame" sentinel — it compares the eased lat/lon/zoom/
        // bearing above against the target itself, not against a record of what was last
        // applied — which is what stops the per-frame GL redraw + fog invalidate from
        // running once the ease has settled and the target has stopped moving.
        var neverPushed = true
        var lastTargetLat = Double.NaN
        var lastTargetLon = Double.NaN
        var lastNs = withFrameNanos { it }
        while (true) {
            val ns = withFrameNanos { it }
            // Clamp dt so a dropped frame or a stalled render doesn't teleport us.
            val dt = ((ns - lastNs) / 1_000_000_000.0).coerceIn(0.0, 0.1)
            lastNs = ns

            // Where the vehicle is now, plus CAM_POS_TAU of lead. The lead is what
            // cancels the ease's own steady-state error: a first-order lag driven at
            // constant velocity settles v*tau behind its input, so aiming tau ahead
            // leaves the camera on the true position instead of behind it.
            // Re-read every frame: the fix effect rewrites it, and a null means
            // no fix has set one yet, so the rider's current default applies.
            val targetZoom = retained.camTargetZoom ?: defaultZoom.toDouble()
            val f = liveFix
            val nowElapsed = SystemClock.elapsedRealtime()
            // While the marker is on the route, aim at the point it is drawn at
            // rather than at the fix. The two differ by the whole off-route
            // distance — most of a screen at navigation zoom — so aiming at the
            // fix leaves the rider's own icon sitting well off the crosshair.
            // The lead is still applied, along the *segment* bearing this time:
            // the snapped point already carries the fix's age (the marker loop
            // predicts before it snaps), so this call adds only the tau, and the
            // road is the honest direction to add it along.
            val snapped = retained.snappedAt
            val camTargetNow = when {
                snapped != null && f != null -> MapMotion.predict(
                    at = snapped,
                    bearingDeg = retained.camTargetBearing,
                    speedMps = f.speedMps,
                    fixElapsedMs = nowElapsed,
                    nowElapsedMs = nowElapsed,
                    leadSeconds = CAM_POS_TAU,
                )
                f != null -> MapMotion.predict(
                    at = LatLon(f.lat, f.lon),
                    bearingDeg = f.bearingDeg,
                    speedMps = f.speedMps,
                    fixElapsedMs = f.elapsedRealtimeMs,
                    // Drive time, not wall time: the fix's speed is the drive's,
                    // so the age it is multiplied by has to be too. Identical to
                    // nowElapsed at 1x — see DriveClock.predictionNowMs.
                    nowElapsedMs = clock.predictionNowMs(f.elapsedRealtimeMs),
                    leadSeconds = CAM_POS_TAU,
                )
                else -> retained.camTarget
            }
            camTargetNow?.let { target ->
                if (MapMotion.shouldSnap(LatLon(lat, lon), target)) {
                    // Too far to be continuous motion — a resume from background, a
                    // tunnel exit, a first fix after an outage. Easing across it would
                    // sweep the camera, and MapLibre's tile requests, over everything
                    // in between. Bearing and zoom re-anchor here too, so the whole
                    // camera teleports as one instead of still rotating and zooming in
                    // over their own time constants after a background-resume snap.
                    lat = target.lat
                    lon = target.lon
                    bearing = retained.camTargetBearing ?: bearing
                    zoom = targetZoom
                } else {
                    val a = 1.0 - exp(-dt / CAM_POS_TAU)
                    lat += (target.lat - lat) * a
                    lon += (target.lon - lon) * a
                }
            }
            retained.camTargetBearing?.let { target ->
                bearing = smoothBearing(
                    bearing, target, (1.0 - exp(-dt / CAM_BEARING_TAU)).toFloat())
            }
            zoom += (targetZoom - zoom) * (1.0 - exp(-dt / CAM_ZOOM_TAU))

            // Heading-up while moving: MapLibre bearing points the camera along
            // travel, so the road you're on runs up the screen. The camera-move
            // listener redraws the fog; the position dot is world-fixed and rides
            // Push while the ease has not converged, or while the target itself is
            // moving. The old test compared this frame's step against the last pushed
            // value, which cannot tell a slow camera from a settled one: at 20 km/h a
            // frame moves 0.09 m against a 0.14 m threshold, so the camera was pushed
            // every third frame and stepped visibly. A parked map still does no work,
            // because then the target is still and the camera has converged on it.
            val targetMoved = camTargetNow != null &&
                (camTargetNow.lat != lastTargetLat || camTargetNow.lon != lastTargetLon)
            if (camTargetNow != null) {
                lastTargetLat = camTargetNow.lat
                lastTargetLon = camTargetNow.lon
            }
            val moved = MapMotion.shouldPush(
                camLat = lat, camLon = lon, camZoom = zoom, camBearing = bearing,
                tgtLat = camTargetNow?.lat ?: lat, tgtLon = camTargetNow?.lon ?: lon,
                tgtZoom = targetZoom, tgtBearing = retained.camTargetBearing ?: bearing,
                targetMoved = targetMoved,
                neverPushed = neverPushed,
            )
            if (moved) {
                setCamera(map, lat, lon, zoom, bearing)
                neverPushed = false
            }
        }
    }
}

/**
 * The position dot, interpolated per frame.
 *
 * Separate from [MapCameraLoops] because it must keep running when the camera
 * does not: the camera loop returns early when it is inactive, and a parked
 * camera — after a pan, with follow off, or with a spin result up — is exactly
 * when the dot still has to move and turn.
 */
@Composable
internal fun MapPositionMarker(s: MapScreenState, retained: RetainedMap) {
    // Hoisted out of the frame loop below, and it has to be: a CompositionLocal
    // is only readable in a @Composable. Safe to hold as a plain value across the
    // life of the effect — compose-state-hazards §2's stale-capture rule is about
    // values that change, and DriveClocks holds one instance for the process; a
    // replay re-paces the clock inside it rather than swapping it.
    val clock = LocalDriveClock.current
    val mapOverlays = retained.overlays
    val fogView = retained.fogView
    val liveFix by TripTrackingService.lastFix.collectAsStateWithLifecycle()
    val haveFix = retained.camTarget != null || s.myLocation != null
    // The dot, interpolated per frame. It used to be re-placed only when a fix arrived,
    // about once a second, at the raw fix position — so it stepped forward and the camera
    // slid after it. Worst when the camera is parked (after a pan, with follow off, or
    // with a spin result up), because then nothing is gliding underneath to mask it, which
    // is why this loop is deliberately independent of cameraActive.
    //
    // The heading is eased here too, on its own accumulator rather than the camera loop's
    // eased bearing — sharing would guarantee the two never diverge, but the camera loop
    // returns early when !cameraActive, and a parked camera is exactly the case this loop
    // exists to serve, so a shared bearing would freeze right when the marker still needs
    // to turn. Measured on tools/mocklocation/routes/turn-circle.txt (45 km/h, 11.9 deg/s),
    // sampling the icon's on-screen angle at 2.16 fps: its peak excursion from the resting
    // angle fell from 43.1 to 12.8 deg, p90 from 8.7 to 2.8, and the standard deviation of
    // the frame-to-frame change from 7.7 to 2.3. Excursion is the quantity that separates
    // the two — the share of near-zero frame deltas does not, because a heading that tracks
    // the map well is just as flat between samples as one that is held.
    //
    // setPosition writes one point into SRC_POSITION. render() rewrites eight sources
    // including the route line, and doing *that* per frame is what makes a head unit
    // crawl — see MapOverlays.setPosition's own note.
    LaunchedEffect(mapOverlays, haveFix) {
        val overlays = mapOverlays ?: return@LaunchedEffect
        // Cleared on every start of this loop, not only when navigation ends:
        // the value outlives a trip to the Hub in `retained`, and the camera
        // loop above — which resumes on the same composition — read the point
        // the rider left minutes ago and teleported to it before this loop's
        // first frame could replace it.
        retained.snappedAt = null
        var lastLat = Double.NaN
        var lastLon = Double.NaN
        var pushedBearing: Float? = null
        var markerBearing: Float? = null
        // Where the marker last snapped onto the route, and the line it snapped to.
        // Losing these costs one full-route search on the next frame, not a wrong
        // seam — [NavEngine.advance] re-seeds itself from a null.
        var along: NavEngine.Along? = null
        var alongLine: List<LatLon>? = null
        var lastNs = withFrameNanos { it }
        while (true) {
            val ns = withFrameNanos { it }
            // Same clamp as the camera loop: a dropped frame or a stalled render must not
            // let one frame close the whole gap.
            val dt = ((ns - lastNs) / 1_000_000_000.0).coerceIn(0.0, 0.1)
            lastNs = ns
            val f = liveFix ?: continue
            val here = MapMotion.predict(
                at = LatLon(f.lat, f.lon),
                bearingDeg = f.bearingDeg,
                speedMps = f.speedMps,
                fixElapsedMs = f.elapsedRealtimeMs,
                nowElapsedMs = clock.predictionNowMs(f.elapsedRealtimeMs),
                leadSeconds = 0.0,
            )
            // One snap a frame, and everything the route contributes comes off it:
            // the seam between the road behind and the road ahead, and — while the
            // rider is on the line — the marker's own position and heading.
            //
            // Outside any `moved` gate on purpose. The seam is the one thing here
            // that can be stale without the position changing: come back from the
            // background, or take a fix that jumps, and the snap has a gap to walk
            // that a stopped vehicle would otherwise feed it one frame at a time.
            // Nothing is pushed for it — [MapOverlays.setDrivenFraction] drops a
            // fraction inside its step and a tail that has not moved — so a
            // standstill still costs one windowed search a frame and no GeoJSON.
            //
            // `navigating` and `route` are read live rather than keyed: this loop
            // must not restart when either changes (see the accumulators above),
            // and a snapshot read inside the body sees them anyway. Read once into
            // `r` so the two tests below cannot see different values.
            val r = s.route
            if (!s.navigating || r == null) {
                // Nothing to be on: the marker is the fix again, and the per-fix
                // effect above takes the camera's heading back.
                retained.snappedAt = null
            } else {
                // A different line invalidates the snap taken along the old one.
                if (alongLine !== r.polyline) {
                    alongLine = r.polyline
                    along = null
                }
                // Windowed from the previous frame's snap, so this costs a handful
                // of segments rather than the whole route, and — once continued
                // from one — cannot hop to the other leg where the route rides its
                // own tarmac twice. A null `from` is still a full search, which is
                // what the first frame of a drive and a route change pay.
                val a = NavEngine.advance(r.polyline, here, along)
                // Beyond the window — a resume, a jumped fix — drop it, so the
                // next frame searches the whole line once instead of walking
                // the seam forward a window a frame, each of those frames
                // pushing two route-sized GeoJSON sources.
                along = if (a.beyondWindow) null else a
                overlays.setDrivenFraction(a.fraction, a.at)
                // Whether to draw the rider on the line at all is NavPolicy's, off
                // the *windowed* snap's own distance: `Progress.offRouteMeters` is
                // measured to the globally nearest point, which on an out-and-back
                // is the leg the rider finished half an hour ago. The band is what
                // stops a fix sitting on the threshold from teleporting the marker
                // once a second for the length of a reroute cooldown.
                val snapped = NavPolicy.snapToRoute(a.offRouteMeters, retained.snappedAt != null)
                retained.snappedAt = if (snapped) a.at else null
                // The window can be left behind for good: cut a chord on a loop
                // and the nearest segment *inside* it stays the one before the
                // chord, so the marker never snaps again. When the windowed snap
                // says off the line and the per-fix global progress says on it,
                // the window is stale — drop it, and the next frame searches
                // the whole line once.
                if (!snapped && (s.navProgress?.offRouteMeters ?: Double.MAX_VALUE) <= NavPolicy.ARRIVE_METERS) {
                    along = null
                }
                // Heading-up along the road rather than along the GPS. Compose does
                // not invalidate on an equal write and a segment bearing only
                // changes at a vertex, so this is quiet in between (hazards §6);
                // nothing reads either of these two from a composition.
                if (snapped) a.bearingDeg?.let { retained.camTargetBearing = it.toFloat() }
            }
            // On the line while on it. A raw fix wanders off the drawn route by its
            // own error, which reads as the rider driving beside the road.
            val at = retained.snappedAt ?: here
            // Against what was last *pushed*, not against where the fix last was: on
            // route the two differ, and the snap can walk forward while a standing
            // vehicle's fix does not — closing a background gap is exactly that.
            val moved = at.lat != lastLat || at.lon != lastLon
            retained.camTargetBearing?.let { target ->
                markerBearing = smoothBearing(
                    markerBearing, target, (1.0 - exp(-dt / CAM_BEARING_TAU)).toFloat())
            }
            // Named apart from the camera loop's own `bearing`, which is that loop's mutable
            // accumulator rather than a snapshot — the two effects sit a screen apart in this
            // file and reusing the name invites conflating them.
            val easedBearing = markerBearing
            // The gate covers the bearing as well as the position, or a vehicle stopped
            // mid-rotation would ease its nose and never push it. CAM_BEARING_EPS_DEG keeps
            // the standstill optimisation the position half already had: once the marker
            // has settled, this loop goes quiet again.
            val turned = easedBearing != null && (pushedBearing == null ||
                bearingDelta(pushedBearing, easedBearing) > CAM_BEARING_EPS_DEG)
            if (moved || turned) {
                overlays.setPosition(at, easedBearing?.toDouble())
                // Whatever the reason for the push, the bearing just drawn is this one, so
                // that is what the next frame must compare against. Advancing it only on a
                // `turned` push would leave the reference describing something no longer on
                // screen, and cost a redundant push cycle the moment the vehicle stops.
                pushedBearing = easedBearing
            }
            if (moved) {
                // The fog reveals around the interpolated position, or its hole
                // trails the dot by the prediction lead — about 14 m at 100 km/h,
                // snapping forward once a second. `here`, not the snapped `at`:
                // the fog records where the rider actually went, and a route is
                // not evidence of that. The invalidate is for the parked
                // camera: while following, the camera-move listener below already
                // redraws every frame, but parked nothing else would, and that is
                // exactly when a lagging fog is most visible.
                fogView.currentLocation = here
                fogView.invalidate()
                lastLat = at.lat
                lastLon = at.lon
            }
        }
    }
}

/**
 * Puts the map back to north-up, and only when there is a rotation to undo.
 *
 * Writing the camera unconditionally cancels whatever flight is in progress,
 * and parking is exactly what a destination framing does on its way in — so an
 * unguarded write here would pin the camera to wherever the animation had
 * reached, one frame after the pick asked for the destination.
 *
 * `internal` rather than private since #260, because the rail's compass button
 * calls it too: the automatic levelling on a park is gone, so this is now
 * something the rider asks for as well as something the follow loop does.
 * Callers decide *whether* to level — [CameraAuthority.shouldLevelNorthUp] for
 * the loop, [CameraAuthority.State.northUpAvailable] for the button — and this
 * only does it.
 */
internal fun levelToNorthUp(map: MapLibreMap) {
    if (map.cameraPosition.bearing == 0.0) return
    val at = map.cameraPosition.target ?: return
    setCamera(map, at.latitude, at.longitude, map.cameraPosition.zoom, 0f)
}

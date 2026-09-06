package com.jellemax.detour.ui

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.drive.CameraWarner
import com.jellemax.detour.drive.SectionAverageTracker
import com.jellemax.detour.tracking.TripTrackingService

/**
 * The two hazard readouts that speak up: the speed-camera chime, and the
 * average-speed (trajectcontrole) section.
 *
 * Both are `shared/…/drive/` machines — the rules, the one-chime-per-camera
 * latch and the section arithmetic live there with their tests. What is here is
 * what to *do* about an answer: play a tone, and keep the machine's state where
 * a return to the map can resume it rather than restart it.
 *
 * Both effects are keyed on `Unit` and seed from [RetainedMap] on purpose. They
 * restart on every return to the map, and restarting a machine is not the same
 * as resuming one: it re-arms a camera the rider has already passed, and throws
 * away a section's entry time and accumulated distance mid-measurement. Losing
 * that next to a real fine is the worst version of this whole class of bug.
 */
@Composable
internal fun MapHazardAlerts(
    s: MapScreenState,
    retained: RetainedMap,
    /** Spoken warnings go through MapScreen's announcer, which owns the
     *  platform TextToSpeech and the ducking around it. */
    announceAloud: (String) -> Unit,
) {
    // Chime when a camera lies ahead, close, and we're over the posted limit —
    // the one case worth interrupting for. The rule, the one-chime-per-camera
    // latch and the wording are CameraWarner's (shared/…/drive/), where they live
    // with their tests; what to do about a warning is ours.
    val speedCamerasRef = rememberUpdatedState(retained.speedCameras)
    val ambientLimitRef = rememberUpdatedState(retained.ambientSpeedLimitKmh)
    val navProgressRef = rememberUpdatedState(s.navProgress)
    val toneGen = remember {
        runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull()
    }
    DisposableEffect(Unit) { onDispose { toneGen?.release() } }
    LaunchedEffect(Unit) {
        // Resumed for the same reason as the section machine: this is the
        // one-warning-per-camera latch, and restarting it re-arms a camera the
        // rider has already been warned about and already passed.
        var warnerState = retained.warnerState
        TripTrackingService.lastFix.collect { fix ->
            fix ?: return@collect
            // The ambient sign is the free-drive source. While navigating, the
            // route's own posted limit is the authority and the ambient tracker
            // is stopped — and now cleared, see the producer above — so a route
            // segment with no maxspeed judges you against nothing instead of
            // against the sign from wherever you set off.
            val step = CameraWarner.onFix(
                state = warnerState,
                cameras = speedCamerasRef.value,
                at = LatLon(fix.lat, fix.lon),
                headingDeg = fix.bearingDeg?.toDouble(),
                speedKmh = fix.speedMps * 3.6,
                limitKmh = navProgressRef.value?.speedLimitKmh ?: ambientLimitRef.value,
            )
            warnerState = step.state
            retained.warnerState = warnerState
            when (val outcome = step.outcome) {
                is CameraWarner.Outcome.Warn -> {
                    toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
                    // The only trace that the chime fired. NavVoice logs the
                    // spoken half, but that half is gated on the guidance
                    // setting, so with speech off a replay had nothing at all to
                    // grep for and a zero hit count meant "muted" and "never
                    // warned" indistinguishably. Debug level: it is one line per
                    // camera, latched to one per camera by CameraWarner itself.
                    Log.d("DetourCameraWarn", "chime: ${outcome.text}")
                    // A TONE_PROP_BEEP2 on the notification stream is inaudible on
                    // a bar mount with earplugs in and wind noise — which is this
                    // app's primary configuration. The head unit has spoken this
                    // since it shipped and its comment says why
                    // (car/NavScreen.kt's checkCameras). Register entry 15.
                    //
                    // No toast: the car's stands in for a visual the head unit has
                    // no room for, and the phone's map already draws the camera
                    // marker. The snackbarHostState this screen already owns is the
                    // error channel; routing a routine hazard through it would
                    // teach the rider to ignore errors.
                    announceAloud(outcome.text)
                }
                CameraWarner.Outcome.Silent -> {}
            }
        }
    }

    // Average speed through a trajectcontrole: SectionAverageTracker's call now
    // (shared/…/drive/), where the gate rules, the eight thresholds and the
    // reasoning behind each live with their tests.
    val speedSectionsRef = rememberUpdatedState(retained.speedSections)
    LaunchedEffect(Unit) {
        // Resumed, not restarted: this effect is keyed on Unit and so restarts
        // on every return to the map, which used to throw away an in-progress
        // section along with its entry time and accumulated distance.
        var st = retained.sectionState
        TripTrackingService.lastFix.collect { fix ->
            fix ?: return@collect
            st = SectionAverageTracker.onFix(
                state = st,
                sections = speedSectionsRef.value,
                at = LatLon(fix.lat, fix.lon),
                headingDeg = fix.bearingDeg?.toDouble(),
                speedMps = fix.speedMps,
                nowMs = System.currentTimeMillis(),
            )
            // One owner. The reading was also mirrored into two `remember`ed
            // vars here, which is a second copy of a value that already has a
            // home in `retained.sectionState` — and a second copy is a second
            // thing that can be stale. The HUD reads the machine's own state
            // directly now, so "they can no longer disagree" stops being a
            // property the assignment order has to maintain.
            retained.sectionState = st
        }
    }
}

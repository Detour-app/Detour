package com.jellemax.detour.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.jellemax.detour.ColdStartTiming
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.SpeedCameras
import com.jellemax.detour.drive.CameraPrefetch
import com.jellemax.detour.drive.CameraWarner
import com.jellemax.detour.drive.SectionAverageTracker
import com.jellemax.detour.drive.SpeedLimitTracker
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * The map, kept alive across a navigation away from [MapScreen] and back.
 *
 * `NavDisplay` composes only the destination you are on, so leaving the map
 * used to dispose its composition and take the `MapView`, the GL surface, the
 * fetched-and-parsed OpenFreeMap style and every overlay source with it —
 * rebuilt from nothing on the way back, while a slide-in transition animated
 * over the top of it. That is #82's first symptom.
 *
 * ## Why the scope is what it is
 *
 * A `MapView` holds a `Context`, so this must not outlive the Activity: as an
 * app-scoped singleton it would leak one, and every rotation would leak
 * another. It is created with `remember` in `AppRoot`, which is composed for
 * the Activity's whole life and recomposed with it — so the scope is the
 * Activity without anything having to say so, and a rotation still rebuilds
 * the map exactly as it does today.
 *
 * A `ViewModel` would give the same scope and survive rotation too, but this
 * app has no `ViewModel` anywhere and `MapView` is the wrong thing to carry
 * across a configuration change regardless.
 *
 * ## The invariant this is careful to preserve
 *
 * `.claude/skills/detour-compose-state-hazards` §2b: MapScreen registers four
 * MapLibre listeners and gets away with it because [map] has exactly one write
 * site, inside one `getMapAsync`, inside a `DisposableEffect` that can never
 * re-run with a second non-null map. That is still true here — the effect below
 * is keyed on this object, which never changes for the Activity's life. What
 * *did* change is that MapScreen can now compose against an already-non-null
 * [map], so its listener registration had to become a `DisposableEffect` that
 * removes what it added. Without that, every return to the map stacked four
 * more listeners and the fog invalidated N times per camera move.
 */
class RetainedMap(context: Context) {
    val mapView = ColdStartTiming.timed("MapView(context)") { MapView(context) }
    val fogView = FogView(context)

    /** Set once, by the single `getMapAsync` in [rememberRetainedMap]. Snapshot
     *  state so MapScreen recomposes when the map arrives on a cold start. */
    var map: MapLibreMap? by mutableStateOf(null)

    /** Rebuilt whenever the style is (re)loaded — i.e. on a theme flip, not on
     *  a navigation. */
    var overlays: MapOverlays? by mutableStateOf(null)

    // --- where the camera is aiming -------------------------------------
    //
    // Retained for the same reason the view is. The MapView keeps its actual
    // camera across a navigation, but these are MapScreen's *intent*, and as
    // plain `remember`s they came back at their defaults — so the follow loop
    // found itself at the zoom the rider left and a target of `defaultZoom`,
    // and eased between the two. That is the zoom-in on every return to the
    // map, and the accompanying rotation back to north from a null bearing.
    // #82's second acceptance criterion is precisely that this stops.

    var camTarget: LatLon? by mutableStateOf(null)
    var camTargetBearing: Float? by mutableStateOf(null)

    /** Null until the first fix computes one, so MapScreen can fall back to
     *  the current `defaultZoom` setting rather than to a stale copy of it. */
    var camTargetZoom: Double? by mutableStateOf(null)

    /**
     * The eased speedometer reading, retained for the same reason as the
     * camera's target.
     *
     * As a plain `remember` this came back at 0.0 on every return to the map
     * and then climbed to the real speed over `SPEED_TAU`, so a rider glancing
     * at the HUD just after leaving the Hub saw a number that was simply wrong
     * — worst exactly when moving, which is when it is read.
     *
     * `mutableDoubleStateOf`, not `mutableStateOf`, to keep the unboxed write
     * the per-frame easing loop does. Compose still skips invalidation when the
     * value is unchanged, which is what lets that loop run unconditionally once
     * the number has settled (hazards skill §6).
     */
    var displaySpeedKmh: Double by mutableDoubleStateOf(0.0)

    /**
     * The average-speed (trajectcontrole) machine's state.
     *
     * Seeded fresh on every composition before this, so leaving the map
     * mid-section abandoned the measurement: entry time and accumulated
     * distance went with it, the Ø chip vanished, and the section could not be
     * resumed — the vehicle was already past the entry gate, so nothing would
     * re-arm it. Losing it next to a real fine is the worst version of this
     * whole class of bug.
     *
     * The machine itself is pure (`SectionAverageTracker.onFix` is a
     * state-in/state-out function in shared/), so retaining it is just holding
     * its State rather than reaching into a coroutine's locals.
     */
    var sectionState: SectionAverageTracker.State by mutableStateOf(SectionAverageTracker.State())

    // --- the ambient speed-limit sign ------------------------------------

    var limitState: SpeedLimitTracker.State by mutableStateOf(SpeedLimitTracker.State())
    var ambientSpeedLimitKmh: Double? by mutableStateOf(null)

    /**
     * The `navigating` value the limit machine was last reset for.
     *
     * Its effect is keyed on `navigating` and opens by resetting, because
     * crossing into or out of navigation genuinely invalidates the held sign.
     * But a restart caused by returning to the map is indistinguishable from
     * one caused by that crossing — the effect only sees that it restarted — so
     * the sign was cleared on every re-entry and stayed blank until the next
     * prefetch and snap. Recording what was last reset for is what tells the
     * two apart. Null until the first run.
     */
    var limitResetForNavigating: Boolean? = null

    // --- speed cameras and trajectcontrole sections ----------------------

    var cameraPrefetch: CameraPrefetch.State by mutableStateOf(CameraPrefetch.State())
    var speedCameras: List<SpeedCameras.Camera> by mutableStateOf(emptyList())
    var speedSections: List<SpeedCameras.Section> by mutableStateOf(emptyList())

    /** The one-warning-per-camera latch. Reset it and a camera already passed
     *  chimes again on the way back past nothing. */
    var warnerState: CameraWarner.State by mutableStateOf(CameraWarner.State())
}

/**
 * Creates the retained map and owns its lifecycle and style.
 *
 * Call this once, from `AppRoot`, above the navigation swap — not from
 * [MapScreen], whose composition is exactly what this exists to outlive.
 */
@Composable
fun rememberRetainedMap(darkTheme: Boolean): RetainedMap {
    val context = LocalContext.current
    // Keeps OSM/OpenFreeMap attribution above the collapsed spin bar. Applied
    // here rather than in MapScreen because it is set on the map object, which
    // is now configured once rather than once per entry.
    val attributionBottomMarginPx = with(LocalDensity.current) { 84.dp.roundToPx() }
    val retained = remember { RetainedMap(context) }

    // Keyed on `retained`, which never changes while this composable is in the
    // tree — so onCreate/onDestroy happen once per Activity, and getMapAsync
    // fires exactly once. See the class doc's invariant note before adding a key.
    DisposableEffect(retained) {
        retained.mapView.onCreate(null)
        retained.mapView.onStart()
        retained.mapView.onResume()
        retained.mapView.getMapAsync { map ->
            ColdStartTiming.mark("getMapAsync ready")
            map.uiSettings.isCompassEnabled = false
            map.uiSettings.isRotateGesturesEnabled = true
            map.uiSettings.setAttributionMargins(0, 0, 0, attributionBottomMarginPx)
            map.uiSettings.setLogoMargins(0, 0, 0, attributionBottomMarginPx)
            retained.map = map
        }
        onDispose {
            retained.fogView.map = null
            retained.mapView.onPause()
            retained.mapView.onStop()
            retained.mapView.onDestroy()
        }
    }

    // (Re)load the style on a theme flip, rebuild the overlay layers on the new
    // Style, and (re)attach the fog view over the GL surface. Keyed on the theme
    // and the map only — a navigation is neither, which is the point.
    LaunchedEffect(darkTheme, retained.map) {
        val map = retained.map ?: return@LaunchedEffect
        map.setStyle(Style.Builder().fromUri(openFreeMapStyleUrl(darkTheme))) { style ->
            ColdStartTiming.mark("style loaded")
            retained.overlays = MapOverlays(style, context, darkTheme)
            retained.fogView.map = map
            if (retained.mapView.indexOfChild(retained.fogView) < 0) {
                retained.mapView.addView(
                    retained.fogView,
                    android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        }
    }

    return retained
}

package com.jellemax.maproulette.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RectF
import android.media.AudioManager
import android.media.ToneGenerator
import java.io.IOException
import android.net.Uri
import android.os.Build
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.maproulette.R
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.jellemax.maproulette.audio.PushToTalk
import com.jellemax.maproulette.net.ConvoyLiveClient
import com.jellemax.maproulette.data.Account
import com.jellemax.maproulette.data.Convoys
import com.jellemax.maproulette.data.ExploredArea
import com.jellemax.maproulette.data.FriendFog
import com.jellemax.maproulette.data.GeocodeResult
import com.jellemax.maproulette.data.Geocoder
import com.jellemax.maproulette.data.LatLon
import com.jellemax.maproulette.data.NavEngine
import com.jellemax.maproulette.data.PoiKind
import com.jellemax.maproulette.data.PoiRoulette
import com.jellemax.maproulette.data.RecentSearchStore
import com.jellemax.maproulette.data.RoadRoulette
import com.jellemax.maproulette.data.RouteCandidate
import com.jellemax.maproulette.data.RoundTripPlanner
import com.jellemax.maproulette.data.RouteResult
import com.jellemax.maproulette.data.RoutingServer
import com.jellemax.maproulette.data.pickCandidate
import com.jellemax.maproulette.data.SavedPlace
import com.jellemax.maproulette.data.SavedPlaces
import com.jellemax.maproulette.data.ServerConfig
import com.jellemax.maproulette.data.Settings
import com.jellemax.maproulette.data.SpeedCameras
import com.jellemax.maproulette.data.SyncClient
import com.jellemax.maproulette.data.TraceStore
import com.jellemax.maproulette.data.TravelMode
import com.jellemax.maproulette.tracking.TripStats
import com.jellemax.maproulette.tracking.TripTrackingService
import com.jellemax.maproulette.ble.BleNavServer
import com.jellemax.maproulette.wear.NavRelay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

private val DIRECTION_NAMES = listOf("North", "North-east", "East", "South-east",
    "South", "South-west", "West", "North-west")

val TravelMode.icon: ImageVector
    get() = when (this) {
        TravelMode.WALK -> Icons.AutoMirrored.Outlined.DirectionsWalk
        TravelMode.BIKE -> Icons.AutoMirrored.Outlined.DirectionsBike
        TravelMode.MOTO -> Icons.Outlined.TwoWheeler
        TravelMode.CAR -> Icons.Outlined.DirectionsCar
    }

/** Exponentially smooths a compass bearing toward [target], taking the
 *  shortest way round the 0/360 wrap, so heading-up rotation eases instead
 *  of snapping to each noisy raw GPS fix. */
private fun smoothBearing(current: Float?, target: Float, alpha: Float = 0.3f): Float {
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
private const val CAM_POS_TAU = 0.35
private const val CAM_BEARING_TAU = 0.5
private const val CAM_ZOOM_TAU = 1.2

// The speed readout is eased the same way, per frame rather than per fix: GPS
// speed arrives about once a second, and a number that jumps once a second
// reads as a laggy app even when the fix behind it is current. Short tau — the
// readout has to be honest about braking, not just smooth.
private const val SPEED_TAU = 0.30
// Below ~0.15 km/h of remaining gap the rounded number can't change; snap and
// stop recomposing so a steady cruise doesn't repaint the HUD every frame.
private const val SPEED_EPS_KMH = 0.15

// Below these, an eased camera step isn't worth a redraw: ~0.2 m of pan (well
// sub-pixel at driving zooms), a hair of zoom, a tenth of a degree of rotation.
// Once the ease settles inside all three, setCamera is skipped and the map —
// and the fog view riding on its camera-move callback — goes quiet.
private const val CAM_POS_EPS_DEG = 2e-6
private const val CAM_ZOOM_EPS = 2e-3
private const val CAM_BEARING_EPS_DEG = 0.1f

// Padding kept around a fitted route/candidate spread so pins and the trip card
// don't sit against the screen edge.
private const val FIT_PADDING_PX = 140

// Panning or pinching parks the camera instead of forcing you to hunt for the
// follow button. Driving off takes it back: above this speed, this long after
// you last touched the map. The quiet period is what stops a two-finger zoom at
// 80 km/h from being yanked out from under you mid-gesture.
private const val CAM_RESUME_SPEED_MPS = 3.0
private const val CAM_RESUME_QUIET_MS = 8_000L

// How far ahead a speed camera triggers the over-speed chime. ~400 m is ~12 s
// of warning at motorway speed — time to ease off before the camera.
private const val CAMERA_WARN_METERS = 400.0

// How close to a section's device node counts as passing it, for entering and
// leaving a trajectcontrole average-speed measurement.
private const val SECTION_GATE_METERS = 60.0

// How far off your heading the far end of a section may lie and still count as
// driving into it. Wide, because a long section can curve away — it only has to
// separate "the other end is ahead of me" from "behind me, I'm on my way out".
private const val SECTION_WEDGE_DEG = 75.0

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
private fun sectionExitGate(
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

/** One color per spin candidate, so the pin on the map and the row in the card
 *  are recognizably the same place. Kept clear of the blue radius circle, the
 *  orange direction wedge and the pink route line. */
private val CANDIDATE_COLORS = listOf(0xFF7E57C2, 0xFF00897B, 0xFFF4511E)
    .map { it.toInt() }

/** The last spin outcome, kept outside `remember` so it survives activity
 *  recreation (rotation, split-screen resize, a backgrounded process losing
 *  just the Activity) — process-scoped, not a substitute for the stores that
 *  already survive process death. MapScreen seeds its `remember`ed state from
 *  this on composition and writes back whenever the result changes. */
private data class SpinResult(
    val destination: LatLon? = null,
    val destinationName: String? = null,
    val route: RouteResult? = null,
    val candidates: List<RouteCandidate> = emptyList(),
)

private object SpinResultHolder {
    val state = MutableStateFlow(SpinResult())
}

/** What currently occupies the bottom-card slot on the map. */
private enum class BottomCard { NAV, CANDIDATES, COLLAPSED, EXPANDED }

@Composable
fun MapScreen(
    onOpenHub: () -> Unit,
) {
    val context = LocalContext.current
    // Extra bottom padding for a fitted route/candidate spread, roughly the
    // expanded spin card's height, so the card doesn't cover most of it. A
    // fixed fraction of the screen rather than measuring the actual card —
    // the card's real height varies with content, and this only needs to be
    // in the right ballpark.
    val fitBottomPaddingPx = (context.resources.displayMetrics.heightPixels * 0.4).toInt()
    // Bottom margin so OSM/OpenFreeMap attribution stays above the collapsed
    // spin bar instead of half-covered by it — a fixed estimate of the bar's
    // height (icon + row padding) rather than measuring it, same spirit as
    // fitBottomPaddingPx above.
    val attributionBottomMarginPx = with(LocalDensity.current) { 84.dp.roundToPx() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(Unit) { SavedPlaces.ensureLoaded(context) }
    val savedPlaces by SavedPlaces.places.collectAsStateWithLifecycle()
    // Non-null while a name is being entered for the current dropped/destination pin.
    var savePinTarget by remember { mutableStateOf<LatLon?>(null) }

    // Persisted, because the tracking service reads it too: an auto-detected
    // trip has no other way to know whether it is a ride or a drive.
    val mode by Settings.tripMode.collectAsStateWithLifecycle()
    var radiusKm by rememberSaveable { mutableFloatStateOf(Settings.tripMode.value.defaultKm) }
    var minRadiusKm by rememberSaveable { mutableFloatStateOf(0f) }
    // Seeded from SpinResultHolder so a spin result survives activity
    // recreation instead of resetting to defaults; see its declaration above.
    val savedSpin = remember { SpinResultHolder.state.value }
    var candidates by remember { mutableStateOf(savedSpin.candidates) }
    var myLocation by remember { mutableStateOf<LatLon?>(null) }
    var destination by remember { mutableStateOf(savedSpin.destination) }
    var route by remember { mutableStateOf(savedSpin.route) }
    var spinning by remember { mutableStateOf(false) }
    var spinJob by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val serverConfig = remember { RoutingServer.load(context) }
    var poiKind by rememberSaveable { mutableStateOf(PoiKind.ROAD) }
    var directionDeg by rememberSaveable { mutableStateOf<Float?>(null) }
    var destinationName by remember { mutableStateOf(savedSpin.destinationName) }
    // Keep the holder in sync with whatever changed these — a new spin, a
    // pick, a cancel, or navigation ending and clearing the result.
    LaunchedEffect(destination, destinationName, route, candidates) {
        SpinResultHolder.state.value = SpinResult(destination, destinationName, route, candidates)
    }
    val fogEnabled by Settings.fogEnabled.collectAsStateWithLifecycle()
    val accountUsername by Account.username.collectAsStateWithLifecycle()
    var searchOpen by remember { mutableStateOf(false) }
    // Stored traces reload on every store write; the live trace and fix come
    // straight from the tracking service, so fog and position update in real
    // time instead of only when a trip is saved.
    val storeVersion by TraceStore.version.collectAsStateWithLifecycle()
    val traces = remember(storeVersion) { TraceStore.loadAll(context) }
    // Friends' territory, unioned into the same fog. Empty unless both sides
    // opted in; the overlay can't tell whose trace is whose, and neither can we.
    val shareFog by Settings.shareFog.collectAsStateWithLifecycle()
    val friendTraceSource by FriendFog.traces.collectAsStateWithLifecycle()
    val friendTraces = friendTraceSource
    val stats by TripTrackingService.stats.collectAsStateWithLifecycle()
    val liveFix by TripTrackingService.lastFix.collectAsStateWithLifecycle()
    val liveTrace by TripTrackingService.liveTrace.collectAsStateWithLifecycle()
    // Convoy: only present while ConvoyLiveService is running (started/stopped
    // from FriendsScreen's convoy list, see Convoys.join/leave there).
    val convoyConnected by ConvoyLiveClient.connected.collectAsStateWithLifecycle()
    val convoyTalking by ConvoyLiveClient.talking.collectAsStateWithLifecycle()
    val activeConvoyId by ConvoyLiveClient.activeConvoyId.collectAsStateWithLifecycle()
    // ConvoyLiveClient only knows the id it's connected to; resolve it to a
    // name for display by asking the same list FriendsScreen uses.
    var convoyName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activeConvoyId) {
        val id = activeConvoyId
        convoyName = if (id == null) null else withContext(Dispatchers.IO) {
            try {
                Convoys.list(context).find { it.id == id }?.name
            } catch (e: Exception) {
                null
            }
        }
    }

    var navigating by remember { mutableStateOf(false) }
    var navProgress by remember { mutableStateOf<NavEngine.Progress?>(null) }
    var rerouting by remember { mutableStateOf(false) }
    var lastRerouteMs by remember { mutableLongStateOf(0L) }
    // Following is the resting state of the map. `camSuspended` is what a pan,
    // a pinch or a spin result sets so you can look around; it does not switch
    // following off, it parks it until you are moving again.
    var followMe by remember { mutableStateOf(true) }
    var camSuspended by remember { mutableStateOf(false) }
    var lastGestureMs by remember { mutableLongStateOf(0L) }
    // Dock (collapsed) is the resting state; the sheet only comes up when
    // tapped open, and folds back down on its own after a spin lands.
    var settingsCollapsed by rememberSaveable { mutableStateOf(true) }
    var ambientSpeedLimitKmh by remember { mutableStateOf<Double?>(null) }
    var speedLimitWays by remember {
        mutableStateOf<List<RoadRoulette.SpeedLimitWay>>(emptyList())
    }
    var speedLimitWaysCenter by remember { mutableStateOf<LatLon?>(null) }
    var speedLimitFetchMs by remember { mutableLongStateOf(0L) }
    var speedLimitMisses by remember { mutableIntStateOf(0) }
    var speedCameras by remember { mutableStateOf<List<SpeedCameras.Camera>>(emptyList()) }
    var speedSections by remember { mutableStateOf<List<SpeedCameras.Section>>(emptyList()) }
    // Non-null only while driving through a trajectcontrole: the running average
    // speed since entering it, and the posted limit it's judged against.
    var sectionAvgKmh by remember { mutableStateOf<Double?>(null) }
    var sectionLimitKmh by remember { mutableStateOf<Double?>(null) }

    // Where the camera is heading. GPS delivers a fix about once a second; the
    // frame loop further down eases the map toward these targets every frame,
    // which is what turns a sequence of jumps into a glide.
    val defaultZoom by Settings.defaultZoom.collectAsStateWithLifecycle()
    var camTarget by remember { mutableStateOf<LatLon?>(null) }
    var camTargetBearing by remember { mutableStateOf<Float?>(null) }
    var camTargetZoom by remember { mutableDoubleStateOf(defaultZoom.toDouble()) }
    var displaySpeedKmh by remember { mutableDoubleStateOf(0.0) }
    val cameraActive = (followMe || navigating) && !camSuspended
    // What the follow button reflects: navigation drives the camera on its own.
    val following = followMe && !camSuspended

    LaunchedEffect(liveFix) {
        liveFix?.takeIf { it.accuracyMeters <= 100f }?.let {
            myLocation = LatLon(it.lat, it.lon)
        }
    }

    // Keep the min-distance floor from exceeding the radius as the slider moves.
    LaunchedEffect(radiusKm) {
        if (minRadiusKm > radiusKm) minRadiusKm = radiusKm
    }

    // Pull from the sync server on launch: restores everything after a
    // reinstall and picks up trips recorded while the app was closed.
    LaunchedEffect(Unit) {
        if (SyncClient.configured(context) && Account.signedIn) {
            withContext(Dispatchers.IO) {
                try {
                    SyncClient.sync(context)
                } catch (e: Exception) {
                    // offline, server down, or signed out; next launch catches up
                }
            }
        }
    }

    // Re-fetch when sharing is switched on, and drop what we hold the moment it
    // is switched off — a stale union would keep revealing a friend's roads.
    LaunchedEffect(shareFog) {
        if (shareFog) withContext(Dispatchers.IO) { FriendFog.refresh(context) }
        else FriendFog.clear()
    }

    // OpenFreeMap vector basemap: bright "liberty" by day, "dark" by night.
    val themePref by Settings.theme.collectAsStateWithLifecycle()
    val darkTheme = isAppDarkTheme(themePref)
    val fogRadius by Settings.fogRadiusMeters.collectAsStateWithLifecycle()

    val mapView = remember { MapView(context) }
    val fogView = remember { FogView(context) }
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapOverlays by remember { mutableStateOf<MapOverlays?>(null) }

    // Tell the tracker the map is being looked at, so it drops its battery-saving
    // batched fixes for navigation-grade ones while we're here. Tied to the
    // lifecycle, not to the composition: backgrounding the app keeps the map
    // composed, and a phone in a pocket must not hold a 1 Hz GPS request open.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> TripTrackingService.setUiVisible(context, true)
                // Belt-and-braces for push-to-talk: the button's own
                // awaitRelease() releases the mic on a normal press-and-let-go,
                // but backgrounding mid-press (e.g. an incoming call taking
                // over) may not deliver a pointer-up at all. A stuck-open mic
                // is the worst failure mode here, so this stops it regardless
                // of whether the gesture ever saw a release.
                Lifecycle.Event.ON_STOP -> {
                    TripTrackingService.setUiVisible(context, false)
                    PushToTalk.stopTalking()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            TripTrackingService.setUiVisible(context, false)
            PushToTalk.stopTalking()
        }
    }

    // MapView lifecycle. The map arrives asynchronously; effects that touch it
    // guard on `mapLibreMap` being non-null.
    DisposableEffect(Unit) {
        mapView.onCreate(null)
        mapView.onStart()
        mapView.onResume()
        mapView.getMapAsync { map ->
            map.uiSettings.isCompassEnabled = false
            map.uiSettings.isRotateGesturesEnabled = true
            // Keep OSM/OpenFreeMap attribution above the collapsed spin bar
            // instead of half-covered by it in every card state.
            map.uiSettings.setAttributionMargins(0, 0, 0, attributionBottomMarginPx)
            map.uiSettings.setLogoMargins(0, 0, 0, attributionBottomMarginPx)
            mapLibreMap = map
        }
        onDispose {
            fogView.map = null
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    // (Re)load the style on theme flip; rebuild the overlay layers on the new
    // Style and (re)attach the fog view over the GL surface.
    LaunchedEffect(darkTheme, mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        map.setStyle(Style.Builder().fromUri(openFreeMapStyleUrl(darkTheme))) { style ->
            mapOverlays = MapOverlays(style, context, darkTheme)
            fogView.map = map
            if (mapView.indexOfChild(fogView) < 0) {
                mapView.addView(fogView, android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }
    }

    // Park the camera as soon as the map is dragged or pinched. A camera-move
    // listener can't be used for this: the frame loop moves the camera every
    // frame, so it would fire constantly and couldn't tell us from the user.
    // The touch listener returns false, leaving MapView to handle the gesture.
    DisposableEffect(mapView) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        mapView.setOnTouchListener { _, event ->
            fun park() {
                camSuspended = true
                lastGestureMs = System.currentTimeMillis()
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }
                // Second finger down is a pinch starting; no slop test needed.
                MotionEvent.ACTION_POINTER_DOWN -> park()
                MotionEvent.ACTION_MOVE ->
                    if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) park()
                // A tap that never left the slop circle keeps following: it was
                // a long-press pin drop or a marker tap, not a pan.
                MotionEvent.ACTION_UP -> if (camSuspended) lastGestureMs = System.currentTimeMillis()
            }
            false
        }
        onDispose { mapView.setOnTouchListener(null) }
    }

    // Driving off takes the camera back. Not while a spin is on screen: the
    // candidates are the whole reason the map is parked where it is, and a
    // passenger spinning at speed would otherwise never get to read them.
    LaunchedEffect(camSuspended, spinning, candidates.isEmpty()) {
        if (!camSuspended || spinning || candidates.isNotEmpty()) return@LaunchedEffect
        TripTrackingService.lastFix.collect { fix ->
            fix ?: return@collect
            if (fix.speedMps >= CAM_RESUME_SPEED_MPS &&
                System.currentTimeMillis() - lastGestureMs > CAM_RESUME_QUIET_MS
            ) {
                camSuspended = false
            }
        }
    }

    fun fetchLocation() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        scope.launch {
            try {
                val client = LocationServices.getFusedLocationProviderClient(context)
                val loc = client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
                    ?: client.lastLocation.await()
                if (loc != null) {
                    myLocation = LatLon(loc.latitude, loc.longitude)
                    mapLibreMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(
                        LatLng(loc.latitude, loc.longitude), Settings.defaultZoom.value.toDouble()))
                } else {
                    error = "Could not get location; is GPS on?"
                }
            } catch (e: SecurityException) {
                error = "Location permission missing"
            }
        }
    }

    // Background location must be requested separately from fine location,
    // after it is granted (system requirement on Android 11+).
    val bgLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // Mic permission is asked for once a convoy is actually joined, not
    // upfront with location — nothing needs it until push-to-talk does.
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(convoyConnected) {
        if (convoyConnected &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun onLocationGranted() {
        fetchLocation()
        TripTrackingService.startMonitoring(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            onLocationGranted()
        } else {
            error = "Location permission is required"
        }
    }

    LaunchedEffect(Unit) {
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = needed.any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (!missing) {
            onLocationGranted()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    /** Commit to one spin candidate and frame the trip to it. */
    fun choose(c: RouteCandidate) {
        destination = c.destination
        destinationName = c.name
        route = c.route
        candidates = emptyList()
        val loc = myLocation ?: return
        camSuspended = true
        // Buy the same grace period a pan gets, so a pick made at speed isn't
        // re-centered before you've seen the route you just chose.
        lastGestureMs = System.currentTimeMillis()
        mapLibreMap?.let { cameraForPoints(it, listOf(loc, c.destination), FIT_PADDING_PX, fitBottomPaddingPx) }
    }

    // Push overlay state to the map whenever anything drawable changes. The
    // layers are created once per style; here we only swap their GeoJSON data.
    LaunchedEffect(mapOverlays, myLocation, destination, route, radiusKm, mode,
        directionDeg, navigating, candidates) {
        val overlays = mapOverlays ?: return@LaunchedEffect
        // For round trips the slider is trip length; reach ≈ length / 4. Hidden
        // while navigating. Null myLocation hides it too.
        val reachMeters = myLocation?.let {
            when {
                navigating -> null
                mode.roundTrip -> radiusKm * 250.0
                else -> radiusKm * 1000.0
            }
        }
        overlays.render(
            myLocation = myLocation,
            destination = destination,
            routePolyline = route?.polyline,
            reachMeters = reachMeters,
            directionDeg = directionDeg?.toInt(),
            candidates = candidates.mapIndexed { i, c ->
                CandidatePin(c.destination, CANDIDATE_COLORS[i % CANDIDATE_COLORS.size])
            },
            // Dot updates per fix (~1 Hz); the eased camera glides the map under
            // it, so it stays smooth without a per-frame source rewrite.
            showPosition = true,
        )
    }

    // Fog-of-war, fed in two effects on purpose: stored traces change rarely
    // but cost a full re-decimation to assign, while the live fix and trace
    // arrive every second — one combined effect re-paid the decimation on
    // every GPS fix.
    LaunchedEffect(fogEnabled, fogRadius, traces, friendTraces, darkTheme) {
        fogView.active = fogEnabled
        fogView.traces = traces + friendTraces
        fogView.corridorMeters = fogRadius
        fogView.darkTheme = darkTheme
        fogView.invalidate()
    }
    LaunchedEffect(liveTrace, myLocation) {
        fogView.liveTrace = liveTrace
        fogView.currentLocation = myLocation
        fogView.invalidate()
    }

    // Long-press drops a destination pin; a tap on a candidate dot commits to it.
    // Registered once the map is ready; the listeners read live state via refs.
    val candidatesRef = rememberUpdatedState(candidates)
    val navigatingRef = rememberUpdatedState(navigating)
    LaunchedEffect(mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        // The fog is screen-space, projected through the map — redraw it on every
        // camera change so a manual pan/pinch keeps it glued to the map, not just
        // while the follow loop is running.
        map.addOnCameraMoveListener { fogView.invalidate() }
        map.addOnCameraIdleListener { fogView.invalidate() }
        map.addOnMapLongClickListener { ll ->
            if (navigatingRef.value) return@addOnMapLongClickListener false
            destination = LatLon(ll.latitude, ll.longitude)
            destinationName = "Dropped pin"
            route = null
            true
        }
        map.addOnMapClickListener { ll ->
            val p = map.projection.toScreenLocation(ll)
            val tap = RectF(p.x - 22f, p.y - 22f, p.x + 22f, p.y + 22f)
            val idx = map.queryRenderedFeatures(tap, LAYER_CANDIDATES)
                .firstOrNull()?.getNumberProperty("index")?.toInt()
            val cs = candidatesRef.value
            if (idx != null && idx < cs.size) { choose(cs[idx]); true } else false
        }
    }

    fun stopNavigation() {
        navigating = false
        navProgress = null
        camTargetBearing = null
        NavRelay.clear(context)
        BleNavServer.clear(context)
    }

    fun startNavigation() {
        val loc = myLocation ?: run {
            error = "Waiting for your location…"
            return
        }
        camSuspended = false
        if (stats == null) {
            TripTrackingService.start(context, destination?.lat, destination?.lon)
        }
        error = null
        val dest = destination
        if (dest == null) {
            // Round trip: the spin already fetched the loop with instructions.
            if (route?.instructions?.isNotEmpty() == true) {
                navigating = true
            } else {
                error = "No turn data for this loop — spin again with the routing server reachable"
            }
            return
        }
        rerouting = true
        scope.launch {
            try {
                route = withContext(Dispatchers.IO) {
                    RoutingServer.route(serverConfig, loc, dest, mode.ghProfile,
                        Settings.avoidHighways.value, Settings.avoidSmallRoads.value)
                }
                navigating = true
            } catch (e: Exception) {
                error = "Navigation failed: ${e.message}"
            } finally {
                rerouting = false
            }
        }
    }

    // Ambient speed-limit sign while just driving (not navigating). We prefetch
    // every tagged way in a ~1.5km circle once, then snap locally against that
    // set on every fix — so the sign flips the instant you cross onto a new
    // road, instead of lagging a throttled Overpass round-trip behind you. The
    // fetch refreshes only when you near the edge of what you have (throttled on
    // failure so a network blip doesn't hammer the mirrors).
    LaunchedEffect(navigating) {
        if (navigating) return@LaunchedEffect
        TripTrackingService.lastFix.collect { fix ->
            fix ?: return@collect
            if (fix.speedMps < 2.0) return@collect
            val pos = LatLon(fix.lat, fix.lon)
            val fromCenter = speedLimitWaysCenter?.let { RoadRoulette.distanceMeters(it, pos) }
                ?: Double.MAX_VALUE
            val now = System.currentTimeMillis()
            if (fromCenter > RoadRoulette.SPEED_PREFETCH_RADIUS_M - 500.0 &&
                now - speedLimitFetchMs > 10_000
            ) {
                speedLimitFetchMs = now
                val ways = withContext(Dispatchers.IO) { RoadRoulette.speedLimitWays(pos) }
                if (ways.isNotEmpty()) {
                    speedLimitWays = ways
                    speedLimitWaysCenter = pos
                }
            }
            // Heading lets the snap reject the cross street and the frontage
            // road, which is most of why the sign used to show nonsense.
            val result = RoadRoulette.snapSpeedLimitKmh(
                pos, fix.bearingDeg?.toDouble(), speedLimitWays)
            if (result != null) {
                ambientSpeedLimitKmh = result
                speedLimitMisses = 0
            } else if (++speedLimitMisses >= 3) {
                // A few misses in a row means the limit really ended (or the road
                // isn't tagged), not a one-fix gap — only then clear the sign.
                ambientSpeedLimitKmh = null
            }
        }
    }

    // Speed cameras + trajectcontrole sections from Overpass (OSM). Prefetched
    // for a wide circle, refreshed only as you near the edge of what you hold,
    // so there's no request per fix. A null result is a network blip: keep the
    // markers we have and let the throttle retry, instead of flickering them off.
    LaunchedEffect(Unit) {
        var center: LatLon? = null
        var lastFetchMs = 0L
        TripTrackingService.lastFix.collect { fix ->
            fix ?: return@collect
            val pos = LatLon(fix.lat, fix.lon)
            val fromCenter = center?.let { RoadRoulette.distanceMeters(it, pos) }
                ?: Double.MAX_VALUE
            val now = System.currentTimeMillis()
            if (fromCenter > SpeedCameras.PREFETCH_RADIUS_M - 1000.0 &&
                now - lastFetchMs > 15_000
            ) {
                lastFetchMs = now
                val result = withContext(Dispatchers.IO) { SpeedCameras.near(pos) }
                if (result != null) {
                    speedCameras = result.cameras
                    speedSections = result.sections
                    center = pos
                }
            }
        }
    }

    // Push camera markers to the map. Separate from the main overlay render
    // because cameras change on the prefetch cadence, not per drawable-state flip.
    LaunchedEffect(mapOverlays, speedCameras) {
        mapOverlays?.setCameras(speedCameras)
    }

    // Convoy friend markers, on ConvoyLiveClient's own relay-driven cadence —
    // same reasoning as the camera markers above.
    LaunchedEffect(mapOverlays) {
        val overlays = mapOverlays ?: return@LaunchedEffect
        ConvoyLiveClient.peers.collect { peers -> overlays.setFriends(peers.values) }
    }

    // Chime when a camera lies ahead, close, and we're over the posted limit —
    // the one case worth interrupting for. One chime per camera: warnedAt holds
    // the camera we last sounded for and clears once it's behind us, re-arming
    // for the next. Silent when the limit is unknown: we can't judge "too fast".
    val speedCamerasRef = rememberUpdatedState(speedCameras)
    val ambientLimitRef = rememberUpdatedState(ambientSpeedLimitKmh)
    val navProgressRef = rememberUpdatedState(navProgress)
    val toneGen = remember {
        runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull()
    }
    DisposableEffect(Unit) { onDispose { toneGen?.release() } }
    LaunchedEffect(Unit) {
        var warnedAt: LatLon? = null
        TripTrackingService.lastFix.collect { fix ->
            fix ?: return@collect
            val pos = LatLon(fix.lat, fix.lon)
            val heading = fix.bearingDeg?.toDouble()
            val ahead = speedCamerasRef.value.filter { cam ->
                RoadRoulette.distanceMeters(pos, cam.at) <= CAMERA_WARN_METERS &&
                    (heading == null ||
                        RoadRoulette.withinWedge(pos, cam.at, heading, 45.0))
            }.minByOrNull { RoadRoulette.distanceMeters(pos, it.at) }
            if (ahead == null) {
                warnedAt = null
                return@collect
            }
            val limit = navProgressRef.value?.speedLimitKmh ?: ambientLimitRef.value
            val tooFast = limit != null && fix.speedMps * 3.6 > limit + 3.0
            if (tooFast && ahead.at != warnedAt) {
                toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
                warnedAt = ahead.at
            }
        }
    }

    // Average speed through a trajectcontrole. Enter at one end heading for the
    // other, then integrate GPS distance over elapsed time until we pass that
    // far end (or overshoot / time out). The average is what the section
    // actually measures, so it's the number worth seeing while inside one.
    val speedSectionsRef = rememberUpdatedState(speedSections)
    LaunchedEffect(Unit) {
        var active: SpeedCameras.Section? = null
        var exitGate: List<LatLon> = emptyList()
        var entryMs = 0L
        var accMeters = 0.0
        var last: LatLon? = null
        TripTrackingService.lastFix.collect { fix ->
            fix ?: return@collect
            val pos = LatLon(fix.lat, fix.lon)
            val now = System.currentTimeMillis()
            val current = active
            if (current == null) {
                // Below 2 m/s the bearing is noise, so a stopped phone can't
                // heading-test its way into a section.
                val heading = fix.bearingDeg?.toDouble()
                    ?.takeIf { fix.speedMps > 2.0 } ?: return@collect
                // Nearest match, not the first: the two directions of one
                // trajectcontrole are separate relations sharing a location, and
                // a short section can sit inside a longer one.
                val entered = speedSectionsRef.value
                    .mapNotNull { s -> sectionExitGate(s, pos, heading)?.let { s to it } }
                    .minByOrNull { (s, _) ->
                        (s.endA + s.endB).minOf { RoadRoulette.distanceMeters(pos, it) }
                    }
                if (entered != null) {
                    active = entered.first
                    exitGate = entered.second
                    entryMs = now
                    accMeters = 0.0
                    last = pos
                    sectionAvgKmh = null
                    sectionLimitKmh = entered.first.maxspeedKmh
                }
            } else {
                last?.let { accMeters += RoadRoulette.distanceMeters(it, pos) }
                last = pos
                val elapsedHours = (now - entryMs) / 3_600_000.0
                if (elapsedHours > 0 && accMeters > 20.0) {
                    sectionAvgKmh = (accMeters / 1000.0) / elapsedHours
                }
                // Only the end we drove in towards ends the measurement. The
                // 150 m floor keeps the gate we entered through from counting as
                // the exit on the fix right after entering.
                val reachedEnd = accMeters > 150.0 &&
                    exitGate.any { RoadRoulette.distanceMeters(pos, it) < SECTION_GATE_METERS }
                val overshot = accMeters > current.spanMeters * 1.4 + 400.0
                val timedOut = now - entryMs > 30 * 60_000L
                if (reachedEnd || overshot || timedOut) {
                    active = null
                    exitGate = emptyList()
                    last = null
                    sectionAvgKmh = null
                    sectionLimitKmh = null
                }
            }
        }
    }

    // Each fix only moves the targets; nothing touches the map here. This is
    // what lets the camera loop below run uninterrupted — the old code drove
    // animateTo() from an effect keyed on liveFix, so every fix cancelled the
    // previous 350ms flight partway through and the map lurched.
    LaunchedEffect(liveFix, defaultZoom) {
        val fix = liveFix ?: return@LaunchedEffect
        camTarget = LatLon(fix.lat, fix.lon)
        if (fix.bearingDeg != null && fix.speedMps > 2.0) camTargetBearing = fix.bearingDeg
        camTargetZoom = NavEngine.cameraZoom(
            defaultZoom.toDouble(),
            fix.speedMps,
            navProgress?.distanceToTurnMeters ?: Double.MAX_VALUE,
        )
    }

    // The speedometer, eased per frame toward the last fix. Keyed on nothing:
    // it runs for as long as the map is composed, so the number is always
    // gliding rather than stepping once per fix.
    val speedTarget = rememberUpdatedState((liveFix?.speedMps ?: 0.0) * 3.6)
    LaunchedEffect(Unit) {
        var lastNs = withFrameNanos { it }
        while (true) {
            val ns = withFrameNanos { it }
            val dt = ((ns - lastNs) / 1_000_000_000.0).coerceIn(0.0, 0.1)
            lastNs = ns
            val target = speedTarget.value
            val gap = target - displaySpeedKmh
            displaySpeedKmh =
                if (abs(gap) < SPEED_EPS_KMH) target
                else displaySpeedKmh + gap * (1.0 - exp(-dt / SPEED_TAU))
        }
    }

    // The camera itself: one loop, one frame at a time, easing toward whatever
    // the last fix asked for. Compose only produces frames while the activity is
    // resumed, so this costs nothing with the screen off.
    // `haveFix` is a key so that turning follow on before the first fix arrives
    // still starts the loop once it does, instead of leaving it returned-out.
    val haveFix = camTarget != null || myLocation != null
    LaunchedEffect(cameraActive, haveFix, mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        if (!cameraActive) {
            // Level back to north-up when we stop following.
            map.cameraPosition.target?.let {
                setCamera(map, it.latitude, it.longitude, map.cameraPosition.zoom, 0f)
            }
            return@LaunchedEffect
        }
        val start = camTarget ?: myLocation ?: return@LaunchedEffect
        var lat = start.lat
        var lon = start.lon
        var bearing = camTargetBearing ?: 0f
        var zoom = map.cameraPosition.zoom.takeIf { it > 1.0 } ?: camTargetZoom
        // Last values actually pushed to the map. Comparing against these lets us
        // skip setCamera once the ease has settled: an unchanged camera keeps the
        // map idle, which is what stops the per-frame GL redraw + fog invalidate
        // from burning the whole frame budget while stationary or cruising steady.
        var appliedLat = Double.NaN
        var appliedLon = 0.0
        var appliedZoom = 0.0
        var appliedBearing = 0f
        var lastNs = withFrameNanos { it }
        while (true) {
            val ns = withFrameNanos { it }
            // Clamp dt so a dropped frame or a stalled render doesn't teleport us.
            val dt = ((ns - lastNs) / 1_000_000_000.0).coerceIn(0.0, 0.1)
            lastNs = ns

            camTarget?.let { target ->
                val a = 1.0 - exp(-dt / CAM_POS_TAU)
                lat += (target.lat - lat) * a
                lon += (target.lon - lon) * a
            }
            camTargetBearing?.let { target ->
                bearing = smoothBearing(
                    bearing, target, (1.0 - exp(-dt / CAM_BEARING_TAU)).toFloat())
            }
            zoom += (camTargetZoom - zoom) * (1.0 - exp(-dt / CAM_ZOOM_TAU))

            // Heading-up while moving: MapLibre bearing points the camera along
            // travel, so the road you're on runs up the screen. The camera-move
            // listener redraws the fog; the position dot is world-fixed and rides
            // along on its own. Only pushed when the change since the last push is
            // visible (sub-pixel/sub-degree moves are dropped), so a settled camera
            // does no work at all.
            var dBearing = (bearing - appliedBearing) % 360f
            if (dBearing > 180f) dBearing -= 360f
            if (dBearing < -180f) dBearing += 360f
            val moved = appliedLat.isNaN() ||
                abs(lat - appliedLat) > CAM_POS_EPS_DEG ||
                abs(lon - appliedLon) > CAM_POS_EPS_DEG ||
                abs(zoom - appliedZoom) > CAM_ZOOM_EPS ||
                abs(dBearing) > CAM_BEARING_EPS_DEG
            if (moved) {
                setCamera(map, lat, lon, zoom, bearing)
                appliedLat = lat
                appliedLon = lon
                appliedZoom = zoom
                appliedBearing = bearing
            }
        }
    }

    // Current speed for the external display when there's no route up —
    // BleNavServer.send() below covers the navigating case on the same
    // characteristic, so this only fires the other half of the time.
    LaunchedEffect(navigating, liveFix) {
        if (navigating) return@LaunchedEffect
        val fix = liveFix ?: return@LaunchedEffect
        BleNavServer.sendStats(context, currentSpeedKmh = fix.speedMps * 3.6)
    }

    // Follow the route while navigating: progress, arrival, reroute.
    LaunchedEffect(navigating, liveFix, route) {
        if (!navigating) return@LaunchedEffect
        val fix = liveFix ?: return@LaunchedEffect
        val r = route ?: return@LaunchedEffect
        val pos = LatLon(fix.lat, fix.lon)
        val progress = NavEngine.progress(r, pos) ?: return@LaunchedEffect
        navProgress = progress
        NavRelay.send(context, progress, currentSpeedKmh = fix.speedMps * 3.6)
        BleNavServer.send(context, progress, currentSpeedKmh = fix.speedMps * 3.6)

        // Arrived (point-to-point; loops end back at the start on their own).
        if (destination != null && progress.remainingMeters < 40 &&
            progress.offRouteMeters < 60
        ) {
            stopNavigation()
            return@LaunchedEffect
        }

        // Off route → fresh route to the destination. Launched on the screen
        // scope so the next GPS fix doesn't cancel the request; loops keep
        // their drawn line (rerouting a loop would change the whole trip).
        val dest = destination
        val now = System.currentTimeMillis()
        if (dest != null && progress.offRouteMeters > 60 &&
            !rerouting && now - lastRerouteMs > 15_000
        ) {
            rerouting = true
            lastRerouteMs = now
            scope.launch {
                try {
                    route = withContext(Dispatchers.IO) {
                        RoutingServer.route(serverConfig, pos, dest, mode.ghProfile,
                            Settings.avoidHighways.value, Settings.avoidSmallRoads.value)
                    }
                } catch (e: Exception) {
                    // stay on the old line; retried after the cooldown
                } finally {
                    rerouting = false
                }
            }
        }
    }

    fun spin() {
        val loc = myLocation ?: run {
            error = "Waiting for your location…"
            fetchLocation()
            return
        }
        spinJob = scope.launch {
            spinning = true
            error = null
            // The result gets framed on the map; a following camera would drag
            // it straight back to you before you could look at it.
            camSuspended = true
            var serverError: String? = null
            try {
                // Bias destinations toward territory the fog hasn't uncovered.
                val explored = withContext(Dispatchers.IO) { ExploredArea.load(context) }
                if (mode.roundTrip) {
                    // Prefer the self-hosted routing server (single fast request,
                    // real road-following loop); fall back to Overpass sampling.
                    val tripMeters = radiusKm * 1000.0
                    var result: RouteResult? = null
                    if (serverConfig.usable) {
                        result = try {
                            withContext(Dispatchers.IO) {
                                RoutingServer.roundTrip(
                                    serverConfig, loc, tripMeters, Random.nextLong(),
                                    headingDeg = directionDeg?.toDouble(),
                                    avoidSmallRoads = Settings.avoidSmallRoads.value)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            serverError = e.message ?: e.javaClass.simpleName
                            null // fall back to Overpass below, but say why
                        }
                    }
                    if (result == null) {
                        val wps = RoundTripPlanner.plan(
                            loc, tripMeters / 4.0, mode.highwayRegex,
                            bearingDeg = directionDeg?.toDouble())
                        result = RouteResult(
                            polyline = listOf(loc) + wps + loc,
                            waypoints = wps,
                            distanceMeters = null,
                        )
                        if (serverError != null) {
                            error = "Server route failed ($serverError) — approximate loop instead"
                        }
                    }
                    route = result
                    destination = null
                    destinationName = null
                    // A spin result landing is the app's payoff moment; a small
                    // buzz marks it without needing eyes on the screen.
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    mapLibreMap?.let { cameraForPoints(it, result.polyline + loc, FIT_PADDING_PX, fitBottomPaddingPx) }
                } else {
                    val bearing = directionDeg?.toDouble()
                    val minMeters = minRadiusKm.toDouble() * 1000.0
                    val picks = withTimeout(30_000) {
                        coroutineScope {
                            (1..3).map {
                                async(Dispatchers.IO) {
                                    runCatching {
                                        pickCandidate(
                                            serverConfig, loc, radiusKm.toDouble() * 1000.0,
                                            minMeters, mode, poiKind, bearing, explored)
                                    }
                                }
                            }.awaitAll()
                        }
                    }
                    val results = picks.mapNotNull { it.getOrNull() }
                    if (results.isEmpty()) {
                        throw picks.firstNotNullOfOrNull { it.exceptionOrNull() }
                            ?: IOException("Failed to find a destination")
                    }
                    candidates = results
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    mapLibreMap?.let {
                        cameraForPoints(it, results.map { c -> c.destination } + loc, FIT_PADDING_PX, fitBottomPaddingPx)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                // Don't let a fallback timeout hide why the own server failed.
                error = serverError
                    ?.let { "Server route failed ($it); fallback timed out too" }
                    ?: if (mode.roundTrip && !serverConfig.usable) {
                        "No routing server configured — public servers timed out"
                    } else {
                        "Road servers are slow right now — try again"
                    }
            } catch (e: CancellationException) {
                throw e // user cancelled or screen left; finally still resets state
            } catch (e: Exception) {
                error = e.message ?: "Failed to find a road"
            } finally {
                spinning = false
            }
        }
    }

    fun selectMode(m: TravelMode) {
        if (m == mode) return
        Settings.setTripMode(m)
        radiusKm = m.defaultKm
        minRadiusKm = 0f
        destination = null
        destinationName = null
        route = null
        candidates = emptyList()
    }

    Scaffold(
        // Modes are the app's top-level places, so they live in the one bar that
        // is always in reach of a thumb. Navigation hides it: nothing to switch
        // to mid-route, and the map wants the pixels.
        bottomBar = {
            AnimatedVisibility(
                visible = !navigating,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) { ModeBar(mode, ::selectMode) }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { scaffoldPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = scaffoldPadding.calculateBottomPadding()),
        ) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

            // The banner drops in from the top edge when navigation starts; the
            // toolbar fades back once it ends.
            AnimatedVisibility(
                visible = navigating,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NavigationBanner(progress = navProgress, rerouting = rerouting,
                        modifier = Modifier.fillMaxWidth())
                    ThenPill(navProgress)
                }
            }
            AnimatedVisibility(
                visible = !navigating,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(),
            ) {
                MapTopChrome(
                    followMe = following,
                    fogEnabled = fogEnabled,
                    username = accountUsername,
                    convoyName = if (convoyConnected) convoyName else null,
                    onToggleFollow = {
                        if (following) followMe = false
                        else { followMe = true; camSuspended = false }
                    },
                    onSearch = { searchOpen = true },
                    onToggleFog = { Settings.setFogEnabled(!fogEnabled) },
                    onOpenHub = onOpenHub,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(12.dp),
                )
            }

            // Hold-to-talk: only shown while a convoy's live relay is actually
            // connected (see ConvoyLiveService, started from FriendsScreen).
            AnimatedVisibility(
                visible = convoyConnected,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
            ) {
                PushToTalkButton(talking = convoyTalking.isNotEmpty())
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .then(if (navigating) Modifier.navigationBarsPadding() else Modifier)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Ending a trip used to mean expanding the spin card and hunting
                // for a button. It now sits here whatever else is on screen, on
                // the opposite side from the speed you are looking at anyway.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    // Always in the row (zero-sized when hidden) so SpaceBetween
                    // keeps the speed HUD pinned to the end either way.
                    AnimatedVisibility(
                        visible = stats != null,
                        enter = scaleIn() + fadeIn(),
                        exit = scaleOut() + fadeOut(),
                    ) {
                        EndTripButton(onClick = { TripTrackingService.stop(context) })
                    }
                    // Stays up while the eased number winds back down, so
                    // stopping at a light fades the dial out instead of
                    // snatching it away mid-count.
                    liveFix?.takeIf { it.speedMps >= 1.4 || displaySpeedKmh >= 2.0 }?.let {
                        SpeedHud(
                            speedKmh = displaySpeedKmh,
                            limitKmh = if (navigating) navProgress?.speedLimitKmh
                                else ambientSpeedLimitKmh,
                            averageKmh = sectionAvgKmh,
                            averageLimitKmh = sectionLimitKmh,
                        )
                    }
                }

                // The exiting card still composes for a few frames after `stats`
                // goes null; keep the last value so it animates out with content.
                val shownStats = remember { mutableStateOf(stats) }
                if (stats != null) shownStats.value = stats
                AnimatedVisibility(
                    visible = stats != null,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    shownStats.value?.let { ActiveTripCard(it) }
                }

                // Shortcut chips: one-tap a saved place to set it as destination,
                // or save the pin you just dropped. Hidden while navigating.
                AnimatedVisibility(
                    visible = !navigating && (savedPlaces.isNotEmpty() || destination != null),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    ShortcutChips(
                        places = savedPlaces,
                        canSavePin = destination != null,
                        onPick = { p ->
                            destination = p.location
                            destinationName = p.name
                            route = null
                            camSuspended = true
                            lastGestureMs = System.currentTimeMillis()
                            mapLibreMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(
                                LatLng(p.location.lat, p.location.lon), 14.0), 600)
                        },
                        onSavePin = { destination?.let { savePinTarget = it } },
                    )
                }

                // One slot, four occupants; animate the handover instead of
                // hard-swapping so the bottom of the screen stops popping.
                val bottomCard = when {
                    navigating -> BottomCard.NAV
                    candidates.isNotEmpty() -> BottomCard.CANDIDATES
                    settingsCollapsed -> BottomCard.COLLAPSED
                    else -> BottomCard.EXPANDED
                }
                // Same trick as shownStats: the exiting candidates pane must
                // not render an empty card after a cancel clears the list.
                val shownCandidates = remember { mutableStateOf(candidates) }
                if (candidates.isNotEmpty()) shownCandidates.value = candidates
                AnimatedContent(
                    targetState = bottomCard,
                    transitionSpec = {
                        (fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 10 })
                            .togetherWith(fadeOut(tween(120)))
                    },
                    label = "bottomCard",
                ) { card ->
                    when (card) {
                        BottomCard.NAV -> NavigationBottomBar(
                            progress = navProgress,
                            offRoute = (navProgress?.offRouteMeters ?: 0.0) > 60,
                            onExit = { stopNavigation() },
                        )
                        BottomCard.CANDIDATES -> CandidatesCard(
                            candidates = shownCandidates.value,
                            onPick = ::choose,
                            onReroll = { candidates = emptyList(); spin() },
                            onCancel = { candidates = emptyList() },
                        )
                        BottomCard.COLLAPSED -> SpinDock(
                            mode = mode,
                            radiusKm = radiusKm,
                            directionDeg = directionDeg,
                            spinning = spinning,
                            destination = destination,
                            route = route,
                            origin = myLocation,
                            inAppAvailable = serverConfig.usable &&
                                (destination != null ||
                                    route?.instructions?.isNotEmpty() == true),
                            onSpin = { if (spinning) spinJob?.cancel() else spin() },
                            onExpand = { settingsCollapsed = false },
                            onNavigateInApp = { startNavigation() },
                            onNavigate = {
                                if (stats == null) {
                                    TripTrackingService.start(context, destination?.lat, destination?.lon)
                                }
                            },
                        )
                        BottomCard.EXPANDED -> SpinSheet(
                            mode = mode,
                            radiusKm = radiusKm,
                            onRadiusChange = { radiusKm = it },
                            minRadiusKm = minRadiusKm,
                            onMinRadiusChange = { minRadiusKm = it },
                            poiKind = poiKind,
                            onPoiKindChange = { poiKind = it },
                            directionDeg = directionDeg,
                            onDirectionChange = { directionDeg = it },
                            spinning = spinning,
                            error = error,
                            route = route,
                            destinationName = destinationName,
                            destination = destination,
                            origin = myLocation,
                            stats = stats,
                            inAppAvailable = serverConfig.usable &&
                                (destination != null ||
                                    route?.instructions?.isNotEmpty() == true),
                            onSpin = { if (spinning) spinJob?.cancel() else spin() },
                            onCollapse = { settingsCollapsed = true },
                            onNavigateInApp = { startNavigation() },
                            onNavigate = {
                                if (stats == null) {
                                    TripTrackingService.start(context, destination?.lat, destination?.lon)
                                }
                            },
                            onTrack = {
                                TripTrackingService.start(context, destination?.lat, destination?.lon)
                            },
                        )
                    }
                }
            }
        }
    }

    savePinTarget?.let { target ->
        SavePinDialog(
            suggestedName = destinationName?.takeIf { it != "Dropped pin" } ?: "",
            onSave = { name ->
                SavedPlaces.add(context, name, target)
                savePinTarget = null
            },
            onDismiss = { savePinTarget = null },
        )
    }

    if (searchOpen) {
        SearchDialog(
            near = myLocation,
            onPick = { r ->
                searchOpen = false
                destination = r.location
                destinationName = r.name
                route = null
                camSuspended = true
                lastGestureMs = System.currentTimeMillis()
                mapLibreMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(
                    LatLng(r.location.lat, r.location.lon), 14.0), 800)
            },
            onDismiss = { searchOpen = false },
        )
    }
}

/** Full-screen place search: type to get live suggestions, tap one to make it the
 *  destination. Opens with the keyboard up, recents show first, and there is no
 *  Search button — results stream in as you type. */
@Composable
private fun SearchDialog(
    near: LatLon?,
    onPick: (GeocodeResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeocodeResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val recents = remember { RecentSearchStore.load(context) }
    val recentNames = remember(recents) { recents.map { it.name }.toSet() }
    val focusRequester = remember { FocusRequester() }

    fun pick(r: GeocodeResult) {
        RecentSearchStore.save(context, r)
        onPick(r)
    }

    // Start with the keyboard up so the user types straight away.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Live, debounced suggestions. Matching recents show instantly, then a single
    // Photon lookup runs — it already blends the query match with proximity to the
    // user, so nearby streets and POIs rank first while a famous far place still
    // surfaces where it belongs. Recents are kept on top, then deduped against hits.
    LaunchedEffect(query) {
        val q = query.trim()
        error = null
        if (q.length < 2) {
            results = if (q.isEmpty()) recents
                else recents.filter { it.name.contains(q, ignoreCase = true) }
            searching = false
            return@LaunchedEffect
        }
        val recentMatches = recents.filter { it.name.contains(q, ignoreCase = true) }
        results = recentMatches
        delay(300)
        searching = true
        try {
            val hits = withContext(Dispatchers.IO) { Geocoder.search(context, q, near) }
            val seen = HashSet(recentMatches.map { it.name })
            val merged = ArrayList(recentMatches)
            for (hit in hits) if (seen.add(hit.name)) merged.add(hit)
            results = merged
            error = if (merged.isEmpty()) "No results" else null
        } catch (e: Exception) {
            error = e.message ?: "Search failed"
        }
        searching = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search address or place") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searching) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Outlined.Clear, contentDescription = "Clear")
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    )
                }
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(results) { r ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { pick(r) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (r.name in recentNames) Icons.Outlined.History else Icons.Outlined.Place,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(r.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}

/** One-tap saved-place chips over the map, plus a "Save pin" chip when a
 *  destination pin is on screen. Scrolls horizontally when they overflow. */
@Composable
private fun ShortcutChips(
    places: List<SavedPlace>,
    canSavePin: Boolean,
    onPick: (SavedPlace) -> Unit,
    onSavePin: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (canSavePin) {
            AssistChip(
                onClick = onSavePin,
                label = { Text("Save pin") },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null,
                    Modifier.size(18.dp)) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = glassContainerColor()),
            )
        }
        places.forEach { p ->
            AssistChip(
                onClick = { onPick(p) },
                label = { Text(p.name, maxLines = 1) },
                leadingIcon = { Icon(Icons.Default.Place, contentDescription = null,
                    Modifier.size(18.dp)) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = glassContainerColor()),
            )
        }
    }
}

/** Name the current pin and save it as a shortcut. */
@Composable
private fun SavePinDialog(
    suggestedName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(suggestedName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save this place") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name (Home, Work…)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One pill in a [PillRow]: rounded, filled when selected. No new dependency —
 *  built on Surface rather than SegmentedButton so it can scroll horizontally
 *  (the direction row) or fill the width evenly (the destination-type row). */
@Composable
private fun Pill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** A row of equal-width pill segments — the destination-type control. */
@Composable
private fun SegmentedPillRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            Pill(label, i == selectedIndex, { onSelect(i) }, Modifier.weight(1f))
        }
    }
}

/** A horizontally scrolling row of pills — the direction picker, which has
 *  too many options (9) to fit evenly on a phone width. */
@Composable
private fun ScrollingPillRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { i, label ->
            Pill(label, i == selectedIndex, { onSelect(i) })
        }
    }
}

/** Launches navigation via [app] and remembers it as the default for next
 *  time — the single dispatch point behind the dropdown items in
 *  [NavMenuItems] and the direct-tap bypass on [NavButton]/[NavIconButton]. */
private fun launchNav(
    context: Context,
    app: Settings.NavApp,
    destination: LatLon?,
    route: List<LatLon>?,
    origin: LatLon?,
    mode: TravelMode,
    onNavigateInApp: () -> Unit,
    onNavigate: () -> Unit,
) {
    when (app) {
        Settings.NavApp.IN_APP -> onNavigateInApp()
        Settings.NavApp.GOOGLE_MAPS -> {
            onNavigate()
            // Waze can't take multi-waypoint routes; Google Maps only.
            if (route != null && origin != null) navigateRoundTrip(context, origin, route)
            else destination?.let { navigateGoogleMaps(context, it, mode) }
        }
        Settings.NavApp.WAZE -> { onNavigate(); destination?.let { navigateWaze(context, it) } }
        Settings.NavApp.OTHER -> { onNavigate(); destination?.let { navigateGeo(context, it) } }
        Settings.NavApp.ASK -> return // unreachable — callers only pass a concrete app
    }
    Settings.setPreferredNavApp(app)
}

/** Whether [app] can be launched right now without opening the menu — false
 *  for ASK (nothing remembered yet), and false when a round-trip route is
 *  active but [app] can't take multi-waypoint routes (Waze/"Other app"). */
private fun navAppUsableDirectly(
    app: Settings.NavApp,
    inAppAvailable: Boolean,
    route: List<LatLon>?,
    origin: LatLon?,
): Boolean = when (app) {
    Settings.NavApp.ASK -> false
    Settings.NavApp.IN_APP -> inAppAvailable
    Settings.NavApp.GOOGLE_MAPS -> true
    Settings.NavApp.WAZE, Settings.NavApp.OTHER -> !(route != null && origin != null)
}

/** A tap on [NavButton]/[NavIconButton]: go straight to the remembered app
 *  when it's usable here, otherwise fall back to opening the menu — the
 *  same fallback a long-press always takes. */
private fun handleGoTap(
    context: Context,
    preferred: Settings.NavApp,
    inAppAvailable: Boolean,
    destination: LatLon?,
    route: List<LatLon>?,
    origin: LatLon?,
    mode: TravelMode,
    onNavigateInApp: () -> Unit,
    onNavigate: () -> Unit,
    openMenu: () -> Unit,
) {
    if (navAppUsableDirectly(preferred, inAppAvailable, route, origin)) {
        launchNav(context, preferred, destination, route, origin, mode, onNavigateInApp, onNavigate)
    } else {
        openMenu()
    }
}

/** Shared "Go" menu items — in-app when reachable, otherwise the external-app
 *  chooser. Backs both the full-width [NavButton] and the dock's compact
 *  [NavIconButton] so the routing logic lives in exactly one place. */
@Composable
private fun NavMenuItems(
    destination: LatLon?,
    route: List<LatLon>?,
    origin: LatLon?,
    mode: TravelMode,
    inAppAvailable: Boolean,
    onNavigateInApp: () -> Unit,
    onNavigate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    fun pick(app: Settings.NavApp) {
        onDismiss()
        launchNav(context, app, destination, route, origin, mode, onNavigateInApp, onNavigate)
    }
    if (inAppAvailable) {
        DropdownMenuItem(
            text = { Text("Navigate in app") },
            onClick = { pick(Settings.NavApp.IN_APP) },
        )
    }
    if (route != null && origin != null) {
        DropdownMenuItem(
            text = { Text("Google Maps (round trip)") },
            onClick = { pick(Settings.NavApp.GOOGLE_MAPS) },
        )
    } else {
        DropdownMenuItem(
            text = { Text("Google Maps") },
            onClick = { pick(Settings.NavApp.GOOGLE_MAPS) },
        )
        DropdownMenuItem(
            text = { Text("Waze") },
            onClick = { pick(Settings.NavApp.WAZE) },
        )
        DropdownMenuItem(
            text = { Text("Other app") },
            onClick = { pick(Settings.NavApp.OTHER) },
        )
    }
}

/** Compact circular "Go" trigger for the dock — same menu as [NavButton],
 *  just a 40dp icon button instead of a labelled tonal one. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NavIconButton(
    destination: LatLon?,
    route: List<LatLon>?,
    origin: LatLon?,
    mode: TravelMode,
    inAppAvailable: Boolean,
    onNavigateInApp: () -> Unit,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val preferred by Settings.preferredNavApp.collectAsStateWithLifecycle()
    val enabled = destination != null || (route != null && origin != null)
    Box(modifier) {
        Surface(
            modifier = Modifier
                .size(40.dp)
                .combinedClickable(
                    enabled = enabled,
                    onClick = {
                        handleGoTap(context, preferred, inAppAvailable, destination, route, origin,
                            mode, onNavigateInApp, onNavigate) { menuOpen = true }
                    },
                    onLongClick = { menuOpen = true },
                ),
            shape = CircleShape,
            color = if (enabled) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            contentColor = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(Icons.Outlined.Navigation, contentDescription = "Go")
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            NavMenuItems(destination, route, origin, mode, inAppAvailable,
                onNavigateInApp, onNavigate) { menuOpen = false }
        }
    }
}

/** Persistent glass bar at the bottom of the map: the spin dock. Tapping the
 *  left cell opens the sheet; the dice FAB spins right away without needing
 *  the sheet open at all. */
@Composable
private fun SpinDock(
    mode: TravelMode,
    radiusKm: Float,
    directionDeg: Float?,
    spinning: Boolean,
    destination: LatLon?,
    route: RouteResult?,
    origin: LatLon?,
    inAppAvailable: Boolean,
    onSpin: () -> Unit,
    onExpand: () -> Unit,
    onNavigateInApp: () -> Unit,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .glassBorder(MaterialTheme.shapes.extraLarge),
        shape = MaterialTheme.shapes.extraLarge,
        colors = glassCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onExpand),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(mode.icon, contentDescription = null)
                Column {
                    Text(
                        "${if (mode.maxKm <= 10f) "%.1f".format(radiusKm) else radiusKm.toInt()} km",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${mode.label} · " + (directionDeg?.let { DIRECTION_NAMES[(it / 45f).toInt()] }
                            ?: "any direction"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Icon(Icons.Outlined.ExpandLess, contentDescription = "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(
                onClick = onSpin,
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(52.dp),
            ) {
                if (spinning) {
                    CircularProgressIndicator(
                        Modifier.size(22.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.Outlined.Casino, contentDescription = "Spin")
                }
            }
            NavIconButton(
                destination = destination,
                route = route?.waypoints,
                origin = origin,
                mode = mode,
                inAppAvailable = inAppAvailable,
                onNavigateInApp = onNavigateInApp,
                onNavigate = onNavigate,
            )
        }
    }
}

/** The spin sheet: everything the dock's left cell expands into. Same glass
 *  card the dock uses, just taller — a drag-handle bar stands in for an
 *  actual drag gesture, tap it (or the chevron) to fold back to the dock. */
@Composable
private fun SpinSheet(
    mode: TravelMode,
    radiusKm: Float,
    onRadiusChange: (Float) -> Unit,
    minRadiusKm: Float,
    onMinRadiusChange: (Float) -> Unit,
    poiKind: PoiKind,
    onPoiKindChange: (PoiKind) -> Unit,
    directionDeg: Float?,
    onDirectionChange: (Float?) -> Unit,
    spinning: Boolean,
    error: String?,
    route: RouteResult?,
    destinationName: String?,
    destination: LatLon?,
    origin: LatLon?,
    stats: TripStats?,
    inAppAvailable: Boolean,
    onSpin: () -> Unit,
    onCollapse: () -> Unit,
    onNavigateInApp: () -> Unit,
    onNavigate: () -> Unit,
    onTrack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.glassBorder(MaterialTheme.shapes.extraLarge),
        shape = MaterialTheme.shapes.extraLarge,
        colors = glassCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onCollapse),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 34.dp, height = 4.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            CircleShape,
                        ),
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Spin a destination",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onCollapse, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.ExpandMore, contentDescription = "Collapse")
                }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }

            // roundTrip is a fixed property of the mode (only Moto has it), not
            // a chooseable segment — so it gates the destination-type row the
            // same way it always gated the old dropdown, rather than adding a
            // "Loop" option to pick.
            if (!mode.roundTrip) {
                SegmentedPillRow(
                    options = PoiKind.entries.map { it.label },
                    selectedIndex = PoiKind.entries.indexOf(poiKind),
                    onSelect = { onPoiKindChange(PoiKind.entries[it]) },
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (mode.roundTrip) "Trip length" else "Radius",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    if (mode.maxKm <= 10f) "%.1f km".format(radiusKm)
                    else "${radiusKm.toInt()} km",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            route?.distanceMeters?.let {
                Text("Loop found: ${formatDistanceKm(it)}", style = MaterialTheme.typography.bodySmall)
            }
            destinationName?.let {
                Text("→ $it", style = MaterialTheme.typography.bodySmall)
            }
            Slider(
                value = radiusKm,
                onValueChange = onRadiusChange,
                valueRange = mode.minKm..mode.maxKm,
            )

            if (!mode.roundTrip) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Min distance", style = MaterialTheme.typography.labelLarge)
                    Text(
                        if (minRadiusKm <= 0f) "Off"
                        else if (mode.maxKm <= 10f) "%.1f km".format(minRadiusKm)
                        else "${minRadiusKm.toInt()} km",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Slider(
                    value = minRadiusKm,
                    onValueChange = onMinRadiusChange,
                    valueRange = 0f..radiusKm,
                )
            }

            Text("Direction", style = MaterialTheme.typography.labelLarge)
            ScrollingPillRow(
                options = listOf("Any") + DIRECTION_NAMES,
                selectedIndex = directionDeg?.let { (it / 45f).toInt() + 1 } ?: 0,
                onSelect = { i -> onDirectionChange(if (i == 0) null else (i - 1) * 45f) },
            )

            Button(
                onClick = onSpin,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                if (spinning) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Casino, contentDescription = null, Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    if (spinning) "Cancel" else "Spin",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NavButton(
                    destination = destination,
                    route = route?.waypoints,
                    origin = origin,
                    mode = mode,
                    inAppAvailable = inAppAvailable,
                    onNavigateInApp = onNavigateInApp,
                    onNavigate = onNavigate,
                    modifier = Modifier.weight(1f),
                )
                if (stats == null) {
                    OutlinedButton(onClick = onTrack, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Track ${mode.label.lowercase()}", maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Spin results awaiting a pick: distance/ETA per candidate, tap one to commit to it. */
@Composable
private fun CandidatesCard(
    candidates: List<RouteCandidate>,
    onPick: (RouteCandidate) -> Unit,
    onReroll: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.glassBorder(MaterialTheme.shapes.extraLarge),
        shape = MaterialTheme.shapes.extraLarge,
        colors = glassCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Pick a destination", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            Text(
                "All three are on the map — tap a pin or a row.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            candidates.forEachIndexed { index, c ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(c) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(26.dp)
                            .background(
                                Color(CANDIDATE_COLORS[index % CANDIDATE_COLORS.size]),
                                RoundedCornerShape(8.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            ('A' + index).toString(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            // Fixed dark text: the candidate colours are chosen
                            // deliberately (see CANDIDATE_COLORS) and are all
                            // light enough that a themed on-color would clash.
                            color = Color(0xFF1A1A1A),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            c.name ?: "Option ${index + 1}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        val distanceMeters = c.route?.distanceMeters ?: c.straightLineMeters
                        val prefix = if (c.route?.distanceMeters == null) "~ straight-line " else "via road "
                        Text(
                            prefix + formatDistanceKm(distanceMeters),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    c.route?.timeMs?.let { timeMs ->
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Text(
                                "%.0f min".format(timeMs / 60_000.0),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                Button(onClick = onReroll, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Casino, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Reroll")
                }
            }
        }
    }
}

/** The app's three places. Selecting one also tells the tracking service what
 *  you are riding, which decides the stats it bothers to record. */
@Composable
private fun ModeBar(selected: TravelMode, onSelect: (TravelMode) -> Unit) {
    NavigationBar {
        TravelMode.entries.forEach { m ->
            NavigationBarItem(
                selected = m == selected,
                onClick = { onSelect(m) },
                icon = { Icon(m.icon, contentDescription = null) },
                label = { Text(m.label) },
            )
        }
    }
}

/** Map top chrome: a full-width search pill with an avatar that opens the Hub,
 *  and a right-aligned rail of the two controls worth reaching for while
 *  driving (follow toggle, layers). Everything else moved to the Hub. */
@Composable
private fun MapTopChrome(
    followMe: Boolean,
    fogEnabled: Boolean,
    username: String,
    convoyName: String?,
    onToggleFollow: () -> Unit,
    onSearch: () -> Unit,
    onToggleFog: () -> Unit,
    onOpenHub: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var layersOpen by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SearchPill(username = username, onSearch = onSearch, onAvatarClick = onOpenHub)
        AnimatedVisibility(visible = convoyName != null, enter = fadeIn(), exit = fadeOut()) {
            ConvoyPill(name = convoyName ?: "")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassRailButton(
                    icon = if (followMe) Icons.Outlined.MyLocation else Icons.Outlined.LocationSearching,
                    contentDescription = if (followMe) "Stop following my location"
                        else "Follow my location",
                    tinted = followMe,
                    onClick = onToggleFollow,
                )
                Box {
                    GlassRailButton(
                        icon = Icons.Outlined.Layers,
                        contentDescription = "Map layers",
                        onClick = { layersOpen = !layersOpen },
                    )
                    if (layersOpen) {
                        val density = LocalDensity.current
                        Popup(
                            alignment = Alignment.TopEnd,
                            offset = with(density) { IntOffset(0, 48.dp.roundToPx()) },
                            onDismissRequest = { layersOpen = false },
                            properties = PopupProperties(dismissOnClickOutside = true),
                        ) {
                            Card(
                                modifier = Modifier.glassBorder(MaterialTheme.shapes.large),
                                shape = MaterialTheme.shapes.large,
                                colors = glassCardColors(),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        if (fogEnabled) Icons.Outlined.Visibility
                                            else Icons.Outlined.VisibilityOff,
                                        contentDescription = null,
                                    )
                                    Text("Fog of war", modifier = Modifier.weight(1f))
                                    Switch(checked = fogEnabled, onCheckedChange = { onToggleFog() })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Full-width glass search pill: tapping the body opens search, tapping the
 *  avatar opens the Hub. */
@Composable
private fun SearchPill(
    username: String,
    onSearch: () -> Unit,
    onAvatarClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().glassBorder(CircleShape),
        shape = CircleShape,
        colors = CardDefaults.cardColors(containerColor = glassContainerColor()),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSearch)
                .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(
                "Where to?",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onAvatarClick),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    username.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/** Small pill under [SearchPill] naming the convoy this device is currently
 *  live in, i.e. whenever [ConvoyLiveClient.connected] is true. */
@Composable
private fun ConvoyPill(name: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.glassBorder(CircleShape),
        shape = CircleShape,
        colors = CardDefaults.cardColors(containerColor = glassContainerColor()),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Groups,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(name, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** One 40dp glass button in the top-right rail; tinted primary when its
 *  toggle is active (currently just the follow button). */
@Composable
private fun GlassRailButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tinted: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.size(40.dp).glassBorder(CircleShape),
        shape = CircleShape,
        colors = CardDefaults.cardColors(containerColor = glassContainerColor()),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                icon, contentDescription = contentDescription,
                Modifier.size(20.dp),
                tint = if (tinted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Always on screen while a trip is running, in the corner your thumb rests in. */
@Composable
private fun EndTripButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) {
        Icon(Icons.Outlined.Stop, contentDescription = null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("End trip", maxLines = 1, fontWeight = FontWeight.Bold)
    }
}

/** Hold to talk; only shown while a convoy's live relay is connected (see
 *  ConvoyLiveService). Solid red while you're pressing it; a primary-colored
 *  ring while [talking] — a friend currently transmitting — so incoming PTT
 *  audio isn't silent-and-invisible. */
@Composable
private fun PushToTalkButton(talking: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    val containerColor = when {
        pressed -> MaterialTheme.colorScheme.error
        talking -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(
        modifier = modifier
            .size(64.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            return@detectTapGestures
                        }
                        pressed = true
                        // Off the main thread: AudioRecord construction and
                        // stopTalking's join(500) can both take real time,
                        // and this fires from a gesture handler on a screen
                        // meant to be glanced at while riding.
                        scope.launch(Dispatchers.IO) { PushToTalk.startTalking() }
                        try {
                            awaitRelease()
                        } finally {
                            pressed = false
                            scope.launch(Dispatchers.IO) { PushToTalk.stopTalking() }
                        }
                    },
                )
            },
        shape = CircleShape,
        color = containerColor,
        shadowElevation = 4.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = "Push to talk",
                tint = if (pressed) MaterialTheme.colorScheme.onError
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/** Current speed next to the posted limit for the road we're on. Used both while
 *  cruising and while navigating; the whole dial turns red once we're more than
 *  5 km/h over. Sized to be read at a glance, not to dominate the map — the trip
 *  card no longer repeats the number underneath it. */
@Composable
private fun SpeedHud(
    speedKmh: Double,
    limitKmh: Double?,
    averageKmh: Double? = null,
    averageLimitKmh: Double? = null,
    modifier: Modifier = Modifier,
) {
    val speeding = limitKmh != null && speedKmh > limitKmh + 5
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Inside a trajectcontrole: the running average is what the section
        // measures, so it sits front and centre and turns red once it's over.
        averageKmh?.let { avg ->
            SectionAverageChip(avg, averageLimitKmh)
        }
        Crossfade(targetState = limitKmh, animationSpec = tween(300), label = "speedLimit") {
            SpeedLimitSign(it, size = 48.dp)
        }
        Card(
            modifier = Modifier.glassBorder(CircleShape),
            shape = CircleShape,
            colors = if (speeding) CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ) else glassCardColors(),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        ) {
            Column(
                Modifier.size(80.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "%.0f".format(speedKmh),
                    fontSize = 32.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (speeding) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "km/h",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (speeding) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Running average speed through a trajectcontrole, next to the live speed.
 *  Red once the average is over the section's posted limit — that's the number
 *  the camera pair is actually about to fine you on. */
@Composable
private fun SectionAverageChip(averageKmh: Double, limitKmh: Double?, modifier: Modifier = Modifier) {
    val over = limitKmh != null && averageKmh > limitKmh
    Card(
        modifier = modifier,
        shape = CircleShape,
        colors = CardDefaults.cardColors(
            containerColor = if (over) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.tertiaryContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(
            Modifier.size(72.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val onColor = if (over) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onTertiaryContainer
            Text(
                "Ø %.0f".format(averageKmh),
                fontSize = 26.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Bold,
                color = onColor,
            )
            Text("avg km/h", style = MaterialTheme.typography.labelSmall, color = onColor)
        }
    }
}

/** "Go" button with a chooser for the navigation app. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NavButton(
    destination: LatLon?,
    route: List<LatLon>?,
    origin: LatLon?,
    mode: TravelMode,
    inAppAvailable: Boolean,
    onNavigateInApp: () -> Unit,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val preferred by Settings.preferredNavApp.collectAsStateWithLifecycle()
    val enabled = destination != null || (route != null && origin != null)
    Box(modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .combinedClickable(
                    enabled = enabled,
                    onClick = {
                        handleGoTap(context, preferred, inAppAvailable, destination, route, origin,
                            mode, onNavigateInApp, onNavigate) { menuOpen = true }
                    },
                    onLongClick = { menuOpen = true },
                ),
            shape = ButtonDefaults.shape,
            color = if (enabled) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            contentColor = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ) {
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Navigation, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Go", maxLines = 1)
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            NavMenuItems(destination, route, origin, mode, inAppAvailable,
                onNavigateInApp, onNavigate) { menuOpen = false }
        }
    }
}

/** Live trip numbers, minus the ones already on screen: current speed is the
 *  HUD, and a car has no lean angle worth printing. */
@Composable
private fun ActiveTripCard(stats: TripStats) {
    // Tick every second so duration counts up even without GPS updates.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(stats.startTimeMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Card(
        modifier = Modifier.glassBorder(MaterialTheme.shapes.extraLarge),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatItem("Time", formatDuration(now - stats.startTimeMs))
            StatItem("Distance", formatDistanceKm(stats.distanceMeters))
            StatItem("Top", formatSpeedKmh(stats.topSpeedMps))
            if (stats.mode.tracksLean) {
                StatItem("Lean", formatLeanAngle(stats.currentLeanAngleDeg))
                StatItem("Max lean", formatLeanAngle(stats.maxLeanAngleDeg))
            }
            if (stats.mode.tracksGForce) {
                StatItem("Max G", formatGForce(stats.maxGForce))
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

private fun navigateRoundTrip(context: Context, origin: LatLon, waypoints: List<LatLon>) {
    // Directions URL: origin = destination = start, curvy roads as via points.
    // Google Maps supports up to 9 waypoints in this form.
    val wp = waypoints.joinToString("|") { "${it.lat},${it.lon}" }
    val uri = Uri.parse(
        "https://www.google.com/maps/dir/?api=1" +
            "&origin=${origin.lat},${origin.lon}" +
            "&destination=${origin.lat},${origin.lon}" +
            "&travelmode=driving" +
            "&waypoints=" + Uri.encode(wp)
    )
    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
}

private fun navigateGoogleMaps(context: Context, dest: LatLon, mode: TravelMode) {
    val uri = Uri.parse("google.navigation:q=${dest.lat},${dest.lon}&mode=${mode.gmapsMode}")
    val intent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        navigateGeo(context, dest)
    }
}

private fun navigateWaze(context: Context, dest: LatLon) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("waze://?ll=${dest.lat},${dest.lon}&navigate=yes"))
        )
    } catch (e: ActivityNotFoundException) {
        // Waze not installed: universal link opens install page or web.
        context.startActivity(
            Intent(Intent.ACTION_VIEW,
                Uri.parse("https://waze.com/ul?ll=${dest.lat},${dest.lon}&navigate=yes"))
        )
    }
}

private fun navigateGeo(context: Context, dest: LatLon) {
    context.startActivity(
        Intent(Intent.ACTION_VIEW,
            Uri.parse("geo:${dest.lat},${dest.lon}?q=${dest.lat},${dest.lon}"))
    )
}

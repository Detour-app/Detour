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
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.maproulette.R
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.jellemax.maproulette.data.Account
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
import com.jellemax.maproulette.data.RoundTripPlanner
import com.jellemax.maproulette.data.RouteResult
import com.jellemax.maproulette.data.RoutingServer
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

private val TravelMode.icon: ImageVector
    get() = when (this) {
        TravelMode.WALK -> Icons.AutoMirrored.Filled.DirectionsWalk
        TravelMode.BIKE -> Icons.AutoMirrored.Filled.DirectionsBike
        TravelMode.MOTO -> Icons.Default.TwoWheeler
        TravelMode.CAR -> Icons.Default.DirectionsCar
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

/** Straight-line span of a section, its longest device-to-device distance —
 *  the yardstick for deciding we've overshot the far end. */
private fun sectionLengthMeters(devices: List<LatLon>): Double {
    var max = 0.0
    for (i in devices.indices) for (j in i + 1 until devices.size) {
        val d = RoadRoulette.distanceMeters(devices[i], devices[j])
        if (d > max) max = d
    }
    return max
}

/** One color per spin candidate, so the pin on the map and the row in the card
 *  are recognizably the same place. Kept clear of the blue radius circle, the
 *  orange direction wedge and the pink route line. */
private val CANDIDATE_COLORS = listOf(0xFF7E57C2, 0xFF00897B, 0xFFF4511E)
    .map { it.toInt() }

/** One spin result awaiting a pick; [route] is null when the routing server
 *  couldn't be reached — the card then shows straight-line distance only. */
private data class RouteCandidate(
    val destination: LatLon,
    val name: String?,
    val route: RouteResult?,
    val straightLineMeters: Double,
)

/** Picks one destination candidate and eagerly routes to it, so the card list
 *  can show real road distance/ETA instead of a straight line. */
private suspend fun pickCandidate(
    config: ServerConfig,
    loc: LatLon,
    radiusMeters: Double,
    minRadiusMeters: Double,
    mode: TravelMode,
    poiKind: PoiKind,
    bearing: Double?,
    explored: ExploredArea,
): RouteCandidate {
    val (dest, name) = if (poiKind != PoiKind.ROAD) {
        val poi = PoiRoulette.randomPoi(loc, radiusMeters, poiKind, bearing, explored, minRadiusMeters)
        poi.location to poi.name
    } else {
        // Own server snaps a random point to a road reachable in this mode's
        // profile; Overpass fallback below.
        val server = if (config.usable) {
            try {
                RoutingServer.randomRoadDestination(
                    config, loc, radiusMeters, bearing, explored, mode.ghProfile, minRadiusMeters)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        } else null
        val d = server ?: RoadRoulette.randomRoadPoint(
            loc, radiusMeters, mode.highwayRegex, bearing, explored, minRadiusMeters)
        d to null
    }
    val route = try {
        RoutingServer.route(config, loc, dest, mode.ghProfile,
            Settings.avoidHighways.value, Settings.avoidSmallRoads.value)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    return RouteCandidate(
        destination = dest,
        name = name,
        route = route,
        straightLineMeters = RoadRoulette.distanceMeters(loc, dest),
    )
}

@Composable
fun MapScreen(
    onOpenHistory: () -> Unit,
    onOpenBadges: () -> Unit,
    onOpenFriends: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSavedPlaces: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { SavedPlaces.ensureLoaded(context) }
    val savedPlaces by SavedPlaces.places.collectAsStateWithLifecycle()
    // Non-null while a name is being entered for the current dropped/destination pin.
    var savePinTarget by remember { mutableStateOf<LatLon?>(null) }

    // Persisted, because the tracking service reads it too: an auto-detected
    // trip has no other way to know whether it is a ride or a drive.
    val mode by Settings.tripMode.collectAsStateWithLifecycle()
    var radiusKm by rememberSaveable { mutableFloatStateOf(Settings.tripMode.value.defaultKm) }
    var minRadiusKm by rememberSaveable { mutableFloatStateOf(0f) }
    var candidates by remember { mutableStateOf<List<RouteCandidate>>(emptyList()) }
    var myLocation by remember { mutableStateOf<LatLon?>(null) }
    var destination by remember { mutableStateOf<LatLon?>(null) }
    var route by remember { mutableStateOf<RouteResult?>(null) }
    var spinning by remember { mutableStateOf(false) }
    var spinJob by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val serverConfig = remember { RoutingServer.load(context) }
    var poiKind by rememberSaveable { mutableStateOf(PoiKind.ROAD) }
    var directionDeg by rememberSaveable { mutableStateOf<Float?>(null) }
    var destinationName by remember { mutableStateOf<String?>(null) }
    val fogEnabled by Settings.fogEnabled.collectAsStateWithLifecycle()
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
    var settingsCollapsed by rememberSaveable { mutableStateOf(false) }
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
        if (SyncClient.configured && Account.signedIn) {
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
                Lifecycle.Event.ON_STOP -> TripTrackingService.setUiVisible(context, false)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            TripTrackingService.setUiVisible(context, false)
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
        mapLibreMap?.let { cameraForPoints(it, listOf(loc, c.destination), FIT_PADDING_PX) }
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

    // Fog-of-war: keep the overlay view fed with the current traces, then redraw.
    LaunchedEffect(fogEnabled, fogRadius, traces, friendTraces, liveTrace, myLocation) {
        val mine = if (liveTrace.size >= 2) traces + listOf(liveTrace) else traces
        fogView.active = fogEnabled
        fogView.traces = mine + friendTraces
        fogView.currentLocation = myLocation
        fogView.corridorMeters = fogRadius
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

    // Average speed through a trajectcontrole. Enter near a section's device
    // node, then integrate GPS distance over elapsed time until we reach the
    // far device (or overshoot / time out). The average is what the section
    // actually measures, so it's the number worth seeing while inside one.
    val speedSectionsRef = rememberUpdatedState(speedSections)
    LaunchedEffect(Unit) {
        var active: SpeedCameras.Section? = null
        var entryMs = 0L
        var accMeters = 0.0
        var last: LatLon? = null
        TripTrackingService.lastFix.collect { fix ->
            fix ?: return@collect
            val pos = LatLon(fix.lat, fix.lon)
            val now = System.currentTimeMillis()
            val current = active
            if (current == null) {
                val entered = speedSectionsRef.value.firstOrNull { s ->
                    s.devices.any { RoadRoulette.distanceMeters(pos, it) < SECTION_GATE_METERS }
                }
                if (entered != null && fix.speedMps > 2.0) {
                    active = entered
                    entryMs = now
                    accMeters = 0.0
                    last = pos
                    sectionAvgKmh = null
                    sectionLimitKmh = entered.maxspeedKmh
                }
            } else {
                last?.let { accMeters += RoadRoulette.distanceMeters(it, pos) }
                last = pos
                val elapsedHours = (now - entryMs) / 3_600_000.0
                if (elapsedHours > 0 && accMeters > 20.0) {
                    sectionAvgKmh = (accMeters / 1000.0) / elapsedHours
                }
                // Sections can have intermediate device nodes; only treat passing
                // one as the end once we've covered most of the span, so an early
                // point doesn't stop the measurement short.
                val length = sectionLengthMeters(current.devices)
                val reachedEnd = accMeters > maxOf(150.0, length * 0.8) &&
                    current.devices.any { RoadRoulette.distanceMeters(pos, it) < SECTION_GATE_METERS }
                val overshot = accMeters > length * 1.4 + 400.0
                val timedOut = now - entryMs > 30 * 60_000L
                if (reachedEnd || overshot || timedOut) {
                    active = null
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
                    mapLibreMap?.let { cameraForPoints(it, result.polyline + loc, FIT_PADDING_PX) }
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
                    mapLibreMap?.let {
                        cameraForPoints(it, results.map { c -> c.destination } + loc, FIT_PADDING_PX)
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
        bottomBar = { if (!navigating) ModeBar(mode, ::selectMode) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { scaffoldPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = scaffoldPadding.calculateBottomPadding()),
        ) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

            if (navigating) {
                NavigationBanner(
                    progress = navProgress,
                    rerouting = rerouting,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(12.dp),
                )
            } else {
                MapToolbar(
                    followMe = following,
                    fogEnabled = fogEnabled,
                    onToggleFollow = {
                        if (following) followMe = false
                        else { followMe = true; camSuspended = false }
                    },
                    onSearch = { searchOpen = true },
                    onToggleFog = { Settings.setFogEnabled(!fogEnabled) },
                    onOpenHistory = onOpenHistory,
                    onOpenBadges = onOpenBadges,
                    onOpenFriends = onOpenFriends,
                    onOpenSettings = onOpenSettings,
                    onOpenSavedPlaces = onOpenSavedPlaces,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(12.dp),
                )
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
                    if (stats != null) {
                        EndTripButton(onClick = { TripTrackingService.stop(context) })
                    } else {
                        Spacer(Modifier.width(1.dp))
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

                stats?.let { ActiveTripCard(it) }

                // Shortcut chips: one-tap a saved place to set it as destination,
                // or save the pin you just dropped. Hidden while navigating.
                if (!navigating && (savedPlaces.isNotEmpty() || destination != null)) {
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

                if (navigating) NavigationBottomBar(
                    progress = navProgress,
                    offRoute = (navProgress?.offRouteMeters ?: 0.0) > 60,
                    onExit = { stopNavigation() },
                ) else if (candidates.isNotEmpty()) CandidatesCard(
                    candidates = candidates,
                    onPick = ::choose,
                    onReroll = { candidates = emptyList(); spin() },
                    onCancel = { candidates = emptyList() },
                ) else if (settingsCollapsed) CollapsedSpinBar(
                    mode = mode,
                    radiusKm = radiusKm,
                    spinning = spinning,
                    onSpin = { if (spinning) spinJob?.cancel() else spin() },
                    onExpand = { settingsCollapsed = false },
                ) else Card(
                    modifier = Modifier.glassBorder(MaterialTheme.shapes.extraLarge),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = glassCardColors(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (mode.roundTrip) "Loop" else "Destination",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            IconButton(
                                onClick = { settingsCollapsed = true },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(Icons.Default.ExpandMore, contentDescription = "Minimize")
                            }
                        }

                        error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
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
                            Text(
                                "Loop found: ${formatDistanceKm(it)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        destinationName?.let {
                            Text("→ $it", style = MaterialTheme.typography.bodySmall)
                        }
                        Slider(
                            value = radiusKm,
                            onValueChange = { radiusKm = it },
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
                                onValueChange = { minRadiusKm = it },
                                valueRange = 0f..radiusKm,
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!mode.roundTrip) {
                                SelectorChip(
                                    icon = Icons.Default.Place,
                                    label = poiKind.label,
                                    options = PoiKind.entries.map { it.label },
                                    onSelect = { poiKind = PoiKind.entries[it] },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            SelectorChip(
                                icon = Icons.Default.Explore,
                                label = directionDeg?.let { DIRECTION_NAMES[(it / 45f).toInt()] }
                                    ?: "Any direction",
                                options = listOf("Any direction") + DIRECTION_NAMES,
                                onSelect = { i ->
                                    directionDeg = if (i == 0) null else (i - 1) * 45f
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        Button(
                            onClick = { if (spinning) spinJob?.cancel() else spin() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                        ) {
                            if (spinning) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Casino, contentDescription = null,
                                    Modifier.size(20.dp))
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
                                origin = myLocation,
                                mode = mode,
                                inAppAvailable = serverConfig.usable &&
                                    (destination != null ||
                                        route?.instructions?.isNotEmpty() == true),
                                onNavigateInApp = { startNavigation() },
                                onNavigate = {
                                    // Heading out = start tracking automatically.
                                    if (stats == null) {
                                        TripTrackingService.start(
                                            context, destination?.lat, destination?.lon)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            if (stats == null) {
                                OutlinedButton(
                                    onClick = {
                                        TripTrackingService.start(
                                            context, destination?.lat, destination?.lon)
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null,
                                        Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Track ${mode.label.lowercase()}", maxLines = 1)
                                }
                            }
                        }
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search address or place") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searching) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear")
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
                                if (r.name in recentNames) Icons.Default.History else Icons.Default.Place,
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

/** Compact dropdown selector for destination kind and direction. */
@Composable
private fun SelectorChip(
    icon: ImageVector,
    label: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(icon, contentDescription = null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        open = false
                        onSelect(i)
                    },
                )
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
                            .size(14.dp)
                            .background(
                                Color(CANDIDATE_COLORS[index % CANDIDATE_COLORS.size]),
                                CircleShape,
                            )
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            c.name ?: "Option ${index + 1}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        val distanceMeters = c.route?.distanceMeters ?: c.straightLineMeters
                        val distanceLabel = (if (c.route?.distanceMeters == null) "~" else "") +
                            formatDistanceKm(distanceMeters)
                        val etaLabel = c.route?.timeMs?.let { " · %.0f min".format(it / 60_000.0) } ?: ""
                        Text(
                            distanceLabel + etaLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.Default.Navigation, contentDescription = "Choose this destination")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                Button(onClick = onReroll, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Casino, contentDescription = null, Modifier.size(18.dp))
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

/** Map controls, top-right. Only the three you reach for while moving are
 *  buttons; the screens you open while parked are behind the overflow, which is
 *  why this column no longer runs down into whatever the bottom card is showing. */
@Composable
private fun MapToolbar(
    followMe: Boolean,
    fogEnabled: Boolean,
    onToggleFollow: () -> Unit,
    onSearch: () -> Unit,
    onToggleFog: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenBadges: () -> Unit,
    onOpenFriends: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSavedPlaces: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val glassColor = glassContainerColor()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallFloatingActionButton(
            onClick = onToggleFollow,
            containerColor = glassColor,
        ) {
            Icon(
                if (followMe) Icons.Default.MyLocation else Icons.Default.LocationSearching,
                contentDescription = if (followMe) "Stop following my location"
                    else "Follow my location",
            )
        }
        SmallFloatingActionButton(
            onClick = onSearch,
            containerColor = glassColor,
        ) {
            Icon(Icons.Default.Search, contentDescription = "Search destination")
        }
        SmallFloatingActionButton(
            onClick = onToggleFog,
            containerColor = glassColor,
        ) {
            Icon(
                if (fogEnabled) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                contentDescription = "Fog of war",
            )
        }
        Box {
            SmallFloatingActionButton(
                onClick = { menuOpen = true },
                containerColor = glassColor,
            ) {
                Icon(Icons.Default.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Saved places") },
                    leadingIcon = { Icon(Icons.Default.Place, contentDescription = null) },
                    onClick = { menuOpen = false; onOpenSavedPlaces() },
                )
                DropdownMenuItem(
                    text = { Text("Trip history") },
                    leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                    onClick = { menuOpen = false; onOpenHistory() },
                )
                DropdownMenuItem(
                    text = { Text("Badges") },
                    leadingIcon = { Icon(Icons.Default.EmojiEvents, contentDescription = null) },
                    onClick = { menuOpen = false; onOpenBadges() },
                )
                DropdownMenuItem(
                    text = { Text("Friends") },
                    leadingIcon = { Icon(Icons.Default.People, contentDescription = null) },
                    onClick = { menuOpen = false; onOpenFriends() },
                )
                DropdownMenuItem(
                    text = { Text("Settings") },
                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    onClick = { menuOpen = false; onOpenSettings() },
                )
            }
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
        Icon(Icons.Default.Stop, contentDescription = null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("End trip", maxLines = 1, fontWeight = FontWeight.Bold)
    }
}

/** Slim stand-in for the spin card while just cruising. Spin stays reachable
 *  here — collapsing the card used to mean you couldn't spin without expanding it. */
@Composable
private fun CollapsedSpinBar(
    mode: TravelMode,
    radiusKm: Float,
    spinning: Boolean,
    onSpin: () -> Unit,
    onExpand: () -> Unit,
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
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .clickable { onExpand() },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(mode.icon, contentDescription = null)
                Text(
                    "${if (mode.maxKm <= 10f) "%.1f".format(radiusKm) else radiusKm.toInt()} km",
                    style = MaterialTheme.typography.labelLarge,
                )
                Icon(Icons.Default.ExpandLess, contentDescription = "Expand")
            }
            Button(onClick = onSpin, shape = CircleShape) {
                if (spinning) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Casino, contentDescription = null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(if (spinning) "Cancel" else "Spin", maxLines = 1)
            }
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
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier) {
        FilledTonalButton(
            onClick = { menuOpen = true },
            enabled = destination != null || (route != null && origin != null),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Navigation, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Go", maxLines = 1)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (inAppAvailable) {
                DropdownMenuItem(
                    text = { Text("Navigate in app") },
                    onClick = {
                        menuOpen = false
                        onNavigateInApp()
                    },
                )
            }
            if (route != null && origin != null) {
                // Waze can't take multi-waypoint routes; Google Maps only.
                DropdownMenuItem(
                    text = { Text("Google Maps (round trip)") },
                    onClick = {
                        menuOpen = false
                        onNavigate()
                        navigateRoundTrip(context, origin, route)
                    },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Google Maps") },
                    onClick = {
                        menuOpen = false
                        onNavigate()
                        destination?.let { navigateGoogleMaps(context, it, mode) }
                    },
                )
                DropdownMenuItem(
                    text = { Text("Waze") },
                    onClick = {
                        menuOpen = false
                        onNavigate()
                        destination?.let { navigateWaze(context, it) }
                    },
                )
                DropdownMenuItem(
                    text = { Text("Other app") },
                    onClick = {
                        menuOpen = false
                        onNavigate()
                        destination?.let { navigateGeo(context, it) }
                    },
                )
            }
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
        Text(label, style = MaterialTheme.typography.labelSmall)
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

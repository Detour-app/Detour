package com.jellemax.detour.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.RectF
import android.media.AudioManager
import android.media.ToneGenerator
import java.io.IOException
import android.os.Build
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.jellemax.detour.audio.NavVoice
import com.jellemax.detour.audio.PushToTalk
import com.jellemax.detour.data.Features
import com.jellemax.detour.net.ConvoyLiveClient
import com.jellemax.detour.data.Account
import com.jellemax.detour.data.CircleFixes
import com.jellemax.detour.data.ExploredArea
import com.jellemax.detour.data.FriendFog
import com.jellemax.detour.data.Groups
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.MemberFix
import com.jellemax.detour.data.NavAnnouncer
import com.jellemax.detour.data.NavEngine
import com.jellemax.detour.data.PoiKind
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.data.Curviness
import com.jellemax.detour.data.RouteCandidate
import com.jellemax.detour.data.RoundTripPlanner
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.data.RoutingServer
import com.jellemax.detour.data.pickCandidate
import com.jellemax.detour.data.SavedPlaces
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.SpeedCameras
import com.jellemax.detour.data.SyncClient
import com.jellemax.detour.data.TraceStore
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.drive.CameraWarner
import com.jellemax.detour.drive.SectionAverageTracker
import com.jellemax.detour.map.FollowCamera
import com.jellemax.detour.map.NavPolicy
import com.jellemax.detour.map.leadingSpinIndex
import com.jellemax.detour.tracking.TripTrackingService
import com.jellemax.detour.ble.BleNavServer
import com.jellemax.detour.wear.NavRelay
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
    LaunchedEffect(Unit) { SavedPlaces.ensureLoaded() }
    val savedPlaces by SavedPlaces.places.collectAsStateWithLifecycle()
    // Non-null while a name is being entered for the current dropped/destination pin.
    var savePinTarget by remember { mutableStateOf<LatLon?>(null) }

    // Play policy requires our own disclosure of what background location is
    // for, shown and accepted before the system prompt may be raised.
    var showBgLocationDisclosure by remember { mutableStateOf(false) }

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
    // `error` has a dozen writers and, until now, one reader — inside SpinSheet,
    // which is collapsed by default. A denied location permission therefore
    // reported itself to nobody. The snackbar shows it whatever the bottom card
    // is doing; the sheet keeps its own copy for when it is open.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        error?.let { snackbarHostState.showSnackbar(it) }
    }
    val serverConfig = remember { RoutingServer.load() }
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
    // The map layers panel. Lives here rather than in MapTopChrome so the map's
    // own click listeners can close it — see where they clear it below.
    var layersOpen by remember { mutableStateOf(false) }
    // Stored traces reload on every store write; the live trace and fix come
    // straight from the tracking service, so fog and position update in real
    // time instead of only when a trip is saved.
    val storeVersion by TraceStore.version.collectAsStateWithLifecycle()
    val traces = remember(storeVersion) { TraceStore.loadAll() }
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
    // Moved up from the marker-drawing section below: the group-spin commit
    // rule (see commitSpinCandidate) also needs to know who's currently
    // live, not just the map overlay.
    val convoyPeers by ConvoyLiveClient.peers.collectAsStateWithLifecycle()
    val spinOffer by ConvoyLiveClient.spinOffer.collectAsStateWithLifecycle()
    val spinVotes by ConvoyLiveClient.spinVotes.collectAsStateWithLifecycle()
    // ConvoyLiveClient only knows the id it's connected to; resolve it to a
    // name for display by asking the same list FriendsScreen uses.
    var convoyName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activeConvoyId) {
        val id = activeConvoyId
        convoyName = if (id == null) null else withContext(Dispatchers.IO) {
            try {
                Groups.list("convoy").find { it.id == id }?.name
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
    // Out here rather than inside the effect that uses it, for the same reason
    // speedLimitFetchMs is: that effect is keyed on `navigating` and restarts,
    // and a holder that restarted with it would forget an in-flight fetch — so
    // the guard would wave a second one through on the very next fix after a
    // navigation toggle. The fetch itself runs on `scope`, which outlives the
    // restart, so the two have to agree about what is running.
    var speedLimitFetchJob by remember { mutableStateOf<Job?>(null) }
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
    val mapIcon by Settings.mapIcon.collectAsStateWithLifecycle()
    val routeColor by Settings.routeColor.collectAsStateWithLifecycle()
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
        if (SyncClient.configured() && Account.signedIn) {
            withContext(Dispatchers.IO) {
                try {
                    SyncClient.sync()
                } catch (e: Exception) {
                    // offline, server down, or signed out; next launch catches up
                }
            }
        }
    }

    // Re-fetch when sharing is switched on, and drop what we hold the moment it
    // is switched off — a stale union would keep revealing a friend's roads.
    LaunchedEffect(shareFog) {
        if (shareFog) withContext(Dispatchers.IO) { FriendFog.refresh() }
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

    // Driving off takes the camera back; the rule is FollowCamera's. The keys are
    // derived booleans on purpose - keying on the collections themselves would
    // restart this collector on every convoy vote.
    LaunchedEffect(camSuspended, spinning, candidates.isEmpty(), spinOffer == null) {
        if (!FollowCamera.shouldWatch(
                camSuspended = camSuspended,
                spinning = spinning,
                hasCandidates = candidates.isNotEmpty(),
                hasSpinOffer = spinOffer != null,
            )
        ) {
            return@LaunchedEffect
        }
        TripTrackingService.lastFix.collect { fix ->
            fix ?: return@collect
            if (FollowCamera.shouldResume(
                    speedMps = fix.speedMps,
                    nowMs = System.currentTimeMillis(),
                    lastGestureMs = lastGestureMs,
                )
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
    LaunchedEffect(convoyConnected, activeConvoyId) {
        // activeConvoyId != null, not just convoyConnected: the same socket
        // now also stays connected for a circle's arrival notifications with
        // no convoy joined at all (see ConvoyLiveClient.setNotifyCircles),
        // which needs no microphone.
        if (convoyConnected && activeConvoyId != null &&
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
            showBgLocationDisclosure = true
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

    // What's actually shown on the map/card: my own spin's candidates,
    // unless a convoy spin is on the table, in which case everyone - the
    // sharer included, see ConvoyLiveClient.sendSpinOffer - shows the same
    // three from the offer instead. Keeps map pins and votes pointed at
    // the same coordinates on every device even when they came from a
    // spin nobody on this phone actually rolled.
    val displayCandidates = spinOffer?.asRouteCandidates() ?: candidates

    /** Commits a convoy spin's leading (or explicitly chosen) candidate,
     *  same as [choose] but sourced from [spinOffer] and clearing it after -
     *  see ConvoyLiveClient's class doc for why that's purely local. */
    fun commitSpinCandidate(index: Int) {
        val offer = spinOffer ?: return
        val c = offer.candidates.getOrNull(index) ?: return
        destination = LatLon(c.lat, c.lon)
        destinationName = c.name
        route = null // startNavigation() fetches a real route once tapped, same as a dropped pin
        candidates = emptyList()
        ConvoyLiveClient.clearSpinOffer()
        val loc = myLocation ?: return
        camSuspended = true
        lastGestureMs = System.currentTimeMillis()
        mapLibreMap?.let { cameraForPoints(it, listOf(loc, LatLon(c.lat, c.lon)), FIT_PADDING_PX, fitBottomPaddingPx) }
    }

    // How a vote round ends: the rule and its correctness argument are
    // resolveSpinRound in map/GroupSpinRules.kt. Not wired to it yet -
    // verifying the convoy path needs two devices transmitting to each other.
    LaunchedEffect(spinOffer, spinVotes, convoyPeers, accountUsername) {
        val offer = spinOffer ?: return@LaunchedEffect
        if (offer.candidates.size == 1) {
            commitSpinCandidate(0)
            return@LaunchedEffect
        }
        if (!offer.fromMe) return@LaunchedEffect
        val expected = convoyPeers.keys + setOfNotNull(accountUsername.takeIf { it.isNotBlank() })
        if (expected.isNotEmpty() && spinVotes.keys.containsAll(expected)) {
            ConvoyLiveClient.sendSpinOffer(
                listOf(offer.candidates[leadingSpinIndex(spinVotes, offer.candidates.size)]))
        }
    }

    // Push overlay state to the map whenever anything drawable changes. The
    // layers are created once per style; here we only swap their GeoJSON data.
    LaunchedEffect(mapOverlays, myLocation, destination, route, radiusKm, mode,
        directionDeg, navigating, displayCandidates) {
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
            candidates = displayCandidates.mapIndexed { i, c ->
                CandidatePin(c.destination, CANDIDATE_COLORS[i % CANDIDATE_COLORS.size])
            },
            // Marker updates per fix (~1 Hz); the eased camera glides the map
            // under it, so it stays smooth without a per-frame source rewrite.
            showPosition = true,
            // Same bearing the camera is easing towards, which is already held
            // through a stop rather than following the noise below 2 m/s.
            positionBearingDeg = camTargetBearing?.toDouble(),
        )
    }

    // Swapping the vehicle icon only replaces one style image, so it can be its
    // own effect rather than a key on the render above — which would re-push
    // every overlay source to change one bitmap.
    LaunchedEffect(mapOverlays, mapIcon) {
        mapOverlays?.setPositionIcon(mapIcon)
    }

    // Same reasoning for the route colour: two paint properties, no reason to
    // re-serialise the line to change them.
    LaunchedEffect(mapOverlays, routeColor) {
        mapOverlays?.setRouteColor(routeColor)
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

    // Long-press drops a destination pin; a tap on a candidate dot commits to it
    // (or, mid convoy-vote, casts a vote instead - see spinOfferRef below).
    // Registered once the map is ready; the listeners read live state via refs.
    val candidatesRef = rememberUpdatedState(displayCandidates)
    val spinOfferRef = rememberUpdatedState(spinOffer)
    val navigatingRef = rememberUpdatedState(navigating)
    LaunchedEffect(mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        // The fog is screen-space, projected through the map — redraw it on every
        // camera change so a manual pan/pinch keeps it glued to the map, not just
        // while the follow loop is running.
        map.addOnCameraMoveListener { fogView.invalidate() }
        map.addOnCameraIdleListener { fogView.invalidate() }
        // Touching the map dismisses the layers panel, which is what the Popup's
        // dismissOnClickOutside used to do before the panel moved inline.
        map.addOnMapLongClickListener { ll ->
            layersOpen = false
            if (navigatingRef.value) return@addOnMapLongClickListener false
            destination = LatLon(ll.latitude, ll.longitude)
            destinationName = "Dropped pin"
            route = null
            true
        }
        map.addOnMapClickListener { ll ->
            layersOpen = false
            val p = map.projection.toScreenLocation(ll)
            val tap = RectF(p.x - 22f, p.y - 22f, p.x + 22f, p.y + 22f)
            val idx = map.queryRenderedFeatures(tap, LAYER_CANDIDATES)
                .firstOrNull()?.getNumberProperty("index")?.toInt()
            val cs = candidatesRef.value
            if (idx == null || idx >= cs.size) return@addOnMapClickListener false
            if (spinOfferRef.value != null) ConvoyLiveClient.sendSpinVote(idx) else choose(cs[idx])
            true
        }
    }

    // ---- spoken guidance ---------------------------------------------------
    //
    // The phone was the only navigating surface with no voice: the head unit and
    // iOS have spoken turns since they shipped, while Settings.voiceGuidance had
    // three consumers and two voices. Register decision 1, full parity.
    //
    // Declared up here rather than beside the nav loop because four call sites
    // below need it — stopNavigation, startNavigation, the camera collector and
    // the nav loop — and Kotlin resolves local declarations in order.
    val navVoice = remember { NavVoice(context) }
    DisposableEffect(Unit) {
        onDispose {
            // Not stop(): the engine connection and any held focus request
            // outlive the composition otherwise. The car does the same in its
            // onDestroy (car/NavScreen.kt:199-202).
            navVoice.shutdown()
        }
    }
    val announcer = remember { NavAnnouncer() }

    // Muting has to cut the sentence already in flight, which is what the car's
    // speaker button does (car/NavScreen.kt:479-480). A raw collect and not
    // collectAsStateWithLifecycle: a mute has to land while the app is in the
    // background, which is exactly where the lifecycle-aware copy stops
    // updating.
    LaunchedEffect(Unit) {
        Settings.voiceGuidance.collect { on -> if (!on) navVoice.stop() }
    }

    fun announceAloud(text: String) {
        // Read off the StateFlows rather than the composed state: the camera
        // warning's collector runs while the app is backgrounded, and the
        // composed copies do not update there.
        if (!Settings.voiceGuidance.value) return
        // A live convoy owns the output. ConvoyLiveService takes
        // AUDIOFOCUS_GAIN_TRANSIENT for the whole convoy and registers no
        // focus-change listener (convoy/ConvoyLiveService.kt:172-183), and puts
        // the device into MODE_IN_COMMUNICATION routed to the speaker (:129,
        // :149-161) — so a guidance prompt would not duck anything, it would
        // talk over the riders you are talking to, through a route nobody has
        // measured. activeConvoyId is the closest observable to "the service is
        // running"; FriendsScreen.kt:681 records that the two are not exactly
        // the same thing.
        if (ConvoyLiveClient.activeConvoyId.value != null) return
        navVoice.speak(text)
    }

    fun stopNavigation() {
        navigating = false
        navProgress = null
        // Arrival, or the Exit button. Either way stop mid-sentence rather than
        // finishing a prompt for a turn that no longer matters.
        navVoice.stop()
        camTargetBearing = null
        // The line stays on the map after arrival (and after a stop); without
        // this it would keep the driven part greyed out with nothing following
        // it any more.
        mapOverlays?.setDrivenFraction(null)
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
        // A fresh session hears its first turn immediately, whatever the
        // distance — the same rule the car has, and the reason it exists is
        // that silence after pressing Start is indistinguishable from a broken
        // voice.
        announcer.routeChanged()
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
        // Crossing into or out of navigation invalidates whatever sign we hold:
        // the collector below is the only writer and it doesn't run while
        // navigating, so the value would otherwise be the limit from wherever
        // the route began and would survive the whole session — and then the
        // trip after it. Stale in both directions: the camera chime falls back
        // to it while navigating, and the HUD switches back to it on the way
        // out. Clear it and let the next snap re-establish it, the way the car
        // has since it shipped (car/SpinScreen.kt:117-121). The misses counter
        // goes with it, or the first miss after the switch would clear a sign
        // that was already cleared.
        ambientSpeedLimitKmh = null
        speedLimitMisses = 0
        if (navigating) return@LaunchedEffect
        TripTrackingService.lastFix.collect { fix ->
            fix ?: return@collect
            if (fix.speedMps < 2.0) return@collect
            val pos = LatLon(fix.lat, fix.lon)
            val fromCenter = speedLimitWaysCenter?.let { RoadRoulette.distanceMeters(it, pos) }
                ?: Double.MAX_VALUE
            val now = System.currentTimeMillis()
            if (fromCenter > RoadRoulette.SPEED_PREFETCH_RADIUS_M - 500.0 &&
                now - speedLimitFetchMs > 10_000 &&
                speedLimitFetchJob?.isActive != true
            ) {
                // The refresh runs in its own coroutine. lastFix is a StateFlow
                // and this collector is sequential, so awaiting a mirror *here*
                // suspended the collector — and every fix that landed meanwhile
                // was conflated away, so the snap below, the miss counter and
                // the sign all stopped tracking the road for as long as Overpass
                // took. A mirror having a slow ten seconds is normal; a posted
                // limit that stops following the road for ten seconds is not.
                // The isActive guard is what now stops two fetches overlapping,
                // which is the job the inline await used to do by accident.
                // Same fix as car/SpinScreen.kt:265-287.
                speedLimitFetchMs = now
                speedLimitFetchJob = scope.launch {
                    // runCatching because this no longer runs inside the
                    // collector: an exception escaping here would cancel
                    // `scope`, i.e. every coroutine this screen owns, where
                    // inline it only killed this one collector. speedLimitWays
                    // swallows IOException but not the SerializationException a
                    // busy Overpass's HTML error page produces — the hazard
                    // SpeedCameras.near:65-79 documents and catches.
                    val ways = runCatching {
                        withContext(Dispatchers.IO) { RoadRoulette.speedLimitWays(pos) }
                    }.getOrDefault(emptyList())
                    if (ways.isNotEmpty()) {
                        speedLimitWays = ways
                        speedLimitWaysCenter = pos
                    }
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
        // Coroutine-local, unlike the ambient limit's holder up in the body:
        // this effect is keyed on Unit and never restarts, so a local has
        // nothing to lose. Keeping it here is what says so.
        var fetchJob: Job? = null
        TripTrackingService.lastFix.collect { fix ->
            fix ?: return@collect
            val pos = LatLon(fix.lat, fix.lon)
            val fromCenter = center?.let { RoadRoulette.distanceMeters(it, pos) }
                ?: Double.MAX_VALUE
            val now = System.currentTimeMillis()
            if (fromCenter > SpeedCameras.PREFETCH_RADIUS_M - 1000.0 &&
                now - lastFetchMs > 15_000 &&
                fetchJob?.isActive != true
            ) {
                // Own coroutine, isActive guard, runCatching: same reasoning as
                // the ambient limit above, and as car/NavScreen.kt:348-379,
                // which is where this was diagnosed. This collector feeds the
                // section machine, so suspending it also stalled the running
                // average's own fix stream.
                lastFetchMs = now
                fetchJob = scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) { SpeedCameras.near(pos) }
                    }.getOrNull()
                    if (result != null) {
                        speedCameras = result.cameras
                        speedSections = result.sections
                        center = pos
                    }
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
    // same reasoning as the camera markers above. (convoyPeers itself is
    // collected further up, alongside the other convoy state.)
    LaunchedEffect(mapOverlays, convoyPeers) {
        mapOverlays?.setFriends(convoyPeers.values)
    }

    // Circle member markers: every circle you're in, always — not just
    // whichever one CirclesScreen last had open. A circle is the always-on
    // relationship (docs/CIRCLES_AND_CONVOYS.md section 2); making the map go
    // blank until you walk into another screen and pick one defeats the point
    // of it, and the selection lived in memory, so every app restart lost it.
    // Polled rather than socketed: a circle fix only changes once a minute or
    // so server-side, so polling faster would just repeat the same row.
    var circleFixes by remember { mutableStateOf<List<MemberFix>>(emptyList()) }
    LaunchedEffect(accountUsername) {
        if (accountUsername.isBlank()) {
            circleFixes = emptyList()  // signed out: nothing to ask the server for
            return@LaunchedEffect
        }
        while (true) {
            circleFixes = try {
                withContext(Dispatchers.IO) { CircleFixes.othersFixes(accountUsername) }
            } catch (e: Exception) {
                circleFixes // offline or server down; keep the last known positions
            }
            delay(CIRCLE_FIX_POLL_MS)
        }
    }
    LaunchedEffect(mapOverlays, circleFixes) {
        mapOverlays?.setCircleMembers(circleFixes)
    }

    // The fog scrim is a sibling View over the GL surface, so it covers the
    // member and peer symbol layers too. Clear it around them, or the markers
    // the map just drew stay invisible on any ground you haven't driven —
    // which is most of where a circle member actually is.
    LaunchedEffect(circleFixes, convoyPeers) {
        fogView.peers = circleFixes.map { LatLon(it.lat, it.lon) } +
            convoyPeers.values.map { LatLon(it.lat, it.lon) }
        fogView.invalidate()
    }

    // Chime when a camera lies ahead, close, and we're over the posted limit —
    // the one case worth interrupting for. The rule, the one-chime-per-camera
    // latch and the wording are CameraWarner's (shared/…/drive/), where they live
    // with their tests; what to do about a warning is ours.
    val speedCamerasRef = rememberUpdatedState(speedCameras)
    val ambientLimitRef = rememberUpdatedState(ambientSpeedLimitKmh)
    val navProgressRef = rememberUpdatedState(navProgress)
    val toneGen = remember {
        runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull()
    }
    DisposableEffect(Unit) { onDispose { toneGen?.release() } }
    LaunchedEffect(Unit) {
        var warnerState = CameraWarner.State()
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
            when (val outcome = step.outcome) {
                is CameraWarner.Outcome.Warn -> {
                    toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
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
    val speedSectionsRef = rememberUpdatedState(speedSections)
    LaunchedEffect(Unit) {
        var st = SectionAverageTracker.State()
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
            // Two states, one assignment source: they can no longer disagree
            // across a recomposition. Collapsing them into one is stage 4's.
            sectionAvgKmh = st.reading.averageKmh
            sectionLimitKmh = st.reading.limitKmh
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
        // Fade out the road already behind you. Cheap when it changes nothing —
        // the overlay drops an update that wouldn't move the line (see
        // MapOverlays.setDrivenFraction).
        mapOverlays?.setDrivenFraction(progress.drivenFraction)
        NavRelay.send(context, progress, currentSpeedKmh = fix.speedMps * 3.6)
        BleNavServer.send(context, progress, currentSpeedKmh = fix.speedMps * 3.6)

        // Same policy the head unit and iOS read, so the three surfaces cannot
        // word one maneuver three ways.
        announcer.onProgress(progress.nextInstruction, progress.distanceToTurnMeters)
            ?.let { announceAloud(it) }

        // Arrival and reroute are NavPolicy's call, shared with car/NavScreen.kt.
        val dest = destination
        val now = System.currentTimeMillis()
        when (NavPolicy.decide(
            progress = progress,
            hasDestination = dest != null,
            rerouting = rerouting,
            lastRerouteMs = lastRerouteMs,
            nowMs = now,
        )) {
            // Point-to-point only; loops end back at the start on their own.
            NavPolicy.Decision.Arrived -> {
                stopNavigation()
                return@LaunchedEffect
            }
            // Off route → fresh route to the destination. Launched on the screen
            // scope so the next GPS fix doesn't cancel the request; loops keep
            // their drawn line (rerouting a loop would change the whole trip).
            NavPolicy.Decision.Reroute -> {
                val target = dest ?: return@LaunchedEffect // Reroute implies a destination
                rerouting = true
                lastRerouteMs = now
                announceAloud(announcer.rerouting())
                scope.launch {
                    try {
                        route = withContext(Dispatchers.IO) {
                            RoutingServer.route(serverConfig, pos, target, mode.ghProfile,
                                Settings.avoidHighways.value, Settings.avoidSmallRoads.value)
                        }
                        // Instruction indices belong to the old polyline; start
                        // the new line's prompts from scratch.
                        announcer.routeChanged()
                    } catch (e: Exception) {
                        // stay on the old line; retried after the cooldown
                    } finally {
                        rerouting = false
                    }
                }
            }
            NavPolicy.Decision.Continue -> {}
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
                val explored = withContext(Dispatchers.IO) { ExploredArea.load() }
                if (mode.roundTrip) {
                    // Prefer the self-hosted routing server (real road-following
                    // loops, curviest of a few rolls); fall back to Overpass
                    // sampling.
                    val tripMeters = radiusKm * 1000.0
                    var result: RouteResult? = null
                    if (serverConfig.usable) {
                        result = try {
                            val rolls = coroutineScope {
                                (1..CURVY_CANDIDATES).map {
                                    async(Dispatchers.IO) {
                                        runCatching {
                                            val loop = RoutingServer.roundTrip(
                                                serverConfig, loc, tripMeters, Random.nextLong(),
                                                headingDeg = directionDeg?.toDouble(),
                                                avoidSmallRoads = Settings.avoidSmallRoads.value)
                                            // Scored here so it stays off the main
                                            // thread with the request that produced it.
                                            loop to Curviness.routeScore(
                                                loop.polyline, loop.instructions)
                                        }
                                    }
                                }.awaitAll()
                            }
                            val loops = rolls.mapNotNull { it.getOrNull() }
                            if (loops.isEmpty()) {
                                // Every roll failed the same way; report the first.
                                val e = rolls.firstNotNullOfOrNull { it.exceptionOrNull() }
                                if (e is CancellationException) throw e
                                serverError = e?.message ?: e?.javaClass?.simpleName ?: "no route"
                                null // fall back to Overpass below, but say why
                            } else {
                                loops.maxBy { it.second }.first
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            serverError = e.message ?: e.javaClass.simpleName
                            null
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
        // A convoy spin's candidates are mode-specific too - a switch away
        // must not leave a stale vote round on everyone's screen.
        if (spinOffer != null) ConvoyLiveClient.clearSpinOffer()
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    layersOpen = layersOpen,
                    onLayersOpenChange = { layersOpen = it },
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
            // activeConvoyId != null is also required now that the same
            // socket can be connected for a circle's notify-only join with
            // no convoy at all - see the mic permission effect above.
            // Gated on its own flag rather than on the relay's: the rebuilt relay
            // carries positions and votes but drops voice frames, so a button
            // shown here would transmit into nothing and read as a bug.
            AnimatedVisibility(
                visible = Features.pushToTalk && convoyConnected && activeConvoyId != null,
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
                    displayCandidates.isNotEmpty() -> BottomCard.CANDIDATES
                    settingsCollapsed -> BottomCard.COLLAPSED
                    else -> BottomCard.EXPANDED
                }
                // Same trick as shownStats: the exiting candidates pane must
                // not render an empty card after a cancel clears the list.
                val shownCandidates = remember { mutableStateOf(displayCandidates) }
                if (displayCandidates.isNotEmpty()) shownCandidates.value = displayCandidates
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
                            offRoute = (navProgress?.offRouteMeters ?: 0.0) >
                                NavPolicy.OFF_ROUTE_METERS,
                            onExit = { stopNavigation() },
                        )
                        BottomCard.CANDIDATES -> CandidatesCard(
                            candidates = shownCandidates.value,
                            onPick = { index, c ->
                                if (spinOffer != null) ConvoyLiveClient.sendSpinVote(index) else choose(c)
                            },
                            onReroll = { candidates = emptyList(); spin() },
                            onCancel = {
                                candidates = emptyList()
                                if (spinOffer != null) ConvoyLiveClient.clearSpinOffer()
                            },
                            // Non-null only once a spin has actually been shared - that's
                            // also what tells the card to show votes instead of Reroll.
                            convoyVotes = spinOffer?.let { spinVotes },
                            onShare = if (activeConvoyId != null && spinOffer == null && candidates.isNotEmpty()) {
                                { ConvoyLiveClient.sendSpinOffer(candidates.asSpinCandidates()) }
                            } else null,
                            // The sharer's button only: closing the round is
                            // one device's call, same reason the auto-commit
                            // above is.
                            onGoWithLead = spinOffer?.takeIf { it.fromMe }?.let { offer ->
                                {
                                    ConvoyLiveClient.sendSpinOffer(listOf(
                                        offer.candidates[
                                            leadingSpinIndex(spinVotes, offer.candidates.size)]))
                                }
                            },
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

    if (showBgLocationDisclosure) {
        BackgroundLocationDisclosure(
            onAllow = {
                showBgLocationDisclosure = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            },
            onDismiss = { showBgLocationDisclosure = false },
        )
    }

    savePinTarget?.let { target ->
        SavePinDialog(
            suggestedName = destinationName?.takeIf { it != "Dropped pin" } ?: "",
            onSave = { name ->
                SavedPlaces.add(name, target)
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

package com.jellemax.detour.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.RectF
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import java.io.IOException
import android.os.Build
import android.os.SystemClock
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import com.jellemax.detour.ColdStartTiming
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.data.RoutingServer
import com.jellemax.detour.data.pickCandidate
import com.jellemax.detour.data.SavedPlaces
import com.jellemax.detour.auth.PendingSignIn
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.SpeedCameras
import com.jellemax.detour.data.SyncClient
import com.jellemax.detour.data.TraceStore
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.drive.CameraPrefetch
import com.jellemax.detour.drive.CameraWarner
import com.jellemax.detour.drive.SectionAverageTracker
import com.jellemax.detour.drive.SpeedLimitTracker
import com.jellemax.detour.drive.SpinRoundOutcome
import com.jellemax.detour.map.CameraAuthority
import com.jellemax.detour.map.FollowCamera
import com.jellemax.detour.map.MapMotion
import com.jellemax.detour.map.NavPolicy
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
    retained: RetainedMap,
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
    // A sign-in that fails on the way back from the browser had exactly one
    // reader — FriendsScreen, the screen with the button on it — and `screen` in
    // AppRoot is a plain `remember`. So whenever Android restarted the app behind
    // the browser, the redirect landed on a fresh process that composes the map,
    // and the reason went nowhere at all. That is the case most likely to fail,
    // which made it the case least likely to be explained.
    //
    // Its own effect rather than a write into `error` above: that var has a dozen
    // writers already, and a sign-in failure is not a spin failure. Repeats are
    // not a concern here — every Sign in tap clears this first, so a second
    // identical failure still re-keys from null.
    val signInError by PendingSignIn.error.collectAsStateWithLifecycle()
    LaunchedEffect(signInError) {
        signInError?.let { snackbarHostState.showSnackbar(it) }
    }
    // And the same for a sign-in that worked, which said even less: the avatar in
    // the top corner turned from a question mark into a letter, and that was the
    // whole announcement. Cleared once shown so returning to the map later does
    // not re-announce it — the failure above needs no such call, because every
    // Sign in tap clears it on the way out.
    val signedInAs by PendingSignIn.signedInAs.collectAsStateWithLifecycle()
    LaunchedEffect(signedInAs) {
        val handle = signedInAs ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            if (handle.isBlank()) "Signed in" else "Signed in as $handle"
        )
        PendingSignIn.clearSignedIn()
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
    //
    // Loaded off the main thread: reading + JSON-decoding traces.jsonl inside a
    // remember{} ran during composition and stalled the first frame — this is
    // the app's default landing screen, so that stall was the app's cold start.
    // Empty until the read lands; the fog effect below just redraws when it does.
    val storeVersion by TraceStore.version.collectAsStateWithLifecycle()
    val traces by produceState(initialValue = emptyList<List<LatLon>>(), storeVersion) {
        value = withContext(Dispatchers.IO) { TraceStore.loadAll() }
    }
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
    // following off, it parks it until you are moving again. All three - the
    // intent, the park and the quiet-window stamp - have one owner: every
    // transition is a CameraAuthority.reduce dispatch, and the rules (including
    // the spin park that deliberately does not stamp) live there with their
    // tests rather than being spread across ten call sites.
    var camAuthority by remember { mutableStateOf(CameraAuthority.State()) }
    // Dock (collapsed) is the resting state; the sheet only comes up when
    // tapped open, and folds back down on its own after a spin lands.
    var settingsCollapsed by rememberSaveable { mutableStateOf(true) }
    var ambientSpeedLimitKmh by remember { mutableStateOf<Double?>(null) }
    // The prefetched way set, the fetch throttle, the miss counter and the
    // snapped value: SpeedLimitTracker's, in shared/…/drive/, where the policy
    // lives with its tests. ambientSpeedLimitKmh stays its own state because the
    // camera chime snapshots it below and the HUD reads it; collapsing the two is
    // the state layer's call, not this one's.
    var limitState by remember { mutableStateOf(SpeedLimitTracker.State()) }
    // Out here rather than inside the effect that uses it, for the same reason
    // limitState is: that effect is keyed on `navigating` and restarts, and a
    // holder that restarted with it would forget an in-flight fetch — so the
    // guard would wave a second one through on the very next fix after a
    // navigation toggle. The fetch itself runs on `scope`, which outlives the
    // restart, so the two have to agree about what is running.
    var speedLimitFetchJob by remember { mutableStateOf<Job?>(null) }
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
    // Held in RetainedMap, not in a remember: these survive a navigation so the
    // camera does not ease back to the default zoom and north-up every time the
    // rider returns to the map. See RetainedMap's camera section.
    // Same expression as before, now owned by the state: navigation drives the
    // camera whether or not you are following, and a park still stops it.
    val cameraActive = camAuthority.cameraActive(navigating)

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
    // reinstall and picks up trips recorded while the app was closed. Gated
    // by SyncClient.syncIfDue() so relaunching soon after a sync (the common
    // case) doesn't re-pay the full-history round trip every time.
    LaunchedEffect(Unit) {
        if (SyncClient.configured() && Account.signedIn) {
            withContext(Dispatchers.IO) {
                try {
                    SyncClient.syncIfDue()
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

    // All four outlive this composition — see RetainedMap. Leaving the map for
    // the Hub no longer destroys the GL surface or re-fetches the style.
    val mapView = retained.mapView
    val fogView = retained.fogView
    val mapLibreMap = retained.map
    val mapOverlays = retained.overlays

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

    // The MapView's lifecycle, getMapAsync and style load all moved into
    // RetainedMap, which owns them for the Activity's life rather than for this
    // composition's. Calling onDestroy here would destroy a map the next entry
    // expects to still be alive.

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
                camAuthority = CameraAuthority.reduce(
                    camAuthority,
                    CameraAuthority.Action.Gesture(System.currentTimeMillis()),
                )
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
                // a long-press pin drop or a marker tap, not a pan. That guard
                // is GestureEnd's - it leaves an unparked camera alone.
                MotionEvent.ACTION_UP -> {
                    camAuthority = CameraAuthority.reduce(
                        camAuthority,
                        CameraAuthority.Action.GestureEnd(System.currentTimeMillis()),
                    )
                }
            }
            false
        }
        onDispose { mapView.setOnTouchListener(null) }
    }

    // Driving off takes the camera back; the rule is FollowCamera's. The keys are
    // derived booleans on purpose - keying on the collections themselves would
    // restart this collector on every convoy vote.
    LaunchedEffect(camAuthority.camSuspended, spinning, candidates.isEmpty(), spinOffer == null) {
        if (!FollowCamera.shouldWatch(
                camSuspended = camAuthority.camSuspended,
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
                    lastGestureMs = camAuthority.lastGestureMs,
                )
            ) {
                camAuthority = CameraAuthority.reduce(
                    camAuthority,
                    CameraAuthority.Action.DriveOffResumed,
                )
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
        // Parks and buys the same grace period a pan gets, so a pick made at
        // speed isn't re-centered before you've seen the route you just chose.
        camAuthority = CameraAuthority.reduce(
            camAuthority,
            CameraAuthority.Action.DestinationFramed(System.currentTimeMillis()),
        )
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
        camAuthority = CameraAuthority.reduce(
            camAuthority,
            CameraAuthority.Action.DestinationFramed(System.currentTimeMillis()),
        )
        mapLibreMap?.let { cameraForPoints(it, listOf(loc, LatLon(c.lat, c.lon)), FIT_PADDING_PX, fitBottomPaddingPx) }
    }

    // How a vote round ends: the rule and its correctness argument are
    // ConvoyRelay.spinRoundOutcome (shared/.../drive/ConvoyRelay.kt).
    LaunchedEffect(spinOffer, spinVotes, convoyPeers, accountUsername) {
        val offer = spinOffer ?: return@LaunchedEffect
        if (offer.candidates.size == 1) {
            commitSpinCandidate(0)
            return@LaunchedEffect
        }
        when (val outcome = ConvoyLiveClient.spinRoundOutcome(accountUsername)) {
            SpinRoundOutcome.Wait, SpinRoundOutcome.CommitOnly -> Unit
            is SpinRoundOutcome.CloseRound ->
                ConvoyLiveClient.sendSpinOffer(listOf(offer.candidates[outcome.leadIndex]))
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
            // The marker loop below owns SRC_POSITION and writes it every frame, so this
            // render must not touch it. Hide would not mean "leave it alone" — it clears
            // the source, and this effect is keyed on myLocation, so the dot would be
            // erased once a second and redrawn by the next frame.
            positionMarker = PositionMarker.CallerDraws,
            // Same bearing the camera is easing towards, which is already held
            // through a stop rather than following the noise below 2 m/s.
            positionBearingDeg = retained.camTargetBearing?.toDouble(),
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
    // The trace polyline genuinely changes once per fix, so it belongs here. The
    // reveal centre does not: the marker loop sets it per frame from the same
    // interpolated position the dot is drawn at, so the hole and the dot agree
    // rather than the hole trailing by the prediction lead.
    //
    // It is still set here when there is no fix, because myLocation has a second
    // writer — the one-shot last-known-location fetch at :474 that centres the map
    // at startup — and the fog needs a centre in the window before the first fix
    // arrives. Once fixes are flowing the loop owns it.
    LaunchedEffect(liveTrace, myLocation) {
        fogView.liveTrace = liveTrace
        if (liveFix == null) fogView.currentLocation = myLocation
        fogView.invalidate()
    }

    // Long-press drops a destination pin; a tap on a candidate dot commits to it
    // (or, mid convoy-vote, casts a vote instead - see spinOfferRef below).
    // Registered once the map is ready; the listeners read live state via refs.
    val candidatesRef = rememberUpdatedState(displayCandidates)
    val spinOfferRef = rememberUpdatedState(spinOffer)
    val navigatingRef = rememberUpdatedState(navigating)
    // A DisposableEffect, not a LaunchedEffect, and that is load-bearing now the
    // map outlives this composition. Before RetainedMap, `mapLibreMap` went
    // null -> map exactly once per Activity, so registering without removing was
    // safe (the hazards skill's §2b). Now every return to the map composes
    // against an already-non-null map and re-runs this — so each of the four
    // has to come back off, or a rider who visits the Hub three times gets
    // sixteen listeners and the fog invalidates four times per camera move.
    // The remove-what-you-added shape is FogView.map's setter, in MapLibreMap.kt.
    DisposableEffect(mapLibreMap) {
        val map = mapLibreMap ?: return@DisposableEffect onDispose { }
        // The fog is screen-space, projected through the map — redraw it on every
        // camera change so a manual pan/pinch keeps it glued to the map, not just
        // while the follow loop is running.
        val onCameraMove = MapLibreMap.OnCameraMoveListener { fogView.invalidate() }
        val onCameraIdle = MapLibreMap.OnCameraIdleListener { fogView.invalidate() }
        // Touching the map dismisses the layers panel, which is what the Popup's
        // dismissOnClickOutside used to do before the panel moved inline.
        val onLongClick = MapLibreMap.OnMapLongClickListener { ll ->
            layersOpen = false
            if (navigatingRef.value) return@OnMapLongClickListener false
            destination = LatLon(ll.latitude, ll.longitude)
            destinationName = "Dropped pin"
            route = null
            true
        }
        val onClick = MapLibreMap.OnMapClickListener { ll ->
            layersOpen = false
            val p = map.projection.toScreenLocation(ll)
            val tap = RectF(p.x - 22f, p.y - 22f, p.x + 22f, p.y + 22f)
            val idx = map.queryRenderedFeatures(tap, LAYER_CANDIDATES)
                .firstOrNull()?.getNumberProperty("index")?.toInt()
            val cs = candidatesRef.value
            if (idx == null || idx >= cs.size) return@OnMapClickListener false
            if (spinOfferRef.value != null) ConvoyLiveClient.sendSpinVote(idx) else choose(cs[idx])
            true
        }
        map.addOnCameraMoveListener(onCameraMove)
        map.addOnCameraIdleListener(onCameraIdle)
        map.addOnMapLongClickListener(onLongClick)
        map.addOnMapClickListener(onClick)
        onDispose {
            map.removeOnCameraMoveListener(onCameraMove)
            map.removeOnCameraIdleListener(onCameraIdle)
            map.removeOnMapLongClickListener(onLongClick)
            map.removeOnMapClickListener(onClick)
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
        retained.camTargetBearing = null
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
        camAuthority = CameraAuthority.reduce(camAuthority, CameraAuthority.Action.NavigationStarted)
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

    // Ambient speed-limit sign while just driving (not navigating). The whole
    // policy — the prefetch throttle, the local snap and the three-miss clear —
    // is SpeedLimitTracker's (shared/…/drive/), where it lives with its tests and
    // is shared with the head unit. The I/O below is ours: commonMain has no
    // Dispatchers, so the machine says a fetch is wanted and we perform it.
    LaunchedEffect(navigating) {
        // Crossing into or out of navigation invalidates whatever sign we hold;
        // reset() says why, and keeps the prefetched area. Clear it and let the
        // next snap re-establish it, the way the car has since it shipped
        // (car/SpinScreen.kt's onStart).
        limitState = SpeedLimitTracker.reset(limitState)
        ambientSpeedLimitKmh = null
        if (navigating) return@LaunchedEffect
        TripTrackingService.lastFix.collect { fix ->
            fix ?: return@collect
            // Not just the machine's own floor: returning here is what also keeps
            // a parked phone from prefetching.
            if (fix.speedMps < SpeedLimitTracker.MIN_MPS) return@collect
            val pos = LatLon(fix.lat, fix.lon)
            val now = System.currentTimeMillis()
            if (SpeedLimitTracker.needsWays(limitState, pos, now) &&
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
                // Same fix as car/SpinScreen.kt's updateSpeedLimit.
                limitState = SpeedLimitTracker.fetchStarted(limitState, now)
                speedLimitFetchJob = scope.launch {
                    // runCatching because this no longer runs inside the
                    // collector: an exception escaping here would cancel
                    // `scope`, i.e. every coroutine this screen owns, where
                    // inline it only killed this one collector. speedLimitWays
                    // now catches the SerializationException a busy Overpass's
                    // HTML error page produces as well as the IOException — the
                    // hazard SpeedCameras.near documents — so the runCatching is
                    // belt and braces rather than the only guard it used to be.
                    //
                    // getOrNull, not getOrDefault(emptyList()): speedLimitWays
                    // returns null for both of those and an empty list only for
                    // an area with no tagged road. The tracker backs off on the
                    // first and not on the second, and collapsing them here
                    // would give that distinction away.
                    val ways = runCatching {
                        withContext(Dispatchers.IO) { RoadRoulette.speedLimitWays(pos) }
                    }.getOrNull()
                    limitState = SpeedLimitTracker.withWays(limitState, ways, pos)
                }
            }
            limitState = SpeedLimitTracker.onFix(
                state = limitState,
                at = pos,
                headingDeg = fix.bearingDeg?.toDouble(),
                speedMps = fix.speedMps,
            )
            ambientSpeedLimitKmh = limitState.limitKmh
        }
    }

    // Speed cameras + trajectcontrole sections from Overpass (OSM). Prefetched
    // for a wide circle, refreshed only as you near the edge of what you hold,
    // so there's no request per fix. A null result is a network blip: keep the
    // markers we have and let CameraPrefetch's backoff decide when to try again,
    // instead of flickering them off.
    LaunchedEffect(Unit) {
        // The cadence — the margin, the throttle and the backoff after a run of
        // refusals — is CameraPrefetch's (shared/…/drive/), so the head unit
        // keeps the same one. What stays here is the I/O and the two holders it
        // fills. Coroutine-local, unlike the ambient limit's holder up in the
        // body: this effect is keyed on Unit and never restarts, so a local has
        // nothing to lose. Keeping it here is what says so.
        var prefetch = CameraPrefetch.State()
        var fetchJob: Job? = null
        TripTrackingService.lastFix.collect { fix ->
            fix ?: return@collect
            val pos = LatLon(fix.lat, fix.lon)
            val now = System.currentTimeMillis()
            if (CameraPrefetch.needsFetch(prefetch, pos, now) &&
                fetchJob?.isActive != true
            ) {
                // Own coroutine, isActive guard, runCatching: same reasoning as
                // the ambient limit above, and as car/NavScreen.kt:348-379,
                // which is where this was diagnosed. This collector feeds the
                // section machine, so suspending it also stalled the running
                // average's own fix stream.
                prefetch = CameraPrefetch.fetchStarted(prefetch, now)
                fetchJob = scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) { SpeedCameras.near(pos) }
                    }.getOrNull()
                    prefetch = CameraPrefetch.fetched(prefetch, result, pos)
                    // Only the markers are ours to fold in; a null result keeps
                    // the ones we hold rather than flickering them off.
                    if (result != null) {
                        speedCameras = result.cameras
                        speedSections = result.sections
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
        retained.camTarget = LatLon(fix.lat, fix.lon)
        if (fix.bearingDeg != null && fix.speedMps > 2.0) retained.camTargetBearing = fix.bearingDeg
        retained.camTargetZoom = NavEngine.cameraZoom(
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
            val gap = target - retained.displaySpeedKmh
            retained.displaySpeedKmh =
                if (abs(gap) < SPEED_EPS_KMH) target
                else retained.displaySpeedKmh + gap * (1.0 - exp(-dt / SPEED_TAU))
        }
    }

    // The camera itself: one loop, one frame at a time, easing toward whatever
    // the last fix asked for. Compose only produces frames while the activity is
    // resumed, so this costs nothing with the screen off.
    // `haveFix` is a key so that turning follow on before the first fix arrives
    // still starts the loop once it does, instead of leaving it returned-out.
    val haveFix = retained.camTarget != null || myLocation != null
    LaunchedEffect(cameraActive, haveFix, mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        if (!cameraActive) {
            // Level back to north-up when we stop following.
            map.cameraPosition.target?.let {
                setCamera(map, it.latitude, it.longitude, map.cameraPosition.zoom, 0f)
            }
            return@LaunchedEffect
        }
        val start = retained.camTarget ?: myLocation ?: return@LaunchedEffect
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
            val camTargetNow = if (f != null) MapMotion.predict(
                at = LatLon(f.lat, f.lon),
                bearingDeg = f.bearingDeg,
                speedMps = f.speedMps,
                fixElapsedMs = f.elapsedRealtimeMs,
                nowElapsedMs = SystemClock.elapsedRealtime(),
                leadSeconds = CAM_POS_TAU,
            ) else retained.camTarget
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
        var lastLat = Double.NaN
        var lastLon = Double.NaN
        var pushedBearing: Float? = null
        var markerBearing: Float? = null
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
                nowElapsedMs = SystemClock.elapsedRealtime(),
                leadSeconds = 0.0,
            )
            retained.camTargetBearing?.let { target ->
                markerBearing = smoothBearing(
                    markerBearing, target, (1.0 - exp(-dt / CAM_BEARING_TAU)).toFloat())
            }
            // Named apart from the camera loop's own `bearing`, which is that loop's mutable
            // accumulator rather than a snapshot — the two effects sit a screen apart in this
            // file and reusing the name invites conflating them.
            val easedBearing = markerBearing
            val moved = here.lat != lastLat || here.lon != lastLon
            // The gate covers the bearing as well as the position, or a vehicle stopped
            // mid-rotation would ease its nose and never push it. CAM_BEARING_EPS_DEG keeps
            // the standstill optimisation the position half already had: once the marker
            // has settled, this loop goes quiet again.
            val turned = easedBearing != null && (pushedBearing == null ||
                bearingDelta(pushedBearing, easedBearing) > CAM_BEARING_EPS_DEG)
            if (moved || turned) {
                overlays.setPosition(here, easedBearing?.toDouble())
                // Whatever the reason for the push, the bearing just drawn is this one, so
                // that is what the next frame must compare against. Advancing it only on a
                // `turned` push would leave the reference describing something no longer on
                // screen, and cost a redundant push cycle the moment the vehicle stops.
                pushedBearing = easedBearing
            }
            if (moved) {
                // The fog reveals around the same interpolated position, or its hole
                // trails the dot by the prediction lead — about 14 m at 100 km/h,
                // snapping forward once a second. The invalidate is for the parked
                // camera: while following, the camera-move listener below already
                // redraws every frame, but parked nothing else would, and that is
                // exactly when a lagging fog is most visible.
                fogView.currentLocation = here
                fogView.invalidate()
                lastLat = here.lat
                lastLon = here.lon
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
            // it straight back to you before you could look at it. SpinStarted
            // parks without stamping the quiet window - see CameraAuthority.reduce:
            // that asymmetry is today's behaviour, kept deliberately.
            camAuthority = CameraAuthority.reduce(camAuthority, CameraAuthority.Action.SpinStarted)
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
            // The view is retained, so on a return it is still attached to the
            // parent the previous entry gave it, and addView would throw
            // "The specified child already has a parent".
            AndroidView(
                factory = {
                    (mapView.parent as? android.view.ViewGroup)?.removeView(mapView)
                    mapView
                },
                modifier = Modifier.fillMaxSize(),
            )

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
                    followMe = camAuthority.following,
                    fogEnabled = fogEnabled,
                    username = accountUsername,
                    convoyName = if (convoyConnected) convoyName else null,
                    layersOpen = layersOpen,
                    onLayersOpenChange = { layersOpen = it },
                    onToggleFollow = {
                        camAuthority = CameraAuthority.reduce(
                            camAuthority,
                            CameraAuthority.Action.FollowToggled,
                        )
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
                    liveFix?.takeIf { it.speedMps >= 1.4 || retained.displaySpeedKmh >= 2.0 }?.let {
                        SpeedHud(
                            speedKmh = retained.displaySpeedKmh,
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
                            camAuthority = CameraAuthority.reduce(
                                camAuthority,
                                CameraAuthority.Action.DestinationFramed(System.currentTimeMillis()),
                            )
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
                                            ConvoyLiveClient.currentLeadIndex(offer.candidates.size)]))
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
                camAuthority = CameraAuthority.reduce(
                    camAuthority,
                    CameraAuthority.Action.DestinationFramed(System.currentTimeMillis()),
                )
                mapLibreMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(
                    LatLng(r.location.lat, r.location.lon), 14.0), 800)
            },
            onDismiss = { searchOpen = false },
        )
    }
}

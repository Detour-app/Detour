package com.jellemax.detour.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.RectF
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.google.android.gms.location.Priority
import com.jellemax.detour.audio.NavVoice
import com.jellemax.detour.audio.PushToTalk
import com.jellemax.detour.data.Features
import com.jellemax.detour.net.ConvoyLiveClient
import com.jellemax.detour.data.Account
import com.jellemax.detour.data.CircleFixes
import com.jellemax.detour.data.ConvoysStore
import com.jellemax.detour.data.FriendFog
import com.jellemax.detour.data.Groups
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.NamedMemberFix
import com.jellemax.detour.data.NavAnnouncer
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.data.handleFor
import com.jellemax.detour.data.NavEngine
import com.jellemax.detour.data.PoiKind
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.data.RouteCandidate
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.data.RoutingClient
import com.jellemax.detour.data.RoutingServer
import com.jellemax.detour.data.SavedPlaces
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.SpeedCameras
import com.jellemax.detour.data.TraceStore
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.drive.CameraPrefetch
import com.jellemax.detour.drive.CameraWarner
import com.jellemax.detour.drive.SectionAverageTracker
import com.jellemax.detour.drive.SpeedLimitTracker
import com.jellemax.detour.drive.SpinRoundOutcome
import com.jellemax.detour.presentation.HomeBottomCard
import com.jellemax.detour.presentation.displayCandidates
import com.jellemax.detour.presentation.homeBottomCard
import com.jellemax.detour.presentation.navStateFrom
import com.jellemax.detour.presentation.obd2FedThisTrip
import com.jellemax.detour.presentation.pushToTalkShown
import com.jellemax.detour.presentation.reachMeters
import com.jellemax.detour.presentation.RiderCardState
import com.jellemax.detour.presentation.riderCardStateFrom
import com.jellemax.detour.presentation.speedHudStateFrom
import com.jellemax.detour.map.CAM_BEARING_EPS_DEG
import com.jellemax.detour.map.CAM_BEARING_TAU
import com.jellemax.detour.map.CameraAuthority
import com.jellemax.detour.map.FollowCamera
import com.jellemax.detour.map.MapMotion
import com.jellemax.detour.map.ModeSwipePolicy
import com.jellemax.detour.map.NavStart
import com.jellemax.detour.map.SpinOutcome
import com.jellemax.detour.map.SpinParams
import com.jellemax.detour.map.modeSwitch
import com.jellemax.detour.map.fetchNavRoute
import com.jellemax.detour.map.navStart
import com.jellemax.detour.map.runSpin
import com.jellemax.detour.map.NavPolicy
import com.jellemax.detour.map.needsBackgroundDisclosure
import com.jellemax.detour.map.requiredStartupPermissions
import com.jellemax.detour.map.shouldRequestMic
import com.jellemax.detour.map.bearingDelta
import com.jellemax.detour.map.smoothBearing
import com.jellemax.detour.obd2.Obd2Connection
import com.jellemax.detour.obd2.Obd2ConnectionState
import com.jellemax.detour.tracking.LocationSources
import com.jellemax.detour.tracking.TripTrackingService
import com.jellemax.detour.ble.BleNavServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import kotlin.math.abs
import kotlin.math.exp

@Composable
fun MapScreen(
    onOpenHub: () -> Unit,
    onOpenRoutes: () -> Unit,
    onOpenSocial: () -> Unit,
    retained: RetainedMap,
) {
    val context = LocalContext.current
    // Extra bottom padding for a fitted route/candidate spread, so whatever is
    // in the bottom slot doesn't cover most of it. See the constant for what
    // it is measured against and why it stopped being a fraction of the screen.
    val fitBottomPaddingPx = with(LocalDensity.current) {
        MAP_FIT_BOTTOM_PADDING_DP.dp.roundToPx()
    }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val savedPlaces by SavedPlaces.places.collectAsStateWithLifecycle()
    // Non-null while a name is being entered for the current dropped/destination pin.

    // Play policy requires our own disclosure of what background location is
    // for, shown and accepted before the system prompt may be raised.

    // Persisted, because the tracking service reads it too: an auto-detected
    // trip has no other way to know whether it is a ride or a drive.
    val mode by Settings.tripMode.collectAsStateWithLifecycle()
    var radiusKm by rememberSaveable { mutableFloatStateOf(Settings.tripMode.value.defaultKm) }
    var minRadiusKm by rememberSaveable { mutableFloatStateOf(0f) }
    // Seeded from SpinResultHolder so a spin result survives activity
    // recreation instead of resetting to defaults; see its declaration above.
    // One owner for the screen's own state. `remember`, so its lifetime is
    // exactly what the twenty loose vars had; the five rememberSaveable ones
    // below are deliberately NOT in here (see MapScreenState's KDoc).
    val s = remember { MapScreenState(SpinResultHolder.state.value) }
    // `error` has a dozen writers and, until now, one reader — inside SpinSheet,
    // which is collapsed by default. A denied location permission therefore
    // reported itself to nobody. The snackbar shows it whatever the bottom card
    // is doing; the sheet keeps its own copy for when it is open.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(s.error) {
        s.error?.let { snackbarHostState.showSnackbar(it) }
    }
    val serverConfig = remember { RoutingServer.load() }
    var poiKind by rememberSaveable { mutableStateOf(PoiKind.ROAD) }
    var directionDeg by rememberSaveable { mutableStateOf<Float?>(null) }
    val fogEnabled by Settings.fogEnabled.collectAsStateWithLifecycle()
    // The handle to draw (MapTopChrome's avatar) and the id to compare
    // (the spin-round self-check, the circle self-filter below) are two
    // different things this screen legitimately needs at once - see
    // RiderId's own doc for why #133 keeps them as separate types rather
    // than one string doing both jobs.
    val accountUsername by Account.username.collectAsStateWithLifecycle()
    val accountRiderId by Account.riderId.collectAsStateWithLifecycle()
    // The map layers panel. Lives here rather than in MapTopChrome so the map's
    // own click listeners can close it — see where they clear it below.
    // The destination search island, hoisted for the same reason: a tap on the
    // map is its outside-tap dismissal.
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
    val obd2State by Obd2Connection.connectionState.collectAsStateWithLifecycle()
    val obd2LastDataAtMs by Obd2Connection.lastDataAtMs.collectAsStateWithLifecycle()
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
    // convoyPeers/spinVotes carry an id and no handle now (#133) — this is
    // the membership ConvoyLiveClient's own watchPeers self-heals, so a peer
    // who joined mid-ride still gets a name once the store catches up rather
    // than staying a raw id forever.
    val convoysState by ConvoysStore.state.collectAsStateWithLifecycle()
    val activeConvoyMembers = convoysState.convoys.firstOrNull { it.id == activeConvoyId }?.members.orEmpty()
    // ConvoyLiveClient only knows the id it's connected to; resolve it to a
    // name for display by asking the same list FriendsScreen uses.
    LaunchedEffect(activeConvoyId) {
        val id = activeConvoyId
        s.convoyName = if (id == null) null else withContext(Dispatchers.IO) {
            try {
                Groups.list("convoy").find { it.id == id }?.name
            } catch (e: Exception) {
                null
            }
        }
    }


    // Keep the holder in sync with whatever changed these — a new spin, a
    // pick, a cancel, or navigation ending and clearing the result. Declared
    // here rather than beside `destination` because it now also reads
    // `navigating`, and Kotlin resolves local declarations in order.
    LaunchedEffect(s.destination, s.destinationName, s.route, s.candidates, s.navigating) {
        SpinResultHolder.publish(
            SpinResult(s.destination, s.destinationName, s.route, s.candidates, s.navigating)
        )
    }
    // Following is the resting state of the map. `camSuspended` is what a pan,
    // a pinch or a spin result sets so you can look around; it does not switch
    // following off, it parks it until you are moving again. All three - the
    // intent, the park and the quiet-window stamp - have one owner: every
    // transition is a CameraAuthority.reduce dispatch, and the rules (including
    // the spin park that deliberately does not stamp) live there with their
    // tests rather than being spread across ten call sites.
    // Collapsed is the resting state; the spin sheet comes up only when the
    // home sheet's Spin chip opens it. A concrete destination no longer
    // touches this flag — it takes the slot as the navigation dock through
    // `hasDestination` in homeBottomCard (#254), which outranks `collapsed`.
    var settingsCollapsed by rememberSaveable { mutableStateOf(true) }
    // A trip ending lands on the home sheet. Edge-triggered on a running trip
    // ending, not on "no trip": a return from the Hub with no trip must not
    // close a spin sheet the rider left open.
    var tripWasRunning by remember { mutableStateOf(stats != null) }
    LaunchedEffect(stats != null) {
        val running = stats != null
        if (tripWasRunning && !running) settingsCollapsed = true
        tripWasRunning = running
    }
    // The prefetched way set, the fetch throttle, the miss counter and the
    // snapped value: SpeedLimitTracker's, in shared/…/drive/, where the policy
    // lives with its tests. retained.ambientSpeedLimitKmh stays its own state because the
    // camera chime snapshots it below and the HUD reads it; collapsing the two is
    // the state layer's call, not this one's.
    // Out here rather than inside the effect that uses it, for the same reason
    // retained.limitState is: that effect is keyed on `navigating` and restarts, and a
    // holder that restarted with it would forget an in-flight fetch — so the
    // guard would wave a second one through on the very next fix after a
    // navigation toggle. The fetch itself runs on `scope`, which outlives the
    // restart, so the two have to agree about what is running.
    // Non-null only while driving through a trajectcontrole: the running average
    // speed since entering it, and the posted limit it's judged against.
    // Seeded from the retained machine, so a return mid-section shows the
    // reading it was showing rather than nothing.

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

    LaunchedEffect(liveFix) {
        liveFix?.takeIf { it.accuracyMeters <= 100f }?.let {
            s.myLocation = LatLon(it.lat, it.lon)
        }
    }

    // Keep the min-distance floor from exceeding the radius as the slider moves.
    LaunchedEffect(radiusKm) {
        if (minRadiusKm > radiusKm) minRadiusKm = radiusKm
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
                s.camAuthority = CameraAuthority.reduce(
                    s.camAuthority,
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
                    s.camAuthority = CameraAuthority.reduce(
                        s.camAuthority,
                        CameraAuthority.Action.GestureEnd(System.currentTimeMillis()),
                    )
                }
            }
            false
        }
        onDispose { mapView.setOnTouchListener(null) }
    }

    // Nothing rotates the map out from under a heading-up camera while navigating.
    // The map has no compass (RetainedMap turns it off), and a two-finger brush
    // parks the camera before it turns anything, so a stray rotation used to sit
    // there — skewed, unlabelled and with no way back — until the rider drove off
    // fast enough for FollowCamera to resume. Pinch and pan are untouched; only the
    // rotation goes, which is what the head unit has always done
    // (car/CarMapRenderer.kt). Off route it stays off too: the camera is still
    // heading-up, and a reroute is not the moment to hand rotation back.
    LaunchedEffect(mapLibreMap, s.navigating) {
        mapLibreMap?.uiSettings?.isRotateGesturesEnabled = !s.navigating
    }

    // Driving off takes the camera back; the rule is FollowCamera's. The keys are
    // derived booleans on purpose - keying on the collections themselves would
    // restart this collector on every convoy vote.
    LaunchedEffect(s.camAuthority.camSuspended, s.spinning, s.candidates.isEmpty(), spinOffer == null) {
        if (!FollowCamera.shouldWatch(
                camSuspended = s.camAuthority.camSuspended,
                spinning = s.spinning,
                hasCandidates = s.candidates.isNotEmpty(),
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
                    lastGestureMs = s.camAuthority.lastGestureMs,
                )
            ) {
                s.camAuthority = CameraAuthority.reduce(
                    s.camAuthority,
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
                // Through the location port (#306), not the platform directly.
                // Asking fused here was right while the only replay rig pushed
                // its fixes *into* fused; through the port the platform has
                // never heard of the route, so it answers with where the phone
                // physically is and this moveCamera yanks the map off the replay.
                val loc = LocationSources.currentLatLon(context)
                if (loc != null) {
                    s.myLocation = loc
                    // Only take the camera if it is still ours to take. This
                    // await can run for seconds, and a parked camera means the
                    // rider has since chosen something to look at - a search
                    // pick, a saved place, a spin result. Centring on them here
                    // would yank the map back off it long after the tap.
                    if (!s.camAuthority.camSuspended) {
                        mapLibreMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(
                            LatLng(loc.lat, loc.lon), Settings.defaultZoom.value.toDouble()))
                    }
                } else {
                    s.error = "Could not get location; is GPS on?"
                }
            } catch (e: SecurityException) {
                s.error = "Location permission missing"
            }
        }
    }

    fun onLocationGranted() {
        fetchLocation()
        TripTrackingService.startMonitoring(context)
        if (needsBackgroundDisclosure(
                sdkInt = Build.VERSION.SDK_INT,
                backgroundGranted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED,
            )
        ) {
            s.showBgLocationDisclosure = true
        }
    }

    // Three launchers and two effects, in MapPermissions.kt; every decision
    // they make is in map/PermissionPolicy.kt, with tests.
    val bgLocationLauncher = rememberMapPermissions(
        s = s,
        convoyConnected = convoyConnected,
        activeConvoyId = activeConvoyId,
        onLocationReady = { onLocationGranted() },
    )
    /** Commit to one spin candidate and frame the trip to it. */
    fun choose(c: RouteCandidate) {
        s.destination = c.destination
        s.destinationName = c.name
        s.route = c.route
        s.candidates = emptyList()
        val loc = s.myLocation ?: return
        // Parks and buys the same grace period a pan gets, so a pick made at
        // speed isn't re-centered before you've seen the route you just chose.
        s.camAuthority = CameraAuthority.reduce(
            s.camAuthority,
            CameraAuthority.Action.DestinationFramed(System.currentTimeMillis()),
        )
        mapLibreMap?.let { cameraForPoints(it, listOf(loc, c.destination), FIT_PADDING_PX, fitBottomPaddingPx) }
    }

    // What's actually shown on the map/card - see the shared rule for why a
    // convoy offer outranks this phone's own spin (ConvoyLiveClient.sendSpinOffer).
    val visibleCandidates = displayCandidates(spinOffer?.asRouteCandidates(), s.candidates)

    // One slot, five occupants, decided once here rather than re-derived where
    // each of them is drawn. The home sheet is the resting one; the other four
    // displace it.
    val bottomCard = homeBottomCard(
        navigating = s.navigating,
        hasCandidates = visibleCandidates.isNotEmpty(),
        tripActive = stats != null,
        hasDestination = s.destination != null,
        collapsed = settingsCollapsed,
    )

    // The search island lives in the home sheet and, when it is open, in the
    // drive sheet, so anything that displaces them takes the island's
    // BackHandler with it - and back would then leave the app rather than
    // dismissing a search that is still on screen. Closing it here covers
    // every displacement at once, navigation included, and the drive sheet is
    // no exception: the same trigger closes the sheet below, which takes its
    // island off screen, and a flag left true would pop the keyboard the next
    // time the sheet opened. Every slot change lands on a closed sheet.
    LaunchedEffect(bottomCard) {
        if (bottomCard != HomeBottomCard.COLLAPSED) s.searchOpen = false
        s.rideSheetExpanded = false
    }

    /** Commits a convoy spin's leading (or explicitly chosen) candidate,
     *  same as [choose] but sourced from [spinOffer] and clearing it after -
     *  see ConvoyLiveClient's class doc for why that's purely local. */
    fun commitSpinCandidate(index: Int) {
        val offer = spinOffer ?: return
        val c = offer.candidates.getOrNull(index) ?: return
        s.destination = LatLon(c.lat, c.lon)
        s.destinationName = c.name
        s.route = null // startNavigation() fetches a real route once tapped, same as a dropped pin
        s.candidates = emptyList()
        ConvoyLiveClient.clearSpinOffer()
        val loc = s.myLocation ?: return
        s.camAuthority = CameraAuthority.reduce(
            s.camAuthority,
            CameraAuthority.Action.DestinationFramed(System.currentTimeMillis()),
        )
        mapLibreMap?.let { cameraForPoints(it, listOf(loc, LatLon(c.lat, c.lon)), FIT_PADDING_PX, fitBottomPaddingPx) }
    }

    // How a vote round ends: the rule and its correctness argument are
    // ConvoyRelay.spinRoundOutcome (shared/.../drive/ConvoyRelay.kt).
    LaunchedEffect(spinOffer, spinVotes, convoyPeers, accountRiderId) {
        val offer = spinOffer ?: return@LaunchedEffect
        if (offer.candidates.size == 1) {
            commitSpinCandidate(0)
            return@LaunchedEffect
        }
        when (val outcome = ConvoyLiveClient.spinRoundOutcome(accountRiderId)) {
            SpinRoundOutcome.Wait, SpinRoundOutcome.CommitOnly -> Unit
            is SpinRoundOutcome.CloseRound ->
                ConvoyLiveClient.sendSpinOffer(listOf(offer.candidates[outcome.leadIndex]))
        }
    }

    // Push overlay state to the map whenever anything drawable changes. The
    // layers are created once per style; here we only swap their GeoJSON data.
    LaunchedEffect(mapOverlays, s.myLocation, s.destination, s.route, radiusKm, mode,
        directionDeg, s.navigating, visibleCandidates) {
        val overlays = mapOverlays ?: return@LaunchedEffect
        overlays.render(
            myLocation = s.myLocation,
            destination = s.destination,
            routePolyline = s.route?.polyline,
            reachMeters = reachMeters(
                hasLocation = s.myLocation != null,
                navigating = s.navigating,
                hasDestination = s.destination != null,
                roundTrip = mode.roundTrip,
                radiusKm = radiusKm.toDouble(),
            ),
            directionDeg = directionDeg?.toInt(),
            candidates = visibleCandidates.mapIndexed { i, c ->
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
    LaunchedEffect(liveTrace, s.myLocation) {
        fogView.liveTrace = liveTrace
        if (liveFix == null) fogView.currentLocation = s.myLocation
        fogView.invalidate()
    }

    // Long-press drops a destination pin; a tap on a candidate dot commits to it
    // (or, mid convoy-vote, casts a vote instead - see spinOfferRef below).
    // Registered once the map is ready; the listeners read live state via refs.
    val candidatesRef = rememberUpdatedState(visibleCandidates)
    val spinOfferRef = rememberUpdatedState(spinOffer)
    val navigatingRef = rememberUpdatedState(s.navigating)
    // A DisposableEffect, not a LaunchedEffect, and that is load-bearing now the
    // map outlives this composition. Before RetainedMap, `mapLibreMap` went
    // null -> map exactly once per Activity, so registering without removing was
    // safe (the hazards skill's §2b). Now every return to the map composes
    // against an already-non-null map and re-runs this — so each of the four
    // has to come back off, or a rider who visits the Hub three times gets
    // sixteen listeners and the fog invalidates four times per camera move.
    // The remove-what-you-added shape is FogView.map's setter, in FogView.kt.
    DisposableEffect(mapLibreMap) {
        val map = mapLibreMap ?: return@DisposableEffect onDispose { }
        // The fog is screen-space, projected through the map — redraw it on every
        // camera change so a manual pan/pinch keeps it glued to the map, not just
        // while the follow loop is running.
        val onCameraMove = MapLibreMap.OnCameraMoveListener { fogView.invalidate() }
        val onCameraIdle = MapLibreMap.OnCameraIdleListener { fogView.invalidate() }
        // Touching the map dismisses the layers panel and the search island,
        // which is what the Popup's dismissOnClickOutside used to do before the
        // panel moved inline — and the island's outside-tap dismissal.
        val onLongClick = MapLibreMap.OnMapLongClickListener { ll ->
            s.layersOpen = false
            s.searchOpen = false
            if (navigatingRef.value) return@OnMapLongClickListener false
            s.destination = LatLon(ll.latitude, ll.longitude)
            s.destinationName = "Dropped pin"
            s.route = null
            true
        }
        val onClick = MapLibreMap.OnMapClickListener { ll ->
            s.layersOpen = false
            s.searchOpen = false
            val p = map.projection.toScreenLocation(ll)
            // A tap on a convoy peer or circle member opens its rider card
            // (#156). Queried first, and with a gloved-thumb box around the
            // finger rather than the icon's own pixels — 28 dp each way.
            val riderHalf = 28f * context.resources.displayMetrics.density
            val hitRider = map.queryRenderedFeatures(
                RectF(p.x - riderHalf, p.y - riderHalf, p.x + riderHalf, p.y + riderHalf),
                LAYER_FRIENDS, LAYER_CIRCLE_MEMBERS,
            ).firstOrNull()?.getStringProperty("rider")
            if (hitRider != null) {
                s.tappedRider = RiderId(hitRider)
                return@OnMapClickListener true
            }
            // Empty map with the card open: the tap just dismisses it.
            if (s.tappedRider != null) {
                s.tappedRider = null
                return@OnMapClickListener true
            }
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
        s.navigating = false
        s.navProgress = null
        // Drop the route line: arrival or the Exit button ends the navigation,
        // and the geometry it drew has nothing left to follow. The render effect
        // keyed on `s.route` clears the layers; the destination pin stays (it is
        // never nulled here — see the `destination` effect above), so the user
        // can re-frame it. Same as the car surface, which drops its route on end
        // (car/NavScreen.kt).
        s.route = null
        // Arrival, or the Exit button. Either way stop mid-sentence rather than
        // finishing a prompt for a turn that no longer matters.
        navVoice.stop()
        retained.camTargetBearing = null
        // The marker goes back to the fix now rather than on the next frame the
        // loop runs: with the map parked, or the app away, that frame may be a
        // while, and until it lands the marker would sit on the abandoned line.
        retained.snappedAt = null
        // Reset the driven cut so the next route starts from a clean slate rather
        // than inheriting the last one's greyed fraction.
        mapOverlays?.setDrivenFraction(null)
        BleNavServer.clear(context)
    }

    fun startNavigation() {
        val loc = s.myLocation ?: run {
            s.error = "Waiting for your location…"
            return
        }
        s.camAuthority = CameraAuthority.reduce(s.camAuthority, CameraAuthority.Action.NavigationStarted)
        if (stats == null) {
            TripTrackingService.start(context, s.destination?.lat, s.destination?.lon)
        }
        s.error = null
        // A fresh session hears its first turn immediately, whatever the
        // distance — the same rule the car has, and the reason it exists is
        // that silence after pressing Start is indistinguishable from a broken
        // voice.
        announcer.routeChanged()
        // Which of the three cases this is lives in map/NavStart, with tests.
        when (val start = navStart(s.destination, s.route)) {
            NavStart.UseExistingRoute -> s.navigating = true
            NavStart.NoTurnData ->
                s.error = "No turn data for this loop — spin again with the routing server reachable"
            is NavStart.FetchTo -> {
                s.rerouting = true
                scope.launch {
                    try {
                        s.route = fetchNavRoute(serverConfig, loc, start.destination, mode)
                        s.navigating = true
                    } catch (e: Exception) {
                        s.error = "Navigation failed: ${e.message}"
                    } finally {
                        s.rerouting = false
                    }
                }
            }
        }
    }

    // The two Overpass prefetches, in MapHazardPrefetch.kt.
    MapHazardPrefetch(s = s, retained = retained, scope = scope, snackbarHostState = snackbarHostState)

    // Push camera markers to the map. Separate from the main overlay render
    // because cameras change on the prefetch cadence, not per drawable-state flip.
    LaunchedEffect(mapOverlays, retained.speedCameras) {
        mapOverlays?.setCameras(retained.speedCameras)
    }

    // Convoy friend markers, on ConvoyLiveClient's own relay-driven cadence —
    // same reasoning as the camera markers above. (convoyPeers itself is
    // collected further up, alongside the other convoy state.) Also keyed on
    // activeConvoyMembers, unlike the camera effect above: a position frame
    // repeats every couple of seconds and would eventually pick up a
    // membership reload on its own, but there is no reason to wait out that
    // window when the peer list itself hasn't changed — the redraw here is
    // idempotent, so re-running it the moment a name resolves costs nothing.
    LaunchedEffect(mapOverlays, convoyPeers, activeConvoyMembers) {
        mapOverlays?.setFriends(
            convoyPeers.map { (id, fix) -> NamedFriendPosition(fix, activeConvoyMembers.handleFor(id)) },
        )
    }

    // Circle member markers: the poll and the two draws, in MapCircleMembers.kt.
    MapCircleMemberMarkers(
        s = s,
        accountRiderId = accountRiderId,
        mapOverlays = mapOverlays,
        fogView = fogView,
        convoyPeers = convoyPeers,
    )

    // The camera chime and the trajectcontrole section, in MapHazardAlerts.kt.
    MapHazardAlerts(s = s, retained = retained, announceAloud = { announceAloud(it) })

    // Each fix only moves the targets; nothing touches the map here. This is
    // what lets the camera loop below run uninterrupted — the old code drove
    // animateTo() from an effect keyed on liveFix, so every fix cancelled the
    // previous 350ms flight partway through and the map lurched.
    LaunchedEffect(liveFix, defaultZoom) {
        val fix = liveFix ?: return@LaunchedEffect
        retained.camTarget = LatLon(fix.lat, fix.lon)
        // Only while there is no road to take the heading from: once the marker
        // loop below is drawing on the route, that loop owns this target and
        // writes the bearing of the segment it snapped to, which is the
        // direction of travel without the fix's noise on a straight or its lag
        // through a junction. `retained.snappedAt` is that loop's own answer
        // rather than a second reading of the same question here — two effects
        // deciding it apart is how the camera ends up with no writer at all.
        // Off route, or not navigating, the fix is all there is — and below
        // 2 m/s a reported bearing is noise, so the last one is held.
        if (retained.snappedAt == null && fix.bearingDeg != null && fix.speedMps > 2.0) {
            retained.camTargetBearing = fix.bearingDeg
        }
        retained.camTargetZoom = NavEngine.cameraZoom(
            defaultZoom.toDouble(),
            fix.speedMps,
            s.navProgress?.distanceToTurnMeters ?: Double.MAX_VALUE,
        )
    }

    // The speedometer ease, and the camera + position-dot loops, in MapCamera.kt.
    MapSpeedEase(retained = retained)

    MapCameraLoops(s = s, retained = retained)
    MapPositionMarker(s = s, retained = retained)

    // Progress, arrival, reroute and the external display, in MapNavigation.kt.
    MapNavigationSession(
        s = s,
        scope = scope,
        announcer = announcer,
        announceAloud = { announceAloud(it) },
        onArrive = { stopNavigation() },
    )

    // The banner, its "then" chip, the bottom bar and the HUD's limit source all
    // read one mapper call, so none of them formats a number of its own.
    // derivedStateOf bounds how often that work happens, not which scopes
    // recompose: the mapper runs — and the clock is read — once per progress
    // tick rather than once per frame. This screen's body still recomposes per
    // tick, as it already does for liveFix and retained.displaySpeedKmh.
    val navState by remember(retained) {
        derivedStateOf {
            navStateFrom(
                progress = s.navProgress,
                navigating = s.navigating,
                rerouting = s.rerouting,
                ambientSpeedLimitKmh = retained.ambientSpeedLimitKmh,
                nowMs = System.currentTimeMillis(),
                // Named here rather than left to the mapper's own default:
                // :shared cannot see NavPolicy, so that default is a second
                // copy of the threshold and would drift without a word.
                offRouteThresholdMeters = NavPolicy.OFF_ROUTE_METERS,
                sep = Settings.decimalSeparatorChar(),
            )
        }
    }

    fun spin() {
        val loc = s.myLocation ?: run {
            s.error = "Waiting for your location…"
            fetchLocation()
            return
        }
        s.spinJob = scope.launch {
            s.spinning = true
            s.error = null
            // The result gets framed on the map; a following camera would drag
            // it straight back to you before you could look at it. SpinStarted
            // parks without stamping the quiet window - see CameraAuthority.reduce:
            // that asymmetry is today's behaviour, kept deliberately.
            s.camAuthority = CameraAuthority.reduce(s.camAuthority, CameraAuthority.Action.SpinStarted)
            try {
                // Producing the result lives in map/SpinRun, with its tests.
                // What stays here is what a *screen* does with one: the buzz,
                // the framing, and which var it lands in.
                val outcome = runSpin(
                    serverConfig, loc,
                    SpinParams(mode, radiusKm, minRadiusKm, poiKind, directionDeg),
                )
                when (outcome) {
                    is SpinOutcome.Loop -> {
                        s.route = outcome.route
                        s.destination = null
                        s.destinationName = null
                        outcome.warning?.let { s.error = it }
                        // A spin result landing is the app's payoff moment; a
                        // small buzz marks it without needing eyes on the screen.
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        mapLibreMap?.let {
                            cameraForPoints(
                                it, outcome.route.polyline + loc,
                                FIT_PADDING_PX, fitBottomPaddingPx,
                            )
                        }
                    }
                    is SpinOutcome.Candidates -> {
                        s.candidates = outcome.candidates
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        mapLibreMap?.let {
                            cameraForPoints(
                                it, outcome.candidates.map { c -> c.destination } + loc,
                                FIT_PADDING_PX, fitBottomPaddingPx,
                            )
                        }
                    }
                    is SpinOutcome.Failed -> s.error = outcome.message
                }
            } finally {
                s.spinning = false
            }
        }
    }

    /**
     * Switch travel mode — from the spin sheet, where every consequence is
     * visible at once (the routing profile, loop vs. destination, the slider
     * range), or from the navigation dock, where it re-picks the profile for
     * a destination already chosen.
     *
     * The radius is reset to the new mode's own default rather than carried
     * over or clamped. The two ranges barely overlap (Car 5-100 km, Moto
     * 30-400 km), so a carried-over value is either outside the new range
     * entirely - a 25 km car radius is below Moto's 30 km floor, and the
     * slider would render its thumb off the track - or a number that meant
     * something different in the mode it was chosen in: for Moto the slider
     * is total loop length, not a radius. A default is in range by
     * construction and means what it says. `minRadiusKm` goes with it,
     * because its own range is `0f..radiusKm`.
     */
    fun selectMode(m: TravelMode) {
        // The rule about what a mode change invalidates lives in map/ModeSwitch,
        // with its tests; null is the "already in that mode" no-op.
        val next = modeSwitch(from = mode, to = m, hasSpinOffer = spinOffer != null)
            ?: return
        Settings.setTripMode(m)
        radiusKm = next.radiusKm
        minRadiusKm = next.minRadiusKm
        // A concrete destination survives; its route does not — a moto profile
        // and a car profile reach the same place by different roads, so the
        // dock drops the fetched route here and startNavigation() re-fetches
        // on the new profile. A loop spin's route (destination == null) is
        // cleared by the same line.
        s.route = next.route
        s.candidates = next.candidates
        if (next.clearSpinOffer) ConvoyLiveClient.clearSpinOffer()
    }

    // The card a tapped rider marker opens (#156). Only the id lives in state;
    // the contents are re-derived here from the live convoy-peer and circle-fix
    // collections every recomposition, so a fresh position frame updates an
    // open card in place — and a convoy peer past its expiry arrives with
    // RiderCardState.stale set. A tapped id in neither collection any more (the
    // peer left, the circle stopped sharing) closes the card.
    val riderCard: RiderCardState? = s.tappedRider?.let { id ->
        val nowMs = System.currentTimeMillis()
        val sep = Settings.decimalSeparatorChar()
        val peer = convoyPeers[id]
        when {
            peer != null -> riderCardStateFrom(
                handle = activeConvoyMembers.handleFor(id),
                riderLocation = LatLon(peer.lat, peer.lon),
                headingDeg = peer.headingDeg,
                speedKmh = peer.speedKmh,
                fixTsMs = peer.tsMs,
                expiresAtMs = peer.expiresAtMs,
                ownLocation = s.myLocation,
                nowMs = nowMs,
                sep = sep,
                vehicleName = peer.vehicleName,
            )
            else -> s.circleFixes.firstOrNull { it.fix.riderId == id }?.let { m ->
                riderCardStateFrom(
                    handle = m.username,
                    riderLocation = LatLon(m.fix.lat, m.fix.lon),
                    headingDeg = null,
                    speedKmh = null,
                    fixTsMs = m.fix.tsMs,
                    expiresAtMs = null,
                    ownLocation = s.myLocation,
                    nowMs = nowMs,
                    sep = sep,
                )
            }
        }
    }
    LaunchedEffect(s.tappedRider, riderCard == null) {
        if (s.tappedRider != null && riderCard == null) s.tappedRider = null
    }
    BackHandler(enabled = s.tappedRider != null) { s.tappedRider = null }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { _ ->
        Box(Modifier.fillMaxSize()) {
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
            // toolbar fades back once it ends. The speed island rides in the
            // same column, under the banner rather than beside it, so the two
            // can never overlap and the island slides down as the banner
            // arrives instead of being drawn through by it.
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(12.dp),
            ) {
                AnimatedVisibility(
                    visible = s.navigating,
                    enter = slideInVertically { -it } + fadeIn(),
                    exit = slideOutVertically { -it } + fadeOut(),
                ) {
                    NavigationBanner(navState, Modifier.fillMaxWidth())
                }
                // Speed, the posted limit and the trajectcontrole average, in
                // one island at the top-left — isHome.html's left:14/top:44,
                // widened so the average keeps its slot.
                //
                // Idle, it sits level with the top chrome's right-hand rail,
                // which needs no offset at all: both are at the chrome's own
                // 12 dp padding and nothing else, and everything that chrome
                // draws — the convoy pill included — is End-aligned, so nothing
                // of it shares this corner. The 50 dp this used to add was the
                // search pill's height plus the gap under it, and the pill has
                // moved into the home sheet. Navigating, the banner above has
                // already pushed the island clear and it only needs the gap.
                //
                // Drawn unconditionally. The standstill fade — "stopping at a
                // light fades the dial out" — is deliberately gone: a parked map
                // now keeps its instruments, as the head unit always has. See
                // the divergence register's entry 18.
                Column(
                    Modifier.padding(top = if (s.navigating) 10.dp else 0.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SpeedHud(
                        state = speedHudStateFrom(
                            speedKmh = retained.displaySpeedKmh,
                            limitKmh = navState.speedLimitKmh,
                            averageKmh = retained.sectionState.reading.averageKmh,
                            averageLimitKmh = retained.sectionState.reading.limitKmh,
                            // Threshold left at its default: it is
                            // SpeedLimitTracker.OVER_LIMIT_TOLERANCE_KMH, the
                            // one the car dial and the trip recorder compare
                            // against too, so naming it here would only be a
                            // second place for it to drift.
                        ),
                    )
                    // Diagnostics: an adapter that fed this trip and has since
                    // dropped. Obd2Connection never resets lastDataAtMs, hence
                    // the shared after-the-start test rather than a per-trip
                    // accumulator.
                    Obd2SignalLostLabel(
                        lost = obd2FedThisTrip(stats?.startTimeMs, obd2LastDataAtMs) &&
                            obd2State != Obd2ConnectionState.CONNECTED,
                    )
                }
            }
            AnimatedVisibility(
                visible = !s.navigating,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(),
            ) {
                MapTopChrome(
                    followMe = s.camAuthority.following,
                    convoyName = if (convoyConnected) s.convoyName else null,
                    layers = MapLayers(
                        open = s.layersOpen,
                        onOpenChange = { s.layersOpen = it },
                        fogEnabled = fogEnabled,
                        onToggleFog = { Settings.setFogEnabled(!fogEnabled) },
                    ),
                    onToggleFollow = {
                        s.camAuthority = CameraAuthority.reduce(
                            s.camAuthority,
                            CameraAuthority.Action.FollowToggled,
                        )
                    },
                    // Offered only while the camera is idle, which is exactly
                    // when the map can hold a bearing — the loop overwrites it
                    // every frame otherwise. `mapLibreMap` is read here rather
                    // than captured: this lambda is rebuilt on recomposition,
                    // so there is no stale-capture hazard to defeat.
                    onFaceNorth = if (s.camAuthority.northUpAvailable(s.navigating)) {
                        { mapLibreMap?.let { levelToNorthUp(it) } }
                    } else null,
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
                visible = pushToTalkShown(
                    featureEnabled = Features.pushToTalk,
                    convoyConnected = convoyConnected,
                    hasActiveConvoy = activeConvoyId != null,
                ),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
            ) {
                PushToTalkButton(talking = convoyTalking.isNotEmpty())
            }

            MapBottomSlot(
                stats = stats,
                onEndTrip = { TripTrackingService.stop(context) },
                rideToggle = SheetToggle(s.rideSheetExpanded) {
                    s.rideSheetExpanded = !s.rideSheetExpanded
                },
                savedPlaces = savedPlaces,
                destination = s.destination,
                destinationName = s.destinationName,
                route = s.route,
                myLocation = s.myLocation,
                serverConfig = serverConfig,
                username = accountUsername,
                onOpenHub = onOpenHub,
                searchOpen = s.searchOpen,
                onSearchOpenChange = { s.searchOpen = it },
                onPickDestination = { r ->
                    s.destination = r.location
                    s.destinationName = r.name
                    s.route = null
                    s.camAuthority = CameraAuthority.reduce(
                        s.camAuthority,
                        CameraAuthority.Action.DestinationFramed(System.currentTimeMillis()),
                    )
                    mapLibreMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(
                        LatLng(r.location.lat, r.location.lon), 14.0), 800)
                },
                onOpenRoutes = onOpenRoutes,
                onOpenSocial = onOpenSocial,
                onPickPlace = { p ->
                    s.destination = p.location
                    s.destinationName = p.name
                    s.route = null
                    s.camAuthority = CameraAuthority.reduce(
                        s.camAuthority,
                        CameraAuthority.Action.DestinationFramed(System.currentTimeMillis()),
                    )
                    mapLibreMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(
                        LatLng(p.location.lat, p.location.lon), 14.0), 600)
                },
                onSavePin = { s.destination?.let { s.savePinTarget = it } },
                bottomCard = bottomCard,
                navState = navState,
                onExitNavigation = { stopNavigation() },
                displayCandidates = visibleCandidates,
                // Non-null only once a spin has actually been shared.
                convoyVotes = spinOffer?.let { spinVotes },
                activeConvoyMembers = activeConvoyMembers,
                onPickCandidate = { index, c ->
                    if (spinOffer != null) ConvoyLiveClient.sendSpinVote(index) else choose(c)
                },
                onReroll = { s.candidates = emptyList(); spin() },
                onCancelCandidates = {
                    s.candidates = emptyList()
                    if (spinOffer != null) ConvoyLiveClient.clearSpinOffer()
                },
                onShare = if (activeConvoyId != null && spinOffer == null && s.candidates.isNotEmpty()) {
                    { ConvoyLiveClient.sendSpinOffer(s.candidates.asSpinCandidates()) }
                } else null,
                onGoWithLead = spinOffer?.takeIf { it.fromMe }?.let { offer ->
                    {
                        ConvoyLiveClient.sendSpinOffer(listOf(
                            offer.candidates[
                                ConvoyLiveClient.currentLeadIndex(offer.candidates.size)]))
                    }
                },
                mode = mode,
                // A refusal goes to the snackbar, not to `error`: that field
                // renders as a red line inside the sheet, which a "not right
                // now" is not, and its LaunchedEffect re-keys on value, so a
                // second identical refusal in a row would raise nothing at
                // all. Replace rather than queue - two refused taps are one
                // situation, not a backlog to sit through.
                onSelectMode = { m ->
                    // Navigation and an open candidate round need no entry
                    // here: both replace the spin sheet with a different card
                    // in the same slot, so the control is not on screen.
                    val blocked = ModeSwipePolicy.blockedReason(
                        spinning = s.spinning,
                        tracking = stats != null,
                    )
                    if (blocked == null) selectMode(m) else scope.launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showSnackbar(blocked)
                    }
                },
                radiusKm = radiusKm,
                onRadiusChange = { radiusKm = it },
                minRadiusKm = minRadiusKm,
                onMinRadiusChange = { minRadiusKm = it },
                poiKind = poiKind,
                onPoiKindChange = { poiKind = it },
                directionDeg = directionDeg,
                onDirectionChange = { directionDeg = it },
                spinning = s.spinning,
                error = s.error,
                onSpin = { if (s.spinning) s.spinJob?.cancel() else spin() },
                onExpand = { settingsCollapsed = false },
                onCollapse = { settingsCollapsed = true },
                onNavigateInApp = { startNavigation() },
                onNavigate = {
                    if (stats == null) {
                        TripTrackingService.start(context, s.destination?.lat, s.destination?.lon)
                    }
                },
                onTrack = {
                    TripTrackingService.start(context, s.destination?.lat, s.destination?.lon)
                },
                // The navigation dock's ✕: drop the destination and everything
                // derived from it. `settingsCollapsed` is left untouched, so
                // the slot falls back to whatever the Spin chip last set —
                // COLLAPSED at rest, the spin sheet only if the rider had it
                // open before picking.
                onClearDestination = {
                    s.destination = null
                    s.destinationName = null
                    s.route = null
                },
            )

            // Drawn last, so it floats over the bottom slot: a tap on a rider
            // marker asked for it, and Back or a tap elsewhere takes it away
            // again (#156). Camera follow/park is not touched here.
            AnimatedVisibility(
                visible = riderCard != null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(12.dp)
                    .widthIn(max = 420.dp),
            ) {
                // Held past the dismiss so the card animates out with content.
                val shown = remember { mutableStateOf(riderCard) }
                riderCard?.let { shown.value = it }
                shown.value?.let { card ->
                    RiderCard(state = card, onDismiss = { s.tappedRider = null })
                }
            }
        }
    }

    // The two dialogs and the state they read live together in MapDialogs.kt;
    // this screen just says when they are up.
    MapScreenDialogs(s = s, bgLocationLauncher = bgLocationLauncher)

}

package com.jellemax.detour.car

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Distance
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.NavEngine
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.data.RouteCandidate
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.data.RoutingServer
import com.jellemax.detour.data.ServerConfig
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.SpeedCameras
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.net.ConvoyLiveClient
import com.jellemax.detour.tracking.TripTrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

private const val ARRIVE_METERS = 40.0
private const val OFF_ROUTE_METERS = 60.0
private const val REROUTE_COOLDOWN_MS = 15_000L
private const val CAMERA_FETCH_MARGIN_M = 1000.0
private const val CAMERA_FETCH_THROTTLE_MS = 15_000L

/**
 * The actual turn-by-turn screen, pushed from [SpinScreen] once a candidate
 * has real turn data. Runs its own independent nav loop against
 * [TripTrackingService.lastFix] — deliberately not shared with MapScreen's
 * Compose-local navigation state (see the plan's Context: a shared session
 * would be a bigger, riskier refactor for no v1 benefit, since starting nav
 * here doesn't need to know what the phone screen is doing, same as the
 * existing wear/BLE relays each drive themselves).
 */
class NavScreen(
    carContext: CarContext,
    private val origin: LatLon,
    private val destination: LatLon,
    initialRoute: RouteResult,
    private val serverConfig: ServerConfig,
) : Screen(carContext) {

    private val renderer = CarMapRenderer(carContext, carContext.isDarkMode())
    private val navigationManager = carContext.getCarService(NavigationManager::class.java)
    private val toneGen = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull()

    private var route = initialRoute
    private var progress: NavEngine.Progress? = null
    private var currentSpeedKmh = 0.0
    private var rerouting = false
    private var lastRerouteMs = 0L

    private var speedCameras: List<SpeedCameras.Camera> = emptyList()
    private var camerasCenter: LatLon? = null
    private var lastCameraFetchMs = 0L
    private var warnedCameraAt: LatLon? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // The car can be the only thing driving this trip — the phone
                // UI may never be opened — and every position on this screen
                // comes from TripTrackingService.lastFix, so without this the
                // map never leaves the world view and the HUD stays empty.
                TripTrackingService.start(carContext, destination.lat, destination.lon)
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
                // navigationStarted() throws unless a callback is registered
                // first. onStopNavigation fires when the host hands navigation
                // to another app, which for us means leaving this screen.
                navigationManager.setNavigationManagerCallback(object : NavigationManagerCallback {
                    override fun onStopNavigation() {
                        screenManager.pop()
                    }
                })
                navigationManager.navigationStarted()
            }
            // Covers every way this screen leaves the front of the stack —
            // the Exit action, arrival, and the car's own back control alike.
            override fun onStop(owner: LifecycleOwner) {
                navigationManager.navigationEnded()
                navigationManager.clearNavigationManagerCallback()
            }
            override fun onDestroy(owner: LifecycleOwner) {
                renderer.destroy()
                toneGen?.release()
            }
        })
        lifecycleScope.launch {
            TripTrackingService.lastFix.collect { fix ->
                fix ?: return@collect
                onFix(LatLon(fix.lat, fix.lon), fix.bearingDeg, fix.speedMps)
            }
        }
        lifecycleScope.launch {
            ConvoyLiveClient.peers.collect { peers -> renderer.overlays?.setFriends(peers.values) }
        }
    }

    private suspend fun onFix(pos: LatLon, bearingDeg: Float?, speedMps: Double) {
        currentSpeedKmh = speedMps * 3.6
        val p = NavEngine.progress(route, pos) ?: return
        progress = p
        renderer.updatePosition(pos, bearingDeg ?: 0f, currentSpeedKmh, p.speedLimitKmh,
            Settings.defaultZoom.value.toDouble())
        renderer.overlays?.render(
            myLocation = pos, destination = destination, routePolyline = route.polyline,
            reachMeters = null, directionDeg = null, candidates = emptyList(), showPosition = true)
        checkCameras(pos, bearingDeg?.toDouble())
        invalidate()

        // Same arrival/reroute policy as MapScreen.kt's navigating LaunchedEffect.
        if (p.remainingMeters < ARRIVE_METERS && p.offRouteMeters < OFF_ROUTE_METERS) {
            screenManager.pop()
            return
        }
        val now = System.currentTimeMillis()
        if (p.offRouteMeters > OFF_ROUTE_METERS && !rerouting && now - lastRerouteMs > REROUTE_COOLDOWN_MS) {
            rerouting = true
            lastRerouteMs = now
            lifecycleScope.launch {
                try {
                    route = withContext(Dispatchers.IO) {
                        RoutingServer.route(serverConfig, pos, destination, TravelMode.CAR.ghProfile,
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

    private suspend fun checkCameras(pos: LatLon, headingDeg: Double?) {
        val fromCenter = camerasCenter?.let { RoadRoulette.distanceMeters(it, pos) } ?: Double.MAX_VALUE
        val now = System.currentTimeMillis()
        if (fromCenter > SpeedCameras.PREFETCH_RADIUS_M - CAMERA_FETCH_MARGIN_M &&
            now - lastCameraFetchMs > CAMERA_FETCH_THROTTLE_MS
        ) {
            lastCameraFetchMs = now
            val result = withContext(Dispatchers.IO) { SpeedCameras.near(pos) }
            if (result != null) {
                speedCameras = result.cameras
                camerasCenter = pos
                renderer.overlays?.setCameras(speedCameras)
            }
        }
        val ahead = speedCameras.filter { cam ->
            RoadRoulette.distanceMeters(pos, cam.at) <= SpeedCameras.WARN_METERS &&
                (headingDeg == null || RoadRoulette.withinWedge(pos, cam.at, headingDeg, 45.0))
        }.minByOrNull { RoadRoulette.distanceMeters(pos, it.at) }
        if (ahead == null) {
            warnedCameraAt = null
            return
        }
        val limit = progress?.speedLimitKmh
        val tooFast = limit != null && currentSpeedKmh > limit + 3.0
        if (tooFast && ahead.at != warnedCameraAt) {
            toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
            carContext.getCarService(AppManager::class.java)
                .showToast("Speed camera ahead", CarToast.LENGTH_SHORT)
            warnedCameraAt = ahead.at
        }
    }

    override fun onGetTemplate(): Template {
        val builder = NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.Builder().setTitle("Exit")
                        .setOnClickListener { screenManager.pop() }.build())
                    .build()
            )
        val p = progress
        if (p != null) {
            val step = Step.Builder(p.nextInstruction?.text ?: "Continue")
                .setManeuver(Maneuver.Builder(maneuverType(p.nextInstruction?.sign ?: 0)).build())
                .build()
            builder.setNavigationInfo(
                RoutingInfo.Builder()
                    .setCurrentStep(step, carDistance(p.distanceToTurnMeters.coerceAtLeast(0.0)))
                    .build()
            )
            val remainingSec = (p.remainingTimeMs ?: 0L) / 1000
            builder.setDestinationTravelEstimate(
                TravelEstimate.Builder(
                    carDistance(p.remainingMeters),
                    ZonedDateTime.now().plusSeconds(remainingSec),
                ).setRemainingTimeSeconds(remainingSec).build()
            )
        }
        return builder.build()
    }

    companion object {
        /** Builds a [NavScreen] for [candidate], or null if it has no turn
         *  data to navigate with — caller falls back to the external handoff. */
        fun forCandidate(
            carContext: CarContext, origin: LatLon, candidate: RouteCandidate, serverConfig: ServerConfig,
        ): NavScreen? {
            val route = candidate.route ?: return null
            if (route.instructions.isEmpty()) return null
            return NavScreen(carContext, origin, candidate.destination, route, serverConfig)
        }
    }
}

private fun carDistance(meters: Double): Distance = if (meters >= 1000.0)
    Distance.create(meters / 1000.0, Distance.UNIT_KILOMETERS_P1)
else
    Distance.create(meters, Distance.UNIT_METERS)

/** GraphHopper sign code → car maneuver type, same table as
 *  ui/Navigation.kt's signIcon(). Roundabout direction assumes right-hand
 *  traffic (Benelux) — CCW when entering. */
private fun maneuverType(sign: Int): Int = when (sign) {
    -98, -8 -> Maneuver.TYPE_U_TURN_LEFT
    8 -> Maneuver.TYPE_U_TURN_RIGHT
    -7 -> Maneuver.TYPE_FORK_LEFT
    7 -> Maneuver.TYPE_FORK_RIGHT
    -3 -> Maneuver.TYPE_TURN_SHARP_LEFT
    -2 -> Maneuver.TYPE_TURN_NORMAL_LEFT
    -1 -> Maneuver.TYPE_TURN_SLIGHT_LEFT
    1 -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
    2 -> Maneuver.TYPE_TURN_NORMAL_RIGHT
    3 -> Maneuver.TYPE_TURN_SHARP_RIGHT
    4, 5 -> Maneuver.TYPE_DESTINATION
    6 -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
    else -> Maneuver.TYPE_STRAIGHT
}

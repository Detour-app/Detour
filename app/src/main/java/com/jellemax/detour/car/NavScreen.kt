package com.jellemax.detour.car

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarText
import androidx.car.app.model.Distance
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.jellemax.detour.R
import com.jellemax.detour.audio.NavVoice
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.NavAnnouncer
import com.jellemax.detour.data.NavEngine
import com.jellemax.detour.data.NavInstruction
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.data.RouteCandidate
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.data.RoutingServer
import com.jellemax.detour.data.ServerConfig
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.SpeedCameras
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.map.NavPolicy
import com.jellemax.detour.tracking.TripTrackingService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import kotlin.math.max
import kotlin.math.roundToLong

private const val TAG = "DetourNav"

private const val CAMERA_FETCH_MARGIN_M = 1000.0
private const val CAMERA_FETCH_THROTTLE_MS = 15_000L

/** Fallback pace for the "time to the next turn" estimate when the router gave
 *  no travel time, ~50 km/h. Only feeds the cluster's step ETA. */
private const val FALLBACK_MPS = 14.0

/**
 * The actual turn-by-turn screen, pushed from [SpinScreen] once a candidate
 * has real turn data. Runs its own independent nav loop against
 * [TripTrackingService.lastFix] — deliberately not shared with MapScreen's
 * Compose-local navigation state (see the plan's Context: a shared session
 * would be a bigger, riskier refactor for no v1 benefit, since starting nav
 * here doesn't need to know what the phone screen is doing, same as the
 * existing wear/BLE relays each drive themselves).
 *
 * Turn-by-turn on a head unit is three separate things, and this screen owes
 * all three:
 *
 *  - the **template**, which is what the car draws while Detour is the app on
 *    screen ([onGetTemplate]);
 *  - the **trip**, pushed to the host through [NavigationManager.updateTrip],
 *    which is what feeds the instrument cluster and the host's own turn card
 *    when the driver is looking at some other car app;
 *  - the **voice**, via [NavVoice] — the only one of the three that works while
 *    you are watching the road.
 */
class NavScreen(
    carContext: CarContext,
    /** The session's map — shared with [SpinScreen]'s free-drive view, so the
     *  car surface keeps drawing across the push and the pop. */
    private val renderer: CarMapRenderer,
    private val origin: LatLon,
    private val destination: LatLon,
    initialRoute: RouteResult,
    private val serverConfig: ServerConfig,
    private val destinationName: String? = null,
) : Screen(carContext) {

    private val navigationManager = carContext.getCarService(NavigationManager::class.java)
    private val voice = NavVoice(carContext)

    /** The ladder, the latch and the wording: `:shared`'s, so the head unit,
     *  the phone and iOS cannot word the same maneuver differently. One per
     *  session — it holds per-instruction state. */
    private val announcer = NavAnnouncer()

    private val toneGen = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull()

    private var route = initialRoute
    private var progress: NavEngine.Progress? = null
    private var currentSpeedKmh = 0.0
    private var rerouting = false
    private var lastRerouteMs = 0L

    /** True between [NavigationManager.navigationStarted] and its end. The
     *  manager throws on updateTrip/clearCallback in the wrong state, and the
     *  host can end navigation from its side at any moment, so the state is
     *  tracked rather than assumed. */
    private var navigating = false
    private var arrived = false

    /** What the template last showed, so an unchanged screen isn't rebuilt and
     *  re-sent over the projection link once a second. */
    private var templateKey: String? = null

    private var speedCameras: List<SpeedCameras.Camera> = emptyList()
    private var camerasCenter: LatLon? = null
    private var lastCameraFetchMs = 0L
    private var warnedCameraAt: LatLon? = null
    /** The in-flight Overpass fetch, so a slow mirror is waited on once rather
     *  than re-requested by every fix that lands while it is still running. */
    private var cameraFetchJob: Job? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // The car can be the only thing driving this trip — the phone
                // UI may never be opened — and every position on this screen
                // comes from TripTrackingService.lastFix, so without this the
                // map never leaves the world view and the HUD stays empty.
                // Wrapped: from Android 12 a foreground service started while
                // the app itself is in the background throws, and a phone
                // sitting locked in a cradle is exactly that. Losing the trip
                // recording is survivable; taking the car app down mid-drive
                // with it is not.
                runCatching { TripTrackingService.start(carContext, destination.lat, destination.lon) }
                    .onFailure { Log.w(TAG, "could not start trip tracking", it) }
                // navigationStarted() throws unless a callback is registered
                // first. onStopNavigation fires when the host hands navigation
                // to another app, which for us means leaving this screen.
                navigationManager.setNavigationManagerCallback(object : NavigationManagerCallback {
                    override fun onStopNavigation() {
                        // The host has already torn navigation down on its
                        // side; navigationEnded() must not follow it.
                        navigating = false
                        voice.stop()
                        screenManager.pop()
                    }
                })
                runCatching {
                    navigationManager.navigationStarted()
                    navigating = true
                }.onFailure {
                    // Silence here is the worst outcome: the host refuses the
                    // start when another app already owns navigation on this
                    // head unit, and the visible symptom is simply no turn card
                    // and no cluster guidance, with the map still moving — which
                    // reads as "the banner is missing" rather than "Google Maps
                    // is still navigating".
                    Log.w(TAG, "navigationStarted failed", it)
                    runCatching {
                        carContext.getCarService(AppManager::class.java).showToast(
                            "Another app is navigating — stop it to get turn guidance here",
                            CarToast.LENGTH_LONG,
                        )
                    }
                }
                renderer.setRoute(route.polyline, destination)
            }
            // Covers every way this screen leaves the front of the stack —
            // the Exit action, arrival, and the car's own back control alike.
            override fun onStop(owner: LifecycleOwner) {
                if (navigating) {
                    navigating = false
                    runCatching { navigationManager.navigationEnded() }
                }
                runCatching { navigationManager.clearNavigationManagerCallback() }
                voice.stop()
                // The map outlives this screen — hand it back to free drive
                // without the finished route still drawn on it.
                renderer.setRoute(null, null)
            }
            override fun onDestroy(owner: LifecycleOwner) {
                // Not renderer.destroy(): the session owns it.
                toneGen?.release()
                voice.shutdown()
            }
        })
        lifecycleScope.launch {
            TripTrackingService.lastFix.collect { fix ->
                fix ?: return@collect
                // One bad fix must not take the app with it. Everything below
                // runs against live network and host state — a reroute, an
                // Overpass call, a template the host rejects — and an exception
                // escaping here reaches the coroutine's default handler, which
                // means the process dies in the middle of a drive.
                try {
                    onFix(LatLon(fix.lat, fix.lon), fix.bearingDeg, fix.speedMps)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "nav update failed", e)
                }
            }
        }
    }

    private fun onFix(pos: LatLon, bearingDeg: Float?, speedMps: Double) {
        currentSpeedKmh = speedMps * 3.6
        val p = NavEngine.progress(route, pos) ?: return
        progress = p

        renderer.follow(
            pos, bearingDeg, speedMps,
            NavEngine.cameraZoom(
                Settings.defaultZoom.value.toDouble(), speedMps, p.distanceToTurnMeters),
        )
        renderer.updateHud(currentSpeedKmh, p.speedLimitKmh)
        renderer.setPosition(pos, bearingDeg?.takeIf { speedMps > 2.0 })
        renderer.setDrivenFraction(p.drivenFraction)

        announce(p)
        pushTrip(p)
        refreshTemplate(p)
        checkCameras(pos, bearingDeg?.toDouble())

        // Arrival and reroute are NavPolicy's call, shared with MapScreen.kt's
        // navigating LaunchedEffect. `arrived` stays here: it is this screen's
        // own once-only latch on popping itself, not part of the policy.
        val now = System.currentTimeMillis()
        when (NavPolicy.decide(
            progress = p,
            hasDestination = true, // a constructor parameter on this screen
            rerouting = rerouting,
            lastRerouteMs = lastRerouteMs,
            nowMs = now,
        )) {
            NavPolicy.Decision.Arrived -> if (!arrived) {
                arrived = true
                screenManager.pop()
            }
            NavPolicy.Decision.Reroute -> {
                rerouting = true
                lastRerouteMs = now
                speak(announcer.rerouting())
                lifecycleScope.launch {
                    try {
                        val fresh = withContext(Dispatchers.IO) {
                            RoutingServer.route(serverConfig, pos, destination, TravelMode.CAR.ghProfile,
                                Settings.avoidHighways.value, Settings.avoidSmallRoads.value)
                        }
                        route = fresh
                        // The line on the map is only pushed when it changes, so a
                        // reroute is the one moment it has to be pushed again.
                        renderer.setRoute(fresh.polyline, destination)
                        // Instruction indices belong to the old polyline; start the
                        // prompts for the new one from scratch, "Rerouting" followed
                        // by what the new line asks for next.
                        announcer.routeChanged()
                        templateKey = null
                    } catch (e: Exception) {
                        // stay on the old line; retried after the cooldown
                        Log.w(TAG, "reroute failed", e)
                    } finally {
                        rerouting = false
                    }
                }
            }
            NavPolicy.Decision.Continue -> {}
        }
    }

    // ---- voice ------------------------------------------------------------

    private fun speak(text: String) {
        if (Settings.voiceGuidance.value) voice.speak(text)
    }

    /** Speaks whatever [NavAnnouncer] says is due for this fix. The decision
     *  and the words are the core's; this screen only decides that speech is
     *  how the head unit delivers them. */
    private fun announce(p: NavEngine.Progress) {
        announcer.onProgress(p.nextInstruction, p.distanceToTurnMeters)?.let { speak(it) }
    }

    // ---- host state -------------------------------------------------------

    /**
     * Pushes the trip to the host: the cluster display, the head unit's own
     * turn card and the "navigating" state of the car's UI all come from this,
     * not from the template — which is why the car showed no turn-by-turn of
     * its own no matter what the Detour screen was drawing.
     */
    private fun pushTrip(p: NavEngine.Progress) {
        if (!navigating) return
        val step = stepFor(p.nextInstruction) ?: return
        val remainingSec = ((p.remainingTimeMs ?: 0L) / 1000).coerceAtLeast(0)
        val stepSec = secondsFor(p, p.distanceToTurnMeters)
        val trip = runCatching {
            val builder = Trip.Builder()
                .addDestination(
                    Destination.Builder().setName(destinationLabel()).build(),
                    travelEstimate(p.remainingMeters, remainingSec),
                )
                .addStep(step, travelEstimate(p.distanceToTurnMeters, stepSec))
            val nextStep = stepFor(p.nextNextInstruction)
            val nextDistance = p.distanceToNextNextMeters
            if (nextStep != null && nextDistance != null) {
                builder.addStep(nextStep, travelEstimate(nextDistance, secondsFor(p, nextDistance)))
            }
            builder.build()
        }.getOrElse {
            Log.w(TAG, "could not build trip", it)
            return
        }
        runCatching { navigationManager.updateTrip(trip) }
            .onFailure { Log.w(TAG, "updateTrip failed", it) }
    }

    /** Rebuilds the template only when what it shows would actually differ.
     *  The host redraws on every invalidate(), and at one fix a second an
     *  identical redraw is pure cost on the projection link. */
    private fun refreshTemplate(p: NavEngine.Progress) {
        val key = buildString {
            append(p.nextInstruction?.startIndex).append('|')
            append(p.nextInstruction?.text).append('|')
            append(displayMeters(p.distanceToTurnMeters)).append('|')
            append(displayMeters(p.remainingMeters)).append('|')
            append((p.remainingTimeMs ?: 0L) / 60_000).append('|')
            // Part of the key, not just of the template: leaving the route has
            // to redraw even when nothing else moved, and a car stopped just
            // off the line moves none of the five values above.
            append(offRoute(p))
        }
        if (key == templateKey) return
        templateKey = key
        invalidate()
    }

    /**
     * Keeps the prefetched camera set current, and warns about the next one
     * ahead.
     *
     * The Overpass fetch runs in its own coroutine rather than inline. This is
     * the whole fix loop's hot path: [TripTrackingService.lastFix] is a
     * StateFlow and its collector is sequential, so awaiting a mirror *here*
     * suspended [onFix] itself — and with it the camera target, the HUD and the
     * turn card — for however long Overpass took, while every fix that landed
     * meanwhile was conflated away. A mirror having a slow ten seconds is
     * normal; a map that stops moving for ten seconds at 100 km/h is not, and
     * that is what made it look like the map had simply stopped updating.
     */
    private fun checkCameras(pos: LatLon, headingDeg: Double?) {
        val fromCenter = camerasCenter?.let { RoadRoulette.distanceMeters(it, pos) } ?: Double.MAX_VALUE
        val now = System.currentTimeMillis()
        if (fromCenter > SpeedCameras.PREFETCH_RADIUS_M - CAMERA_FETCH_MARGIN_M &&
            now - lastCameraFetchMs > CAMERA_FETCH_THROTTLE_MS &&
            cameraFetchJob?.isActive != true
        ) {
            lastCameraFetchMs = now
            cameraFetchJob = lifecycleScope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) { SpeedCameras.near(pos) }
                }.onFailure { Log.w(TAG, "camera fetch failed", it) }.getOrNull()
                if (result != null) {
                    speedCameras = result.cameras
                    camerasCenter = pos
                    renderer.setCameras(speedCameras)
                }
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
            // The toast is on the car screen and the tone is on the phone's
            // notification stream; only the spoken one reaches a driver who is
            // looking at the road with the radio on.
            speak("Speed camera ahead")
            carContext.getCarService(AppManager::class.java)
                .showToast("Speed camera ahead", CarToast.LENGTH_SHORT)
            warnedCameraAt = ahead.at
        }
    }

    // ---- template ---------------------------------------------------------

    override fun onGetTemplate(): Template {
        val builder = NavigationTemplate.Builder().setActionStrip(actionStrip())
        val p = progress
        if (p == null) {
            // Before the first fix there is nothing honest to show. A loading
            // routing card is the host's own "working on it", and beats the
            // blank template that used to sit there until GPS arrived.
            builder.setNavigationInfo(RoutingInfo.Builder().setLoading(true).build())
            return builder.build()
        }
        val step = stepFor(p.nextInstruction)
        if (step == null) {
            // No instruction left, or a cue the host wouldn't build a Step from.
            // Leaving navigationInfo unset drops the entire turn card, so the
            // screen goes blank rather than degrading; a loading card keeps it
            // on screen.
            builder.setNavigationInfo(RoutingInfo.Builder().setLoading(true).build())
        } else {
            val info = RoutingInfo.Builder().setCurrentStep(step, carDistance(p.distanceToTurnMeters))
            stepFor(p.nextNextInstruction)?.let { info.setNextStep(it) }
            builder.setNavigationInfo(info.build())
        }
        val remainingSec = ((p.remainingTimeMs ?: 0L) / 1000).coerceAtLeast(0)
        builder.setDestinationTravelEstimate(
            travelEstimate(p.remainingMeters, remainingSec, offRoute = offRoute(p)))
        return builder.build()
    }

    private fun actionStrip(): ActionStrip {
        val voiceOn = Settings.voiceGuidance.value
        return ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(
                        CarIcon.Builder(
                            IconCompat.createWithResource(
                                carContext,
                                if (voiceOn) R.drawable.ic_car_volume_on
                                else R.drawable.ic_car_volume_off,
                            ),
                        ).build(),
                    )
                    .setOnClickListener {
                        Settings.setVoiceGuidance(!voiceOn)
                        if (voiceOn) voice.stop()
                        invalidate()
                    }
                    .build(),
            )
            // The strip allows a single action with a custom title, and that
            // one is Exit; anything else added here has to be an icon.
            .addAction(
                Action.Builder().setTitle("Exit")
                    .setOnClickListener { screenManager.pop() }.build(),
            )
            .build()
    }

    private fun destinationLabel(): String =
        destinationName?.takeIf { it.isNotBlank() } ?: "Destination"

    /** Off the drawn line far enough that [NavPolicy] would ask for a fresh
     *  route. The same bound the phone's nav bar reads
     *  (`ui/MapScreen.kt:1426-1427`) and the same one [NavPolicy.decide]
     *  reroutes on, so what the driver is told cannot disagree with what the
     *  policy decided. Entry 8 of the divergence register is precisely that
     *  this bound is named once. */
    private fun offRoute(p: NavEngine.Progress): Boolean =
        p.offRouteMeters > NavPolicy.OFF_ROUTE_METERS

    /**
     * [offRoute] defaults to false so [pushTrip]'s three call sites keep a
     * zero-line diff: those estimates go to the instrument cluster through
     * [NavigationManager.updateTrip], which is a fourth drawing surface and not
     * part of this change.
     */
    private fun travelEstimate(
        meters: Double,
        seconds: Long,
        offRoute: Boolean = false,
    ): TravelEstimate {
        val builder = TravelEstimate
            .Builder(carDistance(meters), ZonedDateTime.now().plusSeconds(seconds))
            .setRemainingTimeSeconds(seconds)
        if (offRoute) {
            // Two signals, because the words need a newer host than the colour
            // does: setTripText is @RequiresCarApi(5) while
            // AndroidManifest.xml:56-57 declares minCarApiLevel 1, so on an
            // older head unit the red readouts *are* the indicator. Colouring
            // both matches the phone, which turns the same string
            // error-coloured (`ui/Navigation.kt:195-200`).
            //
            // Persistent and not a toast on purpose: the defect being fixed is
            // that the one spoken "Rerouting" at :258 leaves a driver who
            // missed it with no way to tell.
            builder.setRemainingDistanceColor(CarColor.RED)
            builder.setRemainingTimeColor(CarColor.RED)
            if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_5) {
                builder.setTripText(CarText.create("Off route"))
            }
        }
        return builder.build()
    }

    /** Travel time over [meters] of what's left, at the pace the router implied
     *  for the rest of the route (or [FALLBACK_MPS] when it gave no time). */
    private fun secondsFor(p: NavEngine.Progress, meters: Double): Long {
        val totalMs = p.remainingTimeMs
        val seconds = if (totalMs != null && p.remainingMeters > 1.0)
            (totalMs / 1000.0) * (meters / p.remainingMeters)
        else meters / FALLBACK_MPS
        return seconds.roundToLong().coerceAtLeast(0)
    }

    companion object {
        /** Builds a [NavScreen] for [candidate], or null if it has no turn
         *  data to navigate with — caller falls back to the external handoff. */
        fun forCandidate(
            carContext: CarContext, renderer: CarMapRenderer, origin: LatLon,
            candidate: RouteCandidate, serverConfig: ServerConfig,
        ): NavScreen? {
            val route = candidate.route ?: return null
            if (route.instructions.isEmpty()) return null
            return NavScreen(
                carContext, renderer, origin, candidate.destination, route, serverConfig, candidate.name)
        }
    }
}

private fun carDistance(meters: Double): Distance {
    val safe = if (meters.isNaN()) 0.0 else max(0.0, meters)
    return if (safe >= 1000.0) Distance.create(safe / 1000.0, Distance.UNIT_KILOMETERS_P1)
    else Distance.create(safe, Distance.UNIT_METERS)
}

/** Distance as the template shows it — to 10 m up close, 100 m further out.
 *  Used to decide whether a redraw would change anything. */
private fun displayMeters(meters: Double): Long =
    if (meters < 1000.0) (meters / 10.0).roundToLong() * 10
    else (meters / 100.0).roundToLong() * 100

/** A car [Step] for [instruction]: the spoken/written cue plus a maneuver icon.
 *  Null when there is no instruction to show. */
private fun stepFor(instruction: NavInstruction?): Step? {
    instruction ?: return null
    val cue = instruction.text.ifBlank { "Continue" }
    return runCatching {
        Step.Builder(cue)
            .apply { maneuverFor(instruction)?.let { setManeuver(it) } }
            .build()
    }.getOrNull()
}

/**
 * The car maneuver for a GraphHopper instruction.
 *
 * Roundabouts are why this returns a nullable [Maneuver] built inside a
 * runCatching rather than a bare type int: the enter-and-exit types are the
 * only ones the host will not accept without an exit number, and
 * `Maneuver.Builder(TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW).build()` throws
 * `IllegalArgumentException("Maneuver missing roundaboutExitNumber")`. Building
 * one on the first roundabout of the drive is what was killing the car app —
 * GraphHopper *does* send the exit number (`NavInstruction.exitNumber`), it was
 * simply never passed on, and where it is missing (0, or negative when the
 * router can't tell) the plain enter type is used instead.
 */
private fun maneuverFor(instruction: NavInstruction): Maneuver? {
    val exit = instruction.exitNumber
    val type = maneuverType(instruction.sign, exit)
    return runCatching {
        Maneuver.Builder(type)
            .apply {
                if (type == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW ||
                    type == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
                ) {
                    setRoundaboutExitNumber(exit)
                }
            }
            .build()
    }.getOrNull()
}

/** GraphHopper sign code → car maneuver type, same table as
 *  ui/Navigation.kt's signIcon(). Roundabout direction assumes right-hand
 *  traffic (Benelux) — CCW when entering. */
private fun maneuverType(sign: Int, exitNumber: Int): Int = when (sign) {
    -98, -8 -> Maneuver.TYPE_U_TURN_LEFT
    8 -> Maneuver.TYPE_U_TURN_RIGHT
    -7 -> Maneuver.TYPE_KEEP_LEFT
    7 -> Maneuver.TYPE_KEEP_RIGHT
    -6 -> Maneuver.TYPE_ROUNDABOUT_EXIT_CCW
    -3 -> Maneuver.TYPE_TURN_SHARP_LEFT
    -2 -> Maneuver.TYPE_TURN_NORMAL_LEFT
    -1 -> Maneuver.TYPE_TURN_SLIGHT_LEFT
    1 -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
    2 -> Maneuver.TYPE_TURN_NORMAL_RIGHT
    3 -> Maneuver.TYPE_TURN_SHARP_RIGHT
    4, 5 -> Maneuver.TYPE_DESTINATION
    6 -> {
        if (exitNumber > 0) Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
        else Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
    }
    else -> Maneuver.TYPE_STRAIGHT
}

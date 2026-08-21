package com.jellemax.detour.car

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.location.LocationServices
import com.jellemax.detour.R
import com.jellemax.detour.data.ExploredArea
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.PoiKind
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.data.RouteCandidate
import com.jellemax.detour.data.RoutingServer
import com.jellemax.detour.data.ServerConfig
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.data.pickCandidate
import com.jellemax.detour.drive.SpeedLimitTracker
import com.jellemax.detour.tracking.TripTrackingService
import com.jellemax.detour.ui.formatDistanceKm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "DetourSpin"

/**
 * Car-screen "Spin": road-only, [TravelMode.CAR] fixed — no POI kinds or the
 * moto round-trip loop (v1 scope, agreed with the phone-app parity question
 * parked for later). Radius has no slider widget on a car template, so it
 * cycles through a fixed preset list instead.
 *
 * Also the free-drive map. This is the screen the head unit shows whenever
 * Detour is open and no route is running, so it draws the same following map
 * [NavScreen] does — a car app that shows a bare list while you drive reads as
 * broken next to Waze, and the split-screen panel beside a media app is drawn
 * from that same surface, so with nothing rendering into it that panel is black.
 * The spin controls ride along in the content pane beside the map.
 */
class SpinScreen(
    carContext: CarContext,
    private val renderer: CarMapRenderer,
) : Screen(carContext) {

    private val radiusPresetsKm = listOf(10f, 25f, 50f, 100f)
        .filter { it in TravelMode.CAR.minKm..TravelMode.CAR.maxKm }
        .ifEmpty { listOf(TravelMode.CAR.defaultKm) }
    private var radiusIndex = radiusPresetsKm.indices
        .minByOrNull { kotlin.math.abs(radiusPresetsKm[it] - TravelMode.CAR.defaultKm) } ?: 0

    private var myLocation: LatLon? = null
    private var candidate: RouteCandidate? = null
    private var serverConfig: ServerConfig? = null
    private var spinning = false
    private var errorText: String? = null

    // Ambient speed limit, for the HUD ring while no route is running. The
    // prefetch throttle, the snap and the three-miss clear are
    // SpeedLimitTracker's, in shared/…/drive/, shared with the phone map.
    private var limitState = SpeedLimitTracker.State()
    /** The in-flight Overpass fetch — see [updateSpeedLimit]. */
    private var limitFetchJob: Job? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                fetchLocation()
                // A map someone is watching needs navigation-grade fixes; left
                // to itself the tracker sits in its batched idle mode and the
                // free-drive map crawls a step a minute. Same lever the phone
                // map pulls. Wrapped for the same reason NavScreen wraps its
                // start: a foreground service started from a locked phone in a
                // cradle throws, and losing the map is better than losing the
                // app mid-drive.
                runCatching {
                    TripTrackingService.startMonitoring(carContext)
                    TripTrackingService.setUiVisible(carContext, true)
                }.onFailure { Log.w(TAG, "could not start location updates", it) }
                // Coming back from a drive: the last ambient sign is from
                // wherever you set off, so show nothing until the next fix
                // snaps rather than a stale limit from another town. reset()
                // keeps the prefetched area and the throttle stamp, exactly as
                // clearing these two fields by hand did.
                limitState = SpeedLimitTracker.reset(limitState)
            }
            override fun onStop(owner: LifecycleOwner) {
                runCatching { TripTrackingService.setUiVisible(carContext, false) }
            }
        })
        lifecycleScope.launch {
            // STARTED only: while NavScreen is pushed on top this screen is
            // stopped but not destroyed, and NavScreen owns the camera then.
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                TripTrackingService.lastFix.collect { fix ->
                    fix ?: return@collect
                    val pos = LatLon(fix.lat, fix.lon)
                    myLocation = pos
                    renderer.setPosition(pos, fix.bearingDeg?.takeIf { fix.speedMps > 2.0 })
                    renderer.follow(pos, fix.bearingDeg, fix.speedMps,
                        Settings.defaultZoom.value.toDouble())
                    // Never suspends — the network side runs on its own job, see
                    // updateSpeedLimit. Still wrapped for the same reason
                    // NavScreen wraps its own fix handler: an exception out of a
                    // collector doesn't skip a frame, it takes the process down
                    // mid-drive.
                    try {
                        updateSpeedLimit(pos, fix.bearingDeg, fix.speedMps)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "speed limit snap failed", e)
                    }
                    renderer.updateHud(fix.speedMps * 3.6, limitState.limitKmh)
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        if (ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return MessageTemplate.Builder(
                "Open Detour on your phone once to grant location access, then come back."
            ).setTitle("Location needed").setHeaderAction(Action.APP_ICON).build()
        }

        val pane = Pane.Builder()
        pane.addRow(
            Row.Builder()
                .setTitle("Radius: ${radiusPresetsKm[radiusIndex].toInt()} km")
                .addText("Tap the radius button below to change it")
                .build()
        )
        pane.addAction(
            Action.Builder()
                .setTitle("Radius: ${radiusPresetsKm[radiusIndex].toInt()} km")
                .setOnClickListener {
                    radiusIndex = (radiusIndex + 1) % radiusPresetsKm.size
                    invalidate()
                }
                .build()
        )
        when {
            spinning -> pane.addRow(Row.Builder().setTitle("Spinning…").build())
            errorText != null -> pane.addRow(
                Row.Builder().setTitle("Couldn't find a destination").addText(errorText!!).build()
            )
            candidate != null -> {
                val c = candidate!!
                val meters = c.route?.distanceMeters ?: c.straightLineMeters
                pane.addRow(
                    Row.Builder()
                        .setTitle(c.name ?: "Random road")
                        .addText(formatDistanceKm(meters))
                        .build()
                )
                // Turn-by-turn only when the pick actually has turn data (own
                // server reachable); otherwise fall back to handing off to
                // whatever nav app is default on the head unit.
                val hasTurnData = c.route?.instructions?.isNotEmpty() == true
                pane.addAction(
                    Action.Builder()
                        .setTitle(if (hasTurnData) "Start Navigation" else "Navigate")
                        .setOnClickListener {
                            val config = serverConfig
                            val from = myLocation
                            val navScreen = if (config != null && from != null)
                                NavScreen.forCandidate(carContext, renderer, from, c, config) else null
                            if (navScreen != null) screenManager.push(navScreen)
                            else navigate(c.destination)
                        }
                        .build()
                )
            }
        }

        val content = PaneTemplate.Builder(pane.build())
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)

        // The map-backed variant only exists from car API 7. On an older head
        // unit the pane is still the whole screen — the strip has to go on it
        // then, whereas the wrapper owns it when there is a map behind it.
        if (carContext.carAppApiLevel < CarAppApiLevels.LEVEL_7) {
            return content.setActionStrip(actionStrip()).build()
        }
        return MapWithContentTemplate.Builder()
            .setContentTemplate(content.build())
            .setActionStrip(actionStrip())
            .build()
    }

    // Just the search icon: Spin used to sit here too, but a title action next
    // to the map read as clutter and reviewers missed it as the way to start
    // turn-by-turn (Google's Auto App Quality review flagged exactly that).
    // It now lives inside the search screen instead, as the first thing you
    // see there — one obvious door into "pick a destination", typed or spun.
    private fun actionStrip(): ActionStrip = ActionStrip.Builder()
        .addAction(
            Action.Builder()
                .setIcon(
                    CarIcon.Builder(
                        IconCompat.createWithResource(carContext, R.drawable.ic_car_search),
                    ).build(),
                )
                .setOnClickListener {
                    screenManager.push(SearchScreen(carContext, renderer, onSpin = { spin() }))
                }
                .build(),
        )
        .build()

    /**
     * Posted limit for the road you're on, with no route to read it off.
     * Refreshes the prefetched set only as you near the edge of what you hold,
     * then snaps against it locally on every fix.
     *
     * The refresh runs in its own coroutine. Moving the camera before this call
     * was not enough: the fix collector is sequential, so suspending here for a
     * slow Overpass mirror conflated away every fix that landed meanwhile, and
     * the camera then had nothing new to ease towards until the request came
     * back. Same fix as [NavScreen.checkCameras].
     */
    private fun updateSpeedLimit(pos: LatLon, bearingDeg: Float?, speedMps: Double) {
        if (speedMps < SpeedLimitTracker.MIN_MPS) return
        val now = System.currentTimeMillis()
        if (SpeedLimitTracker.needsWays(limitState, pos, now) &&
            limitFetchJob?.isActive != true
        ) {
            // Throttled on failure too, and backed off after a run of them:
            // hammering the Overpass mirrors from a moving car fixes nothing and
            // gets the IP refused, which takes the sign down for hours.
            limitState = SpeedLimitTracker.fetchStarted(limitState, now)
            limitFetchJob = lifecycleScope.launch {
                // getOrNull, not getOrDefault(emptyList()): speedLimitWays now
                // returns null on a refused or unparseable answer and an empty
                // list only for an area with no tagged road. The tracker backs
                // off on the first and not on the second (SpeedLimitTracker's
                // withWays says why), and collapsing them here would give that
                // distinction away.
                val ways = runCatching {
                    withContext(Dispatchers.IO) { RoadRoulette.speedLimitWays(pos) }
                }.onFailure { Log.w(TAG, "speed limit lookup failed", it) }.getOrNull()
                limitState = SpeedLimitTracker.withWays(limitState, ways, pos)
            }
        }
        limitState = SpeedLimitTracker.onFix(limitState, pos, bearingDeg?.toDouble(), speedMps)
    }

    private fun fetchLocation() {
        if (myLocation != null) return
        if (ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        LocationServices.getFusedLocationProviderClient(carContext).lastLocation
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    myLocation = LatLon(loc.latitude, loc.longitude)
                    invalidate()
                }
            }
    }

    private fun spin() {
        val loc = myLocation ?: run {
            errorText = "Waiting for your location…"
            fetchLocation()
            invalidate()
            return
        }
        if (spinning) return
        spinning = true
        errorText = null
        candidate = null
        renderer.setRoute(null, null)
        invalidate()
        lifecycleScope.launch {
            try {
                val config = withContext(Dispatchers.IO) { RoutingServer.load() }
                serverConfig = config
                val explored = withContext(Dispatchers.IO) { ExploredArea.load() }
                val radiusMeters = radiusPresetsKm[radiusIndex].toDouble() * 1000.0
                val picked = withContext(Dispatchers.IO) {
                    pickCandidate(config, loc, radiusMeters, 0.0,
                        TravelMode.CAR, PoiKind.ROAD, bearing = null, explored)
                }
                candidate = picked
                // Preview where the spin landed before committing to it — the
                // whole point of having a map on this screen.
                renderer.setRoute(picked.route?.polyline, picked.destination)
            } catch (e: Exception) {
                errorText = e.message ?: "Spin failed"
            } finally {
                spinning = false
                invalidate()
            }
        }
    }

    private fun navigate(dest: LatLon) {
        val uri = Uri.parse("geo:${dest.lat},${dest.lon}?q=${dest.lat},${dest.lon}")
        carContext.startCarApp(Intent(CarContext.ACTION_NAVIGATE, uri))
    }
}

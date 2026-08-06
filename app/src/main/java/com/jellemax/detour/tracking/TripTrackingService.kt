package com.jellemax.detour.tracking

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.jellemax.detour.MainActivity
import com.jellemax.detour.ble.BleNavServer
import com.jellemax.detour.ble.BoardTelemetry
import com.jellemax.detour.data.BadgeDef
import com.jellemax.detour.data.BadgeStore
import com.jellemax.detour.data.Coverage
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.MunicipalityStore
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.SyncClient
import com.jellemax.detour.data.TraceStore
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.data.Trip
import com.jellemax.detour.data.TripStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

data class TripStats(
    val startTimeMs: Long,
    /** Fixed when the trip began. Switching mode tabs mid-ride must not change
     *  which stats the running trip is recording, or claim to have recorded. */
    val mode: TravelMode = TravelMode.CAR,
    val durationMs: Long = 0,
    val distanceMeters: Double = 0.0,
    val currentSpeedMps: Double = 0.0,
    val topSpeedMps: Double = 0.0,
    val currentLeanAngleDeg: Double = 0.0,
    val maxLeanAngleDeg: Double = 0.0,
    val currentGForce: Double = 0.0,
    val maxGForce: Double = 0.0,
)

/** Latest GPS fix, published live for the map (fog, navigation). */
data class Fix(
    val lat: Double,
    val lon: Double,
    val speedMps: Double,
    val bearingDeg: Float?,
    val accuracyMeters: Float,
    val timeMs: Long,
)

/**
 * Always-on foreground service that scales its location appetite to what the
 * phone is doing:
 *
 *  - [LocationMode.SLEEP]: activity recognition says the phone is STILL. We
 *    ask for passive fixes only — free, but we still hear anything another app
 *    requests, so a drive is never missed if the STILL-exit event is late.
 *  - [LocationMode.IDLE]: moving around on foot. Coarse batched fixes extend
 *    the fog-of-war trace and watch for a drive starting.
 *  - [LocationMode.PROBE]: activity recognition just reported IN_VEHICLE.
 *    Tight fixes for a few minutes to confirm (or refute) a real drive.
 *  - [LocationMode.TRIP]: recording duration, distance, speed, lean and g-force.
 *    Live stats go to [stats]; the finished trip is written to [TripStore].
 *
 * Auto-start deliberately never trusts a single signal. IN_VEHICLE only opens a
 * probe window, in which sustained [PROBE_SPEED_MPS] confirms a drive; with no
 * such hint the bar is a sustained [FAST_SPEED_MPS], which catches motorcycles
 * that activity recognition misclassifies. Either way the run must last
 * [MIN_FAST_RUN_MS] and cover [MIN_FAST_RUN_METERS] from tight fixes only, so
 * indoor GPS drift while you walk around the house can't fake a trip.
 */
class TripTrackingService : Service() {

    private enum class LocationMode { SLEEP, IDLE, LIVE, PROBE, TRIP }

    companion object {
        const val EXTRA_DEST_LAT = "dest_lat"
        const val EXTRA_DEST_LON = "dest_lon"
        private const val ACTION_START_TRIP = "com.jellemax.detour.START_TRIP"
        private const val ACTION_END_TRIP = "com.jellemax.detour.END_TRIP"
        private const val ACTION_TRANSITION = "com.jellemax.detour.ACTIVITY_TRANSITION"
        private const val ACTION_REFRESH = "com.jellemax.detour.REFRESH"
        private const val CHANNEL_ID = "trip_tracking"
        private const val NOTIFICATION_ID = 1

        // Auto start/stop tuning.
        private const val FAST_SPEED_MPS = 7.0          // ~25 km/h, no vehicle hint
        private const val PROBE_SPEED_MPS = 4.0         // ~14 km/h, IN_VEHICLE was seen
        private const val FAST_FIXES_TO_START = 3
        private const val MIN_FAST_RUN_MS = 8_000L
        private const val MIN_FAST_RUN_METERS = 120.0
        /** Fixes looser than this never contribute to a start decision. */
        private const val MAX_START_ACCURACY_M = 25f
        private const val PROBE_WINDOW_MS = 3 * 60_000L
        /** A probe opened by speed alone, with no IN_VEHICLE to back it up. Kept
         *  short: one freak fix shouldn't buy three minutes of GPS. */
        private const val SPEED_PROBE_WINDOW_MS = 60_000L
        private const val EXIT_GRACE_MS = 2 * 60_000L   // after IN_VEHICLE exit
        private const val STATIONARY_END_MS = 5 * 60_000L
        private const val MIN_AUTO_TRIP_METERS = 500.0
        // A trip whose average pace stays under this, with no mapped vehicle
        // connected, is a walk. Judged on average (not top) speed so one GPS
        // spike can't upgrade a stroll, and only after enough of the trip to
        // tell a real walk from the first slow seconds of a drive.
        private const val WALK_AVG_MAX_MPS = 2.5           // ~9 km/h
        private const val WALK_MIN_JUDGE_MS = 90_000L
        /** ...but average pace alone calls a car stuck in town traffic a walk.
         *  Nothing that has ever hit this speed is one, whatever its average. */
        private const val WALK_TOP_MAX_MPS = 6.0           // ~22 km/h
        /** Which vehicle wins when several mapped devices are connected at
         *  once, weakest first. */
        private val MODE_PRIORITY =
            listOf(TravelMode.WALK, TravelMode.BIKE, TravelMode.CAR, TravelMode.MOTO)
        /** Motion sensors fire ~60x/s; publish stats at 5 Hz. */
        private const val SENSOR_EMIT_INTERVAL_MS = 200L
        /** Past this the phone is being picked up or repositioned, not leaning
         *  with the bike, and it must not become the trip's max. */
        private const val MAX_PLAUSIBLE_LEAN_DEG = 65.0
        /** Low-pass factor for lean/G-force: a pothole or engine vibration hits
         *  the handlebar mount at a far higher frequency than a real lean or
         *  braking/cornering load, so an unfiltered sample can spike the max
         *  well past anything the bike actually did. Smaller = more smoothing. */
        private const val LEAN_EMA_ALPHA = 0.3
        /** A single rotation-vector sample implying more than this much change
         *  since the last one is a fusion glitch (e.g. a magnetometer disturbance
         *  from passing metal), not a real lean — a bike can't snap over that
         *  fast between two ~60ms samples. Rejected before it ever reaches the
         *  EMA, since the EMA only damps a glitch, it doesn't remove one. */
        private const val MAX_LEAN_SLEW_DEG = 20.0
        /** Below this, "lean" is steering-head geometry, not the bike leaning —
         *  turning the bars while stopped or walking the bike tips a bar-mounted
         *  phone via the fork's rake angle alone. Lean is only recorded at or
         *  above real riding speed. */
        private const val MIN_LEAN_SPEED_MPS = 3.0           // ~11 km/h
        private const val G_EMA_ALPHA = 0.15
        /** A single accelerometer sample implying more than this much change
         *  since the last one is a pothole or the mount resonating, not a real
         *  cornering/braking load — a vehicle can't change loading that fast
         *  between two ~60ms samples. Rejected before it ever reaches the EMA,
         *  mirroring MAX_LEAN_SLEW_DEG: the EMA only damps a spike's
         *  contribution, it doesn't remove one outright. */
        private const val MAX_G_SLEW = 0.5
        /** Past this the reading is a shock surviving the EMA, not the vehicle
         *  — a road bike or car doesn't sustain real cornering/braking loads
         *  above roughly this envelope. One ride recorded a max of 6.7 g, which
         *  is physically impossible on two wheels; this caps what can become
         *  the recorded max the same way MAX_PLAUSIBLE_LEAN_DEG caps lean. */
        private const val MAX_PLAUSIBLE_G = 2.0
        /** The board pushes telemetry every 250ms (see moto_hud's ble_central.cpp);
         *  a few missed writes are a hiccup, not a disconnect, so this stays a
         *  multiple of that rather than matching it 1:1. Past this, fall back to
         *  the phone's own sensors rather than freezing on a stale board number. */
        private const val BOARD_TELEMETRY_STALE_MS = 2_000L
        /** Floor between boundary lookups, so a drive along a coastline (where
         *  every point misses) can't turn into a stream of Overpass queries. */
        private const val MUNICIPALITY_LOOKUP_COOLDOWN_MS = 60_000L

        private val _stats = MutableStateFlow<TripStats?>(null)
        val stats: StateFlow<TripStats?> = _stats

        private val _lastFix = MutableStateFlow<Fix?>(null)
        val lastFix: StateFlow<Fix?> = _lastFix

        /** Trace points not yet flushed to [TraceStore]; live fog-of-war. */
        private val _liveTrace = MutableStateFlow<List<LatLon>>(emptyList())
        val liveTrace: StateFlow<List<LatLon>> = _liveTrace

        /** True while the map is on screen. The batched idle fixes are fine for
         *  a fog trace but far too slow for a speed readout someone is looking
         *  at, so a visible map buys navigation-grade updates for as long as it
         *  is visible — and gives them straight back when it isn't. */
        private var uiVisible = false

        fun setUiVisible(context: Context, visible: Boolean) {
            if (uiVisible == visible) return
            uiVisible = visible
            refresh(context)
        }

        /** True while a convoy is joined. A live-shared position is only worth
         *  anything to friends watching it if it's actually live, so joining a
         *  convoy earns the same [LocationMode.LIVE] cadence as having the map
         *  open — see [currentMode] — even with no trip running and the map
         *  backgrounded. */
        private var convoyActive = false

        fun setConvoyActive(context: Context, active: Boolean) {
            if (convoyActive == active) return
            convoyActive = active
            refresh(context)
        }

        /**
         * Every entry point goes through here, because a location-type
         * foreground service may only be started while the location permission
         * is actually held — from Android 14 the system throws rather than
         * ignoring it. Without this the app died on its very first launch: the
         * map's ON_START observer starts the tracker before the user has been
         * asked for anything.
         *
         * The check has to sit on this side of startForegroundService(), not in
         * onStartCommand(): once the system has been *told* a foreground
         * service is coming it insists on seeing startForeground() within a few
         * seconds, so a service that quietly stood down would crash just as
         * hard. MapScreen calls [startMonitoring] again from onLocationGranted()
         * the moment permission arrives.
         *
         * Coarse counts: the fused provider hands back whatever the granted
         * level allows, and the fine-only behaviour degrades rather than breaks.
         */
        private fun canStart(context: Context): Boolean =
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED

        /** Start (or keep) the always-on tracker in idle mode. */
        fun startMonitoring(context: Context) {
            if (!canStart(context)) return
            ContextCompat.startForegroundService(
                context, Intent(context, TripTrackingService::class.java))
        }

        /** Nudge the service to rebuild its notification — e.g. after the
         *  auto-detect setting is toggled, so the text reflects it at once. */
        fun refresh(context: Context) {
            if (!canStart(context)) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, TripTrackingService::class.java).setAction(ACTION_REFRESH),
            )
        }

        /** Manually start a trip (Go/Track button). */
        fun start(context: Context, destLat: Double?, destLon: Double?) {
            val intent = Intent(context, TripTrackingService::class.java).apply {
                action = ACTION_START_TRIP
                destLat?.let { putExtra(EXTRA_DEST_LAT, it) }
                destLon?.let { putExtra(EXTRA_DEST_LON, it) }
            }
            if (!canStart(context)) return
            ContextCompat.startForegroundService(context, intent)
        }

        /** End the current trip; the service stays alive in idle mode. */
        fun stop(context: Context) {
            if (!canStart(context)) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, TripTrackingService::class.java).setAction(ACTION_END_TRIP),
            )
        }
    }

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private var lastLocation: Location? = null
    private var destLat: Double? = null
    private var destLon: Double? = null
    private val tracePoints = ArrayList<TraceStore.TracePoint>()
    private var origin: LatLon? = null
    private var awayFromOrigin = false

    private var autoStarted = false
    private var pendingStopAtMs: Long? = null
    private var lastMovingMs = 0L
    private var transitionsRegistered = false

    /** Activity recognition says the phone is STILL, and no trip is running. */
    private var stationary = false
    /** Deadline of the IN_VEHICLE confirmation window; null when not probing. */
    private var probeUntilMs: Long? = null

    // Run of consecutive fast, accurate fixes that would start a trip.
    private var fastFixes = 0
    private var fastRunStartMs = 0L
    private var fastRunStart: LatLon? = null

    /** Which mode the active location request was made for; null = none yet. */
    private var activeMode: LocationMode? = null

    private var lastMunicipalityLookupMs = 0L

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) onLocation(location)
            // Batched idle fixes arrive together and a probe window can lapse
            // between them; re-evaluate the mode once the burst is handled.
            ensureLocationUpdates()
        }
    }

    private val rotationMatrix = FloatArray(9)

    // Written on the sensor thread, read when the trip is saved.
    @Volatile private var currentLeanDeg = 0.0
    @Volatile private var maxLeanDeg = 0.0
    // Seeded at 1.0, not 0: a stationary accelerometer reads gravity, so the
    // magnitude idles at 1 g. Starting the EMA from 0 would put the first real
    // sample a full 1 g away — past MAX_G_SLEW — and the slew gate would then
    // reject every sample for the rest of the trip.
    @Volatile private var currentG = 1.0
    @Volatile private var maxG = 0.0
    /** Deepest lean since the last trace point, sign kept; see [addTracePoint]. */
    @Volatile private var segmentPeakLeanDeg = 0.0
    /** Whether this vehicle's lean is being measured at all — a car's points
     *  record no lean rather than a misleading zero. */
    @Volatile private var leanTracked = false
    private var lastSensorEmitMs = 0L
    /** Mount-to-bike misalignment, subtracted from every raw lean reading;
     *  see [Settings.leanOffsetDeg]. Cached at trip start — it only changes
     *  from the settings screen, never mid-trip. */
    private var leanOffsetDeg = 0.0

    /**
     * The board's own GPS and IMU, treated as truth over the phone's
     * FusedLocationProvider/rotation-vector sensor when both are present:
     * a dash-mounted GPS antenna with clear sky and an IMU bolted to the
     * bike itself beat a phone in a pocket or a less rigid mount. Position
     * (lat/lon) stays the phone's alone — only speed and lean are compared
     * here, see the BLE server for the write side of this.
     *
     * `receivedAtMs` is stamped on arrival in [BleNavServer], not sent by the
     * board, so a stopped or disconnected board reads as stale within
     * [BOARD_TELEMETRY_STALE_MS] rather than freezing on its last number.
     */
    private fun freshBoardTelemetry(): BoardTelemetry? {
        val telemetry = BleNavServer.boardTelemetry.value ?: return null
        val age = System.currentTimeMillis() - telemetry.receivedAtMs
        return if (age in 0..BOARD_TELEMETRY_STALE_MS) telemetry else null
    }

    /** Board lean is only trusted for a vehicle whose mode tracks lean at all
     *  — the same rule [startMotionSensors] applies to the phone's own sensor,
     *  so a car trip with a board still connected doesn't suddenly grow one. */
    private fun freshBoardLeanDeg(mode: TravelMode?): Double? {
        if (mode?.tracksLean != true) return null
        val telemetry = freshBoardTelemetry() ?: return null
        return if (telemetry.hasLean) telemetry.leanDeg else null
    }

    /** Shared by the phone's own rotation-vector sensor and fresh board
     *  telemetry — whichever is currently authoritative calls this, so the
     *  recorded max reflects one source at a time, not whichever updated last. */
    private fun recordLean(deg: Double) {
        if (abs(deg) > MAX_PLAUSIBLE_LEAN_DEG) return
        // Below riding speed, "lean" is steering-head rake, not the bike
        // actually leaning — see MIN_LEAN_SPEED_MPS.
        if ((_stats.value?.currentSpeedMps ?: 0.0) < MIN_LEAN_SPEED_MPS) return
        maxLeanDeg = maxOf(maxLeanDeg, abs(deg))
        if (abs(deg) > abs(segmentPeakLeanDeg)) segmentPeakLeanDeg = deg
    }

    /**
     * Lean angle (from the rotation-vector sensor) and g-force (accelerometer
     * magnitude) only make sense while a trip is running, so these sensors are
     * only registered between [beginTrip] and [endTrip]. Lean angle assumes the
     * phone is mounted upright facing forward, e.g. a handlebar mount — a phone
     * in a pocket will read garbage.
     *
     * Lean is *not* [SensorManager.getOrientation]'s roll. That roll is only
     * defined for a phone lying flattish: a phone standing upright sits exactly
     * on its gimbal-lock singularity (pitch -90°), where roll degenerates and
     * reads ±180° regardless of how the bike is leaning — which is why every
     * ride recorded a max lean of about 180°.
     *
     * The gravity direction has no such singularity. The rotation matrix's
     * third row is world-up expressed in device axes, so the angle between it
     * and the device's own up axis, about the axis out of the screen, is the
     * lean: 0 with the phone upright, positive leaning right. A mount tilted
     * back towards the rider only moves gravity along that third axis, so it
     * does not bias the reading.
     */
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (_stats.value == null) return
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    // Third row of the rotation matrix = world up in device axes.
                    // Negated x so a lean to the right reads positive: tipping
                    // right moves gravity towards the device's -x side.
                    val upX = -rotationMatrix[6]
                    val upY = rotationMatrix[7]
                    val rawLeanDeg = Math.toDegrees(atan2(upX, upY).toDouble()) - leanOffsetDeg
                    // Drop single-sample fusion glitches before they ever reach
                    // the EMA — see MAX_LEAN_SLEW_DEG. The EMA below only damps
                    // a glitch's contribution, it can't remove it outright.
                    if (abs(rawLeanDeg - currentLeanDeg) <= MAX_LEAN_SLEW_DEG) {
                        currentLeanDeg += LEAN_EMA_ALPHA * (rawLeanDeg - currentLeanDeg)
                        // Only this sensor's own reading feeds the recorded max while
                        // the board isn't supplying a fresher one — see recordLean()
                        // and freshBoardLeanDeg(). (MAX_PLAUSIBLE_LEAN_DEG below still
                        // guards against the phone being handled, not a lean.)
                        if (freshBoardLeanDeg(_stats.value?.mode) == null) recordLean(currentLeanDeg)
                    }
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    val (x, y, z) = event.values
                    val rawG = sqrt((x * x + y * y + z * z).toDouble()) /
                        SensorManager.GRAVITY_EARTH
                    // Drop single-sample shocks before they ever reach the EMA —
                    // see MAX_G_SLEW.
                    if (abs(rawG - currentG) <= MAX_G_SLEW) {
                        currentG += G_EMA_ALPHA * (rawG - currentG)
                        // MAX_PLAUSIBLE_G still guards the recorded max even
                        // once a shock has been smoothed into currentG.
                        if (currentG <= MAX_PLAUSIBLE_G) maxG = maxOf(maxG, currentG)
                    }
                }
            }
            // Peaks are folded in on every event above; publishing them at 5 Hz
            // keeps the trip card live without recomposing it 100x a second.
            val now = SystemClock.elapsedRealtime()
            if (now - lastSensorEmitMs < SENSOR_EMIT_INTERVAL_MS) return
            lastSensorEmitMs = now
            // The board updates at 4 Hz (see BOARD_TELEMETRY_STALE_MS), close
            // enough to this 5 Hz tick that sampling it here rather than on
            // its own event is a fine match — recorded here rather than in the
            // ROTATION_VECTOR branch above since that branch only fires from
            // the phone's own sensor, never from a BLE write.
            val boardLeanDeg = freshBoardLeanDeg(_stats.value?.mode)
            if (boardLeanDeg != null) recordLean(boardLeanDeg)
            _stats.update {
                it?.copy(
                    currentLeanAngleDeg = boardLeanDeg ?: currentLeanDeg,
                    maxLeanAngleDeg = maxLeanDeg,
                    currentGForce = currentG,
                    maxGForce = maxG,
                )
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** Registers only the sensors this vehicle has a meaningful reading for, so
     *  a car trip never records a lean angle and a bicycle wakes neither sensor. */
    private fun startMotionSensors(mode: TravelMode) {
        // SENSOR_DELAY_UI (~60ms) resolves a lean or a braking spike just as well
        // as SENSOR_DELAY_GAME (~20ms) and wakes the CPU a third as often.
        leanTracked = false
        if (mode.tracksLean) {
            leanOffsetDeg = Settings.leanOffsetDeg.value.toDouble()
            sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
                sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
                leanTracked = true
            }
        }
        if (mode.tracksGForce) {
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    private fun stopMotionSensors() {
        sensorManager.unregisterListener(sensorListener)
        leanTracked = false
        segmentPeakLeanDeg = 0.0
    }

    // --- Bluetooth vehicle auto-detect -------------------------------------
    // Mapped Classic devices (Cardo, car infotainment) pick the trip mode,
    // falling back to the default when none is connected. Addresses of
    // currently-connected mapped devices.
    private val connectedVehicles = LinkedHashSet<String>()
    private var btRegistered = false

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = deviceFrom(intent) ?: return
            val address = try { device.address } catch (e: SecurityException) { return } ?: return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED ->
                    if (Settings.vehicleDevices.value.containsKey(address)) {
                        connectedVehicles.remove(address) // move to newest
                        connectedVehicles.add(address)
                        refreshTripMode()
                    }
                BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                    if (connectedVehicles.remove(address)) refreshTripMode()
            }
        }
    }

    /** Turning the adapter off drops every link without an ACL_DISCONNECTED per
     *  device, so without this the car stays "connected" for the rest of the
     *  service's life and the next ride is logged as a drive. */
    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF ->
                    if (connectedVehicles.isNotEmpty()) {
                        connectedVehicles.clear()
                        refreshTripMode()
                    }
                BluetoothAdapter.STATE_ON -> seedConnectedVehicles()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun deviceFrom(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    /** True when we're allowed to touch bonded devices/connection state. Below
     *  API 31 the normal BLUETOOTH permission is granted at install. */
    private fun hasBtPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** Register the connect/disconnect watcher once, and seed it with whatever
     *  is already connected (so it works if the app opens mid-drive). No-op
     *  until permission is granted; retried on the next service command. */
    private fun ensureBluetoothWatch() {
        if (btRegistered || !hasBtPermission()) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, btReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(
            this,
            btStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        btRegistered = true
        seedConnectedVehicles()
    }

    /**
     * Ask the headset/A2DP profiles which mapped devices are connected right
     * now, since ACL broadcasts only fire on change, not for existing links.
     *
     * The answer replaces what we believed rather than adding to it: a missed
     * disconnect (adapter reset, device out of range, service asleep) otherwise
     * pins the trip to a vehicle that was left behind hours ago. Both profiles
     * are asked before we commit, so the two callbacks can't erase each other.
     */
    private fun seedConnectedVehicles() {
        val map = Settings.vehicleDevices.value
        if (map.isEmpty() || !hasBtPermission()) return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val profiles = listOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP)
        val found = LinkedHashSet<String>()
        var pending = profiles.size
        // Runs once the last profile has answered (or failed to).
        val commit = {
            if (connectedVehicles != found) {
                connectedVehicles.clear()
                connectedVehicles.addAll(found)
                refreshTripMode()
            }
        }
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                try {
                    proxy.connectedDevices.forEach { d ->
                        if (map.containsKey(d.address)) found.add(d.address)
                    }
                } catch (e: SecurityException) {
                    // permission revoked between the check and here; ignore
                } finally {
                    adapter.closeProfileProxy(profile, proxy)
                }
                if (--pending == 0) commit()
            }
            /** A profile the phone doesn't support never calls back connected. */
            override fun onServiceDisconnected(profile: Int) {
                if (--pending == 0) commit()
            }
        }
        profiles.forEach {
            if (!adapter.getProfileProxy(this, listener, it)) pending--
        }
        if (pending == 0) commit()
    }

    /**
     * What this trip should be logged as. Priority: a connected mapped device
     * decides (Cardo → moto, infotainment → car, walking earbuds → walk); else,
     * once we have enough of the trip to judge, a sustained walking pace with
     * nothing mapped connected means a walk; else the spin tab's mode. The tab
     * itself is never changed here — classification is the trip's, not the UI's.
     */
    private fun resolvedMode(): TravelMode {
        val map = Settings.vehicleDevices.value
        // The heaviest vehicle connected wins, not the last to connect: earbuds
        // paired for a walk stay linked in the car, and the helmet intercom and
        // the car radio can both be up while the bike sits in the garage.
        connectedVehicles.mapNotNull { map[it]?.mode }
            .maxByOrNull { MODE_PRIORITY.indexOf(it) }
            ?.let { return it }
        val s = _stats.value
        if (s != null && s.durationMs > WALK_MIN_JUDGE_MS) {
            val avg = if (s.durationMs > 0) s.distanceMeters / (s.durationMs / 1000.0) else 0.0
            if (avg < WALK_AVG_MAX_MPS && s.topSpeedMps < WALK_TOP_MAX_MPS) return TravelMode.WALK
        }
        return Settings.tripMode.value
    }

    /** Retag the running trip if its mode should change (device connected/left,
     *  or a walk revealed itself by pace). Restarts motion sensors to match. */
    private fun refreshTripMode() {
        val mode = resolvedMode()
        if (_stats.value != null && _stats.value?.mode != mode) {
            _stats.update { it?.copy(mode = mode) }
            stopMotionSensors()
            startMotionSensors(mode)
            updateNotification()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Settings.init(this)
        createChannel()
        // From Android 12 the platform refuses a foreground service started
        // while the app itself is in the background, and throws rather than
        // ignoring it — which the Android Auto flow can walk into, since the
        // car screen starts this with the phone locked in its cradle and no
        // activity of ours anywhere. Standing down is the only safe answer: a
        // service that is told a foreground start is coming and neither calls
        // startForeground() nor stops is killed with an ANR.
        val foreground = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        }.isSuccess
        if (!foreground) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!::fusedClient.isInitialized) {
            fusedClient = LocationServices.getFusedLocationProviderClient(this)
        }
        if (!::sensorManager.isInitialized) {
            sensorManager = getSystemService(SensorManager::class.java)
        }

        // Before the action, so a trip started in this same command classifies
        // against devices that were already connected when the service woke.
        ensureBluetoothWatch()

        when (intent?.action) {
            ACTION_START_TRIP -> {
                if (_stats.value == null) {
                    destLat = intent.takeIf { it.hasExtra(EXTRA_DEST_LAT) }
                        ?.getDoubleExtra(EXTRA_DEST_LAT, 0.0)
                    destLon = intent.takeIf { it.hasExtra(EXTRA_DEST_LON) }
                        ?.getDoubleExtra(EXTRA_DEST_LON, 0.0)
                    beginTrip(auto = false)
                }
            }
            ACTION_END_TRIP -> endTrip()
            ACTION_TRANSITION -> handleTransition(intent)
        }

        ensureLocationUpdates()
        registerActivityTransitions()
        return START_STICKY
    }

    /** [startTimeMs] backdates an auto-started trip to when the drive really
     *  began, rather than to the fix that finally proved it. */
    private fun beginTrip(
        auto: Boolean,
        startTimeMs: Long = System.currentTimeMillis(),
        initialDistanceMeters: Double = 0.0,
    ) {
        autoStarted = auto
        origin = null
        awayFromOrigin = false
        stationary = false
        probeUntilMs = null
        pendingStopAtMs = null
        resetStartDetector()
        currentLeanDeg = 0.0; maxLeanDeg = 0.0
        // 1.0, not 0: the resting magnitude is 1 g — see the field declaration.
        currentG = 1.0; maxG = 0.0
        lastMovingMs = System.currentTimeMillis()
        // Re-check what's actually linked: the set may have gone stale since the
        // last trip. Answers async, retagging through refreshTripMode.
        seedConnectedVehicles()
        // Classify by connected device / pace / tab; refined live as the trip runs.
        _stats.value = TripStats(startTimeMs = startTimeMs, distanceMeters = initialDistanceMeters)
        val mode = resolvedMode()
        _stats.value = _stats.value?.copy(mode = mode)
        ensureLocationUpdates()
        startMotionSensors(mode)
        updateNotification()
    }

    private fun endTrip() {
        val stats = _stats.value ?: return
        val wasAuto = autoStarted
        stopMotionSensors()
        flushTrace()
        val worthSaving =
            if (wasAuto) stats.distanceMeters >= MIN_AUTO_TRIP_METERS
            else stats.durationMs > 0
        if (worthSaving) {
            TripStore.save(
                this,
                Trip(
                    startTimeMs = stats.startTimeMs,
                    endTimeMs = System.currentTimeMillis(),
                    distanceMeters = stats.distanceMeters,
                    topSpeedMps = stats.topSpeedMps,
                    maxLeanAngleDeg = maxLeanDeg,
                    maxGForce = maxG,
                    destinationLat = destLat,
                    destinationLon = destLon,
                    mode = stats.mode,
                ),
            )
            SyncClient.syncQuietly(this)
            checkBadges()
            // Only tell the user about trips they didn't end themselves.
            if (wasAuto) notifyTripEnded()
        }
        _stats.value = null
        destLat = null
        destLon = null
        autoStarted = false
        pendingStopAtMs = null
        ensureLocationUpdates()
        updateNotification()
    }

    private fun currentMode(): LocationMode = when {
        _stats.value != null -> LocationMode.TRIP
        probeUntilMs?.let { System.currentTimeMillis() < it } == true -> LocationMode.PROBE
        // Beats SLEEP: someone watching the map wants a live speed even if
        // activity recognition still thinks the phone is sitting still, and a
        // joined convoy wants the same cadence whether or not the map is open.
        uiVisible || convoyActive -> LocationMode.LIVE
        stationary -> LocationMode.SLEEP
        else -> LocationMode.IDLE
    }

    private fun locationRequest(mode: LocationMode): LocationRequest = when (mode) {
        // Passive costs no radio time of its own: we only see fixes some other
        // app already paid for. Enough to notice a drive if STILL-exit is late.
        LocationMode.SLEEP ->
            LocationRequest.Builder(Priority.PRIORITY_PASSIVE, 60_000L)
                .setMinUpdateDistanceMeters(100f)
                .build()
        // Still batched, but a burst held for a minute meant a drive that began
        // 60 s ago was invisible to the start detector for 60 s. IDLE only runs
        // while you're actually moving around on foot (STILL parks us in SLEEP),
        // so the shorter window costs little and is what the detector reacts to.
        LocationMode.IDLE ->
            LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 20_000L)
                .setMinUpdateDistanceMeters(30f)
                .setMaxUpdateDelayMillis(20_000L)
                .setWaitForAccurateLocation(false)
                .build()
        // Same appetite as a trip: the map is open, the screen is on, and the
        // radio is the small cost next to the display.
        LocationMode.LIVE, LocationMode.TRIP ->
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
                // GNSS tops out around 1 Hz, but fused will hand over anything
                // faster it has (sensor-fused, another app's request) instead of
                // holding it back to the nominal interval.
                .setMinUpdateIntervalMillis(200L)
                .setWaitForAccurateLocation(false)
                .build()
        LocationMode.PROBE ->
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4_000L).build()
    }

    /** (Re)request location updates matching the current mode. */
    private fun ensureLocationUpdates() {
        val mode = currentMode()
        if (activeMode == mode) return
        fusedClient.removeLocationUpdates(locationCallback)
        try {
            fusedClient.requestLocationUpdates(
                locationRequest(mode), locationCallback, Looper.getMainLooper())
            activeMode = mode
            updateNotification()
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun registerActivityTransitions() {
        if (transitionsRegistered) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACTIVITY_RECOGNITION,
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        fun transition(activity: Int, type: Int) = ActivityTransition.Builder()
            .setActivityType(activity)
            .setActivityTransition(type)
            .build()

        val transitions = listOf(
            transition(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
            transition(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_EXIT),
            // STILL drives the sleep mode; WALKING cancels a stray vehicle probe.
            transition(DetectedActivity.STILL, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
            transition(DetectedActivity.STILL, ActivityTransition.ACTIVITY_TRANSITION_EXIT),
            transition(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
        )
        val pendingIntent = PendingIntent.getForegroundService(
            this, 1,
            Intent(this, TripTrackingService::class.java).setAction(ACTION_TRANSITION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        try {
            ActivityRecognition.getClient(this)
                .requestActivityTransitionUpdates(
                    ActivityTransitionRequest(transitions), pendingIntent)
                .addOnSuccessListener { transitionsRegistered = true }
        } catch (e: SecurityException) {
            // No activity recognition permission; speed fallback still works.
        }
    }

    private fun handleTransition(intent: Intent) {
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        for (event in result.transitionEvents) {
            val entering = event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
            when (event.activityType) {
                DetectedActivity.STILL -> {
                    if (_stats.value == null) stationary = entering
                    if (entering) {
                        resetStartDetector()
                        flushTrace()
                    }
                }
                DetectedActivity.IN_VEHICLE -> {
                    if (entering) {
                        stationary = false
                        pendingStopAtMs = null
                        // IN_VEHICLE on its own is not evidence of a drive — it
                        // fires for a phone on a desk next to a fan. Open a window
                        // in which a modest sustained speed is enough to confirm.
                        if (_stats.value == null && Settings.autoDetectDrives.value) {
                            probeUntilMs = System.currentTimeMillis() + PROBE_WINDOW_MS
                            resetStartDetector()
                        }
                    } else {
                        probeUntilMs = null
                        // Don't end immediately — could be a fuel stop. The grace
                        // period is checked against speed in onTripLocation.
                        if (_stats.value != null && autoStarted) {
                            pendingStopAtMs = System.currentTimeMillis()
                        }
                    }
                }
                DetectedActivity.WALKING -> {
                    if (entering && _stats.value == null) {
                        stationary = false
                        probeUntilMs = null // walking never becomes a drive
                        resetStartDetector()
                    }
                }
            }
        }
        ensureLocationUpdates()
    }

    private fun resetStartDetector() {
        fastFixes = 0
        fastRunStart = null
    }

    private fun onLocation(location: Location) {
        val speed = speedOf(location)
        _lastFix.value = Fix(
            lat = location.latitude,
            lon = location.longitude,
            speedMps = speed,
            bearingDeg = if (location.hasBearing()) location.bearing else null,
            accuracyMeters = location.accuracy,
            timeMs = location.time,
        )
        val stats = _stats.value
        if (stats == null) {
            onIdleLocation(location, speed)
        } else {
            onTripLocation(location, speed, stats)
        }
        lastLocation = location
    }

    /** Idle/probe/sleep: extend the explored trace, watch for a drive starting. */
    private fun onIdleLocation(location: Location, speed: Double) {
        if (location.accuracy <= 50f) {
            addTracePoint(
                LatLon(location.latitude, location.longitude), location.time, speed)
        }
        if (!Settings.autoDetectDrives.value) {
            resetStartDetector()
            return
        }
        // A loose fix can drift 100 m in a minute while the phone sits indoors,
        // which reads as a comfortable 6 km/h — or, over one bad jump, as 25.
        if (location.accuracy > MAX_START_ACCURACY_M) {
            resetStartDetector()
            return
        }

        val probing = probeUntilMs?.let { System.currentTimeMillis() < it } == true
        if (speed < (if (probing) PROBE_SPEED_MPS else FAST_SPEED_MPS)) {
            resetStartDetector()
            return
        }

        // One accurate fix at driving speed is enough to *look closer*, and that
        // is the whole reason a drive used to take minutes to notice: we waited
        // for IN_VEHICLE, then confirmed against fixes that arrived every 20 s.
        // Escalating here puts us on 4 s fixes immediately — the run below is
        // then confirmed in seconds. The evidence bar for starting is unchanged.
        if (!probing) {
            probeUntilMs = System.currentTimeMillis() + SPEED_PROBE_WINDOW_MS
            stationary = false
        }

        val here = LatLon(location.latitude, location.longitude)
        val runStart = fastRunStart
        if (runStart == null) {
            fastRunStart = here
            // GPS timestamps, not wall clock: a batched burst of idle fixes all
            // arrive at the same instant but describe minutes of driving.
            fastRunStartMs = location.time
            fastFixes = 1
            return
        }
        fastFixes++
        val runDistanceMeters = RoadRoulette.distanceMeters(runStart, here)
        if (fastFixes >= FAST_FIXES_TO_START &&
            location.time - fastRunStartMs >= MIN_FAST_RUN_MS &&
            runDistanceMeters >= MIN_FAST_RUN_METERS
        ) {
            beginTrip(auto = true, startTimeMs = fastRunStartMs, initialDistanceMeters = runDistanceMeters)
        }
    }

    private fun onTripLocation(location: Location, speed: Double, stats: TripStats) {
        val now = System.currentTimeMillis()

        var distance = stats.distanceMeters
        val last = lastLocation
        // Only accumulate distance for accurate, recent fixes to avoid GPS jumps.
        if (last != null && location.accuracy <= 50f &&
            location.time - last.time in 1..15_000
        ) {
            distance += last.distanceTo(location).toDouble()
        }

        if (location.accuracy <= 50f) {
            val p = LatLon(location.latitude, location.longitude)
            addTracePoint(p, location.time, speed)

            // Auto-stop when back at the starting point after a real trip.
            if (origin == null) origin = p
            origin?.let { start ->
                val fromStart = RoadRoulette.distanceMeters(p, start)
                if (fromStart > 400) awayFromOrigin = true
                if (awayFromOrigin && fromStart < 120 &&
                    now - stats.startTimeMs > 5 * 60_000
                ) {
                    endTrip()
                    return
                }
            }
        }

        if (speed > 2.0) lastMovingMs = now

        // Left the vehicle and stayed slow through the grace period: trip over.
        pendingStopAtMs?.let { exitedAt ->
            if (speed > 5.0) {
                pendingStopAtMs = null
            } else if (now - exitedAt > EXIT_GRACE_MS) {
                endTrip()
                return
            }
        }
        // Fallback if the vehicle-exit event never arrives. Also stops the
        // high-accuracy fixes draining the battery in a car park.
        if (autoStarted && now - lastMovingMs > STATIONARY_END_MS) {
            endTrip()
            return
        }

        // Board GPS over the phone's when it's fresh — see freshBoardTelemetry().
        // Deliberately scoped to just the two fields below: `speed` above still
        // drives auto-start/stop and the fog trace, which stay on the phone's
        // own consistent GPS pipeline regardless of whether a board is paired.
        val effectiveSpeedMps = freshBoardTelemetry()
            ?.takeIf { it.hasSpeed }
            ?.let { it.speedKmh / 3.6 }
            ?: speed

        // update (not value =) so the 5 Hz sensor writes aren't clobbered here.
        _stats.update {
            it?.copy(
                durationMs = now - it.startTimeMs,
                distanceMeters = distance,
                currentSpeedMps = effectiveSpeedMps,
                topSpeedMps = maxOf(it.topSpeedMps, effectiveSpeedMps),
            )
        }
        // Now that pace is updated, a slow trip may reveal itself as a walk.
        refreshTripMode()
    }

    private fun speedOf(location: Location): Double {
        if (location.hasSpeed()) return location.speed.toDouble()
        // Coarse fixes often lack speed, and deriving it from two positions is
        // only honest when both are tight — otherwise a single indoor GPS jump
        // between sparse idle fixes looks exactly like pulling out of a driveway.
        val last = lastLocation ?: return 0.0
        if (location.accuracy > MAX_START_ACCURACY_M ||
            last.accuracy > MAX_START_ACCURACY_M
        ) return 0.0
        val dtSec = (location.time - last.time) / 1000.0
        if (dtSec !in 1.0..120.0) return 0.0
        return last.distanceTo(location) / dtSec
    }

    /**
     * Trace for the fog-of-war map, decimated to ~25 m spacing, now carrying
     * what the ride was doing at each point as well as where it was.
     *
     * Lean is the peak since the previous point, not the reading at this
     * instant: points are 25 m apart, which is a whole corner at town speed, and
     * the deepest lean through it is the interesting number. Sign is kept, so
     * the peak is the largest magnitude with its direction intact.
     */
    private fun addTracePoint(p: LatLon, timeMs: Long, speedMps: Double) {
        val lastTrace = tracePoints.lastOrNull()?.at
        if (lastTrace != null) {
            val gap = RoadRoulette.distanceMeters(lastTrace, p)
            if (gap < 25.0) return
            // Big jump (location off for a while): close this segment first.
            if (gap > 500.0) flushTrace()
        }
        tracePoints.add(
            TraceStore.TracePoint(
                at = p,
                timeMs = timeMs,
                speedKmh = speedMps * 3.6,
                leanDeg = if (leanTracked) segmentPeakLeanDeg else null,
            )
        )
        segmentPeakLeanDeg = 0.0
        if (tracePoints.size >= 200) flushTrace(keepLast = true)
        _liveTrace.value = tracePoints.map { it.at }
        maybeDiscoverMunicipality(p)
    }

    /**
     * Learn the boundary of whatever municipality we just drove into. Points
     * inside a boundary we already hold cost a polygon test and nothing else, so
     * a whole ride through familiar territory makes zero network requests.
     */
    private fun maybeDiscoverMunicipality(p: LatLon) {
        val now = System.currentTimeMillis()
        if (now - lastMunicipalityLookupMs < MUNICIPALITY_LOOKUP_COOLDOWN_MS) return
        val app = applicationContext
        if (!MunicipalityStore.needsLookup(app, p)) return
        lastMunicipalityLookupMs = now
        Thread { MunicipalityStore.discoverQuietly(app, p) }.start()
    }

    /** Rescore badges off the main thread and tell the user about new ones. */
    private fun checkBadges() {
        val app = applicationContext
        Thread {
            val coverage = Coverage.compute(app)
            val newly = BadgeStore.refresh(app, BadgeStore.stats(app, coverage)).newlyEarned
            if (newly.isNotEmpty()) notifyBadgesEarned(newly)
        }.start()
    }

    private fun flushTrace(keepLast: Boolean = false) {
        if (tracePoints.isEmpty()) return
        TraceStore.append(this, tracePoints)
        val last = tracePoints.lastOrNull()
        tracePoints.clear()
        if (keepLast && last != null) tracePoints.add(last)
        _liveTrace.value = tracePoints.map { it.at }
    }

    override fun onDestroy() {
        if (::fusedClient.isInitialized) {
            fusedClient.removeLocationUpdates(locationCallback)
        }
        if (btRegistered) {
            runCatching { unregisterReceiver(btReceiver) }
            runCatching { unregisterReceiver(btStateReceiver) }
            btRegistered = false
        }
        endTrip()
        flushTrace()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Trip tracking", NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notifyTripEnded() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Detour")
            .setContentText("Trip ended — saved to history.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(2, notification)
    }

    private fun notifyBadgesEarned(badges: List<BadgeDef>) {
        val title = if (badges.size == 1) "Badge earned!" else "${badges.size} badges earned!"
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(badges.joinToString(", ") { it.title })
            .setSmallIcon(android.R.drawable.btn_star_big_on)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(3, notification)
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stats = _stats.value
        val text = when {
            stats != null -> "Tracking your ${stats.mode.label.lowercase()} trip…"
            !Settings.autoDetectDrives.value -> "Auto-tracking off"
            stationary -> "Standing by"
            else -> "Watching for trips"
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Detour")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(contentIntent)
            .setOngoing(true)
        // Ending a trip from the shade beats unlocking, finding the app, and
        // hunting for a button — which is the situation you are in at a kerbside.
        if (stats != null) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "End trip",
                PendingIntent.getForegroundService(
                    this, 2,
                    Intent(this, TripTrackingService::class.java).setAction(ACTION_END_TRIP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        return builder.build()
    }
}

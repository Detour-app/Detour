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
import com.jellemax.detour.R
import com.jellemax.detour.ble.BleNavServer
import com.jellemax.detour.ble.BoardTelemetry
import com.jellemax.detour.data.syncQuietly
import com.jellemax.detour.data.BadgeDef
import com.jellemax.detour.data.BadgeStore
import com.jellemax.detour.data.CirclePresence
import com.jellemax.detour.data.Coverage
import com.jellemax.detour.data.Curviness
import com.jellemax.detour.data.DrivingStats
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.MunicipalityStore
import com.jellemax.detour.data.RiderTotals
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.SyncClient
import com.jellemax.detour.data.TraceStore
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.data.Trip
import com.jellemax.detour.data.TripStore
import com.jellemax.detour.drive.FuelType
import com.jellemax.detour.drive.HardEventDetector
import com.jellemax.detour.drive.RoadTypeTracker
import com.jellemax.detour.drive.SpeedLimitTracker
import com.jellemax.detour.drive.StopDetector
import com.jellemax.detour.notif.TripEndedNotification
import com.jellemax.detour.obd2.Obd2Connection
import com.jellemax.detour.obd2.ObdTelemetry
import com.jellemax.detour.ui.loadTripPoints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.roundToLong
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
    val hardBrakeCount: Int = 0,
    val hardAccelCount: Int = 0,
    val hardCornerCount: Int = 0,
    val stopCount: Int = 0,
    /** True while the current fix reads over the posted limit (same margin as
     *  [TripTrackingService.OVER_LIMIT_MARGIN]) — a live HUD signal, not a
     *  count; carries the previous value forward on a fix with no real speed
     *  measurement rather than flickering off. */
    val currentlyOverLimit: Boolean = false,
)

/** Latest location fix, published live for the map (fog, navigation) and the
 *  HUD. `speedMps` is the best available source — fresh OBD2, else board
 *  telemetry, else the phone's GPS — not necessarily GPS. Auto-start/stop and
 *  the fog trace deliberately stay on raw GPS; see onLocation. */
data class Fix(
    val lat: Double,
    val lon: Double,
    val speedMps: Double,
    val bearingDeg: Float?,
    val accuracyMeters: Float,
    /** Provider wall-clock UTC ([android.location.Location.getTime]). For anything that
     *  leaves this device: a peer reading a convoy or circle position has no way to
     *  interpret our uptime. */
    val timeMs: Long,
    /** Monotonic, on [android.os.SystemClock.elapsedRealtime]'s basis. For measuring this
     *  fix's *own age*, which is the only thing [timeMs] was ever wrong for: subtracting
     *  a provider wall clock from ours compares two clocks that only usually agree, so a
     *  device clock running persistently fast biases every answer in one direction. */
    val elapsedRealtimeMs: Long,
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
        // One definition, shared with the trip-ended notification that posts to
        // the same channel from notif/.
        private const val CHANNEL_ID = TripEndedNotification.CHANNEL_ID
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
        // connected, was never a drive — a walk, a jog, pushing a bike. Judged
        // on average (not top) speed so one GPS spike can't rescue it, and
        // only after enough of the trip to tell a real walk from the first
        // slow seconds of a drive. Dropped at endTrip() rather than saved
        // under a mode that doesn't fit it.
        private const val SLOW_NO_VEHICLE_AVG_MAX_MPS = 2.5    // ~9 km/h
        private const val SLOW_NO_VEHICLE_MIN_JUDGE_MS = 90_000L
        /** ...but average pace alone calls a car stuck in town traffic slow.
         *  Nothing that has ever hit this speed gets dropped, whatever its average. */
        private const val SLOW_NO_VEHICLE_TOP_MAX_MPS = 6.0    // ~22 km/h
        /** Which vehicle wins when several mapped devices are connected at
         *  once, weakest first. */
        private val MODE_PRIORITY = listOf(TravelMode.CAR, TravelMode.MOTO)
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
        /** Same reasoning as [BOARD_TELEMETRY_STALE_MS]: a disconnected/stalled
         *  OBD2 adapter must read as stale, not freeze on its last speed. The
         *  poll loop ticks every ~1s (see Obd2Connection.POLL_INTERVAL_MS); 3s
         *  tolerates one or two missed polls before falling back to GPS. */
        private const val OBD_TELEMETRY_STALE_MS = 3_000L
        /** A near-zero OBD2 speed is ignored when the phone's own GPS is this sure the
         *  vehicle is moving — an always-hot ELM327 dongle keeps reporting 0 km/h from a
         *  parked car while its owner walks or cycles past, and _lastFix (unlike the
         *  trip pipeline) is live even with no trip running. */
        internal const val OBD_ZERO_OVERRIDE_MPS = 2.78  // ~10 km/h
        /** Throttle position above which a sample counts toward
         *  [DrivingStats.pctWideOpenThrottle]. Provisional. */
        private const val WIDE_OPEN_THROTTLE_PCT = 90.0
        /** Floor between boundary lookups, so a drive along a coastline (where
         *  every point misses) can't turn into a stream of Overpass queries. */
        private const val MUNICIPALITY_LOOKUP_COOLDOWN_MS = 60_000L
        /** 10% over the posted limit, provisional — a floor against GPS/rounding
         *  noise reading a steady legal speed as a violation. */
        private const val OVER_LIMIT_MARGIN = 1.10

        private val _stats = MutableStateFlow<TripStats?>(null)
        val stats: StateFlow<TripStats?> = _stats

        private val _lastFix = MutableStateFlow<Fix?>(null)
        val lastFix: StateFlow<Fix?> = _lastFix

        /** Best-available display speed in m/s, on a faster cadence than [lastFix]:
         *  a paired OBD2 adapter refreshes this every ~1s between GPS fixes so the
         *  speed HUD keeps gliding through a tunnel or a pocketed phone. [lastFix]
         *  stays on the GPS cadence on purpose — position and time there move
         *  together, and a bare speed refresh on it would depress every section-
         *  average / speed-limit / relay consumer that keys distance off the fix
         *  position while keying time off the wall clock. */
        private val _displaySpeedMps = MutableStateFlow(0.0)
        val displaySpeedMps: StateFlow<Double> = _displaySpeedMps

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

        /** True when [Obd2Connection] should be held open for something other
         *  than the OBD2 pairing screen's own readout — a running trip or a
         *  visible map. The pairing screen reads this to decide whether to tear
         *  its link down on exit. */
        fun obdWantedByService(): Boolean = uiVisible || _stats.value != null

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
    // The raw GPS speed of the last fix, kept for the OBD2 speed-refresh loop:
    // between GPS callbacks it has no other way to re-run resolveDisplaySpeedMps'
    // GPS-contradiction guard.
    private var lastGpsSpeedMps = 0.0
    private var destLat: Double? = null
    private var destLon: Double? = null
    private val tracePoints = ArrayList<TraceStore.TracePoint>()
    private var origin: LatLon? = null
    private var awayFromOrigin = false

    private var autoStarted = false
    private var pendingStopAtMs: Long? = null
    private var lastMovingMs = 0L
    private var transitionsRegistered = false
    private var circleSyncStarted = false
    private var obdSpeedRefreshStarted = false

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

    /** Set in [onDestroy] before teardown so a late [reconcileObd2Connections]
     *  (via [endTrip]) can't re-dial an adapter as the service dies. */
    @Volatile private var destroyed = false

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

    @Volatile private var speedEventState = HardEventDetector.SpeedState()
    @Volatile private var headingEventState = HardEventDetector.HeadingState()
    /** Threaded into [HardEventDetector.onLeanSample] from [recordLean] — a
     *  car trip never calls it, so it only ever moves for a moto trip. */
    @Volatile private var leanCorneringNow = false
    @Volatile private var hardCornerCount = 0
    @Volatile private var hardBrakeCount = 0
    @Volatile private var hardAccelCount = 0
    @Volatile private var obd2SpeedFixes = 0
    @Volatile private var speedFixesTotal = 0
    // OBD2 engine summary, folded from each fix's telemetry snapshot in
    // onTripLocation for the duration of a trip.
    @Volatile private var obdMaxRpm = 0.0
    @Volatile private var obdMaxThrottlePct = 0.0
    @Volatile private var obdRpmSum = 0.0
    @Volatile private var obdRpmSamples = 0
    @Volatile private var obdWideOpenThrottleSamples = 0
    @Volatile private var obdThrottleSamples = 0
    // Fuel burned this trip: rate × elapsed, integrated per fix. Millilitres as a
    // Double while accumulating; rounded to a Long on the saved trip.
    @Volatile private var fuelMlAccum = 0.0
    @Volatile private var fuelSampledMeters = 0.0
    @Volatile private var lastFuelSampleMs = 0L
    @Volatile private var fuelWasEstimated = false
    @Volatile private var stopState = StopDetector.State()
    @Volatile private var tripLimitState = SpeedLimitTracker.State()
    @Volatile private var tripLimitFetchJob: kotlinx.coroutines.Job? = null
    @Volatile private var secondsOverLimit = 0.0
    @Volatile private var lastLimitFixMs = 0L
    @Volatile private var roadTypeState = RoadTypeTracker.State()
    @Volatile private var roadTypeFetchJob: kotlinx.coroutines.Job? = null

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

    /** Mirrors [freshBoardTelemetry] exactly — see its own KDoc for why
     *  staleness is gated on arrival time rather than trusting the source to
     *  say when it disconnected. */
    private fun freshObdTelemetry(): ObdTelemetry? {
        val telemetry = Obd2Connection.telemetry.value ?: return null
        val age = System.currentTimeMillis() - telemetry.receivedAtMs
        return if (age in 0..OBD_TELEMETRY_STALE_MS) telemetry else null
    }

    /** OBD2 -> board telemetry -> phone GPS, highest priority first, each used
     *  only while fresh. Single definition of the priority chain that
     *  onTripLocation's effectiveSpeedMps and _lastFix both read. [obd] defaults
     *  to a fresh snapshot; onTripLocation passes the one it already took for
     *  that fix so its speed, attribution and engine-summary reads agree. */
    private fun resolveDisplaySpeedMps(
        gpsSpeedMps: Double,
        mode: TravelMode,
        obd: ObdTelemetry? = freshObdTelemetry(),
    ): Double =
        obdSpeedMpsFrom(obd, gpsSpeedMps, mode)
            ?: freshBoardTelemetry()
                ?.takeIf { it.hasSpeed }
                ?.let { it.speedKmh / 3.6 }
            ?: gpsSpeedMps

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
        // actually leaning — see MIN_LEAN_SPEED_MPS. Skip the sample, but if the
        // bike is also upright, drop the corner latch: onLeanSample never runs
        // here to clear it, so a hard corner that bled off speed while leaned
        // (slow hairpin, braking to a stop mid-lean) would otherwise leave
        // leanCorneringNow stuck true and swallow the next corner. Still leaned
        // past the threshold → keep the latch, same as onHeadingFix holds it
        // through an unmeasurable fix rather than re-firing on a brief dip.
        if ((_stats.value?.currentSpeedMps ?: 0.0) < MIN_LEAN_SPEED_MPS) {
            if (abs(deg) < HardEventDetector.HARD_CORNER_LEAN_DEG) leanCorneringNow = false
            return
        }
        maxLeanDeg = maxOf(maxLeanDeg, abs(deg))
        if (abs(deg) > abs(segmentPeakLeanDeg)) segmentPeakLeanDeg = deg
        val (cornering, newEvent) = HardEventDetector.onLeanSample(leanCorneringNow, deg)
        leanCorneringNow = cornering
        if (newEvent) hardCornerCount++
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
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    if (Settings.vehicleDevices.value.containsKey(address)) {
                        connectedVehicles.remove(address) // move to newest
                        connectedVehicles.add(address)
                        refreshTripMode()
                    }
                    reconcileObd2Connections()
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    if (connectedVehicles.remove(address)) refreshTripMode()
                    reconcileObd2Connections()
                }
            }
        }
    }

    /** Turning the adapter off drops every link without an ACL_DISCONNECTED per
     *  device, so without this the car stays "connected" for the rest of the
     *  service's life and the next ride is logged as a drive. */
    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    if (connectedVehicles.isNotEmpty()) {
                        connectedVehicles.clear()
                        refreshTripMode()
                    }
                    Obd2Connection.disconnect()
                }
                BluetoothAdapter.STATE_ON -> {
                    seedConnectedVehicles()
                    // STATE_OFF called Obd2Connection.disconnect(); nothing
                    // re-dials a phone-initiated SPP link on its own. Reconcile
                    // picks it back up if a trip or the UI still wants it.
                    reconcileObd2Connections()
                }
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
        reconcileObd2Connections()
    }

    /** Which OBD2 adapter [Obd2Connection] should be on, or null to stay
     *  disconnected. See [pickObd2Address] for the rules. */
    private fun desiredObd2Address(): String? {
        val map = Settings.vehicleDevices.value
        val tripVehicle = resolvedVehicle()
        return pickObd2Address(
            tripActive = _stats.value != null,
            uiVisible = uiVisible,
            tripVehicleResolved = tripVehicle != null,
            tripVehicleObd2Address = tripVehicle?.obd2Address,
            connectedObd2Addresses = connectedVehicles.mapNotNull { map[it]?.obd2Address },
            configuredObd2Addresses = map.values.mapNotNull { it.obd2Address }.distinct(),
        )
    }

    /** Bring [Obd2Connection] in line with [desiredObd2Address]: drop a link to
     *  the wrong adapter (or any link at all when none is wanted), open one to
     *  the right adapter when idle. Called from every edge that can change the
     *  answer — trip start/stop, UI visibility ([ACTION_REFRESH]), a Bluetooth
     *  connect/disconnect/toggle, and a Settings change. Replaces the old
     *  unconditional dial-every-configured-adapter seed: a parked adapter is no
     *  longer retried around the clock (#96), and only the vehicle being driven
     *  is ever dialled, so an absent adapter can't block a present one (#97). */
    private fun reconcileObd2Connections() {
        if (destroyed) return
        val target = desiredObd2Address()
        if (Obd2Connection.linkedAddress.value.let { it != null && it != target }) {
            Obd2Connection.disconnect()
        }
        if (target != null && Obd2Connection.linkedAddress.value == null) {
            val v = Settings.vehicleDevices.value.values.firstOrNull { it.obd2Address == target }
            Obd2Connection.connect(
                applicationContext, target,
                fuelType = v?.fuelType ?: FuelType.PETROL,
                calibrationPct = v?.fuelCalibrationPct ?: 100,
            )
        }
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

    /** The connected mapped vehicle that classifies the trip. The heaviest
     *  mode wins (see [MODE_PRIORITY]), not the last to connect: the helmet
     *  intercom and the car radio can both be up while the bike sits in the
     *  garage. Null when no mapped device is connected. */
    private fun resolvedVehicle(): Settings.VehicleDevice? {
        val map = Settings.vehicleDevices.value
        return connectedVehicles.mapNotNull { map[it] }
            .maxByOrNull { MODE_PRIORITY.indexOf(it.mode) }
    }

    /** What the running trip is logged as — the resolved vehicle's mode
     *  (Cardo → moto, infotainment → car), else the spin tab's mode. The tab
     *  itself is never changed here: classification is the trip's, not the
     *  UI's. Whether a trip is worth keeping at all is decided in [endTrip]. */
    private fun resolvedMode(): TravelMode =
        resolvedVehicle()?.mode ?: Settings.tripMode.value

    /** Retag the running trip if its mode should change (a mapped device
     *  connected or left). Restarts motion sensors to match. */
    private fun refreshTripMode() {
        val mode = resolvedMode()
        if (_stats.value != null && _stats.value?.mode != mode) {
            _stats.update { it?.copy(mode = mode) }
            stopMotionSensors()
            startMotionSensors(mode)
            updateNotification()
        }
        reconcileObd2Connections()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Settings.init()
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

        // Circles' second sink on this same fix stream (see circleSyncLoop's
        // doc) - started once and left running for the life of this always-on
        // service, independent of trip/convoy state, same as the Bluetooth
        // watch above.
        if (!circleSyncStarted) {
            circleSyncStarted = true
            serviceScope.launch { circleSyncLoop() }
        }

        // The speed HUD reads [displaySpeedMps], which onLocation only recomputes
        // on a GPS callback. When fixes stretch out (tunnel, a phone in a pocket)
        // an OBD2 adapter keeps reporting speed every ~1s; refresh the resolved
        // speed off its telemetry so the dial keeps pace with the pairing screen
        // instead of freezing between fixes. Only [displaySpeedMps] — never
        // [_lastFix] — so section/limit/relay consumers keying off the fix
        // position aren't fed a stale-position, fresh-time step. Main dispatcher:
        // same thread onLocation writes on, so no race.
        if (!obdSpeedRefreshStarted) {
            obdSpeedRefreshStarted = true
            serviceScope.launch(Dispatchers.Main.immediate) {
                Obd2Connection.telemetry.collect { _ ->
                    if (_lastFix.value == null) return@collect
                    val refreshed = resolveDisplaySpeedMps(lastGpsSpeedMps, resolvedMode())
                    if (refreshed != _displaySpeedMps.value) _displaySpeedMps.value = refreshed
                }
            }
        }

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
            ACTION_REFRESH -> reconcileObd2Connections()
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
        speedEventState = HardEventDetector.SpeedState()
        headingEventState = HardEventDetector.HeadingState()
        leanCorneringNow = false
        hardCornerCount = 0
        hardBrakeCount = 0
        hardAccelCount = 0
        obd2SpeedFixes = 0
        speedFixesTotal = 0
        obdMaxRpm = 0.0
        obdMaxThrottlePct = 0.0
        obdRpmSum = 0.0
        obdRpmSamples = 0
        obdWideOpenThrottleSamples = 0
        obdThrottleSamples = 0
        fuelMlAccum = 0.0
        fuelSampledMeters = 0.0
        lastFuelSampleMs = 0L
        fuelWasEstimated = false
        stopState = StopDetector.State()
        tripLimitState = SpeedLimitTracker.State()
        tripLimitFetchJob?.cancel()
        tripLimitFetchJob = null
        secondsOverLimit = 0.0
        lastLimitFixMs = startTimeMs
        roadTypeState = RoadTypeTracker.State()
        roadTypeFetchJob?.cancel()
        roadTypeFetchJob = null
        lastMovingMs = System.currentTimeMillis()
        // Re-check what's actually linked: the set may have gone stale since the
        // last trip. Answers async, retagging through refreshTripMode.
        seedConnectedVehicles()
        // Classify by connected device / pace / tab; refined live as the trip runs.
        _stats.value = TripStats(startTimeMs = startTimeMs, distanceMeters = initialDistanceMeters)
        val mode = resolvedMode()
        _stats.value = _stats.value?.copy(mode = mode)
        reconcileObd2Connections()
        ensureLocationUpdates()
        startMotionSensors(mode)
        updateNotification()
    }

    /**
     * Returns the [kotlinx.coroutines.Job] doing the trip's save-and-notify tail
     * (null when nothing was worth saving), so [onDestroy] — the one caller that
     * cannot let this outlive its own teardown — can join it before tearing down
     * [serviceScope]. Every other call site discards the return value; that
     * remains source-compatible since none of them assigned or returned it.
     */
    private fun endTrip(): kotlinx.coroutines.Job? {
        val stats = _stats.value ?: return null
        val wasAuto = autoStarted
        stopMotionSensors()
        tripLimitFetchJob?.cancel()
        tripLimitFetchJob = null
        roadTypeFetchJob?.cancel()
        roadTypeFetchJob = null
        flushTrace()
        // An auto trip with no mapped vehicle that never left walking pace
        // wasn't a drive; don't save it under whatever mode the tab happened
        // to have selected. Judged the same way MIN_AUTO_TRIP_METERS judges
        // "never went anywhere" — a second false-positive filter, not a
        // classification.
        val looksLikeAWalk = stats.durationMs > SLOW_NO_VEHICLE_MIN_JUDGE_MS &&
            connectedVehicles.mapNotNull { Settings.vehicleDevices.value[it]?.mode }.isEmpty() &&
            (stats.distanceMeters / (stats.durationMs / 1000.0)) < SLOW_NO_VEHICLE_AVG_MAX_MPS &&
            stats.topSpeedMps < SLOW_NO_VEHICLE_TOP_MAX_MPS
        val worthSaving =
            if (wasAuto) stats.distanceMeters >= MIN_AUTO_TRIP_METERS && !looksLikeAWalk
            else stats.durationMs > 0
        var saveJob: kotlinx.coroutines.Job? = null
        if (worthSaving) {
            val durationSec = stats.durationMs / 1000.0
            val trip = Trip(
                startTimeMs = stats.startTimeMs,
                endTimeMs = System.currentTimeMillis(),
                distanceMeters = stats.distanceMeters,
                topSpeedMps = stats.topSpeedMps,
                maxLeanAngleDeg = maxLeanDeg,
                maxGForce = maxG,
                destinationLat = destLat,
                destinationLon = destLon,
                mode = stats.mode,
                drivingStats = DrivingStats(
                    hardBrakeCount = hardBrakeCount,
                    hardAccelCount = hardAccelCount,
                    hardCornerCount = hardCornerCount,
                    secondsOverLimit = secondsOverLimit.toLong(),
                    pctOverLimit = if (durationSec > 0) secondsOverLimit / durationSec * 100.0 else 0.0,
                    roadTypeMeters = roadTypeState.meters,
                    // Post-hoc, over the trace this trip just flushed above — see
                    // Curviness.traceScore's KDoc for why this can't run live.
                    twistinessScore = 0.0, // placeholder, replaced inside the launch below
                    stopCount = stopState.stopCount,
                    idleMs = stopState.idleMs,
                    obd2SpeedPct = if (speedFixesTotal > 0)
                        obd2SpeedFixes * 100.0 / speedFixesTotal else 0.0,
                    maxRpm = obdMaxRpm,
                    maxThrottlePct = obdMaxThrottlePct,
                    pctWideOpenThrottle = if (obdThrottleSamples > 0)
                        obdWideOpenThrottleSamples * 100.0 / obdThrottleSamples else 0.0,
                    avgRpm = if (obdRpmSamples > 0) obdRpmSum / obdRpmSamples else 0.0,
                    fuelMilliliters = fuelMlAccum.roundToLong(),
                    fuelSampledMeters = fuelSampledMeters.roundToLong(),
                    fuelEstimated = fuelWasEstimated,
                ),
            )
            // Two separate coroutines, not one: onDestroy's runBlocking joins
            // saveJob to guarantee the trip survives process death, and that join
            // must be bounded by a cheap file write, not by loadTripPoints — which
            // reads the whole traces.jsonl back and parses every line before
            // filtering to this trip's window (same class of cost HistoryScreen.kt's
            // own Dispatchers.IO comment documents for the smaller trips.json).
            // `trip` above is already fully built from this-instant state, so
            // nothing here needs to run before the field resets below.
            val save = serviceScope.launch {
                TripStore.save(trip)
                checkBadges()
                // Only tell the user about trips they didn't end themselves.
                if (wasAuto) TripEndedNotification.show(this@TripTrackingService, stats.startTimeMs)
            }
            saveJob = save
            // Unawaited — best-effort. onDestroy only joins saveJob above (and
            // then syncs itself), so if the process dies before this finishes the
            // trip still exists (saved above) with twistinessScore at its
            // placeholder default; only the expensive post-hoc score is lost, not
            // the whole trip. Joins `save` first: updateDrivingStats loads
            // trips.json and no-ops if the trip isn't there yet, and a bare
            // TripStore.save call has no dedup so it can't be used to race ahead.
            // syncQuietly() runs AFTER the twistiness write, not in saveJob: a
            // sync response applies via TripStore.replaceRaw (SyncClient.kt),
            // which overwrites the local trips file wholesale — syncing before
            // the write would let that response clobber it straight back to the
            // placeholder on a signed-in device.
            serviceScope.launch {
                save.join()
                val twistiness = runCatching {
                    Curviness.traceScore(loadTripPoints(trip).map { it.at })
                }.getOrDefault(0.0)
                TripStore.updateDrivingStats(trip.startTimeMs, trip.drivingStats.copy(twistinessScore = twistiness))
                SyncClient.syncQuietly()
            }
        }
        _stats.value = null
        reconcileObd2Connections()
        destLat = null
        destLon = null
        autoStarted = false
        pendingStopAtMs = null
        ensureLocationUpdates()
        updateNotification()
        return saveJob
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
        lastGpsSpeedMps = speed
        val fix = Fix(
            lat = location.latitude,
            lon = location.longitude,
            speedMps = resolveDisplaySpeedMps(speed, resolvedMode()),
            bearingDeg = if (location.hasBearing()) location.bearing else null,
            accuracyMeters = location.accuracy,
            timeMs = location.time,
            elapsedRealtimeMs = location.elapsedRealtimeNanos / 1_000_000L,
        )
        _lastFix.value = fix
        _displaySpeedMps.value = fix.speedMps
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

        // One OBD2 snapshot for this fix: the speed chain, the attribution
        // counter, the engine-summary fold and speedIsReal all read the same
        // values, so a poll landing mid-function can't make them disagree.
        val obd = freshObdTelemetry()

        // Best-available speed for the recorded-trip pipeline (hard-event / stop
        // detectors, SpeedLimitTracker, RoadTypeTracker, persisted topSpeedMps).
        // See resolveDisplaySpeedMps for the OBD2/board/GPS priority. `speed`
        // above still drives auto-start/stop and the fog trace, which stay on the
        // phone's own GPS pipeline regardless of what's paired.
        val effectiveSpeedMps = resolveDisplaySpeedMps(speed, stats.mode, obd)

        // Which source actually drove that number, for the per-trip
        // obd2SpeedPct. Same decision resolveDisplaySpeedMps uses for its OBD2
        // arm — board telemetry winning does not count, GPS fallback does not
        // count.
        // Non-null iff effectiveSpeedMps below is the OBD adapter's reading
        // (not board telemetry, not the GPS fallback). Drives both the per-trip
        // attribution counter and the recorded-trip fix clock (#98).
        val obdSpeedMps = obdSpeedMpsFrom(obd, speed, stats.mode)
        speedFixesTotal++
        if (obdSpeedMps != null) {
            obd2SpeedFixes++
        }

        // Engine summary: fold this fix's OBD2 telemetry into the accumulators
        // endTrip turns into DrivingStats.maxRpm/avgRpm/throttle. Sampled here
        // on the same snapshot and the same mode/freshness gate as the speed arm
        // above — it was a free-running Obd2Connection.telemetry collector, which
        // raced endTrip's non-suspending read of these vars and recorded
        // emissions the speed path would have rejected. onTripLocation only runs
        // mid-trip, so this is trip-scoped by construction.
        if (stats.mode.tracksGForce && obd != null) {
            if (obd.hasRpm) {
                obdMaxRpm = maxOf(obdMaxRpm, obd.rpmValue)
                obdRpmSum += obd.rpmValue
                obdRpmSamples++
            }
            if (obd.hasThrottle) {
                obdMaxThrottlePct = maxOf(obdMaxThrottlePct, obd.throttlePct)
                obdThrottleSamples++
                if (obd.throttlePct > WIDE_OPEN_THROTTLE_PCT) obdWideOpenThrottleSamples++
            }
            if (obd.hasFuelRate) {
                // Fuel is a rate, so it's integrated over time, not averaged like
                // RPM above: this fix's L/h held over the gap since the last fuel
                // sample, dropped (not saturated) when that gap is outside 1..15s.
                // The gap is measured on the OBD reading's own arrival clock
                // (receivedAtMs), not the GPS fix clock — a batched burst of
                // GPS fixes shares one location.time but the fuel readings that
                // arrived across it did not (#98).
                val fixMs = obd.receivedAtMs
                cappedFixDtSec(fixMs, lastFuelSampleMs)?.let { dtSec ->
                    fuelMlAccum += obd.fuelRateLph * (1000.0 / 3600.0) * dtSec
                    // Distance covered while a fuel reading was live — the L/100km
                    // denominator, so a mid-trip disconnect can't make a partial
                    // measurement look like a whole-trip figure.
                    fuelSampledMeters += (distance - stats.distanceMeters).coerceAtLeast(0.0)
                }
                lastFuelSampleMs = fixMs
                if (obd.fuelEstimated) fuelWasEstimated = true
            }
        }

        // speedOf() hands back a fabricated 0.0 sentinel for a coarse/no-speed fix
        // (see its own doc below) — not a real zero-speed measurement. Feeding
        // that into the physics-based detectors below as if it were real reads a
        // tunnel/parking-garage GPS gap as "suddenly stopped": a false hard brake,
        // and potentially a false stop. Real iff this fix's own hasSpeed() is set,
        // or fresh OBD2/board telemetry supplied the number effectiveSpeedMps is using.
        val speedIsReal = location.hasSpeed() ||
            freshBoardTelemetry()?.takeIf { it.hasSpeed } != null ||
            (stats.mode.tracksGForce && obd?.hasSpeed == true)

        // The hard-brake/accel and stop detectors derive Δt from the timestamp
        // passed here. When the speed reading came from the OBD adapter, use
        // that reading's own arrival clock so PID 0D's ~1 Hz jitter lands in
        // the Δt rather than being flattened to a nominal second (#98). A GPS
        // speed keeps the GPS clock. Heading-rate cornering stays on
        // location.time — its signal is the GPS bearing.
        val recordedFixMs = if (obdSpeedMps != null && obd != null) obd.receivedAtMs else location.time

        // Thresholds here are scoped to car/moto (tracksGForce) — a bike or walk
        // decelerating normally must not print a "hard brake" meant for a vehicle.
        // Cornering is separately gated: heading-rate below to CAR, lean-based
        // cornering (recordLean) to tracksLean.
        if (stats.mode.tracksGForce) {
            if (speedIsReal) {
                val speedResult = HardEventDetector.onSpeedFix(speedEventState, effectiveSpeedMps, recordedFixMs)
                speedEventState = speedResult.state
                if (speedResult.hardBrake) hardBrakeCount++
                if (speedResult.hardAccel) hardAccelCount++
            }
            // No speedIsReal guard needed: a fabricated 0.0 here just fails the
            // MIN_CORNER_SPEED_MPS gate harmlessly inside onHeadingFix.
            if (stats.mode == TravelMode.CAR && location.hasBearing()) {
                val (nextHeadingState, cornerEvent) = HardEventDetector.onHeadingFix(
                    headingEventState, location.bearing.toDouble(), effectiveSpeedMps, location.time)
                headingEventState = nextHeadingState
                if (cornerEvent) hardCornerCount++
            }
        }
        // Stops/speeding are meaningful for every mode, so no tracksGForce gate
        // here — but a fabricated zero must not open or resolve a stop candidate,
        // so this still needs the speedIsReal guard. Skipping entirely (rather
        // than feeding the sentinel) lets the state's stale lastFixMs carry
        // forward, so the next real fix's own Δt naturally spans the gap.
        if (speedIsReal) {
            stopState = StopDetector.onFix(stopState, effectiveSpeedMps, recordedFixMs)
        }

        val here = LatLon(location.latitude, location.longitude)
        val bearing = if (location.hasBearing()) location.bearing.toDouble() else null
        if (effectiveSpeedMps >= SpeedLimitTracker.MIN_MPS &&
            SpeedLimitTracker.needsWays(tripLimitState, here, now) &&
            tripLimitFetchJob?.isActive != true
        ) {
            tripLimitState = SpeedLimitTracker.fetchStarted(tripLimitState, now)
            // serviceScope is already Dispatchers.IO (`:1343`), so no withContext needed here.
            tripLimitFetchJob = serviceScope.launch {
                val ways = runCatching { RoadRoulette.speedLimitWays(here) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                    .getOrNull()
                tripLimitState = SpeedLimitTracker.withWays(tripLimitState, ways, here)
            }
        }
        tripLimitState = SpeedLimitTracker.onFix(tripLimitState, here, bearing, effectiveSpeedMps)
        val limitKmh = tripLimitState.limitKmh
        // Same speedIsReal guard as above, and the same reason: a fabricated
        // zero must not read as "suddenly under the limit" nor have its (bogus)
        // duration folded into secondsOverLimit. lastLimitFixMs is left stale on
        // a skipped fix so the next real fix's Δt naturally spans the gap.
        var currentlyOverLimitNow: Boolean? = null
        if (speedIsReal) {
            val over = limitKmh != null && effectiveSpeedMps * 3.6 > limitKmh * OVER_LIMIT_MARGIN
            if (over) cappedFixDtSec(location.time, lastLimitFixMs)?.let { secondsOverLimit += it }
            lastLimitFixMs = location.time
            currentlyOverLimitNow = over
        }

        // Reuses the hop the distance accumulator above already computed under both its
        // guards (accuracy AND recency) rather than tracking a third `lastFixLocation`
        // anchor with only the accuracy half of that gate — an accuracy-only guard would
        // let a post-tunnel/post-parking-garage GPS re-acquire, fully accurate but far from
        // the last real fix, attribute several kilometres to whatever class the reacquire
        // fix snaps to. `distance` already equals `stats.distanceMeters + hop` if the
        // accumulator's guard passed, or is unchanged if it didn't. No speedIsReal guard
        // needed here — this is driven by the accuracy+recency-gated distance hop, not
        // raw speed.
        val roadTypeHop = distance - stats.distanceMeters
        // Scoped to car/moto (tracksGForce), same reasoning as the hard-event
        // block above: a walk/bike's road-type mix isn't part of this stat.
        if (stats.mode.tracksGForce) {
            if (effectiveSpeedMps >= SpeedLimitTracker.MIN_MPS &&
                RoadTypeTracker.needsWays(roadTypeState, here, now) &&
                roadTypeFetchJob?.isActive != true
            ) {
                roadTypeState = RoadTypeTracker.fetchStarted(roadTypeState, now)
                // serviceScope is already Dispatchers.IO (`:1358`), so no withContext needed here.
                // Rethrow cancellation rather than let runCatching swallow it (same pattern
                // Task 4 established for SpeedLimitTracker's fetch) — RoadTypeTracker.fetchWays
                // is nullable with the identical null-vs-empty contract, so getOrNull, not
                // getOrDefault(emptyList()): collapsing a cancelled/failed fetch to emptyList()
                // would make withWays treat it as "confirmed no roads here."
                roadTypeFetchJob = serviceScope.launch {
                    val ways = runCatching { RoadTypeTracker.fetchWays(here) }
                        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                        .getOrNull()
                    roadTypeState = RoadTypeTracker.withWays(roadTypeState, ways, here)
                }
            }
            if (roadTypeHop > 0.0) {
                roadTypeState = RoadTypeTracker.onFix(roadTypeState, here, bearing, roadTypeHop)
            }
        }

        // update (not value =) so the 5 Hz sensor writes aren't clobbered here.
        _stats.update {
            it?.copy(
                durationMs = now - it.startTimeMs,
                distanceMeters = distance,
                currentSpeedMps = effectiveSpeedMps,
                topSpeedMps = maxOf(it.topSpeedMps, effectiveSpeedMps),
                hardBrakeCount = hardBrakeCount,
                hardAccelCount = hardAccelCount,
                hardCornerCount = hardCornerCount,
                stopCount = stopState.stopCount,
                // Carries the previous value forward on a fix with no real speed
                // measurement, rather than flickering the HUD signal off.
                currentlyOverLimit = currentlyOverLimitNow ?: it.currentlyOverLimit,
            )
        }
        // Pick up a mode-bar change made while the trip is running.
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
        if (!MunicipalityStore.needsLookup(p)) return
        lastMunicipalityLookupMs = now
        serviceScope.launch { MunicipalityStore.discoverQuietly(p) }
    }

    /**
     * Circles' second sink on [_lastFix] - the "one collector, two sinks"
     * rule from docs/CIRCLES_AND_CONVOYS.md section 10. This never opens its
     * own location request; it just samples whatever the mode-driven fused
     * request above is already producing, the same way [ConvoyLiveClient]'s
     * `forwardLocation` does for convoys.
     *
     * The decision every pass makes - which circles to post to, the geofence
     * evaluation, the transition recording, the idle backoff - lives in
     * [CirclePresence.tick] now (see its doc). This keeps only what
     * `commonMain` cannot have: the `while`/`delay` itself, the guard that a
     * fix actually exists to share, and [fixAgeMs] - monotonic, not wall
     * clock, so a device clock that drifts or is corrected mid-drive doesn't
     * answer "how old is this reading" wrong in whichever direction the
     * correction went. [Fix.timeMs] is the opposite question - wall clock,
     * what gets posted - and stays on the fix.
     */
    private suspend fun circleSyncLoop() {
        var interval = CirclePresence.ACTIVE_INTERVAL_MS
        while (true) {
            delay(interval)
            val fix = _lastFix.value ?: continue
            val fixAgeMs = SystemClock.elapsedRealtime() - fix.elapsedRealtimeMs
            interval = CirclePresence.tick(
                fix.lat, fix.lon, fix.accuracyMeters.toDouble(), fix.timeMs, fixAgeMs,
                System.currentTimeMillis(),
            )
        }
    }

    /** Rescore badges off the main thread and tell the user about new ones. */
    private fun checkBadges() {
        serviceScope.launch {
            val coverage = Coverage.compute()
            val newly = BadgeStore.refresh(BadgeStore.stats(coverage)).newlyEarned
            if (newly.isNotEmpty()) notifyBadgesEarned(newly)
            // The trip just saved was folded into the record incrementally, so
            // the badge check above already read the right numbers. This is the
            // TTL catching up, after the notification rather than before it.
            RiderTotals.refreshIfStale()
        }
    }

    private fun flushTrace(keepLast: Boolean = false) {
        if (tracePoints.isEmpty()) return
        TraceStore.append(tracePoints)
        val last = tracePoints.lastOrNull()
        tracePoints.clear()
        if (keepLast && last != null) tracePoints.add(last)
        _liveTrace.value = tracePoints.map { it.at }
    }

    /** Outlives no single trip: municipality discovery and badge rescoring
     *  both start as a trip *ends* and must not be cancelled by that. Torn
     *  down with the service in onDestroy. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        destroyed = true
        if (::fusedClient.isInitialized) {
            fusedClient.removeLocationUpdates(locationCallback)
        }
        if (btRegistered) {
            runCatching { unregisterReceiver(btReceiver) }
            runCatching { unregisterReceiver(btStateReceiver) }
            btRegistered = false
        }
        Obd2Connection.disconnect()
        // endTrip()'s save-and-notify tail runs on serviceScope (round-1 fix,
        // off the main thread on every other call site) — but the service is
        // dying right here, so cancelling that scope before the tail runs would
        // silently drop an in-flight trip. Join it before cancelling: a brief
        // main-thread block in this one terminal-teardown path beats losing the
        // trip. Every other endTrip() call site keeps running fully async.
        val saveJob = endTrip()
        if (saveJob != null) {
            kotlinx.coroutines.runBlocking { saveJob.join() }
            // endTrip's own syncQuietly() rides on the unawaited twistiness
            // coroutine, which serviceScope.cancel() below kills mid-compute —
            // so push the just-saved trip here instead. syncQuietly() runs on
            // its own scope and returns immediately; the placeholder
            // twistinessScore it carries is the same value that coroutine's
            // loss leaves on this device anyway.
            SyncClient.syncQuietly()
        }
        serviceScope.cancel()
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
            .setContentTitle(getString(R.string.app_name))
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

/** Fresh OBD2 vehicle speed in m/s from an already-taken [telemetry] snapshot,
 *  or null when there is none to trust. Pulled out of the service so one fix
 *  takes a single [com.jellemax.detour.obd2.Obd2Connection.telemetry] snapshot
 *  and feeds it to both the display-speed chain and the attribution counter,
 *  rather than each re-sampling and possibly disagreeing.
 *
 *  `mode.tracksGForce` is currently always true (only CAR and MOTO exist) and
 *  is kept for a future non-g-force mode. The real safeguard is
 *  [TripTrackingService.OBD_ZERO_OVERRIDE_MPS]: a hot dongle in a parked car
 *  reports ~0 km/h, so a near-zero reading is dropped when the phone's own GPS
 *  is sure the vehicle is moving. */
internal fun obdSpeedMpsFrom(
    telemetry: ObdTelemetry?,
    gpsSpeedMps: Double,
    mode: TravelMode,
): Double? = telemetry
    ?.takeIf { mode.tracksGForce && it.hasSpeed }
    ?.takeUnless { it.speedKmh < 1.0 && gpsSpeedMps > TripTrackingService.OBD_ZERO_OVERRIDE_MPS }
    ?.let { it.speedKmh / 3.6 }

/** Seconds between [lastMs] and [nowMs], or null when [lastMs] is unset (0) or
 *  the gap is outside 1..15_000 ms — a tunnel, a Doze window, a BT dropout.
 *  Dropping the Δt (rather than clamping it) means the *next* real fix's own
 *  gap spans the lost interval, instead of this fix inventing a saturated 15 s
 *  of fuel burn or over-limit time. Shared by the fuel integrator and
 *  secondsOverLimit; the trace-distance gate keeps its own GPS-clock check. */
internal fun cappedFixDtSec(nowMs: Long, lastMs: Long): Double? =
    (nowMs - lastMs).takeIf { lastMs > 0L && it in 1L..15_000L }?.let { it / 1000.0 }

/** Which OBD2 adapter the connection loop should be on right now, or null to
 *  stay disconnected. Pure so the connect/disconnect decision is testable
 *  without a service; the caller ([TripTrackingService.desiredObd2Address])
 *  gathers the inputs and acts on the result.
 *
 *  - nothing while parked with the app closed and no trip running (#96);
 *  - a running trip polls its resolved vehicle's adapter — that is the vehicle
 *    you are in, so the one-connection singleton never has to choose (#97). A
 *    resolved vehicle with no adapter means "no OBD for this trip"; only when
 *    NO vehicle resolved do we fall back to the sole configured adapter (and
 *    two-or-more configured is ambiguous, so nothing);
 *  - otherwise, while the UI is up, the first connected mapped vehicle that
 *    has an adapter. */
internal fun pickObd2Address(
    tripActive: Boolean,
    uiVisible: Boolean,
    tripVehicleResolved: Boolean,
    tripVehicleObd2Address: String?,
    connectedObd2Addresses: List<String>,
    configuredObd2Addresses: List<String>,
): String? {
    if (!tripActive && !uiVisible) return null
    if (tripActive) {
        // A resolved vehicle without a dongle means "no OBD for this trip",
        // NOT "guess from the configured set" — guessing dials some other
        // vehicle's adapter for the whole drive (#96).
        return if (tripVehicleResolved) tripVehicleObd2Address
        else configuredObd2Addresses.singleOrNull()
    }
    return connectedObd2Addresses.firstOrNull()
}

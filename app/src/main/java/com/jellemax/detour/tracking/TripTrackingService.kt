package com.jellemax.detour.tracking

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jellemax.detour.ble.BleNavServer
import com.jellemax.detour.ble.BoardTelemetry
import com.jellemax.detour.data.syncQuietly
import com.jellemax.detour.data.BadgeDef
import com.jellemax.detour.data.BadgeStore
import com.jellemax.detour.data.CirclePresence
import com.jellemax.detour.data.Coverage
import com.jellemax.detour.data.DrivingStats
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.MunicipalityStore
import com.jellemax.detour.data.RiderTotals
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.SyncClient
import com.jellemax.detour.data.TraceStore
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.data.TripStore
import com.jellemax.detour.drive.HardEventDetector
import com.jellemax.detour.drive.RoadTypeTracker
import com.jellemax.detour.drive.SpeedLimitTracker
import com.jellemax.detour.drive.StopDetector
import com.jellemax.detour.drive.TripFixMath
import com.jellemax.detour.obd2.Obd2Connection
import com.jellemax.detour.obd2.ObdTelemetry
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
    /** True while the current fix reads over the posted limit, by
     *  [SpeedLimitTracker.isOverLimit] — the same rule the speed dial reddens
     *  on, so the card cannot contradict the sign next to it. A live HUD
     *  signal, not a count; carries the previous value forward on a fix with no
     *  real speed measurement rather than flickering off. */
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

    /** Module-visible (not private) so [LocationRequests] and
     *  [currentLocationMode] — moved out to their own file, but this stays put
     *  per the tuning-lives-in-one-place rule — can reference it. */
    internal enum class LocationMode { SLEEP, IDLE, LIVE, PROBE, TRIP }

    companion object {
        const val EXTRA_DEST_LAT = "dest_lat"
        const val EXTRA_DEST_LON = "dest_lon"
        private const val ACTION_START_TRIP = "com.jellemax.detour.START_TRIP"
        private const val ACTION_END_TRIP = "com.jellemax.detour.END_TRIP"
        /** Not private: [DriveTransitions] builds the same PendingIntent action
         *  to (un)register with GMS - tuning/identifiers stay declared once, on
         *  this companion, and are referenced from there rather than copied. */
        internal const val ACTION_TRANSITION = "com.jellemax.detour.ACTIVITY_TRANSITION"
        private const val ACTION_REFRESH = "com.jellemax.detour.REFRESH"
        private const val ACTION_GEOFENCE_WAKE = "com.jellemax.detour.GEOFENCE_WAKE"

        /** How long a geofence wake keeps the service alive regardless of what
         *  activity recognition still thinks. The auto-start detector needs 8 s
         *  and 120 m above [FAST_SPEED_MPS] to confirm a drive, and a service
         *  starting cold can wait 10-20 s for its first usable fix, so this has
         *  to cover both with margin. Bounded so a false wake — the rider walked
         *  the dog past 150 m — costs one grace window of foreground service and
         *  then parks again, rather than staying up indefinitely. */
        private const val GEOFENCE_WAKE_GRACE_MS = 90_000L
        /** Retry delay after a failed [DriveTransitions.register] request
         *  (#144). Short: the failure this guards against is a transient
         *  Play Services race, not a real outage, so there is nothing gained
         *  by waiting longer - and the whole point is not stranding parking
         *  (#90) until some unrelated onStartCommand happens to arrive. */
        internal const val AR_REGISTER_RETRY_MS = 15_000L

        // Auto start/stop tuning. Not private: [TripStartDetector] and
        // [TripEndDetector] apply these. Same rule as [AR_REGISTER_RETRY_MS] and
        // [PROBE_WINDOW_MS] below — tuning stays declared once, here, and is
        // referenced from the collaborator rather than copied into it.
        internal const val FAST_SPEED_MPS = 7.0         // ~25 km/h, no vehicle hint
        internal const val PROBE_SPEED_MPS = 4.0        // ~14 km/h, IN_VEHICLE was seen
        internal const val FAST_FIXES_TO_START = 3
        internal const val MIN_FAST_RUN_MS = 8_000L
        internal const val MIN_FAST_RUN_METERS = 120.0
        /** Fixes looser than this never contribute to a start decision. */
        internal const val MAX_START_ACCURACY_M = 25f

        /** Loosest fix still worth *drawing*: the fog-of-war trace, and with it
         *  the auto-stop-at-origin check that rides on the same point. A scatter
         *  fix past this would paint explored ground the rider never saw. */
        private const val MAX_TRACE_ACCURACY_M = 50f
        /** Loosest fix still allowed to *bank distance* into the running trip.
         *  Deliberately a separate constant from [MAX_TRACE_ACCURACY_M] even
         *  though the two are equal today: one governs what is drawn, the other
         *  what is recorded as the rider's mileage, and tuning trace quality
         *  must not silently move an odometer. */
        private const val MAX_DISTANCE_ACCURACY_M = 50f
        /** Shortest gap between two fixes that can carry a Δt. A gap of 0 is a
         *  redelivered fix on a frozen clock, whose hop was banked already.
         *  Not private: [cappedFixDtSec] gates on the same floor. */
        internal const val MIN_FIX_GAP_MS = 1L
        /** Longest gap between two fixes that can still be treated as one
         *  continuous stretch — past this it is a tunnel, a Doze window or a BT
         *  dropout, and whatever happened in between was not observed. Shared
         *  with [cappedFixDtSec]: one window, two clocks (see its KDoc). */
        internal const val MAX_FIX_GAP_MS = 15_000L

        /** Not private: [DriveTransitions] opens the IN_VEHICLE confirmation
         *  window against this. */
        internal const val PROBE_WINDOW_MS = 3 * 60_000L
        /** A probe opened by speed alone, with no IN_VEHICLE to back it up. Kept
         *  short: one freak fix shouldn't buy three minutes of GPS. Not private:
         *  [DriveTransitions.startSpeedProbe] opens against this. */
        internal const val SPEED_PROBE_WINDOW_MS = 60_000L
        /** After IN_VEHICLE exit. Not private: [TripEndDetector] applies it. */
        internal const val EXIT_GRACE_MS = 2 * 60_000L
        /** Not private: [TripEndDetector] applies it. */
        internal const val STATIONARY_END_MS = 5 * 60_000L
        /** The worth-saving thresholds are not private: [TripSession.end]
         *  applies them. Same rule as [AR_REGISTER_RETRY_MS] and
         *  [PROBE_WINDOW_MS] above — tuning stays declared once, here, and is
         *  referenced from the collaborator rather than copied into it. */
        /** A trip shorter than this in ground covered was a mis-start, a nudge
         *  in the driveway, or a stop-and-go that never became a drive — not
         *  something worth a history row. Applies to every trip, auto or
         *  manually ended. */
        internal const val MIN_TRIP_METERS = 500.0
        /** ...and one shorter than this in wall-clock time, likewise. Both
         *  floors must clear before a trip is saved. */
        internal const val MIN_TRIP_DURATION_MS = 60_000L   // 60 s
        // A trip whose average pace stays under this, with no mapped vehicle
        // connected, was never a drive — a walk, a jog, pushing a bike. Judged
        // on average (not top) speed so one GPS spike can't rescue it, and
        // only after enough of the trip to tell a real walk from the first
        // slow seconds of a drive. Dropped at endTrip() rather than saved
        // under a mode that doesn't fit it.
        internal const val SLOW_NO_VEHICLE_AVG_MAX_MPS = 2.5    // ~9 km/h
        internal const val SLOW_NO_VEHICLE_MIN_JUDGE_MS = 90_000L
        /** ...but average pace alone calls a car stuck in town traffic slow.
         *  Nothing that has ever hit this speed gets dropped, whatever its average. */
        internal const val SLOW_NO_VEHICLE_TOP_MAX_MPS = 6.0    // ~22 km/h
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

        private val _stats = MutableStateFlow<TripStats?>(null)
        val stats: StateFlow<TripStats?> = _stats

        private val _lastFix = MutableStateFlow<Fix?>(null)
        val lastFix: StateFlow<Fix?> = _lastFix

        /** The mapped Bluetooth device currently classifying the trip, if any
         *  — see [VehicleLinks.resolvedVehicle]. Companion-level for the same
         *  reason [lastFix] is: [com.jellemax.detour.net.ConvoyLiveClient]
         *  reads it to decide whether this device's position frames carry a
         *  vehicle name (#158), and it has no instance of this service to
         *  reach into. */
        private val _resolvedVehicle = MutableStateFlow<Settings.VehicleDevice?>(null)
        val resolvedVehicle: StateFlow<Settings.VehicleDevice?> = _resolvedVehicle

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

        /** True from a successful startForeground() to onDestroy(). While the
         *  service is up its own `circleSyncLoop` ticks [CirclePresence], whose
         *  state is not thread-safe; the parked [com.jellemax.detour.notif.CircleSyncWorker]
         *  reads this and stands down so `tick()` is only ever driven from one
         *  place at a time. */
        @Volatile private var running = false

        fun circleSyncHandledByService(): Boolean = running

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

        /** [startMonitoring], but flagged so the dormancy check gives this start
         *  [GEOFENCE_WAKE_GRACE_MS] before it may park again — see
         *  [dormancyDecision]'s `justWokenByGeofence`. Without the flag the wake
         *  undoes itself before a single fix arrives. */
        fun startFromGeofenceWake(context: Context) {
            if (!canStart(context)) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, TripTrackingService::class.java)
                    .setAction(ACTION_GEOFENCE_WAKE),
            )
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

    /**
     * Where fixes come from (#306): the platform in a shipped app, and a route
     * read from this app's own files when a debug build arms the replay port.
     *
     * [LocationSources] chooses, by source set — this service names no Play
     * Services location API at all any more. See [currentLocationMode] for the
     * mode decision it acts on. Same guarded-init shape as [motionSensors]
     * below, since it needs a `Context` and the service's scope.
     */
    private lateinit var locationSource: LocationSource
    private lateinit var sensorManager: SensorManager
    /** The rotation-vector sensor and lean bookkeeping; see its own KDoc for
     *  why [recordLean] stays here rather than moving with it. */
    private lateinit var motionSensors: RideMotionSensors
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
    private var circleSyncStarted = false
    private var obdSpeedRefreshStarted = false

    /**
     * The drive's clock — real time in a shipped app, and a compressed one while
     * the replay rig is running (#307).
     *
     * A field read of [DriveClocks] rather than a constructor parameter because
     * a `Service` is constructed by the framework and cannot take one. Three
     * places in `app/` reach for the clock and no more — here, `LocalDriveClock`'s
     * default for the UI, and the car surface's `DetourCarSession`/`NavScreen`.
     * Everything downstream of those is *handed* it: the two detectors below,
     * `TripSession`, `DriveTransitions`, every composable. That is the rule
     * `CONTRIBUTING.md` states — "the core is handed things, it never reaches for
     * them" — and the nine scattered reads #307 replaced.
     */
    private val clock: DriveClock = DriveClocks.current

    /** The run of consecutive fast, accurate fixes that would start a trip.
     *  A plain class rather than three loose `var`s so its bar can be checked
     *  against literals — see its KDoc. */
    private val startDetector = TripStartDetector()

    /** The "still moving" clock and the two ways a trip ends by itself. Takes
     *  [clock], so a compressed replay's grace periods are the drive's. */
    private val endDetector = TripEndDetector(clock)

    /** Wall clock past which a geofence wake no longer protects this instance
     *  from re-parking; 0 when this start was not a geofence wake. Per-instance
     *  on purpose — the grace belongs to the wake, and a wake always brings a
     *  fresh instance up. */
    private var geofenceWakeGraceUntilMs = 0L

    /** Set once [stopDormant] has committed to the #90 stop path. Blocks the
     *  mode/notification machinery that callers still run after
     *  `maybeGoDormant()` returns, so a stopping service can't re-post an
     *  ongoing notification nothing will remove.
     *
     *  Cleared at [onStartCommand] entry, because a committed stop is not
     *  necessarily a completed one: the system cancels a pending bring-down
     *  when a start Intent arrives before `onDestroy()` runs, and that leaves
     *  *this* instance serving the new start. Latched shut it would live on
     *  with no location updates and no notification (issue #141). */
    @Volatile private var stopping = false

    /** The most recent startId the system has delivered to this instance.
     *  [stopDormant] stops against it rather than unconditionally, so a start
     *  Intent that lands while a dormancy stop is being decided supersedes the
     *  stop instead of racing it — see issue #141. */
    private var lastStartId = 0

    /** What this process last asked GMS to do with the park geofence; null
     *  until it has asked for anything. Records the *request*, not a confirmed
     *  registration — [ParkGeofence.arm] is fire-and-forget and only logs a
     *  failure — which is enough for its job of collapsing repeat calls, and
     *  self-heals on the next service start, since a fresh instance starts at
     *  null again. See [geofenceAction]. */
    private var geofenceRequested: Boolean? = null

    /** True between [requestDormancyEvaluation] and the evaluation it posted.
     *  See that function for why the evaluation is posted rather than run. */
    private var dormancyEvaluationPending = false

    /** Carries the coalesced dormancy evaluation. The bounded activity-
     *  transition registration retry (#144) has its own handler now, inside
     *  [driveTransitions] — see [DriveTransitions.cancelPendingRegister] for
     *  why [onDestroy] still has to reach it separately. */
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastMunicipalityLookupMs = 0L

    /** One delivery from whichever [LocationSource] is running — a batch,
     *  because an IDLE request is batched and the two calls after the loop are
     *  per-burst rather than per-fix. Eager, not guarded-init, for the same
     *  reason [driveTransitions] below is. */
    private val locationListener = LocationBatchListener { locations ->
        for (location in locations) onLocation(location)
        // Batched idle fixes arrive together and a probe window can lapse
        // between them; re-evaluate the mode once the burst is handled.
        ensureLocationUpdates()
        // A phone booted stationary in a garage gets its STILL ENTER before
        // any fix, so the evaluation bailed for want of a position. This is
        // where that first SLEEP fix arms the park geofence.
        requestDormancyEvaluation()
    }

    /** Activity-recognition registration, and the STILL/IN_VEHICLE state that
     *  drives auto-start/auto-sleep - see its own KDoc for the IN_VEHICLE
     *  probe-window coupling to the start detector. Eager, not guarded-init
     *  like [motionSensors]/[locationSource]: [buildNotification] reads
     *  [DriveTransitions.stationary] from inside `onStartCommand`'s
     *  `startForeground()` call, before that guarded-init block runs on a
     *  cold start - the same reason [locationListener] above is eager too. */
    private val driveTransitions = DriveTransitions(
        context = this,
        clock = clock,
        tripActive = { _stats.value != null },
        listener = object : DriveTransitions.Listener {
            override fun onVehicleEnter() {
                endDetector.onVehicleEnter()
                // Only ever a no-op reset while a trip is running - see
                // DriveTransitions' KDoc: the start detector's run is already
                // empty for the life of any trip, so folding this call in
                // unconditionally changes nothing observable.
                resetStartDetector()
            }

            override fun onVehicleExit() {
                // Don't end immediately — could be a fuel stop. The grace period
                // is checked against speed in onTripLocation.
                if (_stats.value != null && autoStarted) {
                    endDetector.onVehicleExit()
                }
            }

            override fun onStill() {
                resetStartDetector()
                flushTrace()
            }

            override fun onWalking() = resetStartDetector()
        },
    )

    /** Set in [onDestroy] before teardown, so a dormancy evaluation coalesced
     *  behind it ([maybeGoDormant], [requestDormancyEvaluation]) can't act on
     *  an instance that's already going away. [VehicleLinks] keeps its own
     *  copy of this same guard for its own reconcileObd2Connections. */
    @Volatile private var destroyed = false

    /** Throttles the 5 Hz stats publish out of [gForceListener]. Not part of
     *  the recorded trip, so it stays here rather than in [TripSession]. */
    private var lastSensorEmitMs = 0L

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
     *  onTripLocation's effectiveSpeedMps and _lastFix both read. [obd] and
     *  [board] default to fresh snapshots; onTripLocation passes the ones it
     *  already took for that fix so its speed, attribution, engine-summary and
     *  speedIsReal reads all agree. */
    private fun resolveDisplaySpeedMps(
        gpsSpeedMps: Double,
        mode: TravelMode,
        obd: ObdTelemetry? = freshObdTelemetry(),
        board: BoardTelemetry? = freshBoardTelemetry(),
    ): Double =
        obdSpeedMpsFrom(obd, gpsSpeedMps, mode)
            ?: board
                ?.takeIf { it.hasSpeed }
                ?.let { it.speedKmh / 3.6 }
            ?: gpsSpeedMps

    /**
     * Shared by [motionSensors]'s two trigger points (a fresh phone reading,
     * or a throttled poll of the board's own lean telemetry) — whichever is
     * currently authoritative reaches here, so the recorded max reflects one
     * source at a time, not whichever updated last. Stays on the service
     * rather than moving with the sensor: it needs the running trip's own
     * speed to gate a reading and [HardEventDetector]'s cornering latch,
     * neither of which [motionSensors] has any business holding.
     */
    private fun recordLean(deg: Double) {
        session.lastLeanDeg = deg
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
            if (abs(deg) < HardEventDetector.HARD_CORNER_LEAN_DEG) session.leanCorneringNow = false
            return
        }
        session.maxLeanDeg = maxOf(session.maxLeanDeg, abs(deg))
        motionSensors.notePeak(deg)
        val (cornering, newEvent) = HardEventDetector.onLeanSample(session.leanCorneringNow, deg)
        session.leanCorneringNow = cornering
        if (newEvent) session.hardCornerCount++
    }

    /**
     * G-force (accelerometer magnitude) only makes sense while a trip is
     * running, so this sensor is only registered between [beginTrip] and
     * [endTrip]. Lean now comes from [motionSensors] — [recordLean] above is
     * where its readings rejoin this trip's own state.
     */
    private val gForceListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (_stats.value == null) return
            val (x, y, z) = event.values
            val rawG = sqrt((x * x + y * y + z * z).toDouble()) /
                SensorManager.GRAVITY_EARTH
            // Drop single-sample shocks before they ever reach the EMA —
            // see MAX_G_SLEW.
            if (abs(rawG - session.currentG) <= MAX_G_SLEW) {
                session.currentG += G_EMA_ALPHA * (rawG - session.currentG)
                // MAX_PLAUSIBLE_G still guards the recorded max even
                // once a shock has been smoothed into currentG.
                if (session.currentG <= MAX_PLAUSIBLE_G) session.maxG = maxOf(session.maxG, session.currentG)
            }
            // Peaks are folded in on every event above; publishing them at 5 Hz
            // keeps the trip card live without recomposing it 100x a second.
            val now = SystemClock.elapsedRealtime()
            if (now - lastSensorEmitMs < SENSOR_EMIT_INTERVAL_MS) return
            lastSensorEmitMs = now
            _stats.update {
                it?.copy(
                    currentLeanAngleDeg = session.lastLeanDeg,
                    maxLeanAngleDeg = session.maxLeanDeg,
                    currentGForce = session.currentG,
                    maxGForce = session.maxG,
                )
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** Registers only the sensors this vehicle has a meaningful reading for, so
     *  a car trip never records a lean angle and a bicycle wakes neither sensor. */
    private fun startMotionSensors(mode: TravelMode) {
        motionSensors.start(mode)
        // SENSOR_DELAY_UI (~60ms) resolves a braking spike just as well as
        // SENSOR_DELAY_GAME (~20ms) and wakes the CPU a third as often.
        if (mode.tracksGForce) {
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager.registerListener(gForceListener, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    private fun stopMotionSensors() {
        sensorManager.unregisterListener(gForceListener)
        motionSensors.stop()
    }

    /** Bluetooth vehicle auto-detect and OBD2 link reconciliation — which
     *  mapped device is connected picks the trip mode, and which OBD2 adapter
     *  should be dialled. See [VehicleLinks]. */
    private val vehicleLinks = VehicleLinks(
        context = this,
        modePriority = MODE_PRIORITY,
        uiVisible = { uiVisible },
        currentTripMode = { _stats.value?.mode },
        onModeChanged = { mode ->
            _stats.update { it?.copy(mode = mode) }
            stopMotionSensors()
            startMotionSensors(mode)
            updateNotification()
        },
        onVehicleChanged = { vehicle -> _resolvedVehicle.value = vehicle },
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Both before anything else can consult them: this instance is alive and
        // serving a start, whatever a dormancy stop decided a moment ago. See
        // [lastStartId] and [stopping] for the two halves of issue #141.
        lastStartId = startId
        stopping = false
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
                TripNotifications.NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        }.isSuccess
        if (!foreground) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        if (!::locationSource.isInitialized) {
            locationSource = LocationSources.create(this, locationListener, serviceScope) {
                // Location permission pulled out from under an already-running
                // service - clear the notification now rather than leave it
                // dangling for the ~5 s until the process dies.
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        if (!::sensorManager.isInitialized) {
            sensorManager = getSystemService(SensorManager::class.java)
        }
        if (!::motionSensors.isInitialized) {
            motionSensors = RideMotionSensors(
                this, LEAN_EMA_ALPHA, MAX_LEAN_SLEW_DEG, SENSOR_EMIT_INTERVAL_MS, ::freshBoardTelemetry,
            ) { deg -> recordLean(deg) }
        }

        // Before the action, so a trip started in this same command classifies
        // against devices that were already connected when the service woke.
        vehicleLinks.start()

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
                    val refreshed = resolveDisplaySpeedMps(lastGpsSpeedMps, vehicleLinks.resolvedMode())
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
            ACTION_REFRESH -> vehicleLinks.reconcileObd2Connections()
            ACTION_GEOFENCE_WAKE -> {
                geofenceWakeGraceUntilMs = System.currentTimeMillis() + GEOFENCE_WAKE_GRACE_MS
                Log.i(ParkGeofence.TAG, "woken by geofence; holding for ${GEOFENCE_WAKE_GRACE_MS}ms")
            }
        }

        ensureLocationUpdates()
        driveTransitions.register()
        // No disarm here any more: applyGeofence() owns the fence and the single
        // evaluation this queues reconciles it, so an awake service takes it down
        // once instead of on every start (issue #146).
        requestDormancyEvaluation()
        return START_STICKY
    }

    /** [startTimeMs] backdates an auto-started trip to when the drive really
     *  began, rather than to the fix that finally proved it. */
    private fun beginTrip(
        auto: Boolean,
        startTimeMs: Long = clock.nowMs(),
        initialDistanceMeters: Double = 0.0,
    ) {
        autoStarted = auto
        origin = null
        awayFromOrigin = false
        driveTransitions.reset()
        resetStartDetector()
        motionSensors.resetLean()
        session.begin(startTimeMs)
        endDetector.onTripBegan()
        // Re-check what's actually linked: the set may have gone stale since the
        // last trip. Answers async, retagging through VehicleLinks.refreshTripMode.
        vehicleLinks.seedConnectedVehicles()
        // Classify by connected device / pace / tab; refined live as the trip runs.
        _stats.value = TripStats(startTimeMs = startTimeMs, distanceMeters = initialDistanceMeters)
        val mode = vehicleLinks.resolvedMode()
        _stats.value = _stats.value?.copy(mode = mode)
        vehicleLinks.reconcileObd2Connections()
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
        session.cancelFetchJobs()
        flushTrace()
        val saveJob = session.end(stats, wasAuto, destLat, destLon)
        _stats.value = null
        vehicleLinks.reconcileObd2Connections()
        destLat = null
        destLon = null
        autoStarted = false
        endDetector.onTripEnded()
        ensureLocationUpdates()
        updateNotification()
        if (saveJob != null) session.lastSaveJob = saveJob
        requestDormancyEvaluation()  // trip's over — nothing may need us foreground now
        return saveJob
    }

    /** (Re)request location updates matching the current mode - see
     *  [currentLocationMode] and [LocationRequests]. */
    private fun ensureLocationUpdates() {
        if (stopping) return
        val mode = currentLocationMode(
            hasActiveTrip = _stats.value != null,
            probing = driveTransitions.probing,
            uiVisible = uiVisible,
            convoyActive = convoyActive,
            stationary = driveTransitions.stationary,
        )
        if (locationSource.ensureFor(mode)) updateNotification()
    }

    /**
     * The stop path (issue #90). Reached once per main-thread pass via
     * [requestDormancyEvaluation], never called directly — the events that can
     * change whether anything still needs this service foreground raise a
     * request instead, and several of them fire within one pass. Idempotent
     * either way: a STAY_ALIVE decision does nothing and the ordinary mode
     * machinery runs as before.
     */
    private fun maybeGoDormant() {
        // onDestroy() calls endTrip(), whose tail raises a request — but the
        // service is already tearing down and joining the in-flight save. Don't
        // start a second teardown on top of it.
        if (destroyed) return
        val decision = dormancyDecision(
            autoDetect = Settings.autoDetectDrives.value,
            tripActive = _stats.value != null,
            convoyActive = convoyActive,  // companion field, same as currentLocationMode() reads
            uiVisible = uiVisible,
            stationary = driveTransitions.stationary,
            justWokenByGeofence = System.currentTimeMillis() < geofenceWakeGraceUntilMs,
        )
        // Resolved to what will actually be acted on *before* the geofence is
        // reconciled below, so a park we cannot honour doesn't leave a fence
        // armed behind a service that then stays up.
        val resolved = cannotParkReason()
            ?.takeIf { decision == DormancyDecision.STOP_WITH_GEOFENCE }
            ?.also { Log.i(ParkGeofence.TAG, "staying alive: $it") }
            ?.let { DormancyDecision.STAY_ALIVE }
            ?: decision

        applyGeofence(resolved)

        when (resolved) {
            DormancyDecision.STAY_ALIVE -> return
            DormancyDecision.STOP_WITH_GEOFENCE -> {
                Log.i(ParkGeofence.TAG, "parking: $resolved")
                // Superseded: the service stays up, so the fence just armed has
                // no job. Take it back down rather than leave a live service
                // behind a park geofence that can only fire a redundant wake.
                if (!stopDormant()) applyGeofence(DormancyDecision.STAY_ALIVE)
            }
            DormancyDecision.STOP_BARE -> {
                Log.i(ParkGeofence.TAG, "parking: $resolved (auto-detect off)")
                driveTransitions.unregister()
                // Nothing to restore if this is superseded: with auto-detect off
                // there is no fence to bring back, and DriveTransitions.register()
                // stands down on that same setting, so the newer start's own call
                // is a no-op too.
                stopDormant()
            }
        }
    }

    /**
     * Why this service cannot park behind a wake geofence right now, phrased
     * for the log; null when it can.
     *
     * Both answers are degradations to always-on rather than faults, and both
     * are silent to the rider — which is issue #145.
     */
    private fun cannotParkReason(): String? = when {
        // A geofence transition only reaches a backgrounded app with
        // ACCESS_BACKGROUND_LOCATION (Android 10+). Without it the fence would
        // never fire: the service would stop and never wake, and auto-detection
        // would silently die. Stay alive instead — the pre-#90 behaviour for
        // "while using the app" location users.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED -> "no ACCESS_BACKGROUND_LOCATION"
        // No position yet (booted stationary, no fix before STILL ENTER). The
        // location callback re-evaluates on the first fix.
        locationSource.lastKnownLatLon(lastLocation) == null -> "no position to arm at yet"
        else -> null
    }

    /**
     * The one place the park geofence is registered or removed (issue #146).
     *
     * Every caller that used to reach for [ParkGeofence] directly — including
     * `onStartCommand`'s unconditional `disarm()` — goes through here, so the
     * fence is reconciled against a decision rather than reissued per caller,
     * and the arm/disarm pair GMS was only assumed to serialise never forms.
     */
    private fun applyGeofence(decision: DormancyDecision) {
        when (geofenceAction(decision, geofenceRequested)) {
            GeofenceAction.NONE -> return
            GeofenceAction.ARM -> {
                val (lat, lon) = locationSource.lastKnownLatLon(lastLocation) ?: return
                ParkGeofence.arm(this, lat, lon)
                geofenceRequested = true
            }
            GeofenceAction.DISARM -> {
                ParkGeofence.disarm(this)
                geofenceRequested = false
            }
        }
    }

    /**
     * Ask for one dormancy evaluation, soon (issue #146).
     *
     * `maybeGoDormant()` used to be called directly from four places — this
     * function's callers — several of which run inside the same
     * `onStartCommand` pass, so a single service start commonly evaluated
     * dormancy two to four times and issued that many GMS round trips for one
     * fence. Eight `ParkGeofence` log lines in ~370 ms, all for the same circle
     * at the same position, which is also what made the log unreadable at
     * exactly the moment #140 needs to read it.
     *
     * Posting rather than running collapses them: every caller is on the main
     * thread (the location callback is requested with `Looper.getMainLooper()`),
     * so all the requests raised while one pass unwinds coalesce into the single
     * evaluation that runs once it has. The location callback is why this is not
     * simply "let `onStartCommand` be the one caller" as #146 sketches — it
     * fires long after any start has returned, and is the path that arms the
     * fence for a phone booted stationary in a garage.
     */
    private fun requestDormancyEvaluation() {
        if (destroyed || stopping || dormancyEvaluationPending) return
        dormancyEvaluationPending = true
        mainHandler.post {
            dormancyEvaluationPending = false
            maybeGoDormant()
        }
    }

    /**
     * Tear the foreground service down for [maybeGoDormant]. Returns false —
     * having changed nothing — when the stop was superseded.
     *
     * `stopSelfResult(lastStartId)`, not `stopSelf()`: this stop was decided
     * against the state of one particular start, and a start Intent that has
     * landed since means something wants the service after all. The unqualified
     * `stopSelf()` this replaces could not tell the two apart (issue #141).
     *
     * The ask comes *first*, before any of the teardown below, because the
     * teardown is not conditional-safe: cancelling the notification and
     * dropping location updates for a service the system then keeps alive
     * leaves it running and useless.
     */
    private fun stopDormant(): Boolean {
        if (!stopSelfResult(lastStartId)) {
            Log.i(ParkGeofence.TAG, "dormancy stop superseded by a newer start")
            return false
        }
        stopping = true
        if (::locationSource.isInitialized) locationSource.stop()
        flushTrace()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(TripNotifications.NOTIFICATION_ID)
        return true
    }

    /** Wiring for [DriveTransitions.onTransitionIntent]: the trailing
     *  re-evaluation is service-only (a STILL ENTER may have just parked us),
     *  so it stays here rather than in the collaborator. */
    private fun handleTransition(intent: Intent) {
        driveTransitions.onTransitionIntent(intent)
        ensureLocationUpdates()
        requestDormancyEvaluation()  // a STILL ENTER may have just parked us
    }

    private fun resetStartDetector() {
        startDetector.reset()
    }

    private fun onLocation(location: Location) {
        // Debug builds only, and by source set rather than by a flag: the gate
        // in a release build is a function returning true. It throws out the
        // real position that fused blends into a mock replay (#47) — which is
        // what a compressed replay's trip duration depends on, since a blended
        // fix from hundreds of kilometres away is movement over the 2.0 m/s
        // gate and stops the trip ever ending. Ahead of everything, so a
        // rejected fix reaches no state at all.
        if (!ReplayFixGate.accept(location)) return
        val speed = speedOf(location)
        lastGpsSpeedMps = speed
        val fix = Fix(
            lat = location.latitude,
            lon = location.longitude,
            speedMps = resolveDisplaySpeedMps(speed, vehicleLinks.resolvedMode()),
            bearingDeg = if (location.hasBearing()) location.bearing else null,
            accuracyMeters = location.accuracy,
            timeMs = clock.fixTimeMs(location.time),
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
        if (location.accuracy <= MAX_TRACE_ACCURACY_M) {
            addTracePoint(
                LatLon(location.latitude, location.longitude),
                clock.fixTimeMs(location.time),
                speed,
            )
        }
        // Whether auto-detection is on at all is a Settings read, so it stays
        // here rather than inside the detector — which knows only about fixes.
        if (!Settings.autoDetectDrives.value) {
            resetStartDetector()
            return
        }

        val probing = driveTransitions.probing
        val decision = startDetector.onFix(
            accuracyM = location.accuracy,
            speed = speed,
            at = LatLon(location.latitude, location.longitude),
            fixTimeMs = location.time,
            probing = probing,
        )
        if (decision == TripStartDetector.Decision.Idle) return

        // One accurate fix at driving speed is enough to *look closer*, and that
        // is the whole reason a drive used to take minutes to notice: we waited
        // for IN_VEHICLE, then confirmed against fixes that arrived every 20 s.
        // Escalating here puts us on 4 s fixes immediately — the run below is
        // then confirmed in seconds. The evidence bar for starting is unchanged.
        // Before beginTrip, as it always was: beginTrip's driveTransitions.reset()
        // clears the window this opens.
        if (!probing) driveTransitions.startSpeedProbe()

        if (decision is TripStartDetector.Decision.Start) {
            beginTrip(
                auto = true,
                startTimeMs = decision.startTimeMs,
                initialDistanceMeters = decision.distanceMeters,
            )
        }
    }

    /** The trip's distance with this fix's hop added, or unchanged when the fix
     *  fails [TripFixMath.distanceHopMeters]'s accuracy/recency gate. */
    private fun accumulateDistance(location: Location, stats: TripStats): Double {
        val last = lastLocation
        return stats.distanceMeters + TripFixMath.distanceHopMeters(
            rawHopMeters = last?.distanceTo(location)?.toDouble() ?: 0.0,
            lastFixMs = last?.time,
            fixMs = location.time,
            accuracyM = location.accuracy,
            maxAccuracyM = MAX_DISTANCE_ACCURACY_M,
            minGapMs = MIN_FIX_GAP_MS,
            maxGapMs = MAX_FIX_GAP_MS,
        )
    }

    /** Extends the fog-of-war trace with this fix and, riding on the same
     *  point, watches for the trip closing back on where it started.
     *  Returns true if it ended the trip. */
    private fun appendTracePoint(
        location: Location,
        speed: Double,
        stats: TripStats,
        now: Long,
    ): Boolean {
        // Negated `<=` rather than `>`, same reason as TripFixMath.distanceHopMeters:
        // every comparison with a NaN accuracy is false, so `>` would fall through
        // and append the point to the persisted trace. No usable accuracy, no draw.
        if (!(location.accuracy <= MAX_TRACE_ACCURACY_M)) return false
        val p = LatLon(location.latitude, location.longitude)
        addTracePoint(p, clock.fixTimeMs(location.time), speed)

        // Auto-stop when back at the starting point after a real trip.
        if (origin == null) origin = p
        val start = origin ?: return false
        val fromStart = RoadRoulette.distanceMeters(p, start)
        if (fromStart > 400) awayFromOrigin = true
        if (awayFromOrigin && fromStart < 120 &&
            now - stats.startTimeMs > 5 * 60_000
        ) {
            endTrip()
            return true
        }
        return false
    }

    /** Keeps the "still moving" clock, then decides whether the rider has left
     *  the vehicle for good. Returns true if it ended the trip. The decision
     *  itself is [TripEndDetector]'s; ending the trip is this service's. */
    private fun checkVehicleExit(speed: Double, now: Long): Boolean {
        if (!endDetector.shouldEnd(speed, autoStarted, now)) return false
        endTrip()
        return true
    }

    /** Everything the rest of the per-fix pipeline needs to know about this
     *  fix's speed, decided once from one telemetry snapshot so no two readers
     *  can disagree about it. */
    private data class FixSpeed(
        val obd: ObdTelemetry?,
        val effectiveMps: Double,
        val isReal: Boolean,
        val recordedFixMs: Long,
    )

    private fun resolveSpeed(location: Location, speed: Double, stats: TripStats): FixSpeed {
        // One OBD2 snapshot for this fix: the speed chain, the attribution
        // counter, the engine-summary fold and speedIsReal all read the same
        // values, so a poll landing mid-function can't make them disagree.
        val obd = freshObdTelemetry()
        // Same rule for the board's BLE telemetry, and for the same reason: the
        // speed chain and speedIsReal below both consult it, and a packet
        // landing between two reads would let speedIsReal vouch for a number
        // effectiveSpeedMps never saw (or drop a fix it did).
        val board = freshBoardTelemetry()

        // Best-available speed for the recorded-trip pipeline (hard-event / stop
        // detectors, SpeedLimitTracker, RoadTypeTracker, persisted topSpeedMps).
        // See resolveDisplaySpeedMps for the OBD2/board/GPS priority. `speed`
        // still drives auto-start/stop and the fog trace, which stay on the
        // phone's own GPS pipeline regardless of what's paired.
        val effectiveSpeedMps = resolveDisplaySpeedMps(speed, stats.mode, obd, board)

        // Which source actually drove that number, for the per-trip
        // obd2SpeedPct. Same decision resolveDisplaySpeedMps uses for its OBD2
        // arm — board telemetry winning does not count, GPS fallback does not
        // count.
        // Non-null iff effectiveSpeedMps is the OBD adapter's reading
        // (not board telemetry, not the GPS fallback). Drives both the per-trip
        // attribution counter and the recorded-trip fix clock (#98).
        val obdSpeedMps = obdSpeedMpsFrom(obd, speed, stats.mode)
        session.speedFixesTotal++
        if (obdSpeedMps != null) {
            session.obd2SpeedFixes++
        }

        return FixSpeed(
            obd = obd,
            effectiveMps = effectiveSpeedMps,
            // speedOf() hands back a fabricated 0.0 sentinel for a coarse/no-speed
            // fix (see its own doc) — not a real zero-speed measurement. Feeding
            // that into the physics-based detectors as if it were real reads a
            // tunnel/parking-garage GPS gap as "suddenly stopped": a false hard
            // brake, and potentially a false stop. Real iff this fix's own
            // hasSpeed() is set, or fresh OBD2/board telemetry supplied the number
            // effectiveSpeedMps is using.
            isReal = TripFixMath.speedIsReal(
                fixHasSpeed = location.hasSpeed(),
                boardHasSpeed = board != null && board.hasSpeed,
                modeTracksGForce = stats.mode.tracksGForce,
                obdHasSpeed = obd != null && obd.hasSpeed,
            ),
            // The hard-brake/accel and stop detectors derive Δt from this
            // timestamp. When the speed reading came from the OBD adapter, use
            // that reading's own arrival clock so PID 0D's ~1 Hz jitter lands in
            // the Δt rather than being flattened to a nominal second (#98). A GPS
            // speed keeps the GPS clock. Heading-rate cornering stays on
            // location.time — its signal is the GPS bearing.
            recordedFixMs = TripFixMath.recordedFixMs(
                obdDroveSpeed = obdSpeedMps != null,
                obdReceivedAtMs = obd?.receivedAtMs,
                locationTimeMs = location.time,
            ),
        )
    }

    /**
     * Folds this fix's OBD2 telemetry into the accumulators endTrip turns into
     * DrivingStats.maxRpm/avgRpm/throttle. Sampled here on the same snapshot
     * and the same mode/freshness gate as the speed arm — it was a free-running
     * Obd2Connection.telemetry collector, which raced endTrip's non-suspending
     * read of these vars and recorded emissions the speed path would have
     * rejected. onTripLocation only runs mid-trip, so this is trip-scoped by
     * construction.
     *
     * [hopMeters] is the distance this fix banked, for the L/100km denominator.
     */
    private fun foldEngineSummary(obd: ObdTelemetry?, stats: TripStats, hopMeters: Double) {
        if (!stats.mode.tracksGForce || obd == null) return
        if (obd.hasRpm) {
            session.obdMaxRpm = maxOf(session.obdMaxRpm, obd.rpmValue)
            session.obdRpmSum += obd.rpmValue
            session.obdRpmSamples++
        }
        if (obd.hasThrottle) {
            session.obdMaxThrottlePct = maxOf(session.obdMaxThrottlePct, obd.throttlePct)
            session.obdThrottleSamples++
            if (obd.throttlePct > WIDE_OPEN_THROTTLE_PCT) session.obdWideOpenThrottleSamples++
        }
        if (!obd.hasFuelRate) return
        // Fuel is a rate, so it's integrated over time, not averaged like
        // RPM above: this fix's L/h held over the gap since the last fuel
        // sample, dropped (not saturated) when that gap is outside 1..15s.
        // The gap is measured on the OBD reading's own arrival clock
        // (receivedAtMs), not the GPS fix clock: fuelRateLph was valid at
        // that instant, and the ~1 Hz OBD poll cadence is not the GPS
        // fix cadence. It also keeps integrating correctly through a
        // short GPS-staleness window (a redelivered stale fix freezes
        // location.time) as long as the adapter is alive and the gap is
        // inside the 15s cap (#98).
        val fixMs = obd.receivedAtMs
        cappedFixDtSec(fixMs, session.lastFuelSampleMs)?.let { dtSec ->
            session.fuelMlAccum += obd.fuelRateLph * (1000.0 / 3600.0) * dtSec
            // Distance covered while a fuel reading was live — the L/100km
            // denominator, so a mid-trip disconnect can't make a partial
            // measurement look like a whole-trip figure.
            session.fuelSampledMeters += hopMeters.coerceAtLeast(0.0)
        }
        session.lastFuelSampleMs = fixMs
        if (obd.fuelEstimated) session.fuelWasEstimated = true
    }

    private fun detectHardEvents(location: Location, stats: TripStats, fix: FixSpeed) {
        // Thresholds here are scoped to car/moto (tracksGForce) — a bike or walk
        // decelerating normally must not print a "hard brake" meant for a vehicle.
        // Cornering is separately gated: heading-rate below to CAR, lean-based
        // cornering (recordLean) to tracksLean.
        if (stats.mode.tracksGForce) {
            if (fix.isReal) {
                val speedResult =
                    HardEventDetector.onSpeedFix(session.speedEventState, fix.effectiveMps, fix.recordedFixMs)
                session.speedEventState = speedResult.state
                if (speedResult.hardBrake) session.hardBrakeCount++
                if (speedResult.hardAccel) session.hardAccelCount++
            }
            // No speedIsReal guard needed: a fabricated 0.0 here just fails the
            // MIN_CORNER_SPEED_MPS gate harmlessly inside onHeadingFix.
            if (stats.mode == TravelMode.CAR && location.hasBearing()) {
                val (nextHeadingState, cornerEvent) = HardEventDetector.onHeadingFix(
                    session.headingEventState, location.bearing.toDouble(), fix.effectiveMps, location.time)
                session.headingEventState = nextHeadingState
                if (cornerEvent) session.hardCornerCount++
            }
        }
        // Stops/speeding are meaningful for every mode, so no tracksGForce gate
        // here — but a fabricated zero must not open or resolve a stop candidate,
        // so this still needs the speedIsReal guard. Skipping entirely (rather
        // than feeding the sentinel) lets the state's stale lastFixMs carry
        // forward, so the next real fix's own Δt naturally spans the gap.
        if (fix.isReal) {
            session.stopState = StopDetector.onFix(session.stopState, fix.effectiveMps, fix.recordedFixMs)
        }
    }

    /** Advances the trip's speed-limit state for this fix (fetching ways when
     *  the tracker asks for them) and folds any time spent over the limit into
     *  `secondsOverLimit`. Null when the fix carried no real speed measurement. */
    private fun updateSpeedLimit(location: Location, fix: FixSpeed, now: Long): Boolean? {
        val here = LatLon(location.latitude, location.longitude)
        val bearing = if (location.hasBearing()) location.bearing.toDouble() else null
        if (fix.effectiveMps >= SpeedLimitTracker.MIN_MPS &&
            SpeedLimitTracker.needsWays(session.tripLimitState, here, now) &&
            session.tripLimitFetchJob?.isActive != true
        ) {
            session.tripLimitState = SpeedLimitTracker.fetchStarted(session.tripLimitState, now)
            // serviceScope is already Dispatchers.IO, so no withContext needed here.
            session.tripLimitFetchJob = serviceScope.launch {
                val ways = runCatching { RoadRoulette.speedLimitWays(here) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                    .getOrNull()
                session.tripLimitState = SpeedLimitTracker.withWays(session.tripLimitState, ways, here)
            }
        }
        session.tripLimitState = SpeedLimitTracker.onFix(session.tripLimitState, here, bearing, fix.effectiveMps)
        val limitKmh = session.tripLimitState.limitKmh
        // Same speedIsReal guard as the detectors, and the same reason: a
        // fabricated zero must not read as "suddenly under the limit" nor have
        // its (bogus) duration folded into secondsOverLimit. lastLimitFixMs is
        // left stale on a skipped fix so the next real fix's Δt spans the gap.
        if (!fix.isReal) return null
        val over = SpeedLimitTracker.isOverLimit(fix.effectiveMps * 3.6, limitKmh)
        if (over) cappedFixDtSec(location.time, session.lastLimitFixMs)?.let { session.secondsOverLimit += it }
        session.lastLimitFixMs = location.time
        return over
    }

    /**
     * Attributes this fix's banked distance to a road class, fetching ways when
     * the tracker asks for them.
     *
     * [hopMeters] reuses the hop the distance accumulator already computed under
     * both its guards (accuracy AND recency) rather than tracking a third
     * `lastFixLocation` anchor with only the accuracy half of that gate — an
     * accuracy-only guard would let a post-tunnel/post-parking-garage GPS
     * re-acquire, fully accurate but far from the last real fix, attribute
     * several kilometres to whatever class the reacquire fix snaps to. It is
     * zero when the accumulator's guard did not pass. No speedIsReal guard
     * needed here — this is driven by the accuracy+recency-gated distance hop,
     * not raw speed.
     */
    private fun updateRoadType(
        location: Location,
        stats: TripStats,
        fix: FixSpeed,
        hopMeters: Double,
        now: Long,
    ) {
        // Scoped to car/moto (tracksGForce), same reasoning as the hard-event
        // block: a walk/bike's road-type mix isn't part of this stat.
        if (!stats.mode.tracksGForce) return
        val here = LatLon(location.latitude, location.longitude)
        if (fix.effectiveMps >= SpeedLimitTracker.MIN_MPS &&
            RoadTypeTracker.needsWays(session.roadTypeState, here, now) &&
            session.roadTypeFetchJob?.isActive != true
        ) {
            session.roadTypeState = RoadTypeTracker.fetchStarted(session.roadTypeState, now)
            // serviceScope is already Dispatchers.IO, so no withContext needed here.
            // Rethrow cancellation rather than let runCatching swallow it (same pattern
            // Task 4 established for SpeedLimitTracker's fetch) — RoadTypeTracker.fetchWays
            // is nullable with the identical null-vs-empty contract, so getOrNull, not
            // getOrDefault(emptyList()): collapsing a cancelled/failed fetch to emptyList()
            // would make withWays treat it as "confirmed no roads here."
            session.roadTypeFetchJob = serviceScope.launch {
                val ways = runCatching { RoadTypeTracker.fetchWays(here) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                    .getOrNull()
                session.roadTypeState = RoadTypeTracker.withWays(session.roadTypeState, ways, here)
            }
        }
        if (hopMeters > 0.0) {
            val bearing = if (location.hasBearing()) location.bearing.toDouble() else null
            session.roadTypeState = RoadTypeTracker.onFix(session.roadTypeState, here, bearing, hopMeters)
        }
    }

    private fun onTripLocation(location: Location, speed: Double, stats: TripStats) {
        // The drive's clock, not the wall's: at 5x replay these dwell and
        // duration comparisons have to be against the drive they describe.
        // See DriveClock for what deliberately stays on the platform clock.
        val now = clock.nowMs()

        val distance = accumulateDistance(location, stats)
        // The one hop this fix banked, reused by the fuel-economy denominator and
        // by road-type attribution rather than measured a second time.
        val hopMeters = TripFixMath.roadTypeHopMeters(distance, stats.distanceMeters)

        if (appendTracePoint(location, speed, stats, now)) return

        if (checkVehicleExit(speed, now)) return

        val fix = resolveSpeed(location, speed, stats)

        foldEngineSummary(fix.obd, stats, hopMeters)
        detectHardEvents(location, stats, fix)
        val currentlyOverLimitNow = updateSpeedLimit(location, fix, now)
        updateRoadType(location, stats, fix, hopMeters, now)

        // update (not value =) so the 5 Hz sensor writes aren't clobbered here.
        _stats.update {
            it?.copy(
                durationMs = now - it.startTimeMs,
                distanceMeters = distance,
                currentSpeedMps = fix.effectiveMps,
                topSpeedMps = maxOf(it.topSpeedMps, fix.effectiveMps),
                hardBrakeCount = session.hardBrakeCount,
                hardAccelCount = session.hardAccelCount,
                hardCornerCount = session.hardCornerCount,
                stopCount = session.stopState.stopCount,
                // Carries the previous value forward on a fix with no real speed
                // measurement, rather than flickering the HUD signal off.
                currentlyOverLimit = currentlyOverLimitNow ?: it.currentlyOverLimit,
            )
        }
        // Pick up a mode-bar change made while the trip is running.
        vehicleLinks.refreshTripMode()
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
                leanDeg = motionSensors.peakLeanSinceLastTracePoint(),
            )
        )
        motionSensors.resetPeakLean()
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
     *
     * Since #90 this loop only runs while the service is alive (trip, convoy,
     * visible map). The parked case moved to
     * [com.jellemax.detour.notif.CircleSyncWorker], which stands down whenever
     * this service is up ([circleSyncHandledByService]) — so the two never tick
     * [CirclePresence] concurrently.
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
            PlaceGeofenceGate.sync(this@TripTrackingService, CirclePresence.lastGateCandidates)
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

    /** The running trip's own recorded state and its two ends — see
     *  [TripSession]. Declared after [serviceScope] because it takes it by
     *  value; a `val` above that line would capture an uninitialised scope.
     *  Private, which is what keeps [TripSession.end] — and with it the
     *  [SyncClient.syncQuietly] on its tail — reachable only through this
     *  service's own [endTrip]. */
    private val session = TripSession(
        context = this,
        scope = serviceScope,
        resolvedVehicle = vehicleLinks::resolvedVehicle,
        clock = clock,
        checkBadges = ::checkBadges,
    )

    override fun onDestroy() {
        destroyed = true
        // A coalesced evaluation may still be queued behind this teardown.
        // maybeGoDormant() would bail on `destroyed` anyway; dropping it keeps
        // the handler from holding this instance past its own destruction.
        mainHandler.removeCallbacksAndMessages(null)
        driveTransitions.cancelPendingRegister()
        if (::locationSource.isInitialized) locationSource.stop()
        vehicleLinks.stop()
        // endTrip()'s save-and-notify tail runs on serviceScope (round-1 fix,
        // off the main thread on every other call site) — but the service is
        // dying right here, so cancelling that scope before the tail runs would
        // silently drop an in-flight trip. Join it before cancelling: a brief
        // main-thread block in this one terminal-teardown path beats losing the
        // trip. Every other endTrip() call site keeps running fully async.
        // `?: lastSaveJob?.takeIf { it.isActive }` — a dormancy stop from
        // endTrip()'s tail already nulled _stats, so the call above returns null
        // while its save is still running; join that one instead. `isActive`
        // keeps the old "only sync when a trip just ended" behaviour: a save
        // that already finished needs neither a join nor another sync.
        val saveJob = endTrip() ?: session.lastSaveJob?.takeIf { it.isActive }
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
        serviceScope.cancel()  // kills circleSyncLoop — only now does the worker take the tick back
        running = false
        flushTrace()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val notifications = TripNotifications(this)

    private fun createChannel() = notifications.createChannel()

    private fun notifyBadgesEarned(badges: List<BadgeDef>) = notifications.badgesEarned(badges)

    private fun updateNotification() {
        if (stopping) return
        notifications.update(_stats.value, driveTransitions.stationary, ACTION_END_TRIP)
    }

    private fun buildNotification(): android.app.Notification =
        notifications.build(_stats.value, driveTransitions.stationary, ACTION_END_TRIP)
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
 *  the gap is outside [TripTrackingService.MIN_FIX_GAP_MS]..[TripTrackingService.MAX_FIX_GAP_MS]
 *  — a tunnel, a Doze window, a BT dropout.
 *  Dropping the Δt (rather than clamping it) means the *next* real fix's own
 *  gap spans the lost interval, instead of this fix inventing a saturated 15 s
 *  of fuel burn or over-limit time. Shared by the fuel integrator and
 *  secondsOverLimit; the trace-distance gate keeps its own GPS-clock check.
 *
 *  Same window as the distance accumulator's, but not the same clock: that gate
 *  always measures GPS fix times, while this one measures whichever clock the
 *  caller passes — the OBD reading's arrival time for the fuel accumulators,
 *  location.time for secondsOverLimit. Kept separate for exactly that reason —
 *  do not fold one into the other. */
internal fun cappedFixDtSec(nowMs: Long, lastMs: Long): Double? =
    (nowMs - lastMs)
        .takeIf {
            lastMs > 0L &&
                it in TripTrackingService.MIN_FIX_GAP_MS..TripTrackingService.MAX_FIX_GAP_MS
        }
        ?.let { it / 1000.0 }

/** Which OBD2 adapter the connection loop should be on right now, or null to
 *  stay disconnected. Pure so the connect/disconnect decision is testable
 *  without a service; the caller ([VehicleLinks.desiredObd2Address])
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

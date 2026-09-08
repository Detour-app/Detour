package com.jellemax.detour.tracking

import android.content.Context
import com.jellemax.detour.data.Curviness
import com.jellemax.detour.data.DrivingStats
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.SyncClient
import com.jellemax.detour.data.Trip
import com.jellemax.detour.data.TripStore
import com.jellemax.detour.data.syncQuietly
import com.jellemax.detour.drive.HardEventDetector
import com.jellemax.detour.drive.RoadTypeTracker
import com.jellemax.detour.drive.SpeedLimitTracker
import com.jellemax.detour.drive.StopDetector
import com.jellemax.detour.notif.TripEndedNotification
import com.jellemax.detour.ui.loadTripPoints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/**
 * One trip's own recorded state, and the two ends of its life: [begin] arms a
 * fresh set of accumulators, [end] turns them into the [Trip] that is written
 * to disk. What sits between the two — the per-fix pipeline that folds each
 * location fix into these accumulators — stays on [TripTrackingService], which
 * is why every field here is `internal` rather than `private`.
 *
 * That `internal` is Kotlin's minimum for "another file in this module can see
 * it", not an invitation: the only instance of this class is held in a private
 * field of the service, so nothing outside the service can reach one. The
 * service's own [TripTrackingService.stats] StateFlow — the trip's *published*
 * surface, which the map, the car screens and the convoy client all collect —
 * deliberately stays on the service and is not mirrored here.
 *
 * Tuning constants stay declared once on [TripTrackingService]'s companion and
 * are read from there (see the `internal const val`s it exposes), never copied.
 */
internal class TripSession(
    private val context: Context,
    /** The service's own scope, which outlives any single trip — [end]'s save
     *  tail must survive the trip that started it. */
    private val scope: CoroutineScope,
    /** [VehicleLinks.resolvedVehicle], passed as a function rather than a value
     *  so [end]'s `looksLikeAWalk` keeps its original short-circuit: the lookup
     *  only happens for a trip long enough to be judged. */
    private val resolvedVehicle: () -> Settings.VehicleDevice?,
    /** Rescore badges once the trip is on disk; see the service's `checkBadges`,
     *  which keeps its own scope launch and notification. */
    private val checkBadges: () -> Unit,
) {

    // Written on the sensor thread, read when the trip is saved.
    /** Mirrors whatever `motionSensors` last reported through its `onLean`
     *  callback — board lean when fresh, else the phone's own smoothed
     *  reading — for the live HUD readout only. `recordLean` on the service is
     *  what decides whether the same value also becomes part of the recorded trip. */
    @Volatile internal var lastLeanDeg = 0.0
    @Volatile internal var maxLeanDeg = 0.0
    // Seeded at 1.0, not 0: a stationary accelerometer reads gravity, so the
    // magnitude idles at 1 g. Starting the EMA from 0 would put the first real
    // sample a full 1 g away — past MAX_G_SLEW — and the slew gate would then
    // reject every sample for the rest of the trip.
    @Volatile internal var currentG = 1.0
    @Volatile internal var maxG = 0.0

    @Volatile internal var speedEventState = HardEventDetector.SpeedState()
    @Volatile internal var headingEventState = HardEventDetector.HeadingState()
    /** Threaded into [HardEventDetector.onLeanSample] from the service's
     *  `recordLean` — a car trip never calls it, so it only ever moves for a
     *  moto trip. */
    @Volatile internal var leanCorneringNow = false
    @Volatile internal var hardCornerCount = 0
    @Volatile internal var hardBrakeCount = 0
    @Volatile internal var hardAccelCount = 0
    @Volatile internal var obd2SpeedFixes = 0
    @Volatile internal var speedFixesTotal = 0
    // OBD2 engine summary, folded from each fix's telemetry snapshot in
    // onTripLocation for the duration of a trip.
    @Volatile internal var obdMaxRpm = 0.0
    @Volatile internal var obdMaxThrottlePct = 0.0
    @Volatile internal var obdRpmSum = 0.0
    @Volatile internal var obdRpmSamples = 0
    @Volatile internal var obdWideOpenThrottleSamples = 0
    @Volatile internal var obdThrottleSamples = 0
    // Fuel burned this trip: rate × elapsed, integrated per fix. Millilitres as a
    // Double while accumulating; rounded to a Long on the saved trip.
    @Volatile internal var fuelMlAccum = 0.0
    @Volatile internal var fuelSampledMeters = 0.0
    @Volatile internal var lastFuelSampleMs = 0L
    @Volatile internal var fuelWasEstimated = false
    @Volatile internal var stopState = StopDetector.State()
    @Volatile internal var tripLimitState = SpeedLimitTracker.State()
    @Volatile internal var tripLimitFetchJob: kotlinx.coroutines.Job? = null
    @Volatile internal var secondsOverLimit = 0.0
    @Volatile internal var lastLimitFixMs = 0L
    @Volatile internal var roadTypeState = RoadTypeTracker.State()
    @Volatile internal var roadTypeFetchJob: kotlinx.coroutines.Job? = null

    /** The last trip-save [end] kicked off. Since #90 that save can start
     *  from a path that then stops the service (dormancy → stopSelf) before
     *  [TripTrackingService.onDestroy]'s own endTrip call runs, so onDestroy
     *  needs a handle to it to join before cancelling the service scope. */
    @Volatile internal var lastSaveJob: kotlinx.coroutines.Job? = null

    /**
     * Arms a fresh set of accumulators for a trip starting at [startTimeMs].
     * Every field above that carries over from one trip to the next is reset
     * here; the service's own trip machinery (auto/manual, origin, the exit
     * grace, the notification) is reset alongside this call in `beginTrip`.
     */
    fun begin(startTimeMs: Long) {
        lastLeanDeg = 0.0; maxLeanDeg = 0.0
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
    }

    /** Stops the two in-flight Overpass lookups. Separate from [end] because it
     *  has to happen at exactly the point `endTrip` reached it before this split
     *  — before the trace flush, so neither tracker's state can be rewritten
     *  between here and the [Trip] that reads it below. */
    fun cancelFetchJobs() {
        tripLimitFetchJob?.cancel()
        tripLimitFetchJob = null
        roadTypeFetchJob?.cancel()
        roadTypeFetchJob = null
    }

    /**
     * Decides whether [stats] was a trip worth keeping and, if so, writes it.
     * Returns the [kotlinx.coroutines.Job] doing the save-and-notify tail (null
     * when nothing was worth saving), so `onDestroy` — the one caller that
     * cannot let this outlive its own teardown — can join it before tearing
     * down the service scope.
     *
     * The caller has already flushed the trace and stopped the motion sensors;
     * this is only the save.
     */
    fun end(
        stats: TripStats,
        wasAuto: Boolean,
        destLat: Double?,
        destLon: Double?,
    ): kotlinx.coroutines.Job? {
        // An auto trip with no mapped vehicle that never left walking pace
        // wasn't a drive; don't save it under whatever mode the tab happened
        // to have selected. Judged the same way MIN_TRIP_METERS judges
        // "never went anywhere" — a second false-positive filter, not a
        // classification.
        val looksLikeAWalk = stats.durationMs > TripTrackingService.SLOW_NO_VEHICLE_MIN_JUDGE_MS &&
            resolvedVehicle() == null &&
            (stats.distanceMeters / (stats.durationMs / 1000.0)) <
                TripTrackingService.SLOW_NO_VEHICLE_AVG_MAX_MPS &&
            stats.topSpeedMps < TripTrackingService.SLOW_NO_VEHICLE_TOP_MAX_MPS
        if (!worthSaving(stats.distanceMeters, stats.durationMs, wasAuto, looksLikeAWalk)) {
            return null
        }
        val durationSec = stats.durationMs / 1000.0
        val trip = Trip(
            startTimeMs = stats.startTimeMs,
            endTimeMs = ReplayClock.nowMs(),
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
        // nothing here needs to run before the caller's field resets.
        val save = scope.launch {
            TripStore.save(trip)
            checkBadges()
            // Only tell the user about trips they didn't end themselves.
            if (wasAuto) TripEndedNotification.show(context, stats.startTimeMs)
        }
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
        scope.launch {
            save.join()
            val twistiness = runCatching {
                Curviness.traceScore(loadTripPoints(trip).map { it.at })
            }.getOrDefault(0.0)
            TripStore.updateDrivingStats(trip.startTimeMs, trip.drivingStats.copy(twistinessScore = twistiness))
            SyncClient.syncQuietly()
        }
        return save
    }

    internal companion object {
        /**
         * Whether a finished trip clears the bar to be saved. Both floors —
         * [TripTrackingService.MIN_TRIP_METERS] and
         * [TripTrackingService.MIN_TRIP_DURATION_MS] — must clear, so an
         * accidental mis-start (a few seconds, no ground covered) never reaches
         * history or rider totals whether it was auto-detected or the rider
         * tapped End. Auto trips additionally drop anything that only
         * [looksLikeAWalk]; that filter is a no-op on manual trips, which the
         * rider is asserting was a drive.
         *
         * Pure so the gate is unit-testable without a running service.
         */
        internal fun worthSaving(
            distanceMeters: Double,
            durationMs: Long,
            wasAuto: Boolean,
            looksLikeAWalk: Boolean,
        ): Boolean =
            distanceMeters >= TripTrackingService.MIN_TRIP_METERS &&
                durationMs >= TripTrackingService.MIN_TRIP_DURATION_MS &&
                !(wasAuto && looksLikeAWalk)
    }
}

package com.jellemax.detour.tracking

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette

/**
 * Whether the accumulating run of fast, accurate fixes is yet proof that a drive
 * has begun.
 *
 * Pulled out of [TripTrackingService] for the same reason as [TripEndDetector]:
 * three `var`s inside an Android `Service`, in a repo with no Robolectric and no
 * `androidTest`, are unreachable by the only gate CI has. The bar this applies —
 * [TripTrackingService.FAST_FIXES_TO_START] fixes above the speed gate,
 * sustained for [TripTrackingService.MIN_FAST_RUN_MS] and
 * [TripTrackingService.MIN_FAST_RUN_METERS] — is the thing a replay route has to
 * be built to clear, and it is worth being able to check against literals rather
 * than by driving.
 *
 * **No clock, deliberately.** Every timestamp here is the *fix's* own
 * `location.time`, not a reading of now, and that is load-bearing rather than
 * incidental: a batched burst of idle fixes all arrive at the same instant but
 * describe minutes of driving, so a wall-clock run length would never reach
 * `MIN_FAST_RUN_MS` on the very deliveries it is meant to catch. This is the same
 * shape `com.jellemax.detour.drive.StopDetector` holds in `commonTest` —
 * timestamps as parameters, no clock in the machine.
 *
 * Two things it deliberately does not decide, because they are the service's:
 * whether auto-detection is switched on at all (a `Settings` read), and what to
 * do about [Decision.Building] (escalating the fix cadence is
 * [DriveTransitions]'s to do).
 */
internal class TripStartDetector {

    /** What the run of fixes so far amounts to. */
    sealed interface Decision {
        /** Nothing usable in this fix — too loose, or too slow. The run, if
         *  there was one, has been reset by the call that returned this. */
        data object Idle : Decision

        /** A fast, accurate fix: worth watching closer, not yet worth starting
         *  a trip over. */
        data object Building : Decision

        /** The bar is cleared. [startTimeMs] backdates the trip to when the
         *  drive really began rather than to the fix that finally proved it,
         *  and [distanceMeters] is the ground already covered by then. */
        data class Start(val startTimeMs: Long, val distanceMeters: Double) : Decision
    }

    private var fastFixes = 0
    private var fastRunStartMs = 0L
    private var fastRunStart: LatLon? = null

    /** Abandon the run in progress. Called when the fix stream says the rider is
     *  not driving after all — walking, still, or auto-detection switched off —
     *  and when a trip begins, so the next one starts from nothing. */
    fun reset() {
        fastFixes = 0
        fastRunStart = null
    }

    /**
     * Fold one idle-path fix into the run.
     *
     * [probing] is [DriveTransitions.probing]: activity recognition recently saw
     * IN_VEHICLE, or a fast fix already opened a speed-only window, so a lower
     * speed counts as evidence.
     */
    fun onFix(
        accuracyM: Float,
        speed: Double,
        at: LatLon,
        fixTimeMs: Long,
        probing: Boolean,
    ): Decision {
        // A loose fix can drift 100 m in a minute while the phone sits indoors,
        // which reads as a comfortable 6 km/h — or, over one bad jump, as 25.
        if (accuracyM > TripTrackingService.MAX_START_ACCURACY_M) {
            reset()
            return Decision.Idle
        }
        val gate =
            if (probing) TripTrackingService.PROBE_SPEED_MPS else TripTrackingService.FAST_SPEED_MPS
        if (speed < gate) {
            reset()
            return Decision.Idle
        }

        val runStart = fastRunStart
        if (runStart == null) {
            fastRunStart = at
            // GPS timestamps, not wall clock: a batched burst of idle fixes all
            // arrive at the same instant but describe minutes of driving.
            fastRunStartMs = fixTimeMs
            fastFixes = 1
            return Decision.Building
        }
        fastFixes++
        val runDistanceMeters = RoadRoulette.distanceMeters(runStart, at)
        return if (fastFixes >= TripTrackingService.FAST_FIXES_TO_START &&
            fixTimeMs - fastRunStartMs >= TripTrackingService.MIN_FAST_RUN_MS &&
            runDistanceMeters >= TripTrackingService.MIN_FAST_RUN_METERS
        ) {
            Decision.Start(startTimeMs = fastRunStartMs, distanceMeters = runDistanceMeters)
        } else {
            Decision.Building
        }
    }
}

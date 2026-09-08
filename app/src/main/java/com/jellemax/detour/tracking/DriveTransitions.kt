package com.jellemax.detour.tracking

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.jellemax.detour.data.Settings

/**
 * Activity-recognition wiring for the auto-start/auto-sleep signal, pulled out
 * of [TripTrackingService] (phase 7 split). Owns the two pieces of state a
 * transition decides: [stationary] (STILL-and-no-trip) and the IN_VEHICLE
 * confirmation deadline behind [probing].
 *
 * [probing] is load-bearing beyond activity recognition itself: it is what
 * lets [TripTrackingService.onIdleLocation] auto-start a trip at the lower
 * `PROBE_SPEED_MPS` instead of `FAST_SPEED_MPS` — see [startSpeedProbe],
 * which that same speed-only path opens with no IN_VEHICLE hint at all. That
 * coupling is why this class exposes a write path into its own probe state
 * rather than only reacting to [onTransitionIntent].
 *
 * [tripActive] and [listener] both run back on the service: a running trip
 * gates several of this class's own state changes (matching the original inline
 * `_stats.value == null` checks exactly), and the start detector, the trace and
 * the end detector are state this class has no business holding — see each
 * method's call site in [TripTrackingService] for what it does and why.
 */
internal class DriveTransitions(
    private val context: Context,
    /** The drive's clock (#307). Both ends of the probe window read it: the
     *  window is a duration of *driving*, so under a compressed replay it has to
     *  open and close on the same timeline it is measured against. It previously
     *  opened on `System.currentTimeMillis()` and closed against the replay
     *  clock, which are the same number at 1x and diverge the moment a replay is
     *  scaled — so the window shut immediately for the whole of a scaled run. */
    private val clock: DriveClock,
    private val tripActive: () -> Boolean,
    private val listener: Listener,
) {

    /**
     * The four edges this class reports back to the service.
     *
     * One interface rather than four function parameters: with the drive clock
     * handed in (#307) the constructor reached detekt's `LongParameterList`
     * threshold, and four lambdas that are always passed together, always by the
     * same caller, and always implemented against the same service state are one
     * collaborator rather than four independent knobs.
     */
    internal interface Listener {
        /** Activity recognition saw IN_VEHICLE. */
        fun onVehicleEnter()

        /** Activity recognition saw the rider leave the vehicle. */
        fun onVehicleExit()

        /** Activity recognition saw STILL. */
        fun onStill()

        /** Activity recognition saw WALKING, with no trip running. */
        fun onWalking()
    }
    /** Carries only the bounded registration retry (#144) — never anything
     *  that must survive [cancelPendingRegister]'s call from
     *  [TripTrackingService.onDestroy]. */
    private val handler = Handler(Looper.getMainLooper())

    private var transitionsRegistered = false

    /** Activity recognition says the phone is STILL, and no trip is running.
     *  Only ever set from an AR STILL transition, so with the
     *  ACTIVITY_RECOGNITION runtime permission denied this stays false forever
     *  and STOP_WITH_GEOFENCE dormancy (issue #90) never engages — the service
     *  stays always-on exactly as it did pre-#90. That degradation is
     *  deliberate. */
    var stationary = false
        private set

    /** Deadline of the IN_VEHICLE confirmation window; null when not probing. */
    private var probeUntilMs: Long? = null

    /** True while a confirmation window is open, from either an IN_VEHICLE
     *  transition or [startSpeedProbe]. */
    val probing: Boolean get() = probeUntilMs?.let { clock.nowMs() < it } == true

    private fun pendingIntent(): PendingIntent =
        PendingIntent.getForegroundService(
            context, 1,
            Intent(context, TripTrackingService::class.java)
                .setAction(TripTrackingService.ACTION_TRANSITION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

    fun register() {
        if (transitionsRegistered) return
        if (!Settings.autoDetectDrives.value) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACTIVITY_RECOGNITION,
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
        try {
            ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(
                    ActivityTransitionRequest(transitions), pendingIntent())
                .addOnSuccessListener { transitionsRegistered = true }
                .addOnFailureListener { e ->
                    // #144: this request can fail asynchronously - plausibly
                    // when it lands right after unregister() removed the same
                    // PendingIntent moments earlier, which is exactly what an
                    // auto-detect toggle off-then-on does. With no failure
                    // path, transitionsRegistered stayed false forever,
                    // silently: parking (#90) never engages again until some
                    // *other* onStartCommand happens to arrive, which a
                    // continuously-running foreground service may not see for
                    // a very long time. Logged so this is no longer invisible,
                    // and retried on a short bounded delay rather than left to
                    // chance - the retry re-checks
                    // transitionsRegistered/autoDetectDrives/the permission at
                    // the top of this function, so it's a no-op if any of
                    // those changed in the meantime.
                    Log.w(ParkGeofence.TAG, "activity transition registration failed", e)
                    handler.postDelayed({ register() }, TripTrackingService.AR_REGISTER_RETRY_MS)
                }
        } catch (e: SecurityException) {
            // No activity recognition permission; speed fallback still works.
        }
    }

    fun unregister() {
        // No `transitionsRegistered` guard: that flag is per-instance, but the
        // AR registration is PendingIntent-scoped and outlives the instance
        // (deliberately, on STOP_WITH_GEOFENCE). A fresh instance reaching
        // STOP_BARE must still be able to tear down a registration an earlier
        // instance left standing. removeActivityTransitionUpdates on an
        // unregistered PendingIntent is harmless.
        try {
            ActivityRecognition.getClient(context).removeActivityTransitionUpdates(pendingIntent())
        } catch (e: SecurityException) {
            // Nothing to clean up.
        }
        transitionsRegistered = false
    }

    /** Cancels a pending [register] retry without touching the actual AR
     *  subscription (that one deliberately outlives the instance - see
     *  [unregister]). Call from [TripTrackingService.onDestroy] so a dying
     *  instance's handler doesn't keep retrying forever - mirrors that
     *  service's own `mainHandler.removeCallbacksAndMessages(null)`, which
     *  used to double as this cleanup back when the retry posted to it. */
    fun cancelPendingRegister() {
        handler.removeCallbacksAndMessages(null)
    }

    fun onTransitionIntent(intent: Intent) {
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        for (event in result.transitionEvents) {
            val entering = event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
            when (event.activityType) {
                DetectedActivity.STILL -> {
                    if (!tripActive()) stationary = entering
                    if (entering) listener.onStill()
                }
                DetectedActivity.IN_VEHICLE -> {
                    if (entering) {
                        stationary = false
                        // IN_VEHICLE on its own is not evidence of a drive — it
                        // fires for a phone on a desk next to a fan. Open a window
                        // in which a modest sustained speed is enough to confirm.
                        if (!tripActive() && Settings.autoDetectDrives.value) {
                            probeUntilMs = clock.nowMs() + TripTrackingService.PROBE_WINDOW_MS
                        }
                        listener.onVehicleEnter()
                    } else {
                        probeUntilMs = null
                        listener.onVehicleExit()
                    }
                }
                DetectedActivity.WALKING -> {
                    if (entering && !tripActive()) {
                        stationary = false
                        probeUntilMs = null // walking never becomes a drive
                        listener.onWalking()
                    }
                }
            }
        }
    }

    /** Opens the sustained-speed probe window from a plain fast fix, with no
     *  IN_VEHICLE hint to back it up — see [TripTrackingService.onIdleLocation].
     *  Escalates straight to [probing]'s tight fixes so the run it's judging
     *  is confirmed in seconds rather than waiting out a batched idle fix. */
    fun startSpeedProbe() {
        probeUntilMs = clock.nowMs() + TripTrackingService.SPEED_PROBE_WINDOW_MS
        stationary = false
    }

    /** [TripTrackingService.beginTrip] clears the probe/stationary state for a
     *  fresh trip, auto-started or not. */
    fun reset() {
        stationary = false
        probeUntilMs = null
    }
}

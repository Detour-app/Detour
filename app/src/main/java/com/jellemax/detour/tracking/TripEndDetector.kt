package com.jellemax.detour.tracking

/**
 * When a running trip is over: the "still moving" clock, the grace period after
 * an IN_VEHICLE exit, and the stationary fallback behind both.
 *
 * **Not [com.jellemax.detour.drive.StopDetector], and the two must not be
 * conflated.** That one counts mid-trip pauses that *resume* — a fuel stop on a
 * drive that carries on afterwards — and feeds the trip's statistics. This one
 * decides that the drive is finished. `StopDetector`'s own KDoc draws the line
 * in the other direction ("only detects a stop long enough to *end* the trip;
 * this detects a stop that resumes within the same trip"), which is the sentence
 * this class is named after.
 *
 * Pulled out of [TripTrackingService] because it could not otherwise be tested.
 * This repo runs no Robolectric and has no `androidTest` source set, so anything
 * living as a `var` inside an Android `Service` is out of reach of the only gate
 * CI has — and the auto-stop dwell is a *time* machine, exactly the kind
 * `StopDetector` is already held to literals for over in `commonTest`. It is the
 * same move [currentLocationMode], [LocationRequests] and `dormancyDecision`
 * already made out of this same file.
 *
 * The [clock] is handed in, per `CONTRIBUTING.md`'s "the core is handed things,
 * it never reaches for them" — which is the whole of #307. Under a compressed
 * replay it is a [ScaledDriveClock], so a grace period is the grace period it
 * claims to be rather than a fifth of one.
 *
 * The tuning constants stay declared on [TripTrackingService]'s companion and are
 * referenced from here rather than copied, the same rule that companion already
 * states for `AR_REGISTER_RETRY_MS` and `PROBE_WINDOW_MS`.
 */
internal class TripEndDetector(private val clock: DriveClock) {

    /** Last time the vehicle was above the moving gate. Seeded when a trip
     *  begins, so a trip that never moves still ages out of its own accord. */
    private var lastMovingMs = 0L

    /** When IN_VEHICLE exit fired; null when the rider is still aboard. */
    private var pendingStopAtMs: Long? = null

    /** A trip just began: nothing is pending, and the moving clock starts now. */
    fun onTripBegan() {
        pendingStopAtMs = null
        lastMovingMs = clock.nowMs()
    }

    /** A trip just ended, by any route. */
    fun onTripEnded() {
        pendingStopAtMs = null
    }

    /** Activity recognition saw IN_VEHICLE again — whatever exit was pending was
     *  a fuel stop, not the end of the drive. */
    fun onVehicleEnter() {
        pendingStopAtMs = null
    }

    /** Activity recognition saw the rider leave the vehicle. Not the end of the
     *  trip on its own: [shouldEnd] checks it against speed over the grace
     *  period, because this fires at every fuel stop and every red light long
     *  enough to look like one. */
    fun onVehicleExit() {
        pendingStopAtMs = clock.nowMs()
    }

    /**
     * Keeps the "still moving" clock, then decides whether the rider has left
     * the vehicle for good. Returns true when the trip should end.
     *
     * [now] is a parameter rather than a [clock] read so this shares one instant
     * with the rest of the per-fix pipeline — `checkReturnToOrigin` tests the
     * same `now`, and two readings of a 50x clock a few wall-milliseconds apart
     * are a quarter of a second of drive time apart.
     *
     * The moving gate is written as the literal `2.0` on purpose: it is one of
     * the four numbers `.claude/skills/detour-gps-replay`'s precondition script
     * asserts by grep, because the standstill reconstruction in that skill is
     * arithmetic over it. Naming it would make the assertion check a name
     * instead of the value it exists to pin.
     */
    fun shouldEnd(speed: Double, autoStarted: Boolean, now: Long): Boolean {
        if (speed > 2.0) lastMovingMs = now

        // Left the vehicle and stayed slow through the grace period: trip over.
        pendingStopAtMs?.let { exitedAt ->
            if (speed > RESUMED_SPEED_MPS) {
                pendingStopAtMs = null
            } else if (now - exitedAt > TripTrackingService.EXIT_GRACE_MS) {
                return true
            }
        }
        // Fallback if the vehicle-exit event never arrives. Also stops the
        // high-accuracy fixes draining the battery in a car park.
        if (autoStarted && now - lastMovingMs > TripTrackingService.STATIONARY_END_MS) {
            return true
        }
        return false
    }

    private companion object {
        /** Back above this during the grace period and the rider never left —
         *  clearly higher than the 2.0 m/s moving gate, because pushing a bike
         *  across a forecourt must not cancel a real exit. */
        const val RESUMED_SPEED_MPS = 5.0
    }
}

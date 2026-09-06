package com.jellemax.detour.drive

/**
 * The per-fix decision arithmetic `TripTrackingService.onTripLocation` runs on
 * every GPS fix of a recorded trip: how far the rider actually travelled,
 * whether the speed number in hand was measured or fabricated, and which clock
 * the hard-event/stop detectors should derive their Δt from.
 *
 * Primitives only and clock-free — no `Location`, no Android type, no
 * `currentTimeMillis()` — so the decisions are reachable from a test on every
 * target instead of only from a running foreground service.
 *
 * **Thresholds are parameters with no defaults.** The values live on
 * `TripTrackingService.Companion` and are passed in. A default here that
 * happened to equal the app's constant would be a second copy free to drift
 * out of step with the first, silently, which is a bug class this repo has
 * already been bitten by.
 */
object TripFixMath {

    /**
     * The distance this fix adds to the trip: [rawHopMeters] when the fix is
     * both accurate enough and recent enough, otherwise 0.
     *
     * Both halves of the gate matter. Accuracy alone rejects GPS scatter, but
     * lets a post-tunnel or post-parking-garage re-acquire — fully accurate,
     * and kilometres from the last real fix — bank the whole unobserved
     * stretch as one hop. The recency window is what keeps that out.
     *
     * The caller supplies [rawHopMeters] (on Android, `Location.distanceTo`,
     * which is WGS84 rather than a spherical approximation) so this stays a
     * gate and not a second, subtly different, geodesy implementation.
     *
     * [lastFixMs] is null when the trip has no previous fix to measure from.
     */
    fun distanceHopMeters(
        rawHopMeters: Double,
        lastFixMs: Long?,
        fixMs: Long,
        accuracyM: Float,
        maxAccuracyM: Float,
        minGapMs: Long,
        maxGapMs: Long,
    ): Double {
        if (lastFixMs == null || accuracyM > maxAccuracyM) return 0.0
        val gapMs = fixMs - lastFixMs
        return if (gapMs in minGapMs..maxGapMs) rawHopMeters else 0.0
    }

    /**
     * Whether this fix's speed is a measurement rather than the fabricated 0.0
     * sentinel `speedOf` returns for a coarse/no-speed fix.
     *
     * Feeding that sentinel to the physics-based detectors reads a tunnel or
     * garage GPS gap as "suddenly stopped": a false hard brake, potentially a
     * false stop, and a bogus "suddenly under the limit" transition whose
     * duration would be folded into `secondsOverLimit`.
     *
     * [boardHasSpeed] is fresh board telemetry that reports a speed;
     * [obdHasSpeed] a fresh OBD2 snapshot that does. The OBD arm is scoped to
     * modes tracking g-force ([modeTracksGForce]) — an adapter still connected
     * while the rider walks must not vouch for a pedestrian fix — while board
     * telemetry counts in any mode.
     */
    fun speedIsReal(
        fixHasSpeed: Boolean,
        boardHasSpeed: Boolean,
        modeTracksGForce: Boolean,
        obdHasSpeed: Boolean,
    ): Boolean = fixHasSpeed || boardHasSpeed || (modeTracksGForce && obdHasSpeed)

    /**
     * The timestamp the hard-brake/accel and stop detectors derive Δt from.
     *
     * When the OBD adapter's reading drove the speed ([obdDroveSpeed]), that
     * reading's own arrival clock is the honest one: PID 0D lands at ~1 Hz
     * with its own jitter, and stamping it with the GPS fix time flattens that
     * jitter to a nominal second (#98). A GPS-derived speed keeps the GPS
     * clock. Heading-rate cornering is not this decision — its signal is the
     * GPS bearing, so it stays on the GPS clock regardless.
     *
     * [obdReceivedAtMs] is null when there is no OBD snapshot for this fix at
     * all, in which case there is nothing to stamp with and the GPS clock wins.
     */
    fun recordedFixMs(
        obdDroveSpeed: Boolean,
        obdReceivedAtMs: Long?,
        locationTimeMs: Long,
    ): Long = if (obdDroveSpeed && obdReceivedAtMs != null) obdReceivedAtMs else locationTimeMs

    /**
     * The distance road-type attribution should charge to this fix: exactly
     * the hop [distanceHopMeters] already banked, recovered from the running
     * total rather than measured again.
     *
     * Deliberately not its own `lastFixLocation` anchor. Such an anchor tends
     * to get only the accuracy half of the distance gate, and then a
     * post-tunnel re-acquire attributes several kilometres to whatever road
     * class the re-acquire fix happens to snap to.
     */
    fun roadTypeHopMeters(distanceMeters: Double, previousDistanceMeters: Double): Double =
        distanceMeters - previousDistanceMeters
}

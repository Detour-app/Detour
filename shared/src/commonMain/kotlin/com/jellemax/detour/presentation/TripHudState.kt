package com.jellemax.detour.presentation

/**
 * Everything the speed HUD and the live trip card put on screen: nine already
 * formatted readouts and the three booleans that colour or gate them. Pure —
 * see [tripHudStateFrom] for what it replaces and what deliberately stays out.
 */
data class TripHudState(
    /** The dial's big number, km/h, no unit — "112". */
    val speedText: String,
    /** Over the posted limit by more than the caller's tolerance; turns the
     *  whole dial red. Always false with no [limitSignText] to be over. */
    val speeding: Boolean,
    /** The posted-limit sign's number, or null when there is no sign to draw —
     *  the same "render nothing" the sign composable does on a null limit. */
    val limitSignText: String?,
    /** The trajectcontrole chip's running average — "Ø 98" — or null when no
     *  average-speed section is in play. */
    val averageText: String?,
    /** The running average is past the section's own limit: the number the
     *  camera pair actually measures, so the chip goes red on this, not on
     *  [speeding]. */
    val averageOverLimit: Boolean,
    /** Elapsed trip time, "7:19" / "1:12:36". */
    val durationText: String,
    /** Distance covered, "850 m" / "12.4 km". */
    val distanceText: String,
    /** Top speed reached, with its unit — "138 km/h". */
    val topSpeedText: String,
    /** Current lean angle, "32°". Only on screen for a mode that leans; the
     *  caller still decides that, since the travel mode is Android-side. */
    val leanText: String,
    /** Peak lean angle, "47°". */
    val maxLeanText: String,
    /** Peak lateral acceleration, "1.3 g". */
    val gForceText: String,
    /** Hard brakes + hard accelerations + hard corners, one number: the card
     *  renders the three counts separately but decides on the sum. */
    val hardEvents: Int,
    /** Whether the card's second row (the counts, and the "informational
     *  only" caption under them) is on screen at all. */
    val detailsShown: Boolean,
)

/**
 * Pure map from the live trip's numbers to [TripHudState]. Ported from
 * (named, not cited by line, so this stays true across edits)
 * `app/.../ui/MapHud.kt`'s `SpeedHud`, `SectionAverageChip` and
 * `ActiveTripCard`.
 *
 * Takes primitives rather than the app's `TripStats` and `Fix`: both are
 * declared inside `TripTrackingService.kt` and cannot cross into `:shared`,
 * and `Fix` carries an `elapsedRealtime` basis that is Android-only by
 * nature. The caller unpacks them, and that unpacking is the whole of the
 * Android-shaped part of this screen's display logic.
 *
 * [nowMs] is the stopwatch's clock input, an argument and never read here, so
 * a caller passing a fixed instant gets a fixed string. `ActiveTripCard` used
 * to tick its own `System.currentTimeMillis()` inside the composable; the 1 Hz
 * ticker stays a UI concern, the arithmetic does not. A [nowMs] behind
 * [startTimeMs] clamps to "0:00" rather than counting backwards.
 *
 * [overLimitToleranceKmh] is the HUD's red threshold, defaulting to the same 5
 * the phone, Android Auto and Wear each write out today. It is a parameter,
 * not a constant hoisted into this module, for the reason the divergence
 * register records against that literal: Wear cannot depend on `:shared`, so a
 * shared constant would deduplicate two of the three copies and orphan
 * precisely the one nobody reads. The authoritative copy stays in `app/`,
 * where the phone and the car both reach it, and arrives here as this
 * argument — the same arrangement [navStateFrom] has with
 * `NavPolicy.OFF_ROUTE_METERS`, whose default is likewise never the value the
 * app runs on.
 *
 * [sep] is the rider's decimal separator, resolved once on the render path
 * (`Settings.decimalSeparatorChar`) and passed down, exactly as the formatters
 * in `DisplayFormat.kt` take it.
 *
 * Does not own the trip itself: no accumulation, no auto-stop, no travel-mode
 * decision. `mode.tracksLean` / `mode.tracksGForce` stay with the caller as
 * well — they gate which of these strings get placed, and `TravelMode` is
 * Android-side with the service that owns it.
 */
fun tripHudStateFrom(
    speedKmh: Double,
    limitKmh: Double?,
    averageKmh: Double?,
    averageLimitKmh: Double?,
    startTimeMs: Long,
    nowMs: Long,
    distanceMeters: Double,
    topSpeedKmh: Double,
    leanDeg: Double,
    maxLeanDeg: Double,
    gForce: Double,
    hardBrakeCount: Int,
    hardAccelCount: Int,
    hardCornerCount: Int,
    stopCount: Int,
    currentlyOverLimit: Boolean,
    overLimitToleranceKmh: Double = 5.0,
    sep: Char = '.',
): TripHudState {
    val hardEvents = hardBrakeCount + hardAccelCount + hardCornerCount
    return TripHudState(
        speedText = formatFixed(speedKmh, 0),
        speeding = limitKmh != null && speedKmh > limitKmh + overLimitToleranceKmh,
        // Truncating, not rounding: a sign shows the posted number, and the
        // limits that reach it are whole km/h anyway.
        limitSignText = limitKmh?.let { it.toInt().toString() },
        averageText = averageKmh?.let { "Ø ${formatFixed(it, 0)}" },
        averageOverLimit = averageKmh != null && averageLimitKmh != null &&
            averageKmh > averageLimitKmh,
        durationText = formatDurationClock(nowMs - startTimeMs),
        distanceText = formatDistanceKm(distanceMeters, sep),
        topSpeedText = "${formatFixed(topSpeedKmh, 0)} km/h",
        leanText = "${formatFixed(leanDeg, 0)}°",
        maxLeanText = "${formatFixed(maxLeanDeg, 0)}°",
        gForceText = formatGForce(gForce, sep),
        hardEvents = hardEvents,
        detailsShown = hardEvents > 0 || stopCount > 0 || currentlyOverLimit,
    )
}

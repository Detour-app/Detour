package com.jellemax.detour.presentation

/**
 * The speed dial, the posted-limit sign and the trajectcontrole chip: the
 * readouts that change on every GPS fix. Pure — see [speedHudStateFrom].
 */
data class SpeedHudState(
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
)

/**
 * The live trip card's readouts: the stopwatch and the accumulated totals,
 * ticked about once a second. Pure — see [activeTripCardStateFrom].
 */
data class ActiveTripCardState(
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
    val maxGForceText: String,
    /** Whether the card's second row (the counts, and the "informational
     *  only" caption under them) is on screen at all. */
    val detailsShown: Boolean,
)

/**
 * Pure map from the live fix to [SpeedHudState]. Ported from (named, not cited
 * by line, so this stays true across edits) `app/.../ui/MapHud.kt`'s `SpeedHud`
 * and `SectionAverageChip`.
 *
 * Separate from [activeTripCardStateFrom] because the two surfaces share
 * nothing: different inputs, no common output, and different lifetimes — the
 * card keeps rendering the *exiting* trip's retained stats for a few frames
 * after the trip ends, while this one has to be the speed right now. One fused
 * state computed at one instant cannot honestly be both.
 *
 * Takes primitives rather than the app's `Fix`: it is declared inside
 * `TripTrackingService.kt` and cannot cross into `:shared`, and it carries an
 * `elapsedRealtime` basis that is Android-only by nature.
 *
 * [overLimitToleranceKmh] is the dial's red threshold. It stays a parameter so
 * this function has no tuning value of its own to drift — the authoritative
 * copy is `app/.../ui/MapCameraTuning.kt`'s `OVER_LIMIT_TOLERANCE_KMH`, which
 * both consumers of the threshold (the phone HUD and the Android Auto
 * renderer, which draws its own dial rather than calling this) read. Callers
 * pass it by name, the same arrangement [navStateFrom] has with
 * `NavPolicy.OFF_ROUTE_METERS`: the default here is never the value the app
 * runs on.
 */
fun speedHudStateFrom(
    speedKmh: Double,
    limitKmh: Double?,
    averageKmh: Double?,
    averageLimitKmh: Double?,
    overLimitToleranceKmh: Double = 5.0,
): SpeedHudState = SpeedHudState(
    speedText = formatFixed(speedKmh, 0),
    speeding = limitKmh != null && speedKmh > limitKmh + overLimitToleranceKmh,
    // Truncating, not rounding: a sign shows the posted number, and the
    // limits that reach it are whole km/h anyway.
    limitSignText = limitKmh?.let { it.toInt().toString() },
    averageText = averageKmh?.let { "Ø ${formatFixed(it, 0)}" },
    averageOverLimit = averageKmh != null && averageLimitKmh != null &&
        averageKmh > averageLimitKmh,
)

/**
 * Pure map from the trip's accumulated numbers to [ActiveTripCardState].
 * Ported from `app/.../ui/MapHud.kt`'s `ActiveTripCard`.
 *
 * Takes primitives rather than the app's `TripStats`: it is declared inside
 * `TripTrackingService.kt` and cannot cross into `:shared`. The caller unpacks
 * it, and that unpacking is the whole of the Android-shaped part of this
 * card's display logic.
 *
 * [nowMs] is the stopwatch's clock input, an argument and never read here, so
 * a caller passing a fixed instant gets a fixed string. `ActiveTripCard` used
 * to tick its own `System.currentTimeMillis()` inside the composable; the 1 Hz
 * ticker stays a UI concern, the arithmetic does not. A [nowMs] behind
 * [startTimeMs] clamps to "0:00" rather than counting backwards.
 *
 * [hardEvents] is the caller's sum of hard brakes, accelerations and corners.
 * It arrives summed and is not published back: the card renders the three
 * counts individually and never the total, so the only thing the sum decides
 * is [ActiveTripCardState.detailsShown].
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
fun activeTripCardStateFrom(
    startTimeMs: Long,
    nowMs: Long,
    distanceMeters: Double,
    topSpeedKmh: Double,
    leanDeg: Double,
    maxLeanDeg: Double,
    maxGForce: Double,
    hardEvents: Int,
    stopCount: Int,
    currentlyOverLimit: Boolean,
    sep: Char = '.',
): ActiveTripCardState = ActiveTripCardState(
    durationText = formatDurationClock(nowMs - startTimeMs),
    distanceText = formatDistanceKm(distanceMeters, sep),
    topSpeedText = "${formatFixed(topSpeedKmh, 0)} km/h",
    leanText = "${formatFixed(leanDeg, 0)}°",
    maxLeanText = "${formatFixed(maxLeanDeg, 0)}°",
    maxGForceText = formatGForce(maxGForce, sep),
    detailsShown = hardEvents > 0 || stopCount > 0 || currentlyOverLimit,
)

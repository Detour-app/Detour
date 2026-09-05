package com.jellemax.detour.presentation

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.floor

/**
 * "12480" -> "12 480". Locale-independent by construction: commonMain has no
 * NumberFormat, and these values must read identically on JVM and Kotlin/Native.
 * The separator is a plain ASCII space (U+0020), not U+00A0 or U+202F.
 */
internal fun groupThousands(n: Long): String {
    // Strip the sign off the string form (rather than abs(), which overflows on
    // Long.MIN_VALUE) so it never counts the '-' as a digit position, then
    // reattach it to the first grouped digit.
    val negative = n < 0
    val digits = n.toString().removePrefix("-")
    val sb = StringBuilder()
    for ((i, c) in digits.withIndex()) {
        if (i > 0 && (digits.length - i) % 3 == 0) sb.append(' ')
        sb.append(c)
    }
    return if (negative) "-$sb" else sb.toString()
}

/**
 * Fixed-point decimal rendering, [decimals] places, half-away-from-zero — the
 * replacement for the "%.1f" family that commonMain cannot use.
 */
internal fun formatFixed(value: Double, decimals: Int): String {
    require(decimals in 0..6) { "decimals out of range: $decimals" }
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    // kotlin.math.round is round-half-to-even (it delegates to Math.rint on
    // JVM), not half-away-from-zero, so floor(x + 0.5) on the magnitude is used
    // instead — that rounds .5 up, and applying it to abs(value) keeps the
    // result symmetric for negatives.
    val scaled = floor(abs(value) * factor + 0.5).toLong()
    // A value that rounds to zero must not keep a sign: -0.01 at 1dp is "0.0".
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    val whole = scaled / factor
    if (decimals == 0) return "$sign$whole"
    val frac = (scaled % factor).toString().padStart(decimals, '0')
    return "$sign$whole.$frac"
}

/**
 * "25 min" under an hour, "1 h 12 min" at or past it. The single
 * implementation of this wording: `app/.../ui/Format.kt`'s
 * `formatDurationHistory` delegates here rather than keeping its own copy, so
 * trip history and a route card can't drift apart the way they once did.
 * Public (not `internal`) so `:app` can call it across the module boundary.
 */
fun formatDurationHistory(ms: Long): String {
    val totalMinutes = ms / 60_000
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "$h h $m min" else "$m min"
}

/**
 * Coarse relative age ("3m ago", "2h ago") — exact seconds never matter for a
 * feature whose fixes only update every couple of minutes anyway. Ported from
 * `CirclesScreen.kt`'s old private `relativeAge`; time is an argument here
 * ([nowMs]), never read from a clock, so this stays deterministically testable.
 *
 * A future [tsMs] (clock skew between a rider's phone and the event's server
 * timestamp is real) clamps to zero elapsed minutes, i.e. "just now" — carried
 * over from the original's `coerceAtLeast(0)`, which already avoids the
 * "-3m ago" a naive subtraction would produce.
 *
 * Public (not internal), matching [formatDurationHistory] above — though
 * unlike that one, nothing in `:app` calls this any more: the event list
 * that used to call it directly from `CirclesScreen.kt` now goes through
 * [circleDetailStateFrom] in this module instead, which is this function's
 * only caller today.
 */
fun relativeAge(tsMs: Long, nowMs: Long): String {
    val minutes = (nowMs - tsMs).coerceAtLeast(0) / 60_000L
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        else -> "${minutes / (60 * 24)}d ago"
    }
}

/**
 * "850 m" under a kilometre, "1.2 km" at or above it, rendered via [formatFixed].
 * The single implementation: `app/.../ui/Format.kt`'s `formatDistanceKm`
 * delegates here rather than keeping its own `"%.1f km".format(...)` copy, which
 * followed `Locale.getDefault()` and so put "20,5 km" on the same screen as the
 * nav bar's "33.3 km" for a comma-decimal rider.
 *
 * The branch is decided on the raw value, before rounding: 999.6 m is still
 * "< 1000" and renders "1000 m", not "1.0 km". Long-standing behaviour, pinned
 * by tests here and by the nav display's.
 *
 * Public (not `internal`) so `:app` can call it across the module boundary.
 */
fun formatDistanceKm(meters: Double): String =
    if (meters < 1000) "${formatFixed(meters, 0)} m" else "${formatFixed(meters / 1000, 1)} km"

/**
 * Peak lateral acceleration as "1.3 g", one decimal. Same story as
 * [formatDistanceKm]: `app/.../ui/Format.kt`'s `formatGForce` delegates here so
 * the g readout and the distance beside it on the trip card can't disagree
 * about which decimal separator to use.
 */
fun formatGForce(g: Double): String = "${formatFixed(g, 1)} g"

/**
 * Wall-clock time of day as "HH:mm", 24-hour, zero-padded — commonMain's
 * substitute for `SimpleDateFormat("HH:mm", Locale.getDefault())`. [zone]
 * defaults to the device's own zone for real callers; a test pins it
 * explicitly so the assertion does not depend on the machine running it.
 */
fun formatEta(epochMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val dt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)
    return "${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
}

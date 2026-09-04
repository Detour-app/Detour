package com.jellemax.detour.presentation

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

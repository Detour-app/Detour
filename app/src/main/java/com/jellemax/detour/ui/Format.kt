package com.jellemax.detour.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

// History shows past trips side by side ("1:12:36" next to "7:19"), where the
// live card's seconds-precision "M:SS" is ambiguous — is "7:19" seven minutes
// or seven hours? formatDuration above still owns the live trip card, where
// seconds matter and there's only ever one duration on screen at a time.
fun formatDurationHistory(ms: Long): String {
    val totalMinutes = ms / 60_000
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "%d h %d min".format(h, m) else "%d min".format(m)
}

fun formatSpeedKmh(mps: Double): String = "%.0f km/h".format(mps * 3.6)

fun formatDistanceKm(meters: Double): String =
    if (meters < 1000) "%.0f m".format(meters) else "%.1f km".format(meters / 1000)

// Built once, reused. A fresh SimpleDateFormat per call re-parses the ICU
// pattern (~1-2 ms) — cheap alone, but LazyColumn composes many rows per fling
// frame, so per-row allocation is what made trip-history scrolling stutter.
// Composition is single-threaded (main), so one shared instance is safe here.
private val tripDateFormat = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault())

fun formatDate(timeMs: Long): String = tripDateFormat.format(Date(timeMs))

private val timeOfDayFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

fun formatTimeOfDay(timeMs: Long): String = timeOfDayFormat.format(Date(timeMs))

fun formatLeanAngle(deg: Double): String = "%.0f°".format(deg)

fun formatGForce(g: Double): String = "%.1f g".format(g)

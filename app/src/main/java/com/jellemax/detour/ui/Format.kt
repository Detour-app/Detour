package com.jellemax.detour.ui

import com.jellemax.detour.data.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.jellemax.detour.presentation.formatDistanceKm as sharedFormatDistanceKm
import com.jellemax.detour.presentation.formatDurationHistory as sharedFormatDurationHistory
import com.jellemax.detour.presentation.formatGForce as sharedFormatGForce

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
//
// The wording itself lives in commonMain (presentation.formatDurationHistory)
// so a route card and trip history can't drift apart the way they once could
// with two hand-written copies. This keeps the name/signature app callers
// already use.
fun formatDurationHistory(ms: Long): String = sharedFormatDurationHistory(ms)

fun formatSpeedKmh(mps: Double): String = "%.0f km/h".format(mps * 3.6)

// Both of these used to be "%.1f"-based String.format calls with no Locale, so
// they followed Locale.getDefault(): a nl-BE rider saw "20,5 km" and "1,0 g" in
// the trip HUD while the nav bar, already on commonMain, read "33.3 km" — two
// decimal separators on one screen. Delegating leaves one implementation, which
// is also the one that can't drift.
//
// This is the render path, and so the place the separator is resolved: the
// shared formatter takes it as an argument and never reaches for it. The
// default argument is what keeps the ~12 call sites unchanged, and lets a test
// pin the separator without moving the JVM's default Locale.
fun formatDistanceKm(meters: Double, sep: Char = Settings.decimalSeparatorChar()): String =
    sharedFormatDistanceKm(meters, sep)

// Built once, reused. A fresh SimpleDateFormat per call re-parses the ICU
// pattern (~1-2 ms) — cheap alone, but LazyColumn composes many rows per fling
// frame, so per-row allocation is what made trip-history scrolling stutter.
// Composition is single-threaded (main), so one shared instance is safe here.
private val tripDateFormat = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault())

fun formatDate(timeMs: Long): String = tripDateFormat.format(Date(timeMs))

private val timeOfDayFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

fun formatTimeOfDay(timeMs: Long): String = timeOfDayFormat.format(Date(timeMs))

fun formatLeanAngle(deg: Double): String = "%.0f°".format(deg)

// Same story as formatDistanceKm above: one implementation, separator resolved
// here and passed in.
fun formatGForce(g: Double, sep: Char = Settings.decimalSeparatorChar()): String =
    sharedFormatGForce(g, sep)

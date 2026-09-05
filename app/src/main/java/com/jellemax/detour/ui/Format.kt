package com.jellemax.detour.ui

import com.jellemax.detour.data.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.jellemax.detour.presentation.formatFixed
import com.jellemax.detour.presentation.formatDistanceKm as sharedFormatDistanceKm
import com.jellemax.detour.presentation.formatDurationClock as sharedFormatDurationClock
import com.jellemax.detour.presentation.formatDurationHistory as sharedFormatDurationHistory
import com.jellemax.detour.presentation.formatGForce as sharedFormatGForce

// Same delegation as the three below: the wording lives in commonMain
// (presentation.formatDurationClock) so the live trip card and the shared
// HUD display state render one implementation of "7:19" / "1:12:36" instead
// of two. Keeps the name app callers already use.
fun formatDuration(ms: Long): String = sharedFormatDurationClock(ms)

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

// Fuel economy was the last "%.1f".format(...) left on a trip card: no Locale,
// so it followed Locale.getDefault() and put "5,4 L/100km" next to the
// "12.4 km" beside it for a rider who had picked POINT — the exact split the
// setting exists to close. Same shape as the two above.
fun formatFuelPer100Km(litresPer100Km: Double, sep: Char = Settings.decimalSeparatorChar()): String =
    "${formatFixed(litresPer100Km, 1, sep)} L/100km"

package com.jellemax.detour.presentation

import com.jellemax.detour.data.NavEngine
import kotlinx.datetime.TimeZone

/**
 * The "then" pill under the nav banner ("then ⟲ 400 m") — the maneuver after
 * the current one, so a driver can see a turn-then-turn coming before the
 * first is done. [sign] is the raw GraphHopper sign code; the icon lookup for
 * it stays where it is (`Navigation.kt`'s private `signIcon`, see
 * [navStateFrom]'s KDoc), so this only carries the code and the
 * already-formatted distance.
 */
data class NavThenPill(
    val sign: Int,
    val distanceText: String,
    /** Roundabout exit turn for [sign] 6/-6 — see [NavState.maneuverRoundaboutTurnDeg]. */
    val roundaboutTurnDeg: Double? = null,
)

/**
 * Turn-by-turn display state: what the nav banner, the "then" pill and the
 * bottom bar render today. Pure — see [navStateFrom] for the exact call sites
 * this replaces and what stays out of it.
 */
data class NavState(
    /** "Rerouting…" / "Waiting for GPS…" / the formatted distance to the next
     *  turn — `NavigationBanner`'s big headline number. */
    val headlineText: String,
    /** `nextInstruction?.text ?: ""`. */
    val maneuverText: String,
    /** `nextInstruction?.sign ?: 0`, for the caller's own icon lookup. */
    val maneuverSign: Int,
    /** For a roundabout maneuver ([maneuverSign] 6 or -6): how far the rider's
     *  heading turns from the approach to the exit, degrees, negative left /
     *  positive right / ~0 straight on — `NavInstruction.roundaboutTurnDeg`,
     *  measured off the route polyline. The phone banner draws the roundabout
     *  glyph's exit spur to this angle; null falls back to a straight-on exit. */
    val maneuverRoundaboutTurnDeg: Double?,
    /** Null past the last turn or when there's only one left — same guard
     *  `Navigation.kt`'s `ThenChip` uses today to render nothing. */
    val thenPill: NavThenPill?,
    /** "12.4 km · 25 min left", or "—" before the first fix. */
    val remainingText: String,
    /** "Off route", "Arrival 14:32", or "" when there's nothing to say yet. */
    val arrivalText: String,
    /** Drives the bottom bar's error styling; [arrivalText] already reflects
     *  it too, so a caller normally only needs one or the other. */
    val offRoute: Boolean,
    /** 0f..1f, how much of the route is behind you — the progress track's
     *  fill width. */
    val progressFraction: Float,
    /** The speed HUD's reading: the route's posted limit while navigating,
     *  the ambient sign otherwise. Valid regardless of [thenPill]/[offRoute],
     *  since the HUD that reads this is on screen outside navigation too. */
    val speedLimitKmh: Double?,
    /** A route request is in flight — the first fetch after Start or a
     *  reroute. The drive sheet's Start shows busy while it is (#432). */
    val rerouting: Boolean,
)

/**
 * Pure map from [NavEngine.Progress] — plus the handful of values that live
 * outside it in `MapScreen.kt` — to [NavState]. Ported from (named, not cited
 * by line, so this stays true across edits):
 *  - `app/.../ui/Navigation.kt`'s `NavigationBanner` (headline/maneuver text),
 *    `ThenChip` (the pill's text) and `RideSheet.kt`'s `NavSheet`
 *    (remaining/ETA text and the progress fraction);
 *  - `MapScreen.kt`'s inline speed-limit source switch
 *    (`if (navigating) navProgress?.speedLimitKmh else retained.ambientSpeedLimitKmh`);
 *
 * Does not own `NavEngine.progress()` itself, `NavPolicy.decide`, the reroute
 * network call, `NavVoice`/`NavAnnouncer`, `BleNavServer`, or the maneuver
 * *icon* lookup (`Navigation.kt`'s private `signIcon`, one of four un-shared
 * copies of the GraphHopper sign table — converging those is separate work).
 * Those all stay exactly where they are; this only turns [progress] into the
 * strings/numbers the banner, pill and bottom bar render.
 *
 * [offRoute] is the caller's verdict, not a distance compared here: the app's
 * `NavPolicy` confirms it over a run of fixes scaled by their accuracy (#1008),
 * and `:shared` cannot see `NavPolicy` (the dependency runs the other way).
 * Taking the verdict keeps the bar's "Off route" equal to what reroutes, with
 * no second copy of the bound in this module.
 *
 * [nowMs] is the ETA's clock input — a plain argument, never read from a
 * clock here, so a caller passing a fixed instant gets a fixed string. The
 * zone is not: this public overload resolves
 * `TimeZone.currentSystemDefault()` ambiently on every call, which is what
 * `:app` gets. The `internal` overload beside it takes the zone explicitly,
 * which is how `NavStateTest` pins it to UTC.
 */
fun navStateFrom(
    progress: NavEngine.Progress?,
    navigating: Boolean,
    rerouting: Boolean,
    ambientSpeedLimitKmh: Double?,
    nowMs: Long,
    offRoute: Boolean = false,
    sep: Char = '.',
): NavState = navStateFrom(
    progress, navigating, rerouting, ambientSpeedLimitKmh, nowMs,
    offRoute, sep, TimeZone.currentSystemDefault(),
)

/**
 * [navStateFrom] with the ETA's zone supplied instead of resolved ambiently,
 * so a test can pin it. `internal`, and deliberately so: `TimeZone` on a
 * *public* signature would put kotlinx-datetime on every consumer's compile
 * classpath, because Kotlin resolves a called function's parameter types
 * whether or not the caller passes them — which is what forced an `api`
 * export of the library for a zone `:app` never names. `commonTest` is the
 * same module, so it reaches this directly.
 */
internal fun navStateFrom(
    progress: NavEngine.Progress?,
    navigating: Boolean,
    rerouting: Boolean,
    ambientSpeedLimitKmh: Double?,
    nowMs: Long,
    offRoute: Boolean = false,
    sep: Char = '.',
    zone: TimeZone,
): NavState {
    val headlineText = when {
        rerouting -> "Rerouting…"
        progress == null -> "Waiting for GPS…"
        else -> formatDistanceKm(progress.distanceToTurnMeters, sep)
    }
    val thenPill = progress?.nextNextInstruction?.let { nextNext ->
        progress.distanceToNextNextMeters?.let { distance ->
            NavThenPill(
                sign = nextNext.sign,
                distanceText = formatDistanceKm(distance, sep),
                roundaboutTurnDeg = nextNext.roundaboutTurnDeg,
            )
        }
    }
    val remainingText = progress?.let {
        val minutes = formatFixed((it.remainingTimeMs ?: 0L) / 60_000.0, 0)
        "${formatDistanceKm(it.remainingMeters, sep)} · $minutes min left"
    } ?: "—"
    val arrivalText = when {
        offRoute -> "Off route"
        else -> progress?.remainingTimeMs?.let { "Arrival ${formatEta(nowMs + it, zone)}" } ?: ""
    }
    return NavState(
        headlineText = headlineText,
        maneuverText = progress?.nextInstruction?.text ?: "",
        maneuverSign = progress?.nextInstruction?.sign ?: 0,
        maneuverRoundaboutTurnDeg = progress?.nextInstruction?.roundaboutTurnDeg,
        thenPill = thenPill,
        remainingText = remainingText,
        arrivalText = arrivalText,
        offRoute = offRoute,
        progressFraction = (progress?.drivenFraction ?: 0.0).toFloat(),
        speedLimitKmh = if (navigating) progress?.speedLimitKmh else ambientSpeedLimitKmh,
        rerouting = rerouting,
    )
}

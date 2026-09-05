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
    /** Null past the last turn or when there's only one left — same guard
     *  `ThenPill` uses today to render nothing. */
    val thenPill: NavThenPill?,
    /** "12.4 km · 25 min", or "—" before the first fix. */
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
)

/**
 * Pure map from [NavEngine.Progress] — plus the handful of values that live
 * outside it in `MapScreen.kt` — to [NavState]. Ported from:
 *  - `app/.../ui/Navigation.kt`'s `NavigationBanner` (headline/maneuver text,
 *    lines 96-116), `ThenPill` (the pill's text, lines 129-148) and
 *    `NavigationBottomBar` (remaining/ETA text and the progress fraction,
 *    lines 171-201);
 *  - `MapScreen.kt`'s inline speed-limit source switch (line 1777-1778:
 *    `if (navigating) navProgress?.speedLimitKmh else retained.ambientSpeedLimitKmh`);
 *  - `MapScreen.kt`'s inline off-route comparison (line 1860-1861:
 *    `(navProgress?.offRouteMeters ?: 0.0) > NavPolicy.OFF_ROUTE_METERS`).
 *
 * Does not own `NavEngine.progress()` itself, `NavPolicy.decide`, the reroute
 * network call, `NavVoice`/`NavAnnouncer`, `BleNavServer`, or the maneuver
 * *icon* lookup (`Navigation.kt`'s private `signIcon`, one of four un-shared
 * copies of the GraphHopper sign table — converging those is separate work).
 * Those all stay exactly where they are; this only turns [progress] into the
 * strings/numbers the banner, pill and bottom bar render.
 *
 * [offRouteThresholdMeters] defaults to the same 60.0 the app's
 * `NavPolicy.OFF_ROUTE_METERS` uses. It is a separate constant, not a shared
 * reference, because `:shared` cannot depend on `:app` (the dependency runs
 * the other way) — a fifth place this comparison is now written, alongside
 * `MapScreen.kt` and `car/NavScreen.kt`, both of which already compare
 * against `NavPolicy.OFF_ROUTE_METERS` directly.
 *
 * [nowMs] and [zone] are the ETA's clock/timezone inputs — plain arguments,
 * never read from a clock, so this stays deterministic like every other
 * mapper here.
 */
fun navStateFrom(
    progress: NavEngine.Progress?,
    navigating: Boolean,
    rerouting: Boolean,
    ambientSpeedLimitKmh: Double?,
    nowMs: Long,
    offRouteThresholdMeters: Double = 60.0,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): NavState {
    val offRoute = (progress?.offRouteMeters ?: 0.0) > offRouteThresholdMeters
    val headlineText = when {
        rerouting -> "Rerouting…"
        progress == null -> "Waiting for GPS…"
        else -> formatDistanceKm(progress.distanceToTurnMeters)
    }
    val thenPill = progress?.nextNextInstruction?.let { nextNext ->
        progress.distanceToNextNextMeters?.let { distance ->
            NavThenPill(sign = nextNext.sign, distanceText = formatDistanceKm(distance))
        }
    }
    val remainingText = progress?.let {
        val minutes = formatFixed((it.remainingTimeMs ?: 0L) / 60_000.0, 0)
        "${formatDistanceKm(it.remainingMeters)} · $minutes min"
    } ?: "—"
    val arrivalText = when {
        offRoute -> "Off route"
        else -> progress?.remainingTimeMs?.let { "Arrival ${formatEta(nowMs + it, zone)}" } ?: ""
    }
    return NavState(
        headlineText = headlineText,
        maneuverText = progress?.nextInstruction?.text ?: "",
        maneuverSign = progress?.nextInstruction?.sign ?: 0,
        thenPill = thenPill,
        remainingText = remainingText,
        arrivalText = arrivalText,
        offRoute = offRoute,
        progressFraction = (progress?.drivenFraction ?: 0.0).toFloat(),
        speedLimitKmh = if (navigating) progress?.speedLimitKmh else ambientSpeedLimitKmh,
    )
}

package com.jellemax.detour.presentation

import com.jellemax.detour.data.CurvyPreset
import com.jellemax.detour.data.CurvyRoute
import com.jellemax.detour.data.DetourCost
import com.jellemax.detour.data.TravelMode

/** What the A→B curviness control shows (#652). */
enum class CurvyControl {
    /**
     * Not a moto route, or the server can't route curvy: no control. The
     * unsupported case used to show a permanent "your routing server can't do
     * this yet" line, on every destination, for a feature the rider hadn't
     * asked for (#1027).
     */
    HIDDEN,

    /** The fast / balanced / twisty slider. */
    SLIDER,
}

/**
 * Which [CurvyControl] a destination on [mode] gets. [supported] is
 * `RoutingSupport.curvyRoutingSupported()`. Car is hidden whatever the
 * server: `RoutePreferences.rulesFor` drops the preset off every profile but
 * moto, so a slider there would change nothing.
 */
fun curvyControl(mode: TravelMode, supported: Boolean): CurvyControl =
    if (mode == TravelMode.MOTO && supported) CurvyControl.SLIDER else CurvyControl.HIDDEN

/**
 * The cost line under the slider, "+12 min · +9.4 km", as its two numbers.
 * Clamped at zero: a curvy route that comes back a minute quicker than the
 * fastest one is GraphHopper's rounding, not a shortcut worth advertising.
 */
data class DetourCostLine(val extraMinutes: Long, val extraMeters: Double)

fun detourCostLine(cost: DetourCost): DetourCostLine = DetourCostLine(
    extraMinutes = cost.extraMinutes.coerceAtLeast(0),
    extraMeters = cost.extraMeters.coerceAtLeast(0.0),
)

/**
 * Everything the navigation dock's curviness row renders, as one value so the
 * dock takes one parameter for it. [preview] is the last `curvyRouteVia`
 * answer for [preset], null for [CurvyPreset.FAST], while [loading], or when
 * the request failed.
 */
data class CurvyRouteState(
    val control: CurvyControl,
    val preset: CurvyPreset,
    val preview: CurvyRoute?,
    val loading: Boolean,
) {
    /** Null when there's nothing to compare: fast, no answer yet, or no fastest route. */
    val costLine: DetourCostLine? get() = preview?.detourCost?.let(::detourCostLine)

    /** Whether a preview should be asked for at all. */
    val wantsPreview: Boolean get() = control == CurvyControl.SLIDER && preset != CurvyPreset.FAST
}

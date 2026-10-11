package com.jellemax.detour.presentation

import com.jellemax.detour.data.RoutePreferences

/** The Settings root's subtitle strings, already formatted. No Android types. */
data class SettingsHubState(
    val appearanceSubtitle: String,
    val trackingSubtitle: String,
    val navigationSubtitle: String,
    val fogSubtitle: String,
    val displaysSubtitle: String,
    val serversSubtitle: String,
)

/**
 * What the Navigation row summarises: the avoid options routing will use, and
 * whether HERE traffic is on. One input rather than two parameters, since the
 * row's subtitle is the only reader of either.
 *
 * [avoid] is what routing will actually use —
 * [com.jellemax.detour.data.Settings.routePreferences] — so
 * an option the server can't honour isn't listed as on.
 *
 * [trafficOn] is whether a HERE key is stored — which only happens once it has
 * passed its test (#644) — so "traffic on" is never shown for a key that failed.
 * Off says nothing: a rider with no key sees no traffic anywhere but the
 * Navigation spoke's own section (#637).
 */
data class NavigationSummary(
    val avoid: RoutePreferences,
    val trafficOn: Boolean = false,
)

private fun onOff(value: Boolean): String = if (value) "on" else "off"

/** Every avoid option that is on, in the order the Navigation spoke lists them. */
private fun avoidSummary(avoid: RoutePreferences): String {
    val on = listOfNotNull(
        "highways".takeIf { avoid.avoidHighways },
        "small roads".takeIf { avoid.avoidSmallRoads },
        "tolls".takeIf { avoid.avoidTolls },
        "ferries".takeIf { avoid.avoidFerries },
        "unpaved".takeIf { avoid.avoidUnpaved },
    )
    return "Avoid: " + on.ifEmpty { listOf("nothing") }.joinToString(", ")
}

/**
 * Pure map from the settings the root summarises to the strings it shows.
 *
 * [themeName] arrives as the enum's own name rather than the enum, so `:shared`
 * needs no dependency on `Settings.Theme`, which lives on the app side.
 *
 * [navigation] carries the avoid options and the traffic flag; see
 * [NavigationSummary].
 *
 * The OBD2 row's subtitle is a constant and stays at the call site: a field that
 * never varies is a field with nothing to assert.
 */
fun settingsHubStateFrom(
    themeName: String,
    autoDetectDrives: Boolean,
    navigation: NavigationSummary,
    fogRadiusMeters: Double,
    externalDisplayEnabled: Boolean,
    authUsername: String,
): SettingsHubState = SettingsHubState(
    appearanceSubtitle = themeName.lowercase()
        .replaceFirstChar { it.uppercase() } + " theme",
    trackingSubtitle = "Auto-detect drives: " + onOff(autoDetectDrives),
    navigationSubtitle = avoidSummary(navigation.avoid) + if (navigation.trafficOn) " · traffic on" else "",
    // Truncating, matching the slider's own whole-metre steps.
    fogSubtitle = "${fogRadiusMeters.toInt()} m reveal radius",
    displaysSubtitle = "External display: " + onOff(externalDisplayEnabled),
    serversSubtitle = if (authUsername.isBlank()) "Not signed in"
        else "Signed in as $authUsername",
)

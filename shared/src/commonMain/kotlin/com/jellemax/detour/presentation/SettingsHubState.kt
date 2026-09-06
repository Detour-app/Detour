package com.jellemax.detour.presentation

/** The Settings root's subtitle strings, already formatted. No Android types. */
data class SettingsHubState(
    val appearanceSubtitle: String,
    val trackingSubtitle: String,
    val navigationSubtitle: String,
    val fogSubtitle: String,
    val displaysSubtitle: String,
    val serversSubtitle: String,
)

private fun onOff(value: Boolean): String = if (value) "on" else "off"

/**
 * Pure map from the six settings the root summarises to the strings it shows.
 *
 * [themeName] arrives as the enum's own name rather than the enum, so `:shared`
 * needs no dependency on `Settings.Theme`, which lives on the app side.
 *
 * The OBD2 row's subtitle is a constant and stays at the call site: a field that
 * never varies is a field with nothing to assert.
 */
fun settingsHubStateFrom(
    themeName: String,
    autoDetectDrives: Boolean,
    avoidHighways: Boolean,
    fogRadiusMeters: Double,
    externalDisplayEnabled: Boolean,
    authUsername: String,
): SettingsHubState = SettingsHubState(
    appearanceSubtitle = themeName.lowercase()
        .replaceFirstChar { it.uppercase() } + " theme",
    trackingSubtitle = "Auto-detect drives: " + onOff(autoDetectDrives),
    navigationSubtitle = "Avoid highways: " + onOff(avoidHighways),
    // Truncating, matching the slider's own whole-metre steps.
    fogSubtitle = "${fogRadiusMeters.toInt()} m reveal radius",
    displaysSubtitle = "External display: " + onOff(externalDisplayEnabled),
    serversSubtitle = if (authUsername.isBlank()) "Not signed in"
        else "Signed in as $authUsername",
)

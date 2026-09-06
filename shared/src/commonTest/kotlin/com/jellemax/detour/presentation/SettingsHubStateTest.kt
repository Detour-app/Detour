package com.jellemax.detour.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsHubStateTest {

    private fun state(
        themeName: String = "DARK",
        autoDetectDrives: Boolean = false,
        avoidHighways: Boolean = false,
        fogRadiusMeters: Double = 250.0,
        externalDisplayEnabled: Boolean = false,
        authUsername: String = "",
    ) = settingsHubStateFrom(
        themeName, autoDetectDrives, avoidHighways,
        fogRadiusMeters, externalDisplayEnabled, authUsername,
    )

    @Test
    fun `theme name is title-cased for display`() {
        assertEquals("Dark theme", state(themeName = "DARK").appearanceSubtitle)
        assertEquals("System theme", state(themeName = "SYSTEM").appearanceSubtitle)
    }

    @Test
    fun `boolean settings read as on or off`() {
        assertEquals("Auto-detect drives: on", state(autoDetectDrives = true).trackingSubtitle)
        assertEquals("Auto-detect drives: off", state(autoDetectDrives = false).trackingSubtitle)
        assertEquals("Avoid highways: on", state(avoidHighways = true).navigationSubtitle)
        assertEquals("External display: off", state(externalDisplayEnabled = false).displaysSubtitle)
    }

    @Test
    fun `fog radius drops the fraction and carries its unit`() {
        assertEquals("250 m reveal radius", state(fogRadiusMeters = 250.0).fogSubtitle)
        assertEquals("80 m reveal radius", state(fogRadiusMeters = 80.9).fogSubtitle)
    }

    @Test
    fun `a blank username is not signed in`() {
        assertEquals("Not signed in", state(authUsername = "").serversSubtitle)
        assertEquals("Not signed in", state(authUsername = "   ").serversSubtitle)
        assertEquals("Signed in as rider", state(authUsername = "rider").serversSubtitle)
    }
}

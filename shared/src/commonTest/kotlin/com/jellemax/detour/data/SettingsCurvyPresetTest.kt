package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals

/** The stored form of [Settings.curvyPreset] (#652): the enum name, with
 *  anything else reading as the plain fastest route. */
class SettingsCurvyPresetTest {

    @Test
    fun everyPresetRoundTripsThroughItsName() {
        for (preset in CurvyPreset.entries) {
            assertEquals(preset, Settings.decodeCurvyPreset(preset.name))
        }
    }

    @Test
    fun nothingStoredIsFast() {
        assertEquals(CurvyPreset.FAST, Settings.decodeCurvyPreset(""))
    }

    @Test
    fun aNameThisBuildDoesNotKnowIsFast() {
        assertEquals(CurvyPreset.FAST, Settings.decodeCurvyPreset("WIGGLY"))
    }

    @Test
    fun theStoredPresetReachesTheRequestOnlyWhenTheGraphHasCurvature() {
        val chosen = RoutePreferences(curvy = Settings.decodeCurvyPreset(CurvyPreset.TWISTY.name))
        assertEquals(CurvyPreset.TWISTY, chosen.supportedBy(setOf(RoutingEncodedValue.CURVATURE)).curvy)
        assertEquals(CurvyPreset.FAST, chosen.supportedBy(emptySet()).curvy)
    }
}

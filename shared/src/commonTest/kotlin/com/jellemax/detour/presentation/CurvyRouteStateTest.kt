package com.jellemax.detour.presentation

import com.jellemax.detour.data.CurvyPreset
import com.jellemax.detour.data.CurvyRoute
import com.jellemax.detour.data.DetourCost
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.data.TravelMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The navigation dock's curviness row (#652), without Compose. */
class CurvyRouteStateTest {

    private val route = RouteResult(polyline = emptyList(), waypoints = emptyList(), distanceMeters = 42_000.0)

    private fun state(
        preset: CurvyPreset,
        cost: DetourCost? = null,
        control: CurvyControl = CurvyControl.SLIDER,
    ) = CurvyRouteState(
        control = control,
        preset = preset,
        preview = CurvyRoute(route, cost, curviness = null),
        loading = false,
    )

    @Test
    fun aSupportedMotoRouteGetsTheSlider() {
        assertEquals(CurvyControl.SLIDER, curvyControl(TravelMode.MOTO, supported = true))
    }

    @Test
    fun anUnsupportedMotoRouteSaysWhy() {
        assertEquals(CurvyControl.UNSUPPORTED, curvyControl(TravelMode.MOTO, supported = false))
    }

    @Test
    fun aCarRouteHasNoControlWhateverTheServerSupports() {
        assertEquals(CurvyControl.HIDDEN, curvyControl(TravelMode.CAR, supported = true))
        assertEquals(CurvyControl.HIDDEN, curvyControl(TravelMode.CAR, supported = false))
    }

    @Test
    fun theCostLineRoundsMinutesAndKeepsMetres() {
        val line = state(CurvyPreset.TWISTY, DetourCost(extraSeconds = 731, extraMeters = 9_400.0)).costLine
        assertEquals(DetourCostLine(extraMinutes = 12, extraMeters = 9_400.0), line)
    }

    @Test
    fun aCurvyRouteThatComesBackQuickerReadsAsZero() {
        val line = state(CurvyPreset.BALANCED, DetourCost(extraSeconds = -90, extraMeters = -300.0)).costLine
        assertEquals(DetourCostLine(extraMinutes = 0, extraMeters = 0.0), line)
    }

    @Test
    fun noFastestRouteMeansNoCostLine() {
        assertNull(state(CurvyPreset.TWISTY, cost = null).costLine)
    }

    @Test
    fun onlyACurvyPresetOnTheSliderAsksForAPreview() {
        assertTrue(state(CurvyPreset.BALANCED).wantsPreview)
        assertFalse(state(CurvyPreset.FAST).wantsPreview)
        assertFalse(state(CurvyPreset.TWISTY, control = CurvyControl.UNSUPPORTED).wantsPreview)
        assertFalse(state(CurvyPreset.TWISTY, control = CurvyControl.HIDDEN).wantsPreview)
    }
}

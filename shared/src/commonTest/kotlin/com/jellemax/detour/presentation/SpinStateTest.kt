package com.jellemax.detour.presentation

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RouteCandidate
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.data.TravelMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure mapping behind the spin dock and result sheet: the mode label, the
 * radius readout, the direction name and the candidate rows `ModeCell`
 * (`SpinDock.kt`) and `CandidatesCard` (`CandidatesCard.kt`) have always built
 * inline. Does not cover `spin()`'s orchestration, the convoy vote plumbing,
 * or `camAuthority` - those stay in `MapScreen.kt`.
 */
class SpinStateTest {

    private fun candidate(
        name: String? = "Old Mill Road",
        distanceMeters: Double? = 12_400.0,
        timeMs: Long? = 25 * 60_000L,
        straightLineMeters: Double = 9_000.0,
    ) = RouteCandidate(
        destination = LatLon(0.0, 0.0),
        name = name,
        route = distanceMeters?.let {
            RouteResult(polyline = emptyList(), waypoints = emptyList(), distanceMeters = it,
                timeMs = timeMs)
        },
        straightLineMeters = straightLineMeters,
    )

    // --- radiusText: the "%.1f" / toInt() split ------------------------------

    @Test fun aWholeRadiusAboveTheDecimalCutoffShowsNoFraction() {
        assertEquals("25 km", radiusText(maxKm = 100f, radiusKm = 25f))
    }

    @Test fun aFractionalRadiusAboveTheDecimalCutoffIsTruncatedNotRounded() {
        // toInt() truncates toward zero - 25.9 must not round up to "26 km".
        assertEquals("25 km", radiusText(maxKm = 100f, radiusKm = 25.9f))
    }

    @Test fun aFractionalRadiusAtOrBelowTheCutoffShowsOneDecimal() {
        assertEquals("2.5 km", radiusText(maxKm = 10f, radiusKm = 2.5f))
    }

    @Test fun aWholeRadiusAtOrBelowTheCutoffStillShowsOneDecimal() {
        assertEquals("2.0 km", radiusText(maxKm = 10f, radiusKm = 2.0f))
    }

    @Test fun theCutoffIsInclusive() {
        // mode.maxKm <= 10f, not < 10f.
        assertEquals("2.5 km", radiusText(maxKm = 10f, radiusKm = 2.5f))
    }

    @Test fun realTravelModesNeverReachTheDecimalBranch() {
        // CAR's maxKm is 100, MOTO's is 400 - neither is <= 10f, so
        // spinStateFrom always renders a whole-number radius today.
        for (mode in TravelMode.entries) {
            assertTrue(mode.maxKm > 10f, "${mode.name}.maxKm should be > 10f")
        }
    }

    // --- candidateRow: distance/duration strings ------------------------------

    @Test fun aRoutedCandidateShowsViaRoadDistanceAndItsMinutes() {
        val row = candidateRow(0, candidate(distanceMeters = 12_400.0, timeMs = 25 * 60_000L))
        assertEquals("via road 12.4 km", row.distanceText)
        assertEquals("25 min", row.durationText)
    }

    @Test fun anUnroutedCandidateFallsBackToStraightLineDistanceWithNoDuration() {
        val row = candidateRow(0, candidate(distanceMeters = null, straightLineMeters = 9_000.0))
        assertEquals("~ straight-line 9.0 km", row.distanceText)
        assertNull(row.durationText)
    }

    @Test fun aRoutedCandidateWithNoReportedTimeHasNoDurationText() {
        val row = candidateRow(0, candidate(distanceMeters = 5_000.0, timeMs = null))
        assertEquals("via road 5.0 km", row.distanceText)
        assertNull(row.durationText)
    }

    @Test fun aCandidateWithNoNameFallsBackToItsOneBasedOptionNumber() {
        val row = candidateRow(1, candidate(name = null))
        assertEquals("Option 2", row.name)
    }

    @Test fun aNamedCandidateKeepsItsName() {
        val row = candidateRow(0, candidate(name = "Old Mill Road"))
        assertEquals("Old Mill Road", row.name)
    }

    @Test fun subKilometreScaleDistancesRenderInMetresNotKilometres() {
        val row = candidateRow(0, candidate(distanceMeters = 850.0))
        assertEquals("via road 850 m", row.distanceText)
    }

    // --- spinStateFrom: the whole mapper --------------------------------------

    @Test fun theRadiusReadoutComesThroughForACarSpin() {
        val state = spinStateFrom(TravelMode.CAR, radiusKm = 25f, candidates = emptyList())
        assertEquals("25 km", state.radiusText)
    }

    @Test fun aZeroCandidateStateProducesAnEmptyButUsableCandidateList() {
        val state = spinStateFrom(TravelMode.CAR, radiusKm = 25f, candidates = emptyList())
        assertTrue(state.candidates.isEmpty())
    }

    @Test fun candidatesKeepTheirRollOrderForTheLetterLabelsAppliedAtRenderTime() {
        val state = spinStateFrom(
            TravelMode.CAR, radiusKm = 25f,
            candidates = listOf(candidate(name = "A"), candidate(name = "B"), candidate(name = "C")),
        )
        assertEquals(listOf("A", "B", "C"), state.candidates.map { it.name })
    }

    // --- the ranges the spin sheet's slider is bound to -----------------------

    /**
     * The spin sheet's mode control resets the radius to the new mode's
     * `defaultKm` rather than carrying the old one across, because the slider
     * is bound to `minKm..maxKm` and the two ranges only partly overlap. That
     * reset is only safe while every default sits inside its own mode's range;
     * a default outside it would put the slider's thumb off the track the
     * moment the mode changed.
     */
    @Test fun everyModesDefaultRadiusIsInsideItsOwnSliderRange() {
        for (mode in TravelMode.entries) {
            assertTrue(mode.minKm < mode.maxKm, "${mode.label} has an empty range")
            assertTrue(
                mode.defaultKm in mode.minKm..mode.maxKm,
                "${mode.label} default ${mode.defaultKm} is outside ${mode.minKm}..${mode.maxKm}",
            )
        }
    }

    /**
     * And the reset is only *necessary* while some radius valid in one mode is
     * invalid in the other - if the ranges nested, carrying the value over
     * would do. Car's default is below Moto's floor today, which is exactly
     * the case that would strand the slider.
     */
    @Test fun aRadiusValidForOneModeCanBeOutOfRangeForTheOther() {
        assertTrue(TravelMode.CAR.defaultKm !in TravelMode.MOTO.minKm..TravelMode.MOTO.maxKm)
    }
}

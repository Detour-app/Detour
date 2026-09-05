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

    // --- directionText: the eight buckets and their edges --------------------

    @Test fun everyDirectionBucketNamesItsCompassPoint() {
        assertEquals("North", directionText(0f))
        assertEquals("North-east", directionText(45f))
        assertEquals("East", directionText(90f))
        assertEquals("South-east", directionText(135f))
        assertEquals("South", directionText(180f))
        assertEquals("South-west", directionText(225f))
        assertEquals("West", directionText(270f))
        assertEquals("North-west", directionText(315f))
    }

    @Test fun aNullDirectionReadsAnyDirection() {
        assertEquals("any direction", directionText(null))
    }

    @Test fun justUnderThreeHundredSixtyStaysInTheLastBucket() {
        assertEquals("North-west", directionText(359.9f))
    }

    @Test fun exactlyThreeHundredSixtyDegreesWrapsToNorth() {
        // 360 is a full turn back to 0 - wraps to bucket 0 instead of
        // indexing one past DIRECTION_NAMES' last entry.
        assertEquals("North", directionText(360f))
    }

    @Test fun aNegativeMultipleOfFortyFiveWrapsToTheMatchingPositiveBucket() {
        // -45 is the same bearing as 315 - wraps to "North-west" instead of
        // indexing -1.
        assertEquals("North-west", directionText(-45f))
    }

    @Test fun aNegativeNonMultipleWrapsToItsActualBucketNotNorth() {
        // -10 is the same bearing as 350, which falls in the 315..360 bucket -
        // "North-west", not the old truncate-toward-zero "North".
        assertEquals("North-west", directionText(-10f))
    }

    @Test fun aLargePositiveMultipleOfAFullTurnWrapsCorrectly() {
        // 720 is two full turns past 0 - the old code could never even
        // express this without throwing at 360 first.
        assertEquals("North", directionText(720f))
    }

    @Test fun aLargeNegativeBearingWrapsCorrectly() {
        // -400 is the same bearing as 320 (-400 + 2*360), landing in the
        // 315..360 bucket.
        assertEquals("North-west", directionText(-400f))
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

    @Test fun theModeLabelAndRadiusAndDirectionComeThroughForACarSpin() {
        val state = spinStateFrom(TravelMode.CAR, radiusKm = 25f, directionDeg = 90f, candidates = emptyList())
        assertEquals("Car", state.modeLabel)
        assertEquals("25 km", state.radiusText)
        assertEquals("East", state.directionText)
    }

    @Test fun aZeroCandidateStateProducesAnEmptyButUsableCandidateList() {
        val state = spinStateFrom(TravelMode.CAR, radiusKm = 25f, directionDeg = null, candidates = emptyList())
        assertTrue(state.candidates.isEmpty())
        assertEquals("any direction", state.directionText)
    }

    @Test fun candidatesKeepTheirRollOrderForTheLetterLabelsAppliedAtRenderTime() {
        val state = spinStateFrom(
            TravelMode.CAR, radiusKm = 25f, directionDeg = null,
            candidates = listOf(candidate(name = "A"), candidate(name = "B"), candidate(name = "C")),
        )
        assertEquals(listOf("A", "B", "C"), state.candidates.map { it.name })
    }
}

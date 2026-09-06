package com.jellemax.detour.presentation

import com.jellemax.detour.data.NavEngine
import com.jellemax.detour.data.NavInstruction
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The pure mapping behind the nav banner, the "then" pill and the bottom bar
 * (`app/.../ui/Navigation.kt`'s `NavigationBanner`, `ThenChip` and
 * `NavigationBottomBar`), plus the speed-limit source switch and the
 * off-route comparison `MapScreen.kt` has always computed inline. Does not
 * cover `NavEngine.progress()` itself, `NavPolicy.decide`, the reroute call,
 * `NavVoice`, or `BleNavServer` - those stay in `MapScreen.kt`/`NavPolicy.kt`.
 */
class NavStateTest {

    private fun instruction(text: String = "Turn right", sign: Int = 2) =
        NavInstruction(text = text, distanceMeters = 0.0, sign = sign, startIndex = 0, endIndex = 0)

    private fun progress(
        offRouteMeters: Double = 0.0,
        nextInstruction: NavInstruction? = instruction(),
        distanceToTurnMeters: Double = 500.0,
        remainingMeters: Double = 5_000.0,
        routeMeters: Double = 10_000.0,
        remainingTimeMs: Long? = 8 * 60_000L,
        speedLimitKmh: Double? = 50.0,
        nextNextInstruction: NavInstruction? = null,
        distanceToNextNextMeters: Double? = null,
    ) = NavEngine.Progress(
        offRouteMeters = offRouteMeters,
        nextInstruction = nextInstruction,
        distanceToTurnMeters = distanceToTurnMeters,
        remainingMeters = remainingMeters,
        routeMeters = routeMeters,
        remainingTimeMs = remainingTimeMs,
        speedLimitKmh = speedLimitKmh,
        nextNextInstruction = nextNextInstruction,
        distanceToNextNextMeters = distanceToNextNextMeters,
    )

    // --- headline: rerouting / waiting-for-GPS / distance-to-turn -----------

    @Test fun reroutingTakesPriorityOverEverything() {
        val state = navStateFrom(
            progress = progress(), navigating = true, rerouting = true,
            ambientSpeedLimitKmh = null, nowMs = 0L,
        )
        assertEquals("Rerouting…", state.headlineText)
    }

    @Test fun nullProgressWaitsForGps() {
        val state = navStateFrom(
            progress = null, navigating = true, rerouting = false,
            ambientSpeedLimitKmh = null, nowMs = 0L,
        )
        assertEquals("Waiting for GPS…", state.headlineText)
    }

    // --- distance-to-turn across the m -> km boundary ------------------------

    @Test fun distanceToTurnUnderAKilometreReadsInMetres() {
        val state = navStateFrom(
            progress = progress(distanceToTurnMeters = 350.0), navigating = true,
            rerouting = false, ambientSpeedLimitKmh = null, nowMs = 0L,
        )
        assertEquals("350 m", state.headlineText)
    }

    @Test fun distanceToTurnJustUnderAKilometreRoundsUpButStaysInMetres() {
        // The threshold check runs before rounding: 999.6 m is still "< 1000",
        // so it renders as rounded metres ("1000 m"), not "1.0 km" - the same
        // quirk `formatDistanceKm` has always had.
        val state = navStateFrom(
            progress = progress(distanceToTurnMeters = 999.6), navigating = true,
            rerouting = false, ambientSpeedLimitKmh = null, nowMs = 0L,
        )
        assertEquals("1000 m", state.headlineText)
    }

    @Test fun distanceToTurnAtExactlyAKilometreSwitchesToKm() {
        val state = navStateFrom(
            progress = progress(distanceToTurnMeters = 1_000.0), navigating = true,
            rerouting = false, ambientSpeedLimitKmh = null, nowMs = 0L,
        )
        assertEquals("1.0 km", state.headlineText)
    }

    @Test fun distanceToTurnAboveAKilometreRoundsToOneDecimal() {
        val state = navStateFrom(
            progress = progress(distanceToTurnMeters = 1_499.0), navigating = true,
            rerouting = false, ambientSpeedLimitKmh = null, nowMs = 0L,
        )
        assertEquals("1.5 km", state.headlineText)
    }

    // --- maneuver text / then pill -------------------------------------------

    @Test fun maneuverTextAndSignComeFromNextInstruction() {
        val state = navStateFrom(
            progress = progress(nextInstruction = instruction("Turn left", sign = -2)),
            navigating = true, rerouting = false, ambientSpeedLimitKmh = null, nowMs = 0L,
        )
        assertEquals("Turn left", state.maneuverText)
        assertEquals(-2, state.maneuverSign)
    }

    @Test fun thenPillIsPresentWhenANextNextInstructionExists() {
        val state = navStateFrom(
            progress = progress(
                nextNextInstruction = instruction("Then merge", sign = 6),
                distanceToNextNextMeters = 1_200.0,
            ),
            navigating = true, rerouting = false, ambientSpeedLimitKmh = null, nowMs = 0L,
        )
        assertEquals(NavThenPill(sign = 6, distanceText = "1.2 km"), state.thenPill)
    }

    @Test fun thenPillIsAbsentPastTheLastTurn() {
        val state = navStateFrom(
            progress = progress(nextNextInstruction = null, distanceToNextNextMeters = null),
            navigating = true, rerouting = false, ambientSpeedLimitKmh = null, nowMs = 0L,
        )
        assertNull(state.thenPill)
    }

    // --- remaining distance / ETA ---------------------------------------------

    @Test fun remainingTextCombinesDistanceAndMinutes() {
        val state = navStateFrom(
            progress = progress(remainingMeters = 12_400.0, remainingTimeMs = 25 * 60_000L),
            navigating = true, rerouting = false, ambientSpeedLimitKmh = null, nowMs = 0L,
        )
        assertEquals("12.4 km · 25 min left", state.remainingText)
    }

    @Test fun anEtaIsNowPlusRemainingTimeInTheGivenZone() {
        // now = epoch 0 (1970-01-01T00:00:00Z), remaining = 5h30m -> 05:30 UTC.
        val state = navStateFrom(
            progress = progress(remainingTimeMs = (5 * 3_600 + 30 * 60) * 1_000L),
            navigating = true, rerouting = false, ambientSpeedLimitKmh = null,
            nowMs = 0L, zone = TimeZone.UTC,
        )
        assertEquals("Arrival 05:30", state.arrivalText)
    }

    @Test fun noRemainingTimeMeansNoArrivalText() {
        val state = navStateFrom(
            progress = progress(remainingTimeMs = null), navigating = true, rerouting = false,
            ambientSpeedLimitKmh = null, nowMs = 0L,
        )
        assertEquals("", state.arrivalText)
    }

    // --- off-route, just under and just over the threshold --------------------

    @Test fun exactlyAtTheThresholdIsNotOffRoute() {
        val state = navStateFrom(
            progress = progress(offRouteMeters = 60.0, remainingTimeMs = 8 * 60_000L),
            navigating = true, rerouting = false,
            ambientSpeedLimitKmh = null, nowMs = 0L, offRouteThresholdMeters = 60.0,
            zone = TimeZone.UTC,
        )
        assertEquals(false, state.offRoute)
        assertEquals("Arrival 00:08", state.arrivalText)
    }

    @Test fun justOverTheThresholdIsOffRoute() {
        val state = navStateFrom(
            progress = progress(offRouteMeters = 60.01), navigating = true, rerouting = false,
            ambientSpeedLimitKmh = null, nowMs = 0L, offRouteThresholdMeters = 60.0,
        )
        assertEquals(true, state.offRoute)
        assertEquals("Off route", state.arrivalText)
    }

    // --- speed-limit source switch, both states --------------------------------

    @Test fun navigatingReadsTheSpeedLimitFromTheRoute() {
        val state = navStateFrom(
            progress = progress(speedLimitKmh = 90.0), navigating = true, rerouting = false,
            ambientSpeedLimitKmh = 30.0, nowMs = 0L,
        )
        assertEquals(90.0, state.speedLimitKmh)
    }

    @Test fun notNavigatingReadsTheAmbientSignEvenWithARouteLoaded() {
        val state = navStateFrom(
            progress = progress(speedLimitKmh = 90.0), navigating = false, rerouting = false,
            ambientSpeedLimitKmh = 30.0, nowMs = 0L,
        )
        assertEquals(30.0, state.speedLimitKmh)
    }

    @Test fun navigatingWithNoRouteSpeedLimitDoesNotFallBackToAmbient() {
        val state = navStateFrom(
            progress = progress(speedLimitKmh = null), navigating = true, rerouting = false,
            ambientSpeedLimitKmh = 30.0, nowMs = 0L,
        )
        assertNull(state.speedLimitKmh)
    }

    // --- null progress produces a usable state ---------------------------------

    @Test fun nullProgressProducesAUsableState() {
        val state = navStateFrom(
            progress = null, navigating = true, rerouting = false,
            ambientSpeedLimitKmh = null, nowMs = 12_345L,
        )
        assertEquals("Waiting for GPS…", state.headlineText)
        assertEquals("", state.maneuverText)
        assertEquals(0, state.maneuverSign)
        assertNull(state.thenPill)
        assertEquals("—", state.remainingText)
        assertEquals("", state.arrivalText)
        assertEquals(false, state.offRoute)
        assertEquals(0f, state.progressFraction)
        assertNull(state.speedLimitKmh)
    }

    @Test fun nullProgressStillReadsTheAmbientSpeedLimitWhenNotNavigating() {
        val state = navStateFrom(
            progress = null, navigating = false, rerouting = false,
            ambientSpeedLimitKmh = 50.0, nowMs = 0L,
        )
        assertEquals(50.0, state.speedLimitKmh)
    }

    // --- progress fraction, driven so far --------------------------------------

    @Test fun progressFractionIsHowMuchOfTheRouteIsDriven() {
        val state = navStateFrom(
            progress = progress(remainingMeters = 2_500.0, routeMeters = 10_000.0),
            navigating = true, rerouting = false, ambientSpeedLimitKmh = null, nowMs = 0L,
        )
        assertEquals(0.75f, state.progressFraction)
    }

    @Test fun progressFractionIsZeroWithNoRouteLengthYet() {
        val state = navStateFrom(
            progress = progress(remainingMeters = 0.0, routeMeters = 0.0),
            navigating = true, rerouting = false, ambientSpeedLimitKmh = null, nowMs = 0L,
        )
        assertEquals(0f, state.progressFraction)
    }
}

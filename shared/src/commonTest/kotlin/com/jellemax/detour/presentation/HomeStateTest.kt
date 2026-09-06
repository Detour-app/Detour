package com.jellemax.detour.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Pins the map screen's bottom-card selection as `MapScreen.kt` computes it
 * today, in two places:
 *
 *  - the `bottomCard` when-chain that picks one of five cards for the
 *    screen's single bottom slot - the nav banner, the candidate list, the
 *    expanded settings sheet, the driving sheet, or the idle home sheet -
 *    first match wins;
 *  - `dockShown`, which re-derives the same conditions in the same order to
 *    decide whether the mode-swipe dock is on screen at all. `dockShown` is
 *    true exactly when the when-chain would land on
 *    [HomeBottomCard.COLLAPSED].
 *
 * The precedence between the five is the contract, not an implementation
 * detail: it is what decides whether a rider recording a trip sees the turn
 * card, the candidates, the Go button or the trip numbers. Each ordering
 * rule gets a test naming the case it protects.
 */
class HomeStateTest {

    @Test fun navigatingWinsOverCandidatesAndCollapsed() {
        assertEquals(
            HomeBottomCard.NAV,
            homeBottomCard(
                navigating = true, hasCandidates = true, collapsed = true, driving = false,
            ),
        )
    }

    @Test fun candidatesWinOverCollapsedWhenNotNavigating() {
        assertEquals(
            HomeBottomCard.CANDIDATES,
            homeBottomCard(
                navigating = false, hasCandidates = true, collapsed = true, driving = false,
            ),
        )
    }

    @Test fun collapsedShowsTheDockWhenNothingElseClaimsTheSlot() {
        assertEquals(
            HomeBottomCard.COLLAPSED,
            homeBottomCard(
                navigating = false, hasCandidates = false, collapsed = true, driving = false,
            ),
        )
    }

    @Test fun expandedIsTheFallbackWhenNothingElseApplies() {
        assertEquals(
            HomeBottomCard.EXPANDED,
            homeBottomCard(
                navigating = false, hasCandidates = false, collapsed = false, driving = false,
            ),
        )
    }

    @Test fun aRecordingTripTakesTheSlotTheIdleHomeSheetWouldHaveHad() {
        assertEquals(
            HomeBottomCard.DRIVING,
            homeBottomCard(
                navigating = false, hasCandidates = false, collapsed = true, driving = true,
            ),
        )
    }

    @Test fun navigationOutranksARecordingTrip() {
        // Recording a trip while navigating is the normal case, not an odd
        // one: the turn you are about to take beats the numbers piling up.
        assertEquals(
            HomeBottomCard.NAV,
            homeBottomCard(
                navigating = true, hasCandidates = false, collapsed = true, driving = true,
            ),
        )
    }

    @Test fun anOpenCandidateRoundOutranksARecordingTrip() {
        assertEquals(
            HomeBottomCard.CANDIDATES,
            homeBottomCard(
                navigating = false, hasCandidates = true, collapsed = true, driving = true,
            ),
        )
    }

    @Test fun aDestinationDroppedMidTripStillGetsTheSpinSheet() {
        // MapScreen opens the spin sheet (collapsed = false) on every new
        // destination, because that sheet holds the Go button. Ranking
        // DRIVING above it would drop a picked destination back into the
        // dead end #190 closed.
        assertEquals(
            HomeBottomCard.EXPANDED,
            homeBottomCard(
                navigating = false, hasCandidates = false, collapsed = false, driving = true,
            ),
        )
    }

    @Test fun theIdleHomeSheetIsNeverOnScreenWhileATripRecords() {
        // The whole point of the driving occupant: no search bar and no
        // Routes/Social cards in the thumb zone mid-ride, whatever else is
        // true at the time.
        for (navigating in listOf(false, true)) {
            for (hasCandidates in listOf(false, true)) {
                for (collapsed in listOf(false, true)) {
                    assertNotEquals(
                        HomeBottomCard.COLLAPSED,
                        homeBottomCard(navigating, hasCandidates, collapsed, driving = true),
                        "navigating=$navigating hasCandidates=$hasCandidates collapsed=$collapsed",
                    )
                }
            }
        }
    }

    @Test fun dockShownAgreesWithTheWhenChainAcrossEveryCombination() {
        // MapScreen.kt's `dockShown` (`!navigating && candidates.isEmpty() &&
        // settingsCollapsed`) is the same three-way precedence as the
        // when-chain, collapsed into one boolean - true iff the chain would
        // pick COLLAPSED. Checked over all sixteen inputs so a rewrite that
        // reorders the chain, rather than just renaming it, still fails here.
        for (navigating in listOf(false, true)) {
            for (hasCandidates in listOf(false, true)) {
                for (collapsed in listOf(false, true)) {
                    for (driving in listOf(false, true)) {
                        val card = homeBottomCard(navigating, hasCandidates, collapsed, driving)
                        val dockShown = !navigating && !hasCandidates && collapsed && !driving
                        assertEquals(
                            dockShown,
                            card == HomeBottomCard.COLLAPSED,
                            "navigating=$navigating hasCandidates=$hasCandidates " +
                                "collapsed=$collapsed driving=$driving",
                        )
                    }
                }
            }
        }
    }
}

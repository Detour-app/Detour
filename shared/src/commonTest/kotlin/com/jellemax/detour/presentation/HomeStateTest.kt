package com.jellemax.detour.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the map screen's bottom-slot selection: one of six occupants, first
 * match wins — the nav sheet, the candidate list, the drive sheet, the
 * navigation dock, the collapsed home sheet, or the expanded spin sheet.
 *
 * The home sheet is the resting occupant, so the last test checks the
 * precedence the other way round too: COLLAPSED exactly when nothing else
 * claims the slot, over every combination of the five inputs.
 */
class HomeStateTest {

    @Test fun navigatingWinsOverEverything() {
        assertEquals(
            HomeBottomCard.NAV,
            homeBottomCard(
                navigating = true, hasCandidates = true, tripActive = true,
                hasDestination = true, collapsed = true,
            ),
        )
    }

    @Test fun candidatesWinOverTheDriveSheetWhenNotNavigating() {
        // A convoy vote round still surfaces on a moving phone.
        assertEquals(
            HomeBottomCard.CANDIDATES,
            homeBottomCard(
                navigating = false, hasCandidates = true, tripActive = true,
                hasDestination = true, collapsed = true,
            ),
        )
    }

    @Test fun aTripWithoutARouteShowsTheDriveSheet() {
        assertEquals(
            HomeBottomCard.DRIVE,
            homeBottomCard(
                navigating = false, hasCandidates = false, tripActive = true,
                hasDestination = false, collapsed = true,
            ),
        )
    }

    @Test fun theDriveSheetOutranksTheExpandedSpinSheet() {
        // #221 flips `collapsed` off whenever a destination is set; mid-trip
        // that must not swap the drive sheet for the spin sheet.
        assertEquals(
            HomeBottomCard.DRIVE,
            homeBottomCard(
                navigating = false, hasCandidates = false, tripActive = true,
                hasDestination = false, collapsed = false,
            ),
        )
    }

    @Test fun theDriveSheetOutranksTheNavigationDockWhicheverWayTheFlagPoints() {
        // A destination set mid-trip is the DriveSheet's own Where-to row's
        // job; the dock must not displace a recording rider's trip stats.
        for (collapsed in listOf(false, true)) {
            assertEquals(
                HomeBottomCard.DRIVE,
                homeBottomCard(
                    navigating = false, hasCandidates = false, tripActive = true,
                    hasDestination = true, collapsed = collapsed,
                ),
                "collapsed=$collapsed",
            )
        }
    }

    @Test fun aConcreteDestinationShowsTheNavigationDockOverEitherRestingCard() {
        // The point of #254: a known destination replaces the idle home sheet
        // *and* the spin sheet, so the discovery controls are never the thing
        // a rider with somewhere to go has to look past.
        for (collapsed in listOf(false, true)) {
            assertEquals(
                HomeBottomCard.DESTINATION,
                homeBottomCard(
                    navigating = false, hasCandidates = false, tripActive = false,
                    hasDestination = true, collapsed = collapsed,
                ),
                "collapsed=$collapsed",
            )
        }
    }

    @Test fun collapsedShowsTheHomeSheetWhenNothingElseClaimsTheSlot() {
        assertEquals(
            HomeBottomCard.COLLAPSED,
            homeBottomCard(
                navigating = false, hasCandidates = false, tripActive = false,
                hasDestination = false, collapsed = true,
            ),
        )
    }

    @Test fun expandedIsTheFallbackWhenNothingElseApplies() {
        assertEquals(
            HomeBottomCard.EXPANDED,
            homeBottomCard(
                navigating = false, hasCandidates = false, tripActive = false,
                hasDestination = false, collapsed = false,
            ),
        )
    }

    @Test fun theHomeSheetRestsExactlyWhenNothingElseClaimsTheSlot() {
        // Checked over all thirty-two inputs so a rewrite that reorders the
        // chain, rather than just renaming it, still fails here.
        for (navigating in listOf(false, true)) {
            for (hasCandidates in listOf(false, true)) {
                for (tripActive in listOf(false, true)) {
                    for (hasDestination in listOf(false, true)) {
                        for (collapsed in listOf(false, true)) {
                            val card = homeBottomCard(
                                navigating, hasCandidates, tripActive, hasDestination, collapsed,
                            )
                            val homeShown = !navigating && !hasCandidates && !tripActive &&
                                !hasDestination && collapsed
                            assertEquals(
                                homeShown,
                                card == HomeBottomCard.COLLAPSED,
                                "navigating=$navigating hasCandidates=$hasCandidates " +
                                    "tripActive=$tripActive hasDestination=$hasDestination " +
                                    "collapsed=$collapsed",
                            )
                        }
                    }
                }
            }
        }
    }

    @Test fun aRecordingTripOutranksTheSpinSheetWhicheverWayTheFlagPoints() {
        // The drive sheet carries its own Where to? and Go row, so the spin
        // sheet is not needed under it; #202's driving occupant ranked the
        // other way round, and this pins which rule the slot follows now.
        for (collapsed in listOf(false, true)) {
            assertEquals(
                HomeBottomCard.DRIVE,
                homeBottomCard(
                    navigating = false, hasCandidates = false, tripActive = true,
                    hasDestination = false, collapsed = collapsed,
                ),
                "collapsed=$collapsed",
            )
        }
    }

    @Test fun aCandidateRoundOutranksARecordingTripWhicheverWayTheFlagPoints() {
        for (collapsed in listOf(false, true)) {
            assertEquals(
                HomeBottomCard.CANDIDATES,
                homeBottomCard(
                    navigating = false, hasCandidates = true, tripActive = true,
                    hasDestination = false, collapsed = collapsed,
                ),
                "collapsed=$collapsed",
            )
        }
    }

}

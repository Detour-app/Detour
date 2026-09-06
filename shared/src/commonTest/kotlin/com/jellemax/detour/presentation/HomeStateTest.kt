package com.jellemax.detour.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the map screen's bottom-card selection as `MapScreen.kt` computes it
 * today, in two places:
 *
 *  - the `bottomCard` when-chain that picks one of four cards for the
 *    screen's single bottom slot - the nav banner, the candidate list, the
 *    collapsed dock, or the expanded settings sheet - first match wins;
 *  - `dockShown`, which re-derives the same three conditions in the same
 *    order to decide whether the mode-swipe dock is on screen at all.
 *    `dockShown` is true exactly when the when-chain would land on
 *    [HomeBottomCard.COLLAPSED].
 *
 * Written against [homeBottomCard]'s intended signature - a single function
 * meant to replace both copies - which does not exist yet. This file will
 * fail to compile until it is added; that failure is this file's job until
 * then, not a bug in it.
 */
class HomeStateTest {

    @Test fun navigatingWinsOverCandidatesAndCollapsed() {
        assertEquals(
            HomeBottomCard.NAV,
            homeBottomCard(navigating = true, hasCandidates = true, collapsed = true),
        )
    }

    @Test fun candidatesWinOverCollapsedWhenNotNavigating() {
        assertEquals(
            HomeBottomCard.CANDIDATES,
            homeBottomCard(navigating = false, hasCandidates = true, collapsed = true),
        )
    }

    @Test fun collapsedShowsTheDockWhenNothingElseClaimsTheSlot() {
        assertEquals(
            HomeBottomCard.COLLAPSED,
            homeBottomCard(navigating = false, hasCandidates = false, collapsed = true),
        )
    }

    @Test fun expandedIsTheFallbackWhenNothingElseApplies() {
        assertEquals(
            HomeBottomCard.EXPANDED,
            homeBottomCard(navigating = false, hasCandidates = false, collapsed = false),
        )
    }

    @Test fun dockShownAgreesWithTheWhenChainAcrossEveryCombination() {
        // MapScreen.kt's `dockShown` (`!navigating && candidates.isEmpty() &&
        // settingsCollapsed`) is the same three-way precedence as the
        // when-chain, collapsed into one boolean - true iff the chain would
        // pick COLLAPSED. Checked over all eight inputs so a rewrite that
        // reorders the chain, rather than just renaming it, still fails here.
        for (navigating in listOf(false, true)) {
            for (hasCandidates in listOf(false, true)) {
                for (collapsed in listOf(false, true)) {
                    val card = homeBottomCard(navigating, hasCandidates, collapsed)
                    val dockShown = !navigating && !hasCandidates && collapsed
                    assertEquals(
                        dockShown,
                        card == HomeBottomCard.COLLAPSED,
                        "navigating=$navigating hasCandidates=$hasCandidates collapsed=$collapsed",
                    )
                }
            }
        }
    }
}

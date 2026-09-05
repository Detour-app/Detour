package com.jellemax.detour.presentation

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RouteCandidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The rest of `HomeState.kt`: the small predicates the idle map screen used to
 * compute inline, one of them written out twice. The four-way bottom-card
 * selection has its own file, `HomeStateTest`.
 */
class HomeIdlePredicatesTest {

    private fun candidate(name: String) =
        RouteCandidate(
            destination = LatLon(0.0, 0.0),
            name = name,
            route = null,
            straightLineMeters = 0.0,
        )

    @Test fun aConvoyOfferOutranksMyOwnCandidates() {
        val offered = listOf(candidate("theirs"))
        assertSame(offered, displayCandidates(offered, listOf(candidate("mine"))))
    }

    @Test fun myOwnCandidatesShowWhenNoOfferIsOnTheTable() {
        val own = listOf(candidate("mine"))
        assertSame(own, displayCandidates(null, own))
    }

    @Test fun anEmptyOfferIsStillAnOfferAndStillWins() {
        // A closed round clears the offer to null; an offer that is merely
        // empty is a round still in progress, and must not fall back to
        // whatever this phone happened to roll before it joined.
        assertEquals(
            emptyList(),
            displayCandidates(emptyList(), listOf(candidate("mine"))),
        )
    }

    @Test fun inAppNavNeedsAUsableRoutingServer() {
        assertFalse(
            inAppNavAvailable(serverUsable = false, hasDestination = true, hasRouteInstructions = true),
        )
    }

    @Test fun inAppNavNeedsSomewhereToGo() {
        assertFalse(
            inAppNavAvailable(serverUsable = true, hasDestination = false, hasRouteInstructions = false),
        )
        assertTrue(
            inAppNavAvailable(serverUsable = true, hasDestination = true, hasRouteInstructions = false),
        )
        assertTrue(
            inAppNavAvailable(serverUsable = true, hasDestination = false, hasRouteInstructions = true),
        )
    }

    @Test fun theReachCircleIsHiddenWithoutAFixOrWhileNavigating() {
        assertNull(reachMeters(hasLocation = false, navigating = false, roundTrip = false, radiusKm = 10.0))
        assertNull(reachMeters(hasLocation = true, navigating = true, roundTrip = false, radiusKm = 10.0))
        // Navigating hides it whichever mode is selected.
        assertNull(reachMeters(hasLocation = true, navigating = true, roundTrip = true, radiusKm = 10.0))
    }

    @Test fun aRoundTripsReachIsAQuarterOfTheSliderLength() {
        assertEquals(
            2_500.0,
            reachMeters(hasLocation = true, navigating = false, roundTrip = true, radiusKm = 10.0),
        )
    }

    @Test fun aOneWayReachIsTheSliderInMetres() {
        assertEquals(
            10_000.0,
            reachMeters(hasLocation = true, navigating = false, roundTrip = false, radiusKm = 10.0),
        )
    }

    @Test fun obd2CountsOnlyDataSeenAfterThisTripStarted() {
        assertTrue(obd2FedThisTrip(tripStartMs = 1_000L, lastDataAtMs = 1_001L))
        // The adapter's stamp is never reset, so a previous trip's adapter -
        // since unplugged - is not this trip's signal to report as lost.
        assertFalse(obd2FedThisTrip(tripStartMs = 1_000L, lastDataAtMs = 999L))
        // Equal stamps are not "after".
        assertFalse(obd2FedThisTrip(tripStartMs = 1_000L, lastDataAtMs = 1_000L))
    }

    @Test fun obd2IsFalseWithNoTripAndWithNoAdapterData() {
        assertFalse(obd2FedThisTrip(tripStartMs = null, lastDataAtMs = 1_000L))
        assertFalse(obd2FedThisTrip(tripStartMs = 1_000L, lastDataAtMs = null))
    }

    @Test fun shortcutChipsHideWhileNavigating() {
        assertFalse(
            shortcutChipsShown(navigating = true, hasSavedPlaces = true, hasDestination = true),
        )
    }

    @Test fun shortcutChipsNeedAPlaceToOfferOrAPinToSave() {
        assertFalse(
            shortcutChipsShown(navigating = false, hasSavedPlaces = false, hasDestination = false),
        )
        assertTrue(
            shortcutChipsShown(navigating = false, hasSavedPlaces = true, hasDestination = false),
        )
        assertTrue(
            shortcutChipsShown(navigating = false, hasSavedPlaces = false, hasDestination = true),
        )
    }

    @Test fun pushToTalkNeedsTheFlagTheRelayAndAConvoy() {
        assertTrue(
            pushToTalkShown(featureEnabled = true, convoyConnected = true, hasActiveConvoy = true),
        )
        assertFalse(
            pushToTalkShown(featureEnabled = false, convoyConnected = true, hasActiveConvoy = true),
        )
        // The rebuilt relay carries positions and votes but drops voice
        // frames, so a connected socket alone does not imply the flag.
        assertFalse(
            pushToTalkShown(featureEnabled = true, convoyConnected = false, hasActiveConvoy = true),
        )
        // Connected for a circle's notify-only join, with no convoy at all.
        assertFalse(
            pushToTalkShown(featureEnabled = true, convoyConnected = true, hasActiveConvoy = false),
        )
    }
}

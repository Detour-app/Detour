package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where an announced routing/geocoder base (issue #177) resolves to, and the
 * consent step that only applies when it differs from the API's own host.
 *
 * Covers `RoutingServer.nextAnnouncedServiceState` and
 * `.resolvedAnnouncedServiceState` rather than the `prefs`-backed accessors
 * around them, for the same reason `ServerFeatureStorageTest` covers
 * `decodeFeatures`/`encodeFeatures` instead of `knownServerFeatures` — the
 * accessors reach a Context no unit test has, and every decision worth
 * protecting lives in these two pure functions.
 */
class AnnouncedServiceStateTest {

    private val apiBase = "https://api.example"
    private val sameHostAnnounced = "https://api.example/gh"
    private val otherHostAnnounced = "https://gh.other.example"

    @Test
    fun anAnnouncementOnTheApisOwnHostIsAcceptedWithoutAPrompt() {
        val next = RoutingServer.nextAnnouncedServiceState(
            announced = sameHostAnnounced,
            apiBase = apiBase,
            previous = RoutingServer.AnnouncedServiceState(),
        )
        assertEquals(
            RoutingServer.AnnouncedServiceState(discovered = "https://api.example/gh"),
            next,
        )
    }

    @Test
    fun anAnnouncementOnADifferentHostIsHeldAsPendingRatherThanUsed() {
        val next = RoutingServer.nextAnnouncedServiceState(
            announced = otherHostAnnounced,
            apiBase = apiBase,
            previous = RoutingServer.AnnouncedServiceState(),
        )
        assertEquals(RoutingServer.AnnouncedServiceState(pending = otherHostAnnounced), next)
    }

    @Test
    fun aBlankAnnouncementClearsEveryStoredValue() {
        // A deployment that stops announcing must not leave a stale address —
        // accepted, pending or declined — behind (#177's own wording).
        val previous = RoutingServer.AnnouncedServiceState(
            discovered = "https://old-discovered.example",
            pending = "https://old-pending.example",
            declined = "https://old-declined.example",
        )
        val next = RoutingServer.nextAnnouncedServiceState("", apiBase, previous)
        assertEquals(RoutingServer.AnnouncedServiceState(), next)
    }

    @Test
    fun aPlainHttpAnnouncementIsRefusedOutrightNotHeldAsPending() {
        // Not a decision for the rider to make — it is not a value at all, the
        // same rule Capabilities.acceptable applies to a discovered issuer.
        val next = RoutingServer.nextAnnouncedServiceState(
            announced = "http://gh.other.example",
            apiBase = apiBase,
            previous = RoutingServer.AnnouncedServiceState(),
        )
        assertEquals(RoutingServer.AnnouncedServiceState(), next)
    }

    @Test
    fun reannouncingTheSameAcceptedValueChangesNothingAndClearsAnyStalePending() {
        val previous = RoutingServer.AnnouncedServiceState(
            discovered = otherHostAnnounced,
            pending = "https://superseded.example",
        )
        val next = RoutingServer.nextAnnouncedServiceState(otherHostAnnounced, apiBase, previous)
        assertEquals(RoutingServer.AnnouncedServiceState(discovered = otherHostAnnounced), next)
    }

    @Test
    fun aPreviouslyDeclinedValueIsNotReofferedUnchanged() {
        val previous = RoutingServer.AnnouncedServiceState(declined = otherHostAnnounced)
        val next = RoutingServer.nextAnnouncedServiceState(otherHostAnnounced, apiBase, previous)
        assertEquals(previous, next)
    }

    @Test
    fun aChangedAnnouncementIsOfferedAgainEvenIfADifferentValueWasDeclinedBefore() {
        val previous = RoutingServer.AnnouncedServiceState(declined = "https://old-declined.example")
        val next = RoutingServer.nextAnnouncedServiceState(otherHostAnnounced, apiBase, previous)
        assertEquals(RoutingServer.AnnouncedServiceState(pending = otherHostAnnounced), next)
    }

    @Test
    fun aNewAnnouncementBecomesPendingWithoutDisturbingAnAlreadyAcceptedOne() {
        // The deployment re-announced a different host before the rider
        // answered the first prompt: the value already in use must keep
        // working while the new one awaits a decision.
        val previous = RoutingServer.AnnouncedServiceState(discovered = "https://already-accepted.example")
        val next = RoutingServer.nextAnnouncedServiceState(otherHostAnnounced, apiBase, previous)
        assertEquals(
            RoutingServer.AnnouncedServiceState(
                discovered = "https://already-accepted.example",
                pending = otherHostAnnounced,
            ),
            next,
        )
    }

    @Test
    fun acceptingAPendingValueMovesItToDiscoveredAndClearsDeclined() {
        val previous = RoutingServer.AnnouncedServiceState(
            pending = otherHostAnnounced,
            declined = "https://old-declined.example",
        )
        assertEquals(
            RoutingServer.AnnouncedServiceState(discovered = otherHostAnnounced),
            RoutingServer.resolvedAnnouncedServiceState(previous, accept = true),
        )
    }

    @Test
    fun decliningAPendingValueDoesNotDisturbAnAlreadyAcceptedOne() {
        // The failure #177 exists to avoid, reachable via a decline instead of
        // a server outage: saying no to a newly-announced host must not stop
        // using the one already in effect.
        val previous = RoutingServer.AnnouncedServiceState(
            discovered = "https://already-accepted.example",
            pending = otherHostAnnounced,
        )
        assertEquals(
            RoutingServer.AnnouncedServiceState(
                discovered = "https://already-accepted.example",
                declined = otherHostAnnounced,
            ),
            RoutingServer.resolvedAnnouncedServiceState(previous, accept = false),
        )
    }
}

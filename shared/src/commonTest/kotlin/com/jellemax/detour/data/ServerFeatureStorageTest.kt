package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The stored form of a server's advertised feature list, and the one thing it
 * has to keep straight: a server that advertises nothing is not the same answer
 * as a server nobody has asked.
 *
 * Split out of `RoutingServer` for the reason `AuthIssuerTest` covers
 * `vettedIssuer` rather than `discoveredIssuer` — the accessors themselves read
 * `prefs`, which reaches a Context no unit test has.
 */
class ServerFeatureStorageTest {

    @Test
    fun anUnwrittenKeyReadsBackAsUnknownRatherThanAsNoFeatures() {
        // What prefs hands back for a key that was never written. Collapsing this
        // into an empty list would have Android stand its relay service down on a
        // server it has never spoken to.
        assertNull(RoutingServer.decodeFeatures(""))
    }

    @Test
    fun aServerThatAdvertisesNothingReadsBackAsAnEmptyAnswer() {
        assertEquals(emptyList(), RoutingServer.decodeFeatures(RoutingServer.encodeFeatures(emptyList())))
    }

    @Test
    fun featuresSurviveTheRoundTrip() {
        val features = listOf("idp-discovery", ServerFeature.PUSH_ANDROID, ServerFeature.PUSH_IOS)

        assertEquals(features, RoutingServer.decodeFeatures(RoutingServer.encodeFeatures(features)))
    }

    @Test
    fun aValueWrittenBySomethingElseReadsBackAsUnknown() {
        // The marker is what makes the empty answer expressible, so a value without
        // one is not this key's — fail to "unknown", which keeps today's transport.
        assertNull(RoutingServer.decodeFeatures("idp-discovery,push-android"))
    }
}

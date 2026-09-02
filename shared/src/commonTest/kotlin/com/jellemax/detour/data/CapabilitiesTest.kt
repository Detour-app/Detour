package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three decisions in issuer discovery that can be made without a network.
 *
 * They are separated from the fetch deliberately: `Http`'s client is private
 * with no injection seam and there is no `MockEngine` in this source set, so a
 * real request cannot be exercised here. Splitting the judgement out is the
 * same move `Auth.store(tokenResponse: String, …)` and
 * `Auth.tokenFailureMessage(code, body)` already make.
 */
class CapabilitiesTest {

    private val document = """
        {"schema":1,"features":["idp-discovery"],
         "idp":{"issuer":"https://idp.example/realms/detour"}}
    """.trimIndent()

    @Test
    fun aCapabilityDocumentYieldsItsSchemaFeaturesAndIssuer() {
        val caps = Capabilities.parse(document)
        assertEquals(1, caps?.schema)
        assertEquals(listOf("idp-discovery"), caps?.features)
        assertEquals("https://idp.example/realms/detour", caps?.idpIssuer)
    }

    @Test
    fun theIssuerIsNormalisedTheSameWayASavedOneIs() {
        // RoutingServer.pick trims and strips a trailing slash. A discovered
        // value that skipped that would compare unequal to the identical typed
        // one, and the ID token's `iss` carries no trailing slash — so a
        // mismatch here would refuse a sign-in that is actually correct.
        val caps = Capabilities.parse(
            """{"schema":1,"features":[],"idp":{"issuer":" https://idp.example/realms/detour/ "}}"""
        )
        assertEquals("https://idp.example/realms/detour", caps?.idpIssuer)
    }

    @Test
    fun anUnknownFeatureOrFieldIsCarriedOrIgnoredButNeverRefused() {
        // The compatibility rule that lets an old app read a new server's
        // document. A higher schema is still read, not rejected.
        val caps = Capabilities.parse(
            """{"schema":7,"features":["idp-discovery","something-new"],
                "idp":{"issuer":"https://idp.example/realms/detour"},
                "somethingElse":{"nested":true}}"""
        )
        assertEquals(7, caps?.schema)
        assertEquals(listOf("idp-discovery", "something-new"), caps?.features)
        assertEquals("https://idp.example/realms/detour", caps?.idpIssuer)
    }

    @Test
    fun aBodyThatIsNotACapabilityDocumentIsNotOne() {
        // An access gateway's HTML sign-in page is the common case, and a proxy
        // answering `{}` is the quiet one. Both must read as "no answer" rather
        // than as a document with a blank issuer.
        assertNull(Capabilities.parse("<html>Sign in to continue</html>"))
        assertNull(Capabilities.parse("{}"))
        assertNull(Capabilities.parse(""))
    }

    @Test
    fun aDocumentWithNoIssuerParsesButOffersNothing() {
        // A server that has the endpoint but states no realm. Distinct from an
        // unparseable body: the document is real, it just cannot help.
        val caps = Capabilities.parse("""{"schema":1,"features":[]}""")
        assertEquals(1, caps?.schema)
        assertEquals("", caps?.idpIssuer)
    }

    @Test
    fun onlyAnHttpsIssuerIsAcceptable() {
        assertTrue(Capabilities.acceptable("https://idp.example/realms/detour"))
        assertFalse(Capabilities.acceptable("http://idp.example/realms/detour"))
        assertFalse(Capabilities.acceptable(""))
        assertFalse(Capabilities.acceptable("ftp://idp.example/realms/detour"))
    }

    @Test
    fun loopbackOverPlainHttpStaysAcceptableForTheDevStack() {
        // BuildDefaults.idpIssuer documents http://localhost:7580/realms/detour
        // as the dev value, and OAuth guidance carves out loopback for native
        // clients. Any port, because a dev stack picks its own.
        assertTrue(Capabilities.acceptable("http://localhost:7580/realms/detour"))
        assertTrue(Capabilities.acceptable("http://127.0.0.1:8080/realms/detour"))
        // Not a carve-out for anything that merely mentions localhost.
        assertFalse(Capabilities.acceptable("http://localhost.evil.example/realms/detour"))
    }

    @Test
    fun aFreshlyFetchedIssuerBeatsTheStoredOne() {
        assertEquals(
            "https://new.example/realms/detour",
            Capabilities.preferredDiscovered(
                fetched = "https://new.example/realms/detour",
                stored = "https://old.example/realms/detour",
            ),
        )
    }

    @Test
    fun theStoredIssuerCarriesTheProbeThatFailed() {
        // This is what keeps a signed-in rider working offline: the fetch
        // returned nothing, and the value from the last successful probe is
        // still the right answer.
        assertEquals(
            "https://old.example/realms/detour",
            Capabilities.preferredDiscovered(fetched = "", stored = "https://old.example/realms/detour"),
        )
    }

    @Test
    fun anUnacceptableFetchedIssuerDoesNotDisplaceAGoodStoredOne() {
        // A server that starts answering with a plain-HTTP realm must not be
        // able to downgrade a rider who already had an HTTPS one.
        assertEquals(
            "https://old.example/realms/detour",
            Capabilities.preferredDiscovered(
                fetched = "http://idp.example/realms/detour",
                stored = "https://old.example/realms/detour",
            ),
        )
    }

    @Test
    fun nothingFetchedAndNothingStoredIsBlank() {
        assertEquals("", Capabilities.preferredDiscovered(fetched = "", stored = ""))
    }
}

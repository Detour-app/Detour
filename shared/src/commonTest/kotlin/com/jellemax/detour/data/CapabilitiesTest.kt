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
    fun aZeroSchemaIsNotACapabilityDocumentEither() {
        // optInt's own default for "absent", so a document that spells out
        // schema: 0 must be refused exactly like one with no schema field at
        // all — it cannot be told apart from "not a capability document".
        assertNull(Capabilities.parse("""{"schema":0,"features":[]}"""))
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
    fun onlyHttpsIsAcceptableForARemoteRealm() {
        assertTrue(Capabilities.acceptable("https://idp.example/realms/detour"))
        assertFalse(Capabilities.acceptable("http://idp.example/realms/detour"))
        assertFalse(Capabilities.acceptable(""))
        assertFalse(Capabilities.acceptable("ftp://idp.example/realms/detour"))
    }

    @Test
    fun loopbackOverPlainHttpStaysAcceptableForTheDevStack() {
        // BuildDefaults.idpIssuer documents http://localhost:7580/realms/detour
        // as the dev value. The carve-out is for loopback traffic never
        // leaving the device, not an OAuth native-client allowance — RFC 8252
        // §7.3's loopback carve-out is for a client's own redirect URI, and
        // §8.6 in fact requires TLS on the authorization server's endpoints.
        // Any port, because a dev stack picks its own.
        assertTrue(Capabilities.acceptable("http://localhost:7580/realms/detour"))
        assertTrue(Capabilities.acceptable("http://127.0.0.1:8080/realms/detour"))
        // Not a carve-out for anything that merely mentions localhost.
        assertFalse(Capabilities.acceptable("http://localhost.evil.example/realms/detour"))
        // Same trap the other way round: a suffix match would let a hostile
        // domain masquerade as loopback by embedding it as a subdomain label.
        assertFalse(Capabilities.acceptable("http://127.0.0.1.evil.example/realms/detour"))
    }

    @Test
    fun userinfoIsRefusedOnBothSchemes() {
        // `localhost:8080@evil.example` has userinfo `localhost:8080` and
        // host `evil.example` — browsers, OkHttp and ktor all resolve it that
        // way. Truncating at the first colon (the naive approach) reads the
        // userinfo as the hostname, which turns the loopback host check into
        // a host *prefix* check and accepts any host at all over cleartext.
        // No OIDC issuer identifier carries userinfo, so the shape is refused
        // outright rather than parsed correctly.
        assertFalse(Capabilities.acceptable("http://localhost:@evil.example/realms/detour"))
        assertFalse(Capabilities.acceptable("http://localhost:8080@evil.example/realms/detour"))
        assertFalse(Capabilities.acceptable("http://127.0.0.1:8080@evil.example/realms/detour"))
        assertFalse(Capabilities.acceptable("https://user:pw@evil.example/realms/detour"))
    }

    @Test
    fun aSchemeWithNoHostIsRefused() {
        // A prefix check on the scheme alone would accept a bare "https://",
        // which reaches Auth.endpoint() as "https:///protocol/..." and fails
        // as a malformed URL rather than as "no realm advertised" — the wrong
        // failure mode for what is really a missing value.
        assertFalse(Capabilities.acceptable("https://"))
        assertFalse(Capabilities.acceptable("http://"))
    }

    @Test
    fun controlCharactersInTheAuthorityAreRefused() {
        // trim() only strips the ends; a header-injection payload sitting in
        // the middle of the string survives it. This value is later used to
        // build request URLs, so interior control characters must be caught
        // here rather than trusted downstream.
        assertFalse(Capabilities.acceptable("https://idp.example\r\nX-Injected: 1"))
    }

    @Test
    fun uppercaseSchemesAreRefused() {
        // Deliberate fail-closed, not an oversight: a realm whose issuer is
        // spelled with an uppercase scheme already fails the backend's own
        // exact `iss` comparison, so accepting it here would buy nothing.
        assertFalse(Capabilities.acceptable("HTTPS://idp.example/realms/detour"))
        assertFalse(Capabilities.acceptable("HtTp://localhost:7580/realms/detour"))
    }

    @Test
    fun theNarrowLoopbackSetIsDeliberate() {
        // These are all genuinely loopback addresses, and all refused on
        // purpose — the match is exactly "localhost" or "127.0.0.1", nothing
        // wider. Do not "fix" this by loosening it to cover the rest of the
        // loopback range; that widening needs its own justification.
        assertFalse(Capabilities.acceptable("http://[::1]:7580/realms/detour"))
        assertFalse(Capabilities.acceptable("http://127.1/realms/detour"))
        assertFalse(Capabilities.acceptable("http://127.0.0.2/realms/detour"))
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
    fun anUnacceptableStoredIssuerIsNeverReturnedEither() {
        // The store is only ever written with a value that passed acceptable()
        // today, but nothing re-vets it on the Auth.refresh() path and it
        // survives until the API address changes — so a value written under
        // looser rules must not outlive the tightening just because it is
        // sitting there unfetched.
        assertEquals(
            "",
            Capabilities.preferredDiscovered(fetched = "", stored = "http://evil.example/realms/detour"),
        )
    }

    @Test
    fun nothingFetchedAndNothingStoredIsBlank() {
        assertEquals("", Capabilities.preferredDiscovered(fetched = "", stored = ""))
    }
}

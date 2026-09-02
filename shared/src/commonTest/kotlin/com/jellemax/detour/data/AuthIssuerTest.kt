package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import okio.ByteString.Companion.encodeUtf8

/**
 * Reading the `iss` a realm signed into an ID token.
 *
 * This is the client half of ASVS 5.0.0 V10.2.2. It matters more now than it
 * did: the issuer used to be typed by a person, and is now stated by the API
 * server, so "the token came back from the realm we asked" stops being true by
 * construction and has to be checked.
 *
 * Only the extraction is covered here. The comparison in `exchangeCode` reads
 * the pinned issuer through `RoutingServer.loadCustom()`, which reaches `prefs`
 * and therefore a Context no unit test has — the same limit `OidcTest` records.
 */
class AuthIssuerTest {

    /** A JWT is header.payload.signature, each base64url and unpadded. */
    private fun jwt(payload: String): String {
        val part = payload.encodeUtf8().base64Url().trimEnd('=')
        val header = """{"alg":"RS256","typ":"JWT"}""".encodeUtf8().base64Url().trimEnd('=')
        return "$header.$part.signature-not-verified-here"
    }

    private fun tokenResponse(idToken: String) =
        """{"access_token":"a.b.c","refresh_token":"r","expires_in":300,"id_token":"$idToken"}"""

    @Test
    fun theIssuerIsReadOutOfTheIdToken() {
        val response = tokenResponse(
            jwt("""{"iss":"https://idp.example/realms/detour","sub":"abc"}""")
        )
        assertEquals("https://idp.example/realms/detour", Auth.idTokenIssuer(response))
    }

    @Test
    fun aResponseWithNoIdTokenYieldsBlank() {
        // Blank is refused by the caller rather than treated as a match. OIDC
        // core requires an ID token whenever the `openid` scope was requested,
        // and Oidc.begin always requests it — so a response without one is a
        // realm not doing what was asked, not an older realm being tolerant of.
        assertEquals("", Auth.idTokenIssuer("""{"access_token":"a.b.c","expires_in":300}"""))
    }

    @Test
    fun aMalformedIdTokenYieldsBlankRatherThanThrowing() {
        // Reached at the very end of a sign-in, where a throw is the one
        // failure a rider cannot retry past. Every shape below has to come back
        // as a value.
        assertEquals("", Auth.idTokenIssuer(tokenResponse("not-a-jwt")))
        assertEquals("", Auth.idTokenIssuer(tokenResponse("only.two")))
        assertEquals("", Auth.idTokenIssuer(tokenResponse("a.!!!not-base64!!!.c")))
        assertEquals("", Auth.idTokenIssuer("<html>gateway</html>"))
        assertEquals("", Auth.idTokenIssuer(""))
    }

    @Test
    fun anIdTokenCarryingNoIssuerYieldsBlank() {
        assertEquals("", Auth.idTokenIssuer(tokenResponse(jwt("""{"sub":"abc"}"""))))
    }

    @Test
    fun theIssuerIsComparedWithoutATrailingSlash() {
        // Capabilities.parse and RoutingServer.pick both strip one, so the
        // pinned value never has it. A realm that puts one in its `iss` would
        // otherwise fail a comparison that is actually a match.
        val response = tokenResponse(
            jwt("""{"iss":"https://idp.example/realms/detour/","sub":"abc"}""")
        )
        assertEquals("https://idp.example/realms/detour", Auth.idTokenIssuer(response))
    }
}

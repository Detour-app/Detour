package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers what a failed token request tells the rider, in Auth.kt.
 *
 * The token endpoint is the one leg of sign-in with no user interface of its
 * own: the browser has already closed by the time it runs, so whatever this
 * produces is the entire explanation anyone gets. Before this existed the body
 * was thrown away and `HttpStatusException` carried only "HTTP 401", which is
 * why a realm answering `invalid_client` looked identical to one that was
 * simply unreachable.
 *
 * Two rules the assertions below are really about. An OAuth error response is
 * JSON with `error` and `error_description` (RFC 6749 §5.2) and is safe to
 * repeat back — it is the realm describing its own refusal. Anything else is
 * not: a gateway's HTML sign-in page is the common case, and echoing it into a
 * snackbar would put an unbounded chunk of somebody else's markup on screen.
 * So a recognised body is quoted and an unrecognised one is only classified
 * (ASVS 5.0.0 V16.5.1).
 */
class TokenFailureTest {

    @Test
    fun theRealmsOwnDescriptionIsWhatTheRiderSees() {
        val message = Auth.tokenFailureMessage(
            code = 400,
            body = """{"error":"invalid_grant","error_description":"Code not valid"}""",
        )
        assertEquals("Code not valid (invalid_grant)", message)
    }

    /**
     * Keycloak answers a confidential client with no secret exactly this way,
     * and it is the failure mode that looks most like success: the browser leg
     * completes, the rider authenticates, and only the token call is refused.
     */
    @Test
    fun aBareErrorCodeIsTranslatedRatherThanEchoed() {
        val message = Auth.tokenFailureMessage(401, """{"error":"invalid_client"}""")
        assertTrue("invalid_client" in message, message)
        assertTrue("secret" in message, message)
    }

    @Test
    fun anUnknownErrorCodeStillReachesTheRider() {
        val message = Auth.tokenFailureMessage(400, """{"error":"some_new_thing"}""")
        assertEquals("The identity provider refused the sign-in (some_new_thing)", message)
    }

    /**
     * The Cloudflare Access case. The app sends its service token to the API,
     * the router and the geocoder but not to the realm, so a realm behind
     * Access answers the token call with an interstitial page while the
     * browser leg sails through on its own Access cookie.
     */
    @Test
    fun anHtmlBodyIsClassifiedAndNotQuoted() {
        val page = "<!DOCTYPE html><html><head><title>Sign in</title></head>" +
            "<body>Cloudflare Access</body></html>"
        val message = Auth.tokenFailureMessage(403, page)
        assertTrue("HTTP 403" in message, message)
        assertTrue("not JSON" in message, message)
        assertFalseContains(message, "DOCTYPE")
        assertFalseContains(message, "Cloudflare")
    }

    @Test
    fun anEmptyBodyStillNamesTheStatus() {
        assertEquals(
            "The identity provider returned HTTP 502 with no body",
            Auth.tokenFailureMessage(502, ""),
        )
    }

    /** A 200 that is not a token response is a different failure, not this one. */
    @Test
    fun aTruncatedJsonBodyIsTreatedAsNotJson() {
        val message = Auth.tokenFailureMessage(400, """{"error":""")
        assertTrue("not JSON" in message, message)
    }

    private fun assertFalseContains(haystack: String, needle: String) =
        assertTrue(needle !in haystack, "expected \"$needle\" not to appear in: $haystack")
}

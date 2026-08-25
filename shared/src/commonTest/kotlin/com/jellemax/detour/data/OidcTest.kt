package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the authorization-code flow's shared half in Oidc.kt: the authorize
 * URL, the PKCE challenge, and every way a callback can be refused.
 *
 * Worth testing here rather than on a device because all of it is string work
 * with one invariant that cannot be eyeballed — the challenge in the URL has to
 * be the SHA-256 of the verifier the exchange will later present, and a realm
 * refuses the pair with `invalid_grant` long after the mistake was made.
 *
 * Nothing here touches `prefs`: `RoutingServer.loadCustom()` would reach
 * `Platform.android.kt`'s `requireContext()`, which throws in a JVM unit test.
 * That is why the issuer is a parameter on the `internal` overloads.
 */
class OidcTest {

    private val issuer = "https://idp.example/realms/detour"

    /** Distinguishable, deterministic entropy: byte i = i, so the verifier and
     *  the state are different slices of one predictable block and a test can
     *  say which half a value came from. */
    private fun entropy(seed: Int = 0) =
        ByteArray(Oidc.ENTROPY_BYTES) { (it + seed).toByte() }

    /** The query of an authorize URL, as a key/value map. */
    private fun params(url: String): Map<String, String> =
        url.substringAfter('?').split("&").associate {
            val (k, v) = it.split("=", limit = 2)
            k to v.decodeUrl()
        }

    /** Percent-decoding, kept local rather than pulled from Ktor: the test
     *  should not share the encoder it is checking. */
    private fun String.decodeUrl(): String {
        val out = StringBuilder()
        var i = 0
        while (i < length) {
            val c = this[i]
            when {
                c == '+' -> { out.append(' '); i++ }
                c == '%' && i + 2 < length -> {
                    out.append(substring(i + 1, i + 3).toInt(16).toChar()); i += 3
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    @Test
    fun theChallengeMatchesRfc7636sOwnVector() {
        // RFC 7636 appendix B's published pair, asserted against our own
        // encoder. A round-trip through okio here would only prove this file
        // agrees with itself; the vector proves the encoding — SHA-256, URL
        // alphabet, no padding — is the one a realm will recompute.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Oidc.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun theChallengeInTheUrlIsTheOneTheVerifierWillAnswer() {
        // The invariant the whole flow turns on, and the one no device test
        // catches early: a mismatched pair is refused as invalid_grant at the
        // very end of a sign-in that looked like it was working.
        val url = Oidc.begin(entropy(), issuer)
        val state = params(url)["state"]
        val verifier = Oidc.spend("${Auth.REDIRECT_URI}?code=c&state=$state").verifier
        assertEquals(Oidc.challengeFor(verifier), params(url)["code_challenge"])
    }

    @Test
    fun theAuthorizeUrlCarriesEverythingTheRealmRequires() {
        val url = Oidc.begin(entropy(), issuer)
        assertTrue(url.startsWith("$issuer/protocol/openid-connect/auth?"), url)
        val p = params(url)
        assertEquals(Auth.CLIENT_ID, p["client_id"])
        assertEquals("code", p["response_type"])
        assertEquals("openid profile email", p["scope"])
        assertEquals(Auth.REDIRECT_URI, p["redirect_uri"])
        assertEquals("S256", p["code_challenge_method"])
        assertTrue(p["state"].orEmpty().isNotBlank())
    }

    @Test
    fun theVerifierIsInsideTheLengthRfc7636Allows() {
        val url = Oidc.begin(entropy(), issuer)
        val verifier = Oidc.spend("${Auth.REDIRECT_URI}?code=c&state=${params(url)["state"]}").verifier
        assertTrue(verifier.length in 43..128, "verifier was ${verifier.length} chars")
        // No padding, and nothing outside the URL-safe alphabet: a '+' or '/'
        // reaches the realm percent-encoded and comes back as a different string.
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' }, verifier)
    }

    @Test
    fun twoSignInsFromDifferentEntropyDoNotShareAState() {
        val first = params(Oidc.begin(entropy(seed = 0), issuer))["state"]
        val second = params(Oidc.begin(entropy(seed = 7), issuer))["state"]
        assertTrue(first != second, "state was reused: $first")
    }

    @Test
    fun beginReturnsBlankWhenNoRealmIsConfigured() {
        // Blank rather than a throw: begin() is not suspend, and a throw out of
        // a non-suspend exported function kills the Swift process.
        assertEquals("", Oidc.begin(entropy(), ""))
    }

    @Test
    fun beginReturnsBlankWhenHandedTooLittleEntropy() {
        assertEquals("", Oidc.begin(ByteArray(Oidc.ENTROPY_BYTES - 1), issuer))
    }

    @Test
    fun beginRefusingLeavesNoSignInParked() {
        Oidc.begin(entropy(), issuer)
        assertEquals("", Oidc.begin(ByteArray(0), issuer))
        // The earlier sign-in's secrets must not survive a later refusal, or a
        // stale callback would still be spendable.
        val failure = assertFailsWith<AuthException> {
            Oidc.spend("${Auth.REDIRECT_URI}?code=c&state=whatever")
        }
        assertTrue(failure.message!!.contains("app restarted"), failure.message!!)
    }

    @Test
    fun aCallbackWhoseStateIsNotTheOneSentIsRefused() {
        Oidc.begin(entropy(), issuer)
        val failure = assertFailsWith<AuthException> {
            Oidc.spend("${Auth.REDIRECT_URI}?code=abc&state=not-the-one-we-sent")
        }
        assertEquals("Sign-in could not be verified — start again", failure.message)
    }

    @Test
    fun aCallbackWithNoCodeIsRefused() {
        val url = Oidc.begin(entropy(), issuer)
        val failure = assertFailsWith<AuthException> {
            Oidc.spend("${Auth.REDIRECT_URI}?state=${params(url)["state"]}")
        }
        assertEquals("The identity provider returned no code", failure.message)
    }

    @Test
    fun aCallbackCarryingAnErrorParamReportsTheRealmsDescription() {
        Oidc.begin(entropy(), issuer)
        val failure = assertFailsWith<AuthException> {
            Oidc.spend(
                "${Auth.REDIRECT_URI}?error=invalid_scope" +
                    "&error_description=Client%20not%20allowed%20openid"
            )
        }
        // Percent-encoded on the wire, because that is how a realm sends a
        // sentence; a reader must not see "Client%20not%20allowed".
        assertEquals("Client not allowed openid (invalid_scope)", failure.message)
    }

    @Test
    fun aCallbackCarryingABareErrorCodeStillNamesIt() {
        Oidc.begin(entropy(), issuer)
        val failure = assertFailsWith<AuthException> {
            Oidc.spend("${Auth.REDIRECT_URI}?error=access_denied")
        }
        assertEquals("The realm refused the sign-in (access_denied)", failure.message)
    }

    @Test
    fun anErrorIsReportedEvenWhenNoSignInIsParked() {
        // The realm's own refusal is the more useful message of the two, so it
        // is checked before "nothing is in flight" — a process that restarted
        // AND was refused should say why the realm said no.
        Oidc.abandon()
        val failure = assertFailsWith<AuthException> {
            Oidc.spend("${Auth.REDIRECT_URI}?error=access_denied")
        }
        assertEquals("The realm refused the sign-in (access_denied)", failure.message)
    }

    @Test
    fun spendingTheSameCallbackTwiceFailsTheSecondTime() {
        val url = Oidc.begin(entropy(), issuer)
        val callback = "${Auth.REDIRECT_URI}?code=abc&state=${params(url)["state"]}"
        assertEquals("abc", Oidc.spend(callback).code)
        val failure = assertFailsWith<AuthException> { Oidc.spend(callback) }
        assertTrue(failure.message!!.contains("app restarted"), failure.message!!)
    }

    @Test
    fun abandonMakesAParkedSignInUnspendable() {
        val url = Oidc.begin(entropy(), issuer)
        val state = params(url)["state"]
        Oidc.abandon()
        val failure = assertFailsWith<AuthException> {
            Oidc.spend("${Auth.REDIRECT_URI}?code=abc&state=$state")
        }
        assertTrue(failure.message!!.contains("app restarted"), failure.message!!)
    }

    @Test
    fun isCallbackAcceptsTheRedirectAndRejectsAUrlThatMerelyStartsLikeIt() {
        assertTrue(Oidc.isCallback("${Auth.REDIRECT_URI}?code=abc&state=xyz"))
        assertTrue(Oidc.isCallback(Auth.REDIRECT_URI))
        // The old startsWith check accepted this. A different path is a
        // different link — the reset deep link shares this scheme.
        assertFalse(Oidc.isCallback("${Auth.REDIRECT_URI}x?code=abc"))
        assertFalse(Oidc.isCallback("detour://reset?token=abc"))
        assertFalse(Oidc.isCallback("https://example.com/auth/callback?code=abc"))
    }
}

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
        // params() above decodes '+' back to a space by construction, so it
        // cannot tell '+' from '%20' from nothing at all — pin the raw,
        // undecoded query too, or an encoder change that broke the wire
        // format (Keycloak's QueryStringDecoder maps '+' to a space; a plain
        // space or an unencoded literal would not survive the trip) would
        // pass this test while failing every real sign-in.
        assertTrue(url.contains("scope=openid+profile+email"), url)
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
    fun aCallbackWithATrailingFragmentStillYieldsTheBareCode() {
        // Ktor's parseQueryString only knows '&' and '=', never '#'. Put
        // `code` last, right before the fragment, so an unstripped fragment
        // would corrupt it into "abc#fragment-junk" — reproducing
        // detour://auth/callback?code=abc#x from the Android original, which
        // Uri.getQueryParameter handled and this must too.
        val url = Oidc.begin(entropy(), issuer)
        val state = params(url)["state"]
        val spent = Oidc.spend("${Auth.REDIRECT_URI}?state=$state&code=abc#fragment-junk")
        assertEquals("abc", spent.code)
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
        // The error branch sits after the state check now (a hostile deep
        // link must not be able to show its own text before it has proven
        // it's ours), so a genuine realm error has to carry the real state
        // to reach it — same as any other genuine callback.
        val url = Oidc.begin(entropy(), issuer)
        val state = params(url)["state"]
        val failure = assertFailsWith<AuthException> {
            Oidc.spend(
                "${Auth.REDIRECT_URI}?error=invalid_scope" +
                    "&error_description=Client%20not%20allowed%20openid&state=$state"
            )
        }
        // Percent-encoded on the wire, because that is how a realm sends a
        // sentence; a reader must not see "Client%20not%20allowed".
        assertEquals("Client not allowed openid (invalid_scope)", failure.message)
    }

    @Test
    fun aCallbackCarryingABareErrorCodeStillNamesIt() {
        val url = Oidc.begin(entropy(), issuer)
        val state = params(url)["state"]
        val failure = assertFailsWith<AuthException> {
            Oidc.spend("${Auth.REDIRECT_URI}?error=access_denied&state=$state")
        }
        assertEquals("The realm refused the sign-in (access_denied)", failure.message)
    }

    @Test
    fun anUnsolicitedErrorWithNothingParkedDoesNotRepeatItsDescription() {
        // Any app on the device can fire this deep link. With nothing parked
        // there is no state to match against, so this must fail as "app
        // restarted" — not repeat the caller-supplied error_description,
        // which would put arbitrary attacker text on screen.
        Oidc.abandon()
        val failure = assertFailsWith<AuthException> {
            Oidc.spend(
                "${Auth.REDIRECT_URI}?error=access_denied" +
                    "&error_description=Your+account+was+suspended,+call+555-0100"
            )
        }
        assertTrue(failure.message!!.contains("app restarted"), failure.message!!)
        assertFalse(failure.message!!.contains("suspended"), failure.message!!)
    }

    @Test
    fun aHostileErrorCallbackDoesNotConsumeTheParkedSignIn() {
        // The denial of service this closes: a forged callback racing the
        // real one used to discard the parked verifier/state unconditionally
        // (spend() called abandon() as its third statement, before checking
        // anything), so the genuine callback arriving afterwards would fail
        // as "app restarted" even though it was never spent.
        val url = Oidc.begin(entropy(), issuer)
        val state = params(url)["state"]

        val hostile = assertFailsWith<AuthException> {
            Oidc.spend(
                "${Auth.REDIRECT_URI}?error=access_denied" +
                    "&error_description=Your+account+was+suspended&state=not-the-one-we-sent"
            )
        }
        assertEquals("Sign-in could not be verified — start again", hostile.message)

        // The genuine callback must still be spendable — the hostile one
        // above must not have abandoned it.
        assertEquals("abc", Oidc.spend("${Auth.REDIRECT_URI}?code=abc&state=$state").code)
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
        // A longer path is a different link, not a match with trailing junk.
        // This matters most on iOS: Android's manifest filters
        // detour://auth/callback with an exact android:path="/callback", so
        // "…callbackx" is never even delivered there, but nothing narrows
        // incoming links ahead of this check on iOS.
        assertFalse(Oidc.isCallback("${Auth.REDIRECT_URI}x?code=abc"))
        // A different path is a different link, whatever scheme it shares.
        // (detour://reset was the old password-reset link's scheme; the
        // realm owns reset now and no such deep link is registered any more
        // — this just stands in for "some other detour:// URL".)
        assertFalse(Oidc.isCallback("detour://reset?token=abc"))
        assertFalse(Oidc.isCallback("https://example.com/auth/callback?code=abc"))
    }
}

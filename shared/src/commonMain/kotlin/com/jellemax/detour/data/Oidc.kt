package com.jellemax.detour.data

import io.ktor.http.formUrlEncode
import io.ktor.http.parametersOf
import io.ktor.http.parseQueryString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

/**
 * The authorization-code flow with PKCE, minus the browser.
 *
 * Signing in is a trip out to the realm's own page and back, and only the two
 * ends of that trip are platform-shaped: opening a browser, and drawing bytes
 * from a CSPRNG. Everything between them is string work over a wire format —
 * so it lives here, once, where a test can reach it. The platform halves are
 * `app/auth/AuthBrowser.kt` and `iosApp/Detour/SignIn.swift`, and both are
 * about forty lines.
 *
 * The invariant this exists to protect: the `code_challenge` sent to the
 * realm has to be the SHA-256 of the verifier [complete] later presents. Get
 * that pair wrong and the realm answers `invalid_grant` at the very end of a
 * flow that looked like it was working — which is exactly the kind of mistake
 * a unit test catches and a device test does not.
 *
 * Entropy is pushed in rather than reached for ([begin] takes it as an
 * argument) because `Platform.kt` expects three things and a CSPRNG is not one
 * of them. See `CONTRIBUTING.md` and `Platform.kt:11-14`: when the core wants a
 * fourth platform capability, the answer is to be handed the value instead.
 */
object Oidc {

    /** 64 bytes → an 86-character verifier, inside RFC 7636 §4.1's 43..128. */
    private const val VERIFIER_BYTES = 64

    /** Only has to be unguessable, not long: it is compared, never decoded. */
    private const val STATE_BYTES = 16

    /** How many random bytes a platform must hand [begin]. */
    const val ENTROPY_BYTES = VERIFIER_BYTES + STATE_BYTES

    /**
     * The sign-in currently out in the browser, in memory only.
     *
     * Not persisted, deliberately: a sign-in that does not survive the process
     * is a sign-in to start again, which is cheaper than writing a secret to
     * disk to smooth an edge case — and ASVS 5.0.0 V10.1.2 wants the verifier
     * bound to the transaction and the user agent that began it.
     */
    private var pendingVerifier: String? = null
    private var pendingState: String? = null

    /** Whether signing in is possible at all — false when no realm is
     *  configured, which is how a build shipping no baked issuer behaves until
     *  the rider sets one under Settings. */
    val configured: Boolean get() = issuer().isNotBlank()

    /** Resolved rather than read off [BuildDefaults]: a store build ships no
     *  baked issuer, so the saved one is the only one there will ever be. */
    private fun issuer(): String = RoutingServer.issuer(RoutingServer.loadCustom())

    /**
     * Parks a fresh verifier and state and returns the realm's authorize URL,
     * or `""` when there is no realm configured or [entropy] is shorter than
     * [ENTROPY_BYTES].
     *
     * Blank rather than an exception because this is not a `suspend` function:
     * Kotlin/Native turns a throw out of one of those into a terminated
     * process on the Swift side, not something `catch` can see. The two
     * callers both already have a "cannot sign in" path to fall into.
     */
    fun begin(entropy: ByteArray): String = begin(entropy, issuer())

    /** `internal` so a test can supply an issuer without going near `prefs` —
     *  `RoutingServer.loadCustom()` reaches a Context that does not exist in a
     *  unit test. Same reason [Auth.tokenFailureMessage] is internal. */
    internal fun begin(entropy: ByteArray, issuer: String): String {
        if (issuer.isBlank() || entropy.size < ENTROPY_BYTES) {
            // A refused start must not leave the previous attempt's secrets
            // parked, or a stale callback stays spendable.
            abandon()
            return ""
        }

        val verifier = urlSafe(entropy.copyOfRange(0, VERIFIER_BYTES))
        val state = urlSafe(entropy.copyOfRange(VERIFIER_BYTES, ENTROPY_BYTES))
        pendingVerifier = verifier
        pendingState = state

        val query = parametersOf(
            mapOf(
                "client_id" to listOf(Auth.CLIENT_ID),
                "response_type" to listOf("code"),
                "scope" to listOf("openid profile email"),
                "redirect_uri" to listOf(Auth.REDIRECT_URI),
                "state" to listOf(state),
                "code_challenge" to listOf(challengeFor(verifier)),
                "code_challenge_method" to listOf("S256"),
            )
        ).formUrlEncode()

        return "$issuer/protocol/openid-connect/auth?$query"
    }

    /**
     * Whether [url] is the redirect this flow is waiting for.
     *
     * Matched as the whole redirect URI, optionally followed by a query — not
     * as a prefix. `detour://` is also the scheme the legacy reset link used,
     * so "starts with" would claim links that are not ours.
     */
    fun isCallback(url: String): Boolean =
        url == Auth.REDIRECT_URI || url.startsWith("${Auth.REDIRECT_URI}?")

    /**
     * Finishes the flow: verifies the callback, then exchanges the code.
     *
     * Throws [AuthException] on anything that is not a completed sign-in — the
     * realm reporting an error, a state that does not match the request this
     * process started, a missing code, or the exchange itself being refused —
     * so a caller shows one message either way.
     */
    suspend fun complete(url: String) {
        val spent = spend(url)
        Auth.exchangeCode(spent.code, spent.verifier)
    }

    /** Forgets a sign-in that will not be finished: the browser never opened,
     *  or the rider dismissed it. Idempotent. */
    fun abandon() {
        pendingVerifier = null
        pendingState = null
    }

    /** The authorization code and the verifier that has to accompany it. */
    internal data class SpentCallback(val code: String, val verifier: String)

    /**
     * Reads a callback, consuming the parked sign-in whatever the outcome — a
     * code is single-use, and so is the verifier that unlocks it.
     *
     * `internal` because this is the decision half of [complete] and the half
     * worth asserting: [complete]'s other half is a network call.
     */
    internal fun spend(url: String): SpentCallback {
        val verifier = pendingVerifier
        val expectedState = pendingState
        abandon()

        val params = parseQueryString(url.substringAfter('?', ""))

        // Before the "is anything in flight" check: a realm that says why it
        // refused is more use to the reader than this side saying it lost track.
        params["error"]?.let { error ->
            // The bare code is what a realm's own logs and docs call this, so
            // it is worth keeping even when a description is present.
            val described = params["error_description"]
            throw AuthException(
                if (described.isNullOrBlank()) "The realm refused the sign-in ($error)"
                else "$described ($error)"
            )
        }
        if (verifier == null || expectedState == null) {
            // Not "nothing is in progress" — something plainly is, the rider
            // just came back from it. The verifier is held in memory on purpose
            // (see above), so the honest reading of its absence is that this
            // process is not the one that started the sign-in. Android
            // restarting the app behind the browser is by far the likeliest way
            // that happens, and the old wording sent people looking for a
            // broken realm instead.
            throw AuthException(
                "The app restarted while the browser was open, so this sign-in " +
                    "could not be finished. Tap Sign in to start again."
            )
        }
        // A callback whose state is not the one we sent did not come from the
        // request we made, so the code in it is not ours to spend.
        if (params["state"] != expectedState) {
            throw AuthException("Sign-in could not be verified — start again")
        }
        val code = params["code"]
            ?: throw AuthException("The identity provider returned no code")

        return SpentCallback(code, verifier)
    }

    /**
     * okio rather than a platform base64: this runs on Kotlin/Native too, and
     * RFC 7636 §4.2 forbids the padding okio emits.
     *
     * `internal` so the test can assert it against RFC 7636's published vector
     * directly. Round-tripping it through okio in the test would only prove the
     * test agrees with itself.
     */
    internal fun challengeFor(verifier: String): String =
        verifier.encodeUtf8().sha256().base64Url().trimEnd('=')

    private fun urlSafe(raw: ByteArray): String =
        raw.toByteString().base64Url().trimEnd('=')
}

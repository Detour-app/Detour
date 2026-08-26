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
 * `app/auth/AuthBrowser.kt` and `iosApp/Detour/SignIn.swift`.
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
     *
     * Plain `var`s, not behind a lock: correctness depends on [begin],
     * [spend] and [abandon] all being called from a single thread.
     * `commonMain` has no concurrency primitives to enforce that here, so
     * this is a real constraint on every caller, not an accident of the two
     * that exist today — Compose's `onClick` plus `lifecycleScope`
     * (`Main.immediate`) on Android, `@MainActor` throughout on iOS. A future
     * caller off that thread — a background coroutine, a new platform — would
     * race this state rather than fail loudly, so treat "single-threaded
     * access only" as part of this object's contract.
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
     * as a prefix. On Android this is a second line of defence: the
     * manifest's exact `android:path="/callback"` intent filter already keeps
     * a URL like `detour://auth/callbackx` from reaching the app. On iOS
     * there is nothing upstream of this at all — every `detour://` link the
     * OS hands the app arrives here unfiltered — so on that platform this
     * check is the only thing narrowing "our scheme" down to "our redirect".
     */
    fun isCallback(url: String): Boolean =
        url == Auth.REDIRECT_URI || url.startsWith("${Auth.REDIRECT_URI}?")

    /**
     * Finishes the flow: verifies the callback, then exchanges the code.
     *
     * Throws [AuthException] on anything that is not a completed sign-in, in
     * the order [spend] checks them — no sign-in in flight, a state that does
     * not match the request this process started, the realm reporting an
     * error, a missing code — or the exchange itself being refused. A caller
     * shows one message either way.
     *
     * `@Throws(Exception::class)`: see the doc on [SyncClient.sync] for why
     * `Exception` and not just [okio.IOException] — the same reasoning
     * applies here, and matters more here than anywhere else in the app,
     * since an unannotated throw out of this call is the one exception the
     * rider can never even retry past.
     */
    @Throws(Exception::class)
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
     * Reads a callback and, once it is confirmed to be ours, consumes the
     * parked sign-in whatever the outcome — a code is single-use, and so is
     * the verifier that unlocks it.
     *
     * The parked verifier/state are read into locals up front but [abandon]
     * is deliberately *not* called until the state check has passed. Any app
     * on the device can fire `detour://auth/callback` — see `isCallback`'s
     * doc for why nothing upstream of this file rules that out on iOS — so a
     * callback that does not carry this process's own state has to be
     * refused without touching what is parked, or a forged callback could end
     * a sign-in that was never its to end and the genuine callback arriving
     * afterwards would fail as "app restarted" for no reason.
     *
     * `internal` because this is the decision half of [complete] and the half
     * worth asserting: [complete]'s other half is a network call.
     */
    internal fun spend(url: String): SpentCallback {
        val verifier = pendingVerifier
        val expectedState = pendingState

        // Fragment stripped before the scan: parseQueryString only knows '&'
        // and '=', never '#', so `?code=abc#x` would otherwise parse as
        // code == "abc#x" and the exchange would fail invalid_grant. Uri's
        // query parser (the Android original this was extracted from) handled
        // this for free; Keycloak's default response mode never sends a
        // fragment, so this is latent rather than live, but it is a
        // capability loss against what it replaces if left unfixed.
        val params = parseQueryString(url.substringBefore('#').substringAfter('?', ""))

        if (verifier == null || expectedState == null) {
            // Checked before the state comparison below, not after: with
            // nothing parked, expectedState is null too, so comparing states
            // would report every restarted-app callback with this generic
            // "could not be verified" wording instead of this specific one.
            // That specific wording exists on purpose: the phrasing it
            // replaced ("nothing is in progress") sent people looking for a
            // broken realm, when the honest reading is that this process is
            // simply not the one that started the sign-in. This check has to
            // run first to preserve it.
            throw AuthException(
                "The app restarted while the browser was open, so this sign-in " +
                    "could not be finished. Tap Sign in to start again."
            )
        }
        // A callback whose state is not the one we sent did not come from the
        // request we made, so it is refused here, before abandon() runs —
        // deliberately not consuming the parked sign-in below. That is what
        // closes the denial of service: without this, any app on the device
        // could fire a callback with the wrong (or no) state and discard a
        // sign-in genuinely in flight, and the real callback arriving after
        // would find nothing parked.
        if (params["state"] != expectedState) {
            throw AuthException("Sign-in could not be verified — start again")
        }
        // Only now: the callback has proven itself ours by carrying the right
        // state, so it is safe to consume what is parked. A code and a
        // verifier are single-use either way, whatever happens below.
        abandon()

        // Trusted only now that the callback is known to be ours — the
        // realm's own wording is more use to the reader than this side saying
        // it lost track, but showing it before the state check would let an
        // unrelated, hostile deep link put attacker-controlled text on screen.
        params["error"]?.let { error ->
            // The bare code is what a realm's own logs and docs call this, so
            // it is worth keeping even when a description is present.
            val described = params["error_description"]
            throw AuthException(
                if (described.isNullOrBlank()) "The realm refused the sign-in ($error)"
                else "$described ($error)"
            )
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

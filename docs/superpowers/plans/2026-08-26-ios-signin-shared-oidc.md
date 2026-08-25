# iOS sign-in on a shared OIDC core — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the OIDC authorization-code protocol into `shared/commonMain` so iOS can sign in, which unblocks friends, leaderboard, convoys, circles, circle notifications, circle presence sync and trip sync on iOS in one change.

**Architecture:** `commonMain` gets an `Oidc` object owning every string operation and decision — authorize-URL construction, PKCE S256 challenge, in-flight verifier/state parking, callback detection and parsing, state verification. Each platform keeps only what it cannot share: crypto-grade random bytes and a browser. Android's existing `auth/Oidc.kt` shrinks to `auth/AuthBrowser.kt`; iOS gains `SignIn.swift`. No new `expect`, no new interface, no new `FlowWatcher` subclass.

**Tech Stack:** Kotlin Multiplatform (`:shared`), okio (SHA-256 + base64url), `io.ktor.http` (query build/parse), Jetpack Compose + androidx.browser Custom Tabs (Android), SwiftUI + `ASWebAuthenticationSession` (iOS), `kotlin.test` (commonTest).

**Spec:** `docs/superpowers/specs/2026-08-26-ios-signin-shared-oidc-design.md`

## Global Constraints

- **All tooling runs inside the devcontainer.** Never install or build on the host. Gradle commands go through `devcontainer-exec` (on PATH) or `docker exec -u 1000:1000 great_panini …`. The host JDK is 26 and has no Android SDK.
- **`commonMain` may not touch `prefs()`.** `Platform.android.kt`'s `requireContext()` throws `"initSharedCore(context) has not been called"` in a JVM unit test, so **no test may reach `RoutingServer.loadCustom()`**. Every shared function under test takes the issuer as a parameter; the no-argument public overload resolves it. This mirrors `Auth.tokenFailureMessage` being `internal` "because the test for it is the point".
- **`commonMain` has no `Dispatchers`, no logger, and no `java.*`.** Verified by `./gradlew :shared:compileCommonMainKotlinMetadata`, which must pass before the PR.
- **No non-`suspend` shared function may throw across the Kotlin/Native boundary.** A throw out of a non-`suspend` exported function terminates the Swift process rather than raising a catchable error. `begin` therefore returns `""`; only `complete` (which is `suspend`) throws.
- **`const val` inside an object must not be read from Swift.** Its exported spelling depends on the compiler version — see `FlowWatcher.kt`'s `Enums` doc. Anything Swift needs goes on `Enums` in `iosMain`.
- **Exception type is `AuthException`** (`shared/src/commonMain/kotlin/com/jellemax/detour/data/Api.kt:7`), replacing the current Android `IllegalStateException`. Its messages reach Swift as `NSError.localizedDescription`, which `FriendsModel.report` already reads.
- **Never log the callback URL or the authorization code.** `MainActivity` logs `e.message` and the throwable only — ASVS 5.0.0 V16.2.5. Keep it that way.
- **Refusal messages are copied verbatim** from `app/src/main/java/com/jellemax/detour/auth/Oidc.kt`. The "app restarted while the browser was open" wording in particular is deliberate; `auth/Oidc.kt:109-119` records that the earlier phrasing sent people looking for a broken realm.
- **PKCE:** verifier = base64url of 64 random bytes, unpadded, 86 chars (RFC 7636 §4.1 allows 43..128). Challenge = unpadded base64url SHA-256 of the verifier ASCII (RFC 7636 §4.2 — okio emits padding, which must be trimmed).
- **Redirect URI and client id are `Auth.REDIRECT_URI` / `Auth.CLIENT_ID`.** Never re-spell them; they are registered on the realm.
- **`versionName` in `app/build.gradle.kts` gets a minor bump** `1.79.1` → `1.80.0`. `versionCode` is CI-stamped — never touch it.
- **No `Co-Authored-By` or `Claude-Session` trailer on any commit.** Conventional-commits subject lines.
- **Branch:** `feat/ios-signin-shared-oidc`, already created, spec already committed as `1ef9857`.

---

### Task 1: Shared OIDC protocol

The whole protocol, TDD, with no platform code involved. Deliverable: `:shared` compiles for common metadata and its tests pass, on a branch where Android still builds against the old `auth/Oidc.kt` (nothing yet imports the new object).

**Files:**
- Create: `shared/src/commonMain/kotlin/com/jellemax/detour/data/Oidc.kt`
- Create: `shared/src/commonTest/kotlin/com/jellemax/detour/data/OidcTest.kt`
- Modify: `shared/src/iosMain/kotlin/com/jellemax/detour/data/FlowWatcher.kt` (add one line to `Enums`)

**Interfaces:**
- Consumes: `Auth.CLIENT_ID`, `Auth.REDIRECT_URI`, `Auth.exchangeCode(code, verifier)`, `AuthException`, `RoutingServer.issuer(custom)`, `RoutingServer.loadCustom()`.
- Produces, and Tasks 2 and 3 rely on exactly these names:
  - `Oidc.ENTROPY_BYTES: Int` — `80`
  - `Oidc.configured: Boolean`
  - `Oidc.begin(entropy: ByteArray): String` — authorize URL, or `""`
  - `Oidc.isCallback(url: String): Boolean`
  - `suspend Oidc.complete(url: String)` — throws `AuthException`
  - `Oidc.abandon()`
  - `Enums.oidcEntropyBytes: Int` (iosMain only, for Swift)
  - `internal Oidc.begin(entropy: ByteArray, issuer: String): String`, `internal Oidc.spend(url: String): SpentCallback`, `internal Oidc.challengeFor(verifier: String): String` — test seams, not for platform code

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/jellemax/detour/data/OidcTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*OidcTest*'
```

Expected: FAIL at compilation — `Unresolved reference: Oidc`.

- [ ] **Step 3: Write the implementation**

Create `shared/src/commonMain/kotlin/com/jellemax/detour/data/Oidc.kt`:

```kotlin
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
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*OidcTest*'
```

Expected: PASS, 15 tests.

If `parametersOf(mapOf(...))` does not resolve, use the vararg-pair form instead —
`parametersOf("client_id" to listOf(Auth.CLIENT_ID), …)` — which `Auth.kt:262` reaches
through the same import. Do not switch to hand-rolled string concatenation: the `scope`
value contains spaces and must be encoded.

- [ ] **Step 5: Expose the byte count to Swift**

`const val` inside an object crosses to Objective-C under a spelling that depends on the
compiler version, which is why `Enums` exists. Add one line to the `Enums` object in
`shared/src/iosMain/kotlin/com/jellemax/detour/data/FlowWatcher.kt`, after
`cameraPrefetchRadiusMeters`:

```kotlin
    /** How many random bytes `SignIn.swift` must draw for [Oidc.begin]. Named
     *  here for the same reason the rest of this object exists: a `const val`
     *  in an object has no stable exported spelling. */
    val oidcEntropyBytes: Int = Oidc.ENTROPY_BYTES
```

- [ ] **Step 6: Verify the shared module compiles for the common intersection**

```bash
devcontainer-exec ./gradlew :shared:compileCommonMainKotlinMetadata :shared:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`. This is the check that catches `java.*` leaking into
`commonMain`, and it is path-gated in CI so it has to be run here.

- [ ] **Step 7: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/Oidc.kt \
        shared/src/commonTest/kotlin/com/jellemax/detour/data/OidcTest.kt \
        shared/src/iosMain/kotlin/com/jellemax/detour/data/FlowWatcher.kt
git commit -m "feat(shared): move the OIDC authorization-code flow into commonMain

The authorize URL, the PKCE S256 challenge, the parked verifier/state and every
way a callback can be refused are string work over a wire format, so they belong
where a test can reach them rather than in each platform's browser glue.

Entropy is pushed in as a parameter instead of becoming a fourth Platform.kt
expect, and begin() returns \"\" rather than throwing because a throw out of a
non-suspend exported function terminates the Swift process.

Fifteen tests, including RFC 7636's own challenge vector: the challenge sent to
the realm has to be the SHA-256 of the verifier the exchange later presents, and
getting that pair wrong surfaces as invalid_grant at the end of a flow that
looked like it was working."
```

---

### Task 2: Android on the shared protocol

`auth/Oidc.kt` becomes `auth/AuthBrowser.kt` — `SecureRandom` and a Custom Tab, nothing else. Deliverable: the Android app builds, installs, and signs in exactly as before, with the protocol now coming from `:shared`.

**Files:**
- Create: `app/src/main/java/com/jellemax/detour/auth/AuthBrowser.kt`
- Delete: `app/src/main/java/com/jellemax/detour/auth/Oidc.kt`
- Modify: `app/src/main/java/com/jellemax/detour/MainActivity.kt:23,112,116`
- Modify: `app/src/main/java/com/jellemax/detour/ui/FriendsScreen.kt:63,158,174`
- Unchanged: `app/src/main/java/com/jellemax/detour/auth/PendingSignIn.kt`

**Interfaces:**
- Consumes: `Oidc.ENTROPY_BYTES`, `Oidc.begin(entropy)`, `Oidc.isCallback(url)`, `Oidc.complete(url)`, `Oidc.abandon()`, `Oidc.configured` from Task 1.
- Produces: `AuthBrowser.configured: Boolean`, `AuthBrowser.start(context: Context): Boolean`.

- [ ] **Step 1: Create the Android half**

Create `app/src/main/java/com/jellemax/detour/auth/AuthBrowser.kt`:

```kotlin
package com.jellemax.detour.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.jellemax.detour.data.Oidc
import java.security.SecureRandom

/**
 * The browser half of signing in: opens the realm's login page in a Custom Tab.
 *
 * This is all that cannot live in the shared core — a browser, and a CSPRNG.
 * The flow itself (the authorize URL, PKCE, the state check, spending the code)
 * is in [Oidc], shared with iOS; the redirect comes back to MainActivity, which
 * hands the URI straight to it.
 *
 * A WebView deliberately is not a fallback for a missing browser: it would put
 * the realm's login page inside this app's process, where this app could read
 * what is typed into it.
 */
object AuthBrowser {

    /** Whether signing in is possible at all — false when no realm is
     *  configured, which is how a build with no secrets behaves until the rider
     *  sets one under Settings. */
    val configured: Boolean get() = Oidc.configured

    /**
     * Opens the realm's login page. Returns false when there is no realm
     * configured or no browser to open it in, so the caller can say so instead
     * of leaving a button that does nothing.
     */
    fun start(context: Context): Boolean {
        val entropy = ByteArray(Oidc.ENTROPY_BYTES).also { SecureRandom().nextBytes(it) }
        val authorize = Oidc.begin(entropy)
        if (authorize.isBlank()) return false

        return try {
            CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(authorize))
            true
        } catch (e: ActivityNotFoundException) {
            // No browser at all: nothing here can substitute for one. Drop the
            // parked secrets, or the next callback to arrive from some earlier
            // attempt would still look spendable.
            Oidc.abandon()
            false
        }
    }
}
```

- [ ] **Step 2: Delete the old file**

```bash
git rm app/src/main/java/com/jellemax/detour/auth/Oidc.kt
```

- [ ] **Step 3: Point MainActivity at the shared object**

In `app/src/main/java/com/jellemax/detour/MainActivity.kt`, change the import on line 23:

```kotlin
import com.jellemax.detour.data.Oidc
```

and, inside `takeSignInRedirect`, pass strings rather than a `Uri` (lines 112 and 116):

```kotlin
        val data = intent?.data?.toString() ?: return
        if (!Oidc.isCallback(data)) return
        PendingSignIn.begin()
        lifecycleScope.launch {
            try {
                Oidc.complete(data)
```

Everything else in that method — the `PendingSignIn.succeed(Auth.username.value)` call, the
`catch` block, the `Log.w(TAG, …)` line and its ASVS comment — stays exactly as it is. The
comment's claim that `Oidc.complete`'s messages are safe to print still holds: they are the
same strings, now in `Oidc.spend`.

- [ ] **Step 4: Point FriendsScreen at both**

In `app/src/main/java/com/jellemax/detour/ui/FriendsScreen.kt`, replace the import on line 63:

```kotlin
import com.jellemax.detour.auth.AuthBrowser
```

then in `SignInSection`, line 158:

```kotlin
    if (!AuthBrowser.configured) {
```

and line 174:

```kotlin
            if (!AuthBrowser.start(context)) {
```

No other line in that composable changes — the copy, the `PendingSignIn` reads and the busy
spinner are all as they were.

- [ ] **Step 5: Confirm nothing else referenced the old object**

```bash
grep -rn "auth.Oidc\|Oidc\." app/src/main/java/ wear/src/main/java/
```

Expected: hits only in `MainActivity.kt` (`data.Oidc`), `AuthBrowser.kt` (`data.Oidc`), and
no hit anywhere naming `com.jellemax.detour.auth.Oidc`. `wear/` must show nothing — it does
not depend on `:shared`.

- [ ] **Step 6: Build and run the unit tests**

```bash
devcontainer-exec ./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`, and `app/build/outputs/apk/debug/app-debug.apk` written.

- [ ] **Step 7: Verify on a device**

The emulator lives in the devcontainer. Start it and install:

```bash
docker exec -u 1000:1000 great_panini bash -lc \
  'emulator -avd detour-api35 -no-window -no-audio -gpu swiftshader_indirect -no-snapshot &
   adb wait-for-device shell "while [ \"\$(getprop sys.boot_completed)\" != 1 ]; do sleep 2; done"'
devcontainer-exec ./gradlew :app:installDebug
```

Then drive it — the applicationId is **not** the Kotlin package (see the `detour-adb` skill):

```bash
adb shell am start -n io.github.maxke24.detour.debug/com.jellemax.detour.MainActivity
```

Check the two states that do not need a realm, capturing evidence for each with
`.claude/skills/detour-adb/scripts/capture-state.sh "$SCRATCH"/`:

1. **No realm configured.** With nothing saved under Settings → Servers & sync, the Friends
   tab must show "No identity provider is configured, so there is nobody to sign in to." and
   **no** Sign in button. This is `AuthBrowser.configured` returning false through
   `Oidc.configured`.
2. **A realm configured but unreachable.** Save any `https://` URL as the sign-in realm, then
   tap Sign in. A Custom Tab must open (proving `Oidc.begin` produced a URL and the Custom
   Tab launched); the page failing to load is the expected outcome and not a defect.

Never `pm clear` or `adb uninstall` to reach a clean state — see the `detour-adb` skill's
"Never do these". Use a fresh AVD if a wiped install is genuinely needed.

- [ ] **Step 8: Report what was and was not verified**

Write the observed states and the artifact paths into the task report. A full sign-in needs a
reachable Keycloak realm; if one is not available, say so explicitly rather than implying the
round trip was exercised.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/auth/AuthBrowser.kt \
        app/src/main/java/com/jellemax/detour/MainActivity.kt \
        app/src/main/java/com/jellemax/detour/ui/FriendsScreen.kt
git add -u app/src/main/java/com/jellemax/detour/auth/
git commit -m "refactor(auth): reduce the Android OIDC code to a browser and a CSPRNG

auth/Oidc.kt becomes auth/AuthBrowser.kt: the authorize URL, PKCE, the state
check and spending the code now come from shared Oidc, so Android and iOS run
one implementation of the flow instead of one each.

The rename is not cosmetic — com.jellemax.detour.auth.Oidc and
com.jellemax.detour.data.Oidc would collide in every file importing both.

PendingSignIn stays in app/: it exists because Android's redirect lands in
MainActivity rather than in the composition holding the button, which is not a
problem iOS has."
```

---

### Task 3: iOS sign-in

The Swift half, plus the Settings field without which it is unreachable. Deliverable: the iOS app offers a working Sign in button, and everything gated on `Account.signedIn` becomes reachable.

**Files:**
- Create: `iosApp/Detour/SignIn.swift`
- Modify: `iosApp/Detour/FriendsScreen.swift` (replace `SignInForm`, line 149-175)
- Modify: `iosApp/Detour/SettingsScreen.swift` (`serverSection`, and the `@State` comment above `apiURL`)

**Interfaces:**
- Consumes: `Oidc.shared.configured`, `Oidc.shared.begin(entropy:)`, `Oidc.shared.complete(url:)`, `Oidc.shared.abandon()`, `Enums.shared.oidcEntropyBytes` from Task 1.
- Produces: `SignIn` — `@MainActor final class SignIn: NSObject, ObservableObject` with `@Published private(set) var busy: Bool`, `@Published var error: String?`, `var configured: Bool`, `func start() async`.

- [ ] **Step 1: Write the sign-in driver**

Create `iosApp/Detour/SignIn.swift`:

```swift
import AuthenticationServices
import UIKit
import DetourShared

/// The browser half of signing in, which is all iOS has to supply: an
/// `ASWebAuthenticationSession` and 80 bytes from the system CSPRNG. The flow
/// itself — the authorize URL, PKCE, the state check, spending the code — is
/// shared `Oidc`, the same code the Android app runs.
///
/// `ASWebAuthenticationSession` rather than `SFSafariViewController` plus
/// `onOpenURL`: it hands the callback URL back to its caller in-process, so
/// there is no cross-screen state to keep. Android needs that state
/// (`PendingSignIn`) only because its redirect lands in the activity rather
/// than in the view holding the button.
@MainActor
final class SignIn: NSObject, ObservableObject {

    /// True from the moment the sheet is asked for until tokens are stored or
    /// the attempt is refused.
    @Published private(set) var busy = false

    /// The realm's own wording where there is any — shared `Oidc` throws
    /// `AuthException`, which crosses as an `NSError` carrying the message.
    @Published var error: String?

    /// False when no realm is configured, which is how a build shipping no
    /// baked issuer behaves until one is set under Settings.
    var configured: Bool { Oidc.shared.configured }

    /// Held for the life of the attempt: a session that is only a local in
    /// `present(_:)` can be released before it calls back.
    private var session: ASWebAuthenticationSession?

    func start() async {
        error = nil
        busy = true
        defer { busy = false }

        let authorize = Oidc.shared.begin(entropy: entropy())
        guard !authorize.isEmpty, let url = URL(string: authorize) else {
            // begin() returns blank rather than throwing: it is not a suspend
            // function, and a throw out of one of those terminates this process
            // instead of arriving as an error.
            error = "No identity provider is configured, so there is nobody to "
                + "sign in to. Set the sign-in realm under Settings → Own server."
            return
        }

        do {
            let callback = try await present(url)
            try await Oidc.shared.complete(url: callback.absoluteString)
        } catch is SignInDismissed {
            // Not a failure: the rider closed the sheet. Drop the parked
            // verifier so a later stale callback cannot be spent.
            Oidc.shared.abandon()
        } catch {
            Oidc.shared.abandon()
            self.error = (error as NSError).localizedDescription
        }
    }

    /// 80 bytes, the count shared `Oidc` asks for — 64 for the PKCE verifier
    /// and 16 for the state. `SecRandomCopyBytes`, never `Int.random`: both
    /// values have to be unguessable.
    private func entropy() -> KotlinByteArray {
        let count = Int(Enums.shared.oidcEntropyBytes)
        var bytes = [UInt8](repeating: 0, count: count)
        if SecRandomCopyBytes(kSecRandomDefault, count, &bytes) != errSecSuccess {
            // Documented never to fail on iOS. Returning short bytes is the
            // safe outcome anyway: `begin` refuses them and the rider sees
            // "cannot sign in" rather than a guessable verifier.
            return KotlinByteArray(size: 0)
        }
        // Kotlin/Native maps ByteArray to KotlinByteArray, which no Swift
        // Data bridge fills in — hence the copy, once per sign-in.
        let out = KotlinByteArray(size: Int32(count))
        for (index, byte) in bytes.enumerated() {
            out.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return out
    }

    private func present(_ url: URL) async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            // The scheme only, no "://" — and it is already registered in
            // Info.plist, though this API intercepts the redirect itself and
            // does not need it to be.
            let session = ASWebAuthenticationSession(
                url: url,
                callbackURLScheme: "detour"
            ) { callback, failure in
                if let callback {
                    continuation.resume(returning: callback)
                    return
                }
                if (failure as? ASWebAuthenticationSessionError)?.code == .canceledLogin {
                    continuation.resume(throwing: SignInDismissed())
                    return
                }
                continuation.resume(throwing: failure ?? SignInDismissed())
            }
            session.presentationContextProvider = self
            self.session = session
            // A session that will not start never calls back, so resuming here
            // cannot double-resume.
            if !session.start() {
                continuation.resume(throwing: SignInDismissed())
            }
        }
    }
}

/// The rider closed the sheet. Its own type so `start()` can tell a dismissal
/// from a refusal and stay silent about the former.
private struct SignInDismissed: Error {}

extension SignIn: ASWebAuthenticationPresentationContextProviding {
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first { $0.isKeyWindow }
            ?? ASPresentationAnchor()
    }
}
```

- [ ] **Step 2: Replace the placeholder form**

In `iosApp/Detour/FriendsScreen.swift`, replace the whole `SignInForm` struct and the doc
comment above it (currently lines 149-175, the one saying the iOS side "has not been written
yet") with:

```swift
/// Signing in is a trip out to the realm's own page and back — authorization
/// code with PKCE, in an `ASWebAuthenticationSession`. Creating an account,
/// changing a password and recovering one all happen on the realm's pages,
/// which is why none of them is offered here. Same copy as the Android
/// screen's, deliberately: one feature described two ways reads as two.
private struct SignInForm: View {

    @StateObject private var signIn = SignIn()

    var body: some View {
        Form {
            Section {
                Text("""
                    Sign in to sync your rides and compare stats with friends. \
                    Your trips and explored map stay private — friends only ever \
                    see totals and badges.
                    """)
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                if signIn.configured {
                    if let error = signIn.error {
                        Text(error)
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                    Button {
                        Task { await signIn.start() }
                    } label: {
                        if signIn.busy {
                            ProgressView()
                        } else {
                            Text("Sign in")
                        }
                    }
                    .disabled(signIn.busy)
                } else {
                    Text("""
                        No identity provider is configured, so there is nobody to \
                        sign in to. Set the sign-in realm under Settings → Own server.
                        """)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } header: {
                Text("Account")
            } footer: {
                Text("Opens a browser. New accounts and password changes happen there too.")
            }
        }
    }
}
```

The `@ObservedObject var model: FriendsModel` property goes away with the old struct, so
also change its one call site at line 19 from `SignInForm(model: model)` to `SignInForm()`.
`FriendsModel` already watches `SettingsFlows.authToken()`, so the screen flips to
`signedInList` on its own once tokens are stored — nothing has to tell it.

- [ ] **Step 3: Add the sign-in realm editor**

In `iosApp/Detour/SettingsScreen.swift`, the `@State` block above `apiURL` currently says the
screen has no editors for the per-service addresses **or the sign-in realm**. It now has one
for the realm, so narrow that comment:

```swift
    // Carried through load/save untouched: this screen has no editors for the
    // per-service addresses yet, and saving defaults over values set elsewhere
    // would silently unconfigure them.
    @State private var apiURL = ""
    @State private var routingURL = ""
    @State private var geocoderURL = ""
    @State private var idpIssuer = ""
```

Then add the field to `serverSection`, after the `CF-Access-Client-Secret` `SecureField` and
before the `Save server` button:

```swift
            TextField("https://your.realm/realms/detour", text: $idpIssuer)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
```

and extend that section's footer so the new field is explained rather than guessed at:

```swift
            Text("""
                One address reaches routing, search, sync and convoys — the tunnel \
                routes by path. Leave blank to use the built-in defaults. The realm \
                address is separate and never derived from the others: it is where \
                signing in happens.
                """)
```

The realm URL must be its own field because `RoutingServer.issuer` resolves only
`idpIssuer` and the baked default, never the general address
(`shared/…/data/RoutingServer.kt:133-134`) — pointing sign-in at the API host produces a
token exchange against a host with no discovery document, which surfaces as "not signed in"
with nothing logged.

`loadServer()` and `saveServer()` already read and write `idpIssuer`, so neither changes.
`RoutingServer.save` clears the session when the issuer changes
(`RoutingServer.kt:170`), so editing this field cannot leave a session minted by a different
realm behind.

- [ ] **Step 4: Verify the shared framework still builds for iOS targets**

The Kotlin/Native compilations cannot be invoked off macOS, so on Linux verify what can be:

```bash
devcontainer-exec ./gradlew :shared:compileCommonMainKotlinMetadata :shared:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`.

The Swift changes compile only on macOS. `ios.yml` is path-gated on `shared/**` and
`iosApp/**`, so it will run `xcodegen`, build for the simulator, boot it and screenshot.
**Do not claim the Swift side compiles** — say it is pending CI.

- [ ] **Step 5: Re-read the Swift for the two mistakes that only fail at compile time**

Neither is checkable on Linux, so read for them deliberately:

1. `DetourShared.Group` collides with SwiftUI's `Group`. `SignIn.swift` does not name
   `Group`, but confirm no added line does.
2. Exported Kotlin names: the object is `Oidc.shared`, the suspend function is
   `complete(url:)`, and `begin` takes a `KotlinByteArray`, not `Data` or `[UInt8]`.

- [ ] **Step 6: Commit**

```bash
git add iosApp/Detour/SignIn.swift iosApp/Detour/FriendsScreen.swift \
        iosApp/Detour/SettingsScreen.swift
git commit -m "feat(ios): sign in through the realm, and let the realm be configured

The iOS app can now complete the authorization-code flow: SignIn.swift supplies
an ASWebAuthenticationSession and 80 bytes of SecRandomCopyBytes, and shared
Oidc does the rest. SignInForm stops explaining that this was not written yet.

Settings gains a sign-in realm field. Without it the flow was unreachable on
iOS whatever else was built: RoutingServer.issuer resolves only idpIssuer and
the baked default, never the general server address, and the screen was carrying
idpIssuer through load/save with no editor for it.

Everything gated on Account.signedIn — friends, the leaderboard, convoys,
circles, circle notifications, circle presence sync, trip sync — is already
wired in RootView and becomes reachable with this."
```

---

### Task 4: Version bump and the port record

Deliverable: `main` can take this branch — the version reflects a new feature, and `IOS_PORT.md` no longer describes the gap as open.

**Files:**
- Modify: `app/build.gradle.kts:76`
- Modify: `docs/IOS_PORT.md` ("Not done" §1)

**Interfaces:**
- Consumes: nothing. Consumed by: nothing.

- [ ] **Step 1: Bump the version**

In `app/build.gradle.kts` line 76:

```kotlin
        versionName = "1.80.0"
```

Minor, not patch: a new feature, backward compatible — the table in `CLAUDE.md`. Leave
`versionCode` alone; CI stamps it from the run number.

- [ ] **Step 2: Rewrite "Not done" §1 in docs/IOS_PORT.md**

Replace the whole numbered item 1 (the "Sign-in — and with it, half the app" paragraph and
its two following paragraphs) with:

```markdown
1. **A signed device build of the sign-in flow.** Signing in works: the flow moved
   into shared `Oidc` (authorize URL, PKCE, state check, spending the code) and each
   platform supplies only a browser and a CSPRNG — `app/auth/AuthBrowser.kt` and
   `iosApp/Detour/SignIn.swift`. The realm address has an editor on both platforms.

   What is unproven is the round trip on a real iOS device against a real realm: CI
   builds for the simulator and cannot reach a private Keycloak, so the iOS half is
   verified as far as "it compiles, boots and screenshots" and no further.
```

Then renumber the items that followed it, and delete the "Everything listed under *Done*
still works; everything that needs an account does not" sentence wherever it survives —
it is no longer true.

- [ ] **Step 3: Add the new capability to the Done list**

In the same file's "Done" section, after the "Everything that does not need an account is
ported" bullet, add:

```markdown
- **Sign-in, and with it everything gated on an account**: friends and the
  leaderboard, convoys, circles, circle arrival notifications, circle presence
  sync and trip sync. The flow is shared; iOS supplies
  `ASWebAuthenticationSession` and `SecRandomCopyBytes`, Android a Custom Tab
  and `SecureRandom`.
```

- [ ] **Step 4: Verify the whole build one last time**

```bash
devcontainer-exec ./gradlew :shared:compileCommonMainKotlinMetadata \
  :app:testDebugUnitTest :shared:testDebugUnitTest :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts docs/IOS_PORT.md
git commit -m "chore: bump to 1.80.0 and record that iOS sign-in landed

IOS_PORT.md's largest open item was sign-in gating half the app on iOS. What is
left of it is narrower and worth stating plainly: the round trip is unproven on
a real device, because CI builds for the simulator and cannot reach a private
realm."
```

---

## Self-Review

**Spec coverage.** Every in-scope item maps to a task: shared `Oidc.kt` and `OidcTest.kt` →
Task 1; the `AuthBrowser` rename and its call sites → Task 2; `SignIn.swift`, the
`SignInForm` replacement and the `idpIssuer` editor → Task 3; the version bump → Task 4. The
spec's `Enums.oidcEntropyBytes` requirement (a `const val` has no stable exported spelling)
is Task 1 Step 5, which the spec's shared-surface section implies but does not name — added
here. The spec's error-handling table is Task 1 Step 3's `spend`, message for message. The
spec's out-of-scope list stays out: no screen is split, no `PendingReset` deletion, no
convoy or circle work.

**Placeholder scan.** No TBDs, no "handle errors appropriately", no "similar to Task N" —
Task 2's and Task 3's code is written out even where it repeats a phrase from Task 1's
comments. Every command has an expected outcome. The one conditional instruction (Task 1
Step 4's `parametersOf` fallback) names the exact alternative rather than saying "adjust as
needed".

**Type consistency.** `Oidc.begin`/`isCallback`/`complete`/`abandon`/`ENTROPY_BYTES` and
`internal begin(entropy, issuer)`/`spend`/`SpentCallback` are spelled identically in Task 1's
implementation, Task 1's tests, Task 2's Android code and Task 3's Swift. `AuthBrowser.start`
returns `Boolean` in both its definition and its `FriendsScreen.kt` call site.
`Enums.oidcEntropyBytes` is `Int` in Kotlin and read as `Int(...)` in Swift.

**One risk called out for the executor.** Task 1's tests exercise `Oidc`'s parked state, which
is object-level and therefore shared between tests in a run. Every test either calls `begin`
first or calls `abandon` first, deliberately — do not add a test that depends on state left by
its neighbour, and do not reorder the existing ones on the assumption that they are
independent of it.

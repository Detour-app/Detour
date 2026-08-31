# iOS sign-in on a shared OIDC core

First of four slices moving account, friends, circles and shared location toward feature parity
on iOS. `docs/IOS_PORT.md`'s "Not done" §1 names this as the gap that matters: signing in moved
to the identity provider's own page in a browser (authorization code with PKCE), Android does
that in a Custom Tab, and the iOS side was never written. `Account.signedIn` is therefore
permanently false on iOS, which gates friends, the leaderboard, convoys, circles, circle
notifications, circle presence sync and trip sync all at once.

This slice moves the protocol half of that flow into `shared/commonMain` — where it is testable
and where it was never covered by a test on either platform — and leaves each platform with only
the browser trip and the secure random it cannot share.

## Why this is the whole unlock

`iosApp/Detour/RootView.swift` already wires `CircleSync.shared.start()`,
`CircleNotifications.shared.runCatchUpSweep()`, `SyncClient.shared.sync()` and both the Friends
and Circles tabs. Every one is gated on `Account.shared.signedIn`. The screens exist
(`FriendsScreen.swift` 292 lines, `CirclesScreen.swift` 579), the transports exist
(`ConvoyLiveClient.swift` 557, `CircleSync.swift` 135, `CircleNotifications.swift` 248), and the
whole domain layer is already shared (`Auth.kt`, `Social.kt`, `Groups.kt`, `CircleFixes.kt`,
`CirclePlaces.kt`, `CircleEvents.kt`, `FriendFog.kt`, `Api.kt`, `SyncClient.kt`).

So roughly 130 shared lines, 90 Swift lines and one Settings field light up seven features. That
ratio is why this slice goes first rather than the larger duplications behind it.

## Scope

In scope:

- New `shared/src/commonMain/kotlin/com/jellemax/detour/data/Oidc.kt`: authorize-URL
  construction, PKCE S256 challenge, in-flight verifier/state parking, callback detection,
  callback parsing, state verification, and the hand-off to `Auth.exchangeCode`.
- `app/src/main/java/com/jellemax/detour/auth/Oidc.kt` renamed to `AuthBrowser.kt` and reduced to
  the two things it cannot share: `SecureRandom` and `CustomTabsIntent`.
- New `iosApp/Detour/SignIn.swift`: `SecRandomCopyBytes` plus `ASWebAuthenticationSession`.
- `iosApp/Detour/FriendsScreen.swift`: the `SignInForm` placeholder (`FriendsScreen.swift:153`, which
  states the port is missing) becomes a working sign-in button.
- `iosApp/Detour/SettingsScreen.swift`: an editor for `idpIssuer`, which the screen currently
  carries through load/save untouched.
- `shared/src/commonTest/kotlin/com/jellemax/detour/data/OidcTest.kt`: first coverage of any of
  this logic on either platform.
- `versionName` minor bump in `app/build.gradle.kts` (`1.79.1` → `1.80.0`): new feature,
  backward compatible.

Out of scope, and deliberately:

- **Splitting `ui/SettingsScreen.kt` (1293 lines) or `ui/FriendsScreen.kt` (757).** Both get
  touched here; both stay whole. Extracting their state machines into shared stores is slice B.
- **The convoy live relay** (`net/ConvoyLiveClient.kt` 693 against `ConvoyLiveClient.swift` 557,
  the largest single duplication in this feature area). Slice C.
- **Circle presence cadence and notification policy** (`TripTrackingService.circleSyncLoop` and
  `notif/CircleNotifyService.kt` 235 against `CircleSync.swift` 135 and
  `CircleNotifications.swift` 248). Slice D.
- **Deleting `PendingReset`** from `Social.kt`. Its own doc comment says it exists only for the
  iOS app "which still signs in the old way"; once this lands it is dead, along with
  `StoreFlows.pendingResetToken()`. A separate cleanup, so this PR's diff stays about one thing.
- **Moving `app/auth/PendingSignIn.kt` into shared.** See Data flow — it is Android-shaped and
  iOS has no use for it.
- Any change to `Auth.kt` beyond call-site compatibility. Token exchange, refresh, sign-out and
  `tokenFailureMessage` are already shared and already tested (`TokenFailureTest.kt`).

## Architecture

Three approaches considered for where the split falls.

1. **Chosen. Protocol shared, browser and entropy pushed in.** `commonMain` owns everything that
   is a string operation or a decision: URL building, the S256 challenge, `isCallback`, query
   parsing, state comparison. Each platform supplies crypto-grade random bytes and opens a
   browser. No new `expect`, no new interface, no new `FlowWatcher` subclass. `Platform.kt` stays
   at its documented three concerns (`Platform.kt:11-14`, `CONTRIBUTING.md:28-31`).
2. A fourth `expect` concern — `expect fun secureRandomBytes(n: Int): ByteArray` — so
   `Oidc.start()` needs no argument. Rejected: it breaks a ceiling both `Platform.kt` and
   `CONTRIBUTING.md` state in prose, for the sake of one argument at two call sites. The rule
   exists precisely so that "the core is handed things, it never reaches for them"
   (`docs/IOS_PORT.md`), and randomness is not a special case.
3. An injected `Entropy` interface installed at startup the way `BuildDefaults` is. Rejected:
   passes the two-implementations test for adding an interface, but introduces an initialisation
   ordering hazard — a sign-in attempted before install throws — in exchange for nothing the
   parameter does not already give.

`kotlin.random.Random` was never a candidate. A PKCE verifier and an OAuth state must be
unguessable; the common stdlib's generator is not a CSPRNG.

### The rename is required, not cosmetic

`com.jellemax.detour.auth.Oidc` and `com.jellemax.detour.data.Oidc` would collide in every
Android file importing both. The Android object becomes `AuthBrowser`, which names what is left
of it after the protocol moves out.

## Shared surface

`shared/src/commonMain/kotlin/com/jellemax/detour/data/Oidc.kt`:

```kotlin
object Oidc {

    /** Bytes a platform must hand [begin]: 64 for the verifier, 16 for the state. */
    const val ENTROPY_BYTES = 80

    /** Whether signing in is possible at all — false when no realm is configured. */
    val configured: Boolean

    /** The realm's authorize URL, with a fresh verifier and state parked. Returns
     *  "" when there is no realm or [entropy] is short. */
    fun begin(entropy: ByteArray): String

    fun isCallback(url: String): Boolean

    /** Verifies the state, then exchanges the code. Throws [AuthException]. */
    suspend fun complete(url: String)

    /** The browser never opened, or the rider dismissed it. Drops the parked secrets. */
    fun abandon()
}
```

Four details that are decisions rather than mechanics:

**`begin` returns `""` and never throws.** It is not a `suspend` function, and Kotlin/Native
turns an exception out of a non-`suspend` exported function into a process termination, not
something Swift can `catch`. `complete` is `suspend`, and is annotated `@Throws`, so its
`AuthException` arrives in Swift as an `NSError` through the generated completion handler.

> **Correction.** An earlier revision of this document claimed the `@Throws` annotation was
> unnecessary — that a `suspend` function's exceptions reach Swift as an `NSError` on their own,
> and that `FriendsModel.report` already reading `(error as NSError).localizedDescription` for
> `Friends.lists()` proved it. That was wrong, and the evidence was worthless: nothing on iOS
> could sign in, so every one of those `catch` blocks was unreachable and had never run once.
>
> Kotlin/Native's actual rule: a `suspend` function without `@Throws` propagates **only**
> `CancellationException`; any other Kotlin exception reaching Swift is treated as unhandled and
> **terminates the process**. `grep -rc '@Throws' shared/src` returned 0 across the whole
> module, against roughly 40 `try await` call sites in `iosApp/Detour/`. `try?` is no help — the
> abort happens on the Kotlin side, before control returns to Swift.
>
> So the exported `suspend` surface iOS calls is annotated as part of this work, which is a
> deliberate widening of this slice: sign-in is what makes friends, circles, convoys and sync
> reachable on iOS at all, and a feature that dies on its first network error is not parity.
> `@Throws` is a no-op for the Android target, so nothing on that side changes.

**The PKCE challenge is okio, unpadded.** `verifier.encodeUtf8().sha256().base64Url()`, then
`trimEnd('=')`: okio emits padding and RFC 7636 §4.2 forbids it. Both operations are common —
this is the same reason `Http.gzip` uses okio rather than `java.util.zip`.

**Verifier and state are base64url slices of the one entropy block**, 64 bytes and 16. The
verifier is 86 characters, comfortably inside RFC 7636's 43..128.

**The callback is parsed with `io.ktor.http.parseQueryString` over `url.substringAfter('?', "")`,
not Ktor's `Url`.** `detour://auth/callback` is not an http(s) URL and `Url` is built for ones
that are. `Auth.kt` already imports from `io.ktor.http`, so nothing new arrives on the classpath.

The parked verifier and state stay in memory only, exactly as the current `auth/Oidc.kt` header
documents: a sign-in that does not survive the process is a sign-in to start again, which is
cheaper than persisting a secret to smooth an edge case (and is what ASVS 5.0.0 V10.1.2 asks
for — the verifier stays bound to the transaction and the user agent that began it).

## Platform surfaces

`app/src/main/java/com/jellemax/detour/auth/AuthBrowser.kt`, about 45 lines:

```kotlin
object AuthBrowser {

    val configured: Boolean get() = Oidc.configured

    /** False when there is no realm configured or no browser to open it in, so
     *  the caller can say so instead of leaving a button that does nothing. */
    fun start(context: Context): Boolean {
        val url = Oidc.begin(ByteArray(Oidc.ENTROPY_BYTES).also { SecureRandom().nextBytes(it) })
        if (url.isBlank()) return false
        return try {
            CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
            true
        } catch (e: ActivityNotFoundException) {
            // No browser at all: nothing here substitutes for one, and a WebView
            // deliberately is not an option.
            Oidc.abandon()
            false
        }
    }
}
```

`MainActivity.kt:112-116` keeps its shape, with `Oidc.isCallback("$data")` and
`Oidc.complete("$data")` taking strings instead of `Uri`. Everything from `PendingSignIn.begin()`
through `PendingSignIn.fail(reason)` (`MainActivity.kt:113-134`) is untouched, including the
`DetourAuth` logging and its ASVS note.

`iosApp/Detour/SignIn.swift`, about 90 lines: an `ObservableObject` holding `busy` and `error`,
conforming to `ASWebAuthenticationPresentationContextProviding`. It draws 80 bytes from
`SecRandomCopyBytes`, calls `Oidc.shared.begin(entropy:)`, wraps
`ASWebAuthenticationSession(url:callbackURLScheme: "detour")` in a
`withCheckedThrowingContinuation`, and awaits `Oidc.shared.complete(url:)` on the URL the session
hands back.

`ASWebAuthenticationSession` over `SFSafariViewController` plus a registered URL scheme and
`onOpenURL`: it returns the callback URL to the caller in-process, needs no `Info.plist` scheme
entry, and is the API Apple built for this exact leg. `docs/IOS_PORT.md` already names it.

## Data flow, and why it is asymmetric

```
Android   FriendsScreen ──▶ AuthBrowser.start(context)
                              SecureRandom(80) ─▶ Oidc.begin ─▶ CustomTabsIntent
          realm ──▶ detour://auth/callback?code&state ──▶ MainActivity.onNewIntent
                              Oidc.isCallback ─▶ PendingSignIn.begin
                              ─▶ Oidc.complete ─▶ Auth.exchangeCode
                              ─▶ PendingSignIn.succeed(Auth.username.value)

iOS       FriendsScreen ──▶ SignIn.start()
                              SecRandomCopyBytes(80) ─▶ Oidc.begin
                              ─▶ ASWebAuthenticationSession(callbackURLScheme: "detour")
                              ─▶ completion returns the callback URL in-process
                              ─▶ try await Oidc.complete(url:) ─▶ Auth.exchangeCode
```

On Android the redirect lands in `MainActivity`, which is not the composition the button is in,
so the outcome has to travel between them — that is the entire reason `PendingSignIn` exists, and
its own doc comment says so. On iOS the session returns to its caller, so a local `@Published`
pair covers it.

`PendingSignIn` therefore **stays in `app/`**. Moving it would put an Android-shaped workaround in
the core for nobody's benefit, and would cost an eleventh `FlowWatcher` subclass — its `error`
and `signedInAs` are `StateFlow<String?>`, and `StringWatcher` carries a non-null `String`. The
alternative (flattening both to blank-means-none to reuse `StringWatcher`) is churn across
Android call sites bought for an iOS screen that will not read them.

## Error handling

`Oidc.complete` throws `AuthException` (`Api.kt:7`, already an `okio.IOException`) where the
current Android code throws `IllegalStateException`, so both platforms surface one type carrying
the realm's own wording. Five refusals, each keeping today's message verbatim:

| Checked | Condition | Message |
|---|---|---|
| 1st | No sign-in parked | "The app restarted while the browser was open, so this sign-in could not be finished. Tap Sign in to start again." |
| 2nd | State mismatch | "Sign-in could not be verified — start again" |
| 3rd | Callback carries `error` | the realm's `error_description`, with the bare code in parentheses |
| 4th | No `code` param | "The identity provider returned no code" |
| — | Exchange refused | whatever `Auth.exchangeCode` raises, already translated by `Auth.tokenFailureMessage` |

The "app restarted" wording is kept deliberately: `app/auth/Oidc.kt:109-119` records that the
previous phrasing ("nothing is in progress") sent people looking for a broken realm.

### The order is not the Android original's, and that is the point

The Android code checks `error` **first** and clears the parked verifier and state before
parsing anything. Review of the extraction found what that costs: `detour://auth/callback` is an
exported deep link, so any app on the device can fire

```
detour://auth/callback?error=access_denied&error_description=<attacker's sentence>
```

while the rider is on the realm's page. Detour foregrounds, the parked PKCE verifier is
discarded, the attacker's sentence is shown verbatim as the reason sign-in failed, and the
genuine callback arriving afterwards then fails with "The app restarted while the browser was
open" — a denial of service plus unauthenticated text on screen, from an unprivileged app.

So the shared version reorders, and the reorder is the reason it is worth having this in one
place rather than two:

1. **Nothing parked** is checked first, not the state, so the documented "app restarted" wording
   survives. A state-first fix would answer that case with the generic "could not be verified",
   which is exactly the message `auth/Oidc.kt:109-119` records as having sent people looking for
   a broken realm.
2. **The state check throws without clearing.** A callback that does not carry this device's own
   state is not ours, so it must not consume our sign-in. This is the half that closes the denial
   of service.
3. **Only then are the secrets cleared**, and only then is the realm's `error_description`
   trusted — an unsolicited callback can no longer put its own text in front of the rider.

Android inherits the fix by moving onto the shared object; it is not a separate Android change.

iOS additionally swallows `ASWebAuthenticationSessionError.canceledLogin` — the rider dismissed
the sheet — into `Oidc.abandon()` with no error shown. A cancel is not a failure.

`RoutingServer.save` already calls `Auth.clear()` when the issuer changes
(`RoutingServer.kt:170`), so the new iOS issuer editor cannot leave behind a session minted by a
different realm.

## Configuration: the second half of the iOS gap

`RoutingServer.issuer(custom)` resolves to `custom.idpIssuer` or `BuildDefaults.idpIssuer` and
**never** derives from the base URL (`RoutingServer.kt:133-134`). A build published to a store
ships no baked issuer, so the rider's own value is the only one there will ever be.

Android exposes that field (`ui/SettingsScreen.kt:1137,1178`). `SettingsScreen.swift` does not —
it declares `@State private var idpIssuer` purely to carry the value through `loadServer` and
`saveServer` untouched, with a comment saying this screen has no editor for it. Without adding
one, `Oidc.configured` is false forever on iOS and the browser leg is unreachable. The field goes
into the existing `serverSection`, and that carried-through comment goes away.

## Tests

`shared/src/commonTest/kotlin/com/jellemax/detour/data/OidcTest.kt`, plain `kotlin.test`, house
style (a class per subject with a KDoc saying what contract it covers, sentence-shaped test
names, a private fixture builder). There are eight files in `commonTest` today —
`TokenFailureTest.kt` over `Auth.tokenFailureMessage` and `ServerResolutionTest.kt` over
`RoutingServer` are the closest neighbours, and this one sits beside them.

- `challengeIsTheUnpaddedBase64UrlSha256OfTheVerifier` — RFC 7636 §4.6's own vector:
  `dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk` → `E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM`.
  The published vector is the point: it proves the encoding, not just self-consistency.
- The authorize URL carries `client_id`, `response_type=code`, `scope=openid profile email`,
  `redirect_uri`, `code_challenge_method=S256`, and a state that differs between two `begin`
  calls given different entropy.
- `beginReturnsBlankWhenNoRealmIsConfigured`, and blank when entropy is shorter than
  `ENTROPY_BYTES` — the two non-throwing refusals Swift depends on.
- `aCallbackWhoseStateIsNotTheOneSentIsRefused`.
- `aCallbackCarryingAnErrorParamReportsTheRealmsDescription`, including a percent-encoded
  description, since that is how a realm actually sends one.
- `isCallbackRejectsAUrlThatMerelyStartsSimilarly` — e.g. `detour://auth/callbackx`.
- `completeAfterAbandonSaysTheSignInWasNotFinished` — the parked-secrets-cleared path.

No file access and no network: everything above is a string operation, which is what makes the
move worth doing. Tests run on the JVM via `build.yml` on every pull request, and again on
Kotlin/Native via `ios.yml` because `shared/**` changed.

`Oidc.configured` reads `RoutingServer`, which reads `prefs` — so the tests that need a
configured or unconfigured realm set it through `RoutingServer.save`/`clearCustom` the way
`ServerResolutionTest.kt` already does.

## Verification

Decided up front, so "done" is not negotiated afterwards:

- **Shared logic:** `OidcTest.kt`, plus `./gradlew :shared:compileCommonMainKotlinMetadata` and
  `:shared:testDebugUnitTest` before the PR — the metadata task is what catches `java.*` leaking
  into `commonMain`, and it is path-gated so it must be run locally.
- **Android:** re-verified by hand on a device through the devcontainer and adb. `auth/Oidc.kt` is
  being rewritten rather than added to, so the regression risk is real: sign in, sign out, sign in
  again, and kill the app while the Custom Tab is open to confirm the "app restarted" path still
  reports correctly.
- **iOS:** as far as `ios.yml` reaches — it compiles, boots the simulator and screenshots. The
  browser round-trip on iOS stays unproven until someone runs it on a Mac against a reachable
  realm. This is stated in the PR description rather than glossed.

All Kotlin work here is Android-free string and decision logic, which is exactly the scope
`shared/src/commonTest` covers; there is no Robolectric in this project and none is added.

## Follow-ups this creates

1. Delete `PendingReset` from `Social.kt` and `StoreFlows.pendingResetToken()` — dead once iOS
   signs in through the realm.
2. Update `docs/IOS_PORT.md`: "Not done" §1 is what this slice closes, and the table of what
   replaced what gains no row, which is itself worth noting.
3. Slices B, C and D, each its own spec: shared feature state holders; the convoy live relay; and
   circle presence plus notification policy.

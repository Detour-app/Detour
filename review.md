# Detour — current code review

Review date: 2026-08-25, at commit `baa8a67` on `main`.

Scope: the .NET backend (`backend/`), shared KMP core (`shared/`), Android and
Wear apps (`app/`, `wear/`), iOS app (`iosApp/`), CI, Docker configuration and
documentation.

This refresh revalidates the repository-wide review written on 2026-08-13 at
`c192f07` and checks the changes since then. It is a current-state engineering
review, not a penetration test or a substitute for testing the production
deployment.

**Overall:** the backend remains thoughtfully designed. Authorization is
policy-based, privacy boundaries are represented by capabilities that do not
exist rather than permissions that might be missed, live membership revocation
has an explicit backstop, and backend test coverage is substantial. The recent
documentation pass and `MapScreen` split removed two of the largest maintenance
problems from the previous review.

The most urgent remaining work is concentrated at system seams:

1. Android still targets API 35 shortly before the next Play target-API
   requirement.
2. The server's `place_event` timestamp key disagrees with both clients.
3. Reverse-proxy settings are documented and deployed but never installed in
   the ASP.NET pipeline.
4. iOS still has no sign-in flow, and its dormant WebSocket client uses the
   wrong token type.

---

## 0. Changes since the 2026-08-13 review

| Earlier finding | Current status |
| --- | --- |
| Android/shared tests never run in CI | **Fixed.** `build.yml` runs `:app:testDebugUnitTest` and `:shared:testDebugUnitTest` before the release build. |
| No written live-relay protocol | **Fixed.** `docs/CIRCLES_AND_CONVOYS.md` §6 lists every frame and key, and `docs/BACKEND_SPEC.md` points to it. The table also makes the remaining `place_event` mismatch unambiguous. |
| README describes the pre-Keycloak/pre-relay world | **Fixed.** Sign-in, the iOS limitation, self-hosting and missing voice relay are now accurate. |
| `MapScreen.kt` is 3,197 lines | **Improved.** Commit `7904319` split out map chrome, dialogs, HUD, camera policy, navigation helpers and tests. `MapScreen.kt` is now 1,813 lines. |
| `FUTURE.md` advertises shipped features | **Fixed.** GPX import, minimum spin distance and shipped voice-guidance platforms moved to the shipped section. |
| Credentials remain in transferable plaintext preferences | **Fixed.** Session tokens and the Cloudflare Access secret moved to `secure.xml`; migration clears old plaintext keys, and `secure.xml` is excluded from backup and device transfer. |
| Android credential fields can leak to keyboard/history services | **Fixed and guarded.** Secure input components landed with a CI check that rejects future raw credential fields. |
| Unconditional launch-time full sync | **Mitigated.** `SyncClient.syncIfDue()` gates automatic sync to once per five minutes. Explicit sync remains full-history. |

The original review's positive findings still hold: cloud backup excludes
credentials, release builds are signed and minified, pull requests are build
gated, failed writes roll back, fog sharing is mutual, circle pause is enforced
on read and write paths, and no API capability returns another rider's private
trips or traces.

---

## 1. Bugs and release blockers

### 1.1 Android still targets API 35 — HIGH, time-sensitive

`app`, `wear` and `shared` compile with SDK 35; the phone and watch apps also
target 35. `gradle.properties` still suppresses the unsupported-compile-SDK
warning for 35.

The next Play target-API deadline is 31 August 2026, six days after this review.
Confirm it in the Play Console and on Google's
[target API requirements page](https://developer.android.com/google/play/requirements/target-sdk),
then move all modules to API 36. Test Android 16 behavior that intersects with
this app: edge-to-edge UI, predictive back, and its location, microphone and
remote-messaging foreground services.

This is a release risk, not evidence that the current APK is broken. Play upload
is currently gated by `PUBLISH_TO_PLAY`; the risk becomes immediate when that
gate is enabled or the app is submitted manually.

### 1.2 Live `place_event` frames are dropped by both apps — HIGH

The documented contract and server send `ts`:

- `PlaceEventFrame.TimestampMs` has `[JsonPropertyName("ts")]`;
- `docs/CIRCLES_AND_CONVOYS.md` §6.3 lists `ts`.

Both clients require `tsMs`: shared `placeEventFromRelayFrame()` returns `null`
without it, while `ConvoyLiveClient.swift` exits the case. The backend integration
test proves a frame is emitted but does not assert its timestamp key; shared
parser tests build a synthetic `tsMs` frame. Both suites are green while
contradicting each other.

**Impact:** live arrival/departure notifications are silently lost. HTTP
catch-up can make the feature look delayed rather than broken.

**Fix:** make clients follow the `ts` contract, or rename the server field and
protocol table together. Assert the timestamp key in backend and client tests.

### 1.3 Forwarded-header settings are never applied — HIGH

Production Compose sets `ForwardedHeaders__KnownNetworks` and
`ForwardedHeaders__KnownProxies`; `backend/INSTALL.md` tells operators to set one
behind a proxy. No backend code configures `ForwardedHeadersOptions` or calls
`UseForwardedHeaders()`.

Behind the documented proxy topology, `ResolveClientIp()` sees the proxy, so all
riders share one per-IP rate-limit bucket. `UseHttpsRedirection()` also sees the
internal HTTP hop, risking unnecessary redirects or a loop depending on proxy
behavior.

Install forwarded-header handling before authentication/routing, parse only
configured trusted networks/proxies, and test that forwarded values are accepted
only from a configured proxy.

### 1.4 iOS cannot sign in — HIGH product gap

`FriendsScreen.SignInForm` renders an explanation instead of an
`ASWebAuthenticationSession`. Sync, friends, circles, convoys, shared fog and
account restoration are consequently unreachable on iPhone. README and
`FUTURE.md` now say so accurately.

Implement authorization code + PKCE with `ASWebAuthenticationSession`, then pass
the callback code and verifier to shared `Auth.exchangeCode`.

### 1.5 The dormant iOS relay uses a refresh token as bearer — MEDIUM

`ConvoyLiveClient.swift` builds its Authorization header from
`SettingsValues.shared.authToken`, which is `Settings.refreshToken`. The API
expects an access token for `detour-api`. Once iOS sign-in exists, the WebSocket
upgrade will fail.

Use shared `Auth.bearer()` for each connection attempt. Rename the ambiguous
watcher/property: watching a refresh token to answer “is there a durable
session?” is fine; placing it in an Authorization header is not.

### 1.6 WebSockets outlive the session that opened them — MEDIUM

`LiveController.Get` authenticates at upgrade. The 15-second revocation sweep
rechecks group membership, not token expiry, realm logout or account disablement.
A socket can keep receiving live data after token expiry while any membership
remains valid.

Capture the access token's `exp` at upgrade and close at expiry. Both clients
already reconnect, so a valid session can obtain a fresh token and resume.

### 1.7 Identity changes made during GET are not persisted — MEDIUM

`TransactionMiddlewareBase` skips `GET`, `HEAD` and `OPTIONS`. On every request,
`CurrentUser.SyncFromToken()` may change administrator state, email, username
and `LastSeenAt`, but does not flush them. They persist only when a later write
happens to save the tracked user.

Authorization reads current claims, so this does not retain administrator
privilege. It does leave account metadata stale. Explicitly flush changed
identity fields, or separate the intentionally lossy last-seen update.

### 1.8 Dashboard `tolerance=0` cannot request raw geometry — LOW

`DashboardController.Clamp` maps every value `<= 0` to its fallback. The
track/coverage endpoints advertise a valid minimum of zero, but zero becomes 6
or 25 metres. Use nullable query parameters: `null` means default; zero passes
through `Math.Clamp`.

### 1.9 The named anonymous rate-limit policy is unused — LOW

`RateLimitSettings.Anonymous` promises an opt-in `[EnableRateLimiting]` budget;
no endpoint applies it. The unauthenticated health endpoint gets only the much
larger global per-IP budget and performs a database check on every request.
Apply the named policy to `HealthController`, or remove the unused setting and
correct its comments.

### 1.10 Health responses expose dependency exception text — LOW

`HealthCheckReport.From()` copies `entry.Exception.Message` into a public,
unauthenticated response. Database failures can disclose internal host, database
or user names. Production responses should expose status and duration; detailed
exceptions belong in logs or local development.

---

## 2. Security and privacy

The important privacy properties remain sound:

- no endpoint can return another rider's trips, traces, routes or places;
- fog sharing is checked mutually on every request;
- circle pause is enforced on persistence and retrieval paths;
- group lookup failures do not reveal whether an identifier exists;
- dashboard keys are hashed, owner-scoped and use a separate auth scheme;
- credentials are Keystore-encrypted and excluded from cloud backup.

Open security-adjacent issues are proxy handling (§1.3), WebSocket lifetime
(§1.6) and public health details (§1.10).

### 2.1 Sensitive microphone permissions remain for disabled voice — MEDIUM

`Features.pushToTalk` is false and the relay drops `ptt_start`, `ptt_audio` and
`ptt_end`. The Android manifest still declares `RECORD_AUDIO`,
`FOREGROUND_SERVICE_MICROPHONE` and `MODIFY_AUDIO_SETTINGS` for dormant code.
Remove them until voice returns, or document why unreachable code must retain
sensitive permission surface. Reintroduce them with the planned Opus/binary
relay.

### 2.2 Device-transfer comments are stale, credentials are protected — LOW

`data_extraction_rules.xml` and `backup_rules.xml` discuss an old `auth_token` in
`settings.xml`. Current tokens live in excluded `secure.xml`, whose Keystore key
cannot transfer. Update the comments so future maintenance targets the right
preference file.

### 2.3 BLE telemetry remains unauthenticated by design

`SECURITY.md` documents this limitation. Plausibility and slew caps bound spoofed
speed/lean/G-force input. It remains part of the threat model, not a secure
channel, but changes since the previous review do not make it a new blocker.

---

## 3. Documentation and dead code

### 3.1 Shared feature copy contradicts the enabled relay

`Features.liveRelay` is true, but `liveRelayReason` says live location and
arrival alerts need a relay “being rebuilt.” `CircleEvents.kt` says the live
surface was not rebuilt. Only voice is missing. Rewrite these around the actual
push-to-talk limitation, and give iOS sign-in its own notice instead of showing
`liveRelayNotice` as its account heading.

### 3.2 Legacy password-reset plumbing is dead

`PendingReset`, its iOS watcher and `DetourApp.onOpenURL` still handle
`detour://reset`, while password reset belongs to Keycloak in a browser. iOS does
not use an older flow. Delete this path unless the realm intentionally emits
that deep link.

### 3.3 One editing marker remains

`LiveConnection.cs` contains `ponytail:` in an XML comment. Remove the marker and
keep the useful queue rationale that follows it.

---

## 4. Architecture and maintainability

### 4.1 The live protocol still has multiple hand-maintained parsers

The protocol has a written contract, but server records, Android `org.json`,
Swift dictionaries and a shared partial parser can drift. `place_event` proves
it. Extend shared parsing where Kotlin/Native export is practical; where Swift
must stay hand-written, pin exact key sets in both server and client tests.

### 4.2 Circle membership failures use inconsistent HTTP statuses

`CirclesController.SetSharing` maps `NotAMember` to 404. Position, place and event
endpoints call `ThrowIfFailure()` and advertise 400 for the same condition. Pick
one convention—404 fits the anti-enumeration rule—and apply it consistently.

### 4.3 Sync remains full-history

Every explicit sync uploads all trips and raw traces; every response returns the
full union. The five-minute auto gate and gzip reduce frequency and bytes, but
server work still scales with lifetime history. Measure payload and latency
before redesigning. If material, trace hashes and trip `startTimeMs` keys make a
delta protocol natural.

### 4.4 `MapScreen` is improved, not finished

The split added useful camera/follow/navigation tests. At 1,813 lines the
remaining composable is still large, but no longer urgent. Extract coherent
boundaries when features touch them; avoid another bulk split for line count.

---

## 5. Build, dependencies and CI

### 5.1 Kotlin and AGP are outside their tested range — MEDIUM

The build combines Kotlin 2.0.20 with Android Gradle Plugin 8.13.2. A fresh
`./gradlew test` succeeds, but Kotlin reports that AGP 8.13.2 is above the
maximum tested with this plugin (8.5). Upgrade Kotlin/Compose/KMP together and
use the full Android + shared + iOS CI matrix as the acceptance gate, preferably
alongside the API-36 move.

### 5.2 Dependency versions are scattered

Versions remain string literals across root, app, wear and shared Gradle files.
A version catalog plus Dependabot or Renovate would make compatibility upgrades
visible and reviewable. At minimum, document the MapLibre pin beside it.

### 5.3 iOS CI has two configuration gaps — LOW

- Android CI uses Java 21 and `setup-gradle@v4`; iOS uses Java 17 and v3 for the
  same Gradle build.
- iOS `push` paths include root Gradle files and `gradle.properties`, but
  `pull_request` paths do not. Shared build configuration can skip iOS on a PR.

Align the toolchains/actions and path filters. Android lint is also absent;
`:app:lintRelease` would cover manifest, SDK and resource issues unit tests do
not.

### 5.4 Verify the PostgreSQL 18 volume path — LOW

The app database mounts `postgres-data:/var/lib/postgresql`; the PostgreSQL 17
Keycloak database mounts `/var/lib/postgresql/data`. This may be the intended
PostgreSQL 18 layout, but the asymmetry invites a future “cleanup.” Verify
persistence across recreate and explain the path in Compose.

---

## 6. Recommended order of work

### Before the next Android release

1. Confirm the Play deadline and move all Android modules to API 36 (§1.1).
2. Align the Kotlin–AGP toolchain and run the full matrix (§5.1).
3. Fix `place_event` with cross-contract assertions (§1.2, §4.1).
4. Install and test trusted forwarded headers (§1.3).

### Product completeness

5. Add iOS browser sign-in with PKCE (§1.4).
6. Fix iOS WebSocket bearer acquisition in the same change (§1.5).
7. Remove dormant microphone permissions, or restore voice using Opus/binary
   frames (§2.1).

### Backend hardening

8. Close live sockets at token expiry (§1.6).
9. Persist identity-provider changes on read paths (§1.7).
10. Hide production health exceptions and apply the anonymous limiter
    (§1.9–§1.10).
11. Normalize circle membership failures (§4.2).

### Maintenance

12. Correct feature/reset comments and delete dead reset plumbing (§3).
13. Fix dashboard zero semantics (§1.8).
14. Align iOS CI and centralize dependency versions (§5.2–§5.3).
15. Measure sync before designing delta sync (§4.3).
16. Verify and document the PostgreSQL 18 volume mount (§5.4).

This order is front-loaded with release eligibility and bugs that silently
discard data. Architecture cleanup follows observed failures rather than line
count or stylistic preference.

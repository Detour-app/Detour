# iOS port

Merged into `main`. iOS-only work happens on the `ios` branch and merges back;
anything under `shared/` goes to `main` directly, because it moves both
platforms at once.

## Shape

```
shared/     Kotlin Multiplatform. All roulette/routing/trip logic.
            commonMain compiles for Android and iOS alike.
app/        Android app. UI + platform services only; logic comes from :shared.
iosApp/     SwiftUI app. UI + platform services only; same :shared.
wear/       Wear OS app, unchanged.
```

The rule the split follows: **the core is handed things, it never reaches for
them.** Location fixes, audio, Bluetooth and notifications are pushed *into*
`:shared` by whichever platform is running it. That is why `Platform.kt` has
only three expect declarations (a key-value store, a files directory, a file
system) and is not on its way to becoming a second app.

## Done

- **`:shared` module**, 22 files, ~4,000 lines of logic shared verbatim, with
  18 tests over the parsing the port was most likely to break.
- **Android runs on it.** `./gradlew :app:assembleDebug` builds; the phone app
  behaves as before. This is why the port could not stay on a side branch —
  it rewrote the Android app too, so the two platforms have one history.
- **Everything that does not need an account is ported**: map and spin, trip
  recording in the background, history and trip detail with GPX export, badges,
  saved places, routes, settings, and in-app turn-by-turn with spoken
  directions.
- **Sign-in, and with it everything gated on an account**: friends and the
  leaderboard, convoys, circles, circle arrival notifications, circle presence
  sync and trip sync. The authorization-code-with-PKCE flow is shared
  (`shared/.../data/Oidc.kt`) and each platform supplies only the two things it
  cannot share — `ASWebAuthenticationSession` + `SecRandomCopyBytes` on iOS,
  a Custom Tab + `SecureRandom` on Android (`app/auth/AuthBrowser.kt`).

- **Circle presence and the arrival-notification policy are shared.** The two
  presence loops were the tightest duplication in the project — same structure,
  same guards, and eight hand-copied constants across two languages that all
  happened to agree. The tick is now `shared/…/data/CirclePresence.kt` and the
  delivery decisions `CircleNotifyPolicy.kt`; each platform keeps only its own
  loop, its clocks, and its delivery mechanism (a foreground service and a
  notification channel on Android, `UNUserNotificationCenter` and its
  authorization on iOS).

  Two things surfaced by sharing the decision rather than the code. Android's
  catch-up notifications now raise **newest-first**, matching what iOS already
  did — the cap exists because a backlog is not worth reading in full, so the
  most recent arrival should not sit under four older ones. And the two
  platforms do **not** measure fix age the same way: Android uses
  `SystemClock.elapsedRealtime()` deliberately, because a device clock corrected
  mid-drive answers "how old is this reading" wrong in whichever direction it
  moved, while iOS has only `CLLocation`'s wall-clock `Date`. Fixing that means
  stamping `ProcessInfo.systemUptime` where a fix is received, which is a
  location-plumbing change and was left alone. It is visible now because both
  call sites pass the same parameter.

- **The convoy live relay is shared.** It used to be two independent
  implementations of one WebSocket protocol — 693 lines of Kotlin against 600 of
  Swift, with the same tuning constants typed out on both sides. The codec, the
  state machine, peer pruning, backoff and the spin vote are now
  `shared/.../drive/RelayProtocol.kt` and `ConvoyRelay.kt`, with each platform
  supplying only a socket behind a `RelaySocket` interface
  (`OkHttpRelaySocket.kt`, `UrlSessionRelaySocket.swift`).

  The spin rule is the part that mattered most: both clients documented that a
  convoy splits across two destinations if devices resolve one offer
  differently, and it turned out to exist in *three* places — neither platform
  used the one copy that had tests. It is one implementation now, and the
  property is finally tested: two relays with deliberately different peer sets,
  given the same offer, must reach the same destination. A fake `RelaySocket`
  is what makes that testable at all; before this, exercising any of it needed
  two phones and a live relay.

  What is *not* shared, and cannot be: push-to-talk capture and playback
  (`AudioRecord`/`AudioTrack` against `AVAudioEngine`), and Android's
  foreground service with its audio focus and notification.

- **Kotlin exceptions actually reach Swift.** They did not before, and this is
  worth its own bullet because nothing about it is visible at a call site. A
  Kotlin/Native `suspend` function without `@Throws` propagates only
  `CancellationException`; every other exception reaching Swift is treated as
  unhandled and **terminates the process**. The module had zero `@Throws`
  against ~40 `try await` sites, and `try?` is no help — the abort happens on
  the Kotlin side before control returns. It went unnoticed because the
  account-gated majority of those paths was unreachable while sign-in was
  missing. The 29 suspend functions iOS calls are now annotated. `@Throws` is
  inert for the Android target, so nothing changed there.

- **CI on `macos-15`** — free and unmetered on this public repo. Runs the
  shared tests on both the JVM and Kotlin/Native, builds the app for the
  simulator, boots it, and uploads a screenshot. No Mac and no Apple Developer
  account involved.

### What replaced what

| Android-only | commonMain |
|---|---|
| `org.json` | kotlinx.serialization + `opt*` helpers in `Json.kt` |
| `HttpURLConnection` | Ktor — OkHttp on Android, NSURLSession on iOS |
| `java.io.File` | okio |
| `SharedPreferences` | `expect class Prefs` → NSUserDefaults on iOS |
| `BuildConfig` | `BuildDefaults`, pushed in at startup by each platform |
| `java.util.Calendar` | kotlinx-datetime |
| `Math.toRadians` | `Angles.kt` |

The one structural change forced on callers: `HttpURLConnection` blocked and
Ktor does not, so everything touching the network is now `suspend`.

### Verifying without a Mac

```
./gradlew :shared:compileCommonMainKotlinMetadata
```

This type-checks `commonMain` against the *common* intersection, which excludes
`java.*`. A stray JDK import fails here on Linux exactly as it would on macOS —
even though the `ios*` targets themselves can only be **built** on a Mac.

## Where the two platforms deliberately differ

Not gaps — decisions, and the places to look first if behaviour diverges.

- **Trip detection.** Android runs a foreground service tiering location across
  sleep/idle/probe/trip off activity-recognition transitions. iOS has no
  foreground service and no passive fixes, so that collapses onto a distance
  filter plus `CMMotionActivityManager` for the automotive hint. Every
  threshold is the Android one unchanged.
- **Lean and g.** `CMDeviceMotion` gives fused attitude, so there is no
  rotation matrix to assemble. `userAcceleration` excludes gravity, which the
  Android magnitude includes, so it is added back — otherwise the same ride
  would record a different number on each phone.
- **Guidance audio.** Android takes transient-may-duck focus per prompt;
  iOS uses `.duckOthers` + `.voicePrompt` and deactivates the session when the
  utterance ends, so music comes back between prompts.
- **Convoy keep-alive.** OkHttp has `pingInterval`, which stops NAT and the
  Cloudflare tunnel idling a quiet socket closed. `URLSessionWebSocketTask` has
  no such setting, so the ping is scheduled by hand in
  `UrlSessionRelaySocket.swift`. Everything above the socket — the
  sixteen-frame protocol, peers, TTL pruning, reconnect backoff, push-to-talk
  membership and the spin vote — is one implementation now, so this is a
  difference in how the connection is kept alive rather than in what either
  platform does with it.
- **Convoy live-URL resolution — not collapsed by the shared relay, and not
  new either.** `OkHttpRelaySocket.liveUrl()` checks a baked-in
  `BuildConfig.LIVE_URL` first, then derives `wss://<host>/api/live` from
  `RoutingServer.loadCustom()` — a rider's own self-hosted server URL.
  `UrlSessionRelaySocket.swift` reads only the baked-in
  `BuildDefaults.shared.liveUrl` and refuses outright if that is empty; it has
  no equivalent derivation. `RelaySocket`'s own doc says URL resolution is
  deliberately a platform concern, not something the shared relay decides, so
  this is not a gap the relay port left open - it is one that was never
  closed, the same divergence register entry 6c described before the relay
  moved: an iOS install pointed at a self-hosted server with no baked-in live
  URL can never join a convoy, where Android derives one from the same
  `server.url` every other service reads.

  Ktor's WebSockets plugin would close even this, since its `pingInterval` is
  common code. It was not used because the `ktor-client-darwin` engine's
  WebSocket support could not be confirmed from the resolved artifacts and
  nothing in the build environment can compile an iOS target to check — see
  `docs/superpowers/specs/2026-08-26-shared-convoy-relay-design.md`. If that is
  ever confirmed, the two sockets and this divergence collapse together.

## Not done

1. **The iOS sign-in round trip on real hardware.** Signing in works and is
   shared, but what has actually been exercised is narrower than that sounds:
   the shared half has unit tests, the Android half was driven on a device, and
   the iOS half is verified only as far as `ios.yml` reaches — it compiles,
   boots the simulator and screenshots. CI cannot reach a private Keycloak, so
   nobody has yet watched an iPhone complete the browser leg against a real
   realm. Treat that as untested rather than working.

2. **The stores can still take the app down.** The `@Throws` sweep above covered
   the `suspend` surface *iOS actually calls* — not the whole `suspend`
   surface. 17 more public `suspend` functions in `commonMain` are exported
   and still unannotated: `Auth.exchangeCode`/`.signOut`,
   `CircleFixes.fixes`, `Friends.remove`, `PoiRoulette.randomPoi`,
   `RoadRoulette`'s `randomRoadPoint`/`fetchRoads`/`nearestSpeedLimitKmh`/
   `speedLimitWays`/`rawQuery`, `RoundTripPlanner.plan`, `RouteShare.inbox`/
   `.delete`, `RoutingServer.roundTrip`/`.randomRoadDestination`,
   `SpinPicker.pickCandidate` and `SyncClient.syncIfDue`. None of these is
   called from Swift today, so there is no live gap for them — but
   `Auth.bearer` was on this same list until the convoy relay gave Swift a
   reason to call it (`ConvoyLiveClient.swift`'s `AuthBearerSource`, via the
   relay's `BearerSource` interface): it is annotated and called now, which
   is exactly the reminder this list exists to give the next function that
   crosses the same way. `SyncClient.syncIfDue` is worth naming on its own
   ahead of time for the identical reason: it sits directly above `sync()`'s
   canonical `@Throws` doc comment in the same file, is Android-only today,
   and is exactly what an iOS launch-time auto-sync would reach for first.
   Whoever wires that up has to remember to annotate it then; nothing here
   does it for them.

   Nor did the sweep cover the **non-`suspend`** store functions Swift calls —
   `TraceStore.append`/`.clear`/`.rawLines`, `TripStore.save`/
   `.updateMode`/`.delete`, `RouteStore.save`/`.rename`/`.remove`,
   `SavedPlaces.rename`/`.remove`, `BadgeStore.refresh`, `RecentSearchStore.save`
   — which write through `okio.FileSystem` and can throw `okio.IOException`.
   Because they are not annotated and not `suspend`, Swift cannot even write
   `try` against them, so there is no hint at the call site that a real I/O
   failure kills the process. The worst pair is `TraceStore.append` and
   `TripStore.save`, which run from `TripRecorder` *during* a ride: a phone at
   zero free space loses the trip and the app with it. A background location
   launch before first unlock is the other reachable case — default data
   protection returns `EPERM` and okio throws.

   Deliberately not fixed alongside sign-in: annotating a non-`suspend`
   function is a source-breaking Swift API change (~18 call sites across nine
   more files must grow `try`), and the annotation alone fixes nothing — it
   converts an abort into a throw each site must then handle, mostly by
   catching and degrading. These back features that already shipped, so they
   are their own change.
3. **watchOS app.** Small, but nothing reuses from `wear/`.
4. **Signed device builds.** CI builds for the simulator only.

### Will not port

Unchanged, and none of these have an iOS route.

- **Now-playing media** (`media/MediaListenerService.kt`). Reads other apps'
  media notifications. iOS has no equivalent and no workaround.
- **Android Auto → CarPlay.** CarPlay navigation needs an entitlement granted
  by Apple on application, routinely refused for hobby apps. The `car/` package
  (~1,700 lines) has no iOS home until that is granted.
- **BLE peripheral advertising** (`ble/BleNavServer.kt`) works, but iOS puts
  service UUIDs in the backgrounded "overflow area", where a non-Apple
  handlebar display generally cannot see them.

## Building it

Neither path needs a Mac except the last line.

**CI (no Mac):** push to `main` or `ios` touching `shared/` or `iosApp/`, open
a PR that does, or run the *iOS* workflow by hand. Two artifacts come out of it:

| Artifact | What it is |
|---|---|
| `Detour-simulator.app.zip` | Debug build for the simulator. `xcrun simctl install booted Detour.app` on any Mac. |
| `Detour-unsigned.ipa` | Release, arm64, for a real phone — **unsigned**. |

Plus `screenshot.png`, which is the closest thing to looking at the app without
a Mac.

The .ipa is unsigned because signing needs a certificate from a paid Apple
Developer account and nothing in CI has one. To get it onto a phone you re-sign
it yourself — Sideloadly or AltStore on Windows/Linux/macOS, or
`xcodebuild -exportArchive` on a Mac with your account. That is the wall the
$99/yr buys past; no CI trick removes it.

**On a Mac:**

```
brew install xcodegen
cp iosApp/Config.example.xcconfig iosApp/Config.xcconfig   # optional; endpoints
cd iosApp && xcodegen && open Detour.xcodeproj
```

The Xcode project is generated, not committed. A pre-build phase runs
`:shared:packForXcode`, so editing Kotlin and pressing Run rebuilds both halves.

**On a physical iPhone:** needs an Apple Developer account ($99/yr) for a
signing certificate and TestFlight. Nothing in CI removes that.

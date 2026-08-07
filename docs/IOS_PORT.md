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
- **The iOS app is feature-complete against everything that can port**: map and
  spin, trip recording in the background, history and trip detail with GPX
  export, badges, saved places, settings, friends and the leaderboard,
  in-app turn-by-turn with spoken directions, and convoy live location with
  push-to-talk.
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
  no such setting, so the ping is scheduled by hand.

## Not done

1. **watchOS app.** Small, but nothing reuses from `wear/`.
2. **Signed device builds.** CI builds for the simulator only.

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

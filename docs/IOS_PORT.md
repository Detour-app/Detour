# iOS port

Status of the `ios-port` branch. `main` is untouched.

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

- **`:shared` module**, 22 files, ~4,000 lines of logic shared verbatim.
- **Android runs on it.** `./gradlew :app:assembleDebug` builds; the phone app
  behaves as before.
- **iOS app** with MapLibre + CoreLocation and one working screen: spin,
  route, draw.
- **CI on `macos-14`** — free and unmetered on this public repo. Builds the
  shared framework, builds the app for the simulator, boots it, and uploads a
  screenshot. No Mac and no Apple Developer account involved.

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

## Not done

Ordered by what blocks the most.

1. **The rest of the SwiftUI screens.** History, badges, friends, saved places,
   settings, in-app navigation. ~6,000 lines of Compose to rewrite. This is the
   bulk of the remaining work.
2. **Background trip recording.** `LocationProvider.startBackground()` exists
   and is wired, but the trip *recorder* — the iOS counterpart of
   `TripTrackingService` (1,228 lines) — is not written. iOS has no foreground
   service; it grants background delivery via the `location` background mode,
   which Info.plist already declares.
3. **Convoy live location + push-to-talk.** WebSocket and audio capture.
4. **watchOS app.** Small, nothing reuses from `wear/`.

### Will not port

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

**CI (no Mac):** push to `ios-port`, or run the *iOS* workflow by hand. The
screenshot lands in the run's artifacts.

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

# Contributing

## Prerequisites

- JDK 17
- Android SDK 35 (compile/target), min SDK 26
- .NET 10 SDK and Docker if you're touching the backend — Docker for the
  development stack (Postgres, Redis, Keycloak) and for the integration tests,
  which start a real Postgres
- A Mac with Xcode 16 **only** if you want to build or run the iOS app locally.
  Everything else, including type-checking and testing the shared core, works
  on Linux and Windows.

## Layout

```
shared/     Kotlin Multiplatform core. Roulette, routing, trips, badges, sync.
app/        Android app — UI and platform services only.
iosApp/     SwiftUI app — UI and platform services only.
wear/       Wear OS companion.
backend/    .NET sync + social service, its database and tests.
docker/     Local development stack: Postgres, Redis, Keycloak, Traefik, LGTM.
server/     Home Assistant package for the read-only dashboard API.
```

The split follows one rule: **the core is handed things, it never reaches for
them.** Location fixes, audio, Bluetooth and notifications are pushed *into*
`:shared` by whichever platform is running it. `Platform.kt` deliberately
expects only three things — a key-value store, a files directory and a file
system — so wanting to add a fourth is the signal to push the dependency in
from the platform instead. See
[docs/IOS_PORT.md](docs/IOS_PORT.md).

New logic goes in `shared/` unless it genuinely cannot — a change that lands
only in `app/` silently makes iOS diverge.

## Building

All Gradle modules build from the repo root:

```bash
./gradlew assembleDebug          # phone + watch debug APKs
./gradlew :app:assembleDebug     # phone only
./gradlew :wear:assembleDebug    # watch only
```

Phone APK lands in `app/build/outputs/apk/debug/app-debug.apk`, watch APK in
`wear/build/outputs/apk/debug/wear-debug.apk`. `assembleRelease` also works
locally without any signing environment set — you'll get an unsigned,
minified release build; see `README.md` for how CI produces a signed one.

### The shared core

Run these before opening a PR that touches `shared/` — neither needs a Mac:

```bash
./gradlew :shared:compileCommonMainKotlinMetadata   # no java.* leaked into commonMain
./gradlew :shared:testDebugUnitTest                 # shared tests
```

The first is the one that catches the common mistake. `commonMain` compiles
fine against the Android target with a stray `java.util.Calendar` import and
then fails only on the iOS targets, which you cannot build on Linux; the
metadata compilation type-checks against the common intersection and fails
immediately instead.

### The iOS app

```bash
brew install xcodegen
cd iosApp && xcodegen && open Detour.xcodeproj
```

The Xcode project is generated from `iosApp/project.yml` and is not committed —
edit `project.yml`, never the `.xcodeproj`. A pre-build phase runs
`:shared:packForXcode`, so editing Kotlin and pressing Run rebuilds both halves.
`Config.example.xcconfig` is the iOS equivalent of `local.properties`; copy it
to `Config.xcconfig` if you want default endpoints baked in.

Without a Mac, push the branch and let the *iOS* workflow build it on
`macos-15` — it uploads a simulator app, an unsigned `.ipa` and a screenshot of
the app running.

No server URLs, API keys, or Cloudflare Access secrets are required to build.
The app takes all of that at runtime (Settings), or from `local.properties`
for a personal local build — see the `routingCfg()` helper in
`app/build.gradle.kts` for the property/env-var names it reads. If your
services sit behind one path-routed hostname, a single `server.url` covers
routing and the geocoder; the per-service keys override it where they're set.
The API needs `api.url` of its own — `/api` is already the geocoder's path in
that layout — and `idp.issuer` for the realm that issues rider tokens.

## Running the backend locally

Everything the service talks to comes up in Docker; the service itself runs
from your IDE or the command line:

```bash
docker compose -f docker/dev/docker-compose.yml up -d
cd backend/Detour/Detour.Api && dotnet run
```

That gives you Postgres, Redis and a Keycloak with the `detour` realm already
imported, plus the API on `http://localhost:7500`. Ports are fixed and
documented in [docker/dev/README.md](docker/dev/README.md), which also has the
dev rider's credentials and how to get a token by hand.

Point `api.url` and `idp.issuer` (in `local.properties`, or the app's own
Settings for the server address) at it. From an emulator, `adb reverse` both
ports rather than using `10.0.2.2`: the realm hands out absolute URLs built
from the hostname it was started with, so the device has to know it by the same
name the API does.

### Tests

```bash
dotnet test backend/Detour/Detour.Domain.Tests    # pure domain rules
dotnet test backend/Detour/Detour.InfraTests      # the API against a real Postgres
```

The integration tests start Postgres in a container, so they cover what an
in-memory provider cannot: citext comparison, jsonb, snake_case naming and
unique-index violations. Both suites plus `dotnet format style` are what CI
runs; a change to a persisted entity also needs its migration, and CI checks
that too.

## Branches

Three, and only three:

| Branch | For |
| --- | --- |
| `main` | Trunk. Everything lands here. |
| `android` | Android-only work, branched from `main`. |
| `ios` | iOS-only work, branched from `main`. |

`android` and `ios` merge **back into** `main`; work that touches `shared/`
belongs on `main` directly, since it affects both. Short-lived topic branches
are fine — delete them once they're merged rather than leaving them on origin.

## Pull requests

- **One topic per PR.** A security fix and a UI tweak are two PRs, even if
  they're both small.
- **The build must pass.** CI builds the phone and watch release APKs and
  bundles on every change — a red build blocks review, don't ask for an
  exception. A push to `main` additionally signs them, publishes a GitHub
  release, and uploads to Play's internal track (see
  [docs/RELEASING.md](docs/RELEASING.md)); PRs stop at the build.
- **Touching `shared/` or `iosApp/` also runs the iOS workflow**, which
  type-checks `commonMain`, runs the shared tests on both the JVM and
  Kotlin/Native, and builds and boots the app in a simulator. It has to be
  green too.
- If you touched the backend, both test suites have to pass, and a schema
  change needs the migration committed alongside it.
- If you touched security- or privacy-relevant code (BLE, backup rules,
  credential storage, the keychain), say so explicitly — those get a closer
  look.

## Code style

Comments in this repo explain **why**, not what — a line like
`// retry once, the board occasionally drops the first write after reconnect`
is worth its keep; `// increment the counter` is not. This is a deliberate
house style, not incidental: when you add code, add the reasoning behind it,
not a restatement of it. When you touch existing code, keep the comments
that are still true — don't delete or "clean up" a why-comment just because
the line next to it changed, unless the reasoning itself is now wrong.

Beyond that: match whatever the surrounding file already does (naming,
structure, how state is held) rather than introducing a new pattern for one
change.

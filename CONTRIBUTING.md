# Contributing

## Prerequisites

- JDK 17 or newer — the modules target 17; CI builds on 21
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

For code that already exists, two tests decide where it belongs:

> A policy earns the core when it is written more than once.
> A port earns an interface when it has more than one implementation.

The first is why the arrival/reroute rule belongs in `shared/` — it is
written twice today, and the two copies stay aligned only by hand.
`app/.../car/NavScreen.kt:242` notes the car screen runs the "Same
arrival/reroute policy as MapScreen.kt's navigating LaunchedEffect" — but a
comment on one copy naming the other is a promise, not an enforcement
mechanism, and nothing catches the day it stops being true. The convoy vote
rule was the same story until it stopped being one: `app/.../map/GroupSpinRules.kt`
and a Swift copy in `iosApp/Detour/MapScreen.swift` — which called itself
"identical to Android's rule and deliberately so," the exact kind of promise
this rule warns about — collapsed into one implementation,
`shared/.../drive/ConvoyRelay.kt`, which both platforms now call instead of
each carrying their own. The GraphHopper maneuver sign table shows what the
day still looks like for a rule that has not moved yet: the same
sign-to-icon switch, written three times (`app/.../ui/Navigation.kt:57-71`,
`app/.../car/NavScreen.kt:575-593`, `iosApp/Detour/NavScreen.swift`), and it
diverged three ways on iOS — sharp
turns drawn as U-turns, real U-turns and the motorway keep-left/right forks
both silently falling back to "carry on" — until the fix in the immediately
preceding commit (c7ef627).

The second is why `Platform.kt` still expects only the three things named
above: an interface with one implementation is indirection, not a boundary.

## Building

All Gradle modules build from the repo root:

```bash
./gradlew assembleDebug          # every module
./gradlew :app:assembleDebug     # the phone app only
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.
`assembleRelease` also works
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

No server URLs or API keys are required to build, and CI bakes none in. Most of them can be typed into Settings at runtime, or
baked into a personal build from `local.properties` — see the `routingCfg()`
helper in `app/build.gradle.kts` for the property and env-var names it reads.
If your services sit behind one path-routed hostname, a single `server.url`
covers routing and the geocoder; the per-service keys override it where they're
set. The API needs `api.url` of its own, because `/api` is already the
geocoder's path in that layout.

**`idp.issuer` is the exception: it is build-time only.** There is no Settings
field for the realm, so a build without it cannot sign in at all, and every
account feature behaves as it does when signed out. That is why the published
APKs have no sign-in — they are built with no properties set. To work on
anything involving an account, put `api.url` and `idp.issuer` in
`local.properties` and build your own.

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
- **The build must pass.** CI builds the release APK and bundle on every
  change — a red build blocks review, don't ask for an
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

## Versioning

`versionName` in `app/build.gradle.kts` is semver — `MAJOR.MINOR.PATCH` — and the
bump is tied to the commit type already used across this repo's history
(`feat:`, `fix:`, `chore:`, ...):

| Change | Bump | Example |
| --- | --- | --- |
| Bug fix, no API/behaviour break | Patch | `1.76.0` -> `1.76.1` |
| New feature, backward compatible | Minor | `1.76.1` -> `1.77.0` |
| Breaking change (data format, wire protocol, min OS) | Major | `1.77.0` -> `2.0.0` |
| Docs, refactor, chore, test-only | No bump | — |

A PR that mixes a feature and a fix bumps for the higher of the two (minor),
same as it always has here.

**This isn't cosmetic — `versionName` is the git tag and the GitHub release
name** (`.github/workflows/build.yml`). Pushing to `main` twice without
bumping it overwrites that version's release instead of creating a new one.
Bump it in the same commit/PR that lands the change, not as an afterthought.

`versionCode` is unrelated and untouched by this: CI stamps it from the run
number on every build (`VERSION_CODE` in `build.yml`), so it always increases
regardless of what `versionName` says. Only `versionName` is yours to bump.

The mock-location harness (`tools/mocklocation/build.gradle.kts`) versions
independently — this rule is about `app/build.gradle.kts`, the app people
actually install.

## Documentation

[`docs/`](docs/README.md) has an index; start there rather than guessing at
filenames.

Two documents are cited **by section number** from code comments, so their
numbering is load-bearing — append rather than renumber:

| Document | Cited as | Covers |
| --- | --- | --- |
| [docs/BACKEND_SPEC.md](docs/BACKEND_SPEC.md) | `spec §11` (backend) | What the service must do |
| [docs/CIRCLES_AND_CONVOYS.md](docs/CIRCLES_AND_CONVOYS.md) | `docs/CIRCLES_AND_CONVOYS.md section 6` (apps) | Groups, and the live relay's wire format |

Change behaviour that either one describes, and the document is part of the
change — a spec that has quietly drifted is worse than none, because the next
person checks their work against it.

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

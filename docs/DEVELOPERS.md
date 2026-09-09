# Detour: the developer's guide

How to build every piece, run the whole thing locally, and find the document
that covers whatever you're about to change.

What the app does is [USER_GUIDE.md](USER_GUIDE.md). What the system looks like
— every service and how they connect — is the [README](../README.md). The rules
for landing a change (branches, PRs, versioning, review, style) are
[CONTRIBUTING.md](../CONTRIBUTING.md).

## Contents

- [Prerequisites](#prerequisites)
- [Repository layout](#repository-layout)
- [Where code belongs](#where-code-belongs)
- [Building the apps](#building-the-apps)
- [Building the backend](#building-the-backend)
- [Running the whole stack locally](#running-the-whole-stack-locally)
- [Pointing a device at your stack](#pointing-a-device-at-your-stack)
- [Baking addresses into a build](#baking-addresses-into-a-build)
- [Testing](#testing)
- [What CI runs](#what-ci-runs)
- [Document index](#document-index)

## Prerequisites

| For | You need |
| --- | --- |
| The Android app and the shared core | JDK 17 or newer (CI builds on 21), Android SDK 35 (compile/target), min SDK 26 |
| The backend | .NET 10 SDK, and Docker — for the dev stack and for the integration tests, which start a real Postgres |
| The iOS app | A Mac with Xcode 16, plus `xcodegen` |

Everything except the last row works on Linux and Windows, including
type-checking and testing the shared core.

## Repository layout

```
shared/     Kotlin Multiplatform core. Roulette, routing, trips, badges, sync.
app/        Android app — UI and platform services only. Includes app/.../car/ (Android Auto).
iosApp/     SwiftUI app — UI and platform services only.
backend/    .NET sync + social service, its database and tests.
docker/     dev/ = the local stack. prod/ = the deployable one, plus proxy overlays.
server/     Home Assistant package for the read-only dashboard API.
bruno/      Generated Bruno collection covering every endpoint.
tools/      Side utilities — the mock-location harness above all.
docs/       Everything that isn't a README.
```

Inside `backend/`:

```
backend/
  Detour.slnx
  Directory.Build.props        every project's TargetFramework, nullable, xunit config
  Directory.Packages.props     every package version, in one place
  Detour/
    Detour.Api                 controllers, DI, the middleware pipeline, the live relay
    Detour.Domain              entities, repository interfaces, the rules
    Detour.Database            DbContext, entity configurations, repositories, migrations
    Detour.Domain.Tests        the rules, in isolation
    Detour.InfraTests          the API and the schema, against real Postgres
  Shared/                      cross-cutting libraries, nothing Detour-specific
```

`Domain` never references `Database`. It declares the repository interfaces;
`Database` implements them. `Api` is the only project that knows about ASP.NET
Core.

## Where code belongs

One rule: **the core is handed things, it never reaches for them.** Location
fixes, audio, Bluetooth and notifications are pushed *into* `:shared` by
whichever platform is running it. `Platform.kt` deliberately expects only three
things — a key-value store, a files directory and a file system — so wanting to
add a fourth is the signal to push the dependency in from the platform instead.

New logic goes in `shared/` unless it genuinely cannot; a change that lands only
in `app/` silently makes iOS diverge.

For code that already exists, two tests decide:

> A policy earns the core when it is written more than once.
> A port earns an interface when it has more than one implementation.

The first is why the arrival/reroute rule belongs in `shared/` — it is written
twice today, and the two copies stay aligned only by hand.
`app/.../car/NavScreen.kt:242` notes the car screen runs the "Same
arrival/reroute policy as MapScreen.kt's navigating LaunchedEffect" — but a
comment on one copy naming the other is a promise, not an enforcement mechanism,
and nothing catches the day it stops being true. The convoy vote rule was the
same story until it stopped being one: `app/.../map/GroupSpinRules.kt` and a
Swift copy in `iosApp/Detour/MapScreen.swift` — which called itself "identical to
Android's rule and deliberately so," the exact kind of promise this rule warns
about — collapsed into one implementation, `shared/.../drive/ConvoyRelay.kt`,
which both platforms now call instead of each carrying their own. The
GraphHopper maneuver sign table shows what the day still looks like for a rule
that has not moved yet: the same sign-to-icon switch, written three times
(`app/.../ui/Navigation.kt`, `app/.../car/NavScreen.kt`,
`iosApp/Detour/NavScreen.swift`), and it diverged three ways on iOS — sharp
turns drawn as U-turns, real U-turns and the motorway keep-left/right forks both
silently falling back to "carry on" — until it was fixed.

The second is why `Platform.kt` still expects only the three things named above:
an interface with one implementation is indirection, not a boundary.

The long form is [guidelines/](guidelines/README.md), whose section numbers are
what a review cites.

## Building the apps

### Android

```bash
./gradlew assembleDebug          # every module
./gradlew :app:assembleDebug     # the phone app only
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. Install it with
`adb install app/build/outputs/apk/debug/app-debug.apk`.

`assembleRelease` also works locally with no signing environment set — you get
an unsigned, minified build. CI produces the signed one; see
[RELEASING.md](RELEASING.md).

A debug build carries a few `adb` hooks for behaviour that is otherwise slow to
reach — raising the trip-ended notification without driving for one, opening a
trip straight from an intent, seeding trip history. None of them exist in a
release APK; see [DEBUG_INTENTS.md](DEBUG_INTENTS.md). To exercise anything that
needs the phone to actually move, replay a GPS track rather than driving —
`tools/mocklocation/`.

To verify a downloaded release APK's signature:

```bash
apksigner verify --print-certs detour-<version>.apk
```

### The shared core, without a Mac

Run both before opening a PR that touches `shared/`:

```bash
./gradlew :shared:compileCommonMainKotlinMetadata   # no java.* leaked into commonMain
./gradlew :shared:testDebugUnitTest                 # shared tests
```

The first is the one that catches the common mistake. `commonMain` compiles fine
against the Android target with a stray `java.util.Calendar` import and then
fails only on the iOS targets, which you cannot build on Linux; the metadata
compilation type-checks against the common intersection and fails immediately
instead. CI runs the same tests again as `:shared:iosSimulatorArm64Test` on
Kotlin/Native, where they can fail differently.

### iOS, on a Mac

```bash
brew install xcodegen
cd iosApp && xcodegen && open Detour.xcodeproj
```

The Xcode project is generated from `iosApp/project.yml` and is not committed —
edit `project.yml`, never the `.xcodeproj`. A pre-build phase runs
`:shared:packForXcode`, so editing Kotlin and pressing Run rebuilds both halves.
`Config.example.xcconfig` is the iOS equivalent of `local.properties`; copy it to
`Config.xcconfig` to bake in default endpoints.

Without a Mac, push the branch and let the *iOS* workflow build it on `macos-15`
— it uploads a simulator app, an unsigned `.ipa` and a screenshot of the app
running. See [IOS_PORT.md](IOS_PORT.md).

## Building the backend

One solution, so there is nothing to pick.

```bash
dotnet build backend/Detour.slnx
dotnet test  backend/Detour.slnx --configuration Release
dotnet format style backend/Detour.slnx --severity info --verify-no-changes
```

There is a container too:

```bash
docker build -t detour-api backend
```

The context is `backend/`, not the repository root — the API references projects
across `Shared/` and nothing outside `backend/` is needed to build it. You rarely
need to: every push to `main` publishes `ghcr.io/detour-app/detour-api:latest`, and
a tag per commit sha for a deployment you can roll back.

### Conventions worth knowing before you edit

- **Failures are `Result`, not exceptions.** Domain methods return
  `Result`/`Result<T>` carrying a `ValidationKeys` entry; controllers call
  `ThrowIfFailure()` and the global handler renders a localised 400. Adding an
  error path means adding a key *and* its English string in
  `Detour.Api/Translations/Translations.en.resx`.
- **Column names are never written down.** snake_case is applied globally by
  convention, so an explicit `HasColumnName` is almost always a mistake. The
  exception is owned-type flattening, where a prefix avoids a collision.
- **Enums are `SmartEnum`, stored by name.** Reordering members must never
  silently remap existing rows.
- **Entities enforce their own invariants.** Private constructor, static `Create`
  returning `Result<T>`, one validation method shared between create and update,
  no public setters.
- **Caps live in `DetourLimits`.** They are behaviour, not tuning: each exists
  because without it one client can grow another's data without bound.
- **The middleware order in `Startup.Configure` is a security boundary.** Each
  step carries a comment saying what breaks if it moves.

What the service must *do* is [BACKEND_SPEC.md](BACKEND_SPEC.md); backend
comments cite it as `spec §11`, so its numbering is stable.

## Running the whole stack locally

Everything the backend talks to comes up in Docker; the backend itself runs from
your IDE or the command line.

```bash
docker compose -f docker/dev/docker-compose.yml up -d
cd backend/Detour/Detour.Api && dotnet run
```

That gives you the API on `http://localhost:7500`, applying its own migrations at
startup and answering `/api/health` with a per-dependency breakdown. OpenAPI is
at `/openapi/v1.json` in development only.

Ports are **canonical**. If one is taken, kill whatever holds it rather than
moving the service: Keycloak bakes its issuer and redirect URIs into the realm,
so renumbering it means wiping `keycloak-db` and re-importing. Detour owns the
74xx–75xx block, clear of the usual defaults, and everything binds to
`127.0.0.1` — the stack holds a Keycloak admin account and an unauthenticated
Grafana.

| Service | Port | Also at | What it is |
| --- | --- | --- | --- |
| Traefik | 7000 | dashboard on 7090 | One origin, so OIDC redirects and cookie scopes behave as they will in production |
| Grafana (LGTM) | 7440 | `http://grafana.localhost:7000` | Grafana, Loki, Tempo, Prometheus and an OTel collector in one container |
| OTLP gRPC / HTTP | 7441 / 7442 | | What the API exports traces and metrics to |
| Detour.Api | 7500 | `http://api.detour.localhost:7000` | Run from your IDE, not Docker |
| GraphHopper | 7510 | | Routing. Needs an OSM extract first — see below |
| Postgres (app) | 7532 | | |
| Redis | 7579 | | L2 cache behind FusionCache, and its backplane |
| Keycloak | 7580 | `http://idp.localhost:7000` | Realm `detour`, imported on first start |

Keycloak's admin console is at `http://localhost:7580/admin` (`admin` / `admin`)
and the dev rider is `rider` / `detour-dev`. Full detail, including how to get a
token by hand, is [../docker/dev/README.md](../docker/dev/README.md); why the
realm is shaped the way it is, is
[../docker/dev/config/keycloak/REALM.md](../docker/dev/config/keycloak/REALM.md).

**GraphHopper will not start without an OSM extract**, which is not in git — 662
MB of Belgium:

```bash
curl -L -o docker/dev/data/graphhopper/belgium-latest.osm.pbf \
  https://download.geofabrik.de/europe/belgium-latest.osm.pbf
```

First start then builds the graph and contraction hierarchies for both profiles:
minutes, and several GB of heap. That lands in a named volume, so later starts
are seconds — deleting the volume, or changing the extract or the profile set,
pays for it again. The profile **names** are load-bearing: the app sends `car`
and `moto` as the `profile` query parameter, and renaming either leaves every
routing request 400ing, which surfaces in the app as a null route rather than an
error you can see.

Photon is not in the dev stack. Search falls back to the public
`photon.komoot.io`, which is enough for development.

## Pointing a device at your stack

From an emulator, `adb reverse` the ports rather than using `10.0.2.2`: the realm
hands out absolute URLs built from the hostname it was started with, so the
device has to know it by the same name the API does.

```bash
adb reverse tcp:7500 tcp:7500   # API
adb reverse tcp:7580 tcp:7580   # Keycloak
adb reverse tcp:7510 tcp:7510   # GraphHopper
```

Then fill in Settings → Servers & sync. Note that the app's own address fields
outrank the baked-in build defaults, so setting only a build property is not
enough if the field is filled in.

## Baking addresses into a build

Optional — everything can be typed into Settings at runtime, on both platforms.
Bake them in when you'd rather ship a build that already knows its server:

```properties
# local.properties
api.url=https://api.example.com
idp.issuer=https://idp.example.com/realms/detour
routing.url=https://routing.example.com
geocoder.url=https://search.example.com
```

`server.url` covers routing and the geocoder together when one path-routed
hostname fronts both; the per-service keys override it where set. The API needs
`api.url` of its own, because `/api` is already the geocoder's path in that
layout. See the `routingCfg()` helper in `app/build.gradle.kts` for every
property and env-var name it reads.

The published APKs are built by CI with none of these set, deliberately.

## Testing

```bash
./gradlew :shared:testDebugUnitTest             # shared core
dotnet test backend/Detour/Detour.Domain.Tests  # pure domain rules
dotnet test backend/Detour/Detour.InfraTests    # the API against a real Postgres
```

`Detour.Domain.Tests` is plain xUnit and runs in milliseconds. `Detour.InfraTests`
starts a real Postgres via Testcontainers — the InMemory provider cannot
reproduce citext comparison, jsonb columns, snake_case naming or a unique-index
violation, which is most of what those tests are for.

## What CI runs

| Workflow | On | Does |
| --- | --- | --- |
| build | every change | Builds the release APK and bundle. A red build blocks review. On `main`, also signs them, publishes a GitHub release tagged with `versionName`, and uploads to Play's internal track. |
| ios | changes to `shared/` or `iosApp/` | Type-checks `commonMain`, runs shared tests on the JVM and on Kotlin/Native, builds and boots the app in a simulator. |
| backend | changes to `backend/` | Both test suites, `dotnet format style`, and a check that a schema change committed its migration. Publishes the container image on `main`. |

## Document index

[docs/README.md](README.md) is the full index. The ones you reach for most:

| Document | Read it when |
| --- | --- |
| [guidelines/](guidelines/README.md) | Writing or reviewing any Kotlin. Section numbers are what a review cites. |
| [BACKEND_SPEC.md](BACKEND_SPEC.md) | Changing the server. Behaviour and rules, no code. |
| [CIRCLES_AND_CONVOYS.md](CIRCLES_AND_CONVOYS.md) | Touching groups or the live socket. Has the wire format of every relay frame. |
| [PUSH.md](PUSH.md) | Touching the circle wake-ping, FCM or APNs. |
| [IOS_PORT.md](IOS_PORT.md) | Working on iOS, or wondering why a behaviour differs between platforms. |
| [ANDROID_AUTO.md](ANDROID_AUTO.md) | Working on the car screen. |
| [DEBUG_INTENTS.md](DEBUG_INTENTS.md) | Exercising behaviour that would otherwise mean going for a drive. |
| [RELEASING.md](RELEASING.md) | Cutting a release, or a Play upload was rejected. |
| [../backend/INSTALL.md](../backend/INSTALL.md) | Standing the server up somewhere real. |

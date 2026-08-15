---
name: detour-shared-core
description: >-
  Decide which of Detour's surfaces a piece of logic belongs to — shared/ (Kotlin
  Multiplatform commonMain), app/, app/.../car/ (Android Auto), wear/ or iosApp/ — and write
  it correctly once it is there. Use this before adding any new logic, before extracting or
  moving logic between modules, when a change would otherwise land only in app/, when you are
  about to write a second copy of a rule that already exists on another surface, when you
  notice two surfaces have drifted, when proposing an interface or an expect declaration, and
  when deciding where a test is worth putting. It carries the module dependency graph as it
  actually is (wear/ does NOT depend on shared/), commonMain's verified constraints, the
  measured cross-surface duplication, and which CI job gates which source set.
---

# Where code belongs in Detour, and how to write it once it is there

`CONTRIBUTING.md:23-56` states the rule. **Read it — this skill does not restate it.** What
follows is how to apply it: the graph the rule operates on, the constraints that decide
whether "put it in `shared/`" is even possible, and the evidence for why the rule is worth
following when it is inconvenient.

## Preconditions

These are the claims this file makes about the tree. If one fails, that section is stale —
re-derive it before trusting the body.

```sh
.claude/skills/detour-shared-core/scripts/check-preconditions.sh
```

Seven assertions, `PASS`/`FAIL` per line, non-zero exit if any failed: the four `expect`s all
in `Platform.kt`, **zero** `Dispatchers` in `commonMain`, **exactly one** non-sealed
`interface` there (`Prefs`, pinned to `Platform.kt` so a second one is still caught), `wear/`
still **not** depending on `:shared`, `app/` still depending on it, and `nowMs()` still in
`Angles.kt`. Two of those are inverted-to-zero assertions, which is why they are worth running
rather than eyeballing — a grep that prints nothing looks the same whether the claim holds or
the path was mistyped. The script reports `nowMs()`'s current line rather than asserting it,
because line drift is not staleness.

## 1. The surfaces, and which ones `shared/` actually reaches

`settings.gradle.kts:16,17,20` declares **three** Gradle modules: `:app`, `:wear`, `:shared`.
Everything else is either a separate build or not a Gradle project at all.

| Surface | Path | Depends on `:shared`? | Verified at |
|---|---|---|---|
| Phone app | `app/src/main/java/com/jellemax/detour/` | **yes** | `app/build.gradle.kts:135` |
| Android Auto | `app/src/main/java/com/jellemax/detour/car/` | yes — **same Gradle module as the phone** | not a module boundary |
| Wear OS | `wear/src/main/java/com/jellemax/detour/wear/` | **NO** | `wear/build.gradle.kts` dependency block lists six androidx/GMS artifacts and no project |
| iOS | `iosApp/Detour/` (SwiftUI) | yes, as a static framework | `iosApp/project.yml` `FRAMEWORK_SEARCH_PATHS` + `OTHER_LDFLAGS: -framework DetourShared`, built by the `packForXcode` preBuild script |
| .NET backend | `backend/` (18 `.csproj`) | no — HTTP only | `backend/README.md:3` calls it "The .NET replacement for `server/sync/sync_server.py`" |
| Legacy sync server | `server/sync/sync_server.py` | no — **superseded by `backend/`** | same line |

Two consequences that change decisions:

- **"Shared" means phone + Android Auto + iOS. It does not mean Wear.** Putting logic in
  `shared/` does not give `wear/` access to it; `wear/` would need a new Gradle edge first.
  If a task says "share this with the watch", that is a build-file change, not a move.
- **`app/` ↔ `car/` needs no port and no `shared/` move at all.** They are the same module and
  the same package root. De-duplicating between them is a plain extraction into a new file
  under `app/`. Do not reach for `shared/` (or an interface) to solve an `app/`↔`car/` copy.

Measured today (whole-file line counts, `find … | xargs cat | wc -l`):
`shared/src/commonMain` 4,927 lines in 36 files · `app/src/main/java` 16,940 in 56 ·
`iosApp/Detour/*.swift` 5,078 in 25 · `wear/src` 185.

> Note: `docs/refactor/mapscreen/13-surface-independence-audit.md` reports `app/` as 45 files
> / 16,705 lines. That was before the MapScreen mechanical split added eleven files under
> `app/.../ui/`. **Its aggregate duplication figures still hold; its `ui/MapScreen.kt:NNNN`
> citations do not** — that file is now 1,549 lines and several cited line numbers are past
> its end. Re-derive any `MapScreen.kt` line reference with `grep -n` before quoting it.

## 2. The placement decision

`CONTRIBUTING.md` gives the two tests. The operational form, in the order to apply them:

1. **Is it written more than once already?** Then it earns the core (or, for `app/`↔`car/`, a
   shared file under `app/`). One copy plus a comment naming the other copy is not a second
   implementation — but it is the state that *becomes* one, so count copies, not intentions.
2. **Does the proposed abstraction have more than one implementation?** If not, do not create
   the interface or the `expect`. One implementation behind an interface is indirection, not a
   boundary. `commonMain` has **one** interface (`Prefs`, CONTRIBUTING.md:40 — three
   implementations) and 33 `object` singletons; that is the house pattern, and adding a second
   interface needs an argument of its own.
3. **New logic with no second copy yet** — `CONTRIBUTING.md:31-32` sends it to `shared/`
   unless it genuinely cannot go there. §3 and §4 below are the "genuinely cannot" list.
4. **If it cannot go in `shared/`, say so in a comment at the call site, naming what blocks
   it.** The house comment style (`CONTRIBUTING.md:177-189`) is why-not-what; "stays here
   because it needs `withFrameNanos`" is the kind of comment that stops the next person
   re-litigating the decision.

## 3. The `expect` ceiling

`shared/src/commonMain/` contains **exactly four `expect` declarations, all in one file**,
`shared/src/commonMain/kotlin/com/jellemax/detour/data/Platform.kt`:

- `expect fun prefs(name: String): Prefs` (`:48`) — opens the named bag of primitives; `Prefs`
  itself is an `interface` (`:32`), not an `expect`, because it has more than one implementation
  per platform — see §4's Interfaces / DI row
- `expect fun securePrefs(): Prefs` (`:58`) — the one encrypted bag, for credentials; no name
  parameter, because there is exactly one of these and a name would be a second way to say so
- `expect fun appFilesDir(): Path` (`:61`)
- `expect val fileSystem: FileSystem` (`:64`)

**Four declarations, still three concerns**, and the difference is what stops this reading as
drift. `CONTRIBUTING.md:26-28` says `Platform.kt` "expects only three things — a key-value
store, a files directory and a file system — so wanting to add a fourth is the signal to push
the dependency in". That is still exactly true: `securePrefs` is a second *bag* of the
existing key-value concern, not a fourth concern. Count concerns against that rule, not
declarations, or the next credential store looks like a ceiling breach when it is not — and a
genuinely new concern looks permissible when it is not.

Three concerns: a key-value store (plain, and encrypted for credentials), an app-private
directory, a file system. That is the whole platform surface of the core. `Platform.kt:11-14`
states the rule in the file itself, and `CONTRIBUTING.md:26-28` repeats it: **wanting to add a
fourth concern is the signal to push the dependency in from the platform instead** — a new
`expect` for an existing concern (another bag of key-value pairs, say) is not that signal.

What "push it in" means concretely, with the pattern already in the tree:

- The core takes the data as a **parameter or a suspend argument**, and the platform calls it.
  There is no `Fix` type, no location interface and no `expect` for location anywhere in
  `commonMain`; each platform reads its own GPS and calls shared functions with the
  coordinates. Same for audio, Bluetooth and notifications.
- So instead of `expect fun currentLocation(): LatLon`, write
  `fun somethingAboutAPosition(at: LatLon, …)` and let `app/` and `iosApp/` supply `at`.
- The test for whether you have done it right: the new function is callable from
  `commonTest` with literal arguments and no fake.

The cost of this rule is real and is §5. Do not reverse it as a side effect of some other
change; `Platform.kt:11-14` is a documented decision and reversing it is its own argument.

## 4. What `commonMain` actually has — verified, not assumed

| Concern | Status in `commonMain` | What to do instead |
|---|---|---|
| `Dispatchers.*` / `withContext` | **Zero occurrences.** Not available: the module has androidTarget + iOS targets and no jvm∩native intermediate source set | Make the function `suspend` and let the caller pick the dispatcher. Every network API already does this (`RoadRoulette`, `SpeedCameras`, `Geocoder`, `CircleFixes`) |
| Wall clock | **Exists.** `internal fun nowMs(): Long` at `shared/src/commonMain/kotlin/com/jellemax/detour/data/Angles.kt:16`, over `kotlinx.datetime.Clock.System` | See the warning below |
| `kotlin.random.Random` | Available (common stdlib), used at `RoadRoulette.kt`, `PoiRoulette.kt`, `RoundTripPlanner.kt` | no obstacle |
| `kotlin.math` | Available; `Angles.kt:11,13` supplies the two `java.lang.Math` degree/radian converters common Kotlin lacks | no obstacle |
| JSON | kotlinx-serialization plus `Json.kt`'s lenient `org.json`-shaped shim | `org.json.JSONObject` (still used in `app/net/` and `wear/`) must be ported to `Json.kt` before that code can move |
| File I/O | okio, via `Files.kt` over `expect val fileSystem` | works; the strongest seam in the repo, and `Platform.kt:46` notes it takes a fake in tests |
| HTTP | `internal object Http` — a concrete Ktor client, engine chosen per target in `shared/build.gradle.kts` | not injectable and not fakeable from `commonTest`; test the parsing, not the fetch |
| Logging | **Zero.** No logger, no `println` | a move out of `app/` drops its `android.util.Log` calls; there is no port to keep them |
| Interfaces / DI | **One interface (`Prefs`), 33 `object` singletons** | `Prefs` earned it under CONTRIBUTING.md:40 — three implementations (plain Android, Keystore-encrypted Android, plain iOS). Everything with one implementation is still an `object`; see §2 test 2 |
| Frame clock | none, and none possible | `withFrameNanos` cannot move. Animation loops stay in Compose |
| Android/Apple types | none | `Context`, `Intent`, `LatLng`, `MapLibreMap`, `ToneGenerator`, `AudioManager`, `MotionEvent`, `ViewConfiguration` are hard stops |

### The wall clock: an earlier analysis in this repo got this wrong

**`commonMain` does have a wall clock.** `nowMs()` exists at `Angles.kt:16` and is called
directly from `Badges.kt`, `SavedPlaces.kt` and `RouteShare.kt`. An earlier report in this
repo asserted commonMain had no clock and that time was therefore a blocker for moving logic
in. That was false, and it is an easy mistake to repeat because *nothing named `Clock` or
`Instant` appears in the call sites* — `nowMs()` is a bare `internal` free function in a file
called `Angles.kt`, which is the last place anyone greps.

Two real facts about it, which are the ones that matter:

1. **It is `internal`.** Code outside the `:shared` module cannot call it. Logic being moved
   *into* `shared/` can use it; logic staying in `app/` cannot, and widening it is a change
   that needs its own justification.
2. **Prefer a timestamp parameter anyway.** Not because the clock is missing, but because
   path-dependent logic that reads the clock itself cannot be tested deterministically: a
   state machine whose output depends on *when* it was called has no reproducible test. The
   two pieces of `commonMain` that are testable by clock got there by taking time as an
   argument — `GeofenceEvaluator` in `CircleEvents.kt`, and
   `RouteGpx.parseGpx(text, nowMs)`, which `RoutesTest.kt` calls as
   `RouteGpx.parseGpx(gpx, nowMs = 999L)`. Copy that shape. Reserve bare `nowMs()` for
   stamping a record you are writing, where the value does not steer a later decision.

### The iOS `StateFlow` interop cost

Swift cannot start a coroutine, and Kotlin/Native erases a generic's type argument on the way
to Objective-C, so a `StateFlow<Boolean>` reaches Swift as a boxed `KotlinBoolean`.
`shared/src/iosMain/kotlin/com/jellemax/detour/data/FlowWatcher.kt` solves this with an
`abstract class Watcher` (`:25`) plus **nine** concrete subclasses, one per element type
(`BoolWatcher`, `FloatWatcher`, `IntWatcher`, `StringWatcher`, `TravelModeWatcher`,
`RouteColorWatcher`, `SavedPlacesWatcher`, `SavedRoutesWatcher`, `TracesWatcher`).

So: a new core `StateFlow` is free for iOS if its element type already has a watcher, and
costs a new `iosMain` subclass if it does not. Check `FlowWatcher.kt` before adding a
`StateFlow<SomeNewType>` to `commonMain` — and prefer reusing an existing element type over
introducing one that needs an eleventh class.

## 5. The duplication map — why the rule is worth the inconvenience

Measured in `docs/refactor/mapscreen/13-surface-independence-audit.md` §3 (per-item citations
there; the totals are what matter here).

| Pair | Duplicated logic | Share of the smaller surface |
|---|---|---|
| Phone ↔ iOS | **≈1,150 Kotlin / ≈1,070 Swift lines**, 10 items | ≈21% of the whole iOS app, ≈55% of its non-UI code |
| Phone ↔ Android Auto | **≈199 / ≈186 lines**, 11 items | ≈10% of `car/` |
| Phone ↔ Wear | **≈45 lines**, 2 items | 24% of the 185-line `wear/` module |
| Android Auto ↔ iOS, Wear ↔ iOS | 0 | no CarPlay or watchOS target |
| **Total** | **≈1,300 duplicated lines**, ≈11% of client logic | |

The largest single item, verified: `iosApp/Detour/TripRecorder.swift` is a function-for-function
parallel of `app/.../tracking/TripTrackingService.kt`, with **nineteen tuning constants copied
verbatim** at `TripRecorder.swift:41-60` against `TripTrackingService.kt:141-202`. The Swift
header comment at `TripRecorder.swift:39` says so outright: *"Auto-detection thresholds
(identical to the Android service)"*.

And the four-copy case `CONTRIBUTING.md:46-52` already names — the GraphHopper sign table — is
still four copies today, three of them byte-identical bodies:

- `app/src/main/java/com/jellemax/detour/ui/Navigation.kt:57-71` `signIcon`
- `wear/src/main/java/com/jellemax/detour/wear/MainActivity.kt:53-67` `signIcon` (identical)
- `app/src/main/java/com/jellemax/detour/car/NavScreen.kt:575-593` `maneuverType` (same
  sign codes, mapped to `Maneuver.TYPE_*` instead of an icon)
- `iosApp/Detour/NavScreen.swift:232-248` `maneuverIcon`

Also still duplicated: `smoothBearing` at `app/.../ui/MapCameraTuning.kt:10` and
`app/.../car/CarMapRenderer.kt:470`; `fetchLocation` at `app/.../ui/MapScreen.kt:425`,
`app/.../car/SpinScreen.kt:298` and `app/.../car/SearchScreen.kt:164`.

**The pattern the audit found is the useful part**: every feature that reached iOS is one whose
logic sits in `shared/` (`NavEngine`, `SpinPicker`, `Badges`, `Coverage`, `Geocoder`,
`RouteStore`, `GeofenceEvaluator`). Every feature that did not is one whose logic sits inside a
`@Composable` or an Android `Service` — the speed-camera warnings and the ambient speed-limit
sign are absent from iOS *even though* `SpeedCameras.kt` and `RoadRoulette.snapSpeedLimitKmh`
are already in `commonMain` and callable from Swift. **Statefulness, not domain relevance, is
what currently decides whether a feature ships on iOS.** When you write a stateful machine
inside a composable, that is the decision you are making.

## 6. Extract from the better copy, not the nearest one

When two surfaces have diverged, **the phone is not automatically the source of truth.** Diff
the copies before choosing, and extract from whichever one is better — otherwise you promote a
known defect into shared code, where it becomes everyone's.

The documented case, verified in the tree today: the Overpass prefetch machines.

| | Phone | Android Auto |
|---|---|---|
| Speed limit | `app/.../ui/MapScreen.kt:735-767` | `app/.../car/SpinScreen.kt:265-296` |
| Speed cameras | `app/.../ui/MapScreen.kt:773-794` | `app/.../car/NavScreen.kt:378-417` |
| Fetch placement | `withContext(Dispatchers.IO) { … }` **inline inside the `TripTrackingService.lastFix.collect { }` block** | its own job: `limitFetchJob = lifecycleScope.launch { … }` / `cameraFetchJob = lifecycleScope.launch { … }` |
| Re-entry guard | none | `limitFetchJob?.isActive != true` (`SpinScreen.kt:272`), `cameraFetchJob?.isActive != true` (`NavScreen.kt:383`) |
| Thresholds | inline literals `500.0`, `10_000`, `3`, `2.0`, `1000.0`, `15_000` | named: `LIMIT_FETCH_MARGIN_M`, `LIMIT_FETCH_THROTTLE_MS`, `LIMIT_MISSES_TO_CLEAR`, `LIMIT_MIN_MPS`, `CAMERA_FETCH_MARGIN_M`, `CAMERA_FETCH_THROTTLE_MS` (`SpinScreen.kt:52-61`, `NavScreen.kt:56-57`) |

The values agree on both sides; the **structure** does not, and the car side is strictly
better. `car/NavScreen.kt:365-377` explains why in its own KDoc: `lastFix` is a *conflating*
`StateFlow` and its collector is sequential, so awaiting a slow Overpass mirror inline
suspended the whole fix loop — camera, HUD and turn card — while every fix that landed
meanwhile was conflated away. `car/SpinScreen.kt:263` says *"Same fix as
[NavScreen.checkCameras]"*. The phone has neither fix.

So: **extracting the phone copy of either machine would port a stall into `shared/`.** Before
extracting anything that exists twice —

```sh
# Read both copies side by side and diff the structure, not just the constants.
grep -n '<the function or effect name>' app/src/main/java/com/jellemax/detour/ui/*.kt \
                                        app/src/main/java/com/jellemax/detour/car/*.kt
```

— and look specifically for: which copy has named constants, which has a re-entry or
in-flight guard, which one does I/O off the collector, and which one has a comment explaining
a bug it already fixed. A comment that says "same fix as X" is a signal that *X's* version is
the one to keep.

## 7. Which test source set CI actually gates

Three workflows. Getting this right decides where a test is worth putting.

| Workflow | Trigger | Runs Kotlin tests? |
|---|---|---|
| `.github/workflows/build.yml` | push to `main`, **every** pull request, manual — **not path-gated** | **Yes**: `:app:testDebugUnitTest :shared:testDebugUnitTest` (`:117-118`), before the assemble step |
| `.github/workflows/ios.yml` | push to `main`/`ios` and PRs, **path-gated on `shared/**` and `iosApp/**`** (`:11-20`) | **Yes, and more**: `:shared:compileCommonMainKotlinMetadata` (`:59`), `:shared:testDebugUnitTest` (`:65`), `:shared:iosSimulatorArm64Test` (`:68`) |
| `.github/workflows/backend.yml` | path-gated on `backend/**` | **No Kotlin test at all** — .NET only (`dotnet test` on `Detour.Domain.Tests` and `Detour.InfraTests`) |

What follows from that:

- **A test in `shared/src/commonTest/` is the best-protected test in the repo.** It runs on
  every PR via `build.yml`, and again on both the JVM and Kotlin/Native via `ios.yml` when
  `shared/**` changed. The Native pass is the one that catches a JVM-only assumption.
- A test in `app/src/test/` runs on every PR via `build.yml` too — the path-gating is on
  `ios.yml`, not on `build.yml`. Do not skip an `app/` test on the belief that CI ignores it.
- **`:shared:compileCommonMainKotlinMetadata` is path-gated.** It is the check that catches
  `java.*` leaking into `commonMain` (`CONTRIBUTING.md:81-85`), and it only runs when
  `shared/**` or `iosApp/**` changed. That is fine, because that is exactly when it matters —
  but if you edit `commonMain` locally, run it yourself: `commonMain` compiles happily against
  the Android target with a stray `java.util.Calendar` and fails only on the iOS targets.
- `iosApp/` has **no test target** in `project.yml`. Swift logic is untested by construction,
  which is a further argument for moving a rule into `commonMain` rather than into Swift.

Before opening a PR that touches `shared/`:

```sh
./gradlew :shared:compileCommonMainKotlinMetadata
./gradlew :shared:testDebugUnitTest
```

## 8. `shared/src/commonTest/` house style

Three files, 60 `@Test` — `GroupsTest.kt` (22), `ParsingTest.kt` (24), `RoutesTest.kt` (14) —
all in `shared/src/commonTest/kotlin/com/jellemax/detour/data/`. Plus one Android-only test in
`shared/src/androidUnitTest/` (`RouteStoreLoadOrderTest.kt`). Match what is there:

- **Plain `kotlin.test`**: `import kotlin.test.Test`, `assertEquals`, `assertTrue`,
  `assertNull`, `assertNotNull`, `assertFalse`. No mocking library, no test framework beyond
  that, no coroutine test dispatcher.
- **A class per subject, named after it** (`RoutesTest` for `Routes.kt` + `RouteGpx.kt`), with
  a KDoc on the class saying what contract it covers and why — e.g. RoutesTest's *"the GPX
  import/export in RouteGpx.kt (which has to tolerate files this app didn't write)"*.
- **A private builder function for the fixture** (`private fun route() = SavedRoute(…)`) with
  realistic values, and `.copy(…)` per test for the variation under test. Not a `@Before`
  field.
- **Test names are full sentences in camelCase**, stating the property, not the method:
  `fewerThanTwoStopsIsRejected`, `parseGpxAcceptsATrkOnlyFileWithSingleQuotesAndReversedAttributeOrder`,
  `coordinateFormattingNearThePrimeMeridianIsNeverScientificNotation`. A reader should learn
  the rule from the test list alone.
- **Time and randomness are arguments, never ambient**: `RouteGpx.parseGpx(gpx, nowMs = 999L)`.
- **Doubles are compared with `absoluteTolerance`** (`1e-6` for coordinates, `1e-9` where
  exactness is the point), never with bare `assertEquals`.
- **A comment above the awkward assertion explaining why it is the assertion** — the why-not-what
  style from `CONTRIBUTING.md:177-189` applies in tests too:
  `// Two numbers per point, not one object per point.`
- **Regression tests carry the observed symptom**, so the test explains the bug: the
  scientific-notation test asserts `lon="0.0000100"` and adds the whole document to the failure
  message, because `1.0E-5` is what no GPX reader parses.
- No file access. These run on JVM and Kotlin/Native both, so anything needing
  `expect val fileSystem` belongs in `androidUnitTest` (which is what
  `RouteStoreLoadOrderTest.kt` is for) or must take its input as a string.

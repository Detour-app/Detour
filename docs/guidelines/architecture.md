# Modules, source sets and layers

*Part of the [Detour Kotlin guidelines](README.md). Section numbers are stable across files — a reference like §8.4 always means §8.4, wherever it lives.*

## 1. Module layout

```
:app        Android phone app + Android Auto. UI and platform services only.
:shared     KMP core. Roulette, routing, trips, badges, sync, social, presentation.
```

Not Gradle modules, but surfaces that consume `:shared`:

```
iosApp/     SwiftUI app. Links DetourShared.framework (static).
backend/    .NET. Talks HTTP only — never links Kotlin.
```

`app/…/car/` (Android Auto) is **the same Gradle module and the same package
root** as the phone. De-duplicating between `ui/` and `car/` is a plain
extraction into a new file under `app/` — it needs no `shared/` move and no
interface. Do not reach for the core to fix an `app/`↔`car/` copy.

A feature is never promoted to its own module. Two modules is the layout; if
you think you need a third, that is a design discussion, not a refactor.

## 2. Source sets — where platform code is allowed

```
shared/src/
├── commonMain/     domain + data + presentation. The default home for new logic.
├── androidMain/    actual impls only (KeystorePrefs, SecretBox, Platform.android)
├── iosMain/         actual impls + the Swift-facing Watcher classes
├── commonTest/     runs on JVM *and* Kotlin/Native. Best-protected tests in the repo.
└── androidUnitTest/ tests that need a real filesystem
```

```
app/src/main/java/com/jellemax/detour/
├── ui/         Compose phone screens                      (Android only, by nature)
├── car/        Android Auto screens                        (Android only, by nature)
├── tracking/   foreground service, sensors, location plumbing
├── net/ ble/ obd2/ audio/ media/ notif/ auth/ update/ perf/  platform ports
├── map/ nav/   Android-side policy that has not moved yet   ← audit these
└── data/       Android-side data that has not moved yet     ← audit these
```

Rule of thumb, unchanged from `docs/DEVELOPERS.md`: **if a new non-UI file is not
in `commonMain`, you must be able to name what blocks it, in a comment, at the
call site.** "Needs `Context`", "needs `withFrameNanos`", "needs
`MotionEvent`" are reasons. "It was easier here" is not.

Hard stops that genuinely cannot move: `Context`, `Intent`, `LatLng`,
`MapLibreMap`, `MotionEvent`, `ViewConfiguration`, `ToneGenerator`,
`AudioManager`, `withFrameNanos`, `android.util.Log`, and anything under
`androidx.*`.

### 2.1 The `commonMain` constraint list

Check this before deciding something "can't be shared":

| Concern | In `commonMain`? | What to do |
| --- | --- | --- |
| `Dispatchers.*` / `withContext` | **No — zero occurrences, and none possible** | make the function `suspend`; the caller picks the dispatcher |
| Wall clock | **Yes** — `internal fun nowMs()` in `data/Angles.kt:16` | prefer a `nowMs: Long` **parameter** anyway; a machine that reads the clock itself is untestable |
| `kotlin.random.Random`, `kotlin.math` | yes | no obstacle |
| JSON | kotlinx-serialization + the lenient shim in `data/Json.kt` | `org.json.JSONObject` (still in `app/net/`) must be ported before that code moves |
| File I/O | okio via `data/Files.kt` over `expect val fileSystem` | strongest seam in the repo; takes a fake in tests |
| HTTP | `internal object Http`, engine per target | not injectable — test the parsing, not the fetch |
| Logging | **none, and no port** | a move out of `app/` drops its `Log` calls |
| Frame clock | none, none possible | animation loops stay in Compose |

## 3. Layer anatomy — what each package is for

Detour's layers are packages inside `:shared`, not folders inside a feature.

```
shared/src/commonMain/kotlin/com/jellemax/detour/
├── data/           models, stores, HTTP clients, persistence, pure rules
├── drive/          stateful machines fed one GPS fix at a time
└── presentation/   *State.kt (display shapes + pure mappers), *Presenter.kt
```

**`data/`** — 64 top-level `object` singletons and their data classes. A store
owns persisted or fetched state and publishes it. A "rule" file
(`CircleNotifyPolicy`, `HighwayClass`, `SpinPicker`) is pure functions with no
state at all.

**`drive/`** — the machines that consume a fix stream: `SpeedLimitTracker`,
`SectionAverageTracker`, `StopDetector`, `CameraWarner`, `HardEventDetector`,
`RoadTypeTracker`, `TripFixMath`. **This is where a stateful driving machine
belongs — not inside a `@Composable`, and not inside a `Service`.** Every one
of them takes its inputs as arguments and returns a new state; none reads a
clock, a sensor or a network of its own.

**`presentation/`** — two kinds of file:

- `XState.kt` — the display shape (`data class`, all defaults) **plus** the
  pure mapper `xStateFrom(...)`. Fourteen of these exist; copy the nearest one.
- `XPresenter.kt` — a thin class that kicks off loads. It publishes rows of its
  own **only when there is no mutable store underneath it**. When a store
  already publishes state, the screen collects the store and calls the mapper
  on the render path — see `FriendsPresenter`'s KDoc for the full argument.

The mapper is the load-bearing part. It is `fun xStateFrom(raw…): XState`, no
I/O, no clock, callable from `commonTest` with literals. If your new display
logic cannot be written that way, it is not display logic.

## 9. Template for new work

New logic, no second copy yet:

```
shared/src/commonMain/kotlin/com/jellemax/detour/
├── data/<Thing>.kt            object store or pure rule + its data classes
├── drive/<Thing>Tracker.kt    if it steps per GPS fix
└── presentation/<Thing>State.kt   data class + fun <thing>StateFrom(...)

shared/src/commonTest/kotlin/com/jellemax/detour/…/<Thing>Test.kt

app/src/main/java/com/jellemax/detour/ui/<Thing>Screen.kt   draws it
```

Then, before opening the PR:

```bash
./gradlew :shared:compileCommonMainKotlinMetadata   # catches java.* in commonMain
./gradlew :shared:testDebugUnitTest
```

The first is the one that matters: `commonMain` compiles happily against the
Android target with a stray `java.util.Calendar` and fails only on the iOS
targets, which most contributors cannot build.

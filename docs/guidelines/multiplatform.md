# Multiplatform constraints

*Part of the [Detour Kotlin guidelines](README.md). Section numbers are stable across files — a reference like §8.4 always means §8.4, wherever it lives.*

## 5. The UI layer

Detour is "native UI per platform" for every screen — three UIs, one core.
There is no shared-Compose option to choose, so the per-feature strategy
question does not arise. What does arise is **how much logic each UI is
allowed to hold**, and the answer is: only what it draws.

```
app/…/ui/XScreen.kt   collects store StateFlows, calls xStateFrom(...), draws
app/…/car/XScreen.kt  same core, Car App Library templates
iosApp/XScreen.swift  same core, observes a Watcher, draws SwiftUI
```

A screen may: collect flows, hold ephemeral UI state, call store suspend
actions from `rememberCoroutineScope()`, and render. A screen may not: contain
a threshold constant that another surface also needs, own a state machine, or
be the only place a rule exists.

### 5.1 `expect`/`actual` — the ceiling

`commonMain` declares **six `expect` declarations, all in
`data/Platform.kt`**, covering four concerns:

| Concern | Declarations |
| --- | --- |
| key-value store | `prefs(name)`, `securePrefs()` |
| app-private files | `appFilesDir()`, `val fileSystem` |
| cross-thread lock | `class PlatformLock` |
| locale decimal separator | `systemDecimalSeparator()` |

`Platform.kt:11-14` states the rule in the file itself: **wanting a new
*concern* is the signal to push the dependency in from the platform instead.**
A second bag of key-value pairs is not a new concern; a location API, an audio
API or a notification API would be — and each of those is deliberately absent.
Instead of `expect fun currentLocation(): LatLon`, write
`fun somethingAboutAPosition(at: LatLon, …)` and let each platform pass `at`.

The test that you did it right: the new function is callable from `commonTest`
with literal arguments and no fake.

### 5.2 Interfaces are rationed

`commonMain` has **three**: `Prefs`, `RelaySocket`, and the `fun interface`
`BearerSource`. Each has more than one real implementation. Everything else
with one implementation is an `object`.

> A port earns an interface when it has more than one implementation.
> — docs/DEVELOPERS.md § "Where code belongs"

Adding a fourth needs an argument in its KDoc, not just a preference for
indirection.

### 5.3 The iOS `StateFlow` tax

Swift cannot start a coroutine, and Kotlin/Native erases generics on the way
to Objective-C, so `StateFlow<Boolean>` reaches Swift as a boxed
`KotlinBoolean`. `shared/src/iosMain/…/FlowWatcher.kt` fixes this with an
`abstract class Watcher` plus **one concrete subclass per element type**
(~24 today).

Consequence: a new `StateFlow` in `commonMain` is free for iOS if its element
type already has a watcher, and costs a new `iosMain` subclass if it does not.
Check `FlowWatcher.kt` before introducing a new flow element type, and prefer
reusing one that already has a watcher.

## 6. Dependency wiring (there is no DI container)

Composition is by direct reference to `object` singletons, with three seams:

1. `expect`/`actual` in `Platform.kt` for the four platform concerns.
2. The three interfaces of §5.2, constructed by the platform and handed in.
3. **Parameters.** Everything else. Location fixes, audio, Bluetooth,
   notifications and the clock are pushed *into* the core by whichever platform
   is running it.

Do not introduce Koin, Hilt, Dagger or a service locator to solve a testing
problem. The testing problem is solved by making the function take its inputs.

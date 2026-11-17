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

### 5.4 Surfacing errors

Every failure a rider should hear about goes to exactly one of four places,
picked by what failed — not by where the code happened to have a `Text`:

| What failed | Where it shows | How |
| --- | --- | --- |
| **Validation** — the input is wrong | inline, under the field | the field's `supportingText` / `isError` |
| **An action** — a tap that ran and failed | the app snackbar | `LocalAppMessages.current.showError(message, retry)`; Retry only where re-running the same call is safe (a load, a sync, a send — never a delete) |
| **A load** — the content never arrived | in place of the content | `ErrorState(message, onRetry)`, not a blank list or an endless spinner |
| **The app is not in front** | a system notification | the existing `notif/` channels |

Success with news ("Synced", "Not asked: …") is `showNotice`, never `showError`.
`LocalAppMessages` is `AppRoot`'s host on `AppRoot`'s scope, so a message sent
as its screen pops still shows; a `retry` runs on that scope too, after the
screen may be gone — it must not launch on the screen's own scope.

Three exceptions stay inline, each marked `// inline: <reason>` at the site:

- **Inside a `Dialog`/`AlertDialog`** — a snackbar draws under the dialog window.
- **Ambient state** — convoy live status is state, not an event; it has no
  moment to announce.
- **A map sheet already mirrored to the map snackbar** (`MapPermissions.kt`'s
  `LaunchedEffect(s.error)`) may keep its in-sheet copy for when the sheet is open.

**Never render an exception's own text.** `e.message` is written for a log, in
whatever words a parser or socket chose; the rider gets a sentence (`data/Http.kt`'s
`networkFailure` / `failureReason`, `presentation/ServersSyncState.kt`'s `failureText` & co.).

## 6. Dependency wiring (there is no DI container)

Composition is by direct reference to `object` singletons, with three seams:

1. `expect`/`actual` in `Platform.kt` for the four platform concerns.
2. The three interfaces of §5.2, constructed by the platform and handed in.
3. **Parameters.** Everything else. Location fixes, audio, Bluetooth,
   notifications and the clock are pushed *into* the core by whichever platform
   is running it.

Do not introduce Koin, Hilt, Dagger or a service locator to solve a testing
problem. The testing problem is solved by making the function take its inputs.

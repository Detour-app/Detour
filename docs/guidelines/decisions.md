# Standing decisions

*Part of the [Detour Kotlin guidelines](README.md). Section numbers are stable across files — a reference like §8.4 always means §8.4, wherever it lives.*

## 12. Why not Compose Multiplatform, Koin, or `ViewModel`?

Recorded so it is not re-litigated every six months. Two of these are settled;
one is open and §13 is its answer.

### 12.1 Koin — settled, do not add it

There is nothing to inject. `commonMain` is 64 `object` singletons and exactly
three interfaces, each with more than one real implementation. Testability here
comes from a stronger property than DI: **functions take their inputs**, so
`commonTest` calls them with literals and no fake at all —
`RouteGpx.parseGpx(text, nowMs = 999L)` needs no container.

A DI framework would add a graph to keep coherent across three surfaces and buy
nothing the parameter rule does not already give. Where a seam is genuinely
needed, use a default-argument constructor parameter (§13.3).

### 12.2 Compose Multiplatform — settled for now

Three things block it, in descending order of weight:

1. **Android Auto cannot render Compose.** `app/…/car/` uses the Car App
   Library's template system. CMP would collapse two of three UIs, not three —
   the car screens stay hand-written either way.
2. **The map is the app, and it is two different native SDKs.**
   `ui/MapLibreMap.kt` is ~846 lines of MapLibre *Android* bindings; iOS uses
   MapLibre iOS. CMP has no map component, so the largest screen would be
   `UIKitView`-wrapped native regardless — sharing the chrome around the part
   that actually matters.
3. **The iOS framework is deliberately small and static** (`isStatic = true`,
   one framework, no dylib copy phase). CMP changes that calculus.

The realistic ceiling on a CMP migration is "share the settings, list and detail
screens". Revisit only if Android Auto is dropped, or if the map moves behind a
shared abstraction.

### 12.3 `ViewModel` — this one is open, and §13 answers it

`androidx.lifecycle.ViewModel` **is** multiplatform, so "KMP forbids it" is not
the reason. `ui/RetainedMap.kt:45-46` argues only that `MapView` is the wrong
thing to put inside one — an argument against one use, not against the pattern.

What is used instead is process-global `object` stores. That works, and it suits
iOS (SwiftUI cannot consume `viewModelScope`; it observes a `Watcher`). But the
cost is real and visible in the tree:

- **Lifetime is hand-rolled.** `Auth.clear()` → `resetAccountScopedStores()`
  exists to do what a scoped owner would do for free, and every new store must
  remember to enlist.
- **Test seams are cut into production visibility.** `data/Routes.kt:113` and
  `data/SavedPlaces.kt:32` expose `internal val _routes` / `_places` — a literal
  breach of §4.1 — so a session-switch test can assert the reset mutated state.
- **Start-up ordering leaks into the platform surface.** `expect class
  PlatformLock` was added as a new `Platform.kt` concern because
  `CredentialMigration.migrateOnce` must *finish* before `Settings.init()` reads
  the secure store.

None of that is fatal. It is, however, the one of the three where the cost shows
up as extra machinery rather than as an avoided dependency.

# Kotlin architecture guide — Detour

**Scope:** every line of Kotlin in this repo — `shared/` (Kotlin Multiplatform
core), `app/` (Android phone + Android Auto), and the Kotlin that faces
`iosApp/`'s Swift. It is the repo-specific form of a generic KMP/Compose
Multiplatform guideline, adapted to what Detour actually is.

The one rule everything else serves is already in docs/DEVELOPERS.md § "Where code belongs":
**the core is handed things, it never reaches for them.** Logic, state and data
access are written once in `shared/src/commonMain`; only UI and platform
primitives diverge. These pages are how to apply it.

---

## 0. Read this first — how Detour differs from a stock KMP app

Generic KMP guidance assumes Compose Multiplatform, `ViewModel`s and Koin.
**Detour has none of the three.** If you arrive with that mental model, these
are the substitutions:

| Generic KMP guideline | What Detour actually does | Where |
| --- | --- | --- |
| `:core:*` / `:feature:*` Gradle modules | **two** modules: `:app`, `:shared`. Layers are *packages*, not modules | `settings.gradle.kts` |
| Shared Compose UI in `commonMain` | **no Compose Multiplatform.** Three hand-written UIs: Compose phone, Compose-for-Cars, SwiftUI | `app/…/ui/`, `app/…/car/`, `iosApp/` |
| `ViewModel` + `viewModelScope` | **no ViewModel anywhere.** `object` stores own `StateFlow`; the caller supplies the scope | `ui/RetainedMap.kt:45-46` |
| Koin modules, `koinViewModel()` | **no DI framework.** `object` singletons, plus `expect`/`actual` for the four platform concerns | `shared/…/data/Platform.kt` |
| One state class per screen, VM-owned | one state class per screen in `presentation/`, produced by a **pure `…From(…)` mapper**, fed from store `StateFlow`s | `shared/…/presentation/` |
| `domain/` + `data/` split per feature | fused: `data/` holds models, stores and clients; `drive/` holds the stateful driving machines | `shared/…/data/`, `shared/…/drive/` |
| SKIE / KMP-NativeCoroutines for Swift | hand-written `Watcher` subclasses, one per element type | `shared/src/iosMain/…/FlowWatcher.kt` |

Everything below assumes the right-hand column.

---

## Where each section lives

| § | Topic | File |
| --- | --- | --- |
| 1 | Module layout | [architecture.md](architecture.md) |
| 2 | Source sets — where platform code is allowed | [architecture.md](architecture.md) |
| 3 | Layer anatomy — what each package is for | [architecture.md](architecture.md) |
| 4 | State management | [state.md](state.md) |
| 5 | The UI layer | [multiplatform.md](multiplatform.md) |
| 6 | Dependency wiring (there is no DI container) | [multiplatform.md](multiplatform.md) |
| 7 | Naming and file conventions | [conventions.md](conventions.md) |
| 8 | File and concern boundaries | [boundaries.md](boundaries.md) |
| 9 | Template for new work | [architecture.md](architecture.md) |
| 10 | Tests | [testing.md](testing.md) |
| 11 | Review checklist | [checklist.md](checklist.md) |
| 12 | Why not Compose Multiplatform, Koin, or `ViewModel`? | [decisions.md](decisions.md) |
| 13 | The screen-model layer (target state) | [state.md](state.md) |

Section 0 is this page. Numbering is preserved from the single-file
version so every internal cross-reference still resolves.


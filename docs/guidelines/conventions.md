# Naming and comments

*Part of the [Detour Kotlin guidelines](README.md). Section numbers are stable across files — a reference like §8.4 always means §8.4, wherever it lives.*

## 7. Naming and file conventions

| Rule | Detour form |
| --- | --- |
| File name | PascalCase, named for its main type: `SpeedLimitTracker.kt` |
| One top-level type per file | kept, except for a state file's row types next to its state class |
| Package is the namespace | no barrels, no re-export files |
| Public surface | `internal` controls it; module-scoped, and `:shared` is a real module boundary |
| Mappers | extension or `xStateFrom(...)` free function, never `mapXToY(x)` |
| DTOs | plain names — `SendMessageRequest`, not `…Input` |
| State | `XState` / `XUiState` suffix; all fields defaulted |
| Constants | `SCREAMING_SNAKE_CASE`, **named, never an inline literal** if a second surface has the same number |
| Implementations | `Impl` suffix only for the single obvious implementation |
| Packages | `com.jellemax.detour.{data,drive,presentation}` in `shared`, `com.jellemax.detour.{ui,car,tracking,…}` in `app` |

There is no ktlint or detekt in this build. Formatting is by hand and by
review, which is why the conventions above are worth stating.

### 7.1 Comments

Why-not-what (`CONTRIBUTING.md` § "Code style"). The house style is a KDoc that
explains a decision — why this shape, why not the obvious one, which bug it
prevents. A comment that says *"same fix as X"* or *"identical to the Android
service"* is a **promise, not an enforcement mechanism**; when you write one,
you are recording a known divergence risk, and it belongs in
`docs/refactor/mapscreen/15-divergence-register.md` too.

*Part of the [Detour Kotlin guidelines](README.md). Section numbers are stable across files — a reference like §8.4 always means §8.4, wherever it lives.*

## 11. Review checklist

Anything here that fails is a finding, not a preference.

**Placement**
- [ ] New non-UI logic is in `commonMain`, or a comment names what blocks it.
- [ ] Nothing was extracted into `shared/` from the *worse* of two copies — diff
      the copies for named constants, re-entry guards, and I/O off the collector
      before choosing (`detour-shared-core` §6).
- [ ] An `app/`↔`car/` duplicate was fixed by a shared file under `app/`, not by
      a trip through `:shared`.

**Boundaries** (§8)
- [ ] Everything that moved into a new file shares one lifetime, one reason to
      change, and one writer.
- [ ] No extraction was made that needs more than seven parameters — if it did,
      the owner was fixed first (§8.4).
- [ ] No file holds two state machines, or state driven by two different clocks.
- [ ] A file left over the limit says in a comment why it has no owner yet.

**State**
- [ ] No `MutableStateFlow` is publicly visible.
- [ ] Every store update goes through `update { it.copy(…) }`.
- [ ] No stateful machine lives inside a `@Composable` or a `Service` if its
      inputs are just numbers.
- [ ] `null` and empty are distinguished wherever "not loaded yet" is possible.

**Core constraints**
- [ ] No new `expect` concern (a new *bag* of an existing concern is fine).
- [ ] No new `commonMain` interface with one implementation.
- [ ] No `Dispatchers` reference added to `commonMain`; new core APIs are
      `suspend` and let the caller choose.
- [ ] A new `StateFlow` element type either reuses a `FlowWatcher` subclass or
      adds one.

**Concurrency and correctness**
- [ ] Network I/O is not awaited inline inside a conflating `StateFlow`
      collector — it gets its own job with an in-flight guard.
- [ ] Suspend work that blocks on disk or CPU is wrapped by the *caller* in
      `withContext(Dispatchers.IO)`.

**Hygiene**
- [ ] Tuning constants are named, and named in one place.
- [ ] A comment claiming parity with another surface is registered in
      `docs/refactor/mapscreen/15-divergence-register.md`.
- [ ] New shared logic has a `commonTest` test callable with literals.
- [ ] `versionName` in `app/build.gradle.kts` bumped per `CLAUDE.md`.

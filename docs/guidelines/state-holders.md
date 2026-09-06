# State holders and parameter drilling

## 14. State holders, parameter drilling and CompositionLocal

**Enforced by `./gradlew :app:detekt`.** The rules with teeth are named in
§14.7; everything before that is the reasoning they encode.

This is the first page in `docs/guidelines/`. It stands alone; the wider
Kotlin architecture guide it belongs to is being split out of
`CONTRIBUTING.md` separately, and this page will cross-link to it when that
lands.

---

### 14.1 The rule

> **Group state before you pass it.** A composable takes the data it needs, and
> related values travel together in one holder. If a signature is growing past
> about seven parameters, the caller is missing a state holder — add the
> holder, do not make the passing implicit.

Parameter drilling in Compose is the same problem React has with props, and
Compose answers it differently on purpose. React's usual escape hatch is
Context; Compose's own guidance says **do not** use `CompositionLocal` for
this. From Android's documentation:

> Avoid using it for passing specific objects like ViewModels, as this violates
> the pattern of state flowing down and events flowing up, which reduces
> reusability and testability.

So the fix is never "stop passing things". It is "have something worth
passing".

---

### 14.2 Which tool, when

| Situation | Use | Why |
| --- | --- | --- |
| A few plain values a child renders | **Parameters** | Explicit, previewable, testable. Nothing beats it under ~7 |
| Three or more values that change together | **A state holder**, passed as one parameter | The values already form a concept; name it |
| The child must render UI the caller owns | **A slot** — `content: @Composable () -> Unit` | Removes the parameter instead of relocating it |
| Genuinely tree-scoped, useful to any descendant, has a sensible default | **`CompositionLocal`**, allowlisted | Theme, density, haptics, transition scopes |
| Anything else | none of the above — you are drilling | |

### 14.3 The holder is not a `ViewModel` you hand down

Pass a holder **one level**: a screen composable may give its own `…Content`
body the holder it owns. Below that, pass the data. A holder threaded three
levels deep is drilling with a shorter parameter list, and it makes every leaf
untestable without constructing the holder.

`ViewModelForwarding` enforces this. `.*Content` is the one allowed name.

### 14.4 Slots delete parameters that only exist to be forwarded

A callback that a composable accepts purely to pass downward is a slot in
disguise:

```kotlin
// Drilling: onOpenHub exists only so a button three levels down can call it.
SpinSheet(onNavigateInApp = onNavigateInApp, /* … */)

// Slot: the caller supplies the button, and the parameter disappears.
SpinSheet(actions = { NavigateButton(onClick = onNavigateInApp) }, /* … */)
```

---

### 14.5 Why this repo needs the rule written down

`app/…/ui/SpinCards.kt` defines `SpinSheet` with **23 parameters**.
`app/…/ui/SpinDock.kt` defines `SpinDock` with **19**. Both are called from
exactly one place — `ui/MapScreen.kt` — and they are the same feature in two
states, so most of those parameters are the same values passed twice:

```
mode, radiusKm, onRadiusChange, minRadiusKm, onMinRadiusChange,
poiKind, onPoiKindChange, directionDeg, onDirectionChange,
spinning, error, route, destination, destinationName, origin, …
```

The hoisting is textbook-correct, which is what makes it instructive. Their
caller holds ~30 loose `var … by remember` in one composable scope, so there is
no object to pass, and a parameter list is the faithful mirror of that. The
signature is not the defect. It is the defect printed in the type system.

Those first ten belong to one concept — a spin. Given a holder for it, both
signatures lose ten parameters and gain one, and the two composables stop
having to agree by hand about what a spin consists of.

The same gap has been worked around three other ways already, which is how you
know it is structural rather than local:

| Workaround | What it is really doing |
| --- | --- |
| `ui/RetainedMap.kt` | keeping state alive across navigation — an app-scoped owner, hand-built |
| `ui/SpinResultHolder.kt` | keeping state alive across activity recreation — the same, globally |
| `Auth.resetAccountScopedStores()` | clearing state that has no lifecycle to clear it |
| `SpinSheet`/`SpinDock`'s parameter lists | passing state that was never grouped |

Four workarounds, one missing layer.

### 14.6 Applying it

Adding a value to a composable that already takes many:

1. **Does it belong to a concept the caller already has?** Put it in that
   holder. Do not add a parameter.
2. **Is there no holder yet?** Create one for the concept, move the related
   values into it, pass it. This is a mechanical, compiler-checked change: no
   effect moves, no behaviour changes.
3. **Is it a callback that is only forwarded?** Make it a slot.
4. **Is it genuinely ambient?** Then and only then, propose a
   `CompositionLocal` — and it needs an allowlist entry, which needs an
   argument in review.

A holder is a plain class with a `StateFlow` or Compose state, owned by
something with the right lifetime.

Which lifetime is the open question on the map screen specifically. Detour has
two today — process-global `object` stores, and composables that die with the
screen — and several of `MapScreen`'s concerns (the navigation session, trip
tracking, the camera) need to outlive the screen without being global.
`RetainedMap` is a hand-built answer to exactly that. Until it is settled,
prefer the narrowest lifetime that works and say in a comment why.

---

### 14.7 Enforcement

```bash
./gradlew :app:detekt
```

Config: `config/detekt/detekt.yml`. Runs in CI before the unit tests.
detekt 1.23.7 plus `io.nlopez.compose.rules:detekt:0.4.22` — the ruleset is
compiled against that detekt version, so the two move together.

#### Enforced strictly, from today

These have **zero** baseline entries, so any new occurrence fails the build:

| Rule | Enforces |
| --- | --- |
| `CompositionLocalAllowlist` | §14.2 — the allowlist is **empty**. The app defines no `CompositionLocal` today and a new one is a design decision, not a convenience |
| `ViewModelForwarding` | §14.3 — no holder threaded past `.*Content` |
| `ContentTrailingLambda`, `ContentSlotReused` | §14.4 — slots shaped correctly |
| `RememberMissing` | `mutableStateOf` outside a `remember` |
| `MutableParams` | mutable parameters into a composable |

#### Enforced against a baseline

Pre-existing violations are recorded in `config/detekt/baseline-app.xml`
(136 entries). New ones fail. The load-bearing ones:

| Rule | Threshold | Baselined today |
| --- | --- | --- |
| `LongParameterList` | 7 | 18 |
| `LongMethod` | 100 | 17 |
| `CyclomaticComplexMethod` | 15 | 19 |
| `ModifierMissing` | — | 18 |
| `MultipleEmitters` | — | 7 |
| `ComposableParamOrder` | — | 6 |
| `NestedBlockDepth` | 4 | 5 |
| `LambdaParameterInRestartableEffect` | — | 1 |
| `LargeClass` | 500 | 1 |

**Deleting a baseline entry is how a fix gets locked in.** Never add one by
hand — regenerate only when you have deliberately accepted a new violation, and
say why in the pull request.

```bash
./gradlew :app:detektBaseline    # regenerates; review the diff before committing
```

#### What tooling cannot enforce

A linter counts parameters. It cannot tell you the 21 spin values are one
concept. These stay review items:

- whether a holder models a real concept or is a bag of unrelated fields
- whether a composable with one caller is hoisting for a reusability nobody
  will ever use
- whether a value is screen-scoped, session-scoped or app-scoped — the
  question §12.3 leaves open

#### Scope

`:app` only. Compose lives there, and detekt 1.23 needs a KMP module's source
sets pointed at explicitly, which `:shared` has not had done. `:shared` is
governed by `commonMain`'s own constraints — no `Dispatchers`, no `java.*` —
which the
`ios.yml` metadata compile already gates.

#### Known noise, and why it is off

`FunctionNaming` is annotated-exempt for `@Composable`: detekt's default
flagged all 125 composables for PascalCase, which Compose's own
`ComposableNaming` checks correctly. `TooGenericExceptionCaught`,
`SwallowedException` and `ReturnCount` are disabled — the first two fight the
house catch-and-fall-back store pattern, the third fights guard clauses. Those
were turned off rather than baselined, because a baseline still fails every new
occurrence of a pattern the codebase writes on purpose.

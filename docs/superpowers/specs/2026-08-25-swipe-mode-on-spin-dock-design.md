# Swipe mode on the spin dock

Implements [#70](https://github.com/maxke24/Detour/issues/70). Travel mode currently costs a
permanent `NavigationBar` at the bottom of the map — 240 px (80 dp) of content plus a 48 px
gesture inset, 11.9 % of a 1080×2412 screen, to hold two options and one bit of state. The bar
is also redundant with the card directly above it: `SpinDock` already renders the mode icon and
the mode name. This removes the bar and moves the switch onto a horizontal swipe on the dock,
plus a discoverability hint, because a gesture with no affordance is a gesture nobody finds.

Measurements below are from a `uiautomator` dump on a CPH2449 (Android 16, debug v1.79), taken
at the same moment as the screenshot.

| Element | Bounds | Size |
|---|---|---|
| `ModeBar` incl. gesture inset | `[0,2124][1080,2412]` | 288 px — 11.9 % of screen |
| `NavigationBar` content only | `[0,2124][1080,2364]` | 240 px (80 dp) |
| `SpinDock` card | `[36,1884][1044,2088]` | 204 px (68 dp) |
| Dock left cell (tap → expand) | `[84,1914][678,2058]` | 594 × 144 px (198 × 48 dp) |

Visual reference, including a live draggable prototype of the gesture and both hint variants:
<https://claude.ai/code/artifact/b2aee7a9-213b-4a82-a408-956bd78edf28>

## Scope

In scope:

- Delete `ModeBar` and the `Scaffold` `bottomBar` slot on the map screen.
- A horizontal drag on the collapsed `SpinDock` switches `MOTO` ↔ `CAR`, running the same
  `selectMode` the bar's tap runs today.
- A `semantics` custom action as the non-gesture equivalent, since a swipe is undiscoverable to
  TalkBack.
- A discoverability hint with two variants behind a debug switch, retired after three successful
  swipes.
- The inset fix the bar's removal forces (see Architecture).
- `versionName` 1.79.1 → 1.80.0.

Out of scope:

- `selectMode`'s reset of `radiusKm`/`minRadiusKm` to the mode defaults. It stays exactly as it
  is; see "Radius reset" below for why this was considered and deliberately not changed.
- Android Auto (`app/.../car/`) and Wear. Neither uses `ModeBar`; both have their own surfaces.
- Any repurposing of the space the bar vacates. #70 says the bar goes entirely; what if anything
  replaces it is a later decision.
- Splitting `MapScreen.kt`. It is 1842 lines, over the 1000-line hard limit, and has its own
  refactor chain under `docs/refactor/mapscreen/`. This change is net ≈ +5 lines there and does
  not attempt that work.

## Architecture

Three approaches considered:

1. **Chosen.** The gesture, the offset `Animatable` and both hint animations live entirely
   inside `SpinDock` (`SpinCards.kt`). `MapScreen` passes callbacks and a nullable blocked
   reason, and owns only the hint *scheduling*. Keeps the per-frame drag offset out of
   `MapScreen`'s `Scaffold` content lambda, which matters — see "Per-frame reads" below.
2. Extract a reusable `Modifier.horizontalSwitch(...)`. Rejected: there is exactly one caller
   and the repo has no modifier-extension module to put it in. YAGNI.
3. Wrap `SpinDock` in a gesture container composable inside `MapScreen`. Rejected on the
   evidence in `detour-compose-state-hazards` §6: `MapScreen`'s Scaffold content lambda
   (`MapScreen.kt:1536`) is reached through only inline `Box`/`Column`/`Row` wrappers, so a
   per-frame snapshot read there puts the entire lambda in the invalidation set on every frame
   of the drag.

### Where the gesture is live, and where it is not

`SpinDock` is one of four occupants of a single slot (`MapScreen.kt:1691-1696`):

```kotlin
val bottomCard = when {
    navigating                     -> BottomCard.NAV
    displayCandidates.isNotEmpty() -> BottomCard.CANDIDATES
    settingsCollapsed              -> BottomCard.COLLAPSED   // the dock
    else                           -> BottomCard.EXPANDED    // the sheet
}
```

Navigation, an open candidate list and a convoy vote round therefore already replace the dock
with a different card and need no gate of their own. Two states do:

| Condition | Dock shown? | Swipe | Message |
|---|---|---|---|
| `navigating` | No — `NAV` | n/a | — |
| candidates / convoy vote | No — `CANDIDATES` | n/a | — |
| sheet expanded | No — `EXPANDED` | n/a | — |
| `spinning` | Yes | Refused | "Cancel the spin to change mode" |
| `stats != null` | Yes | Refused | "Stop the trip to change mode" |
| spin result on screen | Yes | **Allowed** | — |

`spinning` is cancellable from the dice button, which already cancels when spinning
(`MapScreen.kt:1754`). `stats != null` matters because `Settings.setTripMode` decides whether
lean and G-force are recorded at all (`TravelMode.tracksLean`, `tracksGForce`), so switching
mid-trip would change what a trip in progress is recording.

The gesture must **not** be added to `SpinSheet`. That card holds two `Slider`s and two
horizontal pill rows (`SpinCards.kt:210,238,258,266`), all of which consume horizontal drag.

### Why a stale spin result does not block the swipe

Nothing in the app clears `destination`/`route` back to null except `selectMode` itself
(`MapScreen.kt:1514-1517`). Every other writer — `choose` (`:590`), `commitSpinCandidate`
(`:619`), the map long-press listener (`:740`), the search pick (`:1829`), the shortcut chips
(`:1677`) — only sets them. Gating the swipe on "a result is on screen" would therefore leave no
way to change mode at all until the user navigates or spins again. The swipe replaces a stale
result exactly as the bar does today.

### Radius reset

`selectMode` resets `radiusKm` to `m.defaultKm` and `minRadiusKm` to `0f`. Moto's default is
120 km, Car's 25 km. A swipe is much cheaper to trigger than a deliberate nav-bar tap, so a
stray flick now discards a hand-tuned radius where before it took an intentional tap.

Decided: **keep the reset unchanged.** Per-mode radius memory is a real improvement but new
state and a scope widening past what #70 asks for. Noted here so the trade-off is on the record
rather than rediscovered later.

## The gesture

In `SpinDock`, three new parameters:

```kotlin
onSwitchMode: (TravelMode) -> Unit,
switchBlockedReason: String?,      // null = allowed
onSwitchBlocked: (String) -> Unit,
```

`detectHorizontalDragGestures` on the **whole Card**, so the drag can start anywhere including
over the dice and Go buttons. Only the left `Row` (mode icon + text column) reads the offset and
applies alpha; the dice and Go button stay fixed. The card's existing `clickable(onExpand)` on
the left cell (`SpinCards.kt:83`) keeps working — a tap still expands the sheet.

- **Allowed.** Content tracks the finger with rubber-band resistance past the commit point,
  dimming as it travels. Commit at **84 dp** of travel or a fling over **400 dp/s** →
  `onSwitchMode(other)` fires and the offset springs to 0 with the new mode's icon and label
  already in it. Short of the threshold → spring back to 0.
- **Blocked.** Resistance clamps at ~**8 dp**, springs back, and `onSwitchBlocked(reason)` fires.
  The gesture is visibly received and then refused; silent inertness reads as a broken app.
- **Accessibility.** One `CustomAccessibilityAction("Switch to ${other.label}")` on the Card,
  returning `false` when blocked.

## Discoverability hint

### Persisted state

`shared/src/commonMain/kotlin/com/jellemax/detour/data/Settings.kt`, following the existing
`setTripMode` pattern (a `MutableStateFlow`, a public `StateFlow`, a setter that writes through
to `prefs`, and a load line in `init`):

```kotlin
val modeSwipesUsed: StateFlow<Long>       // "mode_swipes_used", default 0
fun setModeSwipesUsed(value: Long)

val swipeHintVariant: StateFlow<String>   // "swipe_hint_variant", default "nudge"
fun setSwipeHintVariant(value: String)    // "nudge" | "arrows"; unknown maps to nudge
```

`Long`, not `Int`: `Prefs` (`Platform.kt:32-45`) has `string`/`bool`/`float`/`long` and no Int
overload.

A `String` rather than an enum in `commonMain`, deliberately — the variant is a temporary A/B
knob that gets deleted with the losing variant, and a shared enum would outlive its purpose.

### Scheduling lives in MapScreen, not SpinDock

`SpinDock` is disposed every time the sheet expands or a candidate round opens, so a
"shown once" guard inside it would replay the hint each time the sheet was collapsed.
`MapScreen`'s composition *is* the map visit — `AppRoot` (`MainActivity.kt:118`) swaps screens
with a bare `AnimatedContent` and no `rememberSaveableStateHolder`, so leaving the map for the
Hub disposes MapScreen entirely (`detour-compose-state-hazards` §5). So:

```kotlin
var hintShown   by remember { mutableStateOf(false) }   // once per map visit
var hintRequest by remember { mutableStateOf(false) }
val hintDue = !hintShown && swipesUsed < HINT_AFTER_USES && switchBlockedReason == null

LaunchedEffect(hintDue) {
    if (!hintDue) return@LaunchedEffect
    delay(HINT_DELAY_MS)          // 4_000
    hintShown = true
    hintRequest = true
}
```

Derived-boolean keys, the same idiom as `MapScreen.kt:474`. `SpinDock` takes `hintRequest:
Boolean` and `onHintPlayed: () -> Unit`; it plays the animation in `LaunchedEffect(hintRequest)`
and calls back to clear the flag.

The counter increments on a **successful commit only** — blocked swipes and spring-backs do not
count:

```kotlin
onSwitchMode = { m -> selectMode(m); Settings.setModeSwipesUsed(swipesUsed + 1) }
```

`HINT_AFTER_USES = 3`. Once the counter reaches it, the hint never fires again.

### Two variants

Both abort immediately if the user touches the card.

- **`nudge`** — the left cell slides `+16 dp` over 150 ms and springs back, twice, driven by the
  *same* `offsetX` `Animatable` the real drag uses. No new surface, nothing that can intercept a
  touch, and the motion shown is the motion required.
- **`arrows`** — `Icons.Outlined.SwapHoriz` in an overlay `Box`, alpha 0 → 0.35 → 0 over
  ~1.6 s, carrying `clearAndSetSemantics {}` and no `pointerInput` so it can neither swallow a
  touch nor confuse TalkBack.

### Debug switch

There is no analytics, telemetry or remote config anywhere in this app, so this cannot be a
*measured* A/B — it is a side-by-side judged by hand. A new debug-source-set receiver, following
`DebugTripEndedReceiver`:

`app/src/debug/java/com/jellemax/detour/debug/DebugSwipeHintReceiver.kt`, registered in
`app/src/debug/AndroidManifest.xml`:

```sh
# switch variant
adb shell am broadcast \
  -n io.github.maxke24.detour.debug/com.jellemax.detour.debug.DebugSwipeHintReceiver \
  --es variant arrows

# re-arm the hint (zero the counter)
adb shell am broadcast \
  -n io.github.maxke24.detour.debug/com.jellemax.detour.debug.DebugSwipeHintReceiver \
  --ez reset true
```

The reset arm is not optional: after three swipes the hint is retired for good, so without it
there is exactly one chance to judge each variant.

Documented in `docs/DEBUG_INTENTS.md` alongside the existing debug intents. The debug manifest
is merged into debug variants only, so none of this reaches release.

**Cleanup owed.** Once a variant wins, the loser, `swipeHintVariant`, the receiver and its docs
entry all get deleted. That is a follow-up issue, not dead code left to rot.

## MapScreen edits

1. Delete the `bottomBar = { AnimatedVisibility(...) { ModeBar(mode, ::selectMode) } }` block
   (`:1527-1534`) and any imports it orphans.
2. `:1617` — `.then(if (navigating) Modifier.navigationBarsPadding() else Modifier)` becomes an
   unconditional `.navigationBarsPadding()`.
3. Drop the now-always-zero `.padding(bottom = scaffoldPadding.calculateBottomPadding())`
   (`:1540`).
4. Compute `switchBlockedReason` and wire the dock's three new parameters plus the hint pair.

`MapChrome.kt`: delete `ModeBar` (`:46-58`) and its now-unused `NavigationBar`,
`NavigationBarItem` and `TravelMode` imports. Its doc comment says "The app's three places",
which has been stale since a mode was removed; it goes with the function.

## Hazards

Four things here compile clean and fail only at runtime. Each is cited to
`detour-compose-state-hazards`.

**Stale capture (§2).** `pointerInput(Unit)` captures its parameters as plain values, not
delegated state. Without `rememberUpdatedState` over `switchBlockedReason`, `onSwitchMode` and
`onSwitchBlocked`, the gate freezes at first composition and silently stops blocking. The Tier 0
grep asserts the `rememberUpdatedState` count does not drop; here it must go **up**.

**Per-frame reads (§6).** The offset `Animatable` must be remembered *inside* `SpinDock`. A
snapshot read of it in `MapScreen`'s Scaffold content lambda would invalidate that whole lambda
every frame of the drag, because only inline `Box`/`Column`/`Row` wrappers sit between.

**Equal-key snackbar (§1).** The refusal must **not** route through `error`.
`LaunchedEffect(error)` (`MapScreen.kt:184`) re-keys on value, so two identical messages in a row
raise one snackbar — a second blocked swipe would be silent. It also renders as a red error line
inside `SpinSheet` (`SpinCards.kt:200`), which a refusal is not. Call
`scope.launch { snackbarHostState.showSnackbar(reason) }` directly.

**Inset regression.** `navigationBarsPadding()` is conditional on `navigating` today precisely
because the `Scaffold`'s `bottomBar` consumed the inset the rest of the time. Removing the bar
without making it unconditional puts the dock under the gesture bar in every non-navigating
case — which is every case except navigation. This is correct for all three occupants of that
Column: NAV already wanted it, and CANDIDATES and the dock were relying on the bar.

## Files

| File | Change |
|---|---|
| `app/.../ui/SpinCards.kt` | `SpinDock` gains the drag, the `Animatable` and both hint variants. 312 → ~440 lines, inside the 500-line target. |
| `app/.../ui/MapScreen.kt` | The four edits above. Net ≈ +5 lines. |
| `app/.../ui/MapChrome.kt` | Delete `ModeBar` and its imports. |
| `shared/.../data/Settings.kt` | `modeSwipesUsed` and `swipeHintVariant`. |
| `app/src/debug/java/.../DebugSwipeHintReceiver.kt` | New. Debug source set only. |
| `app/src/debug/AndroidManifest.xml` | Register the receiver. |
| `docs/DEBUG_INTENTS.md` | Document the two intent arms. |
| `app/build.gradle.kts` | `versionName` 1.79.1 → 1.80.0 (feature, backward compatible). |

## Verification

There is no Compose test infrastructure in this repo. CI runs `:app:testDebugUnitTest` and
`:shared:testDebugUnitTest` only (`.github/workflows/build.yml:118`) — no Robolectric, no
`compose-ui-test`, no `androidTest` source set. **No automated test can assert this gesture**,
and nothing below pretends otherwise.

- Tier 0 greps — `.claude/skills/detour-staged-refactor/scripts/tier0-greps.sh`. The
  `rememberUpdatedState` count must go up, not down.
- `devcontainer-exec ./gradlew :app:assembleDebug :app:assembleRelease` — R8 catches what debug
  does not.
- `devcontainer-exec ./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest` — existing tests
  unchanged.
- On device: a before/after screenshot proving the 240 px, and a `uiautomator` dump confirming no
  `ModeBar` node.
- By hand: commit, spring-back, both refusals with their snackbars, both hint variants, the hint
  retiring at three swipes, the `--ez reset` arm, and the TalkBack action menu.

**No GPS replay is required.** No `lastFix` collector moves, no `withContext` enters one, and no
effect key list changes — the three things that would make this a Tier 2 change.

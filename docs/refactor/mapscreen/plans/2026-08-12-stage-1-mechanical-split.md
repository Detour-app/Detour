# Stage 1 — Mechanical Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move 1350 lines of presentational composables and pure helpers out of `MapScreen.kt` into eleven same-package files, changing no behaviour whatsoever.

**Architecture:** Pure cut-and-paste. Every new file is in `package com.jellemax.detour.ui`, so no import edits are needed anywhere — including at the three external call sites. The only signature-level change permitted is `private` → `internal`, and only where a symbol is now referenced from a different file. All 59 `remember` declarations, all 36 effects and all eight local functions stay in `MapScreen.kt`; state ownership is stage 4's problem.

**Tech Stack:** Kotlin, Jetpack Compose, MapLibre.

**Spec:** [`../specs/stage-1-mechanical-split.md`](../specs/stage-1-mechanical-split.md) — preconditions re-verified at commit `0cb93f0`, after stage 0's Task 5 and the merge of `main`.

## Global Constraints

- **All Gradle commands run inside the devcontainer.** Host JDK is 26 with no Android SDK. Container `recursing_volhard`, workdir `/workspaces/Detour`, always `-u 1000:1000`:
  ```bash
  docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard ./gradlew <task>
  ```
  Never a bare `./gradlew`. Never install anything on the host.
- **Commit messages:** Conventional Commits. **No `Co-Authored-By` trailer. No `Claude-Session` trailer. No trailers of any kind.**
- **One work item, one commit.**
- **Pure cut-and-paste.** No reformatting, no renaming, no comment rewording, no "while I'm here" improvements. The rationale comments in this file are the codebase's design record — an IDE that reformats on save destroys them. Prove each move changed nothing with `git diff <base>..HEAD -- .../MapScreen.kt | grep -c '^+[^+]'` → `0`. (Do not use `git show -M -C`: rename detection cannot fire when the source file is modified rather than deleted.)
- **Every new file is `package com.jellemax.detour.ui`.** No new packages. This is what makes the moves free.
- **Move each symbol together with its KDoc and any annotations** (`@Composable`, `@OptIn`). The line numbers below locate the declaration; the block to move starts at its doc comment.
- **Do not touch `MapScreen`'s body** (lines 421–1850). Not one line.
- **`BottomCard` (line 419) stays** in `MapScreen.kt` — it describes that composable's own view tree.

### The visibility rule

After moving a symbol, set its visibility by what actually references it:

- referenced from another file → `internal`
- referenced only from within its own new file → keep `private`

Do not guess. After each move, grep for the symbol across `app/src/main/java/com/jellemax/detour/` and let the result decide. `internal` also reaches `app/src/test`, which stage 2 depends on. In-repo precedent for internal-for-test: `HistoryScreen.kt:72,120`.

`TravelMode.icon` is already public and must stay public — `RoutesScreen.kt`, `HistoryScreen.kt` and `RouteEditorScreen.kt` all use it.
`seedRouteNavigation` is already `internal` and is called from `RoutesScreen.kt:202`; it must stay `internal` so that call site keeps a zero-line diff.

### Serialise the deletions

The eleven moves are independent in what they *add* but all delete from one file. Two agents deleting from `MapScreen.kt` concurrently will conflict. Run the work items **sequentially**, in the order given. Each one: create the new file, delete the moved block from `MapScreen.kt`, compile, commit.

## File Structure

| # | New file | Symbols (line of declaration in `MapScreen.kt` at `0cb93f0`) |
|---|---|---|
| 1a | `TravelModeIcon.kt` | `TravelMode.icon` (215) |
| 1b | `MapCameraTuning.kt` | `smoothBearing` (226); the constants `CAM_POS_TAU` (238) through `SECTION_WEDGE_DEG` (290); `sectionExitGate` (302) |
| 1c | `SpinShare.kt` | `CANDIDATE_COLORS` (321), `asRouteCandidates` (331), `asSpinCandidates` (349), `leadingSpinIndex` (363) |
| 1d | `SpinResultHolder.kt` | `SpinResult` (378), `SpinResultHolder` (385), `seedRouteNavigation` (402) |
| 1e | `MapDialogs.kt` | `SearchDialog` (1854), `BackgroundLocationDisclosure` (2014), `SavePinDialog` (2043) |
| 1f | `Pills.kt` | `Pill` (2072), `SegmentedPillRow` (2100), `ScrollingPillRow` (2116) |
| 1g | `NavAppLaunch.kt` | `launchNav` (2137), `navAppUsableDirectly` (2165), `handleGoTap` (2180), `NavMenuItems` (2203), `NavIconButton` (2249), `NavButton` (3062), and the four `navigate*` functions at the end of the file |
| 1h | `SpinCards.kt` | `DIRECTION_NAMES` (212), `SpinDock` (2296), `SpinSheet` (2383) |
| 1i | `CandidatesCard.kt` | `CandidatesCard` (2564) |
| 1j | `MapChrome.kt` | `ModeBar` (2697), `MapTopChrome` (2714), `SearchPill` (2791), `ConvoyPill` (2841), `GlassRailButton` (2867) |
| 1k | `MapHud.kt` | `ShortcutChips` (1974), `EndTripButton` (2894), `PushToTalkButton` (2915), `SpeedHud` (2972), `SectionAverageChip` (3029), `ActiveTripCard` (3115), `StatItem` (follows `ActiveTripCard`) |

`DIRECTION_NAMES` goes to `SpinCards.kt`, not `Pills.kt`: its only two readers are `SpinDock` and `SpinSheet`. `Pills.kt` holds the generic pill widgets, which know nothing about compass directions.

---

## Task 1a: TravelModeIcon.kt

**Files:**
- Create: `app/src/main/java/com/jellemax/detour/ui/TravelModeIcon.kt`
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt`

**Interfaces:**
- Produces: `val TravelMode.icon: ImageVector`, public, unchanged signature.

- [ ] **Step 1: Create the new file**

```kotlin
package com.jellemax.detour.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.ui.graphics.vector.ImageVector
import com.jellemax.detour.data.TravelMode

val TravelMode.icon: ImageVector
    get() = when (this) {
        TravelMode.WALK -> Icons.AutoMirrored.Outlined.DirectionsWalk
        TravelMode.BIKE -> Icons.AutoMirrored.Outlined.DirectionsBike
        TravelMode.MOTO -> Icons.Outlined.TwoWheeler
        TravelMode.CAR -> Icons.Outlined.DirectionsCar
    }
```

- [ ] **Step 2: Delete lines 215–221 from `MapScreen.kt`**

Delete the `val TravelMode.icon` declaration and its `get()` block. Leave the imports alone for now — a separate final commit removes all of them at once.

- [ ] **Step 3: Compile**

Run: `docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard ./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Confirm the external call sites are untouched**

Run: `git diff --stat`
Expected: exactly two files — `MapScreen.kt` and the new file. `RoutesScreen.kt`, `HistoryScreen.kt` and `RouteEditorScreen.kt` must not appear.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/TravelModeIcon.kt \
        app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
git commit -m "refactor(map): move TravelMode.icon out of MapScreen"
```

---

## Tasks 1b–1k: the remaining ten moves

Every one of these follows the identical five steps. They are written once here rather than repeated eleven times, because the procedure genuinely does not vary — only the symbol list does.

**For each work item, in the order 1b, 1c, 1d, 1e, 1f, 1g, 1h, 1i, 1j, 1k:**

- [ ] **Step 1: Read the symbols named for this item** in `MapScreen.kt`, including each one's KDoc and annotations. The table above gives the declaration line; the block starts at the doc comment above it.

- [ ] **Step 2: Create the new file** with `package com.jellemax.detour.ui`, the imports those symbols need, and the symbol bodies **copied byte for byte**. Do not reflow, re-indent, re-wrap a comment, or rename a parameter.

- [ ] **Step 3: Delete the moved blocks from `MapScreen.kt`.** Nothing else.

- [ ] **Step 4: Set visibility.** For each moved symbol:
  ```bash
  grep -rn '\b<SymbolName>\b' app/src/main/java/com/jellemax/detour/ | grep -v '<NewFile>.kt'
  ```
  Any hit outside the new file → change `private` to `internal`. No hits → leave it `private`.

- [ ] **Step 5: Compile and commit**

```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard ./gradlew :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`. Then:
```bash
git add app/src/main/java/com/jellemax/detour/ui/<NewFile>.kt \
        app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
git commit -m "refactor(map): move <what> out of MapScreen"
```

### Per-item notes

**1b `MapCameraTuning.kt`** — the constants carry long explanatory comments about camera easing tau values and the section gate geometry. Those comments are the reason the numbers are what they are; move them verbatim. `smoothBearing` and `sectionExitGate` are both referenced from `MapScreen.kt`, so both become `internal`, as do every constant `MapScreen.kt` still reads.

**1c `SpinShare.kt`** — `CANDIDATE_COLORS` is read by `MapScreen.kt` *and* by `CandidatesCard` (item 1i), so it must be `internal`. The KDoc on `asRouteCandidates` explaining why a received spin carries a placeholder route is load-bearing; keep it.

**1d `SpinResultHolder.kt`** — `seedRouteNavigation` stays `internal` (called from `RoutesScreen.kt:202`). Note the comment at what is now line 376 claims `RoutesScreen.kt` needs `SpinResult`/`SpinResultHolder` from outside; the fact audit established that is stale — they have zero external references. **Do not act on that here.** Move the comment as-is; correcting it is a content change, not a move, and belongs in its own commit.

**1e `MapDialogs.kt`** — `SearchDialog` is not purely presentational: it contains a 300 ms debounce plus `Geocoder.search` and `RecentSearchStore` I/O. It still moves — it is a self-contained composable — but do not describe it as presentation-only in the commit message.

**1g `NavAppLaunch.kt`** — the largest item, ~280 lines, and the symbols are not contiguous: `NavButton` and the four `navigate*` functions sit at the end of the file, far from the rest. Collect all of them. `launchNav`, `navAppUsableDirectly`, `handleGoTap`, `NavMenuItems` and the `navigate*` helpers are used only by this file's own buttons, so they stay `private`; `NavButton` and `NavIconButton` are used by `SpinCards.kt`, so they become `internal`.

**1h `SpinCards.kt`** — must land *after* 1f and 1g, because `SpinSheet` calls `SegmentedPillRow`, `ScrollingPillRow` and `NavButton`, and `SpinDock` calls `NavIconButton`. Their visibility must already be settled.

**1k `MapHud.kt`** — `SectionAverageChip` is called only by `SpeedHud`, and `StatItem` only by `ActiveTripCard`, so both stay `private`. `PushToTalkButton` contains the microphone press/release gesture; move its comment about backgrounding mid-press verbatim.

---

## Task 1l: Remove the orphaned imports

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt`

Only after all eleven moves have landed. Roughly 130 of `MapScreen.kt`'s 206 imports will be unused. Removing them in its own commit keeps every move commit a pure relocation.

- [ ] **Step 1: Find the unused imports**

```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
  ./gradlew :app:compileDebugKotlin --warning-mode all 2>&1 | grep -i 'unused import' | head -50
```

If the compiler does not report them, remove candidates by inspection: for each `import` line, grep the file for the imported simple name and delete the import when there is no hit.

- [ ] **Step 2: Watch the two `Place` icons**

`MapScreen.kt` imports both `Icons.Default.Place` and `Icons.Outlined.Place`. `ShortcutChips` (moved to `MapHud.kt`) uses `Icons.Default.Place`; `SearchDialog` (moved to `MapDialogs.kt`) uses `Icons.Outlined.Place`. Both new files must have the right one, and both imports probably leave `MapScreen.kt`. Verify each by compiling, not by eye.

- [ ] **Step 3: Compile**

```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard ./gradlew :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
git commit -m "refactor(map): drop imports left behind by the file split"
```

---

## Done Criteria

- [ ] Eleven new files, all `package com.jellemax.detour.ui`.
- [ ] `wc -l < app/src/main/java/com/jellemax/detour/ui/MapScreen.kt` between 1500 and 1750.
- [ ] `git diff <base>..HEAD -- app/src/main/java/com/jellemax/detour/ui/MapScreen.kt` shows **zero added lines** until Task 1l, and only deletions. Verify with:
      `git diff <base>..HEAD -- .../MapScreen.kt | grep -c '^+[^+]'` → must be `0`.

      This replaces an earlier criterion that asked for `git show -M -C` to report a
      rename. That was wrong: `MapScreen.kt` is *modified*, never deleted, so git's
      rename detection structurally cannot fire, and the "leave imports until 1l"
      rule dilutes new-file similarity below the `-C` threshold anyway. The
      zero-added-lines check is both achievable and a stronger guarantee — a move
      that altered so much as one character of a moved block would show up as an
      addition somewhere.
- [ ] `RoutesScreen.kt`, `HistoryScreen.kt`, `RouteEditorScreen.kt` and everything under `car/` have zero-line diffs across the whole stage.
- [ ] `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest` passes (the CI gate from stage 0 Task 1).
- [ ] `./gradlew :app:assembleDebug` succeeds.

## Verification

**Desk checklist only** — no GPS replay needed. If a pure move changed GPS behaviour, the move was not pure, and the diff review above is what catches that.

Install the debug build and confirm: map loads and follows; spin, reroll, cancel; pick a candidate; saved-place chip; search dialog opens with the keyboard up and returns results; long-press drops a pin; save-pin dialog; layers panel opens and closes; mode bar switches; rotate the device.

## Stop-point A

This is a legitimate stopping place. If work halts here, record in [`../DECISION.md`](../DECISION.md):

> Stage 1 complete. `MapScreen.kt` is ~1600 lines. **The state layer is untouched** — 59 `remember` declarations, 36 effects and eight closures over mutable state remain in one composable. The file is smaller; the coupling described in the concern table is entirely unaddressed. Stages 2–4 remain open and are now cheaper.

Without that sentence, the line count gets filed as "MapScreen refactored" and the real problem is buried.

## Next

Return to [`../specs/stage-1-mechanical-split.md`](../specs/stage-1-mechanical-split.md) and follow its **Next stage** footer: run stage 2's preconditions, record the result, then either re-brainstorm stage 2 or write its plan.

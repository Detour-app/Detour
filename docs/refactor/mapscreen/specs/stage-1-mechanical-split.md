# Stage 1 — Mechanical split

## Status

| | |
|---|---|
| **Detail level** | Full — symbol and line-range level |
| **Prerequisite** | [Stage 0](stage-0-verification-baseline.md) — tasks 1, 5, 6, 7 done; tasks 2–4 deferred (need a device and route recordings) |
| **State** | **done** 2026-08-12 — `MapScreen.kt` 3204 → 1549 lines across 12 commits (`b5b4367`…`7c134d8`), zero added lines, tests and assemble green. Plan: [`../plans/2026-08-12-stage-1-mechanical-split.md`](../plans/2026-08-12-stage-1-mechanical-split.md) |
| **Preconditions captured** | 2026-08-11, against `MapScreen.kt` at 3193 lines; re-verified at 3204 lines on 2026-08-12 after stage-0 Task 5 and the merge of `main`. The `seedRouteNavigation` count in `RoutesScreen.kt` was corrected from 1 to 2 the same day — the file has always had both the call site (`:202`) and a KDoc cross-reference (`:92`), so the original figure would have tripped a false staleness alarm on its first honest run. |
| **Chain** | [design](00-chain-design.md) · [roadmap](../DECISION.md) · prev: [stage 0](stage-0-verification-baseline.md) · next: [stage 2](stage-2-pure-extractions.md) |

## Preconditions

Run before writing this stage's plan. Any mismatch means the spec is stale — re-brainstorm,
do not adapt.

```sh
M=app/src/main/java/com/jellemax/detour/ui/MapScreen.kt

wc -l < $M                                          # expect 3193 (± the small edits from 0d/0e)
grep -c '^private fun' $M                           # expect 36
grep -c 'LaunchedEffect(' $M                        # expect 35
grep -c 'DisposableEffect(' $M                      # expect 4
grep -c 'collectAsStateWithLifecycle()' $M          # expect 23

# The symbols this stage moves are all still where the layout says they are.
grep -n 'val TravelMode.icon' $M                    # expect line 213
grep -n 'private fun smoothBearing' $M              # expect line 224
grep -n 'private fun sectionExitGate' $M            # expect line 300
grep -n 'internal fun seedRouteNavigation' $M       # expect line 400
grep -n 'private fun SearchDialog' $M               # expect line 1843
grep -n 'private fun NavButton' $M                  # expect line 3051

# The three external consumers still exist and still compile against the old names.
# RoutesScreen.kt has two occurrences, not one: the call at :202 and a KDoc cross-reference
# at :92. The original "expect 1" was wrong the day it was written — corrected here, not
# discovered by drift.
grep -c 'seedRouteNavigation' app/src/main/java/com/jellemax/detour/ui/RoutesScreen.kt   # expect 2
grep -rc 'mode.icon\|route.mode.icon\|trip.mode.icon' \
  app/src/main/java/com/jellemax/detour/ui/RoutesScreen.kt \
  app/src/main/java/com/jellemax/detour/ui/HistoryScreen.kt \
  app/src/main/java/com/jellemax/detour/ui/RouteEditorScreen.kt   # expect 1 each
```

**If 0d or 0e shifted lines in `MapScreen.kt`,** the `grep -n` line numbers above will be off
by a small constant. That is expected drift, not staleness: re-derive the ranges from the
current file. Staleness is a *symbol* that is missing or a *count* that changed.

## Why this stage

1355 of the file's 3193 lines (`1839-3193`) are presentational composables and pure helpers
that already take everything they need as parameters. They are not entangled with the screen's
state; they are simply in the wrong file. Moving them costs nothing behaviourally and shrinks
the file that every later stage has to be reviewed against, from 3193 lines to roughly 1565.

All four earlier proposals that considered file layout converged on this, independently, and
the fact audit found every claim under it survived checking. It is the only stage in the chain
with **zero** behavioural risk.

## Scope

Move the presentational tail, the pure helpers, the tuning constants, `TravelMode.icon` and
the spin-result holder into eleven new files in the same package. Nothing else.

**The rules that make this zero-risk, and which every work item must obey:**

- Every new file is in `package com.jellemax.detour.ui`. No new packages. Same-package moves
  need no import edits anywhere, including at the three external call sites.
- `private` becomes `internal` only where a symbol is now referenced across files. `internal`
  also reaches `app/src/test`, which stage 2 relies on. In-repo precedent:
  `HistoryScreen.kt:72,120`.
- **Pure cut-and-paste.** No reformatting, no renaming, no comment rewording, no "while I'm
  here" improvements. The rationale
  comments are this codebase's design record and `git log -C` is how they stay traceable.
- One work item, one commit.

## Out of scope

- **Any change to state ownership.** All 59 `remember*` declarations and the eight local
  functions stay exactly where they are, in `MapScreen.kt`. Stage 4 owns state.
- **Extracting effect bodies into `@Composable` effect functions.** Proposal 01 offers this
  as an optional phase 3; it is declined. Only five of the state declarations are provably
  effect-private, so the ceiling is low and the `rememberUpdatedState` stale-capture hazard is
  real. Not worth it.
- **Moving anything to `:shared`.** Stage 3 owns that.
- **Writing tests.** Stage 2 owns that. This stage only makes the pure helpers `internal` so
  stage 2 *can* test them.

## Work items

Eleven moves, all mutually independent and **parallelisable** — this is the best case for
subagent fan-out in the chain. Land them within days of each other, not spread across sprints:
sequential commits to one file with feature branches in flight is the one real cost here.

| # | New file | Symbols moved (source lines) | ~Lines |
|---|---|---|---|
| 1a | `TravelModeIcon.kt` | `TravelMode.icon` (213-219) | 22 |
| 1b | `MapCameraTuning.kt` | `smoothBearing` (221-230), `CAM_*` (232-255), `FIT_PADDING_PX`, `CURVY_CANDIDATES`, `CAM_RESUME_*`, `CIRCLE_FIX_POLL_MS`, `SECTION_GATE_METERS`, `SECTION_WEDGE_DEG` (232-288), `sectionExitGate` (290-314) | 105 |
| 1c | `SpinShare.kt` | `CANDIDATE_COLORS` (316-319), `asRouteCandidates` (322-343), `asSpinCandidates` (345-355), `leadingSpinIndex` (357-367) | 112 |
| 1d | `SpinResultHolder.kt` | `SpinResult`, `SpinResultHolder`, `seedRouteNavigation` (369-414) | 50 |
| 1e | `MapDialogs.kt` | `SearchDialog` (1839-1958), `BackgroundLocationDisclosure` (1998-2028), `SavePinDialog` (2030-2055) | 260 |
| 1f | `Pills.kt` | `DIRECTION_NAMES` (210-211), `Pill` (2057-2085), `SegmentedPillRow` (2087-2100), `ScrollingPillRow` (2102-2121) | 90 |
| 1g | `NavAppLaunch.kt` | `launchNav` (2123-2149), `navAppUsableDirectly` (2151-2164), `handleGoTap` (2166-2186), `NavMenuItems` (2188-2232), `NavIconButton` (2234-2279), `NavButton` (3048-3099), `navigateRoundTrip`/`navigateGoogleMaps`/`navigateWaze`/`navigateGeo` (3150-3193) | 280 |
| 1h | `SpinCards.kt` | `SpinDock` (2281-2366), `SpinSheet` (2368-2547) | 310 |
| 1i | `CandidatesCard.kt` | `CandidatesCard` (2549-2681) | 165 |
| 1j | `MapChrome.kt` | `ModeBar` (2683-2697), `MapTopChrome` (2699-2775), `SearchPill` (2777-2825), `ConvoyPill` (2827-2851), `GlassRailButton` (2853-2879) | 235 |
| 1k | `MapHud.kt` | `ShortcutChips` (1960-1996), `EndTripButton` (2881-2897), `PushToTalkButton` (2899-2954), `SpeedHud` (2956-3012), `SectionAverageChip` (3014-3046), `ActiveTripCard` (3101-3139), `StatItem` (3141-3148) | 250 |

`BottomCard` (417) stays in `MapScreen.kt` — it describes that composable's own view tree.

Two notes for whoever writes the plan:

- **1c and 1d must both promote to `internal`.** `leadingSpinIndex` is called from
  `MapScreen.kt` and needed by stage 2's tests; `seedRouteNavigation` is already `internal`
  and called from `RoutesScreen.kt:202`, which must end up with a zero-line diff.
- **1e is not zero-logic.** `SearchDialog` (`1865-1894`) contains a 300 ms debounce plus
  `Geocoder.search` and `RecentSearchStore` I/O. It still moves in this stage — it is a
  self-contained composable — but do not describe it as presentational, and do not let that
  observation expand the item.

### Import hygiene

After all eleven land, `MapScreen.kt` will carry roughly 130 now-unused imports out of 206.
Remove them in **one final commit**, separate from every move, so that no move commit mixes a
relocation with a deletion.

*Commit:* `refactor(map): drop imports left behind by the file split`

## Done criteria and verification

- [ ] Eleven new files, all in `package com.jellemax.detour.ui`.
- [ ] `wc -l < MapScreen.kt` between 1500 and 1700.
- [ ] `git diff <base>..HEAD -- .../MapScreen.kt | grep -c '^+[^+]'` prints `0` until the
      import-cleanup commit. (Supersedes an earlier `git show -M -C` rename criterion, which
      cannot fire when the source file is modified rather than deleted — corrected in `49084c3`.)
- [ ] `git diff` against the pre-stage SHA shows **no** change to any line inside a moved
      symbol's body.
- [ ] `RoutesScreen.kt`, `HistoryScreen.kt`, `RouteEditorScreen.kt` and everything under
      `car/` have zero-line diffs.
- [ ] The app builds and the CI test step from 0a passes.

Verification tier: **desk checklist only** — map loads, spin, reroll, cancel, candidate pick,
saved-place chip, search, long-press pin, dialogs, layers panel, rotation. No replay needed:
if a pure move changed GPS behaviour, something in the move was not pure, and the diff review
above is what catches it.

## Stop-point A

This is a legitimate place to stop. If work halts here, write this into DECISION.md's status:

> Stage 1 complete. `MapScreen.kt` is ~1565 lines. **The state layer is untouched** — 59
> `remember` declarations, 35 effects and eight closures over mutable state remain in one
> composable. The file is smaller; the coupling described in DECISION.md's concern table is
> entirely unaddressed. Stages 2–4 remain open and are now cheaper.

Without that sentence recorded, the line count gets filed as "MapScreen refactored" and the
real problem is buried.

## Risks

- **Icon import collision.** `MapScreen.kt` imports both `Icons.Default.Place` (`:64`) and
  `Icons.Outlined.Place` (`:76`). Splitting across files must not let a file inherit the wrong
  one. Check each moved composable's icons against the original.
- **Reformatting on save.** An IDE that reformats a pasted block silently destroys the rename
  the comments. Verify zero added lines per commit, not at the end.
- **Drift while parallel.** Eleven agents editing one source file will conflict on the
  deletion side. Serialise the deletions from `MapScreen.kt` even where the additions are
  parallel, or have one agent perform all eleven deletions in order.

## Next stage

→ [`stage-2-pure-extractions.md`](stage-2-pure-extractions.md)

**Before writing stage 2's plan:**

1. Run stage 2's **Preconditions** block. Record the result in its Status table.
2. Stage 2's spec is written at *function level* precisely because this stage invalidates line
   numbers. Expect its ranges to need re-deriving; that is not staleness. Staleness is a named
   function having moved, changed shape, or gained a caller.
3. If the preconditions hold, invoke `superpowers:writing-plans` against
   [`stage-2-pure-extractions.md`](stage-2-pure-extractions.md), then execute with
   `superpowers:subagent-driven-development`.
4. If they fail, invoke `superpowers:brainstorming` for stage 2 and rewrite it against the
   post-split file.

Stage 2 is where the first real tests get written, and the first stage whose output the CI
gate from 0a actually protects.

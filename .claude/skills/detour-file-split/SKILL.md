---
name: detour-file-split
description: >-
  Split an oversized Kotlin or Compose file in this repo into several files without changing
  behaviour. Use this for any task phrased as splitting, extracting, relocating, "breaking up"
  or "moving X out of Y" in app/, wear/ or shared/ — MapScreen, SettingsScreen, FriendsScreen,
  CirclesScreen, TripTrackingService, a stage of the MapScreen refactor chain, or any file
  someone calls too long. It carries the same-package rule that makes a move free, the grep
  that decides visibility, the proof that a move changed nothing, and the two traps in the
  import-cleanup commit. Read it before creating the first new file, not after the first
  merge conflict.
---

# Splitting a Kotlin file in Detour

This is the procedure that landed stage 1 of the MapScreen refactor: 3204 → 1549 lines across
twelve commits, eleven new files, zero added lines to the source file until the final commit,
and zero-line diffs at every external call site. It is mechanical on purpose. The whole value
of the exercise is that a reviewer can believe it changed nothing.

The worked example throughout is `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt` and
the eleven files it produced. The procedure is not specific to it.

## Preconditions

```sh
.claude/skills/detour-file-split/scripts/check-preconditions.sh
```

Three assertions — the worked example is still on disk, there is no `.editorconfig`, and no
ktlint/spotless/detekt is configured — then a printed line count for each of the next-target
files below, so the table can be checked against the tree at a glance.

If a formatter or `.editorconfig` has since been added, stop and rewrite this skill: the
"never reformat inside a move" rule below becomes a build-configuration question instead of a
habit.

## The realistic next targets

Verified sizes under `app/src/main/java/` (the precondition script reprints these):

| File | Lines | Shape |
|---|---|---|
| `ui/MapScreen.kt` | 1549 | already split once; what remains is the state layer, which is **not** a file-split problem — see `detour-staged-refactor` |
| `tracking/TripTrackingService.kt` | 1334 | one service class, not a composable tail; a split here is a class-member move, harder |
| `ui/SettingsScreen.kt` | 1199 | one entry composable + ~15 `private fun …Section()` composables — the same shape MapScreen had |
| `ui/FriendsScreen.kt` | 939 | entry composable + `SignInSection`, `FriendsSection`, `ConvoysSection`, four dialogs, two rows |
| `ui/MapLibreMap.kt` | 764 | the map wrapper plus `FogView` |
| `ui/CirclesScreen.kt` | 716 | entry composable + `CircleListSection`, `CircleDetailSection`, four dialogs |

`SettingsScreen`, `FriendsScreen` and `CirclesScreen` are the three best candidates, because
each is already a thin entry composable over a tail of independent private section composables
that take everything they need as parameters — exactly the property that made MapScreen's tail
free to move.

---

## 1. Same package, always

Every new file gets the **same `package` declaration as the file it came from**. For the `ui`
package that is `package com.jellemax.detour.ui`. No new subpackage, no `ui.map`, no
`ui.settings`.

This is what makes the move free. Kotlin resolves same-package top-level declarations without
an import, so:

- no import is added to the new file for the symbols it left behind,
- no import is added to `MapScreen.kt` for the symbols that left,
- and **no external call site changes at all**. `RoutesScreen.kt`, `HistoryScreen.kt`,
  `RouteEditorScreen.kt` and everything under `car/` ended stage 1 with literally zero-line
  diffs, which is the strongest single piece of evidence that nothing moved semantically.

The counter-argument ("a subpackage would express the grouping") costs a re-touch of every
moved file the day someone changes their mind, and buys an import edit at every call site
today. `DECISION.md` settled it for the `ui` package: flat, for everything that stays in the
app module.

If you are moving into `shared/`, this rule does not apply — that is a rewrite, not a move,
and belongs to `detour-shared-core`.

## 2. One move, one commit

A work item is: create one new file, delete those symbols from the source, compile, commit.
Never two files in one commit; never a move plus anything else.

Commit subject shape, from the twelve that landed:

```
refactor(map): move spin dock/sheet cards out of MapScreen
refactor(map): drop imports left behind by the file split
```

No trailers of any kind.

## 3. Serialise the deletions even when the additions are parallel

Eleven moves that create eleven independent new files still all delete from **one** file. Two
workers deleting from the same file concurrently conflict, and cut-and-paste conflicts resolve
badly and silently — the merge succeeds and a block is subtly duplicated or lost.

Run the items sequentially, in a declared order. Order matters for a second reason: if symbol
B calls symbol A and both are moving, A must land first so B's move sees A's settled
visibility. In stage 1 that forced `SpinCards.kt` (1h) to land after `Pills.kt` (1f) and
`NavAppLaunch.kt` (1g), because `SpinSheet` calls `SegmentedPillRow`, `ScrollingPillRow` and
`NavButton`.

## 4. Move each symbol with its KDoc and its annotations

The declaration line locates the symbol; the block you move starts at the doc comment above it
and includes every `@Composable`, `@OptIn`, `@SuppressLint`.

Copy **byte for byte**. Do not reflow a comment, re-indent, re-wrap an argument list, rename a
parameter, or fix a typo. Not because style does not matter, but because:

- `CONTRIBUTING.md:177-189` makes why-comments the house style, and in these files they are
  the design record — the reason `SECTION_WEDGE_DEG` is 75° and not 60°, the sixteen lines
  arguing why convoy votes are tallied per-device, the note on releasing the mic when
  backgrounding interrupts a press.
- `git log -C` / `git blame -C` is what keeps that record attributable across the move, and it
  needs the text to match.
- The zero-added-lines proof in §6 only works if nothing was retyped.

If a moved comment is **wrong**, move it wrong and fix it in a separate commit. Stage 1 did
exactly this: the comment above `SpinResult` claims `RoutesScreen.kt` needs the holder from
outside, which the fact audit had already disproved. It was moved verbatim anyway, because
correcting it is a content change and content changes do not ride inside moves.

## 5. Visibility by grep, never by guess

After moving a symbol, decide its visibility from what actually references it:

```sh
.claude/skills/detour-file-split/scripts/symbol-visibility.sh <SymbolName> <new-file>
```

It runs `grep -rn '\b<SymbolName>\b'` over `app/src/main/java/com/jellemax/detour/`, excludes
the new file, prints every remaining hit plus any hit in `app/src/test`, and exits 0 for
"keep private" or 10 for "promote to internal".

- **any hit outside the new file → `internal`**
- **no hit outside the new file → keep `private`**

Its verdict is printed as PROVISIONAL and the three ways the grep lies are printed with it,
because the decision is a reading, not a count. Read the hits.

`private` in Kotlin is file-scoped, so a symbol whose only caller moved into the same new file
stays `private`. This is the case people guess wrong. Stage 1's batch C found three of them at
once: `SearchPill`, `ConvoyPill` and `GlassRailButton` stayed `private` in `MapChrome.kt`
because their only caller, `MapTopChrome`, moved into that same file. A naive "everything
MapScreen used becomes internal" would have widened three APIs for no reason.

Promote to `internal`, not `public`. `internal` is module-wide, which covers `ui/`, `car/`,
`tracking/` and everything else in `:app` — and it also reaches **`app/src/test`**, which is
what lets the extracted logic get a test later. The in-repo precedent is
`HistoryScreen.kt:72` (`internal data class TraceSegment`) and `:120`
(`internal fun matchTripPoints`), consumed by
`app/src/test/java/com/jellemax/detour/ui/TripTraceMatchingTest.kt` in the same package. That
is the shape to copy: keep the declaration in the UI package, mark it `internal`, test it with
plain JUnit4 and no Android APIs.

The compiler is a partial check here and worth using: leaving a symbol `private` when it needs
`internal` fails the build with `Cannot access '…': it is private in file`, naming exactly the
symbols that need promotion. It does **not** catch the opposite mistake — an unnecessary
`internal` compiles fine. That is what the grep is for.

### Three ways the grep lies

1. **Extension members have no call site containing their own name.**
   `grep -rn 'TravelMode.icon'` returns exactly one hit: the declaration at
   `TravelModeIcon.kt:11`. The five real call sites read `m.icon`, `mode.icon`,
   `trip.mode.icon`, `route.mode.icon`. Grep the *member* name with a leading dot
   (`grep -rn '\.icon\b'`) and read the receivers.
2. **The same simple name can be a different symbol.** That `\.icon\b` grep also returns
   `BadgesScreen.kt:60`, which declares its own `private val BadgeKind.icon` — a different
   extension on a different receiver in the same package. Read every hit; do not count them.
3. **Prose mentions are not references.** `RoutesScreen.kt:80,96,102` name
   `navigateGoogleMaps` and `navigateRoundTrip` inside a KDoc block that cross-references
   MapScreen's versions. `RoutesScreen.kt` has its own private equivalents and never calls
   into `NavAppLaunch.kt`. Those three symbols correctly stayed `private`. Open the file
   before deciding.

Symbols that are already `public` or already `internal` for a reason keep it. `TravelMode.icon`
is public because `RoutesScreen.kt`, `HistoryScreen.kt` and `RouteEditorScreen.kt` use it;
`seedRouteNavigation` is `internal` because `RoutesScreen.kt:202` calls it. Demoting either
would put a diff in a file that must not change.

## 6. The verification that actually works

**Until the import-cleanup commit, every move commit must add zero lines to the source file.**

```sh
.claude/skills/detour-file-split/scripts/check-no-added-lines.sh <base> <source-file>...
```

It runs `git diff <base>..HEAD -- <file> | grep -c '^+[^+]'` per file — the `[^+]` excludes
the `+++` header, which is why a bare `grep -c '^+'` never returns 0 and always "passes" —
prints added and removed counts per file, exits non-zero if anything was added, and then
prints the range's `--stat` so the zero-line diffs at every external call site are visible in
the same output. Run it after each commit, not only at the end. A non-zero result means a
moved block was retyped, re-indented, or that a "small fix" rode along.

### Why `git show -M -C` does not work here, and never will

Do not ask for rename detection as the proof. It structurally cannot fire:

- `-M` pairs a **deleted** blob with an added one. The source file is *modified*, never
  deleted, so there is nothing for `-M` to pair.
- `-C` will consider a modified file as a copy source, but needs blob similarity above a
  threshold — and the "leave the imports until the last commit" rule guarantees every new file
  carries import lines with no corresponding deletion in the source, diluting similarity
  below it.

This was tested the hard way during stage 1, down to a 0% threshold with
`git diff -C -C -l0 --raw --find-copies-harder`, and it still reports separate `M` and `A`
entries rather than a single `C`. The plan was amended accordingly
(`docs/refactor/mapscreen/plans/2026-08-12-stage-1-mechanical-split.md:207-216` records the
supersession, and `specs/stage-1-mechanical-split.md:132` echoes it).

**Two documents in this repo still demand the rename**: `12-eval-risk-sequencing.md:491` and
`:761-762`. They are wrong. If a checklist you are handed asks for it, substitute the
zero-added-lines check and say why in your report.

The zero-added-lines check is also the stronger guarantee. A rename detection at 90%
similarity still permits ten percent of the block to have changed. Zero added lines permits
none.

## 7. Imports last, in their own commit

Leave the source file's imports completely alone through every move. Roughly half of them will
be orphaned by the end; remove them all in one final commit that touches nothing else. That is
what keeps each move commit a pure relocation — no move commit mixes a move with a deletion,
so a bisect that lands on one has exactly one thing to consider.

Stage 1's cleanup removed 93 of 208 imports and 1 trailing blank line: one file, 94 deletions,
0 additions.

The compiler does not report unused imports in this project's Gradle setup
(`--warning-mode all` produced no such diagnostic), so the removal is done by inspection. Four
traps, all of which were hit:

1. **`getValue` / `setValue` have no textual call site.**
   `androidx.compose.runtime.getValue` and `…setValue` back the `by` operator for every
   `by remember { … }`, `by rememberSaveable { … }` and
   `by …collectAsStateWithLifecycle()`. Kotlin resolves the delegate's operator functions by
   import, so neither name ever appears as literal text in the file. A text-frequency sweep
   scores them zero and deletes them, and the build then fails on dozens of lines at once.
   Keep them. Both are still present in `MapScreen.kt` (`:44`, `:54`).
2. **Two imports can share a simple name.** `MapScreen.kt` imported both
   `androidx.compose.material.icons.filled.Place` and `…outlined.Place`; a name-based count
   collapses them into one number. The filled one belonged to `ShortcutChips` (now
   `MapHud.kt:24`) and the outlined one to `SearchDialog` (now `MapDialogs.kt:21`) — both
   left, and both new files carry the correct one. Same problem with
   `androidx.compose.material.icons.outlined.Groups` (the icon, unused, removed) versus
   `com.jellemax.detour.data.Groups` (the data object, kept, `MapScreen.kt:77`, used at
   `:218`). Resolve these by reading the call site, never by counting.
3. **An import's simple name can be an ordinary English word in a comment.** `Clear`, `Mic`,
   `background` and `height` all appeared in prose in `MapScreen.kt` with no real call site.
   Strip `//` comments and KDoc blocks before counting, then confirm with a targeted grep for
   the actual usage form (`.height(`, `Icons.Filled.Mic`).
4. **A KDoc `[link]` is a real dependency.** `MapScreen.kt` happened to have none that were
   import-backed, but a `/** … [SomeType] … */` reference does need its import. Check before
   assuming.

## 8. Done criteria

- Every new file declares the same package as the source.
- `check-no-added-lines.sh <base> <source>` passes: **zero added lines** up to the import
  commit, and only deletions.
- Every file outside the split has a zero-line diff — the `--stat` that script prints should
  list exactly two files per commit: the source and the one new file.
- `./gradlew :app:compileDebugKotlin` is green after **every** commit, not just the last.
- `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest :app:assembleDebug` is green at
  the end.
- The visibility of every moved symbol is justified by pasted grep output, not by assertion.

Report the per-file line counts, the source file's before/after, and the zero-added-lines
number. "The split is clean" is not a result; `grep -c '^+[^+]'` returning `0` is.

## 9. What a file split does not fix

A smaller file is not a refactored screen. Stage 1 took `MapScreen.kt` from 3204 to 1549
lines and left the state layer — dozens of `remember` declarations, 33 `LaunchedEffect`s, four
`DisposableEffect`s and every closure over mutable state — exactly where it was. That is a
legitimate outcome, but only if it is written down as one. See `detour-staged-refactor` for
the stop-point bookkeeping, and never file a line count as a finished refactor.

## Related

- `detour-staged-refactor` — whether this move may share a commit with anything else, and
  which verification it earns.
- `detour-compose-state-hazards` — read this *before* moving anything that closes over
  composable state; parameterising a listener is how a move stops being a move.
- `detour-shared-core` — moving logic into `shared/` instead, which is a rewrite and follows
  different rules.

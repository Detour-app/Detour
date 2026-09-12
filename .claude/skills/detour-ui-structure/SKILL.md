---
name: detour-ui-structure
description: >-
  Decide which package a file belongs in under app/src/main/java/com/jellemax/detour/ui/, or
  check whether an existing one is in the right place. Use this before adding a new screen,
  dialog, card or shared composable to ui/, before splitting a screen with detour-file-split
  and choosing where the pieces land, and whenever a review comment says a file is in the
  wrong place. It carries the placement test from #184's decision — feature = navigation
  destination, one type-based exception for the design system — the state-holder gap this
  does not solve, and why the target layout is not applied today.
---

# Packaging `ui/`

`ui/` holds 59 files and over 18,000 lines at one flat level, with no subpackages. #184 decided
the separation axis; this skill is the placement test that decision produced, kept next to the
code it applies to rather than only in `docs/DEVELOPERS.md`. Read the full reasoning there
("Packaging `ui/`") if you want the why; this is the how.

**This is a target, not the current tree.** Nothing in `ui/` has been moved because of this
skill. See "Sequencing" below before relocating anything.

## The test

Three questions, in order, for any file you are adding to `ui/` or reconsidering:

1. **Does it reference a `shared/` data model** — a store, an `import
   com.jellemax.detour.data.*`, a `*State`/`*Presenter` from `presentation/`? No → **design
   system**: package `ui/designsystem/`. Theme, icons, generic Material wrappers, and widgets
   built only from primitives and lambdas (`String`, `Int`, `Boolean`, `() -> Unit`).
2. **Yes, and is it used from two or more `Destination`s** (`nav/Destination.kt`)? → **shared
   component**, package-level: stays directly under `ui/`, not inside any destination's
   package. Meant to be the smallest tier — a file earns this the same way a policy earns
   `shared/` (`docs/DEVELOPERS.md`, "Where code belongs"): because it already is used twice,
   not because it might be.
3. **Yes, and used by exactly one `Destination`?** → that destination's own package, named for
   it: `ui/map/` for `Map`, `ui/friends/` for `Friends`, `ui/settings/` for `Settings` and every
   `SettingsSpoke`, `ui/circles/`, `ui/trips/` (`TripDetailScreen`, `TripCardRenderer`),
   `ui/routes/`, `ui/badges/`, `ui/social/`, `ui/profile/`, `ui/coverage/`, `ui/obd2/`,
   `ui/savedplaces/`, `ui/history/`. A destination that is one file today stays one file, just
   moved — this test does not ask anyone to split a screen that already fits.

`app/.../car/` is a separate top-level package by architecture.md §2, not a `Destination` —
a file `ui/` shares with `car/` (`MapLibreMap.kt`, the overlay/marker code `CarMapRenderer.kt`
also calls) counts as shared under question 2 regardless of how many phone destinations use it.

## What this does not answer

**Where the state lives.** This test packages files; it does not add a presentation layer.
Screens in `ui/` are the state holders today — collecting stores and calling repositories
inline, not through a `*Presenter` — and moving `MapScreen.kt` into `ui/map/` does not change
that. `guidelines/state.md` §13 ("The screen-model layer (target state)") is where that question
is decided; this skill's packages are a precondition for it, not a substitute.

**Whether to split a file.** That is `detour-file-split`. The two combine on a large screen:
split first (same package, mechanical, `detour-file-split`'s procedure), then the whole group
of resulting files moves into its destination package as one rename commit, if and when that
destination's turn comes round.

## A first-pass signal, not a verdict

`scripts/classify.sh` prints, per file in `ui/` today: how many `data`/`presentation` imports it
has (question 1's signal) and how many *other* `ui/` files mention its name (a proxy for
question 2 — not proof, since two files both belonging to the same destination will cross-
reference each other without that making either one "shared"). Read the actual call sites before
acting on the count.

```sh
.claude/skills/detour-ui-structure/scripts/classify.sh
```

Worked examples from one run, past the mechanical proxy and into the real answer:

| File | Data ref? | Real usage | Tier |
|---|---|---|---|
| `GraphiteTheme.kt`, `Theme.kt`, `GlassSurface.kt`, `Pills.kt`, `ConfirmDialog.kt`, `SecureFields.kt` | no (or theme-only) | app-wide | design system |
| `MapLibreMap.kt` | yes | `MapScreen.kt` (phone) and `car/CarMapRenderer.kt` | shared component |
| `FogView.kt` | yes | `MapScreen.kt`, `RetainedMap.kt`, `MapCircleMembers.kt` — `Map` only | `ui/map/` |
| `RiderCard.kt` | yes (`RiderCardState`, from `presentation/`) | one call site today | `Map`'s own package — promote to shared component only when a second destination actually calls it (#294 would be that second caller, if it lands) |
| `BadgesScreen.kt`, `HistoryScreen.kt`, `ProfileScreen.kt`, `Obd2PairingScreen.kt` | yes | one `Destination` each | that destination's own package |
| `SettingsScreen.kt`, `SettingsHub.kt`, `SettingsServers.kt`, `SettingsDiagnostics.kt` | yes | `Settings` and its spokes only | `ui/settings/` |
| `MapScreen.kt`, `MapCamera.kt`, `MapBottom.kt`, `MapCircleMembers.kt`, `MapDialogs.kt`, `MapHazardAlerts.kt`, `MapHazardPrefetch.kt`, `MapNavigation.kt`, `MapPermissions.kt`, `MapScreenState.kt`, `HomeSheet.kt`, `RideSheet.kt`, `SpinCards.kt`, `SpinResultHolder.kt`, `SpinShare.kt`, `SearchIsland.kt`, `CandidatesCard.kt`, `NavigationDock.kt`, `NavAppLaunch.kt` | yes | `Map` only (stage 1 of the `MapScreen` refactor already grouped these — see `detour-file-split`) | `ui/map/` |

Not exhaustive — `RetainedMap.kt`, `Navigation.kt`, `Format.kt`, `TravelModeIcon.kt`,
`DisabledFeature.kt`, `MapCameraTuning.kt`, `DriveClockLocal.kt` and the remaining screens
still want a real look at their call sites, not just the script's count, before you'd commit to
a tier for them.

**The test can produce a technically-correct, practically-wrong answer — know when to
overrule it.** `MapChrome.kt` and `MapHud.kt` have zero `data`/`presentation` imports: every
value they draw (follow state, layer visibility, a speed number) arrives as a primitive or a
lambda parameter, same shape as a design-system widget. Rule 1 alone would place them in
`ui/designsystem/`. They stay `ui/map/` anyway, because nothing about them is meant to be
reusable — they are the map screen's own chrome and HUD, written data-model-free by
construction (§8's boundary discipline, not genericity), and moving them away from the one
screen that draws them would cost a reader the "these files are the map" grouping for no
reuse gained. Rule 1 asks "does this reference a data model", not "is this generic" — the two
usually agree, and when they don't, generic-in-shape-but-single-purpose loses to where the
file is actually read from.

## Sequencing

Not applied today, and not scheduled as a bulk job either — a redesign is in flight, and
`ui/MapScreen.kt` alone carried 34 commits in the 60 days before #184 was filed, with five of
the repo's nine unmerged local branches touching `ui/`. Moving files now conflicts with all of
them for zero behaviour change.

Applied **incrementally, the next time a file is touched for an unrelated reason**: if you are
already changing `HistoryScreen.kt`'s content, moving it into `ui/history/` is a second,
separate commit in the same PR (a move and a content change never share a commit — same reason
`detour-file-split` gives for the same-package rule during a split). If you are not already
touching a file, this skill is not asking you to move it.

A **new** file always goes straight into its target package — there is no "flat first, move
later" for something that does not exist yet.

## Related

- `detour-file-split` — splitting a large screen into pieces, same package, before any of them
  move. Read it first if the file in question is over ~1000 lines; use this skill after, to
  place the pieces.
- `docs/DEVELOPERS.md` "Packaging `ui/`" — the decision itself and its full rationale.
- `guidelines/state.md` §13 — the state-holder gap, left open on purpose.

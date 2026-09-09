# Out-of-scope findings

Measured during the `ui/` packaging research: real, not packaging, not being
acted on.

Compiled 2026-09-09 at commit efa6c9ad. Not filed as issues by decision; this
file is the record. Nothing here is scheduled.

The settled scope of the packaging work is: restructure `app/.../ui/`, extract
the navigation host out of `MainActivity.kt`, and add an `entry/` tier for the
thin Android entry points. Everything below fell out of that research and sits
outside that scope. See `proposal-c-react-informed-nesting.md` §Scope.

Every number here was measured on the commit above. They will go stale; the
commands are given so they can be re-taken rather than trusted.

---

## 1. `tracking/TripTrackingService.kt` is the largest file in the repo

1736 lines. Over the 1000-line hard limit in the project coding standards, and
larger than either file this whole research effort was about
(`ui/MapScreen.kt` 1353, `ui/SettingsScreen.kt` 1228).

It is a foreground service holding the tracking loop. By the shape test used
for the `entry/` tier, it is the work itself and not an entry point, so no
part of the packaging proposals touches it.

Important: this is a state-ownership problem, not a file-move problem — the
same diagnosis `docs/guidelines/boundaries.md` §8.8 gives for `ui/MapScreen.kt`.
Splitting it before its state has an owner reproduces the `MapBottomSlot`
46-parameter failure (`boundaries.md` §8.4). Owner first.

    wc -l app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt

## 2. `car/` is the worst-factored package in the repo

6 files, 2233 lines, mean 372 lines/file — worse per file than `ui/` (306).

    946  car/CarMapRenderer.kt
    690  car/NavScreen.kt

Both over the 500-line soft limit. `car/` also imports 8 symbols out of `ui/`
(see `03-move-cost.md` §2): `MapOverlays`, `NamedFriendPosition`,
`PositionMarker`, `openFreeMapStyleUrl`, `setCamera`, `MAX_FRAME_WALL_S`,
`driveFrameDelta`, `formatDistanceKm`. Those are shared map math and
formatting, not phone-UI internals, so their location in `ui/` is wrong
independently of any packaging decision.

`car/` carries both Android Auto (projected) and Android Automotive OS
(on-device) — one `CarAppService`, two hosts, selected by the `automotive`
build type at `app/build.gradle.kts:177`. So its blast radius is two
surfaces, not one.

No proposal in this research touches `car/`.

## 3. `shared/drive/ConvoyRelay.kt` and its test are both over the hard limit

    1204  shared/src/commonMain/kotlin/com/jellemax/detour/drive/ConvoyRelay.kt
    1192  shared/src/commonTest/kotlin/com/jellemax/detour/drive/ConvoyRelayTest.kt

Outside `app/` entirely. Listed here only so the two are on record as the
remaining hard-limit violations after the `ui/` ones.

## 4. Domain logic sitting in `app/` that reads as platform-agnostic

Android/AndroidX import counts per package, as imports/files:

    genuine ports     update 51/11   notif 36/10   ble 23/1
                      audio 16/2     media 7/1     obd2 5/1  (ambiguous)

    near-zero         net    0/2     <- zero Android imports at all
                      map    2/10    (1012 LoC)
                      nav    2/2     perf 4/2      auth 3/2

    for scale         ui 1588/59     tracking 92/20   car 91/6

`docs/guidelines/architecture.md` §1 already flags `map/`, `nav/` as
"Android-side policy that has not moved yet — audit these" and `data/`
likewise. This measurement confirms those flags empirically and adds two the
diagram does not name: `net/` (no Android imports whatsoever, across both its
files) and `perf/`.

Caveat on `obd2/`: 563 lines in one file with 5 Android imports could be
mostly protocol parsing with the transport living in `ble/`. Unclassified
here rather than asserted either way.

Caveat on the method: import density is a diagnostic for spotting misplaced
domain logic. It is NOT a rule for deciding port membership — see §6 below and
`proposal-c-react-informed-nesting.md` §"Ports are triples, not packages".

    for d in app/src/main/java/com/jellemax/detour/*/; do \
      echo "$(grep -rh '^import android\|^import androidx' $d*.kt 2>/dev/null | wc -l) $d"; done

## 5. `01-ui-inventory.md` §B contains a confirmed error

That document states no average-speed HUD composable exists, and that average
speed is voice-announced rather than drawn. **This is wrong.**
`ui/MapHud.kt`'s `SpeedHud` takes a `SpeedHudState` and renders the section
average with its own divider and an independent over-limit accent. It landed
in `461281ae`, "feat(hud): say the section average as a number and 'avg'
(#237)", dated 2026-09-07 — two days before the inventory was captured. The
claim was wrong at capture time, not merely stale.

Consequence: §B of that document is a list of negatives ("no shared chips
exist", "no shared empty/error states exist", "no recording indicator
exists"), and negatives are the class of claim that failed here. The rest of
§B has not been re-verified. Treat its absence claims as unconfirmed until
someone re-greps them; the per-file inventory and the call-count tables in §A
were spot-checked and held.

## 6. Ports here span three source sets, and that constrains any repackaging

Not a defect — recorded because it is a live constraint that every packaging
proposal initially missed.

    src/main/     LocationSource.kt       97   port interface
                  FusedLocationSource.kt  75   real adapter (Play Services)
                  DriveClock.kt          125   port interface
                  ScaledDriveClock.kt    159   time-scaling; ships in release

    src/debug/    LocationSources.kt      67   selector -> replay
                  DriveClocks.kt          32   selector -> scaled
                  ReplayLocationSource.kt 214
                  ReplayAutoDetect.kt · ReplayFixGate.kt · ReplayMode.kt

    src/release/  LocationSources.kt · DriveClocks.kt · ReplayFixGate.kt
                  no-op counterparts, SAME fully-qualified names

Wired at `app/build.gradle.kts:202-203`. `com.jellemax.detour.tracking.LocationSources`
exists twice with an identical FQN; the compiler picks one per variant.

The rule that follows: a **package rename** must move all three source sets in
one commit, and must be verified with a release-variant compile. A debug build
and a local `assembleRelease` can both miss the failure. The build file records
this having already happened — `app/build.gradle.kts:190-201` notes
`:app:compileGithubReleaseKotlin` was the first thing to catch it, in CI, after
`:app:assembleRelease` passed locally.

A pure `git mv` that leaves the `package` line untouched is safe, because every
FQN stays identical.

## 7. Defects inside `ui/` that are not packaging problems

From `01-ui-inventory.md` §Anomalies. Each is independently fixable and none
requires a packaging decision:

- `ui/SecureFields.kt` — `SecretTextField` has **zero callers**. Dead code.
- `ui/BadgesScreen.kt:179` declares a `private fun SectionLabel` that shadows
  `ui/Cards.kt:66`'s. The call at `BadgesScreen.kt:100` silently resolves to
  the local one.
- Names swapped: `ui/SettingsHub.kt` holds `SettingsScreen`; `ui/SettingsScreen.kt`
  holds `SettingsSpokeScreen`.
- `ui/Obd2PairingScreen.kt` is not a route — it is a settings spoke section,
  called from `ui/SettingsScreen.kt:200`. The `Screen` suffix misleads.
- `ui/Navigation.kt` is a holding pen: three unrelated single-caller
  declarations, none used by its own file.
- `ui/HistoryScreen.kt` carries the trip-formatting utilities that
  `ui/TripDetailScreen.kt` and two unit tests all consume — utility logic
  inside a 586-line screen file.
- `* 3.6` (m/s to km/h) is hand-rolled at `car/NavScreen.kt:255` and
  `car/SpinScreen.kt:139` instead of going through `ui/Format.kt`.

## 8. Files over the 500-line soft limit outside `ui/`

    946  app/car/CarMapRenderer.kt
    785  shared/commonTest/data/ParsingTest.kt
    753  shared/data/Settings.kt
    690  app/car/NavScreen.kt
    660  shared/data/Auth.kt
    630  shared/commonTest/data/StoresTest.kt
    605  shared/commonTest/drive/SectionAverageTrackerTest.kt
    586  app/ble/BleNavServer.kt
    573  app/MainActivity.kt          (the routes/ extraction addresses this)
    563  app/obd2/Obd2Connection.kt
    550  shared/data/RoadRoulette.kt

## 9. Issue #184 has grown since it was filed

#184 is OPEN, labelled `p3-later`, and its body records `ui/` at 38 files and
13,743 lines. As of this commit it is 59 files and 18,033 lines: +55% files,
+31% lines. The issue's own "done" definition asks for a decided separation
axis written where humans read it, plus a project skill carrying the placement
test. The three proposals in this directory are the evidence base for that
decision; none of them is that decision.

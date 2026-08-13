# Pre-refactor behavioural baseline

**These are the reference recordings for the MapScreen refactor chain. Do not regenerate them
after a behaviour-touching commit.** They were captured while the code was still original; once
a commit changes behaviour, the "before" side of every A/B comparison in stages 2–4 is gone, and
re-recording produces files that look the same and mean nothing. Work item 0c of
[`../../../docs/refactor/mapscreen/specs/stage-0-verification-baseline.md`](../../../docs/refactor/mapscreen/specs/stage-0-verification-baseline.md).

If a later run disagrees with a number here, the number here is the baseline. Add a second file;
do not overwrite one of these.

> ## ⚠ The route files changed at `ba74e40`. Read this before comparing anything.
>
> Every fix index in this file was recorded against the route files as they stood at `5fc8e90`.
> `ba74e40` replaced all three (`../routes/README.md` says why), so **an index here does not
> index the current route files.** Specifically:
>
> | Route | Then | Now | Effect on the indices below |
> |---|---|---|---|
> | `trajectcontrole.txt` | 984 fixes, 17.0 km, both E40 sections driven **east → west** | 1466 fixes, 27.3 km, relation `15682532` driven **west → east** | **Totally invalid.** Different geometry, different direction, different length. Nothing below transfers. |
> | `urban-limits.txt` | standstill held 16 fixes at 1075 | held 19 fixes at 1075, then a 2-fix hold at 1094 | Indices **≤ 1074 still valid**; past 1075 they are shifted by up to 3 fixes. Length is unchanged at 1442, so the ends still line up. |
> | `stop-start.txt` | stops held 9 / 39 / 12 / 8 fixes | 12 / 42 / 15 / 11 | Indices **≤ 358 still valid**; each later stop absorbs 3 fixes from the segment after it, so positions drift by up to 12 fixes by the end. Length unchanged at 762. |
>
> Recover a superseded route file to re-interpret an index against what was actually replayed:
>
> ```sh
> git show 5fc8e90:tools/mocklocation/routes/trajectcontrole.txt
> ```
>
> **This is a limitation of this baseline, not a licence to re-record it.** The qualitative
> findings — the HUD easing to zero and returning at every standstill, the camera holding bearing
> while parked, following resuming unaided, the sign clearing 3 fixes after the last snap, 0 stalls
> in 3 909 frame pairs — are all properties of the *code*, and none of them depends on which
> kilometre of the E40 the route covered. Those still stand. It is the fix-index arithmetic that
> does not.

**`trajectcontrole` has a second capture, at `689c580`, and it is not a replacement.** The
`5fc8e90` run of that route recorded no section behaviour at all because both Overpass mirrors
were refusing (below), so for the section quantities there was no "before" to lose — which is
the only reason re-recording was legitimate under the rule above. Everything `5fc8e90` *did*
measure on that route (the HUD fade/return cycles, the stall count) is still the baseline; the
`689c580` files add the section quantities and nothing else supersedes them. Both sets of files
are kept side by side, named by their commit. Do not use the `689c580` run as the "before" side
of any A/B: it was captured after `bac833a`, `689c580` and the rest of stage 2 had landed.

**And a third, at `a90c3df`, which is the one Q7–Q9 are now read from.** It is the first run of this
route against the file `ba74e40` introduced, and the first with a mirror that answered for the whole
drive. Same rule as above: it supersedes nothing, it is a *later* observation and not a control, and
it is filed beside the other two rather than over them. The `5fc8e90` and `689c580` numbers stay
exactly where they are.

## What was captured

| | |
|---|---|
| Commit | `5fc8e90` on `refactor/mapscreen-split`; tree clean apart from untracked `.devcontainer/` |
| Device | Samsung Galaxy Z Fold 3 (`SM-F926B`), serial `RFCT42HS9WY`, **Android 15, SDK 35** |
| Display | inner panel, 1768×2208 at density 420 (2.625 px/dp), display id `4630947232161729154` |
| App | `io.github.maxke24.detour.debug` v1.74, built from `5fc8e90` |
| Harness | `com.jellemax.mocklocation` v1.0, designated (`appops get` → `MOCK_LOCATION: allow`) |
| Date | 2026-08-12, 12:23–14:10 local (CEST) |
| Routes | `../routes/{stop-start,trajectcontrole,urban-limits}.txt` at `intervalMs=1000`, **as those files stood at `5fc8e90`** — unmodified then, but all three were replaced at `ba74e40`; see the warning above |

The `trajectcontrole` re-run differs from that table in three places and nowhere else: commit
`689c580` (tree clean apart from untracked `.devcontainer/` and an untracked GPX), date 2026-08-12
**18:02–18:19** CEST, and a 1000 ms screencap cadence. Same device, same display id, same app
version, same harness — which was verified still designated with `appops get` rather than
reinstalled — and the same unmodified route file at `intervalMs=1000`.

The device is a foldable, so **every screenshot names its display explicitly** — without `-d`,
`screencap` warns and picks non-deterministically:

```sh
adb -s RFCT42HS9WY exec-out screencap -p -d 4630947232161729154 > shot.png
```

## Exact commands

Built in the devcontainer (the host JDK is 26 and has no Android SDK):

```sh
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard ./gradlew :app:assembleDebug
docker exec -u 1000:1000 -w /workspaces/Detour/tools/mocklocation recursing_volhard ./gradlew assembleDebug
adb -s RFCT42HS9WY install -r app/build/outputs/apk/debug/app-debug.apk
adb -s RFCT42HS9WY install -r tools/mocklocation/build/outputs/apk/debug/DetourMockLocation-debug.apk
adb -s RFCT42HS9WY shell appops set com.jellemax.mocklocation android:mock_location allow
adb -s RFCT42HS9WY shell appops get com.jellemax.mocklocation android:mock_location   # MOCK_LOCATION: allow
```

Then per route — the app force-stopped and relaunched first, so `MapScreen` starts each run with
an empty `speedLimitWays`, a zeroed miss counter and no section state:

```sh
adb -s RFCT42HS9WY shell am force-stop io.github.maxke24.detour.debug
adb -s RFCT42HS9WY shell am start -n io.github.maxke24.detour.debug/com.jellemax.detour.MainActivity
adb -s RFCT42HS9WY logcat -c
adb -s RFCT42HS9WY logcat -v threadtime > logcat.log &
.claude/skills/detour-gps-replay/scripts/start-replay.sh tools/mocklocation/routes/<route>.txt RFCT42HS9WY 1000
#   screencap every 2000 ms (1500 ms for urban-limits; 1000 ms for the 689c580
#   trajectcontrole re-run, which needed one frame per fix) for the duration + 4%
.claude/skills/detour-gps-replay/scripts/stop-replay.sh RFCT42HS9WY
adb -s RFCT42HS9WY shell input tap 232 1474     # End trip; bounds from a uiautomator dump
```

The trip is ended through the button on purpose: `TripTrackingService` is
`android:exported="false"` so `am startservice … END_TRIP` is refused; force-stopping the app
would discard the trip rather than write it (`endTrip()` is what persists it); and auto-stop
needs five stationary minutes of fixes that a finished replay no longer supplies.

**Three fixes to `start-replay.sh` were needed before any of this ran** — the script had never
been executed end to end. All three are committed alongside this baseline:

1. `pm list packages | tr | grep -qx` — `grep -q` exits on the first match, `tr` dies of
   SIGPIPE, and `set -o pipefail` turns that into a failed pipeline, so an installed harness
   reported as missing.
2. A freshly installed harness has no `files/` directory (nothing has called `getFilesDir()`),
   so the route push failed with `can't create files/route.txt`.
3. The line-count verification redirected `< files/route.txt` outside `run-as`, where the cwd is
   `/` rather than the app's data dir, so it failed with `can't open files/route.txt`.

Nothing was uninstalled, no data was cleared, no permission was changed and no device setting
was changed. See "Deviation from the skill" below for the one instruction not followed, and why.

**State this left on the device:** **six** synthetic trips in the `.debug` variant's history —
five from `5fc8e90`, one more from the `689c580` re-run (`files/trips.json` did not exist before;
the release variant is not installed, so no real history was ever at risk) — and the harness
installed and still designated. The test providers were removed cleanly after both sessions:
`dumpsys location` afterwards shows real fixes again (gps hAcc 10.6 m / 29 satellites after
`5fc8e90`, hAcc 4.53 m / 21 satellites after `689c580`), not a device pinned to the last replayed
coordinate. Delete the synthetic trips from the app if you want the debug history empty; do **not**
`pm clear` to do it. Nothing was uninstalled, cleared, revoked or reconfigured in either session;
the app was installed with `install -r` over itself, which keeps its data.

## Artifacts

`<sha>` below is the commit the run was captured from — `5fc8e90` for the original three routes,
`689c580` for the `trajectcontrole` re-run, `fca3c35` for the `stop-start` run on the OnePlus 11,
`a90c3df` for the `trajectcontrole` run that finally answered Q7–Q9. **`a90c3df` has no `.png` and
no `-stall.tsv`** — its capture is the HUD band only, and the 9.6 MB of images this directory used
to carry were purged from history on 2026-08-13, so nothing image-shaped goes back in.
The formats are identical between them, on purpose: the whole point of a re-run is a file that can be
compared against the first. The one exception is named where it occurs — `fca3c35` was captured on a
device rendering the app in **dark** theme, so its `dial_ink` counts *white* ink and its
`map_mean`/`map_sd` are not numerically comparable with `5fc8e90`'s. The on/off semantics are.

| File | What it is |
|---|---|
| `<route>-<sha>.log` | logcat for the run, filtered (see below) |
| `<route>-<sha>.tsv` | **the machine-comparable record** — one row per captured frame |
| `<route>-<sha>-events.tsv` | derived: every HUD state change, dated to a fix index and a route position |
| `<route>-<sha>-stall.tsv` | derived: per-frame-pair map-region RMSE next to the speed the route was doing |
| `<route>-<sha>-t<sec>-<what>.png` | single frames at named moments, 50% scale |
| `<route>-<sha>-<topic>.png` | montages — several frames of one behaviour in one image |

`urban-limits-noverpass-*` is a **second, earlier run of the same route** kept on purpose: it is
the same drive with Overpass unavailable throughout, which is what isolates the Overpass-fed
behaviour from everything else. `trajectcontrole-09fddde-*` is the same thing by accident rather
than design — that run had no Overpass at all — and `trajectcontrole-b29d014-*` is the same route
with Overpass healthy for its first ~2.5 minutes, which is what finally recorded the section
quantities. Total directory size ~9.6 MB; trim it if that is too much for the repo, but keep the
`.tsv` files — they are the small part and the whole point.

### Why a TSV rather than a folder of screenshots

There is not one `Log` call in `MapScreen.kt` or `TripTrackingService.kt`, so the only record of
what the HUD did is the pixels. A folder of screenshots nobody can diff is worthless, so each
frame is reduced to signals keyed on colours the app picks deliberately and the map never uses:

| Column | Meaning |
|---|---|
| `sign_red` | fraction of the HUD strip matching the sign ring's traffic red (`#E8112D`, `SpeedLimitSign`) → **is a sign on screen** |
| `sign_box` | bounding box of that red. `126x126` is a 48dp sign at this density; the offset moves when the row moves |
| `sign_ink` | near-black fraction inside the disc → **which number**. `30` and `50` both measure 0.1348, so an area measure alone cannot tell them apart |
| `sign_sig` | 6×6 grey fingerprint of the disc → catches the value changes `sign_ink` cannot |
| `avg_blue` | fraction matching `onTertiaryContainer` (`#0B3D82`), the section-average chip's text → **is a trajectcontrole average showing**. The chip's own fill `#DCEBFD` is useless as a marker: the pale map background is within 8% of it |
| `dial_ink` | near-black fraction in the 80dp dial → **is the HUD on screen at all**. `SpeedHud` is only composed above 1.4 m/s (or while the eased number is still above 2 km/h), so this dropping to 0 *is* the HUD fading out at a standstill |

Measured values for reference: sign present → `sign_red` 0.0244; `sign_ink` 0.1348 = `30` or
`50`, 0.1233 = `70`, 0.1772 = `120`; HUD present → `dial_ink` ≈ 0.045–0.063. **Chip present →
`avg_blue` 0.0206 = `Ø 121`, 0.0226 = `Ø 120`** (from `trajectcontrole-b29d014`; at `5fc8e90` this
column was 0 on every frame of every route, so the marker itself had never been confirmed to
work). The chip's own threshold in `events.py` is `avg_blue > 0.0004`, two orders of magnitude
below the measured value, so it is not a marginal detection.

One calibration hazard found while re-running: `avg_blue` keys on `onTertiaryContainer`, which is
the chip's text colour **only while the average is at or under the section's posted limit**.
`SectionAverageChip` switches to `errorContainer`/`onErrorContainer` once `averageKmh > limitKmh`
(`MapHud.kt:235,240-251`), and a red chip is invisible to this column. It did not bite here, and
the reason is worth knowing: `Section.maxspeedKmh` is read from the **relation's** `maxspeed` tag
(`SpeedCameras.kt:126-128`), neither E40 relation carries one, so `sectionLimitKmh` was null,
`over` was false and the chip stayed blue for the whole measurement. The posted 120 does exist in
OSM — but on the `highway=speed_camera` device nodes, which the parser never looks at. A section
that does tag `maxspeed` on the relation can therefore produce a red chip that this TSV reads as
"no chip", and a future run through one needs a second marker colour.

The HUD strip is `688x260+1080+1300`, the row while a trip is active. It deliberately stops
above y=1560: the `ActiveTripCard` below it is filled with a light blue close enough to
`#DCEBFD` to false-positive the chip.

Position and speed at any moment come from the route file, never from the app: one line = one
fix = one interval, so the file is the ground truth an observation is dated against.

### The logs are filtered

Raw logcat is ~2.4 MB/min and ~95% of it is platform chatter (`W/Layer` alone is 33k lines in
six minutes; `E/native` from Play Services' `geller_cache.cc` was 11 884 lines in one 25-minute
run). The committed logs keep every line from the app's PID and the harness's PID, every
`MockLocation` and `FusedLocation` line, and every `E`/`F` line except a few platform tags that
only ever complain about themselves. A real crash still lands (`F DEBUG`, `E AndroidRuntime`);
there were none.

`MockLocation` emits one line per fix (`passive provider is not a test provider` — the passive
provider cannot be a test provider, which is harmless), so **the log doubles as a timestamped
per-fix timeline**. That is what the cadence below was measured from.

## Measured: the replay runs at ~1.02 s per fix, not 1.00

`MockService` sleeps `intervalMs` *after* pushing to four providers, so a nominal 1000 ms replay
advances one fix every **1.018–1.022 s** (per run: `stop-start` 1.0224, `trajectcontrole` 1.018,
`urban-limits` 1.0189, `trajectcontrole` at `689c580` 1.02030, from the first and last
`MockLocation` line and the number of pushes).
Over a 13-minute route that is 17 s of drift. Every fix index in `*-events.tsv` and
`*-stall.tsv` is corrected by the per-run factor — uncorrected, it once made a reconstructed
standstill look like a 4.5 s camera freeze at 67 km/h.

It also means a capture window sized at the nominal duration cuts the tail: the first two runs
reached point 750/762 and 967/984, after which the window was widened by 4% and both
`urban-limits` runs completed 1442/1442. Nothing measured below happens in those tails.

## Per route

### stop-start — 762 fixes, 9.7 km, four standstills · Overpass healthy · **fully captured**

Standstills in the route file, as fix indices (= seconds of replay):
`359–366` (8 s), `395–432` (38 s), `672–682` (11 s), `696–702` (7 s).

**The speed HUD eased to zero and faded at all four, and returned at all four** — from
`stop-start-09fddde-events.tsv`:

| Standstill | HUD faded | HUD returned | Latency |
|---|---|---|---|
| fixes 359–366 | fix 360 | fix 368 | 1 fix after the stop began, 2 after moving off |
| fixes 395–432 | fix 395 | fix 434 | on the first standstill fix, 2 after moving off |
| fixes 672–682 | fix 673 | fix 685 | 1 fix / 2 fixes |
| fixes 696–702 | fix 698 | fix 704 | 2 fixes / 2 fixes |

The easing is in `…-stop2-window.png`: 28 → 8 → 19 → 16 km/h and then gone, rather than the dial
being snatched away.

**The camera held its bearing while stopped.** During the 38 s standstill the map-region RMSE
between consecutive frames is *exactly* 0 for five consecutive pairs (t=424→434 s,
`…-stall.tsv`) — no pan, no rotation. `…-bearing-hold.png` puts the last moving frame beside two
parked frames: the same street runs at the same angle in all three, so there is no north-up
snap. For ~20 s before that it is still easing, which is `CAM_BEARING_TAU` finishing, not the
bearing changing.

**Following resumed on its own** after all four stops; no re-centre tap was needed.

**Speed-limit sign — the 0d quantity.** This is the route where Overpass was healthy, so it
carries the sign baseline:

| Fix | Event | Value |
|---|---|---|
| 94 | appears | 30 |
| 360 / 368 | clears / returns with the HUD, standstill 1 | 30 |
| 395 / 434 | clears / returns with the HUD, standstill 2 | 30 |
| **473** | **clears while moving at 31 km/h** | — |
| **477** | **returns, 4 fixes later** | 30 |
| 497 | value change | 70 |
| 544 | value change | 120 |
| **651** | **clears while moving at 82 km/h and never returns** (111 fixes, 0.9 km) | — |

> **Fixes and seconds from the last successful snap to the sign clearing: 3 fixes ≈ 3.1 s**
> (bounded to 2–4 fixes by the 2 s frame cadence). The sign was still up in the frame at fix 471
> and gone at 473, so the last match was fix 470 and the three misses landed on 471–473 — the
> 3-miss hysteresis behaving exactly as written, on a stream that was not dropping fixes at that
> moment. `urban-limits` independently shows the same 3-fix clear at fix 612.

**This is the acceptance criterion for 0d.** After 0d, expect the clear to stay at 3 fixes. If it
shrinks, the drops were load-bearing and the `3` needs retuning in that same commit.

**Did the sign ever show a cross-street value?** No wrong-road value was seen on any route. The
one case worth naming: the change to 120 at fix 544, three fixes (49 m) before the route's own
speed profile reaches motorway pace at fix 547 — the sign picking up the motorway from the
acceleration lane, which is right, not a frontage-road artifact. `…-sign-values.png` shows
30/50/70/120 as displayed, each next to the live speed.

**Camera or HUD frozen for more than a second?** No: 0 stalls in 382 frame pairs.

Recorded trip: `distanceMeters` 881 088 (see "the Distance readout" below), `topSpeedMps` 36.78
— the latter faithful to the route, whose own spacing implies 132 km/h at fix 598.

### urban-limits — 1442 fixes, 23.5 km · captured twice

Only one reconstructed standstill long enough to matter: fixes `1075–1089` (15 s).

**Run 2 (`urban-limits-09fddde-*`, 13:45–14:09), Overpass healthy for the first ~3.5 minutes:**

| Fix | Event | Value |
|---|---|---|
| 22 | sign appears | 120 |
| 453, 579 | one-frame dropouts of the whole HUD (see below) | — |
| **612** | **clears while moving at 90 km/h and never returns** — 830 fixes, 17 km, including the entire urban half of the route | — |
| 1079 / 1094 | HUD fades / returns at the standstill | — |

The sign held 120 for 590 fixes (6.4 km) and then went out for good. That is the prefetch's held
set running dry: `overpass-api.de` stopped answering **3.5 minutes into the run** (poll evidence
below), and every refetch after that failed. The set outlasted its own
`SPEED_PREFETCH_RADIUS_M = 1500` m by a wide margin because a motorway way is long and stays
matchable for kilometres — but at fix ~609 the route left the last of it, and the 3-miss
hysteresis cleared the sign at fix 612, three fixes after the last match: the same latency as
`stop-start`. **The urban half of
"urban-limits" therefore has no sign baseline**: no posted-limit change, cross street or frontage
road was ever exercised, and the app never displayed any value other than 120.

**Run 1 (`urban-limits-noverpass-09fddde-*`, 13:05–13:29), Overpass unavailable throughout:** no
sign at any point in 1004 frames. Kept because it is the control — it shows the sign's absence is
environmental, not a code change.

Both runs: HUD faded and returned once, at the standstill (run 1 fixes 1080→1093, run 2
1079→1094), and **0 stalls** in 1003 frame pairs each. Run 2 has one identical-map pair, at fix
1091 — the 1.5 s immediately after the standstill, at 20 km/h, before the camera easing produced
a visible pixel change.

**Two single frames of 1004 (fixes 453 and 579) have a blank map surface *and* no HUD, while the
trip card renders normally** (`sign_red`, `sign_ink` and `dial_ink` all 0 on exactly one row,
normal on both neighbours). The fix stream is continuous across them and the previous run shows
none, so this is most likely `screencap` catching the composited SurfaceView mid-swap rather than
an app stall. Worth knowing so a later run's identical blip is not read as a regression.

### trajectcontrole at `5fc8e90` — 984 fixes, 17.0 km · Overpass unavailable · **did not test what it exists to test**

Superseded for the section quantities by the `689c580` re-run in the next subsection; still the
baseline for this route's HUD and stall behaviour.

**No average-speed chip and no speed-limit sign ever appeared** (`avg_blue` and `sign_red` are 0
across all 494 frames), and no speed-camera markers were drawn on the map
(`…-mid-route.png`, six frames across the run at 66–124 km/h on the E40).

That is one cause, not three: `SpeedCameras.near()` feeds both the sections and the camera
markers, `RoadRoulette.speedLimitWays()` feeds the sign, and both are Overpass. So:

- Section entry gating, the running average, its settled value, when it clears, and the
  back-to-back transition over the shared gantry are all unrecorded **in this run**. They were
  recorded in the `689c580` re-run below.
- The route's two sections start at 2.47 km, share a gantry at 6.36 km and exit at 14.35 km
  (`../routes/README.md`), which is **fixes 72, 218 and 463** of this route file — all of it
  inside the first eight minutes, so a re-run needs only a few minutes of a healthy mirror.
  (`5fc8e90`'s note said 73/219/464; recomputed against the route file with the gantry
  coordinates fetched from the OSM API, the closest fixes are 72, 218 and 463.)

What it does establish:

- **11 HUD fade/return cycles** across the route's nine reconstructed standstills (fixes
  `536–542`, `548–556`, `634–641`, `743–756`, `762–796`, `817–832`, `838–852`, `936–947`,
  `963–976`) plus two sub-threshold creeps — all in `…-events.tsv`.
- **0 camera stalls in 493 frame pairs**, while the ambient-limit collector was suspended on
  failing Overpass calls for most of the run. That is the useful half of the accident.
- The nine standstills are all at fixes ≥536, i.e. **after** both sections end (the second exits
  at 464). `../routes/README.md` says they are "inside the sections"; they are not.

### trajectcontrole re-run at `689c580` — 1029 frames · Overpass healthy for the first ~2.5 min

Captured 2026-08-12 18:02:05–18:19:20 CEST from `689c580` (app v1.74), same device, same display
id, same route file, same `intervalMs=1000`. The replay ran to the end — `replay finished at point
984/984`. Measured cadence for this run: **1.02030 s per fix** (984 pushes, first 18:02:05.654,
last 18:18:48.606), so every fix index below is that-corrected.

**Captured at a 1000 ms cadence, not `5fc8e90`'s 2000 ms** — one frame per fix. That was a
deliberate departure and it is the reason the run answers question 4 at all: the chip is expected
to be absent for only about two fixes across the shared gantry (the exit fix clears it, the
re-entry fix sets it null again until `accMeters > 20`), and at a 2 s cadence that transition can
fall entirely between frames and read as "never cleared" — the confidently wrong answer. The TSV
columns are unchanged, so the file is still directly comparable to the other four.

The five quantities stage 3 needs, all from `trajectcontrole-b29d014-events.tsv`:

| # | Question | Answer |
|---|---|---|
| 1 | Does the chip appear, and at which fix? | **Yes — `AVG-ON` at fix 73** (t=74.0 s, cum 2502 m), 34 m past the entry gantry at 2468 m. The measurement starts at fix 72, the first fix inside the 60 m gate; the chip needs `accMeters > 20` first, which is one fix at 122 km/h |
| 2 | What value, and does it track sensibly? | **Appears at `Ø 121`, settles to `Ø 120` and holds it for eight consecutive frames** (t=75–82) while the route's own speed is 121–124 km/h. It tracks the running average correctly — `…-chip-values.png` is the frame-by-frame readout |
| 3 | When does it clear, and at the far gantry? | **`AVG-CLEARED` at fix 81** (t=83.0 s, cum 2774 m) — **306 m into a 3852 m section. No, it does not coincide with the far gantry**, which is fix 218 at 6360 m; the vehicle was still 3529 m short of it |
| 4 | Does it appear twice? | **No. Exactly two AVG events in 1029 frames** — one `AVG-ON`, one `AVG-CLEARED`. Nothing re-armed at the shared gantry at fix 218 (`…-t0224-shared-gantry-no-chip.png`, `…-shared-gantry.png`) or anywhere after |
| 5 | Speed-camera markers on the map? | **Yes** — `…-t0074-chip-on.png` shows the camera icon drawn on the E40 at the entry gantry. The two gantry nodes are themselves tagged `highway=speed_camera`, so one fetch feeds both the marker and the section |

#### The early clear is not explained by any of the three exit conditions

This is the finding worth carrying into stage 3, and it is arithmetic rather than impression. The
gantry coordinates below were fetched from the **OSM API** (`/api/0.6/relation/<id>/full.json`),
which is a different service from Overpass and was reachable throughout, so this check does not
depend on the mirror that was refusing.

At fix 81, with `active` = relation `15685856` (span 3852 m) and `accMeters` ≈ 306 m:

- `reachedEnd` needs `accMeters > 150` **and** a node of `exitGate` within `SECTION_GATE_METERS`
  (60 m). The exit gate is the single far device node at 50.8618251,4.6050292; the vehicle was
  **3529 m** from it.
- `overshot` needs `accMeters > spanMeters * 1.4 + 400` = **5793 m**. It was 306 m. Note this
  condition cannot fire below 400 m for *any* span, so no mis-parsed span explains it either.
- `timedOut` needs 30 minutes. Nine seconds had passed.

None of them can fire, yet `sectionAvgKmh` went null, and the only two writers of null are the
entry branch and the exit branch (`MapScreen.kt:1004,1025`). Five alternative explanations were
checked and each is ruled out by evidence in this run's own files:

- **Not an inflated `accMeters`.** The displayed value is the proof: `Ø 120` at a true 122 km/h is
  a correct accumulator. An `accMeters` large enough to trip `overshot` would have put a nonsense
  average on screen first.
- **Not the HUD dropping out of composition.** `dial_ink` is 0.072 on the frames after the clear
  and the speed dial is legible in them.
- **Not a `MapScreen` recomposition resetting `remember`ed state.** The 120 sign stayed up until
  fix 129, so `speedLimitWays` survived; a fresh composition would have cleared it too.
- **Not real-provider contamination.** Every fix the platform handled inside the replay window
  carried accuracy **4.00**, the harness's constant. The first non-4.00 accuracy in the log is at
  18:18:53, *after* the last mock push at 18:18:48.606.
- **Not a different, shorter section.** An OSM API `map` call over the box containing both the
  entry gantry and the clear position returns **exactly one** enforcement relation, `15685856`, so
  there is no short mis-mapped section for `minByOrNull` to have preferred.

**What is still unmeasured is the one input that would settle it: what `speedSections` actually
held.** That needs `SpeedCameras.near()`'s own query replayed against Overpass, and Overpass
refused for the entire post-run window (still refusing 25 minutes after the run). Until that is
done, treat this as a reproducible discrepancy to investigate, not as a diagnosed bug — and note
that `sectionExitGate` is *not* yet implicated: the entry it made was correct, and it is the
termination that is wrong.

#### Geometry, re-verified

`../routes/README.md` asks for this to be re-checked in case OSM moved a gantry. It has not:

| Relation | Description | Device nodes | Span | `maxspeed` |
|---|---|---|---|---|
| `15685856` | Bertem-Leuven | two 22 m apart at the entry gantry, one at the shared gantry | 3852 m | none |
| `15682532` | Zaventem - Bertem | one at the shared gantry, one at 14.35 km, plus a `from` node 14 m from it | 7936 m | none |

The route passes **14 m and 30 m** from the entry pair (fix 72), **15 m** from the shared node
(fix 218) and **24 m and 27 m** from the far pair (fix 463) — all inside the 60 m gate, and the
shared node really is one node in both relations. The route still tests what it claims to.

#### Everything else this run measured

- **0 stalls in 1028 frame pairs** — no identical map region while the route was above 20 km/h,
  matching `5fc8e90`. The single HUD-absent window, fixes 5–8, is the auto-start gate not yet
  satisfied (`HUD-ON` at fix 9), not a freeze.
- **Sign**: `SIGN-ON` at fix 11 showing 120, then `SIGN-CLEARED` at fix 129 (cum 4300 m) and never
  again — the held set running dry once Overpass began refusing, the same shape as `urban-limits`
  run 2. The section chip and the sign therefore cleared for unrelated reasons, 48 fixes apart.
- **Nine standstills, HUD fade/return throughout the second half**, consistent with `5fc8e90`.
- **Recorded trip**: one trip, mode `CAR` (not retagged `WALK`), `topSpeedMps` 35.93 (129 km/h,
  faithful to the route), `distanceMeters` 5 363 588 — ×315 inflated, the same non-deterministic
  bug as the first four runs. Still not usable as an A/B quantity. Trace: 369 segments, 2779
  points.

#### What this run cost Overpass

The app logs no network activity, so this is **derived from the two prefetch loops' own throttles**
rather than measured: replaying both against the route at 1.0203 s/fix, with the healthy window
ending where the poll saw it end, gives **~143 requests** — camera/section prefetch 2 successful
and 53 failed, ambient speed-limit prefetch 5 successful and 83 failed.

Two successful camera/section fetches is all it took: the second, at fix 88, was centred 3011 m
along the route, and its 4 km circle reaches the shared gantry at 6360 m — so `15682532` was
within range to have been held when the vehicle got there.

The interesting half of that number is the 136 failures. `center` only advances on a **successful**
fetch (`MapScreen.kt:854-858`), so the first refusal leaves the radius trigger permanently true and
each loop simply re-fires at its throttle — 15 s and 10 s — for the remaining fourteen minutes.
An expectation of "roughly five queries for this route" is right about the *successful* path and
wrong by a factor of thirty about what a refusing mirror actually receives, which is precisely the
no-backoff mechanism flagged below.

### trajectcontrole at `a90c3df` — 1765 frames · Overpass healthy **throughout** · the run Q7–Q9 needed

Captured 2026-08-13 08:00:15–08:25:09 CEST from `a90c3df`, same device and display id, app v1.74
**rebuilt from `a90c3df`** (the installed binary predated the `e0c49d5` merge). Route
`../routes/trajectcontrole.txt` as it stands at `a90c3df` — the 1466-fix, 27 283 m file `ba74e40`
introduced — at `intervalMs=1000`. Completed **1466/1466**. Measured cadence **1.01866 s/fix**.
Full write-up in [`../../../.superpowers/sdd/replay-trajectcontrole.md`](../../../.superpowers/sdd/replay-trajectcontrole.md).

**Captured at a mean 0.873 s per frame — faster than one frame per fix**, so no transition could
fall between frames. That needed the raw framebuffer: `screencap -p` costs 2.00 s per frame on this
device against 1.03 s for `screencap` without `-p` (15.6 MB RGBA, 16-byte header, no row padding).
The crop is the HUD band only (768×470 at screen `1000,1140`), which is why there is **no
`-stall.tsv` for this run** — the map region is not in the capture. That is stated as not measured
rather than measured badly; Q4 already rests on 3 909 frame pairs.

All four AVG events, from `trajectcontrole-a90c3df-events.tsv`:

| Fix | Event | Value | Why |
|---|---|---|---|
| **166** | `AVG-ON` | `Ø 115` | first fix inside the west gate's 60 m radius (window 165–168, closest 13.2 m at 166) — **zero latency**, because the *first* prefetch already held the section |
| **543** | `AVG-CLEARED` | last read `Ø 75` | `reachedEnd`: first fix inside the exit node's 60 m gate (window 543–553), `accMeters` ≈ **7 946 m of the relation's 7 950 m** |
| **546** | `AVG-ON` | `Ø 38` | re-armed into `15685856` three fixes later, over the shared node |
| **804** | `AVG-CLEARED` | last read `Ø 79` | `overshot`: `accMeters` > 3852.2 × 1.4 + 400 = 5 793 m. The route's own cumulative distance makes that first true at fix **805** |

The average tracked the geometry: `Ø 115` at the gate while the route was doing 119 km/h, then
84 → 78 → **75** as the slow middle (11–42 km/h over fixes 400–550) pulled it down. The route's
8 000 m transit in 382 s is **75.4 km/h**. The second measurement's far end is unreachable on this
route — closest approach to the Leuven gantry pair is **1 202 m**, twenty times the gate — so
`overshot` is the only exit it could have, and that is a property of the route, not a defect.

- **Sign: six distinct posted limits exercised — 30, 50, 70, 90, 100, 120.** The richest sign
  baseline in this directory; `stop-start` reached four and the other two runs only ever showed 120.
  No held-set exhaustion, because no fetch failed.
- **`speedSections` was seen directly**, not inferred: `SpeedCameras.near()`'s exact query was
  replayed against `overpass-api.de` at two centres before the run. `15682532` comes back with all
  three node members carrying coordinates including one **10 459 m outside the 4 km `around`
  radius** — so **`out geom` does not clip relation members**, which is the premise under #22, now
  measured rather than argued.
- **Request cost: ~35 for the whole route**, all successful — camera/section prefetch 9 (fixes 0,
  177, 264, 356, 633, 789, 882, 1100, 1464), ambient 26. Same derivation as `689c580`'s ~143, which
  is what the identical route costs a *refusing* mirror.
- **Recorded trip**: one trip, `CAR`, `distanceMeters` **28 126.4** for a 27 283 m route — **+3.1 %,
  correct**, the first time this route recorded a usable distance. `topSpeedMps` 37.32 (134 km/h),
  faithful. Trace **5 segments, 835 points** — four splits, all at the 200-point boundary, so not
  one activity-recognition `STILL` flush, against 369 segments for 2 779 points at `689c580`.
- **Contamination**: inside the window the platform handled **4 397** fixes, **4 395 at accuracy
  4.00**. The two exceptions are accuracy 20.00 m at **+1493.13 s and +1493.17 s** after the first
  push — the final 0.2 s, while `removeTestProvider` was running.
- **HUD faded and returned once**, at the route's only standstill (fixes 1124–1130): `HUD-CLEARED`
  1124, `HUD-ON` 1131.

#### Two marker hazards this run had to fix, which the columns above do not survive

**`dial_ink` reads a red dial as "no HUD".** `SpeedHud` turns the whole dial `errorContainer` once
`speedKmh > limitKmh + 5` (`MapHud.kt:184,201-217`), and `onErrorContainer` is `#5A1710`, whose red
channel is 90 — above any near-black threshold. At 121–131 km/h against a posted 120, **83 of 125
frames in one sampled window read `dial_ink` = 0 with the HUD plainly on screen.** A `dial_red`
column keyed on `errorContainer` was added; "HUD present" is `dial_ink > 0.008 or dial_red > 0.05`.
Frame 302 is the proof: `dial_ink` 0.000, `dial_red` 0.445, dial legibly reading 126 in red.

**`errorContainer` cannot tell a red chip from a red dial** — they share the fill, and the
baseline's single strip covers both. Each marker is therefore scoped to its own sub-box:
`chip_fill`/`chip_err` over `CHIP`, `dial_ink`/`dial_red` over `DIAL`. Without that, this run's red
dial would have registered as an over-limit chip for most of the motorway. The existing note
anticipated the hazard for the chip but not that the *dial* trips the same colour.

**A one-frame `sign_red` = 0 at this cadence is a value change, not a clear.** `SpeedLimitSign` sits
inside a 300 ms `Crossfade` (`MapHud.kt:195`), so a frame can land mid-fade with no ring on screen.
Frames 145/146/147 are `100` → nothing → `120`. Every `SIGN-CLEARED` immediately followed by
`SIGN-ON` in this run's events file is one of these. The 1–2 s cadences of the earlier runs mostly
stepped over them, which is why the column looked binary there.

## Overpass was the limiting factor, and the app's own request rate is why

Timeline, measured — poll of `/api/status` and of a real way query every ~60 s from the
devcontainer on the same WAN as the phone's Wi-Fi, plus `curl` from the device itself:

| Time | `overpass-api.de` | `overpass.kumi.systems` |
|---|---|---|
| 12:22–12:36 | working (sign appeared throughout `stop-start`) | — |
| 12:43–13:38 | refusing: `000` in 0.2–7 s; from the device, `curl (7) couldn't connect` after 7.2 s | `/api/status` 200 but in 15.4 s; `/api/interpreter` gave **no response at all in 90 s** |
| 13:39–13:47 | healthy: status 200 in 0.1–0.2 s, a real way query 200 in **0.47 s** | still unusable |
| 13:48:42 → 14:10+ | refusing again — **3.5 minutes after the second `urban-limits` replay began** | still unusable |
| 17:55–18:02 | recovered, but flaky: the same small query answered `200, 200, 504` and then `200, 504, 200, 200` — 3/4 immediately before the `689c580` replay was started | — |
| 18:02–18:03:36 | healthy through the start of the run: status 200 in 0.17 s. Both Overpass-fed features worked — sign at fix 11, chip at fix 73 | — |
| 18:04:31 → 18:37+ | refusing again — **~2.5 minutes after the `trajectcontrole` replay began**, and still refusing 25 minutes after it ended | `overpass.private.coffee` also `000`; `overpass.osm.ch` answers but is a Switzerland-only extract, so it returns 0 elements for this bbox |
| **2026-08-13 08:00:30 → 08:24:12** | **healthy for the whole `a90c3df` run: 13/13 status polls 200**, eleven of them in 0.10–0.17 s. Degrading at the end — **4.34 s at 08:22:10 and 6.47 s at 08:24:12** — but never refusing, and both Overpass-fed features were live at the finish | `/api/status` 200 in 0.34 s at 07:57, not needed |

**The `a90c3df` row is the first `trajectcontrole` run that did not get re-blocked**, and it is the
one where the app made ~35 requests instead of ~143. That is consistent with the mechanism below and
**it is not evidence for it** — a day had passed, so the earlier penalty may simply have expired.
Two habits from that run are worth copying regardless: poll `/api/status` every ~120 s rather than
every 60, and replay `near()`'s own query at the handful of centres that matter *before* the drive
instead of hoping the mirror survives until afterwards.

The `689c580` row repeats the `5fc8e90` pattern almost exactly: a healthy window, a replay started
inside it, and a re-block two to three minutes later. Note the shape of the recovery, because it
matters for judging when to start a run — `overpass-api.de` returned `504` (a gateway timeout from
an overloaded server) rather than `000` (a refused connection) while it was coming back, and
reported `2 slots available now`, i.e. the rate limiter was *not* the thing saying no at that point.

Two Overpass-free workarounds were used for the geometry checks in this baseline, and both are
worth reusing rather than waiting on a mirror: `api.openstreetmap.org/api/0.6/relation/<id>/full.json`
for a known relation, and `/api/0.6/map?bbox=` over a small box to enumerate what is near a point.
Neither touches Overpass's quota, and the second is what established that only one enforcement
relation exists at the entry gantry.

Plain HTTP from the device worked throughout (`http://example.com` → 200), so this is not the
phone's network. `Http.kt` sets `connectTimeoutMillis = 5_000`, so a mirror that takes 7 s to
refuse fails every time, and the 15 s+ fallback cannot serve a 1.5 km way query inside the 30 s
request timeout.

**The pattern is the app hammering the mirror into blocking the IP.** The ambient-limit effect
refetches on a 10 s throttle whenever the vehicle nears the edge of its held circle, which at
motorway speed is every ~45 s, and the camera prefetch does the same on 15 s. Roughly 13 minutes
of that traffic (the whole of `stop-start`) was followed by an hour of refusals; once penalised,
resumed traffic re-blocked the IP within 3.5 minutes.

**Do not read this as measured field behaviour, and do not file it as one.** This applies to the
`689c580` re-run exactly as much as to the original three, and re-reading it as field behaviour
gets easier, not harder, now that the pattern has repeated. Replay compresses geography in a way
real driving does not: three routes run back to back over one bounding box, a fourth replay of one
of them the same evening, repeatedly, from a single IP that had also served a 335 kB Overpass query
for the gantry lookups earlier the same day. A real user drives through an area once, from a
residential or mobile address. The honest claim is about the *mechanism*, not the frequency — and
the `689c580` run puts a number on the mechanism (~143 requests for one 17 km route, 136 of them
retries into a refusal) without making the frequency any more representative. The mechanism is
worth its own investigation independent of this refactor:

- there is exactly **one** fallback mirror (`RoadRoulette.kt:33-34`), and it was unusable all day,
  so in practice the app has no fallback at all;
- `Http.kt` sets `connectTimeoutMillis = 5_000` while the primary took 7 s to *refuse* and the
  fallback took 15 s to answer `/api/status`, so both fail the timeout rather than degrading;
- nothing backs off after a refusal — the 10 s and 15 s throttles are unchanged by failure, which
  is what turned a penalty into a re-block 3.5 minutes later.

What a real user hits is unmeasured. That question needs a drive on a mobile connection, not a
replay, and this baseline cannot answer it.

## Environmental findings that are not the app's behaviour

**1. The trip card's Distance readout inflates, non-deterministically.** Three of four recorded
trips are badly wrong and one is right:

| Run | Route length | Recorded `distanceMeters` |
|---|---|---|
| stop-start | 9.7 km | 881 088 m (×90) |
| trajectcontrole (`5fc8e90`) | 17.0 km | 4 633 883 m (×273) |
| urban-limits run 1 | 23.5 km | **24 431 m (correct)** |
| urban-limits run 2 | 23.5 km | 3 319 833 m (×141) |
| trajectcontrole (`689c580`) | 17.0 km | 5 363 588 m (×315) |

The last row is the useful one for pinning this down: **the same route file, replayed at the same
interval on the same device, inflated by ×273 once and ×315 the other time.** So the multiplier is
not a function of the route, and a fix for this has to explain a per-run difference, not a
per-geometry one. `topSpeedMps` stayed faithful in both (36.78 and 35.93 against a route whose own
spacing implies ~130 km/h), which keeps the fault inside the distance accumulator rather than in
the fix stream.

Read off the trip card during `stop-start` it was correct at 29 s (340 m), already 28.7 km at
1 min 59 s, and **kept climbing by ~73 km during the 40 s standstill** while every fix carried
the same coordinate (`stop-start-09fddde-camera-park.png`: 276.7 km → 328.2 km across the stop).

The fix stream is not the cause, and this was checked rather than assumed: the platform handled
751 fused + 750 network + 750 gps fixes on `stop-start`, **all at accuracy 4.00** — the harness's
constant — with exactly two non-mock fixes, both timestamped before the replay started. The
stored trace is on-route throughout at 25–35 m spacing with no jumps. So `_lastFix` and
everything downstream of it are sound, and the fault is confined to the
`distance += last.distanceTo(location)` accumulator in `onTripLocation`, whose `lastLocation`
must sometimes be far staler than one fix. **Do not use trip `distanceMeters`, the trip card's
Distance, or `topSpeedMps` as an A/B quantity on this harness** — and chase the inflation
separately; nothing in this baseline explains it.

**2. Trace segment counts are meaningless on a desk replay.** `stop-start` produced 57 segments
for 282 points, with 14–69 m between the segments — not the >500 m jumps `addTracePoint` splits
on. `handleTransition` calls `flushTrace()` on every activity-recognition `STILL` **enter**, and
a phone lying on a desk earns one every few seconds. Compare points, never segments, unless the
phone is actually moving.

**3. `MockService` cannot mock the passive provider**, one `E/MockLocation` per fix (~750–1440 per
route). Harmless, and useful: it is the per-fix timeline everything here is dated against.

## What 0d can and cannot fix, given what was measured

0d moves two Overpass fetches out of a `TripTrackingService.lastFix` collector. The spec says
that stall freezes "the camera, the speed HUD and the turn card". **In the code as captured it
cannot.** The camera and the HUD read `liveFix`, a separate
`collectAsStateWithLifecycle(TripTrackingService.lastFix)` at `MapScreen.kt:201`, and the file
has five independent `lastFix` collectors (`:423`, `:735`, `:774`, `:856`, `:889`). Suspending
one frees the main thread instead of blocking it, so the others keep receiving.

The measurements agree: **0 stalls in 3 909 frame pairs across five runs** (382 + 493 + 1 003 +
1 003 + 1 028), including three whole routes where the fetches were hanging until the 5 s connect
timeout — the `689c580` re-run adds fourteen minutes of a *refusing* mirror at a 1 s cadence, which
is the tightest look at this yet. A stall here means a pixel-identical map region while the route
says the vehicle was above 20 km/h; at a 1–2 s cadence, that catches any freeze of that length or
longer. The single identical pair in the whole set is `urban-limits` run 2 at fix 1091, the 1.5 s
just after a standstill at 20 km/h, before the camera easing moved a pixel.

What 0d does change is the *ambient-limit collector's own* fix stream: while it awaits Overpass,
`StateFlow` conflation drops every fix that lands, so three misses take longer than three fixes
of wall-clock. Hold it to the number above — **the sign clears 3 fixes after the last successful
snap** — and expect that not to shrink.

## Deviation from the skill, stated plainly

`detour-gps-replay` says to turn off every real location source before trusting a replay.
**Wi-Fi was left on**, because it is this device's only validated internet path, the sign, the
sections and the map tiles all need the network, and changing device settings was outside what
this task was allowed to do. The risk that warning exists for was then checked instead of
assumed, per run, from the platform's own fix log (`SLocation`'s `PositionManager` prints the
accuracy of every fix it handles):

**Inside all five replay windows, 100% of the fixes the platform handled carried accuracy 4.00 —
the harness's constant.** Every fix with any other accuracy is timestamped either a fraction of a
second *before* the first replayed fix or *after* the last one; e.g. on `trajectcontrole` at
`5fc8e90` the non-mock fixes are at 12:43:24.039 (first mock: 12:43:24.327) and 12:59:55.701 (last
mock: 12:59:51.818), and on the `689c580` re-run the 2953 handled fixes are *all* 4.00 with the
first other value at 18:18:53.722, after the last mock push at 18:18:48.606. Every recorded trace
also stays on its route. No real provider contaminated any run, and the teleporting the skill warns
about did not occur.

That check earns its keep on the re-run specifically: contamination would have been the tidiest
explanation for the early clear (one desk fix ~10 km off-route would add enough to `accMeters` to
trip `overshot` instantly), and the log rules it out.

## What could not be captured

- **Average-speed sections: fully captured at `a90c3df`** — entry gating, the running average and
  its settled value, exit at a real gantry by `reachedEnd`, the shared-gantry re-arm as a
  *positive*, and a second termination by `overshot` within one fix of the arithmetic. `near()`'s
  own query was replayed at two centres before the run, so what `speedSections` held is measured
  too. **Nothing about the section machine is outstanding for stage 3.** Still not exercised, and
  neither is reachable by any fixture here: **`reachedEnd` at a far gantry entered from the near end**
  (this route's exit gate is also the next section's entry), **`timedOut`** (30 minutes in one
  section) and **the over-limit red chip** (no relation in this geography tags `maxspeed`).
- **Why the `689c580` run cleared 306 m into a 3852 m section is narrowed, not diagnosed.**
  `a90c3df` rules out the clipping premise — `out geom` returns members 10 459 m outside the
  `around` radius — and shows the machine is correct given a populated `speedSections`, which points
  at what *that* run's `speedSections` held. Its inputs are gone, so this cannot be closed; do not
  re-drive the route for it.
- **The urban half of `urban-limits`:** posted-limit changes, cross streets and the frontage
  road. Partly superseded — `trajectcontrole` at `a90c3df` displayed **30, 50, 70, 90, 100, 120**,
  so the sign has now been seen changing value six ways on one drive; what is still missing is a
  *wrong*-road value to have been looked for on an urban grid.
- **Route (iii), off-route/reroute:** does not exist — no reachable routing server, so there is
  no computed route to deviate from (`../routes/README.md`,
  `docs/refactor/mapscreen/DECISION.md`).
- **A map pan mid-drive**, which `../routes/README.md` lists for `stop-start`: not performed.
  Camera park was observed at standstills instead.
- **Video.** `screenrecord` caps at ~3 minutes, so periodic frames plus logcat are the record.
  The montages cover the moments a clip would have.
- **Anything needing a second radio:** convoy with a real peer, BLE board telemetry, a paired
  watch's sensors, real accuracy degradation. The harness reports a constant 4 m.

---

# The named quantities

Work item 0c's actual deliverable. Everything above is evidence; this is the short list a later
run is measured against, so that "it looks the same" is never the comparison. Each row names the
quantity, the value observed, and the file the value is read out of.

| # | Quantity | Baseline value | Read from | Still valid after `ba74e40`? |
|---|---|---|---|---|
| Q1 | Fixes from the last successful speed-limit snap to the sign clearing | **3 fixes ≈ 3.1 s** — seen twice independently: `stop-start` fix 470→473, `urban-limits` run 2 fix 609→612 | `stop-start-09fddde-events.tsv`, `urban-limits-09fddde-events.tsv` | **Yes.** A latency in fixes, not a position. This is the 0d acceptance criterion; if it shrinks, the `StateFlow` drops were load-bearing and the 3-miss hysteresis needs retuning in that same commit |
| Q2 | Does the sign ever show a cross-street or frontage-road value? | **No.** No wrong-road value on any of five runs. The one near-miss is a change to 120 three fixes before the route reaches motorway pace — the sign picking up the motorway from the acceleration lane, which is correct | `…-sign-values.png` | **Yes**, as a yes/no |
| Q3 | Speed-limit values actually exercised | **30, 50, 70, 90, 100, 120** — six values on `trajectcontrole` at `a90c3df`, the only run whose mirror never failed. `stop-start` reached 30/50/70/120; `urban-limits` and the two earlier `trajectcontrole` runs only ever displayed **120** before their held set ran dry | `trajectcontrole-a90c3df.tsv` (distinct `sign_ink`), `stop-start-09fddde-events.tsv` | **Yes** |
| Q4 | Camera or HUD frozen > 1 s | **0 stalls in 3 909 frame pairs** across five runs, including fourteen minutes against a *refusing* Overpass mirror at a 1 s cadence | `…-stall.tsv` (all five) | **Yes** |
| Q5 | Does the speed HUD ease to zero and hold at a standstill, and return? | **Yes, at 4/4 stops on `stop-start`**, and across all nine on `trajectcontrole`. Fade lands 1–2 fixes into the stop, return 2 fixes after moving off. The easing is gradual (28 → 8 → 19 → 16 km/h, then gone), not snatched away | `stop-start-09fddde-events.tsv`, `…-stop2-window.png` | **Latency yes; the fix indices no** — the stops now start at the same fix but run 3 fixes longer |
| Q6 | Does the map camera park and resume? | **Yes.** Frame-to-frame map RMSE is *exactly* 0 for five consecutive pairs inside the 38 s stop — no pan, no rotation, and no north-up snap. Following resumed unaided after all four stops; no re-centre tap was needed | `stop-start-09fddde-stall.tsv`, `…-bearing-hold.png`, `…-camera-park.png` | **Yes** as a yes/no; the RMSE-zero run is now 3 fixes longer |
| Q7 | Does a section average appear, where, at what value? | **Yes — `AVG-ON` at fix 166**, the first fix inside the west gate's 60 m radius, reading `Ø 115` and converging to **`Ø 75`** against a transit the route's geometry puts at **75.4 km/h**. **Zero latency**: the first prefetch already held the section | `trajectcontrole-a90c3df-events.tsv` | **Yes** — measured against the current route file |
| Q8 | Does it clear at the far gantry? | **Yes, at fix 543**, the first fix inside the exit node's 60 m gate, having accumulated **7 946 m of the relation's 7 950 m span**. `reachedEnd`, as written. The `689c580` early clear (fix 81, 306 m into 3 852 m) **did not reproduce** | `trajectcontrole-a90c3df-events.tsv` | **Yes** |
| Q9 | Does it re-arm at the shared gantry? | **Yes — `AVG-ON` at fix 546**, three fixes after clearing, reading `Ø 38` against a route doing 38–40 km/h. That measurement then cleared at **fix 804** by `overshot`, which the route's cumulative distance puts at fix **805**. **Four AVG events in 1765 frames**, all four explained | `trajectcontrole-a90c3df-events.tsv` | **Yes** |
| Q10 | Is the chip ever red (average over the section's posted limit)? | **No, and it cannot be here.** Neither E40 relation tags `maxspeed`, so `Section.maxspeedKmh` is null, `over` is false and the chip stays `tertiaryContainer` for both measurements. `chip_err` is 0 on all 1765 frames. **A missing red state on this route is correct, not a defect** | `trajectcontrole-a90c3df.tsv` | **Yes**, as a yes/no. Exercising red needs a relation that tags `maxspeed`, or a fixture |
| — | Trip `distanceMeters` / `topSpeedMps` / trace segment counts | **Unusable as A/B quantities on this harness.** Distance inflates ×90–×315 non-deterministically (same route, same device: ×273 once, ×315 the next); segments split on desk-induced activity-recognition `STILL` events. **`topSpeedMps` is not safe either** — it stayed faithful on the Z Fold but came out at 1359.93 m/s (4 896 km/h) on the OnePlus 11; see the `fca3c35` section below for where in the run that value was acquired. **The `a90c3df` run is the counter-example that gives the inflation a shape**: 28 126 m for a 27 283 m route (+3.1 %), 5 trace segments for 835 points, and only **2** non-mock fixes inside the window, both in its last 0.2 s. Six runs now line up as "clean fix stream → correct distance", so control the real providers rather than treating the accumulator as random | `trajectcontrole-a90c3df.tsv` and the report | — |

## Q7–Q9 were the ones `ba74e40` was meant to fix, and `a90c3df` re-measured them

The `689c580` capture answered them against a route that drove the E40 sections **backwards**, east
to west, at an implied 117 km/h. That is why Q8's early clear could not be closed out: with the
route running against the direction the measurement is defined in, an unexplained termination could
have been the app or could have been the route, and the recording cannot distinguish them.

The `trajectcontrole.txt` committed at `ba74e40` exists precisely to remove that ambiguity — one
relation, `15682532`, driven west → east in the direction the measurement runs, entry gantry at
second 166 and exit gantry at second 548, an 8.00 km transit in 382 s. **So the correct value for
Q7 is a chip that appears shortly after second 166, settles near `Ø 75`, and clears at second 548.**
Anything else is the defect Q8 points at.

**That replay has been run, at `a90c3df` on 2026-08-13.** Q7–Q9 are re-measured below and all three
came out correct; the two attempt sections that follow are kept as the record of how it got blocked
twice first.

## Attempt on the OnePlus 11 at `ba74e40`, 2026-08-12 — blocked, nothing recorded

| | |
|---|---|
| Commit | `ba74e40` on `refactor/mapscreen-split` |
| Device | OnePlus 11 (`CPH2449`, `OP594DL1`), serial `50043ff9`, **Android 16, SDK 36**, 1080×2412 at density 480 |
| App | `io.github.maxke24.detour.debug` v1.74 built from `ba74e40` and installed with `install -r` — **succeeded** |
| Harness | `com.jellemax.mocklocation` v1.0 installed — **succeeded**; designated — **refused** |
| Outcome | **No replay ran. No frames, no logcat, no TSV.** |

`appops set com.jellemax.mocklocation android:mock_location allow` is refused by ColorOS:

```
java.lang.SecurityException: uid 2000 does not have android.permission.MANAGE_APP_OPS_MODES
```

and `pm grant` is refused the same way (`GRANT_RUNTIME_PERMISSIONS`). This is not a missing step —
on this OEM the adb shell simply does not hold those permissions, and no invocation of `appops`
recovers them. Confirmed by running the harness anyway, which fails four times per fix:

```
E MockLocation: addTestProvider(fused) failed: java.lang.SecurityException:
    com.jellemax.mocklocation from uid 10407 not allowed to perform MOCK_LOCATION
```

**To unblock, a human has to designate the harness by hand:** Settings → System → Developer
options → **Select mock location app** → `DetourMockLocation`. Developer options are already
enabled (`development_settings_enabled=1`). Once that is set, `start-replay.sh` runs unmodified and
its own designation check passes — nothing else about the recipe changes. This was deliberately not
done by driving Settings with `input tap`: a mis-tap in Developer options can silently toggle
something with consequences (OEM unlocking, "don't keep activities", revoking USB debugging), and
that is not a risk worth taking blind on this device.

The app's `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` were found **already granted**
(`USER_SET`), so the permission half of the setup needs nothing.

**Device state, for the record:** nothing was uninstalled, cleared, revoked or reset. The release
variant `io.github.maxke24.detour` — which holds the user's real trips — was **not** installed
over, launched, cleared or touched in any way; its `lastUpdateTime` is still 2026-08-11 17:31:17.
No device setting was changed: `screen_off_timeout` was read as **300000** and left at 300000, and
`stay_on_while_plugged_in` was read as **0** and left at 0. `svc power stayon usb` was never run,
because there was no recording to keep the screen awake for; a setting changed and restored for
nothing is just churn with a failure mode. The probe route file written into the harness's `files/`
was deleted, and the harness's foreground service was stopped with `am stopservice` (which is what
calls `removeTestProvider`) rather than force-stopped — though it had registered no providers to
leave stale.

## `stop-start` on the OnePlus 11 at `fca3c35`, 2026-08-12 — the attempt above, unblocked

The harness was designated by hand in Developer options between the two sessions, so
`start-replay.sh` ran unmodified and the whole route replayed. **This is a later observation, not a
"before" for anything**: it post-dates every stage-2 commit and it is a different device. Full write-up
in [`../../../.superpowers/sdd/replay-stop-start.md`](../../../.superpowers/sdd/replay-stop-start.md);
what follows is the part a later run is measured against.

| | |
|---|---|
| Commit | `fca3c35`; `git diff --stat ba74e40..fca3c35` touches only the harness manifest and this file, so the installed debug v1.74 *is* the app code at `fca3c35` and nothing was rebuilt. **The branch was then merged forward to `e0c49d5` (upstream main) while this run was being analysed, changing 38 files under `app/`+`shared/` — so this run is a reference for `fca3c35`, not for `e0c49d5`.** The merge left `tools/mocklocation/` untouched and `routes/stop-start.txt` byte-identical |
| Device | OnePlus 11 (`CPH2449`), serial `50043ff9`, Android 16 SDK 36, 1080×2412 at density 480, **dark theme** |
| Release variant | **installed on this device**, unlike the Z Fold. Force-stopped by the script, never restarted during the run, `lastUpdateTime` unchanged |
| Route | `../routes/stop-start.txt` at `fca3c35`, `intervalMs=1000`: 762 fixes, 9.704 km. Holds over fixes 359–370, 395–436, 672–686, 696–706 |
| Cadence | **1.0156 s/fix** (Z Fold: 1.0224). Completed 762/762; 247 frames at 3.3 s |
| Overpass | **reachable from the phone** (unreachable from the host at the same time), so this run *does* carry ambient-limit evidence: sign on at fix 93, values 30 / 50 / 120 |

- **One trip, mode `CAR`**, `distanceMeters` 537 025.5 (×55.3), `topSpeedMps` 1359.93, duration 943.2 s
  of which 772.9 s is the replay. Auto-stop did not fire at any of the four standstills, which is
  correct. **Auto-end cannot be exercised by a replay** — the 5-minute check runs in
  `onTripLocation`, so it needs fixes; the trip was closed with the End trip button, as at `5fc8e90`.
- **Where the inflation comes from, measured this time.** Distance is *correct* for the first ~100
  fixes (371 m at fix 34, 1,3 km at fix 101), grew **8,6 km during 20 s of the 41 s standstill** while
  every replayed fix carried the same coordinate, and gained its last **7,6 km** *after* the providers
  were removed at t+773.9 s — the route's last point is 6 760 m from its first. `topSpeedMps` landed in
  the same 14 s window (`…-distance-jump.png`: frame 231 at t+778 still reads 529,4 km / 132 km/h,
  frame 235 at t+792 reads 537,0 km / 4 896 km/h and never changes again). So a live non-mock
  provider was interleaved into the app's stream, and one loose fix is enough:
  `lastLocation = location` is assigned for every fix regardless of accuracy
  (`TripTrackingService.kt:983`) while the distance accumulator gates only on the *new* fix
  (`:1043-1048`) and `speedOf`'s fast path not at all (`:1110`). It leaves no trace evidence because a
  >500 m point is flushed into a buffer of its own (`:1138`) and `TraceStore.append` drops segments
  under 2 points (`TraceStore.kt:44`).
- **Trace: 48 segments, 319 points** for 9.704 km (hops median 31.8 m, summed 9 670 m, stored
  `speedKmh` 0–132.4, zero points more than 200 m off route, zero hops over 500 m). Stops found by
  `profile-trace.py`: **0 per segment**; **1 of 4** when the segments are concatenated (Δt 39.6 s over
  26 m). The three short holds (10–14 s) are under `--min-seconds 12`, and the long one straddles a
  segment seam, so a stop under ~15 s is not recoverable from this trace.
- **HUD: eases down, then leaves the screen — it does not hold a zero.** 28 → 26 → 25 → 22 → 16 → 16
  km/h and then no dial (`…-dial-easing.png`); `SpeedHud` is only composed above 1.4 m/s, and
  `SpeedLimitSign` lives inside it, so the sign clears with the dial every time. Faded/returned at
  **4/4** stops: fixes 361→371, 398→437, 677→690, 700→710 (±3 fixes at this cadence).
- **Camera parked and kept its bearing.** Pixel-identical map region at frames 123→124 (RMSE
  0.000000) and 129→130 (0.000212); across the whole stop (121→132, 36.8 s) the best alignment is
  dx = dy = 0 with the residual only on feature outlines — no pan, no rotation, **no north-up snap
  even though the harness reports bearing 0° on every held fix**. Following resumed unaided after all
  four stops. **0 stalls in 246 frame pairs.**
- **Frames 125 and 128 have a blank map surface and no puck** — the same `screencap` mid-swap artifact
  as `urban-limits` fixes 453/579, and the only source of the 0.04–0.05 RMSE in that window
  (`…-blank-surface.png`).
- **No crash.** 400 019 raw lines, no `FATAL EXCEPTION`, no `E AndroidRuntime`, no `F DEBUG`, no ANR.

**Route-file defect found by this run.** Every one of `stop-start`'s four holds ends with a single fix
implying **92–99 km/h** (fixes 370, 436, 686, 706) followed by a duplicate point implying 0, before the
route settles at 25–30 km/h. `urban-limits` has the same defect on its one 18 s hold (exit fix 1093 =
92 km/h, then 0, then 47); `trajectcontrole`'s single 7 s hold is clean (exit 4 km/h, then 20). `detour-gps-replay` warns about exactly this
("25 m covered in one 1000 ms line reports 90 km/h on that fix"). It is why the trip card's Top reads
97 then 99 km/h at the first two stops, and why the dial one fix after the 41 s stop shows **71 km/h in
red** against an ambient 30. Those readings are faithful to the file; the file is wrong.
`gpx2route.py` should spread the first hop after a hold over at least the `--pull-away-kmh` interval.

## This baseline post-dates stage 2 and cannot verify it

Stated plainly because the filename pattern invites the opposite assumption. `FollowCamera`
(`4a03ead`) and `NavPolicy` (`2452dfc`) both landed **before** `5fc8e90`, the commit the first four
runs were captured at:

```sh
git merge-base --is-ancestor 4a03ead 5fc8e90   # true
git merge-base --is-ancestor 2452dfc 5fc8e90   # true
```

So this is a valid reference for **stage 3 onward**, and it is *not* a "before" for stage 2's camera
and nav-policy extractions. There is no recording of the code as it stood before those, and there
cannot be one now — that is the cost of having deferred 0c past stage 2, and it is not recoverable
by re-recording. Stage 2's evidence is its unit tests, which is what it has.

For the same reason `bac833a` ("clear the ambient speed limit when navigation starts or ends") sits
between `5fc8e90` and the `689c580` re-run, so the two `trajectcontrole` captures are not a clean
A/B of each other either. The `689c580` run is a *later* observation, not a control.

### A note on the filenames in this directory

The `.tsv` names embed the abbreviated SHA of the commit their run was captured
at — `stop-start-09fddde.tsv` and its siblings. Those abbreviations are
**pre-rewrite**: this branch's history was purged on 2026-08-13 (see
`docs/refactor/mapscreen/DECISION.md`) and every commit from `21a02b4` onward
took a new SHA. The filenames were deliberately left alone so the paths keep
resolving, which means a filename's SHA no longer names a commit you can look
up. The prose in this file cites the post-rewrite SHAs. Renaming the files to
match is a safe follow-up nobody has needed yet.

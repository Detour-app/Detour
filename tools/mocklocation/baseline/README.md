# Pre-refactor behavioural baseline

**These are the reference recordings for the MapScreen refactor chain. Do not regenerate them
after a behaviour-touching commit.** They were captured while the code was still original; once
a commit changes behaviour, the "before" side of every A/B comparison in stages 2–4 is gone, and
re-recording produces files that look the same and mean nothing. Work item 0c of
[`../../../docs/refactor/mapscreen/specs/stage-0-verification-baseline.md`](../../../docs/refactor/mapscreen/specs/stage-0-verification-baseline.md).

If a later run disagrees with a number here, the number here is the baseline. Add a second file;
do not overwrite one of these.

> ## ⚠ The route files changed at `923e16c`. Read this before comparing anything.
>
> Every fix index in this file was recorded against the route files as they stood at `09fddde`.
> `923e16c` replaced all three (`../routes/README.md` says why), so **an index here does not
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
> git show 09fddde:tools/mocklocation/routes/trajectcontrole.txt
> ```
>
> **This is a limitation of this baseline, not a licence to re-record it.** The qualitative
> findings — the HUD easing to zero and returning at every standstill, the camera holding bearing
> while parked, following resuming unaided, the sign clearing 3 fixes after the last snap, 0 stalls
> in 3 909 frame pairs — are all properties of the *code*, and none of them depends on which
> kilometre of the E40 the route covered. Those still stand. It is the fix-index arithmetic that
> does not.

**`trajectcontrole` has a second capture, at `b29d014`, and it is not a replacement.** The
`09fddde` run of that route recorded no section behaviour at all because both Overpass mirrors
were refusing (below), so for the section quantities there was no "before" to lose — which is
the only reason re-recording was legitimate under the rule above. Everything `09fddde` *did*
measure on that route (the HUD fade/return cycles, the stall count) is still the baseline; the
`b29d014` files add the section quantities and nothing else supersedes them. Both sets of files
are kept side by side, named by their commit. Do not use the `b29d014` run as the "before" side
of any A/B: it was captured after `d452d5b`, `b29d014` and the rest of stage 2 had landed.

## What was captured

| | |
|---|---|
| Commit | `09fddde` on `refactor/mapscreen-split`; tree clean apart from untracked `.devcontainer/` |
| Device | Samsung Galaxy Z Fold 3 (`SM-F926B`), serial `RFCT42HS9WY`, **Android 15, SDK 35** |
| Display | inner panel, 1768×2208 at density 420 (2.625 px/dp), display id `4630947232161729154` |
| App | `io.github.maxke24.detour.debug` v1.74, built from `09fddde` |
| Harness | `com.jellemax.mocklocation` v1.0, designated (`appops get` → `MOCK_LOCATION: allow`) |
| Date | 2026-08-12, 12:23–14:10 local (CEST) |
| Routes | `../routes/{stop-start,trajectcontrole,urban-limits}.txt` at `intervalMs=1000`, **as those files stood at `09fddde`** — unmodified then, but all three were replaced at `923e16c`; see the warning above |

The `trajectcontrole` re-run differs from that table in three places and nowhere else: commit
`b29d014` (tree clean apart from untracked `.devcontainer/` and an untracked GPX), date 2026-08-12
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
#   screencap every 2000 ms (1500 ms for urban-limits; 1000 ms for the b29d014
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
five from `09fddde`, one more from the `b29d014` re-run (`files/trips.json` did not exist before;
the release variant is not installed, so no real history was ever at risk) — and the harness
installed and still designated. The test providers were removed cleanly after both sessions:
`dumpsys location` afterwards shows real fixes again (gps hAcc 10.6 m / 29 satellites after
`09fddde`, hAcc 4.53 m / 21 satellites after `b29d014`), not a device pinned to the last replayed
coordinate. Delete the synthetic trips from the app if you want the debug history empty; do **not**
`pm clear` to do it. Nothing was uninstalled, cleared, revoked or reconfigured in either session;
the app was installed with `install -r` over itself, which keeps its data.

## Artifacts

`<sha>` below is the commit the run was captured from — `09fddde` for the original three routes,
`b29d014` for the `trajectcontrole` re-run. The formats are identical between the two, on purpose:
the whole point of the re-run was a file that can be compared against the first.

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
`avg_blue` 0.0206 = `Ø 121`, 0.0226 = `Ø 120`** (from `trajectcontrole-b29d014`; at `09fddde` this
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
`urban-limits` 1.0189, `trajectcontrole` at `b29d014` 1.02030, from the first and last
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

### trajectcontrole at `09fddde` — 984 fixes, 17.0 km · Overpass unavailable · **did not test what it exists to test**

Superseded for the section quantities by the `b29d014` re-run in the next subsection; still the
baseline for this route's HUD and stall behaviour.

**No average-speed chip and no speed-limit sign ever appeared** (`avg_blue` and `sign_red` are 0
across all 494 frames), and no speed-camera markers were drawn on the map
(`…-mid-route.png`, six frames across the run at 66–124 km/h on the E40).

That is one cause, not three: `SpeedCameras.near()` feeds both the sections and the camera
markers, `RoadRoulette.speedLimitWays()` feeds the sign, and both are Overpass. So:

- Section entry gating, the running average, its settled value, when it clears, and the
  back-to-back transition over the shared gantry are all unrecorded **in this run**. They were
  recorded in the `b29d014` re-run below.
- The route's two sections start at 2.47 km, share a gantry at 6.36 km and exit at 14.35 km
  (`../routes/README.md`), which is **fixes 72, 218 and 463** of this route file — all of it
  inside the first eight minutes, so a re-run needs only a few minutes of a healthy mirror.
  (`09fddde`'s note said 73/219/464; recomputed against the route file with the gantry
  coordinates fetched from the OSM API, the closest fixes are 72, 218 and 463.)

What it does establish:

- **11 HUD fade/return cycles** across the route's nine reconstructed standstills (fixes
  `536–542`, `548–556`, `634–641`, `743–756`, `762–796`, `817–832`, `838–852`, `936–947`,
  `963–976`) plus two sub-threshold creeps — all in `…-events.tsv`.
- **0 camera stalls in 493 frame pairs**, while the ambient-limit collector was suspended on
  failing Overpass calls for most of the run. That is the useful half of the accident.
- The nine standstills are all at fixes ≥536, i.e. **after** both sections end (the second exits
  at 464). `../routes/README.md` says they are "inside the sections"; they are not.

### trajectcontrole re-run at `b29d014` — 1029 frames · Overpass healthy for the first ~2.5 min

Captured 2026-08-12 18:02:05–18:19:20 CEST from `b29d014` (app v1.74), same device, same display
id, same route file, same `intervalMs=1000`. The replay ran to the end — `replay finished at point
984/984`. Measured cadence for this run: **1.02030 s per fix** (984 pushes, first 18:02:05.654,
last 18:18:48.606), so every fix index below is that-corrected.

**Captured at a 1000 ms cadence, not `09fddde`'s 2000 ms** — one frame per fix. That was a
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
  matching `09fddde`. The single HUD-absent window, fixes 5–8, is the auto-start gate not yet
  satisfied (`HUD-ON` at fix 9), not a freeze.
- **Sign**: `SIGN-ON` at fix 11 showing 120, then `SIGN-CLEARED` at fix 129 (cum 4300 m) and never
  again — the held set running dry once Overpass began refusing, the same shape as `urban-limits`
  run 2. The section chip and the sign therefore cleared for unrelated reasons, 48 fixes apart.
- **Nine standstills, HUD fade/return throughout the second half**, consistent with `09fddde`.
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

## Overpass was the limiting factor, and the app's own request rate is why

Timeline, measured — poll of `/api/status` and of a real way query every ~60 s from the
devcontainer on the same WAN as the phone's Wi-Fi, plus `curl` from the device itself:

| Time | `overpass-api.de` | `overpass.kumi.systems` |
|---|---|---|
| 12:22–12:36 | working (sign appeared throughout `stop-start`) | — |
| 12:43–13:38 | refusing: `000` in 0.2–7 s; from the device, `curl (7) couldn't connect` after 7.2 s | `/api/status` 200 but in 15.4 s; `/api/interpreter` gave **no response at all in 90 s** |
| 13:39–13:47 | healthy: status 200 in 0.1–0.2 s, a real way query 200 in **0.47 s** | still unusable |
| 13:48:42 → 14:10+ | refusing again — **3.5 minutes after the second `urban-limits` replay began** | still unusable |
| 17:55–18:02 | recovered, but flaky: the same small query answered `200, 200, 504` and then `200, 504, 200, 200` — 3/4 immediately before the `b29d014` replay was started | — |
| 18:02–18:03:36 | healthy through the start of the run: status 200 in 0.17 s. Both Overpass-fed features worked — sign at fix 11, chip at fix 73 | — |
| 18:04:31 → 18:37+ | refusing again — **~2.5 minutes after the `trajectcontrole` replay began**, and still refusing 25 minutes after it ended | `overpass.private.coffee` also `000`; `overpass.osm.ch` answers but is a Switzerland-only extract, so it returns 0 elements for this bbox |

The `b29d014` row repeats the `09fddde` pattern almost exactly: a healthy window, a replay started
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
`b29d014` re-run exactly as much as to the original three, and re-reading it as field behaviour
gets easier, not harder, now that the pattern has repeated. Replay compresses geography in a way
real driving does not: three routes run back to back over one bounding box, a fourth replay of one
of them the same evening, repeatedly, from a single IP that had also served a 335 kB Overpass query
for the gantry lookups earlier the same day. A real user drives through an area once, from a
residential or mobile address. The honest claim is about the *mechanism*, not the frequency — and
the `b29d014` run puts a number on the mechanism (~143 requests for one 17 km route, 136 of them
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
| trajectcontrole (`09fddde`) | 17.0 km | 4 633 883 m (×273) |
| urban-limits run 1 | 23.5 km | **24 431 m (correct)** |
| urban-limits run 2 | 23.5 km | 3 319 833 m (×141) |
| trajectcontrole (`b29d014`) | 17.0 km | 5 363 588 m (×315) |

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
timeout — the `b29d014` re-run adds fourteen minutes of a *refusing* mirror at a 1 s cadence, which
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
`09fddde` the non-mock fixes are at 12:43:24.039 (first mock: 12:43:24.327) and 12:59:55.701 (last
mock: 12:59:51.818), and on the `b29d014` re-run the 2953 handled fixes are *all* 4.00 with the
first other value at 18:18:53.722, after the last mock push at 18:18:48.606. Every recorded trace
also stays on its route. No real provider contaminated any run, and the teleporting the skill warns
about did not occur.

That check earns its keep on the re-run specifically: contamination would have been the tidiest
explanation for the early clear (one desk fix ~10 km off-route would add enough to `accMeters` to
trip `overshot` instantly), and the log rules it out.

## What could not be captured

- **Average-speed sections: captured at `b29d014`, and the answers are above.** Entry gating, the
  running average and its settled value, and whether the readout appears twice across the shared
  gantry are all recorded. What that run leaves open is narrower but sharper: **why the measurement
  terminated 306 m into a 3852 m section**, which none of the three exit conditions can account
  for. Settling it needs `SpeedCameras.near()`'s own query replayed so the contents of
  `speedSections` can be seen, and Overpass refused for the whole post-run window. That is one
  query against a healthy mirror, not another replay — **do not re-drive the route for it.**
- **Exit detection at a real gantry**, therefore, is still unmeasured: the measurement never
  survived far enough to reach one. So is the shared-gantry re-arm as a *positive* observation —
  what is recorded is that it did not happen, which is only half of what stage 3 wants.
- **The urban half of `urban-limits`:** posted-limit changes, cross streets and the frontage
  road, for the same reason. The sign baseline that does exist covers 120 on the motorway and the
  30/50/70/120 sequence on `stop-start`.
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

| # | Quantity | Baseline value | Read from | Still valid after `923e16c`? |
|---|---|---|---|---|
| Q1 | Fixes from the last successful speed-limit snap to the sign clearing | **3 fixes ≈ 3.1 s** — seen twice independently: `stop-start` fix 470→473, `urban-limits` run 2 fix 609→612 | `stop-start-09fddde-events.tsv`, `urban-limits-09fddde-events.tsv` | **Yes.** A latency in fixes, not a position. This is the 0d acceptance criterion; if it shrinks, the `StateFlow` drops were load-bearing and the 3-miss hysteresis needs retuning in that same commit |
| Q2 | Does the sign ever show a cross-street or frontage-road value? | **No.** No wrong-road value on any of five runs. The one near-miss is a change to 120 three fixes before the route reaches motorway pace — the sign picking up the motorway from the acceleration lane, which is correct | `…-sign-values.png` | **Yes**, as a yes/no |
| Q3 | Speed-limit values actually exercised | **30, 50, 70, 120** (all on `stop-start`); `urban-limits` and `trajectcontrole` only ever displayed **120** before their held set ran dry | `stop-start-09fddde-events.tsv` | **Yes** |
| Q4 | Camera or HUD frozen > 1 s | **0 stalls in 3 909 frame pairs** across five runs, including fourteen minutes against a *refusing* Overpass mirror at a 1 s cadence | `…-stall.tsv` (all five) | **Yes** |
| Q5 | Does the speed HUD ease to zero and hold at a standstill, and return? | **Yes, at 4/4 stops on `stop-start`**, and across all nine on `trajectcontrole`. Fade lands 1–2 fixes into the stop, return 2 fixes after moving off. The easing is gradual (28 → 8 → 19 → 16 km/h, then gone), not snatched away | `stop-start-09fddde-events.tsv`, `…-stop2-window.png` | **Latency yes; the fix indices no** — the stops now start at the same fix but run 3 fixes longer |
| Q6 | Does the map camera park and resume? | **Yes.** Frame-to-frame map RMSE is *exactly* 0 for five consecutive pairs inside the 38 s stop — no pan, no rotation, and no north-up snap. Following resumed unaided after all four stops; no re-centre tap was needed | `stop-start-09fddde-stall.tsv`, `…-bearing-hold.png`, `…-camera-park.png` | **Yes** as a yes/no; the RMSE-zero run is now 3 fixes longer |
| Q7 | Does a section average appear, where, at what value? | **Yes — at fix 73, 34 m past the entry gantry, reading `Ø 121`, settling to `Ø 120` and holding it for eight frames** while the route was doing 121–124 km/h | `trajectcontrole-b29d014-events.tsv`, `…-chip-values.png` | **No — recorded against the superseded route.** See below |
| Q8 | Does it clear at the far gantry? | **No. It cleared at fix 81, 306 m into a 3852 m section, 3529 m short of the exit gate** — and none of the three exit conditions (`reachedEnd`, `overshot`, `timedOut`) can fire there. Five alternative explanations were checked and ruled out | `trajectcontrole-b29d014-events.tsv` | **No** — same reason |
| Q9 | Does it re-arm at the shared gantry? | **No. Exactly two AVG events in 1029 frames**, one on and one cleared | `trajectcontrole-b29d014-events.tsv` | **No** — the new route only enters the second relation, so this is now testable as a *positive* |
| — | Trip `distanceMeters` / `topSpeedMps` / trace segment counts | **Unusable as A/B quantities on this harness.** Distance inflates ×90–×315 non-deterministically (same route, same device: ×273 once, ×315 the next); segments split on desk-induced activity-recognition `STILL` events | — | — |

## Q7–Q9 are the ones `923e16c` was meant to fix, and they are not yet re-measured

The `b29d014` capture answered them against a route that drove the E40 sections **backwards**, east
to west, at an implied 117 km/h. That is why Q8's early clear could not be closed out: with the
route running against the direction the measurement is defined in, an unexplained termination could
have been the app or could have been the route, and the recording cannot distinguish them.

The `trajectcontrole.txt` committed at `923e16c` exists precisely to remove that ambiguity — one
relation, `15682532`, driven west → east in the direction the measurement runs, entry gantry at
second 166 and exit gantry at second 548, an 8.00 km transit in 382 s. **So the correct value for
Q7 is a chip that appears shortly after second 166, settles near `Ø 75`, and clears at second 548.**
Anything else is the defect Q8 points at.

**That replay has not been run.** See the next section.

## Attempt on the OnePlus 11 at `923e16c`, 2026-08-12 — blocked, nothing recorded

| | |
|---|---|
| Commit | `923e16c` on `refactor/mapscreen-split` |
| Device | OnePlus 11 (`CPH2449`, `OP594DL1`), serial `50043ff9`, **Android 16, SDK 36**, 1080×2412 at density 480 |
| App | `io.github.maxke24.detour.debug` v1.74 built from `923e16c` and installed with `install -r` — **succeeded** |
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

## This baseline post-dates stage 2 and cannot verify it

Stated plainly because the filename pattern invites the opposite assumption. `FollowCamera`
(`8e0d765`) and `NavPolicy` (`09ee448`) both landed **before** `09fddde`, the commit the first four
runs were captured at:

```sh
git merge-base --is-ancestor 8e0d765 09fddde   # true
git merge-base --is-ancestor 09ee448 09fddde   # true
```

So this is a valid reference for **stage 3 onward**, and it is *not* a "before" for stage 2's camera
and nav-policy extractions. There is no recording of the code as it stood before those, and there
cannot be one now — that is the cost of having deferred 0c past stage 2, and it is not recoverable
by re-recording. Stage 2's evidence is its unit tests, which is what it has.

For the same reason `d452d5b` ("clear the ambient speed limit when navigation starts or ends") sits
between `09fddde` and the `b29d014` re-run, so the two `trajectcontrole` captures are not a clean
A/B of each other either. The `b29d014` run is a *later* observation, not a control.

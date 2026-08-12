# Pre-refactor behavioural baseline

**These are the reference recordings for the MapScreen refactor chain. Do not regenerate them
after a behaviour-touching commit.** They were captured while the code was still original; once
a commit changes behaviour, the "before" side of every A/B comparison in stages 2–4 is gone, and
re-recording produces files that look the same and mean nothing. Work item 0c of
[`../../../docs/refactor/mapscreen/specs/stage-0-verification-baseline.md`](../../../docs/refactor/mapscreen/specs/stage-0-verification-baseline.md).

If a later run disagrees with a number here, the number here is the baseline. Add a second file;
do not overwrite one of these.

## What was captured

| | |
|---|---|
| Commit | `09fddde` on `refactor/mapscreen-split`; tree clean apart from untracked `.devcontainer/` |
| Device | Samsung Galaxy Z Fold 3 (`SM-F926B`), serial `RFCT42HS9WY`, **Android 15, SDK 35** |
| Display | inner panel, 1768×2208 at density 420 (2.625 px/dp), display id `4630947232161729154` |
| App | `io.github.maxke24.detour.debug` v1.74, built from `09fddde` |
| Harness | `com.jellemax.mocklocation` v1.0, designated (`appops get` → `MOCK_LOCATION: allow`) |
| Date | 2026-08-12, 12:23–14:10 local (CEST) |
| Routes | `../routes/{stop-start,trajectcontrole,urban-limits}.txt`, unmodified, `intervalMs=1000` |

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
#   screencap every 2000 ms (1500 ms for urban-limits) for the route's duration + 4%
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

**State this left on the device:** five synthetic trips in the `.debug` variant's history
(`files/trips.json` did not exist before; the release variant is not installed, so no real
history was ever at risk), and the harness installed and still designated. The test providers
were removed cleanly — `dumpsys location` afterwards shows real fixes again (gps hAcc 10.6 m,
29 satellites), not a device pinned to the last replayed coordinate. Delete the synthetic trips
from the app if you want the debug history empty; do **not** `pm clear` to do it.

## Artifacts

| File | What it is |
|---|---|
| `<route>-09fddde.log` | logcat for the run, filtered (see below) |
| `<route>-09fddde.tsv` | **the machine-comparable record** — one row per captured frame |
| `<route>-09fddde-events.tsv` | derived: every HUD state change, dated to a fix index and a route position |
| `<route>-09fddde-stall.tsv` | derived: per-frame-pair map-region RMSE next to the speed the route was doing |
| `<route>-09fddde-t<sec>-<what>.png` | single frames at named moments, 50% scale |
| `<route>-09fddde-<topic>.png` | montages — several frames of one behaviour in one image |

`urban-limits-noverpass-*` is a **second, earlier run of the same route** kept on purpose: it is
the same drive with Overpass unavailable throughout, which is what isolates the Overpass-fed
behaviour from everything else. Same for `trajectcontrole`, whose only run had no Overpass at
all. Total directory size ~6.6 MB; trim it if that is too much for the repo, but keep the
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
`50`, 0.1233 = `70`, 0.1772 = `120`; HUD present → `dial_ink` ≈ 0.045–0.063.

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
`urban-limits` 1.0189, from the first and last `MockLocation` line and the number of pushes).
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

### trajectcontrole — 984 fixes, 17.0 km · Overpass unavailable · **does not test what it exists to test**

**No average-speed chip and no speed-limit sign ever appeared** (`avg_blue` and `sign_red` are 0
across all 494 frames), and no speed-camera markers were drawn on the map
(`…-mid-route.png`, six frames across the run at 66–124 km/h on the E40).

That is one cause, not three: `SpeedCameras.near()` feeds both the sections and the camera
markers, `RoadRoulette.speedLimitWays()` feeds the sign, and both are Overpass. So:

- **Section entry gating, the running average, its settled value, when it clears, and the
  back-to-back transition over the shared gantry are all unrecorded.** The question "does the
  average-speed readout appear twice?" has no baseline answer.
- The route's two sections start at 2.47 km, share a gantry at 6.36 km and exit at 14.35 km
  (`../routes/README.md`), which is **fixes 73, 219 and 464** of this route file — all of it
  inside the first eight minutes, so a re-run needs only a few minutes of a healthy mirror.

What it does establish:

- **11 HUD fade/return cycles** across the route's nine reconstructed standstills (fixes
  `536–542`, `548–556`, `634–641`, `743–756`, `762–796`, `817–832`, `838–852`, `936–947`,
  `963–976`) plus two sub-threshold creeps — all in `…-events.tsv`.
- **0 camera stalls in 493 frame pairs**, while the ambient-limit collector was suspended on
  failing Overpass calls for most of the run. That is the useful half of the accident.
- The nine standstills are all at fixes ≥536, i.e. **after** both sections end (the second exits
  at 464). `../routes/README.md` says they are "inside the sections"; they are not.

## Overpass was the limiting factor, and the app's own request rate is why

Timeline, measured — poll of `/api/status` and of a real way query every ~60 s from the
devcontainer on the same WAN as the phone's Wi-Fi, plus `curl` from the device itself:

| Time | `overpass-api.de` | `overpass.kumi.systems` |
|---|---|---|
| 12:22–12:36 | working (sign appeared throughout `stop-start`) | — |
| 12:43–13:38 | refusing: `000` in 0.2–7 s; from the device, `curl (7) couldn't connect` after 7.2 s | `/api/status` 200 but in 15.4 s; `/api/interpreter` gave **no response at all in 90 s** |
| 13:39–13:47 | healthy: status 200 in 0.1–0.2 s, a real way query 200 in **0.47 s** | still unusable |
| 13:48:42 → 14:10+ | refusing again — **3.5 minutes after the second `urban-limits` replay began** | still unusable |

Plain HTTP from the device worked throughout (`http://example.com` → 200), so this is not the
phone's network. `Http.kt` sets `connectTimeoutMillis = 5_000`, so a mirror that takes 7 s to
refuse fails every time, and the 15 s+ fallback cannot serve a 1.5 km way query inside the 30 s
request timeout.

**The pattern is the app hammering the mirror into blocking the IP.** The ambient-limit effect
refetches on a 10 s throttle whenever the vehicle nears the edge of its held circle, which at
motorway speed is every ~45 s, and the camera prefetch does the same on 15 s. Roughly 13 minutes
of that traffic (the whole of `stop-start`) was followed by an hour of refusals; once penalised,
resumed traffic re-blocked the IP within 3.5 minutes.

**Do not read this as measured field behaviour, and do not file it as one.** Replay compresses
geography in a way real driving does not: three routes run back to back over one bounding box,
repeatedly, from a single IP that had also served a 335 kB Overpass query for the gantry lookups
earlier the same day. A real user drives through an area once, from a residential or mobile
address. The honest claim is about the *mechanism*, not the frequency — and the mechanism is
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
| trajectcontrole | 17.0 km | 4 633 883 m (×273) |
| urban-limits run 1 | 23.5 km | **24 431 m (correct)** |
| urban-limits run 2 | 23.5 km | 3 319 833 m (×141) |

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

The measurements agree: **0 stalls in 2 881 frame pairs across four runs** (382 + 493 + 1 003 +
1 003), including two whole routes where the fetches were hanging until the 5 s connect timeout.
A stall here means a pixel-identical map region while the route says the vehicle was above
20 km/h; at a 1.5–2 s cadence, that catches any freeze of that length or longer. The single
identical pair in the set is `urban-limits` run 2 at fix 1091, the 1.5 s just after a standstill
at 20 km/h, before the camera easing moved a pixel.

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

**Inside all four replay windows, 100% of the fixes the platform handled carried accuracy 4.00 —
the harness's constant.** Every fix with any other accuracy is timestamped either a fraction of a
second *before* the first replayed fix or *after* the last one; e.g. on `trajectcontrole` the
non-mock fixes are at 12:43:24.039 (first mock: 12:43:24.327) and 12:59:55.701 (last mock:
12:59:51.818). Every recorded trace also stays on its route. No real provider contaminated any
run, and the teleporting the skill warns about did not occur.

## What could not be captured

- **Average-speed sections: nothing at all.** Entry gating, the running average and its settled
  value, exit detection, and whether the readout appears twice across the shared gantry. Both
  Overpass mirrors were unavailable for the whole `trajectcontrole` window. **Re-run
  `trajectcontrole` when a real way query answers inside 5 s** — the section events are all
  within its first eight minutes.
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

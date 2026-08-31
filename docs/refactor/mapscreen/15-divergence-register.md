# Divergence register — which copy survives, and who decides

Companion to `13-surface-independence-audit.md` (`13-surface-independence-audit.md`, in history at `b7f4c6f`), and an
input to `specs/stage-3-hazard-machines-to-shared.md` (`stage-3-hazard-machines-to-shared.md`, in history at `b7f4c6f`).

Read-only, no build. Every claim re-derived from the tree at **`a0f7f42`** on
`refactor/mapscreen-split`. Where audit 13 cites a `MapScreen.kt` line number, that citation is
stale — the file went 3204 → 1553 lines across stages 1 and 2 — so every line number below was
re-derived with `grep -n` rather than carried forward.

## Why this file exists

Detour implements the same behaviour on four client surfaces: `app/…/ui/` (phone Compose),
`app/…/car/` (Android Auto), `wear/`, and `iosApp/` (SwiftUI), over a KMP `shared/` core.

Where two copies are **identical**, extracting them is a pure refactor: the diff is provably
behaviour-preserving and nobody needs to be consulted. Where they have **drifted**, extraction
silently picks a winner, and that is a product decision wearing a refactor's clothes. Today the
repo has no place to record such a choice: `.claude/skills/detour-shared-core/SKILL.md:213`
states the rule *"Extract from the better copy, not the nearest one"* but gives no way to write
down which copy was judged better, or why. This register is that place.

The register does not make the decisions. It makes them *visible before* they are made, so a
human picks from a list instead of finding out from a diff six months later.

## How to read an entry

Each entry gives both behaviours neutrally, then a recommendation. Three verdicts are used:

- **survive** — one copy is better on evidence; extract from it and delete the other.
- **needs-a-human** — a genuine product trade-off; the register is not entitled to choose.
- **bug** — one side is simply wrong. Fix it on its own, before or after any extraction, never
  inside one. These are listed separately in §B.

Anything marked **UNVERIFIED** could not be established from the tree and must not be relied on.

---

## 1. Speed-camera chime: does an unknown posted limit fall back to the ambient sign?

**What.** When a speed camera is coming up, the app chimes only if you are over the limit. The
phone will use the speed-limit sign it read off the road when the route does not carry a posted
limit. The car will not — it stays silent instead.

**Copies.**

Phone — `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt:869-870`:

```kotlin
val limit = navProgressRef.value?.speedLimitKmh ?: ambientLimitRef.value
val tooFast = limit != null && fix.speedMps * 3.6 > limit + 3.0
```

Android Auto — `app/src/main/java/com/jellemax/detour/car/NavScreen.kt:413-414`:

```kotlin
val limit = progress?.speedLimitKmh
val tooFast = limit != null && currentSpeedKmh > limit + 3.0
```

There is a third, quieter copy of the same decision. `app/src/main/java/com/jellemax/detour/wear/NavRelay.kt:28`
sends the watch only the route's posted limit, never the ambient one:

```kotlin
put("speedLimitKmh", progress.speedLimitKmh ?: JSONObject.NULL)
```

and the car HUD does the same at `app/src/main/java/com/jellemax/detour/car/NavScreen.kt:231`
(`renderer.updateHud(currentSpeedKmh, p.speedLimitKmh)`) while
`app/src/main/java/com/jellemax/detour/car/SpinScreen.kt:150` passes the ambient one
(`renderer.updateHud(fix.speedMps * 3.6, ambientLimitKmh)`). So the car's split is structural:
its ambient-limit machine lives in `SpinScreen` (free-drive) and `NavScreen` (navigating) has no
access to it at all.

**How they differ, in what a user notices.** Drive a road GraphHopper has no `maxspeed` for —
common on Belgian secondary roads and on any recently-resurfaced stretch — towards a fixed
camera at 90 in a 70 zone. The phone chimes. The head unit says nothing. Same drive, same
camera, same speed, two answers.

**The documentation is wrong either way.** `README.md:383-385` states:

> Android Auto gets a car-sized spin: pick a radius, spin a destination, and drive
> it turn by turn on the head unit, with **the same map, speed readout and camera
> warnings as the phone.** Search works there too.

That sentence is false today. Whichever way this decision goes, `README.md` needs one line
changed, and that edit is part of the decision's commit, not a separate cleanup.

**Which is better, and why.** The phone's behaviour is the intended product — a camera warning
that goes quiet on unmapped roads is a warning you cannot trust, and the whole feature is a
trust feature. But the phone copy as written has a defect that makes "extract the phone version"
the wrong instruction: **`ambientSpeedLimitKmh` is never cleared when navigation starts.** Its
producer is gated off at `MapScreen.kt:733-734`:

```kotlin
LaunchedEffect(navigating) {
    if (navigating) return@LaunchedEffect
    TripTrackingService.lastFix.collect { fix ->
```

and the only writers are `:757` and `:762`, both inside that collector (`grep -n
'ambientSpeedLimitKmh' MapScreen.kt` → `241, 757, 762, 848, 1361`; no reset anywhere). So while
navigating, the fallback the warner reads at `:869` is frozen at whatever the sign said *when
navigation began*. Drive from a 30 zone onto a motorway with no posted limit in the route and the
chime judges you against 30 for the rest of the trip. The HUD is not affected — `:1360-1361`
correctly switches source on `navigating` — only the chime is.

So the right shape is: keep the fallback, and make the ambient tracker keep running (or be
explicitly cleared) while navigating. That is the extraction's job, and it is why this entry
blocks stage 3 rather than riding along with it.

**What a user loses either way.** Unify **on the phone's behaviour** and the car gains a chime
that can fire against a limit snapped from OSM, which is occasionally wrong — a mis-tagged
frontage road makes the head unit beep at you for nothing. Unify **on the car's behaviour** and
the phone loses camera warnings on every untagged road, silently, with no indication the feature
switched off.

**Blast radius.** Phone (chime gains a correct instead of a stale fallback), Android Auto
(gains chime coverage on untagged roads), Wear (unchanged — it draws no camera warning),
`README.md:383-385`.

**Recommendation: survive — the phone's fallback, with the staleness bug fixed in the same
extraction.** The stale-limit half is a bug (§B1) and is not a product decision. The
*existence* of the fallback is the product decision, and the evidence favours keeping it: the
car's omission has no written rationale, and its `git blame` shows it was never a decision at
all (see below).

**RESOLVED (staleness half) — `bac833a`, in favour of the car's reset.** Of the two shapes offered
at `:108-110` — keep the tracker running while navigating, or clear the value — the fix takes the
second, because it is the one the car already ships with a written rationale and because keeping
the producer running would put a second Overpass fetch on the fix collector during navigation,
which is entry 2's stall (§B4, stage 0d) rather than this one. `ambientSpeedLimitKmh` and its miss
counter are now cleared on every `navigating` transition, so the chime no longer judges against a
pre-route sign and the HUD no longer shows one after arrival. **Consequence to hold on to for
stage 3:** while navigating the fallback is now always null, so `CameraWarner` still takes two
limits — the ambient one is the free-drive source, where the chime has no other — but the
untagged-road coverage the phone appeared to have *during* navigation was never real, it was the
stale value. **Still open:** the *product* half, the car gaining the fallback at all, which is
what stage 3's machine 2 lands, along with `README.md:383-385`. **Unobserved:** Overpass is
rate-limited, so no replay confirmed the reset against a live sign.

**Deliberate, or drift?** Drift. `git blame -L 869,869 MapScreen.kt` → `b512351` (2026-07-16,
*"feat: speed cameras, Bluetooth vehicle auto-detect, walk mode"*) — the original feature
commit, fallback included. `git blame -L 413,414 car/NavScreen.kt` → `f5e29b9` (2026-08-04,
*"feat: Waze-like Android Auto navigation — map, speed, cameras, convoy"*) — the hand-port,
three weeks later, fallback dropped. No comment on the car side mentions the omission, unlike
its other deliberate departures, which are all commented.

**Blocks stage 3: yes.** Consumed by stage 3's machine 2, `CameraWarner`. `CameraWarner` cannot
be written until it is known whether it takes one limit or two.

---

## 2. The Overpass prefetch: inline in the fix collector, or its own job?

**What.** Both surfaces download road data (speed limits, camera positions) from Overpass while
you drive. The car does the download alongside its GPS handling. The phone waits for it inside
its GPS handling, which stops everything else until the download finishes.

**Copies.**

Phone speed limit — `app/…/ui/MapScreen.kt:735-751`, inside `TripTrackingService.lastFix.collect`:

```kotlin
speedLimitFetchMs = now
val ways = withContext(Dispatchers.IO) { RoadRoulette.speedLimitWays(pos) }
```

Phone cameras — `app/…/ui/MapScreen.kt:774-789`, same shape:

```kotlin
lastFetchMs = now
val result = withContext(Dispatchers.IO) { SpeedCameras.near(pos) }
```

Car speed limit — `app/…/car/SpinScreen.kt:270-286`:

```kotlin
if (fromCenter > RoadRoulette.SPEED_PREFETCH_RADIUS_M - LIMIT_FETCH_MARGIN_M &&
    now - lastLimitFetchMs > LIMIT_FETCH_THROTTLE_MS &&
    limitFetchJob?.isActive != true
) {
    lastLimitFetchMs = now
    limitFetchJob = lifecycleScope.launch { … }
}
```

Car cameras — `app/…/car/NavScreen.kt:389-404`, identical structure with `cameraFetchJob?.isActive != true`.

Both car copies carry a written rationale. `car/NavScreen.kt:377-384`:

> The Overpass fetch runs in its own coroutine rather than inline. This is the whole fix loop's
> hot path: `TripTrackingService.lastFix` is a StateFlow and its collector is sequential, so
> awaiting a mirror *here* suspended `onFix` itself — and with it the camera target, the HUD and
> the turn card — for however long Overpass took, while every fix that landed meanwhile was
> conflated away.

**Every threshold matches; only the structure differs.** Verified pair by pair:

| Value | Phone | Car |
|---|---|---|
| Speed-limit prefetch margin | `500.0` (`MapScreen.kt:742`, inline literal) | `LIMIT_FETCH_MARGIN_M = 500.0` (`SpinScreen.kt:52`) |
| Speed-limit fetch throttle | `10_000` (`:743`) | `LIMIT_FETCH_THROTTLE_MS = 10_000L` (`:53`) |
| Minimum speed to snap | `2.0` (`:737`) | `LIMIT_MIN_MPS = 2.0` (`:57`) |
| Misses before clearing the sign | `3` (`:759`) | `LIMIT_MISSES_TO_CLEAR = 3` (`:61`) |
| Camera prefetch margin | `1000.0` (`:780`) | `CAMERA_FETCH_MARGIN_M = 1000.0` (`NavScreen.kt:54`) |
| Camera fetch throttle | `15_000` (`:781`) | `CAMERA_FETCH_THROTTLE_MS = 15_000L` (`NavScreen.kt:55`) |
| Re-entry guard | none | `…FetchJob?.isActive != true` |
| Failure handling | throws out of the collector | `runCatching` + `Log.w`, keeps the old set |

**How they differ, in what a user notices.** On the phone, while an Overpass mirror is having a
slow ten seconds, the map stops moving, the speed dial freezes and the turn card stops counting
down. At 100 km/h that is 280 m of road during which the app looks broken. On the head unit it
does not happen. This is the mechanism behind the "choppy map rendering" report filed as
**maxke24/Detour#21**.

**Which is better, and why.** The car, unambiguously, and no trade-off exists — a conflating
`StateFlow` plus a suspending collector is a stall with no upside. Both surfaces already agree
on every number.

**What a user loses either way.** Nothing, in behaviour that anyone wants. There *is* a cost,
and stage 0d names it: the 3-miss clear hysteresis at `MapScreen.kt:759` was only ever tuned
against a fix stream that **had** these drops. Removing them makes the sign clear faster, in
fixes-to-clear terms. That must be measured, not assumed.

**Blast radius.** Phone only. The car is already correct.

**Recommendation: survive — the car's structure.** Already the settled position:
`DECISION.md:298-301` (contradiction 3), `specs/stage-3-hazard-machines-to-shared.md:92-96`, and
`SKILL.md:213-232`. Do not invent a third pattern.

**Already scheduled.** This is stage 0 work item **0d**
(`specs/stage-0-verification-baseline.md:128-146`), currently deferred because the retune check
needs replay route (ii), which does not exist. Stage 3's `SpeedLimitTracker` touches the same
stream a second time, so if 0d has not landed by then, stage 3 inherits the hysteresis trap
along with the machine.

**Blocks stage 3: yes, in ordering.** Consumed by machine 3, `SpeedLimitTracker`, and by machine
2's prefetch half. Stage 3's own constraint list says to verify the two sides agree before
choosing a source — they agree on values today and disagree on structure, so the answer is
"take the car's".

---

## 3. Camera easing `dt` clamp — 0.1 s on the phone, 0.25 s in the car

**What.** Both maps slide the camera smoothly toward the latest GPS fix instead of jumping. Both
put a ceiling on how much catching-up a single step may do, so a stall cannot teleport the map.
The ceilings differ.

**Copies.**

Phone — `app/…/ui/MapScreen.kt:1005-1006`:

```kotlin
// Clamp dt so a dropped frame or a stalled render doesn't teleport us.
val dt = ((ns - lastNs) / 1_000_000_000.0).coerceIn(0.0, 0.1)
```

Car — `app/…/car/CarMapRenderer.kt:395-396`:

```kotlin
// Clamp dt so a stalled render or a paused loop can't teleport.
val dt = ((ns - lastNs) / 1_000_000_000.0).coerceIn(0.0, 0.25)
```

The phone's speed-dial ease uses the same `0.1` at `MapScreen.kt:964`.

**The time constants are identical, and so is everything else.** `CAM_POS_TAU = 0.35`,
`CAM_BEARING_TAU = 0.5`, `CAM_ZOOM_TAU = 1.2` at `app/…/ui/MapCameraTuning.kt:22-24` and
`app/…/car/CarMapRenderer.kt:53-55`. The redraw-suppression epsilons match too —
`CAM_POS_EPS_DEG = 2e-6`, `CAM_ZOOM_EPS = 2e-3`, `CAM_BEARING_EPS_DEG = 0.1f`
(`MapCameraTuning.kt:38-40` ↔ `CarMapRenderer.kt:67-69`).

**The clocks driving them do not match, and that is the point.** The phone loop is
`withFrameNanos` (`MapScreen.kt:1002-1004`) — vsync, ~16.7 ms a tick, and it only runs while the
activity is resumed. The car loop is `delay(CAM_FRAME_MS)` with `CAM_FRAME_MS = 33L`
(`CarMapRenderer.kt:61`, ~30 fps), because — per its own comment at `:57-60` — the car map lives
on a `VirtualDisplay` that keeps rendering with the phone's screen off, and vsync callbacks do
not fire there.

So `0.1 s` is ~6 phone frames and `0.25 s` is ~7.5 car ticks. **Expressed in frames dropped, the
two clamps are nearly the same number.** They look divergent only because they are stated in
seconds against different frame budgets.

**How they differ, in what a user notices.** Almost nothing, and only in the rare case the clamp
exists for. After a stall longer than the clamp, both maps resume by covering at most ~63% of a
tau's worth of gap in one step; the car's larger ceiling lets it close slightly more of a long
gap in its first step after a pause, i.e. a marginally more visible catch-up lurch coming back
from a paused render. Neither value is reachable in normal driving.

**Deliberate, or drift?** Neither, in the sense the question implies. `git blame` gives
`a332fcf` (2026-07-10, *"feat: cut background drain, smooth the camera, add badges and friends"*)
for the phone's `0.1` and `fda8d2c` (2026-08-06, *"fix(car): stop the crash on roundabouts, and
give the car real turn-by-turn"*) for the car's `0.25` — the commit that introduced the car loop
whole, clamp comment and all. `git show fda8d2c` confirms `CAM_FRAME_MS`, the loop and the `0.25`
arrived together. Neither value has a written rationale for its magnitude; both comments explain
only *why a clamp exists*. **No evidence either number was chosen against the other.**

**Which is better, and why.** Neither, and this is the entry where a naive unification would do
harm. Collapsing both to one constant in seconds silently retunes whichever surface loses, for
no user-visible gain. If these are ever unified, unify **in frames** (`clamp = k × frame
interval`, k ≈ 6–8) and derive the seconds per surface — otherwise leave both alone.

**What a user loses either way.** Unify on `0.1` and the car's post-pause catch-up gets slightly
slower. Unify on `0.25` and the phone's gets slightly larger. Either is invisible without a
side-by-side replay, which does not exist.

**Blast radius.** Whichever surface loses its value. Nothing else — the two loops cannot share a
driver, so this constant is the *only* shareable part of them.

**Recommendation: needs-a-human, and the honest answer is "leave it".** This is the weakest item
in the register and it is included because `DECISION.md:271` lists "unify camera-easing constants
+ `smoothBearing`, point `car/` at them" as a ★★★★ four-way convergence. That convergence is
correct for the taus and the epsilons, which are already byte-identical and can be shared for
free. It is **not** correct for `dt`. Deduplicate the seven matching constants; leave the clamp
per-surface with a comment saying why.

**Blocks stage 3: no.** Camera easing is not a stage-3 machine. It is stage-1/stage-4 territory
and this entry exists so nobody folds the clamp into a "unify the tuning constants" commit.

---

## 4. GraphHopper maneuver sign table — four copies, one code apart

**What.** GraphHopper describes each turn as a number. Each surface turns that number into an
arrow. Four surfaces, four tables.

**Copies.** All four re-derived; audit 13 and `SKILL.md` both cite pre-split ranges.

| Surface | Location | Codes handled |
|---|---|---|
| Phone | `app/…/ui/Navigation.kt:57-71` `signIcon` | `-98 -8 8 -7 7 -3 -2 -1 1 2 3 4 5 6` |
| Wear | `wear/…/MainActivity.kt:53-67` `signIcon` | identical set, identical bodies |
| Android Auto | `app/…/car/NavScreen.kt:583-601` `maneuverType` | the same set **plus `-6`** |
| iOS | `iosApp/Detour/NavScreen.swift:232-249` `maneuverIcon` | the same set as phone/wear |

The car's extra branch, `app/…/car/NavScreen.kt:588`:

```kotlin
-6 -> Maneuver.TYPE_ROUNDABOUT_EXIT_CCW
```

`git blame -L 588,588` → `fda8d2c`, *"fix(car): stop the crash on roundabouts, and give the car
real turn-by-turn"*. **Deliberate, and it fixed a crash** — the car's `Maneuver` builder is
stricter than an icon lookup, so an unhandled roundabout-exit sign was not a wrong arrow there,
it was an exception.

Phone and wear are byte-identical bodies. Every other surface sends `-6` to its `else`/`default`
branch, i.e. draws "carry straight on" where GraphHopper said "leave the roundabout".

**iOS: check the current state, not the earlier claim.** Audit 13 §"Divergence has already
produced a live user-facing bug" describes iOS mapping `-3` → `arrow.uturn.left` and dropping
`-98/-8` and `±7` to `default`. **That is fixed on this branch** — `c7ef627` *"fix(ios): correct
maneuver arrows for sharp turns, U-turns and forks"* and `075b991` *"fix(ios): distinguish
keep-left/keep-right fork icons"*, filed upstream as **maxke24/Detour#20**. The current table
handles every code the phone does. Two glyph collapses remain, both documented at
`NavScreen.swift:220-231` and both direction-preserving:

```swift
case -3: return "arrow.turn.up.left"     // same glyph as -2
case -2: return "arrow.turn.up.left"
case -1: return "arrow.up.left"
case  7: return "arrow.up.right"          // same glyph as 1
```

SF Symbols at the iOS 17 deployment target has no distinct sharp-turn glyph and no confirmed
direction-distinct fork pair, so `±3` draws like `±2` and `±7` like `±1`. A keep-left fork looks
like a gentle left. **This is a platform-asset limitation with a written rationale, not drift.**

**How they differ, in what a user notices.** Leaving a roundabout on the phone, the watch or the
iPhone, the arrow says "straight on" at the moment you need "take the exit". On the head unit it
says the right thing. In practice `6` (enter roundabout) usually carries the exit number and
covers the common case, which is why this has not been reported — but the asymmetry is real, and
one surface fixed it and three did not.

**Which is better, and why.** The car's table is a strict superset and is the only one that has
been debugged against a real roundabout. It is the better copy.

**What a user loses either way.** Nothing on any surface. The three tables gain a branch; the
car's table loses nothing.

**Blast radius.** Phone, Wear, iOS. Note that **`wear/` cannot consume `shared/`** —
`wear/build.gradle.kts` has no `:shared` dependency, and `SKILL.md:52-56` and
`specs/stage-3-hazard-machines-to-shared.md:125-128` both say it should stay that way rather than
pull ktor/okio/datetime into a 185-line APK to dedupe a 16-line `when`. So a `shared/` extraction
recovers three of the four copies, and the watch keeps a hand-maintained one by design.

**Recommendation: survive — the car's code set (including `-6`), as a `shared/` sign→semantic
enum with a per-surface glyph table.** Two layers, not one: the *semantics* (`sign → SHARP_LEFT`)
are identical on all four and belong in `commonMain`; the *glyph* (`ImageVector` /
`Maneuver.TYPE_*` / SF Symbol) is per-platform and must stay so — iOS's collapses are legitimate
and would be destroyed by a shared glyph mapping.

**Blocks stage 3: no.** Not a stage-3 machine — stage 3 is the three hazard machines, and the nav
vocabulary is explicitly **out of scope** at `specs/stage-3-hazard-machines-to-shared.md:122-124`.
This is the register's clearest candidate for a standalone commit that needs no stage at all.

---

## 5. Trip auto-detection — nineteen constants agree, six rules do not

**What.** Both phones decide on their own when a drive has started and when it has ended, from
GPS alone. The numbers they use are the same. The logic around those numbers is not.

**Copies.** `app/…/tracking/TripTrackingService.kt:140-228` ↔
`iosApp/Detour/TripRecorder.swift:39-68`, whose header comment says so outright at `:39`:
*"Auto-detection thresholds (identical to the Android service)"*.

**The nineteen constants: all MATCH.** Verified value by value.

| Constant | Android | iOS |
|---|---|---|
| fast speed | `FAST_SPEED_MPS = 7.0` (`:141`) | `fastSpeedMps = 7.0` (`:41`) |
| probe speed | `PROBE_SPEED_MPS = 4.0` (`:142`) | `probeSpeedMps = 4.0` (`:42`) |
| fixes to start | `FAST_FIXES_TO_START = 3` (`:143`) | `fastFixesToStart = 3` (`:43`) |
| min run ms | `MIN_FAST_RUN_MS = 8_000L` (`:144`) | `minFastRunMs = 8_000` (`:44`) |
| min run m | `MIN_FAST_RUN_METERS = 120.0` (`:145`) | `minFastRunMeters = 120.0` (`:45`) |
| max start accuracy | `MAX_START_ACCURACY_M = 25f` (`:147`) | `maxStartAccuracyM = 25.0` (`:46`) |
| probe window | `PROBE_WINDOW_MS = 3 * 60_000L` (`:148`) | `probeWindowMs = 3 * 60_000` (`:47`) |
| stationary end | `STATIONARY_END_MS = 5 * 60_000L` (`:153`) | `stationaryEndMs = 5 * 60_000` (`:48`) |
| min auto trip | `MIN_AUTO_TRIP_METERS = 500.0` (`:154`) | `minAutoTripMeters = 500.0` (`:49`) |
| walk avg max | `WALK_AVG_MAX_MPS = 2.5` (`:159`) | `walkAvgMaxMps = 2.5` (`:50`) |
| walk judge ms | `WALK_MIN_JUDGE_MS = 90_000L` (`:160`) | `walkMinJudgeMs = 90_000` (`:51`) |
| walk top max | `WALK_TOP_MAX_MPS = 6.0` (`:163`) | `walkTopMaxMps = 6.0` (`:52`) |
| max lean | `MAX_PLAUSIBLE_LEAN_DEG = 65.0` (`:172`) | `maxPlausibleLeanDeg = 65.0` (`:54`) |
| lean EMA | `LEAN_EMA_ALPHA = 0.3` (`:177`) | `leanEmaAlpha = 0.3` (`:55`) |
| lean slew | `MAX_LEAN_SLEW_DEG = 20.0` (`:183`) | `maxLeanSlewDeg = 20.0` (`:56`) |
| min lean speed | `MIN_LEAN_SPEED_MPS = 3.0` (`:188`) | `minLeanSpeedMps = 3.0` (`:57`) |
| G EMA | `G_EMA_ALPHA = 0.15` (`:189`) | `gEmaAlpha = 0.15` (`:58`) |
| G slew | `MAX_G_SLEW = 0.5` (`:196`) | `maxGSlew = 0.5` (`:59`) |
| max G | `MAX_PLAUSIBLE_G = 2.0` (`:202`) | `maxPlausibleG = 2.0` (`:60`) |

Municipality cooldown matches too: `MUNICIPALITY_LOOKUP_COOLDOWN_MS = 60_000L` (`:210`) ↔
`municipalityCooldownMs = 60_000` (`:68`).

**Five Android constants have no iOS counterpart at all** (grep of `iosApp/` finds zero hits):
`SPEED_PROBE_WINDOW_MS = 60_000L` (`:151`), `EXIT_GRACE_MS = 2 * 60_000L` (`:152`),
`SENSOR_EMIT_INTERVAL_MS = 200L` (`:169`), `BOARD_TELEMETRY_STALE_MS = 2_000L` (`:207`),
`MODE_PRIORITY` (`:166-167`). Two of those (`BOARD_TELEMETRY_STALE_MS`, `MODE_PRIORITY`) depend
on the BLE moto board and paired-Bluetooth vehicle map, which iOS has no equivalent for —
legitimately absent. The other three are rules iOS is missing, below.

**Six behavioural rules differ.** These are the entry's substance.

**5a — Run distance: straight-line displacement vs cumulative path length.**

Android, `TripTrackingService.kt:1019-1033`:

```kotlin
val here = LatLon(location.latitude, location.longitude)
val runStart = fastRunStart              // a LatLon
…
val runDistanceMeters = RoadRoulette.distanceMeters(runStart, here)
```

iOS, `TripRecorder.swift:243-245`:

```swift
if let previous = previousLocation {
    fastRunMeters += location.distance(from: previous)
}
```

Android measures how far you have *got from where the run started*; iOS sums how far you have
*travelled*. On a straight road they agree. Circling a car park, or a stop-start crawl round a
block, accumulates iOS's 120 m without going anywhere. **iOS starts trips Android would not.**

**5b — Run duration: GPS timestamps vs wall clock.**

Android, `TripTrackingService.kt:1023-1032`:

```kotlin
// GPS timestamps, not wall clock: a batched burst of idle fixes all
// arrive at the same instant but describe minutes of driving.
fastRunStartMs = location.time
…
location.time - fastRunStartMs >= MIN_FAST_RUN_MS
```

iOS, `TripRecorder.swift:241-249`:

```swift
if fastRunStartMs == nil { fastRunStartMs = nowMs() }
…
let runMs = nowMs() - (fastRunStartMs ?? nowMs())
```

Android's why-comment names the exact failure iOS has: a batch of deferred fixes arrives in one
instant, so on iOS `runMs` is ~0 and the 8-second bar is never cleared by a batch, however much
driving it describes. **On iOS a batched delivery cannot start a trip; on Android it can.** Android
is right and says why.

**5c — Speed-only probe escalation exists only on Android.**

`TripTrackingService.kt:1009-1017`:

```kotlin
// One accurate fix at driving speed is enough to *look closer*, and that
// is the whole reason a drive used to take minutes to notice…
if (!probing) {
    probeUntilMs = System.currentTimeMillis() + SPEED_PROBE_WINDOW_MS
    stationary = false
}
```

iOS opens a probe window only from a `CMMotionActivityManager` automotive sample
(`TripRecorder.swift:402-409`). With no automotive hint — indoors-start, a bicycle, a
low-confidence classifier — iOS never escalates its fix rate, so it confirms a drive off
whatever cadence it happens to be on. **A drive starts noticeably later on iOS.**

**5d — Stationary-end threshold: 2.0 m/s vs 1.0 m/s, and one is gated on auto-start.**

Android, `TripTrackingService.kt:1069` and `:1082`:

```kotlin
if (speed > 2.0) lastMovingMs = now
…
if (autoStarted && now - lastMovingMs > STATIONARY_END_MS) {
```

iOS, `TripRecorder.swift:291-296` — as found, before `3928ce0` raised the threshold:

```swift
if speed > 1.0 {
    movingSinceMs = nowMs()
} else if let since = movingSinceMs, nowMs() - since > Self.stationaryEndMs {
    endTrip()
}
```

Two differences in four lines. **The threshold**: walking pace (1.0–2.0 m/s) counts as moving on
Android and as stopped on iOS, so pushing a bike or crawling in queue traffic keeps an Android
trip alive and lets an iOS one time out. **The gate**: Android only auto-ends trips it
auto-started; iOS ends any trip, including one the user started by hand. **An iOS user who starts
a recording deliberately and then sits still for five minutes loses it.** That one is a bug, not
a difference (§B2).

**5e — Distance and trace filters during a trip.**

Android, `TripTrackingService.kt:1045-1053`:

```kotlin
if (last != null && location.accuracy <= 50f &&
    location.time - last.time in 1..15_000
) {
    distance += last.distanceTo(location).toDouble()
}
if (location.accuracy <= 50f) {
    val p = LatLon(location.latitude, location.longitude)
    addTracePoint(p, location.time, speed)
```

iOS, `TripRecorder.swift:262-275`:

```swift
if step < Self.traceBreakMeters * 2 {
    current.distanceMeters += step
}
…
addTracePoint(location, speedMps: speed)
```

Android rejects a step whose fix is loose (>50 m accuracy) or stale (>15 s since the last one);
iOS accepts any step under 1 km. And Android gates the fog trace on accuracy during a trip while
iOS does not. **Recorded distance and the fog-of-war trace are computed from different fix sets,
so the same drive yields different numbers and a visibly noisier iOS trace.** Also note that
Android's auto-stop-at-origin rule (`:1055-1066`: >400 m away, then back within 120 m after 5
minutes) has no iOS counterpart at all.

**5f — Mid-trip mode retagging exists only on Android.**

Android calls `refreshTripMode()` on every trip fix (`TripTrackingService.kt:1106`) and on every
Bluetooth connect/disconnect (`:558, :561, :575, :636`), so a trip that reveals itself as a walk
by pace gets retagged mid-ride (`:688-696`). iOS calls `resolvedMode()` exactly once, at
`startTrip` (`TripRecorder.swift:140`); `grep -n resolvedMode TripRecorder.swift` → `140, 415`
only. **On iOS a slow ride keeps whatever mode was selected when it began, so a walk recorded on
the Car tab stays a car trip** — which then feeds the badge and coverage totals.

**Which is better, and why.** Android on 5a, 5b, 5c, 5e and 5f: each Android rule has a written
reason and iOS's is the unexplained simplification of a hand-port. 5d's threshold is
**needs-a-human** (see below); 5d's gate is a bug.

**What a user loses either way.** Unify on Android and iOS gains correctness but also Android's
complexity — five more rules to keep in step across two languages. Unify on iOS and Android loses
five deliberate defences against false-positive trips, all of which exist because a real ride
went wrong.

**Blast radius.** iOS, on every one. Android is unchanged except by whatever 5d decides.

**Recommendation: survive — Android, on 5a/5b/5c/5e/5f. 5d's `2.0` vs `1.0` is needs-a-human;
5d's auto-start gate is a bug.** The 5d threshold is genuinely arguable: `1.0` ends a trip
promptly when you park, `2.0` refuses to end one while you are still pushing the bike into the
garage. Both are defensible and this register is not entitled to pick.

**RESOLVED (5d threshold) — `3928ce0`, §C decision 3, in favour of Android's `2.0 m/s`.**
The rationale is now written beside the constant in `iosApp/Detour/TripRecorder.swift`, which is
what neither surface had.

**RESOLVED (5d gate) — `35b8993`, in favour of Android's `autoStarted` gate.** The stationary end
is now reached only for trips the iOS recorder started itself; a hand-started recording is the
user's to end. The "moving" stamp still updates either way, as Android's does. **Unobserved:**
nothing in this repo runs the iOS app, so this was verified by reading the two recorders against
each other. **Still open:** 5a, 5b, 5c, 5e and 5f.

**Blocks stage 3: no.** Trip auto-detection is explicitly **out of scope** at
`specs/stage-3-hazard-machines-to-shared.md:122-124` — *"they are a separate programme, not this
stage. Note them; do not start them."* This entry is that note. It is also the largest single
extraction in the repo (~270 Swift lines) and needs its own chain, not a work item.

---

## 6. The convoy relay protocol — two hand-written implementations of one wire format

> **Correction, after the shared-convoy-relay branch.** This entry's whole premise —
> `app/…/net/ConvoyLiveClient.kt` and `iosApp/Detour/ConvoyLiveClient.swift` as two independent
> hand-rolled implementations — stopped being true at `db5a107`. The codec, the state machine
> (peers, push-to-talk membership, the spin vote), peer pruning and the reconnect/backoff loop all
> moved into `shared/src/commonMain/kotlin/com/jellemax/detour/drive/{RelayProtocol,ConvoyRelay}.kt`,
> which both platforms now call through a small `RelaySocket` seam instead of each carrying its
> own copy. `app/…/net/ConvoyLiveClient.kt` is 236 lines now, not the 625 quoted below, and
> `iosApp/Detour/ConvoyLiveClient.swift` is 408 (241 and 473 are older still, from before that).
> Every `kt:`/`swift:` line citation in 6a–6f below is left as written rather than re-derived line
> by line — several now point past the end of the file they cite — because the entries describe a
> divergence in code that no longer exists in that shape at all, not a position that merely moved;
> re-deriving line numbers for deleted code would misrepresent the fix as a line-number drift
> rather than what it actually was. What still matters from this entry — whether the six leaks
> below stayed fixed — is what §D's `check-divergences.sh` fence verifies for 6a/6b against the
> current shared implementation now, not against either platform file any more.

**What.** A convoy is a group of riders sharing live position, push-to-talk audio and a shared
"spin" vote for where to go next, over one WebSocket relay. The Android and iOS clients implement
that protocol independently, in Kotlin and in Swift **— see the correction above: no longer true**.

**Copies.** `app/…/net/ConvoyLiveClient.kt` (625 lines) ↔
`iosApp/Detour/ConvoyLiveClient.swift` (473 lines). The Swift file's own doc comment concedes the
stakes; audit 13 §3.2 quotes it: the two copies *"have to agree or a convoy splits across two
destinations."*

**The good news first: the part that would split a convoy agrees exactly.** Not diffed line by
line — the question asked was whether any *behavioural rule* differs, and on the consensus-critical
rules it does not.

- **Every wire constant matches.** `LOCATION_SEND_INTERVAL_MS 2_000L` (`kt:113`) ↔
  `locationSendIntervalMs 2_000` (`swift:70`); `MIN_BACKOFF_MS 1_000L` / `MAX_BACKOFF_MS 30_000L`
  (`kt:114-115`) ↔ `.seconds(1)` / `.seconds(30)` (`swift:71-72`); `STALE_PEER_MS 20_000L`
  (`kt:121`) ↔ `stalePeerMs 20_000` (`swift:78`); ping period 20 s (`kt:126` ↔ `swift:73`).
- **The outbound frame set is identical** — `join`, `location`, `ptt_start`, `ptt_end`,
  `ptt_audio`, `spin_offer`, `spin_vote`. Both stamp `groupId` on every frame (`kt:376-379` ↔
  `swift:329-337`) and both drop a frame with no group.
- **Vote resolution is identical, tie-break included.** `app/…/map/GroupSpinRules.kt:7-13`
  `leadingSpinIndex` ↔ `iosApp/Detour/MapScreen.swift:339-347`: out-of-range votes ignored, strict
  `>` so ties go to the lowest index. The round-closing rule matches too
  (`app/…/ui/MapScreen.kt:567-579` ↔ `iosApp/Detour/MapScreen.swift:316-335`).
- **The backoff schedule and its reset condition match** (`kt:411, 424-425` ↔ `swift:198,
  219-220`), and `everJoined` means the same thing on both.
- **Neither filters self-frames** and neither has sequence numbers or dedup — both rely on the
  server's `exclude_user_id`. Agreeing to omit something is still agreement.

**Six rules do differ, and none of them splits a convoy — they leak or freeze one.**

**6a — iOS ignores the `left` frame.** Kotlin handles ten inbound types; Swift handles nine.
`app/…/net/ConvoyLiveClient.kt:573-576`:

```kotlin
"left" -> msg.optString("user").takeIf { it.isNotBlank() }?.let { user ->
```

`grep -n 'case "' iosApp/Detour/ConvoyLiveClient.swift` → `joined, error, location, ptt_start,
ptt_end, ptt_audio, spin_offer, spin_vote, place_event`. No `left`; it falls to `default: break`
(`swift:463-464`). The server does emit it — `server/sync/sync_server.py:2442, :2456`, documented
at `:1948`. **A rider who leaves the convoy stays on the iPhone's map.**

**6b — Peer pruning is a timer on Android and an event on iOS.** Kotlin sweeps every 5 s,
`app/…/net/ConvoyLiveClient.kt:401-402`:

```kotlin
delay(PEER_PRUNE_INTERVAL_MS)
val cutoff = System.currentTimeMillis() - STALE_PEER_MS
```

Swift prunes only from inside the inbound `location` branch — `swift:392` calls
`pruneStalePeers()`, defined at `:469-472`, and `grep -n 'pruneStalePeers'` finds no other caller.
**On iOS, if nobody is transmitting, nothing is pruned.** The last peer to go quiet — the
interesting case, because that is a rider who crashed or lost signal — sits frozen on the map
indefinitely. Combined with 6a, an iPhone can show a convoy that has entirely dispersed. The
`20_000` threshold itself matches.

**6c — Only Android can derive the relay URL from a custom server.**
`app/…/net/ConvoyLiveClient.kt:214-222`:

```kotlin
fun liveUrl(context: Context): String {
    BuildConfig.LIVE_URL.takeIf { it.isNotBlank() }?.let { return it }
    val base = RoutingServer.loadCustom()?.url?.trimEnd('/') ?: return ""
    return when {
        base.startsWith("https://") -> "wss://" + base.removePrefix("https://") + "/live"
```

`iosApp/Detour/ConvoyLiveClient.swift:224-229` reads only the baked-in value:

```swift
guard let url = URL(string: BuildDefaults.shared.liveUrl),
      !BuildDefaults.shared.liveUrl.isEmpty else {
    lastError = "No convoy relay configured"
    return false
}
```

`BuildDefaults.liveUrl` is the `DetourLiveURL` Info.plist value. **An iOS install pointed at a
self-hosted server with no baked-in live URL can never join a convoy**, where Android derives
`wss://<host>/live` from the same single `server.url` that `CONTRIBUTING.md:105-110` says covers
all four services. Both surfaces do report an error, so this is a capability gap, not a silent
failure. (Kotlin refuses to open the socket at all; iOS opens, fails, and backs off to one attempt
per 30 s. Same user-visible outcome.)

**6d — A malformed `location` frame lands in two different places.**
`app/…/net/ConvoyLiveClient.kt:544-545` uses `msg.optDouble(…)`, which yields **NaN** for a
missing field; `iosApp/Detour/ConvoyLiveClient.swift:386-387` uses `as? Double ?? 0`, which yields
**0.0** — the Gulf of Guinea. A peer with a malformed frame vanishes from the Android map (NaN
fails every draw) and appears off the coast of Africa on the iPhone, dragging the map fit with it.

**6e — iOS cannot detect a dead socket.** Kotlin sets `pingInterval(20, TimeUnit.SECONDS)` on the
OkHttp client (`kt:123-127`), which also **fails the connection** when no pong arrives. Swift's
`keepAlive` (`swift:270-277`) is:

```swift
while !Task.isCancelled {
    try? await Task.sleep(for: Self.pingInterval)
    task.sendPing { _ in }
}
```

The completion error is discarded, and `URLSessionWebSocketTask` has no pong deadline. **A
half-open connection — the normal outcome of driving out of coverage — is detected on Android and
not on iOS**, where the client believes it is connected and reconnects only when the OS eventually
errors the task.

**6f — Inbound audio and place-events are bounded on Android and unbounded on iOS.** Kotlin fans
both out through `MutableSharedFlow(extraBufferCapacity = 32, DROP_OLDEST)` (`kt:175-178`) and
`(16, DROP_OLDEST)` (`kt:191-194`); Swift calls `PttAudio.shared.play(pcm, from: user)`
(`swift:406`) and `CircleNotifications.shared.handleLiveEvent(…)` (`swift:461`) directly. Android
**drops** PTT audio when nobody is collecting; iOS never drops but also cannot absorb a burst.
Different failure modes, neither obviously better.

Two smaller ones, recorded for completeness: iOS sends close code `1001` on teardown
(`swift:176`) where Kotlin sends `1000` (`kt:294`) — the server ignores both; and iOS reconciles a
circle's notification membership incrementally (`swift:140-158`) where Android tears the whole
socket down and re-joins (`kt:275-281`), so on Android toggling a circle's notifications
interrupts a live convoy socket mid-ride.

**A claim to *not* re-file.** `app/…/map/GroupSpinRules.kt:54` has a guard the two live copies
lack — `if (candidateCount <= 0) return SpinRoundOutcome.Wait` — and both live copies index
`offer.candidates[…]` without it, which looks like a crash on a zero-candidate offer. **It is
unreachable.** Both inbound parsers reject an empty list first (`kt:611` `if
(!list.isNullOrEmpty())`, `swift:425` `guard !candidates.isEmpty else { break }`) and the outbound
send rejects one too (`kt:337`, `swift:294`). The reducer's guard is defence in depth against a
state the protocol cannot produce. Recorded here because it is exactly the shape of finding that
gets filed twice.

**How they differ, in what a user notices.** Nothing that splits a convoy across two
destinations — the failure the feature exists to prevent is genuinely prevented on both. What an
iPhone user gets instead is a map that accumulates ghosts: riders who left, riders who went quiet,
and a socket that thinks it is alive after it is not.

**Which is better, and why.** Android on 6a–6e; each is a rule iOS lacks rather than a rule it
does differently, and none has a written rationale for its absence. 6f is a genuine trade-off with
no clear winner.

**What a user loses either way.** Extracting the protocol into `shared/` is the largest single job
in the repo (~300 Swift lines against ~625 Kotlin) and would cost ~150 lines of rewriting
`org.json` parsing onto `shared/…/Json.kt` (audit 13 §6). Not extracting it means these five gaps
get fixed twice or, more likely, once. Extracting it means iOS gains Android's behaviour wholesale,
including 6f's dropping.

**Blast radius.** iOS, on all six. Android unchanged unless 6f goes the other way.

**Recommendation: survive — Android on 6a through 6e. 6f is needs-a-human but low stakes.**
And: **fix 6a and 6b in iOS Swift now, do not wait for the extraction.** They are a `case "left"`
branch and a timer, they are user-visible today, and the extraction they are waiting for is a
programme that has not been scheduled.

**RESOLVED (6a and 6b) — `aff8407`, in favour of Android's rules, in Swift, without waiting for a
`shared/` protocol.** `case "left"` removes the user from `peers` and `talking` exactly as the
Kotlin branch does, and a 5 s sweep matching `PEER_PRUNE_INTERVAL_MS` now runs for the life of the
connection loop — alongside it as on Android rather than per connection attempt, since peers held
while a dropped socket backs off go stale just the same. The `location` branch's own prune stays;
it costs nothing and keeps a busy convoy tidy between sweeps. **Unobserved:** a two-device convoy
cannot be run here, so this was read against the Kotlin client and the relay's frame
documentation (`server/sync/sync_server.py:1948`), not watched. **Still open:** 6c (relay URL from a
custom server), 6d (`?? 0` vs `NaN`), 6e (no pong deadline), 6f (unbounded fan-out, needs-a-human),
and the two smaller ones recorded above.

**Blocks stage 3: no.** The convoy protocol is explicitly **out of scope** at
`specs/stage-3-hazard-machines-to-shared.md:122-124`, and its risk section warns at `:174-176` that
*"once three machines are in the core, the convoy protocol and trip detection will look easy. They
are each larger than all of stage 3."* This entry is the note that constraint asks for.

---

## 7. `fetchLocation` — how many copies, and do they behave the same?

**What.** Several screens need a single position right now, before the GPS stream has produced
anything. Each asks for it in its own way.

**The true count: three functions named `fetchLocation`, five Android one-shot lookups, and the
iOS file earlier audits counted is not one of them.** Audit 13 §3.4 says four copies and lists
`iosApp/LocationProvider.swift:39-48`; `SKILL.md:206-208` says three and lists only the Kotlin
ones. The later count is the right one for the name, and both undercount the behaviour.

| # | Location | Priority requested | Fallback | Extra behaviour |
|---|---|---|---|---|
| 1 | `app/…/ui/MapScreen.kt:436-456` `fetchLocation` | `PRIORITY_HIGH_ACCURACY` | `client.lastLocation` | moves the camera; sets `error` on null or `SecurityException` |
| 2 | `app/…/car/SpinScreen.kt:298-310` `fetchLocation` | none — `lastLocation` only | — | `if (myLocation != null) return`; calls `invalidate()` |
| 3 | `app/…/car/SearchScreen.kt:164-173` `fetchLocation` | none — `lastLocation` only | — | `if (myLocation != null) return`; **no `invalidate()`** |
| 4 | `app/…/ui/Theme.kt:38-42` (unnamed, in `isNightNow`) | none — `lastLocation` only | `SunTimes.isNightFallback` | polls every 60 s for the auto day/night theme |
| 5 | `app/…/ui/RouteEditorScreen.kt:239-241` (unnamed) | `PRIORITY_BALANCED_POWER_ACCURACY` | `client.lastLocation` | frames the map; swallows `SecurityException` silently |

`iosApp/Detour/LocationProvider.swift` is **not** a fourth copy of this: it is a continuous
`CLLocationManager` wrapper (`start()`, `startBackground()`, a `@Published last`) with no
one-shot fetch at all, and its header comment at `:11-12` says explicitly that nothing in it
belongs in the core. Counting it inflated the figure.

**How they differ, in what a user notices.** Copy 2 and copy 3 return `lastLocation` only — the
system's cached fix, which on a cold start can be null or an hour old, with **no active request to
fall back on**. Copies 1 and 5 ask for a fresh fix first. So opening the car's spin screen right
after a reboot can leave "Waiting for your location…" on screen indefinitely, where the phone map
in the same state gets a fix. Copy 5 uses the balanced-power priority, so on the route editor the
first frame can be a cell-tower fix a few hundred metres out. And copy 3 not calling
`invalidate()` means the car's search screen can hold a location it never redraws with.

**Which is better, and why.** Copy 1's shape — high-accuracy current-location request, then
`lastLocation`, then a reported error. It is the only one that cannot silently produce nothing.
Copy 5's balanced priority is a defensible deliberate choice for a map-framing call that does not
need metres. Copies 2, 3 and 4 are `lastLocation`-only with no rationale written anywhere.

**What a user loses either way.** Unify on copy 1's shape and every caller pays one active
location request — battery cost on `Theme.kt`'s 60-second poll, which is the one call site where
`lastLocation`-only is arguably right (a sunrise/sunset calculation does not need metres, and it
runs forever). So a blanket unification is wrong; this needs one shared helper with an accuracy
argument, not one behaviour.

**Blast radius.** Phone (`MapScreen`, `Theme`, `RouteEditorScreen`) and Android Auto
(`SpinScreen`, `SearchScreen`). Not iOS — it has no equivalent.

**Recommendation: survive — copy 1's shape, as one `internal suspend fun` under `app/` taking a
priority parameter.** `app/` ↔ `car/` is the same Gradle module and the same package root
(`SKILL.md:57-60`), so this is a plain extraction with no `shared/` hop and no interface. Keep
`Theme.kt` on `lastLocation` by passing the parameter, not by keeping a second copy.

**Blocks stage 3: no.** Not a hazard machine. A one-commit cleanup that can land any time.

---

## 8. The "Off route" banner threshold is a fourth copy of a constant stage 2 just extracted

**What.** When you leave the drawn route the phone shows an "Off route" label, and separately
decides to fetch a new route. Those two use the same distance, written twice.

**Copies.** Stage 2 extracted the reroute rule into `app/…/map/NavPolicy.kt:22` —
`const val OFF_ROUTE_METERS = 60.0` — and both driving surfaces now call it:
`app/…/ui/MapScreen.kt:1071` and `app/…/car/NavScreen.kt:244`. `grep -rn 'NavPolicy'` confirms
two call sites and one test file; **no third copy of the policy survives.** Stage 2's
deduplication is real.

But the banner did not come along until `7d57087` folded it in. `app/…/ui/MapScreen.kt:1426-1427`
now reads:

```kotlin
offRoute = (navProgress?.offRouteMeters ?: 0.0) >
    NavPolicy.OFF_ROUTE_METERS,
```

As written, this entry said instead: *a bare `60`*, feeding `NavigationBottomBar`'s
`offRoute: Boolean` (`app/…/ui/Navigation.kt:158`, rendered at `:195` as the string `"Off route"`),
and *the car has **no off-route indicator at all*** — it speaks `"Rerouting"` once
(`app/…/car/NavScreen.kt:258`) and shows nothing persistent.

**RESOLVED (constant half) — `7d57087`, in favour of `NavPolicy.OFF_ROUTE_METERS`.** The bare `60`
this entry was written against was already gone when the entry was committed: `7d57087` is the
commit that added this register and it carried the one-line change in its own diff. The prose here
and the §D assertion below both claimed the literal still existed and were false the day they were
written — corrected in place rather than deleted, so the shape of the mistake stays on record.

**How they differ, in what a user notices.** Nothing today: both numbers are 60. The divergence
is latent — the next person to tune the reroute distance changes `NavPolicy.OFF_ROUTE_METERS`,
the banner keeps saying 60, and the phone shows "Off route" while the policy has decided you are
on it, or the reverse. That is a confusing UI that no test catches, because the literal is in a
composable.

**Which is better, and why.** Not a product question. `NavPolicy.OFF_ROUTE_METERS` is the source
of truth by construction and the literal is a leftover.

**What a user loses either way.** Nothing. Also worth deciding at the same time whether the car
should gain the banner — the head unit currently tells you about a reroute once, in audio, and a
driver who missed it has no way to know they are off route. That half **is** a product question.

**Blast radius.** Phone (one literal → one constant reference). Adding a car banner is a separate,
optional change.

**Recommendation: survive — `NavPolicy.OFF_ROUTE_METERS`, one-line change.** The car banner is
**needs-a-human** and should not ride along.

**RESOLVED (car indicator) — `6551f37`, decision 4 answered yes.** The head unit's
destination card now turns red and reads "Off route" while `p.offRouteMeters` exceeds
`NavPolicy.OFF_ROUTE_METERS`. Two commits as decision 4 required, and the mechanical half was not
one of them because it had already landed.

**Blocks stage 3: no.** Stage 2 leftover. Fix it in stage 2's own follow-up, not in stage 3.

---

## 9. The three-candidate spin roll — the phone has its own copy with a timeout the shared one lacks

**What.** A spin rolls three destinations in parallel and shows all three. `shared/` has that
function. iOS calls it. The phone does not — it has its own loop.

**Copies.**

`shared/src/commonMain/kotlin/com/jellemax/detour/data/SpinPicker.kt:27-55` `pickThreeCandidates`:

```kotlin
val rolls = (1..3).map { async { runCatching { pickCandidate(…) } } }.awaitAll()
rolls.forEach { roll ->
    val e = roll.exceptionOrNull()
    if (e is CancellationException) throw e
}
val found = rolls.mapNotNull { it.getOrNull() }
if (found.isEmpty()) {
    throw rolls.firstNotNullOfOrNull { it.exceptionOrNull() }
        ?: IOException("Failed to find a destination")
}
```

Phone — `app/…/ui/MapScreen.kt:1187-1204`, an inline reimplementation:

```kotlin
val picks = withTimeout(30_000) {
    coroutineScope {
        (1..3).map {
            async(Dispatchers.IO) {
                runCatching {
                    pickCandidate(
                        serverConfig, loc, radiusKm.toDouble() * 1000.0,
                        minMeters, mode, poiKind, bearing, explored)
                }
            }
        }.awaitAll()
    }
}
val results = picks.mapNotNull { it.getOrNull() }
if (results.isEmpty()) { … }
```

iOS calls the shared one — `iosApp/Detour/SpinModel.swift:129`
(`candidates = try await SpinPickerKt.pickThreeCandidates(`), and its comment at `:109` says the
rules *"all live in `pickThreeCandidates` in `:shared`"*. Android Auto calls the
single-candidate `pickCandidate` once (`app/…/car/SpinScreen.kt:332`) and so has no
three-candidate feature at all.

**How they differ, in what a user notices.** Two ways.

- **The phone has a 30-second overall timeout; the shared version has none.** A spin against a
  slow or wedged routing server fails on Android after 30 s with a specific message
  (`MapScreen.kt:1211-1217` distinguishes "server route failed / fallback timed out"). On iOS the
  same spin spins forever with no way out but killing the app.
- **The shared version rethrows `CancellationException`; the phone's swallows it.**
  `runCatching` catches everything, so on the phone a roll cancelled because the spin was called
  off counts as a failed roll. If all three are cancelled, `results.isEmpty()` throws
  `IOException("Failed to find a destination")` and the user gets an error snackbar for an action
  they themselves cancelled. `SpinPicker.kt:25-26` documents exactly this: *"A cancellation is
  never a failed roll — it means the spin was called off, so it propagates instead of being
  counted."*

**Which is better, and why.** Each is better on the half the other gets wrong. The shared copy's
cancellation handling is correct and documented; the phone's timeout is a real protection that
`commonMain` cannot express as written (it can — `withTimeout` is in common coroutines — it simply
was not).

**What a user loses either way.** Delete the phone's copy as-is and Android loses its 30-second
bail-out and its two specific error messages. Keep the phone's copy and iOS keeps a spin that can
hang forever. Neither is acceptable on its own — the answer is to add the timeout to the shared
function, which is a behaviour change for iOS and therefore a decision, not a refactor.

**Blast radius.** Phone (loses ~18 lines, gains the cancellation fix) and iOS (gains a timeout it
does not have today). `Dispatchers.IO` at `MapScreen.kt:1190` disappears — commonMain has none by
design, and the shared function is already `suspend`.

**Recommendation: survive — `shared/`'s `pickThreeCandidates`, with the phone's 30-second timeout
added to it.** The timeout value crossing into iOS behaviour is the part a human should sign off;
everything else is mechanical.

**Blocks stage 3: no.** The spin machine is not one of the three hazard machines. It is however
the cheapest `shared/` win left in the repo — one deletion, one parameter — and audit 13 §3.4
already lists it as a three-copy item.

---

## 10. The car's search screen ignores the routing preferences the rest of the app honours

**What.** "Avoid highways" and "avoid small roads" are user settings. Every route request is
supposed to carry them. One does not.

**Copies.** All five request sites, from `grep -rn 'RoutingServer.route\|routeVia\|roundTrip'`:

| Site | Flags passed |
|---|---|
| `app/…/ui/MapScreen.kt:715-716` (start navigation) | `Settings.avoidHighways.value, Settings.avoidSmallRoads.value` |
| `app/…/ui/MapScreen.kt:1093-1094` (reroute) | both |
| `app/…/ui/RouteEditorScreen.kt:149-150` | both |
| `app/…/car/NavScreen.kt:262-263` (car reroute) | both |
| `app/…/car/SearchScreen.kt:138` | **neither** |

`app/…/car/SearchScreen.kt:138`:

```kotlin
RoutingServer.route(config, from, result.location, TravelMode.CAR.ghProfile)
```

`RoutingServer.route`'s signature defaults both to `false`
(`shared/…/RoutingServer.kt:229-231`), so this silently requests a default route.

**How they differ, in what a user notices.** Search for a destination on the head unit with
"avoid highways" on. The route you get takes the motorway. Then drive off it far enough to trigger
a reroute — `NavScreen.kt:262` *does* pass the flags, so the replacement route suddenly avoids
motorways. **The same trip changes its routing policy mid-drive with no user action.** That is
worse than either behaviour consistently.

The same line hardcodes `TravelMode.CAR.ghProfile`, so a motorcyclist searching on the head unit
gets car routing. `app/…/car/SpinScreen.kt:333` does the same (audit 13 §4 lists that one); the
`SearchScreen` instance is not recorded anywhere.

**Which is better, and why.** Not a product question. Four sites pass the flags, one does not, and
no comment claims the omission is intentional.

**What a user loses either way.** Nothing. A user who wanted default routing on the car screen
specifically has no way to express that today anyway.

**Blast radius.** Android Auto search only.

**Recommendation: bug — fix on its own** (§B3). Two arguments added to one call.

**RESOLVED — `c7f698a`.** `SearchScreen.navigateTo` passes `Settings.avoidHighways.value` and
`Settings.avoidSmallRoads.value`, so all five request sites now agree and a trip started from car
search no longer changes its routing policy on its first reroute. **Still open:** the same line's
hardcoded `TravelMode.CAR.ghProfile` (this entry's second half, also at `SpinScreen.kt:333`),
deliberately left alone — a travel-mode fix is a different change from a settings fix.

**Blocks stage 3: no.**

---

## 11. Trajectcontrole (average-speed sections) — one surface implements it, one fetches the data and throws it away

**RESOLVED — all three surfaces, as decision 2 said.** The extraction is stage 3's
`SectionAverageTracker` (`shared/…/drive/`, `commonTest`-covered). The readouts are convergence 2,
`b655528` (car) and `79f20b7` + `e68c815` (iOS), one surface per commit and none of them sharing a
commit with the extraction, per §C.1. The car keeps `result.sections` from the Overpass answer it
already makes, steps the tracker in `onFix`, and draws a third disc inboard of the posted-limit
sign at 0.9× its diameter — the layout answer to the "fifth readout at arm's length" objection
below. It is silent: an average is a state, not an event. Free drive (`SpinScreen`) is unchanged
and has no section data to be inside of. iOS gains the feature outright — a `SectionAverageHolder`
in `iosMain` (Swift cannot construct the tracker's `State`; every one of its six constructor
parameters is defaulted and Kotlin/Native exports no defaults), the tenth `FlowWatcher` subclass,
and a chip on the map screen matching the phone's. Entry 18 was not decided by accident: the
tracker still emits a reading at a standstill and each surface still chooses whether to draw it —
the car draws it, as it draws its speed.

**Unverified:** no replay and no device ran for either surface, and nothing at all compiles the
Swift. See `DECISION.md` § *What is not verified*.

**What.** Belgian and Dutch motorways measure your *average* speed between two gantries. The phone
tracks that average and shows it. The car downloads the same section data and discards it.

**Copies.** Phone — `app/…/ui/MapScreen.kt:878-939`, the section tracker, with its entry gate in
`app/…/ui/MapCameraTuning.kt:86-99` (`sectionExitGate`) and `SECTION_GATE_METERS = 60.0` /
`SECTION_WEDGE_DEG = 75.0` at `:69, :74`. Displayed by `SectionAverageChip`
(`app/…/ui/MapHud.kt:234-250`).

Car — `app/…/car/NavScreen.kt:396-401`:

```kotlin
val result = runCatching {
    withContext(Dispatchers.IO) { SpeedCameras.near(pos) }
}.onFailure { Log.w(TAG, "camera fetch failed", it) }.getOrNull()
if (result != null) {
    speedCameras = result.cameras
    camerasCenter = pos
```

`result.sections` is never read. `grep -rn 'speedSections\|\.sections' app/…/car/` returns
nothing else.

iOS and Wear have neither the tracker nor the data — `grep -rn 'SpeedCameras' iosApp/` is empty,
even though `shared/…/SpeedCameras.kt` is commonMain and callable from Swift.

**How they differ, in what a user notices.** Enter a trajectcontrole. The phone shows a running
average that turns red once it exceeds the section limit — the number that actually determines
whether you get a fine. The head unit shows the instantaneous speed only, which is the number that
does not. Same drive, same gantries.

**Which is better, and why.** Not a drift between two behaviours — one surface has the feature and
three do not, so there is no "which copy" to choose. It is here because stage 3 makes
`SectionAverageTracker` its **first** machine, and after that move the car needs three lines to
gain the feature (keep `result.sections`, drive the tracker, pass the average to `updateHud`).
Whether it *should* is a product call: the car HUD is already at four readouts (speed, posted
limit, ETA card, action strip) and a fifth may be too much for a head unit read at arm's length.

**What a user loses either way.** Give the car the average and the HUD gets busier. Do not, and a
driver on the head unit has no idea what the section thinks their average is, which is the one
number the section is measuring.

**Blast radius.** Android Auto if adopted; iOS if adopted (it would be a new feature there, which
is the whole argument for stage 3). Phone unchanged either way.

**Recommendation: needs-a-human, on adoption only.** The extraction itself is settled and is
stage 3's machine 1. What is not settled is whether the car and iOS then *use* it. Decide that
before the extraction, because it changes what the machine's output type has to serve.

**Blocks stage 3: no — it is stage 3.** Consumed by work item 1, `SectionAverageTracker`.
Note the recorded blocker: `DECISION.md:29-35` says stage 3 cannot start because no replay
recording exists that enters one gantry and exits the other, and
`specs/stage-3-hazard-machines-to-shared.md:45` asserts `tools/mocklocation/baseline` exists,
which it does not.

---

## 12. Voice announcements exist on two surfaces and the primary one is silent

**What.** During navigation the head unit and the iPhone speak the next turn. The phone app says
nothing.

**Copies.** `grep -rn 'TextToSpeech\|speak(' app/src/main/java/` outside `car/` returns **zero
hits.** Voice lives only in `app/…/car/NavVoice.kt` (150 lines, with audio-focus handling at
`:35-40, :138-145`) and `iosApp/Detour/NavVoice.swift`.

`Settings.voiceGuidance` (`shared/…/Settings.kt:132`) is a shared setting with three consumers and
only two voices:

- Car — `app/…/car/NavScreen.kt:291`: `if (Settings.voiceGuidance.value) voice.speak(text)`
- iOS — `iosApp/Detour/NavScreen.swift:198`: `if SettingsValues.shared.voiceGuidance { voice.speak(text) }`
- Phone — nothing. Its nav loop (`app/…/ui/MapScreen.kt:1054-1066`) computes progress, pushes to
  `NavRelay` and `BleNavServer`, and never announces.

**The settings screens describe the same switch differently, and one of them is honest about it.**
`app/…/ui/SettingsScreen.kt:306-308` says *"Turn instructions read aloud **on the car screen**.
Also mutable mid-drive from the speaker button there."* — an accurate description of a setting
that does nothing on the surface it is displayed on. `iosApp/Detour/SettingsScreen.swift:98-101`
labels the identical switch *"Spoken directions"* under Routing, with no such qualifier, because
on iOS it means what it says.

**The announce ladder itself matches, to one boundary.** `VOICE_FAR_M/NEAR_M/NOW_M = 800/300/80`
at `app/…/car/NavScreen.kt:62-64` ↔ `voiceFarM/voiceNearM/voiceNowM = 800.0/300.0/80.0` at
`iosApp/Detour/NavScreen.swift:142-144`. `spokenDistance` (`NavScreen.kt:532-537` ↔
`NavScreen.swift:203-210`) is equivalent. The phase-selection guards are not:

Car — `app/…/car/NavScreen.kt:302-307`:

```kotlin
distance <= VOICE_NOW_M -> 3
distance <= VOICE_NEAR_M -> 2
distance <= VOICE_FAR_M -> 1
else -> 0
```

iOS — `iosApp/Detour/NavScreen.swift:174-179`:

```swift
switch distance {
case ..<Self.voiceNowM: phase = 3
case ..<Self.voiceNearM: phase = 2
case ..<Self.voiceFarM: phase = 1
default: phase = 0
}
```

`<=` versus `..<`. At *exactly* 800.0, 300.0 or 80.0 m the two pick different phases. A GPS fix
landing on a whole-metre boundary is a measure-zero event in practice, so this is a latent
inconsistency rather than a bug — but it is the kind that makes a characterisation test written
against one surface fail on the other.

**Two utterances exist only on the car.** `app/…/car/NavScreen.kt:258` `speak("Rerouting")` — which
also re-arms the ladder at `:272-274` so the new route's first turn is announced from scratch — and
`app/…/car/NavScreen.kt:420` `speak("Speed camera ahead")` (entry 15). iOS's `NavModel`
(`NavScreen.swift:146-199`) has neither.

**Mute behaves differently.** The car's toggle stops the utterance in flight —
`app/…/car/NavScreen.kt:470-473`: `Settings.setVoiceGuidance(!voiceOn); if (voiceOn) voice.stop()`.
On iOS nothing calls `NavVoice.stop()` on a settings change; `stop()` is wired only to
`.onDisappear` (`NavScreen.swift:44`, defined `:153-156`), and the toggle is not reachable from the
full-screen nav cover at all. **Muting mid-drive on iOS finishes the sentence and needs you to
leave navigation to do it.**

**How they differ, in what a user notices.** A rider using the phone alone — the app's primary
configuration — gets no spoken guidance at all, and a settings switch that reads as if it should
provide it. A rider on iOS gets guidance but cannot silence it without exiting the nav screen. A
driver on the head unit gets both, plus two extra announcements.

**Which is better, and why.** The car is the reference implementation: it has audio focus, a
mid-drive mute, and the two extra cues. Whether the *phone* should join is the product question
(entry 15 and §C1). Two sub-items are not product questions at all: iOS's missing `voice.stop()`
on mute is a defect, and the `<=` / `..<` boundary should simply be made to agree.

**What a user loses either way.** Give the phone a voice and it needs `NavVoice`'s audio-focus
handling ported, or it ducks music on a surface where the user did not ask for audio. Leave it and
the shared `voiceGuidance` setting stays a car-only setting on a screen that does not say so
clearly enough — which is a documentation defect regardless of the decision, exactly like
`README.md:383-385` in entry 1.

**Blast radius.** Phone if adopted (new dependency, new audio-focus handling). iOS for the mute
fix and the boundary. Car unchanged.

**Recommendation: needs-a-human on whether the phone speaks (see §C1). survive — the car's
inclusive boundaries and its `voice.stop()`-on-mute, both applied to iOS.** The ladder itself
(`800/300/80`, `spokenDistance`, the phase latch) is written twice and identical, so it is a clean
`shared/` candidate under the *"a policy earns the core when it is written more than once"* rule —
with delivery per platform, per the `CircleEvents.kt` shape.

**RESOLVED — `c95b19d` (policy), `c9547ee` (car), `fb59b8e` (iOS), `e7cb39f` + `31b2ba5` +
`d682603` (phone), in favour of decision 1's full parity.** The ladder, `spokenDistance` and the
phase latch are now `NavAnnouncer` in `shared/…/data/NavAnnouncer.kt` with ten `commonTest` cases,
and all three navigating surfaces call it. The two sub-bugs went the way this entry recommended:
the boundary is inclusive, the car's (`4e45f4a`), and iOS's mute cuts the utterance in flight
(`04b0f98`). The phone's half took three commits rather than one because a move, a behaviour
change and a feature may not share one: `NavVoice` moved out of `car/` into `audio/` (`e7cb39f`),
stopped speaking over a refused focus request (`31b2ba5`, which changes the head unit too), and
then drove the phone's nav loop (`d682603`).

**Two things this entry did not know.** First, the announcement policy needs **no clock** — the
latch is path-dependent over the distance sequence, not over time, and neither copy read a
timestamp. Second, the phone's second audio client is not the user's music but Detour's own
convoy service, which holds `AUDIOFOCUS_GAIN_TRANSIENT` and forces `MODE_IN_COMMUNICATION` for
the life of a convoy (`convoy/ConvoyLiveService.kt:129,172-183`). The phone therefore does not
speak while a convoy is live, which is a stricter rule than this entry's *"needs `NavVoice`'s
audio-focus handling ported"* implied.

**Still open, and recorded rather than fixed:** the phone announces turns only while the app is
foregrounded, because `liveFix` is `collectAsStateWithLifecycle` (`MapScreen.kt:201`). Moving the
announcer into `TripTrackingService` is a state-ownership change and belongs to stage 4.

**Still UNVERIFIED, and it is most of what this entry was about.** Nothing in this repository can
hear. The commits above compile, pass the unit suites and pass CI; **no one has confirmed that the
phone is audible, that music ducks rather than pausing, that focus comes back between prompts, or
that a live convoy is actually silent.** Six device checks are listed in the plan's *Needs a
human*; until they are done, the phone's voice is *shipped*, not *verified*.

**Blocks stage 3: no.** The voice policy is out of scope by name at
`specs/stage-3-hazard-machines-to-shared.md:122-124`.

---

## 13. Over-limit thresholds: four copies of `+5`, two of `+3`, all currently agreeing

**What.** Two separate "you are speeding" rules: the HUD turns the speed readout red, and the
camera warner chimes. Each threshold is written once per surface.

**Copies — HUD red at `limit + 5`:**

- Phone: `app/…/ui/MapHud.kt:184` — `val speeding = limitKmh != null && speedKmh > limitKmh + 5`
- Android Auto: `app/…/car/CarMapRenderer.kt:635` — `val speeding = limit != null && speed > limit + 5`
- Wear: `wear/…/MainActivity.kt:140` — `val speeding = it.speedLimitKmh?.let { limit -> it.speedKmh > limit + 5 } ?: false`

**Copies — chime at `limit + 3.0`:**

- Phone: `app/…/ui/MapScreen.kt:870` — `fix.speedMps * 3.6 > limit + 3.0`
- Android Auto: `app/…/car/NavScreen.kt:414` — `currentSpeedKmh > limit + 3.0`

Also duplicated alongside them: the camera-ahead wedge, `45.0` at `MapScreen.kt:863` and
`NavScreen.kt:407`.

**How they differ.** **They do not.** All three `+5`s and both `+3.0`s agree today, and the two
thresholds being different from each other is deliberate and sensible — the visual nudge is
tolerant, the audible interrupt is not.

**Why it is in the register anyway.** This is the state that *becomes* a divergence, and
`SKILL.md:2.1` names it: *"One copy plus a comment naming the other copy is not a second
implementation — but it is the state that becomes one, so count copies, not intentions."* Five
un-deduplicated literals across three surfaces, zero tests, and the one that would drift silently
is Wear, which cannot consume `shared/` and whose 185 lines nobody reads.

**Which is better, and why.** No choice to make. Both values are agreed.

**Blast radius.** None — a pure deduplication, provably behaviour-preserving.

**Recommendation: survive — both values, hoisted.** `+3.0` belongs to stage 3's `CameraWarner`
(along with the `45.0` wedge). `+5` cannot go to `shared/` usefully while Wear is excluded from
it, so put it in `app/` and leave the watch's copy with a comment naming the source.

**Blocks stage 3: no, but it is consumed by it.** `CameraWarner` should own `+3.0` and `45.0`.

---

## 14. The Wear relay sends an instruction text the watch throws away

**What.** The phone sends the watch the next maneuver. It includes the written instruction. The
watch never displays it.

**Copies.** Writer — `app/…/wear/NavRelay.kt:24-29`:

```kotlin
put("sign", instruction?.sign ?: 0)
put("text", instruction?.text ?: "")
put("distanceToTurnMeters", progress.distanceToTurnMeters)
put("speedKmh", currentSpeedKmh)
put("speedLimitKmh", progress.speedLimitKmh ?: JSONObject.NULL)
```

Reader — `wear/…/MainActivity.kt:45-50` and `:79-84`:

```kotlin
private data class NavState(
    val sign: Int,
    val distanceToTurnMeters: Double,
    val speedKmh: Double,
    val speedLimitKmh: Double?,
)
…
navState = if (json.has("stop")) null else NavState(
    sign = json.optInt("sign"),
    distanceToTurnMeters = json.optDouble("distanceToTurnMeters"),
    speedKmh = json.optDouble("speedKmh"),
    speedLimitKmh = if (json.isNull("speedLimitKmh")) null else json.optDouble("speedLimitKmh"),
)
```

No `text` field, and no reference to one anywhere in `wear/`.

**How they differ, in what a user notices.** Nothing directly — the watch shows an arrow, a
distance and a speed, and `MainActivity.kt:138-139` explains that omitting the limit number is
deliberate (*"so it stays a glance, not a read"*). The cost is that the watch cannot disambiguate
two maneuvers that share a glyph, which is exactly the case entry 4 is about: `-6` and anything
else falling to `Icons.Default.Straight` are indistinguishable on the watch, and the text that
would have disambiguated them is being transmitted and discarded.

**Which is better, and why.** Both defensible, and it is a product call. A watch face at a glance
is a real design constraint, and adding a line of text to a 1.4-inch screen mid-corner may be
worse than a slightly ambiguous arrow.

**What a user loses either way.** Show the text and the watch becomes something you read rather
than glance at, against its stated design. Do not, and an ambiguous glyph stays ambiguous while
the fix is already arriving over the wire.

**Blast radius.** Wear only. The relay already sends the field, so no phone change is needed
either way.

**Recommendation: needs-a-human — but a small one.** Either display it or stop sending it; the
current state (transmitted, parsed away) is the one option nobody chose.

**Blocks stage 3: no.**

---

## 15. The camera warning announces on the car and only chimes on the phone

**What.** When a speed camera warning fires, the head unit chimes, speaks, and shows a toast. The
phone chimes.

**Copies.** Car — `app/…/car/NavScreen.kt:415-424`:

```kotlin
if (tooFast && ahead.at != warnedCameraAt) {
    toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
    // The toast is on the car screen and the tone is on the phone's
    // notification stream; only the spoken one reaches a driver who is
    // looking at the road with the radio on.
    speak("Speed camera ahead")
    carContext.getCarService(AppManager::class.java)
        .showToast("Speed camera ahead", CarToast.LENGTH_SHORT)
    warnedCameraAt = ahead.at
}
```

Phone — `app/…/ui/MapScreen.kt:871-874`:

```kotlin
if (tooFast && ahead.at != warnedAt) {
    toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
    warnedAt = ahead.at
}
```

The latch logic is identical; only the delivery differs. The car's comment is a *rationale for
the divergence* — the one case in this register where drift was argued in writing.

**How they differ, in what a user notices.** Riding with the phone on a bar mount, earplugs in and
wind noise, a `TONE_PROP_BEEP2` on the notification stream is inaudible — which is the exact
situation the car's comment describes and the phone's primary use case. The head unit tells you;
the phone, on a motorcycle, effectively does not.

**Which is better, and why.** The car's rationale is correct and applies at least as strongly to
the phone. But the phone has no TTS engine wired at all (entry 12), so "make the phone speak" is a
feature, not a unification — and a spoken warning on a phone in a car pocket, over music, is a
different judgement call from one on a head unit with audio focus.

**What a user loses either way.** Give the phone the announcement and it needs `NavVoice`'s
audio-focus handling ported, or it will duck the user's music for a camera it may be wrong about.
Leave it and the app's most-used surface keeps a warning its target user cannot hear.

**Blast radius.** Phone, if adopted. It also pulls in entry 12 — a phone `NavVoice` would then be
the obvious home for turn announcements too, which is a much larger change.

**Recommendation: needs-a-human.** This is the register's clearest genuine product decision: the
mechanism is understood, both sides have a written case, and the cost is a new dependency on the
most-used surface.

**RESOLVED — `ae32722`, decision 1's full parity.** The phone now chimes *and* speaks.
No toast: the car's stands in for a visual the head unit has no room for, and the phone's map
already draws the camera marker. Nothing about *when* to warn moved — `+3.0` and the `45.0`
wedge are entry 13's and stay stage 3's `CameraWarner`, and the warning wording is still a
literal at the delivery site so whichever of the two lands first declares it.

The **UNVERIFIED** half of this entry stands: whether a spoken warning is audible over wind, and
whether ducking a rider's music for a camera the app may be wrong about is the right trade, was
the argument against decision 1 and is not settled by these commits. Nor is the cue itself
observed — no camera warning has been heard on a phone; the change is one call inside an unchanged
latch, verified by the compiler and by greps proving the latch's thresholds did not move.

**Blocks stage 3: partly.** `CameraWarner` (machine 2) must decide whether it emits *"warn"* or
*"chime / speak / toast"*. The `CircleEvents.kt` precedent that
`specs/stage-3-hazard-machines-to-shared.md:97-98` names as the shape to copy answers this:
**decision and wording in the core, delivery per platform.** So `CameraWarner` emits a warning
with its text, and each surface decides how to deliver it — which makes the phone's adoption a
later, independent one-line decision rather than a blocker.

---

## 16. Push-to-talk on iOS: no microphone permission is declared or requested

**What.** Holding the convoy bar's mic button transmits your voice to the group. Android asks for
the microphone first and refuses the press without it. iOS asks for nothing.

**Copies.** Android pre-requests on connect — `app/…/ui/MapScreen.kt:474-480`:

```kotlin
if (convoyConnected && activeConvoyId != null &&
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
    PackageManager.PERMISSION_GRANTED
) { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
```

and the press itself bails — `app/…/ui/MapHud.kt:135-140`:

```kotlin
if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
    != PackageManager.PERMISSION_GRANTED) { return@detectTapGestures }
```

iOS has neither. **As found** (pre-`858dc1e`; see the RESOLVED marker below, and note the line
numbers in this entry are the ones it was written against):
`grep -n '<key>' iosApp/Detour/Info.plist` lists
`NSLocationWhenInUseUsageDescription` (`:30`), `NSLocationAlwaysAndWhenInUseUsageDescription`
(`:35`) and `NSMotionUsageDescription` (`:37`) — **no `NSMicrophoneUsageDescription`**. And
`grep -rn 'Microphone\|recordPermission\|requestRecordPermission\|AVAudioApplication' iosApp/`
returned **nothing**, while `iosApp/Detour/PttAudio.swift:41` — now `:83` — does:

```swift
try session.setCategory(.playAndRecord, mode: .voiceChat, ...)
```

**How they differ, in what a user notices.** Android prompts, then works. On iOS, activating a
`.playAndRecord` session with no usage-description key is the documented condition for the system
to terminate the app. **The specific outcome — crash versus silent failure — is UNVERIFIED**; no
Swift toolchain or device is available here, and it must be confirmed on a device before this is
filed as a crash. What *is* verified is that the key is absent and no request is ever made, which
means iOS push-to-talk cannot legitimately capture audio.

**Two smaller divergences in the same feature, both verified.**

- **The "talking" frame is sent before capture is proven.** Android sends it last —
  `app/…/audio/PushToTalk.kt:58-65`: `if (record.state != AudioRecord.STATE_INITIALIZED) {
  record.release(); return }` … then `record.startRecording()` … then
  `ConvoyLiveClient.sendPttStart()`. iOS sends it first — `iosApp/Detour/ConvoyBar.swift:96-98`
  sets `transmitting = true`, calls `sendPttStart()`, *then* `PttAudio.shared.startCapture`, which
  has three silent `return` paths (`PttAudio.swift:86, :92, :119`). **A failed start leaves every
  peer with a lit "talking" badge and no audio.** Still open after `858dc1e`, which added a
  permission gate above this block and did not reorder it.
- **The button is live while the socket is down on iOS.** Android gates on
  `visible = convoyConnected && activeConvoyId != null` (`app/…/ui/MapScreen.kt:1321`); iOS on
  `if live.activeConvoyId != nil` (`iosApp/Detour/ConvoyBar.swift:22`), with `live.connected` only
  tinting a dot (`:25`). You can hold the mic and talk into a closed socket. Still open.
- **Chunk length.** `CHUNK_SAMPLES = SAMPLE_RATE / 25` = 640 samples / 1280 bytes on both
  (`PushToTalk.kt:31-32` ↔ `PttAudio.swift:16`), and Android always emits exactly that
  (`PushToTalk.kt:67-71`). iOS's emitted size is set by the hardware tap —
  `PttAudio.swift:94` `installTap(onBus: 0, bufferSize: 1024, format: hardware)`, one `convert` per
  callback into a `frameCapacity: Self.chunkSamples` buffer (`:97`) — so at a 48 kHz hardware format
  1024 input frames yield ~341 output samples, i.e. ~21 ms on a wire `PttAudio.swift:5-7` documents
  as 40 ms. **Whether `AVAudioConverter` buffers the residue across taps is UNVERIFIED**; the
  constant/emission mismatch follows from the two lines.

**Which is better, and why.** Android, on all four. None of these is a product decision.

**What a user loses either way.** Nothing. iOS PTT does not work today.

**Blast radius.** iOS only.

**Recommendation: bug — three separate fixes** (§B5). The permission is an `Info.plist` key plus a
request; the frame ordering is a statement move; the visibility gate is one `&&`.

**RESOLVED (permission only) — `858dc1e`.** `NSMicrophoneUsageDescription` is declared and
the press path asks for the record permission before capture. **Still open:** the `sendPttStart()`
ordering and the socket-down visibility gate, both deliberately left out of that commit. The
severity of the original state — termination versus silent failure — is still **UNVERIFIED** and
was not confirmed by that commit either: nothing was run on a device. The Swift does compile —
`ios.yml` run 31600855937 on PR #1 built it for the simulator — which is a type-check, not a
behaviour check.

**Blocks stage 3: no.**

---

## 17. The car's free-drive map does not zoom out with speed; the phone's does

**What.** As you speed up, the map zooms out so you can see further ahead. The head unit does this
while navigating and not while just driving.

**Copies.** The rule is shared and stateless — `shared/…/NavEngine.kt:169-183` (`speedMps < 3.0
-> 1.0` … `else -> -1.5`). Three drivers, two of which use it:

Phone, every fix, navigating or not — `app/…/ui/MapScreen.kt:949-953`:

```kotlin
camTargetZoom = NavEngine.cameraZoom(
    defaultZoom.toDouble(), fix.speedMps,
    navProgress?.distanceToTurnMeters ?: Double.MAX_VALUE,
)
```

Car while navigating — `app/…/car/NavScreen.kt:228-230`:
`NavEngine.cameraZoom(Settings.defaultZoom.value.toDouble(), speedMps, p.distanceToTurnMeters)`.

Car while just driving — `app/…/car/SpinScreen.kt:136-137`:

```kotlin
renderer.follow(pos, fix.bearingDeg, fix.speedMps,
    Settings.defaultZoom.value.toDouble())
```

The raw preference, unadapted — even though `fix.speedMps` is passed in and available.

**How they differ, in what a user notices.** Driving a motorway at 120 km/h with no route running,
the phone map is ~1.5 zoom levels out and shows the road ahead; the head unit sits at whatever
zoom the user picked for town driving. `SpinScreen`'s own doc at `:69-74` claims it *"draws the same
following map"* `NavScreen` does. It does not.

**Which is better, and why.** The phone's. The rule is already in `shared/`, already called by two
of the three drivers, and its whole purpose is speed adaptation.

**What a user loses either way.** Nothing — the shared function honours `defaultZoom` as its base,
so a user who set a close zoom keeps it at low speed.

**Blast radius.** Android Auto's free-drive map only. One argument changed at
`car/SpinScreen.kt:136-137`.

**Recommendation: survive — the phone's (i.e. call the shared rule).** Also a documentation
defect: `SpinScreen.kt:69-74` claims parity it does not have.

**Blocks stage 3: no.**

---

## 18. The speed HUD fades away at a standstill on the phone and stays on the head unit

**What.** Stopped at a light, the phone's speed dial and limit sign fade out. The head unit keeps
showing "0 km/h" and the last posted limit.

**Copies.** Phone — `app/…/ui/MapScreen.kt:1357`, with the whole `SpeedHud` inside the `let`:

```kotlin
liveFix?.takeIf { it.speedMps >= 1.4 || displaySpeedKmh >= 2.0 }?.let {
```

with a comment at `:1354-1356` explaining the second clause: *"Stays up while the eased number
winds back down, so stopping at a light fades the dial out instead of snatching it away
mid-count."*

Car — `app/…/car/CarMapRenderer.kt:630-631` and `:648-649` draw on presence alone
(`val speed = speedKmh; if (speed != null) {` … `val limit = limitKmh; if (limit != null) {`), fed
unconditionally from `app/…/car/SpinScreen.kt:150`.

**How they differ, in what a user notices.** Parked with the app open, the phone gives the map back
its corner; the head unit keeps two discs on screen showing zero and a limit for a road you are not
moving on.

**Which is better, and why.** Both defensible, and it is a product call. A phone is a screen you
also look at when stopped, so decluttering is right there. A head unit is glanceable
instrumentation where things disappearing is itself distracting — and the car HUD is anchored
bottom-right by design (`CarMapRenderer.kt:502-504`) precisely so it is always in the same place.

**What a user loses either way.** Fade on the car and a driver's eye returns to an empty corner
after every stop. Keep it on the phone and a parked map carries a dial reading zero.

**Blast radius.** Whichever surface adopts the other's rule.

**Recommendation: needs-a-human — but small, and defaulting to "leave both" is defensible.**
Recorded because stage 3's `SpeedLimitTracker` decides when a limit value exists, and it must not
also decide whether a sign is *shown* — that stays per-surface. If the tracker starts emitting
"no limit" to force the sign away, this decision has been made accidentally.

**Blocks stage 3: no, but constrains it.** `SpeedLimitTracker` emits a value; visibility is the
caller's.

---

## 19. Distance-to-turn is shown to the metre on two surfaces and quantised on a third

**What.** The turn banner counts down the distance to the next maneuver. Three surfaces round it
differently.

**Copies.**

- Phone — `app/…/ui/Navigation.kt:107`: `else -> formatDistanceKm(progress.distanceToTurnMeters)`,
  which is `"%.0f m"` under 1 km (`app/…/ui/Format.kt:28-29`) → `"437 m"`.
- Wear — `wear/…/MainActivity.kt:69-70`, a verbatim second copy of the same helper, called at
  `:134` → also `"437 m"`.
- iOS — `iosApp/Detour/NavScreen.swift:213-218`:

```swift
if safe >= 1000 { return String(format: "%.1f km", safe / 1000) }
if safe >= 100 { return "\(Int((safe / 100).rounded()) * 100) m" }
return "\(Int((safe / 10).rounded()) * 10) m"
```

→ `"400 m"`.
- Car passes exact metres to the host (`app/…/car/NavScreen.kt:519-523`) and uses a 10 m/100 m
  quantisation only as a redraw key (`:527-529`) — the host renders the number.

**How they differ, in what a user notices.** The same approach reads `"437 m"` on Android and
`"400 m"` on iOS, and the iOS number visibly steps in 100 m jumps rather than counting down. iOS's
comment at `:212` describes its helper as *"as the banner shows it"*, and the car's at `:525-526`
uses the same wording for a value it never displays — so both comments claim to describe a display
rule and one of them does not.

**Which is better, and why.** iOS's, on design grounds: a number changing every metre on a
navigation banner is noise, and quantising is what every mapping app does. But this is a UI
polish judgement, not correctness, and the two Android surfaces have shipped the precise version
for months without complaint.

**What a user loses either way.** Quantise Android and a rider watching the last 50 m loses
resolution exactly where it matters. Keep it precise and the banner flickers.

**Blast radius.** Phone and Wear if quantised; iOS if not. Note Wear's copy of `formatDistance` is
a **second copy of `Format.kt`'s rule** (`wear/…/MainActivity.kt:69-70`) that cannot be
deduplicated via `shared/`.

**Recommendation: needs-a-human, low stakes.** Whichever way it goes, fix the two comments that
claim to describe display behaviour.

**Blocks stage 3: no.**

---

## 20. Place search: three surfaces, three sets of parameters

**What.** Typing a destination name queries the Photon geocoder. When the query fires, how long it
waits, and whether it prefers nearby results all differ per surface.

**Copies.**

| | Minimum query length | Debounce | Proximity bias | Recent saved |
|---|---|---|---|---|
| Phone | `if (q.length < 2)` — `app/…/ui/MapDialogs.kt:87` (recents only, no request) | `delay(300)` — `:96` | `near = myLocation` — `app/…/ui/MapScreen.kt:1538-1539` | on pick (`MapDialogs.kt:73`) |
| Android Auto | `if (text.isBlank())` — `app/…/car/SearchScreen.kt:97` | `SEARCH_DEBOUNCE_MS = 350L` — `:30` | `Geocoder.search(text, myLocation)` — `:113` | **after routing succeeds** (`:140`) |
| iOS | `guard query.count >= 2` — `iosApp/Detour/MapScreen.swift:473` | `.milliseconds(300)` — `:476` | **`near: nil`** — `:479-480` | on pick (`:460`) |

`Geocoder.search`'s `near` parameter is shared (`shared/…/Geocoder.kt:32`) — iOS simply does not
pass it.

**How they differ, in what a user notices.** Three things. **A single typed letter reaches Photon
from the head unit and from nowhere else** — one keystroke, one network round-trip, on the surface
with the worst input method. **iOS results are not proximity-biased**, so searching "Station" on
the iPhone can return one on the other side of the country while the same search on Android returns
the local one — on the surface with the smallest screen and the least room for wrong results. And
**the car only remembers a search once routing to it succeeds**, so a destination you picked and
then cancelled is not in your recents, unlike on the other two.

**Which is better, and why.** The phone's, on all three: a 2-character floor is the standard guard
against a per-keystroke query, 300 ms is the debounce two of three already use, and passing
`myLocation` is what the shared parameter exists for. iOS's `near: nil` and the car's blank-only
guard have no rationale written anywhere.

**What a user loses either way.** Nothing, on any of the three. This is the entry with the highest
ratio of user-visible improvement to risk in the register.

**Blast radius.** Android Auto (a length guard, a debounce value, and where the recent is saved)
and iOS (one argument).

**Recommendation: survive — the phone's parameters.** Three small independent fixes, none of which
needs a decision. The car's save-after-routing is arguably deliberate (a search that failed to
route is not a place you went) — that one half is **needs-a-human**, and it is small enough to
resolve by asking whether recents mean "searched" or "went".

**Blocks stage 3: no.**

---

## 21. Notification catch-up: same five events, opposite order, and one self-filter missing

**What.** After the app has been closed, it catches up on circle arrivals and departures it missed.
Both platforms show at most five, newest first. They post them in opposite order, so the
notification shade reads the other way round.

**Copies.** Cap and max-age match exactly — `NOTIFY_CAP = 5` and `STALE_AFTER_MS = 3 * 60 *
60_000L` at `app/…/notif/PlaceNotifications.kt:55, :51` ↔ `catchUpCap = 5` and
`catchUpMaxAgeMs = 3 * 60 * 60_000` at `iosApp/Detour/CircleNotifications.swift:24, :30`. Both
*select* the newest five. The **iteration** differs.

Android — `app/…/notif/PlaceNotifications.kt:77-84`:

```kotlin
.sortedBy { it.tsMs }
if (relevant.size <= cap) return CatchUpPlan(relevant, 0)
// Newest first: if only a handful can be shown, the most recent
// arrivals are the ones still worth knowing about right now.
return CatchUpPlan(relevant.takeLast(cap), relevant.size - cap)
```

so the plan is **oldest-first**, and `app/…/notif/CircleNotifyService.kt:189` posts in that order.

iOS — `iosApp/Detour/CircleNotifications.swift:134-142`:

```swift
.sorted { $0.tsMs > $1.tsMs }
…
for event in notifiable.prefix(Self.catchUpCap) { raise(event: event, circleId: circle.id) }
```

**newest-first**. Both systems stack by delivery time, so the batch reads newest-on-top on Android
and oldest-on-top on iOS. The summary is raised last on both (`CircleNotifyService.kt:190`,
`CircleNotifications.swift:143-145`) and its wording is shared (`shared/…/CircleEvents.kt:126-127`).

**Also: the live self-filter exists on Android only.** `app/…/notif/CircleNotifyService.kt:159`:

```kotlin
if (relay.event.username == Account.username.value) return@collect
```

`iosApp/Detour/CircleNotifications.swift:90-94`'s `handleLiveEvent` has only
`guard notifyEnabled(circleId: groupId)`, and its doc at `:87-89` argues the server excludes the
mover so *"there is no self-transition to filter here"*. Both **catch-up** paths do filter
(`PlaceNotifications.kt:78`, `CircleNotifications.swift:135`), so the asymmetry is only on the
socket path — a server-side regression in `exclude_user_id` would notify iOS users about their own
arrivals and not Android users.

**How they differ, in what a user notices.** Ordering: reading five missed arrivals top-to-bottom
gives you the newest first on Android and the oldest first on iOS. Self-filter: nothing today,
because the server is correct.

**Which is better, and why.** Ordering is a product call and the argument is not obvious — both
comments claim "newest first" and both are describing their *selection*, not their *display*, so
neither author noticed. Android's shade reading newest-on-top matches how the rest of Android
notifications behave; iOS's is the reverse for the same reason. **Possibly both are already right
for their platform.** The self-filter is not a product call: a defence that costs one line and
guards a cross-surface invariant belongs on both sides.

**What a user loses either way.** Nothing measurable on the ordering.

**Blast radius.** One platform's shade order; iOS gains one guard line.

**Recommendation: needs-a-human on the ordering (and "both are correct for their platform" is a
legitimate answer). survive — Android's self-filter, added to iOS.** If ordering is unified, the
selection rule (cap, max-age, newest-N) is written twice and identical, so it is a clean `shared/`
candidate — and `CircleEvents.kt` already holds the wording, which is the precedent stage 3 is
told to copy.

**Blocks stage 3: no.** Notification policy is not a stage-3 machine.

---

## 22. Trip timestamps use a fixed field order on Android and a locale-derived one on iOS

**What.** A saved trip shows its date and time. Android formats it the same way in every locale;
iOS lets the locale decide the field order.

**Copies.** Six of the eight `Format` functions agree exactly — `formatDuration`,
`formatDurationHistory`, `formatSpeedKmh`, `formatDistanceKm`, `formatLeanAngle`, `formatGForce`,
including every `%.0f`/`%.1f` and the `< 1000` cut (`app/…/ui/Format.kt` ↔
`iosApp/Detour/Format.swift`). The two date functions do not.

Android — `app/…/ui/Format.kt:35` and `:39`:

```kotlin
SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault())
SimpleDateFormat("HH:mm", Locale.getDefault())
```

iOS — `iosApp/Detour/Format.swift:43` and `:49`:

```swift
f.setLocalizedDateFormatFromTemplate("EEE d MMM yyyy HH:mm")
f.setLocalizedDateFormatFromTemplate("HH:mm")
```

`SimpleDateFormat` applies the pattern literally, comma included. `setLocalizedDateFormatFromTemplate`
treats the string as a *skeleton* and derives a locale-appropriate pattern from it, so field order
and separators follow the locale.

**How they differ, in what a user notices.** The same trip reads `"Tue 12 Aug 2026, 14:05"` on
Android in every locale, and locale-reordered on iOS — an `en_US` device would put the month first
and punctuate differently. **The exact iOS output string is UNVERIFIED** (no Swift toolchain here);
the mechanism difference is plain in the two lines. Note this is *not* a 12/24-hour difference:
an explicit `HH` skeleton is honoured by ICU.

**Which is better, and why.** iOS's, on correctness — a date shown to a user should follow their
locale, and Android's hardcoded comma is wrong in most of the world. `Format.swift:3` notes these
are *"Not in `:shared`: these are presentation"*, which is the right call and also why the two
drifted.

**What a user loses either way.** Nothing on iOS. Android would gain locale-correct dates, which
is a visible change to the history and trip-detail screens and therefore worth a release note
rather than a silent fix.

**Blast radius.** Phone (history, trip detail) if adopted. `Format.swift` is 100% duplicated with
`Format.kt` (audit 13 §3.2) but the duplication is *deliberate and documented* — this entry is
about the two functions where the duplication silently stopped being a duplication.

**Recommendation: survive — iOS's locale-derived formatting.** Low priority, and it is the one
entry in the register where iOS is the better copy.

**Blocks stage 3: no.**

---

## §A — Summary, ranked by how user-visible the decision is

**22 divergences.** By kind:

- **6 are plain bugs** — one side is wrong, not different. §B. Four were found by this register and
  are not filed anywhere; one is `maxke24/Detour#21`; one is already scheduled as stage 0d.
  **Four of the six are now fixed** — B1 `bac833a`, B2 `35b8993`, B3 `c7f698a`, B6 `aff8407`, one
  commit each. **Two remain:** B4 (entry 2, the Overpass stall, waiting on stage 0d and a replay
  route) and the open two thirds of B5 (entry 16's `sendPttStart()` ordering and visibility gate).
- **12 have a defensible better copy on the evidence** and need no decision: 2, 4, 6a–6e, 7, 8, 9,
  13, 17, 20, 22. Note that **entry 22 is the one place iOS is the better copy** — a register that
  always picks the phone is not measuring anything.
- **8 carry a genuine product decision.** Four are substantial and listed in §C; four more (14, 18,
  19, 21) are real but small enough that a one-line answer unblocks each.
- **1 is not really a divergence at all** (entry 3) and the register's recommendation is to leave
  it alone.

Several entries are mixed — entry 1 is a product decision *and* a bug, entry 5 is five settled
items plus one open question plus a bug — so the buckets add to more than 22 by design.

| # | Divergence | Kind | Surfaces affected | Blocks stage 3 | Verdict |
|---|---|---|---|:-:|---|
| 16 | **iOS PTT has no microphone permission at all** | **bug** | iOS | no | **permission RESOLVED `858dc1e`**; ordering + socket gate open (§B5) |
| 1 | Camera chime falls back to the ambient limit | product decision + bug | phone, car, wear, `README.md` | **yes** | survive: phone's fallback; **staleness RESOLVED `bac833a`** (the car's reset), fallback for the car is stage 3's |
| 5 | Trip auto-detection: six rules | product decision + bug | iOS | no (out of scope) | survive: Android on 5a/b/c/e/f; **5d RESOLVED — threshold `3928ce0`, gate `35b8993`**; rest open |
| 6 | Convoy relay: `left`, pruning, dead sockets | 5 bugs + 1 trade-off | iOS | no (out of scope) | survive: Android on 6a–6e; **6a + 6b RESOLVED `aff8407`**; 6c–6e open |
| 15 | Camera warning: chime vs chime+speak+toast | **product decision** | phone | partly | **RESOLVED `ae32722`** — full parity: chime + speak, no toast; audibility still unheard |
| 2 | Overpass prefetch on the fix collector | plain bug | phone | **yes (ordering)** | survive: car's structure (stage 0d) |
| 11 | Trajectcontrole adoption on car / iOS | **product decision** | car, iOS | it *is* stage 3 | **RESOLVED** — all three, §C2 |
| 12 | Voice: phone silent, iOS mute doesn't cut | **product decision** + bug | phone, iOS | no | **RESOLVED — policy `c95b19d`, car `c9547ee`, iOS `fb59b8e` + `4e45f4a` + `04b0f98`, phone `e7cb39f`/`31b2ba5`/`d682603`**; turn prompts foregrounded-only (stage 4), and no audio verified |
| 20 | Place search: length, debounce, proximity | drift | car, iOS | no | survive: the phone's parameters |
| 9 | Three-candidate roll: phone's copy vs `shared/` | product decision | phone, iOS | no | survive: `shared/` + phone's timeout |
| 4 | Maneuver sign table, four copies, `-6` | drift | phone, wear, iOS | no | survive: car's code set, split glyph layer |
| 17 | Car free-drive map ignores speed-adaptive zoom | drift | car | no | survive: the shared rule |
| 10 | Car search drops the avoid-* settings | plain bug | car | no | **RESOLVED `c7f698a`**; the same line's hardcoded car profile is still open |
| 19 | Distance-to-turn: metres vs 100 m steps | product decision | phone, wear, iOS | no | **needs-a-human**, low stakes |
| 7 | `fetchLocation`, five one-shot lookups | drift | phone, car | no | survive: high-accuracy shape, parameterised |
| 18 | Speed HUD fades at standstill on the phone only | product decision | phone or car | constrains it | **needs-a-human**, "leave both" is fine |
| 21 | Catch-up order reversed; iOS lacks a self-filter | product decision + gap | one platform, iOS | no | **needs-a-human** on order; survive the filter |
| 14 | Wear discards the instruction text | product decision (small) | wear | no | **needs-a-human** (§C4-adjacent) |
| 8 | `60` literal vs `NavPolicy.OFF_ROUTE_METERS` | latent | phone | no | **RESOLVED — constant `7d57087`, car indicator `6551f37`** |
| 22 | Trip dates: fixed pattern vs locale-derived | drift | phone | no | survive: **iOS's** |
| 3 | Camera easing `dt` clamp, 0.1 vs 0.25 | not really divergent | either | no | leave both; unify the other seven constants |
| 13 | `+5` / `+3.0` / `45.0` literals | not yet divergent | phone, car, wear | consumed by it | survive: both values, hoisted |

### What stage 3 actually consumes

`specs/stage-3-hazard-machines-to-shared.md:69-73` names three machines. Only five entries touch
them; the rest of the register is adjacent work that must not be folded in.

| Stage-3 work item | Entries it consumes | Must be resolved before it? |
|---|---|---|
| 1. `SectionAverageTracker` | 11 (adoption on car / iOS) | **Yes** — the adoption decision changes the output type |
| 2. `CameraWarner` | **1** (one limit or two), 13 (`+3.0`, `45.0`), 15 (emit *warn* or *chime/speak/toast*) | **Yes for 1.** 15 is answered by the `CircleEvents.kt` shape: decision in the core, delivery per platform |
| 3. `SpeedLimitTracker` | **2** (get the fetch off the collector first), 18 (must not decide visibility) | **Yes for 2** — ordering, per stage 0d's hysteresis trap |

Everything else — entries 3, 4, 5, 6, 7, 8, 9, 10, 12, 14, 16, 17, 19, 20, 21, 22 — is **outside
stage 3**, and four of them are out of scope by name at `:120-128`: the convoy protocol (6), trip
auto-detection (5), the nav vocabulary (4, 19) and the voice policy (12, 15). Stage 3's own risk
section warns at `:174-176` that these *"will look easy"* once three machines are in the core and
that *"they are each larger than all of stage 3."* This register is the note that constraint asks
for — not a backlog for it.

Two entries are stage-2 leftovers rather than stage-3 inputs: **8** (the `60` literal `NavPolicy`
left behind) and **9** (the inline three-candidate roll). Both belong in a stage-2 follow-up.

## §B — Divergences that are simply bugs

One side is wrong, not different. Each gets its own commit, and none of them is a decision.
`DECISION.md:394-400` — *"Never in one commit: … an extraction **and** the bug it reveals"*.

**Status: four of six fixed** — B1 `bac833a`, B3 `c7f698a`, B2 `35b8993`, B6 `aff8407`, in that
order, one commit each, each verified against the tree before it was fixed. B4 and B5's remaining
two thirds are open. Every one of the four line-number citations below was re-derived and held; the
line numbers themselves had drifted (stage 2 and the voice work moved `MapScreen.kt`), so each
resolution marker names the code rather than the line.

**B1 — `ambientSpeedLimitKmh` is never reset, so it is stale twice over.** **RESOLVED — `bac833a`.**
`app/…/ui/MapScreen.kt:733-734` gates the producer off while `navigating`, and nothing clears the
value (`grep -n 'ambientSpeedLimitKmh'` → `241, 757, 762, 848, 1361`; the only writers are a
successful snap and three consecutive misses). Two symptoms:

- **During navigation**, the camera chime at `:869` judges you against the limit from wherever
  navigation began.
- **After exiting navigation**, the HUD at `:1360-1361` switches back to `ambientSpeedLimitKmh` and
  shows that same pre-route value until the next successful snap — up to three fixes, or forever on
  an untagged road.

The car fixes exactly this and says so — `app/…/car/SpinScreen.kt:117-121`:

```kotlin
// Coming back from a drive: the last ambient sign is from
// wherever you set off, so show nothing until the next fix
// snaps rather than a stale limit from another town.
ambientLimitKmh = null
limitMisses = 0
```

So the car has the better copy on the *reset* while the phone has the better copy on the
*fallback* — which is why entry 1's recommendation is "the phone's fallback **plus** the car's
reset" rather than either copy wholesale. `SpinScreen.kt:48-51` claims "same policy as the phone
map's"; every constant matches (margin 500 m, throttle 10 s, min 2.0 m/s, 3 misses) and the reset
does not. Not filed upstream. Found by this register. A reviewer diffing the two copies without
reading this could reasonably conclude the car is right outright.

`bac833a` clears both the sign and the miss counter at the top of the producer's
`LaunchedEffect(navigating)`, so every transition in either direction resets them — the car's rule,
in the phone's idiom. Reproduced before fixing: the only writers really were the snap and the
three-miss clear, both inside the gated collector, and both consumers really did read the frozen
value. **Not observable here** (Overpass rate-limited, no replay of a live sign), and it changes
behaviour: during navigation a route segment with no `maxspeed` now chimes on nothing rather than on
a stale sign. **No test** — the rule lives in a Compose effect, and reaching it from a JVM test
means extracting the ambient tracker, which is stage 3's machine 3 and may not share this commit.

**B2 — iOS auto-ends manually started trips.** **RESOLVED — `35b8993`.**
`iosApp/Detour/TripRecorder.swift:291-296` runs the
5-minute stationary end-of-trip check unconditionally; Android gates it on `autoStarted`
(`app/…/tracking/TripTrackingService.kt:1082`). A user who starts a recording by hand and then
stops for five minutes — a coffee, a photo, a long queue — loses the rest of it. Not filed
upstream. Found by this register.

`35b8993` gates the end — not the "moving" stamp, which Android also keeps updating — on the
`startedAutomatically` flag iOS already tracked for `endTrip`'s false-positive rule and simply never
consulted here. **Not observable here:** nothing in this repo runs the iOS app. On a device: start a
recording by hand, stand still for six minutes, confirm it is still recording. **No test** —
`iosApp/` has no test target, and CI's JVM/Native tests cover `:shared` only.

**B3 — The car's search screen drops the routing preferences.** **RESOLVED — `c7f698a`.** Entry 10.
`app/…/car/SearchScreen.kt:138`. Not filed upstream. Found by this register. Two arguments.

`c7f698a` adds exactly those two arguments, so all five request sites agree. Verified before and
after against all five sites, and it compiles (`:app:compileDebugKotlin`). The one behaviour change
that *is* checkable at a desk — nothing else on that call moved — is that the head unit's search now
honours settings it was ignoring; the travel-mode half of entry 10 is untouched. **No test:** one
call site's arguments inside an Android Auto `Screen`, with nothing Android-free to assert.

**B4 — The phone stalls its own fix stream on Overpass.** Entry 2. Filed upstream as
**maxke24/Detour#21** ("choppy map rendering") and already scheduled as stage 0 work item 0d,
deferred pending replay route (ii).

**B5 — iOS push-to-talk cannot capture audio.** Entry 16. No `NSMicrophoneUsageDescription` in
`iosApp/Detour/Info.plist` and no permission request anywhere in `iosApp/`, against
`PttAudio.swift:83` activating a `.playAndRecord` session. Three fixes, three commits: the
permission, the `sendPttStart()` ordering (`ConvoyBar.swift:96-98`), and the visibility gate
(`ConvoyBar.swift:22`). **One of the three has landed:** the permission, in `858dc1e` — the key is
declared and `PttAudio.capturePermission()` gates the press. **The ordering and the visibility gate
remain open**, deliberately kept out of that commit so neither hides in its diff. Not filed
upstream. Found by this register. **The severity — process termination versus silent failure — is
UNVERIFIED and must be confirmed on a device.** The missing key and the missing request were
verified.

**B6 — iOS drops convoy peers that left and never prunes ones that go quiet.** **RESOLVED —
`aff8407`.** Entry 6a and 6b. A
`case "left"` branch (`ConvoyLiveClient.swift`, absent) and a periodic sweep to match
`PEER_PRUNE_INTERVAL_MS` (`app/…/net/ConvoyLiveClient.kt:401`). Not filed upstream. Found by this
register. Fix in Swift now — do not wait for a `shared/` extraction of the protocol, which is a
programme nobody has scheduled. Related and in the same area: iOS's mute does not stop the
utterance in flight (entry 12, one `voice.stop()` call).

`aff8407` adds both: the `left` branch, and a `prunePeersPeriodically()` task owned by the
connection loop rather than by one connection attempt, so peers do not sit frozen through a backoff
either. Reproduced before fixing — the Swift switch really had nine cases and no `left`, and
`pruneStalePeers()` really had exactly one caller, in the inbound `location` branch. **Not
observable here:** no iOS app and no second device. **No test:** `iosApp/` has no test target; the
protocol is still two hand-written clients, and the register is explicit that these two fixes should
not wait for the extraction that would make them testable in one place.

**Already fixed, listed so nobody re-files it:** the iOS maneuver table
(**maxke24/Detour#20**) was corrected on this branch by `c7ef627` and `075b991`. Audit 13's
description of it is now history, not a current defect — see entry 4.

**Looks like a bug, is not — do not file it.** `app/…/map/GroupSpinRules.kt:54` guards
`candidateCount <= 0` and the two live vote-resolution copies (`app/…/ui/MapScreen.kt:567-579`,
`iosApp/Detour/MapScreen.swift:316-335`) index `candidates[…]` without that guard, which reads as a
crash on a zero-candidate offer. It is **unreachable**: both inbound parsers reject an empty list
(`app/…/net/ConvoyLiveClient.kt:611`, `iosApp/Detour/ConvoyLiveClient.swift:425`) and so does the
send path (`kt:337`, `swift:294`). Recorded because this is the shape of finding that gets filed
twice.

## §C — Decisions a human must make

> **All four were decided on 2026-08-12.** The answers are recorded inline below, each with
> what it commits us to. Two of them — 1 and 2 — are **new feature work, not deduplication**,
> and that distinction governs how they are sequenced: neither may ride inside a stage-3
> extraction commit. See §C.1 below for the resulting order of work.

Four. Everything else in §A either has a defensible best copy on evidence, or is a bug.

1. **Does the phone speak?** (entry 15, pulling in entry 12.) The car's own code argues that a
   tone on the notification stream does not reach a rider with earplugs in — and the phone is the
   surface most likely to be on a bar mount. Against: a phone `NavVoice` needs audio-focus
   handling or it ducks the user's music for a camera warning that may be wrong. **This is the
   one decision in the register with no technically-correct answer.**

   > **DECIDED: full parity — port `NavVoice` to the phone.** The phone announces turns and
   > hazards as the car and iOS already do.
   >
   > This is a **new feature**, and the largest single item to come out of this register. It
   > commits us to: audio-focus handling and ducking on the phone; a third consumer of the
   > announcement thresholds (entry 13's `+5`/`+3` family), which is the argument for moving
   > the announcement *policy* into `shared/` rather than writing a third copy; and resolving
   > entry 16 first, because iOS PTT already grabs `.playAndRecord` without declaring a
   > microphone permission and a second audio client will make that failure louder.
   >
   > It does **not** belong in stage 3. Stage 3 moves the hazard *machines*; the voice policy
   > is a separate extraction with its own spec, and the phone's `NavVoice` implementation is
   > feature work after that.

2. **Do the car and iOS get the trajectcontrole average?** (entry 11.) Stage 3 extracts the
   tracker either way. Whether the head unit shows a fifth readout, and whether iOS gains a
   feature it has never had, are product calls that change what the machine's output has to
   serve — so decide before the extraction, not after.

   > **DECIDED: all three surfaces.**
   >
   > This settles stage 3's destination beyond argument: `SectionAverageTracker` goes to
   > `shared/` commonMain, because iOS cannot consume anything in `app/`. It also means the
   > tracker's output is a public contract from day one rather than a phone detail — so its
   > `StateFlow` element type should be chosen with the iOS `FlowWatcher` cost in mind (one
   > subclass per new element type), and it should expose the average and the posted limit as
   > one value rather than two flows.
   >
   > The car is cheap: it already fetches the section data and discards it. iOS is the real
   > work — SwiftUI readout plus the watcher — and is feature work *after* stage 3 lands the
   > tracker, not part of it.

3. **`2.0 m/s` or `1.0 m/s` for "stopped"?** (entry 5d.) `1.0` ends a trip promptly when you
   park; `2.0` refuses to end one while you are still pushing the bike into the garage. Both
   phones have shipped for months on their own value and neither has a written rationale.
   The *gate* half of 5d is a bug (§B2) and is not part of this question.

   > **DECIDED: `2.0 m/s`, Android's value.** iOS changes to match.
   >
   > Rationale, which is what was missing: the failure modes are asymmetric. Ending a trip too
   > late adds harmless idle time to a recording; ending it too early truncates a ride and
   > loses data that cannot be recovered. When in doubt, keep recording.
   >
   > This is a **behaviour change on iOS** and gets its own commit, with that rationale in the
   > message — it is exactly the kind of change that must not hide inside a deduplication.
   > Write the reasoning next to the constant, so the next person does not have to re-derive it.

4. **Does the head unit get an "Off route" indicator?** (entry 8.) Today it speaks "Rerouting"
   once and shows nothing persistent, so a driver who missed the announcement cannot tell. The
   constant deduplication is mechanical; adding the indicator is a car-UI decision.

   > **DECIDED: yes, add the indicator.**
   >
   > Two commits, not one: the constant deduplication is mechanical and behaviour-preserving,
   > the indicator is car UI that changes what a driver sees. The phone already shows this, so
   > there is a precedent to match rather than a design to invent.

### §C.1 — Resulting order of work

The two expensive decisions both expand scope, so the sequencing matters more than it did:

1. **Entry 16 first** — iOS microphone permission. It is a bug (§B), it is cheap, and decision 1
   adds a second audio client on top of it.
2. **Entry 8's constant**, then its car indicator. Independent of everything else.
3. **Decision 3** — the iOS `2.0 m/s` change, own commit, own rationale.
4. **Stage 3 as specified**, with `SectionAverageTracker` in commonMain and its output shaped
   for three consumers rather than one.
5. **Then** the car and iOS section readouts — feature work consuming what stage 3 produced.
   **Done**: `b655528` (car), `79f20b7` + `e68c815` (iOS). Entry 11 resolved.
6. **Then** the announcement policy into `shared/`, and only after that the phone `NavVoice`.

Nothing in 4–6 may share a commit with the extraction it depends on.

Three smaller ones are also genuine but do not need a meeting — a one-line answer unblocks each:
**does the watch show the instruction text it already receives** (entry 14), **should the phone's
HUD-fades-at-standstill rule reach the head unit** (entry 18, where "no" is defensible), and
**which order should a catch-up notification batch stack in** (entry 21, where "each platform is
already right for its own conventions" is a legitimate answer). Entry 19's distance quantisation is
the same shape: real, arguable, low stakes.

Note what is *not* on this list, and why. Entry 1 is not, because the fallback's absence on the car
has no rationale and `git blame` shows it was never decided. Entry 2 is not, because a stall has no
upside. Entry 4 is not, because the car's table is a strict superset. Entry 6 is not, because five
of its six items are rules iOS lacks rather than rules it does differently. Entry 16 is not,
because a feature that cannot request its own permission is broken, not different. Entry 20 is not,
because a one-letter Photon query has no defender. And entry 3 is not, because the right answer is
to leave it alone — which is a decision the register is entitled to make, since the two values turn
out to be nearly the same number expressed against different frame budgets.

**If this list grows past six, the register has stopped working.** A register where everything
needs a human is a register nobody reads.

## §D — Keeping this register current

This file is stale the moment anyone edits a copy it cites, and it cites 40-odd line numbers
across four languages. Line numbers are the wrong anchor — stage 1 alone moved every
`MapScreen.kt` citation in audit 13, and this register had to re-derive all of them.

**Anchor on content, not position.** Every entry above quotes the differing code. Turn each quote
into a one-line assertion, in the style the specs already use
(`specs/stage-3-hazard-machines-to-shared.md:21-46`) and the shared-core skill already automates
(`.claude/skills/detour-shared-core/scripts/check-preconditions.sh`, seven `PASS`/`FAIL` lines,
non-zero exit on any failure).

Concretely: `docs/refactor/mapscreen/scripts/check-divergences.sh`, one assertion per entry, each a
`grep -c` of the *quoted text* with an expected count, using the same `check`/`fails` harness as
`check-preconditions.sh`. Every assertion below was run against `a0f7f42` and produced the count
shown **at that commit**. That is no longer true of three of them: convergence 1 resolved entry
16's permission half and entry 8's car half, so the microphone assertion is inverted from `0` to
`1`, the entry-8 literal assertion is corrected to `0` — it was false the day it was written, see
entry 8 — and the car-indicator assertion is new and was never measured at `a0f7f42` at all.

**The fence is no longer measured at one commit, and pretending otherwise is what would rot it.**
Entries 1, 3, 4, 6a, 10 and 16 carry their post-convergence-1 values, measured against `3928ce0`;
entry 8's two are measured against `7d57087` and `6551f37`; and the three convergence-3 assertions
at the end of the fence were first measured against `ae32722` and did not exist before it. When
you add an assertion, say which commit produced its number.

The §B bug fixes moved six more. Measured against `aff8407`: entry 1's new reset assertion (`2`),
entry 5d's gate (`1`), entry 10's two (`0` and `1`, the second new), and entry 6a's — inverted from
`0` to `1` — plus 6b's sweep (`2`), which is new and was never measured at `a0f7f42` either. Four of
those five are absences that became presences, so four assertions flipped direction in one pass:
that is the register working as designed, and it is also why the fence is a script and not a table.

```sh
M=app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
CAR=app/src/main/java/com/jellemax/detour/car

# Entry 1 — the phone falls back to the ambient limit; the car does not.
check 'phone camera chime still falls back to the ambient limit' 1 \
    "$(grep -c 'navProgressRef.value?.speedLimitKmh ?: ambientLimitRef.value' "$M")"
check 'car camera chime still has no fallback' 1 \
    "$(grep -c 'val limit = progress?.speedLimitKmh$' $CAR/NavScreen.kt)"

# Entry 3 — the two dt clamps. NOTE the phone's is 2, not 1: the speed-dial ease
# at MapScreen.kt:964 and the camera loop at :1006 both use it. A count of 1 means
# one of the two loops changed, which is exactly what this should catch.
check 'phone dt clamp is still 0.1, in both loops' 2 "$(grep -c 'coerceIn(0.0, 0.1)'  "$M")"
check 'car dt clamp is still 0.25'                 1 "$(grep -c 'coerceIn(0.0, 0.25)' $CAR/CarMapRenderer.kt)"

# Entry 4 — only the car handles GraphHopper sign -6.
check 'the -6 roundabout-exit branch is still car-only' 1 \
    "$(grep -c -- '-6 -> Maneuver' $CAR/NavScreen.kt)"

# Entry 16 — inverted assertion, the kind worth running rather than eyeballing.
check 'iOS declares a microphone usage description' 1 \
    "$(grep -c 'NSMicrophoneUsageDescription' iosApp/Detour/Info.plist)"

# Entries 6a and 6b — RESOLVED by aff8407, then SUPERSEDED by db5a107
# (shared-convoy-relay): iOS's own `case "left"`/`prunePeersPeriodically`
# moved out of ConvoyLiveClient.swift entirely into
# shared/.../drive/ConvoyRelay.kt, which both platforms now call - the
# divergence these two guarded is no longer a thing two hand-rolled copies
# could disagree about, since there is only the one copy. Re-pointed at what
# replaced them rather than deleted, so a regression that drops either still
# fails the fence.
check 'the shared relay still handles the left frame' 1 \
    "$(grep -c 'is RelayEvent.Left ->' shared/src/commonMain/kotlin/com/jellemax/detour/drive/ConvoyRelay.kt)"
check 'the shared relay still prunes quiet peers on a timer' 1 \
    "$(grep -c 'val pruner = launch' shared/src/commonMain/kotlin/com/jellemax/detour/drive/ConvoyRelay.kt)"

# Entry 5d's gate — RESOLVED by 35b8993. Inverted: 0 means a hand-started iOS
# trip can auto-end again.
check 'iOS only auto-ends trips it auto-started' 1 \
    "$(grep -c 'else if startedAutomatically,' iosApp/Detour/TripRecorder.swift)"

# Entry 1's staleness half (§B1) — RESOLVED by bac833a. NOTE the count is 2, not
# 1: the three-miss clear inside the collector and the reset above the navigating
# gate both null the same field. A count of 1 means the reset went away — which
# is the whole bug — and this is the assertion stage 3 must keep green when
# CameraWarner takes the fallback over.
check 'the ambient limit is still reset on the navigating transition' 2 \
    "$(grep -c 'ambientSpeedLimitKmh = null' "$M")"

# Entry 8 — RESOLVED by 7d57087. Inverted on purpose: 1 means the literal came back.
check 'the 60 literal is gone' 0 \
    "$(grep -c 'offRouteMeters ?: 0.0) > 60' "$M")"

# Entry 8, car half — RESOLVED by 6551f37. The head unit's persistent indicator.
check 'the head unit has an off-route indicator' 1 \
    "$(grep -c '"Off route"' $CAR/NavScreen.kt)"

# Entry 10 — RESOLVED by c7f698a. Both halves of the old assertion are kept: the
# flag-less call must stay gone, and the flags must stay passed.
check 'car search no longer routes without the avoid-* settings' 0 \
    "$(grep -c 'RoutingServer.route(config, from, result.location, TravelMode.CAR.ghProfile)$' $CAR/SearchScreen.kt)"
check 'car search passes both routing settings' 1 \
    "$(grep -c 'Settings.avoidHighways.value, Settings.avoidSmallRoads.value' $CAR/SearchScreen.kt)"

# Entry 12 — RESOLVED. The policy exists once and all three surfaces call it.
check 'the announce ladder lives only in :shared' 1 \
    "$(grep -rl 'FAR_METERS = 800.0' shared/src/commonMain | wc -l)"
check 'no surface kept its own ladder' 0 \
    "$(grep -c 'VOICE_FAR_M\|voiceFarM' $CAR/NavScreen.kt iosApp/Detour/NavScreen.swift | grep -c ':[1-9]')"
# Entry 15 — RESOLVED. Inverted on purpose: 0 means the phone went quiet again.
check 'the phone speaks the camera warning' 1 \
    "$(grep -c 'announceAloud("Speed camera ahead")' "$M")"
```

Note the **inverted** assertions — after the §B fixes most of the fence is inverted, since a fix for
an absence is a presence to defend. `check-preconditions.sh`'s header comment explains why they
earn a script rather than a glance: *"an inverted assertion is exactly the kind a reader 'checks'
by glancing at a grep that printed nothing, which is also what a mistyped path prints."* Half this
register's findings are absences — a missing `case`, a missing key, a missing reset — so most of its
assertions are inverted, and that is the argument for the script rather than a table of line
numbers.

Three properties make this cheap enough to actually stay green:

- **It survives line drift.** A grep for the code does not care which line it is on, so a
  mechanical split does not turn the whole register red the way it turned audit 13's citations
  stale.
- **A FAIL is informative in both directions.** An entry failing means either the divergence was
  resolved (delete the entry, record the decision) or a copy moved (update the quote). Both are
  things someone should look at; neither is silent.
- **It costs one line per entry.** Twenty-two entries, ~30 assertions, one script, no CI change
  needed — though adding it next to the shared-core script in a pre-PR checklist would cost one
  line of YAML in `build.yml`, which `DECISION.md:120-123` notes runs no Kotlin test at all
  today.

**One rule to go with it.** When a divergence is resolved, the entry is not deleted — it is
marked **resolved** with the commit that resolved it and which way it went. The register's value
is the record of *what was chosen and why*, and that outlives the divergence. An empty register
with a git history nobody reads is exactly the state this file exists to prevent.

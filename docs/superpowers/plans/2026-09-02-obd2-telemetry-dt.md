# OBD2 Stage 3 — OBD-clock Δt for the fuel integrator and hard-event detectors — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Close #98. `onTripLocation` feeds `HardEventDetector.onSpeedFix`, `StopDetector.onFix` and the fuel integrator with a Δt derived from the GPS fix clock (`location.time`), even when the quantity they consume came from the OBD adapter. PID 0D arrives at ~1 Hz with 0–1000 ms `readUntilPrompt` jitter, so a real steady 50 km/h reads 49→51→49 at irregular real spacing while the detector attributes a fixed ~1 s Δt — synthesising acceleration that can nudge a borderline drive over the hard-brake/accel threshold. Fix: pass the OBD reading's own `receivedAtMs` as the fix timestamp for the consumers whose primary signal is OBD-sourced.

**Architecture:** No `shared/` change — `HardEventDetector` / `StopDetector` are already clock-free (they take an absolute `fixMs` and compute Δt from their own `state.lastFixMs`). The only change is *which timestamp* `TripTrackingService.onTripLocation` passes. `ObdTelemetry.receivedAtMs` is a `System.currentTimeMillis()`-basis stamp taken when that reading arrived over SPP (see `freshObdTelemetry`'s staleness gate) — so consecutive OBD readings carry `receivedAtMs` deltas that reflect real arrival spacing, jitter and all.

**Tech Stack:** Kotlin, Android (`:app`). No shared-module or test-module change.

**Spec:** `docs/superpowers/specs/2026-09-02-obd2-fuel-accuracy-design.md` (Stage 3 section)

## Global Constraints

- **Version bump: `1.96.0` → `1.96.1`** in `app/build.gradle.kts` (patch — latent miscalculation fix in a shipped feature, no API / data-format change). LAST task only. `versionCode` CI-stamped — never touch.
- **`onHeadingFix` keeps `location.time`** — deviation from the spec's Scope bullet, deliberate. `onHeadingFix`'s primary signal is `location.bearing` (a GPS sample arriving on the GPS cadence); its Δt must match the heading-sample spacing, not the OBD speed-reading spacing. Only the *speed*-primary consumers (`onSpeedFix`, `StopDetector`) take the OBD clock. Recorded as a ruling in the ledger.
- **The trace-distance decimation gate** (`TripTrackingService.kt:~1332`, `location.time - last.time in 1..15_000`) is NOT touched — it is a GPS-fix-spacing concern.
- **No `Obd2Connection` change, no fuel-math change.**
- **`lastFix`/telemetry-consumer isolation** (`docs/refactor/mapscreen/DECISION.md:367-373`): the fuel-integrator clock change and the hard-event/stop clock change are **separate commits**.
- **No new unit test.** This is a which-timestamp change inside `onTripLocation`, which has no unit-test harness (pre-existing). The source predicate it keys on (`obdSpeedMpsFrom(...) != null`) is already covered by `ObdSpeedResolutionTest`. Verification is the Tier-2 GPS replay A/B (see Done criteria) — user-run; no device this session.
- Branch: `fix/obd2-telemetry-dt`, already cut off `feat/obd2-fuel-type-calibration` (Stage 2, PR #126, unmerged). PR stacks on #126. Rebase `--onto main` when the chain lands.
- Commit trailers on every commit, exactly:
  ```
  Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01AA6YEKTr59Gb2ZZQdwkhoo
  ```

## Verification commands

- `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest`
- `./gradlew :app:assembleDebug :app:assembleRelease`
- `:app:lintDebug` is pre-existing-red on `notif/PlaceNotifications.kt` (untouched) — not a CI gate.

## File Structure

| File | Change |
| --- | --- |
| `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt` | fuel integrator `fixMs` → `obd.receivedAtMs` (Task 1); `onSpeedFix` + `StopDetector.onFix` get an OBD-clock `fixMs` when the speed is OBD-sourced (Task 2) |
| `app/build.gradle.kts` | `versionName` `1.96.0` → `1.96.1` (Task 3) |
| `docs/superpowers/specs/2026-09-02-obd2-fuel-accuracy-design.md` | Stage 3 Status → done; chain complete (Task 3) |

---

## Task 1: OBD-clock Δt for the fuel integrator

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt` — the `if (obd.hasFuelRate) { ... }` block (currently `:1418-1432`).

**Interfaces:**
- Consumes: `obd.receivedAtMs` (`ObdTelemetry` field), `cappedFixDtSec` (Stage 1).
- Produces: nothing — `onTripLocation` internals only.

**Equivalence / behaviour change (this IS a behaviour change — the point of the stage):**
Before, the fuel integrand `obd.fuelRateLph` was held over `location.time - lastFuelSampleMs`. After, over `obd.receivedAtMs - lastFuelSampleMs` (both stamps now the OBD arrival clock). For a promptly-processed GPS fix the two clocks are within tens of ms; the difference shows up when GPS fixes batch (several `location.time`-identical fixes describing minutes) — there the OBD clock advances monotonically and the fuel total stops under/over-counting the batched span. Still guarded by `cappedFixDtSec` (drop if the gap is outside 1..15 s).

- [ ] **Step 1: Make the change**

In the `if (obd.hasFuelRate) {` block, replace:

```kotlin
                // Fuel is a rate, so it's integrated over time, not averaged like
                // RPM above: this fix's L/h held over the gap since the last fuel
                // sample, dropped (not saturated) when that gap is outside 1..15s.
                val fixMs = location.time
                cappedFixDtSec(fixMs, lastFuelSampleMs)?.let { dtSec ->
```

with:

```kotlin
                // Fuel is a rate, so it's integrated over time, not averaged like
                // RPM above: this fix's L/h held over the gap since the last fuel
                // sample, dropped (not saturated) when that gap is outside 1..15s.
                // The gap is measured on the OBD reading's own arrival clock
                // (receivedAtMs), not the GPS fix clock — a batched burst of
                // GPS fixes shares one location.time but the fuel readings that
                // arrived across it did not (#98).
                val fixMs = obd.receivedAtMs
                cappedFixDtSec(fixMs, lastFuelSampleMs)?.let { dtSec ->
```

(`lastFuelSampleMs = fixMs` on the line below is unchanged — it now stores the OBD stamp, keeping both ends of the next gap on the same clock.)

- [ ] **Step 2: Build + test**

Run: `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest :app:assembleDebug`
Expected: green. No test asserts this path (pre-existing gap); this confirms compile + nothing else regressed.

- [ ] **Step 3: Diff read**

`git diff` — confirm only the fuel block's `fixMs` assignment + its comment changed; the trace-distance gate (`:~1332`), the `secondsOverLimit` block, and the detector calls are untouched.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt
git commit -m "$(cat <<'EOF'
fix(trip): integrate fuel over the OBD arrival clock, not the GPS fix clock (#98)

obd.fuelRateLph is now held over obd.receivedAtMs deltas. A batched burst
of GPS fixes shares one location.time; the fuel readings that landed
across it did not, so the GPS clock under/over-counted that span. Still
capped at 1..15s by cappedFixDtSec.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AA6YEKTr59Gb2ZZQdwkhoo
EOF
)"
```

---

## Task 2: OBD-clock Δt for `onSpeedFix` and `StopDetector` when the speed is OBD-sourced

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt` — the `obd2SpeedFixes` attribution (`:1395-1398`), the `onSpeedFix` call (`:1451`), the `StopDetector.onFix` call (`:1471`).

**Interfaces:**
- Consumes: `obdSpeedMpsFrom(obd, speed, stats.mode)` (existing top-level helper, tested by `ObdSpeedResolutionTest`), `obd.receivedAtMs`.
- Produces: nothing — `onTripLocation` internals only.

**Behaviour change (the point of the stage):** when `effectiveSpeedMps` came from the OBD adapter, `onSpeedFix` / `StopDetector` now compute their Δt from `obd.receivedAtMs` deltas. PID 0D's 0–1000 ms arrival jitter is now *in* the Δt instead of being flattened to a nominal ~1 s, so a 49→51→49 flap over irregular real spacing no longer integrates to a fake ±2 m/s². When the speed came from GPS (no adapter, stale telemetry, non-`tracksGForce` mode), nothing changes — `location.time` as before.

**`onHeadingFix` is deliberately NOT changed** — its signal is `location.bearing`, a GPS sample; its Δt stays on the GPS clock. See Global Constraints.

**Cross-clock caveat (document as a Known limit in the PR):** on the one fix where the speed source flips (OBD↔GPS — an adapter drop or reconnect mid-trip), `state.lastFixMs` holds the previous fix's *other* clock, so that single Δt is wrong. `HardEventDetector`'s `MIN_DT_SEC..MAX_DT_SEC` (0.2..15 s) guard rejects it (no event); `StopDetector` gets one odd `dwell`/`candidateSince` that the next same-source fix corrects. Source flips are rare.

- [ ] **Step 1: Hoist the OBD-speed decision**

Currently (`:1395-1398`):

```kotlin
        speedFixesTotal++
        if (obdSpeedMpsFrom(obd, speed, stats.mode) != null) {
            obd2SpeedFixes++
        }
```

Replace with:

```kotlin
        // Non-null iff effectiveSpeedMps below is the OBD adapter's reading
        // (not board telemetry, not the GPS fallback). Drives both the per-trip
        // attribution counter and the recorded-trip fix clock (#98).
        val obdSpeedMps = obdSpeedMpsFrom(obd, speed, stats.mode)
        speedFixesTotal++
        if (obdSpeedMps != null) {
            obd2SpeedFixes++
        }
```

- [ ] **Step 2: Add the fix-clock selection**

Immediately before the `if (stats.mode.tracksGForce) {` block (currently `:1449`), add:

```kotlin
        // The hard-brake/accel and stop detectors derive Δt from the timestamp
        // passed here. When the speed reading came from the OBD adapter, use
        // that reading's own arrival clock so PID 0D's ~1 Hz jitter lands in
        // the Δt rather than being flattened to a nominal second (#98). A GPS
        // speed keeps the GPS clock. Heading-rate cornering stays on
        // location.time — its signal is the GPS bearing.
        val recordedFixMs = if (obdSpeedMps != null && obd != null) obd.receivedAtMs else location.time
```

(`obdSpeedMps != null` already implies `obd != null` — `obdSpeedMpsFrom` returns null for a null snapshot — but the explicit `&& obd != null` is what smart-casts `obd.receivedAtMs`.)

- [ ] **Step 3: Use `recordedFixMs` at the two speed-primary call sites**

`onSpeedFix` (`:1451`):

```kotlin
                val speedResult = HardEventDetector.onSpeedFix(speedEventState, effectiveSpeedMps, recordedFixMs)
```

`StopDetector.onFix` (`:1471`):

```kotlin
            stopState = StopDetector.onFix(stopState, effectiveSpeedMps, recordedFixMs)
```

Leave `onHeadingFix` (`:1459-1460`) on `location.time`.

- [ ] **Step 4: Build + test**

Run: `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest :app:assembleDebug :app:assembleRelease`
Expected: green, R8 clean. `ObdSpeedResolutionTest` (covers `obdSpeedMpsFrom`) still passes.

- [ ] **Step 5: Diff read**

`git diff` for this commit: `onHeadingFix` still passes `location.time`; the trace gate and `secondsOverLimit` untouched; `recordedFixMs` used at exactly the two speed-primary sites; no `HardEventDetector` / `StopDetector` source (in `shared/`) changed.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt
git commit -m "$(cat <<'EOF'
fix(trip): OBD-clock Δt for the hard-event and stop detectors on OBD speed (#98)

When effectiveSpeedMps is the OBD adapter's reading, onSpeedFix and
StopDetector now derive Δt from obd.receivedAtMs deltas — PID 0D's
0-1000ms arrival jitter is in the Δt instead of flattened to a nominal
second, so a quantised 49->51->49 flap no longer integrates to a fake
±2 m/s². GPS speed keeps the GPS clock. onHeadingFix stays on
location.time (its signal is the GPS bearing).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AA6YEKTr59Gb2ZZQdwkhoo
EOF
)"
```

---

## Task 3: version bump + docs + chain close-out

**Files:**
- Modify: `app/build.gradle.kts` — `versionName = "1.96.0"` → `versionName = "1.96.1"`. Nothing else.
- Modify: `docs/superpowers/specs/2026-09-02-obd2-fuel-accuracy-design.md`.

- [ ] **Step 1: Bump the version**

`app/build.gradle.kts`: `versionName = "1.96.1"`.

- [ ] **Step 2: Update the design doc**

- Stage 3 `**State**` line → `**done** <date>, branch fix/obd2-telemetry-dt`, with the commit range and the deviation recorded (`onHeadingFix` kept on `location.time` — its signal is the GPS bearing). Note the Tier-2 GPS-replay A/B is **user-run, not done this session** (no device).
- Add a line near the top-of-doc chain diagram / the "chain complete" stop-point: **all of #96–#98, #100, #101, #103 are now closed across #114 / #121 / #126 / this PR.**

- [ ] **Step 3: Full verification**

Run: `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest :app:assembleDebug :app:assembleRelease`
Expected: all green, R8 clean.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts docs/superpowers/specs/2026-09-02-obd2-fuel-accuracy-design.md
git commit -m "$(cat <<'EOF'
chore: versionName 1.96.0 -> 1.96.1 for the OBD2 telemetry-clock fix (#98)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AA6YEKTr59Gb2ZZQdwkhoo
EOF
)"
```

- [ ] **Step 5: PR** (controller does this after the whole-branch review + the user's finish decision — NOT part of task execution)

Base `feat/obd2-fuel-type-calibration`. `Closes #98`. Body per `detour-pr-writing`, carrying: the before/after framing (nominal-1s Δt → real arrival-spacing Δt), the `onHeadingFix` deviation, the cross-clock source-flip caveat, and **Tier-2 GPS replay A/B unverified this session — user to run against `tools/mocklocation/baseline/`**.

---

## Self-Review

**Spec coverage (Stage 3 section):**
- "when `effectiveSpeedMps` came from the OBD snapshot, derive the Δt handed to `onSpeedFix` ... and `StopDetector` from `obd.receivedAtMs`" → Task 2.
- "`onHeadingFix`" in the spec's list → **deviated** (kept on `location.time`), rationale in Global Constraints + Task 2, to be recorded as a ruling.
- "fuel integrator ... Δt operand becomes an `obd.receivedAtMs` delta, still guarded by `cappedFixDtSec`" → Task 1.
- "A small helper ... if it reads cleaner than inlining — decide in the plan" → **decided: no helper.** The detectors take an absolute `fixMs` and compute Δt internally; the change is a one-line clock selection (`recordedFixMs`), and the fuel site already routes through `cappedFixDtSec`. A `recordedFixMs(...)` helper would wrap a single ternary.
- Out of scope (thresholds / trace gate / `Obd2Connection` / fuel math) → none touched.
- Version `1.96.0` → `1.96.1` → Task 3.
- Tier-2 GPS replay mandatory-or-say-unverified → Task 3 Step 2 + the PR body; flagged user-run.

**Placeholder scan:** none. `<date>` / commit ranges in Task 3 are runtime values.

**Type consistency:** `obdSpeedMps` (the hoisted `obdSpeedMpsFrom(...)` result, `Double?`) used at the attribution counter and the `recordedFixMs` selection — same name, same type, Tasks 2. `recordedFixMs: Long` passed to `onSpeedFix` / `StopDetector.onFix`, both of which take `fixMs: Long` (verified against `HardEventDetector.kt:34`, `StopDetector.kt:31`). `obd.receivedAtMs: Long` (`ObdTelemetry`). `cappedFixDtSec(fixMs, lastFuelSampleMs)` unchanged from Stage 1.

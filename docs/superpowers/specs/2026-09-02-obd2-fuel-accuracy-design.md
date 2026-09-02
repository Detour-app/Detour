# OBD2 fuel accuracy: fuel type, calibration, commanded lambda, and an OBD-clock Δt

Design date: 2026-09-02
Closes: #100, #101, #103, #98
Prerequisite: #114 (`fix/obd2-connection-lifecycle`, closes #96, #97) merged first

## Why

The MAF-derived fuel figure (`Obd2Pids.fuelRateFromMafLph`) assumes the engine
burns **petrol at exactly the stoichiometric ratio** — 14.7:1 air-fuel mass,
745 g/L density, and no excess air. Every one of those is wrong for a diesel:

- diesel fuel is denser (~832 g/L) and stoichiometric at ~14.5:1;
- more importantly a diesel is a **lean-burn** engine — at light cruise it runs
  λ ≈ 2.0–2.5 (twice as much air as it needs to burn its fuel), so the
  "all intake air is burned at stoichiometric" assumption over-estimates fuel
  by roughly that factor.

Field report that opened this: a VW TDI reads **14.1 l/100km** on the trip
detail screen while the car's own trip computer shows **6.2 l/100km** — a 2.3×
over-read, which is exactly a diesel cruise λ.

Fixing the physics needs a per-vehicle fuel type (#101) and, to catch the part
a fuel type cannot — injector wear, MAF sensor drift, fuel blend, the residual
lean-burn error past the PID-44 ceiling — a per-vehicle calibration knob
(#100). Adding the commanded-equivalence-ratio PID (0144) as a third probed,
per-cycle input is what makes the diesel number land near the dash reading
without the user having to tune anything. That third probe is unmaintainable
bolted onto the two copy-pasted probe state machines `pollLoop` already carries
(#103), so the probe helper comes first.

Separately, `#98`: the fuel integrator and the hard-event detectors take an
OBD-sourced quantity (fuel rate, speed) but derive their Δt from the GPS fix
clock. PID responses arrive at ~1 Hz with up to a second of `readUntilPrompt`
jitter, so the Δt is wrong for exactly the samples that came from OBD. The
telemetry already carries a `receivedAtMs` stamp; the fix is to use it.

## The fuel math

Today (`Obd2Pids.kt:78-79`):

```kotlin
fun fuelRateFromMafLph(mafGramsPerSec: Double): Double =
    mafGramsPerSec / STOICH_AFR_PETROL / FUEL_DENSITY_G_PER_L * 3600.0
```

After:

```
fuel L/h  =  MAF (g/s)
             / (AFR_stoich(fuelType) · λ)      // actual air-fuel mass ratio
             / density(fuelType)               // g/L
             · 3600                            // s → h
             · calibrationPct / 100            // per-vehicle trim
```

| Term | Petrol | Diesel | Where it comes from |
| --- | --- | --- | --- |
| `AFR_stoich` | 14.7 | 14.5 | constant, selected by `fuelType` |
| `density` (g/L) | 745 | 832 | constant, selected by `fuelType` |
| `λ` | commanded value, else 1.0 | commanded value, else 1.0 | **PID 0144**, `(2 / 65536) · (256·A + B)`; 1.0 when the adapter doesn't answer it |
| `calibrationPct` | 100 | 100 | **new `VehicleDevice.fuelCalibrationPct`**, user sets it against the dash / a pump fill-up |

### Checked against the field report

VW TDI, `fuelType = DIESEL`, adapter answers 0144. At light cruise the true λ
is ~2.27; PID 0144 saturates at `(2/65536)·65535 ≈ 2.0`, so the estimate uses
λ = 2.0:

```
ratio vs today = (14.7 · 745) / (14.5 · 2.0 · 832)
               = 10951.5 / 24128
               ≈ 0.454
14.1 l/100km · 0.454 ≈ 6.4 l/100km        (dash: 6.2 — within ~3%)
```

The calibration knob (here ~97%) closes the last few percent and absorbs the
load-dependent residual the single λ cap leaves behind.

If the adapter does **not** answer 0144 (λ falls back to 1.0):

```
ratio = (14.7 · 745) / (14.5 · 832) ≈ 0.908
14.1 · 0.908 ≈ 12.8 l/100km
```

— still wrong, and the user has to set `fuelCalibrationPct ≈ 48`. Documented
fallback, not the happy path: a fixed multiplier is only correct at one engine
load, so a value tuned on the motorway over-reads in town. The trip-detail
caveat row says so.

### Petrol is (almost) unchanged

With `fuelType = PETROL` the constants are today's exactly. λ is the only
mover: a petrol engine in closed loop commands λ ≈ 0.97–1.03, so a petrol
vehicle whose adapter answers 0144 sees its estimate wobble by a few percent
against today's flat number, and reads richer (correctly) under wide-open-
throttle enrichment. This is a real accuracy gain, not a regression, and it is
smaller than the swing the existing deceleration-fuel-cut zero already
produces. A petrol vehicle whose adapter does not answer 0144, at
`calibrationPct = 100`, gets a bit-identical number to today.

### Deceleration fuel cut

`resolveFuelRate`'s existing DFCO branch (throttle closed + rpm > 1200 +
moving → 0 L/h) stays as a backstop for adapters without 0144. When 0144 is
present it is mostly redundant — commanded λ climbs steeply on a trailing
throttle — but it costs nothing and covers the gap.

## Data format

`Settings.VehicleDevice` (`Settings.kt:129-134`) gains two fields:

```kotlin
data class VehicleDevice(
    val address: String,
    val name: String,
    val mode: TravelMode,
    val obd2Address: String? = null,
    val fuelType: FuelType = FuelType.PETROL,   // new
    val fuelCalibrationPct: Int = 100,          // new
)
```

`FuelType` is a new enum in `shared` (`commonMain/.../drive/` next to
`Obd2Pids`): `PETROL`, `DIESEL`. `PETROL` is the default so every existing
vehicle keeps today's behaviour with no migration.

`encodeVehicleDevice` writes `fuelType` only when it is not `PETROL` and
`fuelCalibrationPct` only when it is not `100` — same "absent key ⇒ default"
shape as `obd2Address` at `Settings.kt:342` / `:350`. `decodeVehicleDevice`
reads them tolerantly: an unknown `fuelType` string falls back to `PETROL`
(`runCatching { FuelType.valueOf(...) }`), a missing or out-of-range
`fuelCalibrationPct` falls back to `100`.

**This is an additive, backward-compatible change** — an older build reading a
new-format entry ignores the two keys and a newer build reading an old entry
gets the defaults. That is a **minor** bump under `CONTRIBUTING.md`, not the
major that a breaking data-format change would be.

Calibration range clamped on write and on read to **50–150%**. Outside that
the vehicle is either not a diesel or the adapter is returning garbage — a
wider range just lets a mis-set value hide a real bug.

## Settings UI

All of it on the **OBD2 pairing screen** (`Obd2PairingScreen.kt`), in the
per-vehicle block that today shows `Adapter: <name>` / `Forget`
(`:207-223`). When a vehicle has an adapter paired, that block also shows:

- a **Petrol / Diesel** segmented control bound to
  `Settings.setFuelType(vehicle.address, ...)`;
- a **calibration** stepper (−/+ 1%, 50–150, default 100) bound to
  `Settings.setFuelCalibrationPct(...)`, with a one-line hint: *"If the trip
  fuel figure reads high or low against your car's own display, nudge this."*

Both hidden until an adapter is paired — they do nothing without one. No change
to the main Settings "Vehicles" section.

Two new `Settings` setters, same shape as `setObd2Address` (`:368-373`):
`setFuelType(address, FuelType)` and `setFuelCalibrationPct(address, Int)`,
each `copy(...)`-ing the one `VehicleDevice` and calling `writeVehicleDevices`.

## Trip-detail caveat

`TripDetailScreen.kt:604-610` currently hardcodes *"it assumes petrol"*. Reword
to name the configured fuel type and flag a non-default calibration, e.g.:

> Fuel is a MAF-based estimate — this vehicle has no direct fuel-rate PID.
> Estimated for **diesel**, calibrated to **97%**. Still an estimate: it
> tracks engine load, not the injectors.

Only the wording changes; the row still gates on `fuelEstimated`.

---

# The chain

> **Update 2026-09-02:** #114 (Stage 0) squash-merged to `main` as `f4d12f5`, and
> `main` moved to `versionName 1.95.0` via #106. The stacking scheme below is now
> moot — **each stage branches straight off current `main` and its PR targets
> `main`.** Ordering is enforced by each stage's executable Preconditions (a stage
> whose predecessor has not landed fails its precondition grep) rather than by a
> PR base chain. Versions shift up one: S1 no bump (stays `1.95.0`), **S2 →
> `1.96.0`**, **S3 → `1.96.1`**.

Four stages. Each is its own branch off `main`, its own PR against `main`, and
its own version decision.

```
#114  fix/obd2-connection-lifecycle   → main     closes #96 #97   MERGED (f4d12f5)
S1    refactor/obd2-probe-helper      → main     closes #103      no version bump   DONE (rebased)
S2    feat/obd2-fuel-type-calibration → main     closes #100 #101 minor → 1.96.0
S3    fix/obd2-telemetry-dt           → main     closes #98       patch → 1.96.1
```

Land each stage before branching the next (its Preconditions assume the prior
one is on `main`).

Every stage: **one work item ⇒ one commit**, no work item spanning two commits.
The "never in one commit" rules from
`docs/refactor/mapscreen/DECISION.md:367-373` apply — in particular a stage-3
`lastFix`/telemetry-consumer change never shares a commit with anything else.

---

## Stage 1 — probe helper + `cappedFixDtSec`

**State** | **done** 2026-09-02, branch `refactor/obd2-probe-helper`, rebased onto
`main` after #114 (Stage 0) squash-merged as `f4d12f5` — so this branch's PR targets
`main` directly, not a stacked base. `probePidCycle` + the `PidProbe` sealed state
replace both inline probe ladders in `pollLoop` (`probePidCycle` primitive + tests +
the `FUEL_PROBE_MAX_CYCLES`→`PID_PROBE_MAX_CYCLES` rename; fuel wiring zero-delta;
throttle wiring carrying the three intended #103 deltas — cycle budget, no idle 0145
re-poll, watchdog no longer fed by it); `cappedFixDtSec` folds the fuel +
`secondsOverLimit` gap guards, operands still the GPS clock. Post-rebase commits
`5ff2c6c..7112a34` (code: `7d1e4d8` primitive, `07f602e` fuel, `df1942b` throttle,
`ebbe295` cappedFixDtSec). Plan:
`docs/superpowers/plans/2026-09-02-obd2-probe-helper.md`. Each task spec-reviewed;
one fix round on the throttle commit body; whole-branch review clean (one added test
+ doc nits). Suites + `assembleDebug` + `assembleRelease` (R8) green on the rebased
tree. `:app:lintDebug` is pre-existing-red on `notif/PlaceNotifications.kt`
(untouched here) and is not a CI gate — this chain's four code files add zero lint
findings. Live-adapter path unverified — no dongle this session. **No version bump**
(`main` is at `1.95.0` after #106 — Stage 2 now bumps to `1.96.0`, Stage 3 `1.96.1`).

### Preconditions

```sh
M=app/src/main/java/com/jellemax/detour/obd2/Obd2Connection.kt
T=app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt

git merge-base --is-ancestor origin/main HEAD || echo "FAIL: not on the #114 branch or later"
grep -c 'var throttlePid: String? = null' $M          # expect 1
grep -c 'var fuelPid: String? = null' $M              # expect 1
grep -c 'FUEL_PROBE_MAX_CYCLES' $M                    # expect >= 3
grep -c 'probePidCycle' $M                            # expect 0  (helper does not exist yet)
grep -c '15_000' $T                                   # expect 4  (trace-distance + fuel + secondsOverLimit guards, plus 1 comment referencing them)
grep -c 'cappedFixDtSec' $T                           # expect 0
```

### Why this stage

Stage 2 adds PID 0144 as a third probe-and-latch input. `pollLoop`
(`Obd2Connection.kt:273-410`) already carries two near-identical ones —
`throttlePid` (0145 → 0111) and `fuelPid` (015E → 0110) — that have already
drifted apart (#103): the fuel probe has a cycle budget and refuses to latch
its give-up sentinel on a bare timeout; the throttle probe latches on the first
answered-unsupported and has no budget. A third copy is not acceptable.

### Scope

- New `internal fun probePidCycle(input, output, state, primary, fallback, maxCycles)`
  in `Obd2Connection`, collapsing both probe blocks. It runs one poll cycle and
  returns `(newState, PollResult?)`. State is a `PidProbe` sealed interface —
  `Probing(cycles)` / `Latched(pid)` / `Unsupported` — which replaces the two
  ad-hoc sentinels (`null` / `""` for fuel, a re-latched real PID for throttle).
  `fallback` is nullable (lambda has no alternative PID). Shared cycle budget
  `FUEL_PROBE_MAX_CYCLES` renamed `PID_PROBE_MAX_CYCLES`. Exact signature and
  the equivalence argument are in the plan.
- Rewrite the `throttlePid` and `fuelPid` blocks to call it. The throttle
  probe **gains** the fuel probe's robustness — a budget, and no give-up latch
  on a bare timeout. This is the one intended behaviour delta in an otherwise
  structural stage; it is a strict hardening (a transient can no longer
  misclassify throttle support) and is called out in the PR body.
- New top-level `internal fun cappedFixDtSec(nowMs: Long, lastMs: Long): Double?`
  in `TripTrackingService.kt` (beside `obdSpeedMpsFrom` / `pickObd2Address`, so
  it is unit-testable) — returns the gap in seconds when `lastMs > 0` and the
  gap is in `1..15_000` ms, else null. Fold the **fuel** (`:1421`) and
  **secondsOverLimit** (`:1497`) sites onto it. Leave the trace-distance gate
  (`:1332`) alone — it keys off the GPS fix clock deliberately and stage 3
  revisits that path.

### Out of scope

- PID 0144, fuel type, calibration — stage 2.
- Changing which Δt clock the fuel integrator uses — stage 3. This stage only
  extracts the `1..15_000` guard as-is; the operands stay `fixMs` /
  `lastFuelSampleMs`.
- Any `ObdTelemetry` field change.

### Work items

1. `probePidCycle` helper + `PidProbe` state + `PID_PROBE_MAX_CYCLES` rename — added, not
   yet called. Compiles, unused-function warning expected.
2. Route `fuelPid` through the helper. `Obd2ConnectionTest` green.
3. Route `throttlePid` through the helper (carries the behaviour delta).
   `Obd2ConnectionTest` green; add a case for "transient timeout does not latch
   throttle support".
4. `cappedFixDtSec` helper + fold the two `TripTrackingService` sites.
5. `docs/` — mark this stage's Status `done`, record stop-point.

### Done criteria and verification

- `grep -c 'probePidCycle' $M` ⇒ 3 (definition + two call sites).
- `grep -c 'var fuelPid\|var throttlePid' $M` unchanged shape or replaced by the
  holder — the two ad-hoc `when` ladders gone.
- `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest` green.
- `./gradlew :app:assembleDebug :app:assembleRelease` — R8 clean.
- Tier 1 (desk, stationary): with no physical adapter this session, flagged
  **unverified-live**. The pure stream logic is covered by `Obd2ConnectionTest`.

### Version

No bump. Pure refactor (`CONTRIBUTING.md`: "refactor → no bump"). The throttle
hardening is latent-bug-only, no user-visible behaviour, so it does not pull
this to a patch — noted in the PR body, decide against `CONTRIBUTING.md` at
commit time if that reading is disputed.

### Stop-point

`pollLoop` has one probe primitive and `TripTrackingService` has one capped-Δt
helper. **No fuel number has changed** — this must not be recorded as "fuel
accuracy fixed". Next: stage 2.

---

## Stage 2 — fuel type + calibration + commanded lambda

**State** | **done** 2026-09-02, branch feat/obd2-fuel-type-calibration. `FuelType` enum + diesel AFR/density constants + PID 0144 decoder in `shared/Obd2Pids`; `fuelRateFromMafLph` / `resolveFuelRate` gain `fuelType` / `lambda` / `calibrationPct` params (defaulted, petrol path bit-identical); `VehicleDevice` fields additive JSON; `connect()` threads config to `pollLoop` (no Settings dep); `pollLoop` probes 0144 via `probePidCycle` with null fallback; pairing-screen Petrol/Diesel segmented + calibration stepper; trip-detail caveat reworded generic (TripStore unchanged, trip has no fuel-type link). Decode uses `optInt` per decision Task 3 Step 4. Commits a8ae4e6..(this commit). Live-0144 unverified — no adapter this session.

### Preconditions

```sh
P=shared/src/commonMain/kotlin/com/jellemax/detour/drive/Obd2Pids.kt
S=shared/src/commonMain/kotlin/com/jellemax/detour/data/Settings.kt
M=app/src/main/java/com/jellemax/detour/obd2/Obd2Connection.kt

grep -c 'probePidCycle' $M                            # expect 3   (stage 1 landed)
grep -c 'STOICH_AFR_PETROL' $P                        # expect 2   (still petrol-only)
grep -c 'fun fuelRateFromMafLph(mafGramsPerSec: Double)' $P   # expect 1  (old signature)
grep -c 'enum class FuelType' $P $S                   # expect 0
grep -c 'fuelType\|fuelCalibrationPct' $S             # expect 0
grep -c 'PID_EQUIV_RATIO\|"0144"' $P $M               # expect 0
grep -c 'obd2Address.*takeIf.*isNotBlank' $S          # expect >= 1  (decode pattern to copy)
```

### Scope

- `FuelType` enum (`PETROL`, `DIESEL`) in `shared` `drive/`.
- `Obd2Pids`:
  - `STOICH_AFR_DIESEL = 14.5`, `FUEL_DENSITY_DIESEL_G_PER_L = 832.0`;
    keep the petrol constants, rename to `_PETROL` symmetry.
  - `fun parseCommandedEquivRatio(dataBytes): Double?` — `(2.0 / 65536.0) *
    (256*A + B)`, null on short input, same shape as the other parsers.
  - `PID_EQUIV_RATIO = "0144"`.
  - `fuelRateFromMafLph(maf, fuelType, lambda, calibrationPct)` — new params,
    `lambda` defaulting to 1.0 and `calibrationPct` to 100 so existing test
    call sites (`Obd2PidsTest.kt:126,133,138`) still compile and still assert
    the petrol number.
  - `resolveFuelRate(...)` gains `fuelType`, `lambda`, `calibrationPct` and
    threads them into the non-cut branch (`:112`).
- `Obd2Connection.pollLoop`: probe + per-cycle poll 0144 via `probePidCycle`
  (fallback = null). Feed the parsed λ (or 1.0) plus the
  trip vehicle's `fuelType` / `fuelCalibrationPct` into `resolveFuelRate`.
  - the vehicle config reaches `Obd2Connection` the same way the target
    address does — `Obd2Connection.connect(context, address)` already knows
    which adapter; add the `FuelType` + `Int` alongside it, or have
    `pollLoop` read `Settings.vehicleDevices.value` keyed by `linkedAddress`.
    Decide in the plan; prefer passing them into `connect()` so `Obd2Connection`
    keeps no `Settings` dependency.
  - **No new `ObdTelemetry` field** — λ is consumed where fuel is already
    resolved (`Obd2Connection.kt:356-367`).
- `Settings`: two fields on `VehicleDevice`, tolerant decode, guarded encode,
  `setFuelType` / `setFuelCalibrationPct` setters, 50–150 clamp.
- `Obd2PairingScreen`: the segmented control + stepper described above.
- `TripDetailScreen`: reword the caveat row.
- `TripStore`: **no change** — `fuelEstimated` already records the MAF path;
  the fuel type is a property of the vehicle, not the trip, and re-deriving a
  historical trip's fuel type is out of scope.

### Out of scope

- O2-sensor lambda PIDs (0134–013B) as a 0144 alternative — 0144 is the
  commanded value and is enough; the wide-range sensor PIDs are a later
  refinement if a diesel that lacks 0144 turns up.
- Retroactively recomputing stored trips.
- The Δt clock (#98) — stage 3.
- A global calibration setting — per-vehicle only, decided in design.

### Work items (sketch — plan expands)

1. `FuelType` enum + `Obd2Pids` constants, `parseCommandedEquivRatio`,
   `PID_EQUIV_RATIO`. Unit tests for the parser + the two-fuel math.
2. `fuelRateFromMafLph` / `resolveFuelRate` new params (defaulted).
   `Obd2PidsTest` extended: diesel cruise λ=2.0 ⇒ the ~0.45 ratio; petrol
   λ=1.0 calibration=100 ⇒ unchanged.
3. `Settings.VehicleDevice` fields + decode/encode + setters + clamp.
   `SettingsVehicleDeviceTest` extended: round-trip, old-format read, unknown
   `fuelType`, out-of-range calibration.
4. `Obd2Connection` — probe/poll 0144, thread vehicle config into
   `resolveFuelRate`.
5. `Obd2PairingScreen` controls.
6. `TripDetailScreen` caveat wording.
7. `versionName` minor bump + `docs/` Status + PR body closing #100, #101.

### Done criteria and verification

- `Obd2PidsTest`: petrol path bit-identical to today at defaults; diesel + λ
  path produces the field-report ratio within 1e-3.
- `SettingsVehicleDeviceTest`: new fields survive a write/read round trip and
  an old-format entry decodes to `PETROL` / `100`.
- `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest` green.
- `./gradlew :app:assembleDebug :app:assembleRelease`.
- `./gradlew :app:lintDebug`.
- Tier 1: pairing-screen controls write and persist; trip-detail caveat reads
  the configured type. **Live-adapter check flagged unverified** — no dongle
  this session; the on-road diesel A/B is the user's to run and report back.

### Version

Minor. `1.95.0` → `1.96.0` (`main` moved to 1.95.0 via #106). New user-facing feature (per-vehicle fuel type +
calibration), backward-compatible additive data-format change.

### Stop-point

A diesel with a 0144-capable adapter reads within a few percent of its dash;
any vehicle can be trimmed with the calibration knob. Next: stage 3, the Δt
clock — independent of this, ships separately.

---

## Stage 3 — OBD-clock Δt for the fuel integrator and hard-event detectors

**State** | **done** 2026-09-02, branch `fix/obd2-telemetry-dt` (PR stacks on #126).
The fuel integrator now measures its gap on `obd.receivedAtMs` deltas (a batched
GPS burst shares one `location.time`; the fuel readings across it did not); and
`HardEventDetector.onSpeedFix` / `StopDetector.onFix` take an OBD-clock `fixMs`
when `effectiveSpeedMps` is the adapter's reading, so PID 0D's 0–1000 ms arrival
jitter lands in the Δt instead of being flattened to a nominal second. **Deviation
from the Scope bullet:** `onHeadingFix` is kept on `location.time` — its signal is
the GPS bearing, so its Δt must track the heading-sample spacing, not the OBD
speed-reading spacing. Commits `e8e4c3b` (fuel), `c95bb80` (detectors),
`versionName` stays `1.96.0` (inherited from #126 — the stack lands as one minor bump). No `shared/` change; no new unit test (a
which-timestamp change in the untested `onTripLocation`; the source predicate
`obdSpeedMpsFrom` is already covered by `ObdSpeedResolutionTest`). Suites +
`assembleRelease` (R8) green. **Tier-2 GPS-replay A/B against
`tools/mocklocation/baseline/` NOT run this session** — no device; it is the
field check for "OBD-sourced segments no longer synthesise acceleration from
clock jitter, GPS-sourced behaviour unchanged".

Chain complete: **#96, #97 (#114) · #103 (#121) · #100, #101 (#126) · #98 (this
PR)** — all six deferred OBD2 issues closed. The remaining OBD2-adjacent open
question, driving-behaviour threshold calibration, is #61 and is not part of
this chain.

### Preconditions

```sh
T=app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt

grep -c 'cappedFixDtSec' $T                           # expect >= 2  (stage 1 landed)
grep -c 'fun parseCommandedEquivRatio' shared/src/commonMain/kotlin/com/jellemax/detour/drive/Obd2Pids.kt  # expect 1  (stage 2 landed)
grep -c 'HardEventDetector.onSpeedFix\|onHeadingFix' $T    # expect >= 1
grep -c 'freshObdTelemetry()' $T                     # expect >= 2
grep -n 'receivedAtMs' $T                             # INFO — where the OBD stamp is already read
```

### Scope

- In `onTripLocation`: when `effectiveSpeedMps` came from the OBD snapshot
  (the same test `resolveDisplaySpeedMps` / `speedIsReal` already make), derive
  the Δt handed to `HardEventDetector.onSpeedFix` / `onHeadingFix` and
  `StopDetector` from `obd.receivedAtMs` deltas, not `location.time`. When the
  speed came from GPS, keep the GPS clock.
- The fuel integrator (`:1420-1428`): its Δt operand becomes an
  `obd.receivedAtMs` delta (fuel rate is always an OBD reading), still guarded
  by `cappedFixDtSec`.
- A small helper for "Δt since the last OBD sample, capped" if it reads
  cleaner than inlining — decide in the plan.

### Out of scope

- The hard-event **thresholds** themselves — that is #61, needs recorded-trip
  data, explicitly deferred.
- The trace-distance decimation gate (`:1332`) — it is a GPS-fix-spacing
  concern, not an OBD one.
- Any change to `Obd2Connection` or the fuel math.

### Work items (sketch)

1. OBD-clock Δt for the fuel integrator. One commit.
2. OBD-clock Δt for `HardEventDetector` / `StopDetector` when speed is
   OBD-sourced. **Its own commit** — a `lastFix`/telemetry consumer change
   never shares a commit (`DECISION.md:367-373`).
3. `docs/` Status + PR body closing #98. **No `versionName` bump** — this stage
   is stacked on S2 and inherits its `1.96.0`; the stack lands as one minor bump.

### Done criteria and verification

- `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest` green.
- `./gradlew :app:assembleDebug :app:assembleRelease`.
- **Tier 2 — mandatory.** `detour-gps-replay` A/B against
  `tools/mocklocation/baseline/` (or a fresh baseline captured on the stage-2
  branch **before** work item 1): replay a route with a synthetic OBD speed
  track, confirm the hard-event count and stop-detection are unchanged for
  GPS-sourced speed and that OBD-sourced segments no longer synthesise
  acceleration from clock jitter. If the harness cannot be run this session,
  the stage ships **unverified-live** and says so — a Tier 1 checklist is not
  a substitute.

### Version

Patch. `1.96.0` → `1.96.1`. Fixes a latent miscalculation in a shipped
feature, no API or data-format change.

### Stop-point

Chain complete. #96–#98, #100, #101, #103 all closed. Remaining OBD2-adjacent
open question — driving-behaviour threshold calibration — is #61 and is not
part of this chain.

---

## PR stacking

The mechanism that stops a wrong-order merge is the **base branch**:

| PR | head | base | may merge when |
| --- | --- | --- | --- |
| #114 | `fix/obd2-connection-lifecycle` | `main` | now |
| S1 | `refactor/obd2-probe-helper` | `fix/obd2-connection-lifecycle` | #114 merged (GitHub then auto-retargets S1 → `main`) |
| S2 | `feat/obd2-fuel-type-calibration` | `refactor/obd2-probe-helper` | S1 merged |
| S3 | `fix/obd2-telemetry-dt` | `feat/obd2-fuel-type-calibration` | S2 merged |

GitHub will not offer "merge" on S2 into `main` while its base is
`refactor/obd2-probe-helper` and that branch is unmerged — the PR simply
targets a branch, not `main`, until the base lands. Each PR body also carries
an explicit **`Depends on #<prev>`** line and the CI is the same build gate on
every branch.

Workflow as each base merges:

```sh
git checkout refactor/obd2-probe-helper
git rebase --onto main fix/obd2-connection-lifecycle
git push --force-with-lease
gh pr edit <S1> --base main
# repeat for S2 onto its new base, S3 onto its new base
```

If a base branch is deleted on merge (the repo default), GitHub retargets the
child PR to `main` automatically and only the rebase + force-push is needed.

## Versioning summary

| Stage | Change class | `versionName` |
| --- | --- | --- |
| #114 | design change to shipped behaviour | `1.94.0` → shipped; `main` now `1.95.0` (#106) |
| S1 | refactor | `1.95.0` (unchanged) |
| S2 | feature, additive data format | `1.96.0` |
| S3 | latent-bug fix | `1.96.0` (no second bump — stacked on S2, lands in the same minor) |

`versionCode` is CI-stamped — never touched here.

## Testing posture

No Robolectric or instrumented source set exists (same carve-out as #61/#62).
What each stage can and cannot prove at a desk:

- **Provable now**: all `Obd2Pids` math and parsers (`shared` unit tests), the
  `Settings.VehicleDevice` round trip, the probe helper's stream logic
  (`Obd2ConnectionTest`), the capped-Δt helper.
- **Tier 2, replayable without driving**: stage 3's hard-event / stop-detector
  Δt change (`detour-gps-replay`).
- **Needs a physical adapter — the user's to verify and report**: the live
  0144 probe on a real VW TDI, and the on-road diesel fuel A/B against the dash
  computer. Every stage PR that touches this path is labelled
  **unverified-live** in its body rather than claimed as tested.

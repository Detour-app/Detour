# OBD2 Stage 2 — fuel type + calibration + commanded lambda — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the MAF-derived fuel estimate correct for a diesel — a VW TDI reading 14.1 l/100km against a 6.2 dash figure — by giving each vehicle a fuel type (petrol/diesel) and a calibration multiplier, and by folding in the commanded air-fuel equivalence ratio (PID 0144) so the lean-burn factor is measured, not assumed.

**Architecture:** The fuel math lives in `shared/` (`Obd2Pids`, pure, unit-tested on JVM + Kotlin/Native). `fuelRateFromMafLph` / `resolveFuelRate` gain `fuelType` / `lambda` / `calibrationPct` params, all defaulted so petrol at defaults is bit-identical to today. `Settings.VehicleDevice` gains `fuelType` + `fuelCalibrationPct` (additive JSON, absent ⇒ default). `Obd2Connection.connect()` carries the per-vehicle config down to `pollLoop` (no `Settings` dependency in `Obd2Connection`); `pollLoop` probes 0144 through the Stage-1 `probePidCycle` primitive (no fallback PID) and feeds λ into `resolveFuelRate`. UI: a Petrol/Diesel toggle + a calibration stepper on the OBD2 pairing screen.

**Tech Stack:** Kotlin Multiplatform (`shared/` commonMain + commonTest), Android (`:app`), Compose, JUnit4 (`:app` tests) + `kotlin.test` (`shared` tests), Gradle.

**Spec:** `docs/superpowers/specs/2026-09-02-obd2-fuel-accuracy-design.md` (Stage 2 section, and "The fuel math" / "Data format" / "Settings UI" / "Trip-detail caveat" up top)

## Global Constraints

- **Version bump: `1.95.0` → `1.96.0`** in `app/build.gradle.kts` (minor — new user-facing feature, additive backward-compatible data format). Bump in the LAST task only. `versionCode` is CI-stamped — never touch it.
- **Petrol at defaults must not move.** `fuelType = FuelType.PETROL`, `lambda = 1.0`, `calibrationPct = 100` ⇒ `fuelRateFromMafLph` returns exactly `maf / 14.7 / 745 * 3600`. The existing `Obd2PidsTest` petrol assertions (`:126`, `:133`, `:138`, `:157`) must pass unchanged.
- **Additive data format.** `encodeVehicleDevice` writes `fuelType` only when `!= PETROL` and `fuelCalibrationPct` only when `!= 100`. `decodeVehicleDevice` returns `PETROL` / `100` for absent, unknown, or out-of-range values (`runCatching`). An old build reading a new entry ignores the keys; a new build reading an old entry gets defaults.
- **Calibration clamped to 50–150** on both write (`setFuelCalibrationPct`) and read (`decodeVehicleDevice`).
- **No new `ObdTelemetry` field.** λ is consumed inside `pollLoop` where fuel is already resolved.
- **No `Settings` import in `Obd2Connection`.** Per-vehicle config reaches `pollLoop` as `connect()` parameters.
- **`TripStore` unchanged.** Fuel type is a property of the vehicle, not the trip; historical trips are not recomputed.
- **One work item ⇒ one commit.** No item spans two commits; no commit spans two items.
- **`shared/` changes** must pass `./gradlew :shared:compileCommonMainKotlinMetadata` (catches `java.*` in commonMain) — CI runs it path-gated on `shared/**`, which this stage triggers.
- Branch: `feat/obd2-fuel-type-calibration`, already cut off `refactor/obd2-probe-helper` (Stage 1, PR #121, unmerged). When #121 lands, rebase `--onto main`. PR targets `main`.
- Commit trailers on every commit, exactly:
  ```
  Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01AA6YEKTr59Gb2ZZQdwkhoo
  ```

## Verification commands

- Shared tests (JVM): `./gradlew :shared:testDebugUnitTest`
- Shared commonMain metadata (java.* leak check): `./gradlew :shared:compileCommonMainKotlinMetadata`
- App tests: `./gradlew :app:testDebugUnitTest`
- Both + builds: `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest :app:assembleDebug :app:assembleRelease`
- Single shared class: `./gradlew :shared:testDebugUnitTest --tests "com.jellemax.detour.drive.Obd2PidsTest"`
- Single app class: `./gradlew :app:testDebugUnitTest --tests "com.jellemax.detour.obd2.Obd2ConnectionTest"`
- `:app:lintDebug` is pre-existing-red on `notif/PlaceNotifications.kt` (untouched here) — not a CI gate; informational only.

## File Structure

| File | Change |
| --- | --- |
| `shared/src/commonMain/kotlin/com/jellemax/detour/drive/FuelType.kt` | **New** — `enum class FuelType { PETROL, DIESEL }` |
| `shared/src/commonMain/kotlin/com/jellemax/detour/drive/Obd2Pids.kt` | diesel constants; `FUEL_DENSITY_G_PER_L`→`FUEL_DENSITY_PETROL_G_PER_L`; `PID_EQUIV_RATIO`; `parseCommandedEquivRatio`; `fuelRateFromMafLph` + `resolveFuelRate` gain `fuelType`/`lambda`/`calibrationPct` |
| `shared/src/commonTest/kotlin/com/jellemax/detour/drive/Obd2PidsTest.kt` | parser + two-fuel math + λ + calibration cases |
| `shared/src/commonMain/kotlin/com/jellemax/detour/data/Settings.kt` | `VehicleDevice.fuelType` + `.fuelCalibrationPct`; decode/encode; `setFuelType`/`setFuelCalibrationPct` |
| `shared/src/commonTest/kotlin/com/jellemax/detour/data/SettingsVehicleDeviceTest.kt` | round-trip, old-format, unknown fuelType, out-of-range calibration |
| `app/src/main/java/com/jellemax/detour/obd2/Obd2Connection.kt` | `connect`/`runConnectionLoop`/`pollLoop` gain `fuelType`+`calibrationPct`; 0144 probe; feed `resolveFuelRate` |
| `app/src/test/java/com/jellemax/detour/obd2/Obd2ConnectionTest.kt` | `parseCommandedEquivRatio` via `pollPid` if useful; 0144 probe wiring |
| `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt` | `reconcileObd2Connections` passes the resolved vehicle's `fuelType`/`fuelCalibrationPct` to `connect()` |
| `app/src/main/java/com/jellemax/detour/ui/Obd2PairingScreen.kt` | Petrol/Diesel toggle + calibration stepper per adapter-paired vehicle |
| `app/src/main/java/com/jellemax/detour/ui/TripDetailScreen.kt` | caveat row reworded |
| `app/build.gradle.kts` | `versionName` `1.95.0`→`1.96.0` (last task) |
| `docs/superpowers/specs/2026-09-02-obd2-fuel-accuracy-design.md` | Stage 2 Status → done; Stage 3 preconditions recorded |

---

## Task 1: `FuelType` enum + `Obd2Pids` constants, PID, and the lambda parser

**Files:**
- Create: `shared/src/commonMain/kotlin/com/jellemax/detour/drive/FuelType.kt`
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/drive/Obd2Pids.kt`
- Test: `shared/src/commonTest/kotlin/com/jellemax/detour/drive/Obd2PidsTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `com.jellemax.detour.drive.FuelType` — `enum class FuelType { PETROL, DIESEL }`
  - `Obd2Pids.PID_EQUIV_RATIO = "0144"`
  - `Obd2Pids.parseCommandedEquivRatio(dataBytes: List<Int>): Double?` — `(2.0 / 65536.0) * (256*A + B)`, null on `< 2` bytes
  - `Obd2Pids.STOICH_AFR_DIESEL = 14.5`, `Obd2Pids.FUEL_DENSITY_DIESEL_G_PER_L = 832.0` (private)
  - `FUEL_DENSITY_G_PER_L` renamed to `FUEL_DENSITY_PETROL_G_PER_L` (private)

- [ ] **Step 1: Write the failing tests**

Add to `Obd2PidsTest.kt`. After the MAF section (`:118`), add a lambda-parser group; the `parseCommandedEquivRatio` name mirrors the other `parseX` functions.

```kotlin
    // --- Commanded equivalence ratio / lambda (mode 01 PID 44): (2/65536)(256A+B)

    @Test
    fun equivRatioOfStoichiometricIsOne() {
        // λ = 1.0 ⇔ (256A+B) = 32768 = 0x8000 ⇔ A=0x80, B=0x00
        assertEquals(1.0, Obd2Pids.parseCommandedEquivRatio(listOf(0x80, 0x00))!!, 1e-9)
    }

    @Test
    fun equivRatioOfADieselCruiseIsWellAboveOne() {
        // A lean diesel cruise commands λ ≈ 2.0; PID 44 saturates near there.
        // (256*0xFF + 0xFF) * 2 / 65536 = 65535 * 2 / 65536 ≈ 1.99997
        assertEquals(2.0, Obd2Pids.parseCommandedEquivRatio(listOf(0xFF, 0xFF))!!, 1e-3)
    }

    @Test
    fun equivRatioMissingTheSecondByteIsNull() {
        assertNull(Obd2Pids.parseCommandedEquivRatio(listOf(0x80)))
    }

    @Test
    fun equivRatioWithNoBytesIsNull() {
        assertNull(Obd2Pids.parseCommandedEquivRatio(emptyList()))
    }

    @Test
    fun theEquivRatioPidIsRequestedAs0144() {
        assertEquals("0144", Obd2Pids.PID_EQUIV_RATIO)
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.jellemax.detour.drive.Obd2PidsTest"`
Expected: compile failure — `parseCommandedEquivRatio` / `PID_EQUIV_RATIO` unresolved.

- [ ] **Step 3: Create `FuelType.kt`**

```kotlin
package com.jellemax.detour.drive

/** A vehicle's fuel, for the MAF-derived fuel-rate estimate. A diesel is
 *  denser and stoichiometric at a slightly different ratio than petrol, and —
 *  the bigger effect — runs lean, which [Obd2Pids.fuelRateFromMafLph] accounts
 *  for via the commanded-lambda PID (0144) when the adapter reports it. The
 *  direct fuel-rate PID (015E) needs none of this; the ECU already knows. */
enum class FuelType { PETROL, DIESEL }
```

- [ ] **Step 4: Add the constants, the PID, and the parser to `Obd2Pids.kt`**

Rename the petrol density constant and add the diesel pair (`Obd2Pids.kt:41-42`):

```kotlin
    /** Stoichiometric air-fuel mass ratio and fuel density. Petrol vs diesel —
     *  [fuelRateFromMafLph] picks by [FuelType]. The MAF path is still an
     *  estimate; the commanded-lambda term is what makes a lean diesel land
     *  near its dash figure. */
    private const val STOICH_AFR_PETROL = 14.7
    private const val FUEL_DENSITY_PETROL_G_PER_L = 745.0
    private const val STOICH_AFR_DIESEL = 14.5
    private const val FUEL_DENSITY_DIESEL_G_PER_L = 832.0
```

Add the PID constant next to `PID_MAF` (`:36`):

```kotlin
    /** Commanded air-fuel equivalence ratio (lambda) — 1.0 at stoichiometric,
     *  >1 lean. A petrol engine in closed loop commands ~1.0; a diesel at
     *  cruise commands 2.0-2.5, and PID 0144 saturates at ≈2.0. Used only by
     *  the MAF fuel estimate to divide out the lean-burn air the stoichiometric
     *  assumption would otherwise count as fuel. */
    const val PID_EQUIV_RATIO = "0144"
```

Add the parser next to `parseMafGramsPerSec` (`:69-73`):

```kotlin
    /** Two bytes, `(2 / 65536) * (256*A + B)` — dimensionless lambda. */
    fun parseCommandedEquivRatio(dataBytes: List<Int>): Double? {
        val a = dataBytes.getOrNull(0) ?: return null
        val b = dataBytes.getOrNull(1) ?: return null
        return (2.0 / 65536.0) * (256.0 * a + b)
    }
```

- [ ] **Step 5: Run to verify they pass**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.jellemax.detour.drive.Obd2PidsTest"`
Expected: PASS (all, including the untouched petrol cases). Then `./gradlew :shared:compileCommonMainKotlinMetadata` — BUILD SUCCESSFUL (no `java.*` in the new file).

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/drive/FuelType.kt \
        shared/src/commonMain/kotlin/com/jellemax/detour/drive/Obd2Pids.kt \
        shared/src/commonTest/kotlin/com/jellemax/detour/drive/Obd2PidsTest.kt
git commit -m "$(cat <<'EOF'
feat(obd2): FuelType + diesel constants + commanded-lambda PID 0144 (#101)

Adds the FuelType enum, the diesel AFR/density constants, PID 0144 and its
decoder. Nothing consumes them yet — fuelRateFromMafLph still petrol-only.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AA6YEKTr59Gb2ZZQdwkhoo
EOF
)"
```

---

## Task 2: `fuelRateFromMafLph` + `resolveFuelRate` gain fuel type, lambda, calibration

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/drive/Obd2Pids.kt`
- Test: `shared/src/commonTest/kotlin/com/jellemax/detour/drive/Obd2PidsTest.kt`

**Interfaces:**
- Consumes: `FuelType`, the constants from Task 1.
- Produces:
  - `Obd2Pids.fuelRateFromMafLph(mafGramsPerSec: Double, fuelType: FuelType = FuelType.PETROL, lambda: Double = 1.0, calibrationPct: Int = 100): Double`
  - `Obd2Pids.resolveFuelRate(directLph, mafGramsPerSec, throttleClosed, rpm, speedKmh, fuelType: FuelType = FuelType.PETROL, lambda: Double = 1.0, calibrationPct: Int = 100): FuelReading?`

- [ ] **Step 1: Write the failing tests**

Add to `Obd2PidsTest.kt` after the existing MAF-fuel section (`:139`):

```kotlin
    @Test
    fun petrolAtDefaultsIsIdenticalToTheOldFormula() {
        // Explicit defaults == the 2-arg call the rest of the suite makes.
        assertEquals(
            Obd2Pids.fuelRateFromMafLph(10.0),
            Obd2Pids.fuelRateFromMafLph(10.0, FuelType.PETROL, lambda = 1.0, calibrationPct = 100),
            1e-12,
        )
    }

    @Test
    fun dieselWithACruiseLambdaLandsNearTheDashFigure() {
        // Field report: MAF-implied petrol estimate ≈ 14.1 units, dash ≈ 6.2.
        // Ratio (14.7·745)/(14.5·2.0·832) ≈ 0.454 → 14.1 · 0.454 ≈ 6.4.
        val petrol = Obd2Pids.fuelRateFromMafLph(40.0)
        val diesel = Obd2Pids.fuelRateFromMafLph(40.0, FuelType.DIESEL, lambda = 2.0, calibrationPct = 100)
        assertEquals(0.454, diesel / petrol, 1e-3)
    }

    @Test
    fun dieselWithoutLambdaCorrectsOnlyDensityAndAfr() {
        // No 0144 → λ defaults to 1.0. Ratio (14.7·745)/(14.5·832) ≈ 0.908.
        val petrol = Obd2Pids.fuelRateFromMafLph(40.0)
        val diesel = Obd2Pids.fuelRateFromMafLph(40.0, FuelType.DIESEL)
        assertEquals(0.908, diesel / petrol, 1e-3)
    }

    @Test
    fun calibrationScalesTheEstimateLinearly() {
        val base = Obd2Pids.fuelRateFromMafLph(20.0, FuelType.DIESEL, lambda = 2.0, calibrationPct = 100)
        assertEquals(base * 0.80, Obd2Pids.fuelRateFromMafLph(20.0, FuelType.DIESEL, lambda = 2.0, calibrationPct = 80), 1e-9)
        assertEquals(base * 1.20, Obd2Pids.fuelRateFromMafLph(20.0, FuelType.DIESEL, lambda = 2.0, calibrationPct = 120), 1e-9)
    }

    @Test
    fun resolveFuelRateThreadsFuelTypeLambdaAndCalibrationIntoTheMafPath() {
        val r = Obd2Pids.resolveFuelRate(
            directLph = null, mafGramsPerSec = 40.0, throttleClosed = false, rpm = 2000.0, speedKmh = 90.0,
            fuelType = FuelType.DIESEL, lambda = 2.0, calibrationPct = 110,
        )!!
        assertEquals(
            Obd2Pids.fuelRateFromMafLph(40.0, FuelType.DIESEL, lambda = 2.0, calibrationPct = 110),
            r.lph, 1e-9,
        )
        assertTrue(r.estimated)
    }

    @Test
    fun resolveFuelRateDirectPidIgnoresFuelTypeAndCalibration() {
        // The ECU's own 015E reading already accounts for everything.
        val r = Obd2Pids.resolveFuelRate(
            directLph = 6.2, mafGramsPerSec = 40.0, throttleClosed = false, rpm = 2000.0, speedKmh = 90.0,
            fuelType = FuelType.DIESEL, lambda = 2.0, calibrationPct = 50,
        )!!
        assertEquals(6.2, r.lph, 0.0)
        assertEquals(false, r.estimated)
    }

    @Test
    fun decelerationFuelCutStillZerosRegardlessOfFuelType() {
        val r = Obd2Pids.resolveFuelRate(
            directLph = null, mafGramsPerSec = 8.0, throttleClosed = true, rpm = 2500.0, speedKmh = 40.0,
            fuelType = FuelType.DIESEL, lambda = 2.0, calibrationPct = 120,
        )!!
        assertEquals(0.0, r.lph, 0.0)
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.jellemax.detour.drive.Obd2PidsTest"`
Expected: compile failure — the new `fuelRateFromMafLph` / `resolveFuelRate` params don't exist.

- [ ] **Step 3: Rewrite `fuelRateFromMafLph`** (`Obd2Pids.kt:75-79`)

```kotlin
    /** Fuel rate in L/h implied by intake air-mass flow.
     *
     *  `mass air / (AFR_stoich · λ)` is the fuel mass rate — dividing the air
     *  by the *actual* air-fuel ratio (stoichiometric scaled by the commanded
     *  equivalence ratio) rather than the stoichiometric one is what keeps a
     *  lean diesel from reading its excess air as fuel. Then / density / to
     *  the hour, and a per-vehicle [calibrationPct] trims what the model can't
     *  see (injector wear, MAF drift, fuel blend, the residual past PID 0144's
     *  ≈2.0 ceiling). Petrol at λ=1.0, calibration 100 is the old formula
     *  exactly. */
    fun fuelRateFromMafLph(
        mafGramsPerSec: Double,
        fuelType: FuelType = FuelType.PETROL,
        lambda: Double = 1.0,
        calibrationPct: Int = 100,
    ): Double {
        val afr = if (fuelType == FuelType.DIESEL) STOICH_AFR_DIESEL else STOICH_AFR_PETROL
        val density = if (fuelType == FuelType.DIESEL) FUEL_DENSITY_DIESEL_G_PER_L else FUEL_DENSITY_PETROL_G_PER_L
        return mafGramsPerSec / (afr * lambda) / density * 3600.0 * (calibrationPct / 100.0)
    }
```

- [ ] **Step 4: Thread the params through `resolveFuelRate`** (`:100-114`)

Add the three params (defaulted) and pass them to `fuelRateFromMafLph` in the non-cut branch:

```kotlin
    fun resolveFuelRate(
        directLph: Double?,
        mafGramsPerSec: Double?,
        throttleClosed: Boolean?,
        rpm: Double?,
        speedKmh: Double?,
        fuelType: FuelType = FuelType.PETROL,
        lambda: Double = 1.0,
        calibrationPct: Int = 100,
    ): FuelReading? {
        if (directLph != null) return FuelReading(directLph, estimated = false)
        if (mafGramsPerSec == null) return null
        val fuelCut = throttleClosed == true &&
            rpm != null && rpm > DFCO_MIN_RPM &&
            speedKmh != null && speedKmh > 0.0
        val lph = if (fuelCut) 0.0
            else fuelRateFromMafLph(mafGramsPerSec, fuelType, lambda, calibrationPct)
        return FuelReading(lph, estimated = true)
    }
```

Update the `resolveFuelRate` KDoc's second paragraph to mention that `lambda` defaults to 1.0 when the adapter doesn't report 0144, and `calibrationPct` is the per-vehicle trim.

- [ ] **Step 5: Run to verify they pass**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.jellemax.detour.drive.Obd2PidsTest"`
Expected: PASS — all new cases AND every pre-existing petrol/DFCO case (`:126`, `:133`, `:138`, `:144-203`).

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/drive/Obd2Pids.kt \
        shared/src/commonTest/kotlin/com/jellemax/detour/drive/Obd2PidsTest.kt
git commit -m "$(cat <<'EOF'
feat(obd2): fuel type, commanded lambda and calibration in the MAF estimate (#101, #100)

fuelRateFromMafLph / resolveFuelRate take fuelType, lambda and
calibrationPct, all defaulted so petrol at defaults is bit-identical to
the old formula. Diesel + a cruise lambda produces the ~0.45 ratio the
field report needs. Not wired to the adapter yet.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AA6YEKTr59Gb2ZZQdwkhoo
EOF
)"
```

---

## Task 3: `Settings.VehicleDevice` fuel type + calibration fields

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/Settings.kt`
- Test: `shared/src/commonTest/kotlin/com/jellemax/detour/data/SettingsVehicleDeviceTest.kt`

**Interfaces:**
- Consumes: `FuelType` from Task 1.
- Produces:
  - `Settings.VehicleDevice` gains `val fuelType: FuelType = FuelType.PETROL` and `val fuelCalibrationPct: Int = 100`.
  - `Settings.setFuelType(address: String, fuelType: FuelType)` and `Settings.setFuelCalibrationPct(address: String, pct: Int)` — same shape as `setObd2Address` (`Settings.kt:368-373`).
  - `Settings.FUEL_CALIBRATION_MIN = 50`, `Settings.FUEL_CALIBRATION_MAX = 150` (const, for the UI stepper bounds).

- [ ] **Step 1: Write the failing tests**

Add to `SettingsVehicleDeviceTest.kt`:

```kotlin
    @Test
    fun fuelTypeAndCalibrationRoundTrip() {
        val device = Settings.VehicleDevice(
            "AA:BB:CC:DD:EE:FF", "TDI", TravelMode.CAR,
            obd2Address = "11:22:33:44:55:66",
            fuelType = FuelType.DIESEL, fuelCalibrationPct = 108,
        )
        val decoded = Settings.decodeVehicleDevice(device.address, Settings.encodeVehicleDevice(device))
        assertEquals(device, decoded)
    }

    @Test
    fun petrolAndDefaultCalibrationAreNotWrittenToJson() {
        val device = Settings.VehicleDevice("AA:BB", "Car", TravelMode.CAR)
        val json = Settings.encodeVehicleDevice(device)
        assertNull(json["fuelType"])
        assertNull(json["fuelCalibrationPct"])
    }

    @Test
    fun anEntryWithNoFuelKeysDecodesAsPetrolAt100() {
        val old: JsonObject = buildJsonObject {
            put("mode", TravelMode.CAR.name)
            put("name", "Old Car")
            put("obd2Address", "11:22:33")
        }
        val decoded = Settings.decodeVehicleDevice("AA:BB", old)
        assertEquals(FuelType.PETROL, decoded.fuelType)
        assertEquals(100, decoded.fuelCalibrationPct)
    }

    @Test
    fun anUnknownFuelTypeStringDecodesAsPetrol() {
        val bad: JsonObject = buildJsonObject {
            put("mode", TravelMode.CAR.name); put("name", "Car"); put("fuelType", "LPG")
        }
        assertEquals(FuelType.PETROL, Settings.decodeVehicleDevice("AA:BB", bad).fuelType)
    }

    @Test
    fun anOutOfRangeCalibrationDecodesClampedToTheBounds() {
        fun decodedPct(raw: Int): Int {
            val j = buildJsonObject {
                put("mode", TravelMode.CAR.name); put("name", "Car"); put("fuelCalibrationPct", raw)
            }
            return Settings.decodeVehicleDevice("AA:BB", j).fuelCalibrationPct
        }
        assertEquals(Settings.FUEL_CALIBRATION_MIN, decodedPct(10))
        assertEquals(Settings.FUEL_CALIBRATION_MAX, decodedPct(500))
        assertEquals(100, decodedPct(100))
    }
```

Add `import com.jellemax.detour.drive.FuelType` to the test file.

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.jellemax.detour.data.SettingsVehicleDeviceTest"`
Expected: compile failure — `fuelType` / `fuelCalibrationPct` / `FUEL_CALIBRATION_MIN` unresolved.

- [ ] **Step 3: Add the fields and constants**

`Settings.kt` — add near the top of the object (by the other `const`s, `:37-42`):

```kotlin
    const val FUEL_CALIBRATION_MIN = 50
    const val FUEL_CALIBRATION_MAX = 150
```

Extend `VehicleDevice` (`:129-134`) — add `import com.jellemax.detour.drive.FuelType` at the top of the file:

```kotlin
    data class VehicleDevice(
        val address: String,
        val name: String,
        val mode: TravelMode,
        val obd2Address: String? = null,
        /** For the MAF fuel estimate — see [com.jellemax.detour.drive.Obd2Pids]. */
        val fuelType: FuelType = FuelType.PETROL,
        /** Per-vehicle multiplier on the MAF fuel estimate, percent, clamped
         *  [FUEL_CALIBRATION_MIN]..[FUEL_CALIBRATION_MAX]. Tuned against the
         *  car's own trip computer / a pump fill-up. */
        val fuelCalibrationPct: Int = 100,
    )
```

- [ ] **Step 4: Extend decode/encode**

`decodeVehicleDevice` (`:337-345`) — the `is JsonObject` branch gains two reads; keep the tolerant `optString` / `runCatching` style already there. `JsonObject.optInt(key, def)` exists (`Json.kt:55`, same `com.jellemax.detour.data` package as `Settings`, so already in scope):

```kotlin
    internal fun decodeVehicleDevice(address: String, v: JsonElement): VehicleDevice = when (v) {
        is JsonObject -> VehicleDevice(
            address,
            v.optString("name", address),
            TravelMode.of(v.optString("mode")),
            v.optString("obd2Address").takeIf { it.isNotBlank() },
            fuelType = runCatching { FuelType.valueOf(v.optString("fuelType")) }.getOrDefault(FuelType.PETROL),
            fuelCalibrationPct = v.optInt("fuelCalibrationPct", 100)
                .coerceIn(FUEL_CALIBRATION_MIN, FUEL_CALIBRATION_MAX),
        )
        else -> VehicleDevice(address, address, TravelMode.of(v.toString().trim('"')), null)
    }
```

Note: `optInt` returns `def` for absent / zero / negative / non-integer values (see `Capabilities.kt:54`); the test cases below use positive out-of-range ints (10, 500) so the `coerceIn` is what's under test, not `optInt`'s own defaulting.

`encodeVehicleDevice` (`:347-351`) — write only non-defaults:

```kotlin
    internal fun encodeVehicleDevice(d: VehicleDevice): JsonObject = buildJsonObject {
        put("mode", d.mode.name)
        put("name", d.name)
        d.obd2Address?.let { put("obd2Address", it) }
        if (d.fuelType != FuelType.PETROL) put("fuelType", d.fuelType.name)
        if (d.fuelCalibrationPct != 100) put("fuelCalibrationPct", d.fuelCalibrationPct)
    }
```

- [ ] **Step 5: Add the setters**

After `setObd2Address` (`:373`):

```kotlin
    /** Set [address]'s fuel type for the MAF fuel estimate. */
    fun setFuelType(address: String, fuelType: FuelType) {
        val current = _vehicleDevices.value[address] ?: return
        val next = _vehicleDevices.value.toMutableMap()
        next[address] = current.copy(fuelType = fuelType)
        writeVehicleDevices(next)
    }

    /** Set [address]'s fuel-estimate calibration, percent, clamped
     *  [FUEL_CALIBRATION_MIN]..[FUEL_CALIBRATION_MAX]. */
    fun setFuelCalibrationPct(address: String, pct: Int) {
        val current = _vehicleDevices.value[address] ?: return
        val next = _vehicleDevices.value.toMutableMap()
        next[address] = current.copy(
            fuelCalibrationPct = pct.coerceIn(FUEL_CALIBRATION_MIN, FUEL_CALIBRATION_MAX),
        )
        writeVehicleDevices(next)
    }
```

- [ ] **Step 6: Run to verify they pass**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.jellemax.detour.data.SettingsVehicleDeviceTest"`
Expected: PASS, including the four pre-existing `obd2Address` cases.
Then `./gradlew :shared:compileCommonMainKotlinMetadata` — BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/Settings.kt \
        shared/src/commonTest/kotlin/com/jellemax/detour/data/SettingsVehicleDeviceTest.kt
git commit -m "$(cat <<'EOF'
feat(settings): per-vehicle fuel type + calibration on VehicleDevice (#100, #101)

Additive: both keys written only when non-default, absent/unknown/out-of-
range decodes to PETROL / 100. Calibration clamped 50..150 on read and
write. Old entries and old builds are unaffected.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AA6YEKTr59Gb2ZZQdwkhoo
EOF
)"
```

---

## Task 4: thread the vehicle's fuel type + calibration into `Obd2Connection` (diesel constants go live)

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/obd2/Obd2Connection.kt`
- Modify: `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt`
- Modify: `app/src/main/java/com/jellemax/detour/ui/Obd2PairingScreen.kt` (its three `Obd2Connection.connect(...)` call sites)

**Interfaces:**
- Consumes: `FuelType` (Task 1), `resolveFuelRate` params (Task 2), `Settings.VehicleDevice.fuelType` / `.fuelCalibrationPct` (Task 3).
- Produces:
  - `Obd2Connection.connect(context: Context, address: String, fuelType: FuelType, calibrationPct: Int)` — new required params.
  - `pollLoop` passes them to `resolveFuelRate` (lambda still `1.0` — Task 5 adds 0144).

**Design note (record in the report):** the config is a `connect()` parameter, not a `Settings` read inside `Obd2Connection` — keeps `Obd2Connection` free of a `shared`/`Settings` dependency. Consequence: a fuel-type / calibration change while an adapter is connected applies on the **next** connect. The pairing-screen controls (Task 6) call `disconnect()` + `connect()` to apply immediately, matching the existing "Use \<device\>" handler; a change mid-trip from anywhere else waits for the next reconnect. Documented limit, not a bug.

- [ ] **Step 1: Widen `connect` and thread down**

`Obd2Connection.kt` — `connect` (`:163-167`):

```kotlin
    @Synchronized
    fun connect(context: Context, address: String, fuelType: FuelType, calibrationPct: Int) {
        if (job?.isActive == true) return
        _linkedAddress.value = address
        job = scope.launch { runConnectionLoop(context, address, fuelType, calibrationPct) }
    }
```

`runConnectionLoop` (`:182`) — add the two params to the signature and pass them to `pollLoop`:

```kotlin
    private suspend fun runConnectionLoop(
        context: Context, address: String, fuelType: FuelType, calibrationPct: Int,
    ) {
        ...
                pollLoop(input, output, fuelType, calibrationPct)   // at :212
        ...
    }
```

`pollLoop` (`:335`) — add the two params; in the fuel-resolve line (`:404-405` after Stage 1) pass them through:

```kotlin
    private suspend fun pollLoop(
        input: InputStream, output: OutputStream, fuelType: FuelType, calibrationPct: Int,
    ) {
        ...
            val fuel = Obd2Pids.resolveFuelRate(
                directLph, mafGps, throttleClosed, rpm, speed,
                fuelType = fuelType, calibrationPct = calibrationPct,
            )?.takeIf { it.lph <= MAX_PLAUSIBLE_FUEL_LPH }
```

Add `import com.jellemax.detour.drive.FuelType` to `Obd2Connection.kt`.

- [ ] **Step 2: Update the `TripTrackingService` call site**

`reconcileObd2Connections` (`TripTrackingService.kt` — find with `grep -n 'Obd2Connection.connect' app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt`). It currently calls `Obd2Connection.connect(applicationContext, target)` where `target` is `desiredObd2Address()`. Resolve the vehicle whose `obd2Address == target` from `Settings.vehicleDevices.value` and pass its `fuelType` / `fuelCalibrationPct`; fall back to `FuelType.PETROL` / `100` if no vehicle matches (shouldn't happen, but `connect` needs the args):

```kotlin
        if (target != null && Obd2Connection.linkedAddress.value == null) {
            val v = Settings.vehicleDevices.value.values.firstOrNull { it.obd2Address == target }
            Obd2Connection.connect(
                applicationContext, target,
                fuelType = v?.fuelType ?: FuelType.PETROL,
                calibrationPct = v?.fuelCalibrationPct ?: 100,
            )
        }
```

Add `import com.jellemax.detour.drive.FuelType` to `TripTrackingService.kt`.

- [ ] **Step 3: Update the three `Obd2PairingScreen` call sites**

`Obd2PairingScreen.kt` calls `Obd2Connection.connect(...)` in three places (`grep -n 'Obd2Connection.connect' app/src/main/java/com/jellemax/detour/ui/Obd2PairingScreen.kt`): the `DisposableEffect` (`:73`), the "Retry now" button (`:168`), and the "Use \<name\>" button (`:203`). Each has a `vehicle` / `mapping` in scope. Pass the relevant `VehicleDevice`'s `fuelType` / `fuelCalibrationPct`:

- `DisposableEffect` — `readoutAddress` is `mapping.values.firstNotNullOfOrNull { it.obd2Address }`; get that same vehicle: `val v = mapping.values.firstOrNull { it.obd2Address == readoutAddress }` and pass `v?.fuelType ?: FuelType.PETROL`, `v?.fuelCalibrationPct ?: 100`.
- "Retry now" — `retryAddress`; same lookup against `mapping`.
- "Use \<name\>" — inside `mapping.values...forEach { vehicle -> ... }`, the newly-assigned `device.address` is the adapter and `vehicle` is the owner: pass `vehicle.fuelType`, `vehicle.fuelCalibrationPct`. Note the `setObd2Address` runs first so `vehicle` here is the pre-update copy — its `fuelType`/`calibrationPct` are unaffected by that call, so this is correct.

Add `import com.jellemax.detour.drive.FuelType` to `Obd2PairingScreen.kt`.

- [ ] **Step 4: Build + test**

Run: `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest :app:assembleDebug`
Expected: green. `Obd2ConnectionTest` still passes (it calls `pollPid` / `probePidCycle` / `readUntilPrompt` directly, not `connect` / `pollLoop`, so no signature break there). No test asserts the new threading yet — it's covered by `Obd2PidsTest` (the math) + the Task 5 λ tests + on-road verification.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/obd2/Obd2Connection.kt \
        app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt \
        app/src/main/java/com/jellemax/detour/ui/Obd2PairingScreen.kt
git commit -m "$(cat <<'EOF'
feat(obd2): pass each vehicle's fuel type + calibration to the poll loop (#101, #100)

connect() carries fuelType + calibrationPct down to resolveFuelRate, so a
diesel now uses diesel AFR/density (a ~10% correction on its own).
Obd2Connection stays free of a Settings dependency; a config change while
connected applies on the next connect. Lambda still 1.0 — next commit.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AA6YEKTr59Gb2ZZQdwkhoo
EOF
)"
```

---

## Task 5: probe PID 0144 in `pollLoop` and feed lambda into `resolveFuelRate`

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/obd2/Obd2Connection.kt`
- Test: `app/src/test/java/com/jellemax/detour/obd2/Obd2ConnectionTest.kt`

**Interfaces:**
- Consumes: `probePidCycle` / `PidProbe` (Stage 1), `Obd2Pids.PID_EQUIV_RATIO` / `parseCommandedEquivRatio` (Task 1), `resolveFuelRate`'s `lambda` param (Task 2).
- Produces: nothing new outside `pollLoop`.

- [ ] **Step 1: Write the failing test**

The pure-logic seam here is `parseCommandedEquivRatio` (already tested in `shared`) and `probePidCycle` (tested in Stage 1). `pollLoop` itself has no device-free test (pre-existing). Add one `pollPid`-level test to `Obd2ConnectionTest.kt` proving a 0144 frame parses through the same `41 44` header path as the others:

```kotlin
    @Test
    fun pollPidParsesACommandedEquivRatioFrame() {
        // "41 44 80 00" → λ 1.0
        val input = streamOf("41 44 80 00\r\r>")
        val result = Obd2Connection.pollPid(input, ByteArrayOutputStream(), Obd2Pids.PID_EQUIV_RATIO)
        assertEquals(listOf(0x80, 0x00), result.bytes)
        assertEquals(1.0, Obd2Pids.parseCommandedEquivRatio(result.bytes!!)!!, 1e-9)
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.jellemax.detour.obd2.Obd2ConnectionTest"`
Expected: compile failure — `Obd2Pids.PID_EQUIV_RATIO` not imported / used yet is fine, but the test won't compile without it resolving. (It resolves from `shared` — this really just fails until the assertion is added; if it compiles and passes immediately, that's acceptable — it's a characterization test for the shared header path, and the pollLoop wiring below is the actual change.)

- [ ] **Step 3: Add the 0144 probe to `pollLoop`**

Alongside `fuelProbe` (declared `~:360` after Stage 1), add:

```kotlin
        // Commanded lambda (0144): probe once, then poll every cycle like MAF —
        // it varies with load. No fallback PID; an adapter that doesn't answer
        // it leaves lambda at 1.0 (fuelRateFromMafLph's default) and the diesel
        // estimate falls back to a density/AFR-only correction.
        var lambdaProbe: PidProbe = PidProbe.Probing()
```

In the cycle body, after the fuel probe block and before `resolveFuelRate` (so λ is ready), poll it:

```kotlin
            val lambdaCycle = probePidCycle(
                input, output, lambdaProbe,
                primary = Obd2Pids.PID_EQUIV_RATIO, fallback = null,
                maxCycles = PID_PROBE_MAX_CYCLES,
            )
            lambdaProbe = lambdaCycle.state
            val lambda = lambdaCycle.result?.bytes
                ?.let { Obd2Pids.parseCommandedEquivRatio(it) }
                ?.takeIf { it > 0.0 }
                ?: 1.0
```

Feed it into the fuel resolve:

```kotlin
            val fuel = Obd2Pids.resolveFuelRate(
                directLph, mafGps, throttleClosed, rpm, speed,
                fuelType = fuelType, lambda = lambda, calibrationPct = calibrationPct,
            )?.takeIf { it.lph <= MAX_PLAUSIBLE_FUEL_LPH }
```

**Poll-order note:** 0144 is polled after fuel and before speed — speed must stay the last poll before the telemetry publish (the `parseSpeed` comment). Put the lambda poll immediately after the `fuelCycle` block.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.jellemax.detour.obd2.Obd2ConnectionTest"` then the full `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest :app:assembleDebug :app:assembleRelease`.
Expected: green, R8 clean.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/obd2/Obd2Connection.kt \
        app/src/test/java/com/jellemax/detour/obd2/Obd2ConnectionTest.kt
git commit -m "$(cat <<'EOF'
feat(obd2): poll commanded lambda (0144) and fold it into the fuel estimate (#101)

pollLoop probes 0144 through the Stage-1 probePidCycle primitive (no
fallback) and passes the commanded equivalence ratio to resolveFuelRate.
A 0144-capable diesel now reads within a few percent of its dash figure;
one that doesn't answer it stays on the density/AFR-only correction.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AA6YEKTr59Gb2ZZQdwkhoo
EOF
)"
```

---

## Task 6: Petrol/Diesel toggle + calibration stepper on the OBD2 pairing screen

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/Obd2PairingScreen.kt`

**Interfaces:**
- Consumes: `Settings.setFuelType` / `setFuelCalibrationPct` / `FUEL_CALIBRATION_MIN` / `MAX` (Task 3), `FuelType` (Task 1).
- Produces: nothing.

**Compose-state note:** the only effect in this file is the `DisposableEffect(readoutAddress)` (`:71`). These controls add plain `Settings.setX` calls in button `onClick`s + a recompose off the already-collected `mapping` StateFlow — **no new effect, no key-list change**. If the setter should apply immediately (it should, per Task 4's design note), the `onClick` also calls `Obd2Connection.disconnect()` + `Obd2Connection.connect(...)` exactly like the existing "Use \<name\>" handler (`:202-203`) — do not add a `LaunchedEffect` to react to the setting. Follow `detour-compose-state-hazards` if any effect turns out to be needed; it should not be.

- [ ] **Step 1: Add the controls to the adapter-paired block**

In the `pairedName != null` branch (`:207-223`, the `Row` with `"Adapter: $pairedName"` and the "Forget" button), add below that `Row`, still inside the `Column` for that `vehicle`. Use the same `SingleChoiceSegmentedButtonRow` + `SegmentedButton` pattern as the Theme picker (`SettingsScreen.kt:286-300`):

```kotlin
                    // Fuel type + calibration only matter for the MAF estimate,
                    // and only once an adapter is paired.
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        FuelType.entries.forEachIndexed { index, ft ->
                            SegmentedButton(
                                selected = vehicle.fuelType == ft,
                                onClick = {
                                    Settings.setFuelType(vehicle.address, ft)
                                    Obd2Connection.disconnect()
                                    Obd2Connection.connect(
                                        context.applicationContext, vehicle.obd2Address!!,
                                        fuelType = ft, calibrationPct = vehicle.fuelCalibrationPct,
                                    )
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, FuelType.entries.size),
                                label = { Text(ft.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Calibration: ${vehicle.fuelCalibrationPct}%",
                            style = MaterialTheme.typography.bodyMedium)
                        Row {
                            IconButton(
                                enabled = vehicle.fuelCalibrationPct > Settings.FUEL_CALIBRATION_MIN,
                                onClick = { adjustCalibration(vehicle, -1, context) },
                            ) { Text("−") }
                            IconButton(
                                enabled = vehicle.fuelCalibrationPct < Settings.FUEL_CALIBRATION_MAX,
                                onClick = { adjustCalibration(vehicle, +1, context) },
                            ) { Text("+") }
                        }
                    }
                    Text(
                        "If the trip fuel figure reads high or low against your car's own display, nudge this.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
```

And a file-private helper next to `obd2FailureText` (`:238`):

```kotlin
private fun adjustCalibration(vehicle: Settings.VehicleDevice, delta: Int, context: android.content.Context) {
    val next = (vehicle.fuelCalibrationPct + delta)
        .coerceIn(Settings.FUEL_CALIBRATION_MIN, Settings.FUEL_CALIBRATION_MAX)
    if (next == vehicle.fuelCalibrationPct) return
    Settings.setFuelCalibrationPct(vehicle.address, next)
    vehicle.obd2Address?.let { addr ->
        Obd2Connection.disconnect()
        Obd2Connection.connect(context.applicationContext, addr, vehicle.fuelType, next)
    }
}
```

Add imports: `androidx.compose.material3.SingleChoiceSegmentedButtonRow`, `androidx.compose.material3.SegmentedButton`, `androidx.compose.material3.SegmentedButtonDefaults`, `androidx.compose.material3.IconButton`, `com.jellemax.detour.drive.FuelType`. `Arrangement`, `Alignment`, `Row`, `Column`, `Modifier`, `MaterialTheme`, `Text`, `Button` are already imported.

- [ ] **Step 2: Build + eyeball**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. If a physical device or emulator is available, open Settings → OBD2 adapter, pair an adapter to a vehicle, confirm the Petrol/Diesel chips and the −/+ stepper appear and persist across a screen re-entry. **No device this session ⇒ flag Tier-1 unverified in the report; the build + the `Settings` round-trip test (Task 3) are the automated coverage.**

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/Obd2PairingScreen.kt
git commit -m "$(cat <<'EOF'
feat(obd2): fuel type + calibration controls on the pairing screen (#100, #101)

Petrol/Diesel chips and a 50-150% calibration stepper per adapter-paired
vehicle. Both reconnect the adapter so the change applies immediately,
matching the existing "Use <device>" handler.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AA6YEKTr59Gb2ZZQdwkhoo
EOF
)"
```

---

## Task 7: reword the trip-detail fuel caveat

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/TripDetailScreen.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing.

**Deviation from the spec's example wording — record in the report.** The spec (`…-design.md`, "Trip-detail caveat") shows *"Estimated for diesel, calibrated to 97%."* That needs the trip's fuel type at detail time, but `TripStore` is deliberately unchanged (spec's own "out of scope"), and a trip carries no vehicle-config reference to look it up from. So the reword only **removes the now-false "assumes petrol" clause** and keeps a generic, always-true MAF-estimate caveat.

- [ ] **Step 1: Reword** (`TripDetailScreen.kt:604-613`)

```kotlin
                    if (replaySample == null && trip.drivingStats.fuelEstimated &&
                        tripFuelEconomyLper100Km(trip) != null
                    ) {
                        Text(
                            "Fuel is a MAF-based estimate — this vehicle has no direct " +
                                "fuel-rate PID, so it tracks engine load, not the injectors, " +
                                "and can drift. Tune it per vehicle under the OBD2 adapter settings.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
```

- [ ] **Step 2: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/TripDetailScreen.kt
git commit -m "$(cat <<'EOF'
feat(trip): drop the "assumes petrol" clause from the fuel-estimate caveat (#101)

The MAF estimate is now per-vehicle petrol/diesel + calibratable, and the
trip doesn't store which — so the caveat states only what's always true
and points at the per-vehicle knob.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AA6YEKTr59Gb2ZZQdwkhoo
EOF
)"
```

---

## Task 8: version bump + docs

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `docs/superpowers/specs/2026-09-02-obd2-fuel-accuracy-design.md`

- [ ] **Step 1: Bump `versionName`**

`app/build.gradle.kts` — `versionName = "1.95.0"` → `versionName = "1.96.0"`. Nothing else in that file.

- [ ] **Step 2: Update the design doc**

Stage 2 `**State**` line → `**done** <date>`, with the post-implementation commit range and the two deviations recorded (TripDetail wording; the `optInt`-vs-`optString` decode choice from Task 3). Then run Stage 3's preconditions and record them in Stage 3's `**State**` line:

```sh
T=app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt
grep -c 'cappedFixDtSec' $T                                    # expect >= 2
grep -c 'fun parseCommandedEquivRatio' shared/src/commonMain/kotlin/com/jellemax/detour/drive/Obd2Pids.kt   # expect 1
grep -c 'HardEventDetector.onSpeedFix\|onHeadingFix' $T        # expect >= 1
grep -c 'freshObdTelemetry()' $T                               # expect >= 2
```

- [ ] **Step 3: Full verification**

Run: `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest :shared:compileCommonMainKotlinMetadata :app:assembleDebug :app:assembleRelease`
Expected: all green, R8 clean.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts docs/superpowers/specs/2026-09-02-obd2-fuel-accuracy-design.md
git commit -m "$(cat <<'EOF'
chore: versionName 1.95.0 -> 1.96.0 for the OBD2 fuel-type feature (#100, #101)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AA6YEKTr59Gb2ZZQdwkhoo
EOF
)"
```

- [ ] **Step 5: PR**

```bash
git push -u origin feat/obd2-fuel-type-calibration
gh pr create --base main --head feat/obd2-fuel-type-calibration \
  --title "feat(obd2): per-vehicle fuel type + calibration + commanded lambda (#100, #101)" \
  --body-file <(cat <<'EOF'
Closes #100, #101.

The MAF fuel estimate assumed petrol burning stoichiometric — a VW TDI read 14.1 l/100km against its own 6.2 dash figure, a 2.3× over-read that is a diesel's cruise lambda. This adds a per-vehicle fuel type, a calibration multiplier, and folds in commanded lambda (PID 0144) so the lean-burn air is divided out rather than counted as fuel.

## Before / after

`fuelRateFromMafLph`, same 40 g/s MAF:

| config | ratio vs old petrol formula | 14.1 → |
|---|---|---|
| petrol, defaults | `1.000` (bit-identical) | 14.1 |
| diesel, no 0144 (λ=1.0) | `0.908` | 12.8 |
| diesel, 0144 at cruise (λ≈2.0) | `0.454` | **6.4** (dash: 6.2) |

Petrol vehicles at default calibration with no 0144 are unchanged to the last bit — verified by the pre-existing `Obd2PidsTest` assertions passing untouched.

## What changed

1. `FuelType` enum + diesel AFR/density constants + PID 0144 decoder in `shared/Obd2Pids`.
2. `fuelRateFromMafLph` / `resolveFuelRate` take `fuelType` / `lambda` / `calibrationPct`, all defaulted.
3. `Settings.VehicleDevice` gains `fuelType` + `fuelCalibrationPct` — additive JSON (absent ⇒ `PETROL` / `100`), calibration clamped 50–150 on read and write.
4. `Obd2Connection.connect()` carries the config to `pollLoop`; no `Settings` dependency added.
5. `pollLoop` probes 0144 via the Stage-1 `probePidCycle` primitive (no fallback PID) and feeds λ in.
6. Petrol/Diesel chips + a calibration stepper per adapter-paired vehicle on the OBD2 pairing screen.
7. Trip-detail caveat drops "assumes petrol".

## Known limits

- **On-road unverified** — no ELM327 this session. The math and the `Settings` round-trip are unit-covered on JVM + Kotlin/Native; the live 0144 probe on a real TDI and the fuel A/B against the dash are the user's to run.
- **PID 0144 saturates at λ≈2.0.** A diesel cruising leaner than that (very light load) still slightly over-reads; the calibration knob covers the residual, at the cost of being tuned for one load band.
- **Config change while connected applies on the next connect.** The pairing-screen controls force a reconnect so it's immediate there; a change from elsewhere mid-trip waits.
- **Fuel type is not stored on the trip.** The trip-detail caveat is therefore generic, not "estimated for diesel"; recomputing historical trips is out of scope.
- **A diesel with no 0144** gets only the ~10% density/AFR correction and must lean on calibration (`≈48%` for the field-report vehicle), which drifts with load. O2-sensor lambda PIDs (0134–013B) as a fallback are a later refinement.
- No `:app:lintDebug` regression — its 3 errors are pre-existing in `notif/PlaceNotifications.kt`, untouched here.

## Follow-ups

- #98 — derive the fuel-integrator and hard-event Δt from the OBD telemetry clock, not the GPS fix clock. Stage 3; needs a GPS-replay A/B.

Design doc: `docs/superpowers/specs/2026-09-02-obd2-fuel-accuracy-design.md`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)
```

Run `.claude/skills/detour-pr-writing/scripts/check-pr-body.sh` on the body first (write it to a temp file). Confirm `gh pr view <N> --json closingIssuesReferences` shows only #100 and #101.

---

## Self-Review

**Spec coverage (Stage 2 section + top-of-doc fuel math / data format / settings UI / caveat):**
- `FuelType` enum in `shared/drive/` → Task 1.
- `STOICH_AFR_DIESEL` / `FUEL_DENSITY_DIESEL_G_PER_L` + petrol constant rename → Task 1.
- `parseCommandedEquivRatio` + `PID_EQUIV_RATIO` → Task 1.
- `fuelRateFromMafLph(maf, fuelType, lambda, calibrationPct)` defaulted → Task 2.
- `resolveFuelRate` threads the three → Task 2.
- `pollLoop` probes 0144 via `probePidCycle` (fallback null), feeds λ → Task 5.
- config into `connect()` not `Settings`-read → Task 4 (design note records the consequence).
- **No `ObdTelemetry` field** → Tasks 4/5 keep λ inside `pollLoop`.
- `Settings.VehicleDevice` two fields, tolerant decode, guarded encode, two setters, 50–150 clamp → Task 3.
- `Obd2PairingScreen` segmented control + stepper → Task 6.
- `TripDetailScreen` caveat reword → Task 7 (deviation from example wording documented).
- `TripStore` **no change** → not touched by any task; stated in Task 7's note.
- Out of scope (O2 lambda PIDs / historical recompute / Δt clock / global calibration) → none touched; Δt is Stage 3, named in the PR follow-ups.
- Version `1.95.0` → `1.96.0` → Task 8, Global Constraints.
- Stage 3 preconditions recorded → Task 8 Step 2.

**Placeholder scan:** none. `<date>` / `<N>` / commit ranges in Task 8 are runtime values. The `optInt`-vs-`optString` fork in Task 3 Step 4 is a real "check then pick one" with both concrete branches given and a report note required — not a TODO.

**Type consistency:** `FuelType` / `FuelType.PETROL` / `FuelType.DIESEL` used identically Tasks 1→6. `fuelRateFromMafLph(mafGramsPerSec, fuelType, lambda, calibrationPct)` and `resolveFuelRate(..., fuelType, lambda, calibrationPct)` signatures match between Task 2 (definition) and Tasks 4/5 (calls). `Settings.setFuelType(address, FuelType)` / `setFuelCalibrationPct(address, Int)` / `FUEL_CALIBRATION_MIN` / `FUEL_CALIBRATION_MAX` consistent Tasks 3→6. `Obd2Connection.connect(context, address, fuelType, calibrationPct)` consistent Task 4 (def) → Task 4 callers → Task 6 (reconnect calls). `PidProbe.Probing()` / `probePidCycle(input, output, state, primary, fallback, maxCycles)` reused from Stage 1 unchanged in Task 5.

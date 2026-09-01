# OBD2 Connectivity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect to a paired ELM327 Bluetooth OBD2 adapter per vehicle, feed its speed reading
into #61's `HardEventDetector` in place of GPS Δv/Δt when connected, and expose throttle/RPM live
on a new pairing screen — all with automatic fallback to GPS when no adapter is connected.

**Architecture:** A pure SAE J1979 PID-decode module in `shared/` (byte math, no I/O), a new
app-only `Obd2Connection` singleton that owns the Bluetooth Classic (SPP) socket, handshake, and
polling loop, wired into `TripTrackingService`'s existing always-on Bluetooth watcher (the same
`btReceiver` that already detects a mapped vehicle's connect/disconnect) and into its
`effectiveSpeedMps`/`speedIsReal` priority chain one slot above the existing board-telemetry
source. A new Settings sub-page lets the rider pair an adapter per vehicle and see it work live.

**Tech Stack:** Kotlin Multiplatform (`shared/commonMain`), Android (`app/`), Jetpack Compose,
`android.bluetooth.BluetoothSocket` (Classic/SPP), `kotlin.test` in `commonTest`.

**Spec:** `docs/superpowers/specs/2026-08-23-obd2-connectivity-design.md`

## Context: branches from #61, not from `main`

This plan's worktree branches from `worktree-driving-behavior-stats` (branch, tip `12a4fb3` as of
this writing) — #61's own implementation branch, not yet merged to `main`. Every anchor cited
below (`freshBoardTelemetry`, `effectiveSpeedMps`, `speedIsReal`, `TripStats`) is #61's own code,
which only exists on that branch. Branching from `main` instead would mean writing this plan's
wiring tasks against code that doesn't exist yet — the exact class of drift #61's own Task 4 hit
when it discovered `SpeedLimitTracker` had changed since the spec was authored. If `main` gains
conflicting changes before both branches land, resolve at merge time, not by re-deriving this
plan.

## Global Constraints

- Android only (per the issue, no iOS: MFi/iAP2 certification is a separate, much larger lift).
- Generic SAE J1979 PIDs only — no manufacturer-specific extensions, no lateral-g/cornering PID
  (none standardized), no brake-pedal state (non-standard across makes).
- Throttle % and RPM are decoded and exposed live only — no new `DrivingStats`/`Trip` field this
  pass, no new persisted history.
- PID-byte decoding (raw bytes → km/h/%/rpm) lives in `shared/`, clock-free, no I/O. The Bluetooth
  socket, AT handshake, and polling loop stay app-only — `commonMain` has no Bluetooth socket API.
- OBD2 connects via `TripTrackingService`'s existing always-on `btReceiver`
  (`ACTION_ACL_CONNECTED`/`_DISCONNECTED`) — no second background service, no new persistent
  notification.
- No physical ELM327 adapter is confirmed available this session. `Obd2Connection` and the
  `TripTrackingService` wiring tasks get **no unit test** — same carve-out #61's own plan used for
  `TripTrackingService` (no Robolectric, no instrumented source set in this repo) — and their
  on-device verification is the user's own follow-up step, not attempted here.
- Touching `shared/` requires, before each shared-touching commit is considered done:
  `./gradlew :shared:compileCommonMainKotlinMetadata` and `./gradlew :shared:testDebugUnitTest`.
- Full gate before the plan is considered finished:
  `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest` then
  `./gradlew :app:assembleDebug :app:assembleRelease`.
- `versionName` in `app/build.gradle.kts` bumps **minor** (`1.77.0` → `1.78.0`, since this branches
  from #61's tip which already bumped to 1.77.0) — new feature, backward compatible
  (`VehicleDevice.obd2Address` defaults to `null`, GPS fallback unchanged when no adapter is
  paired) — as the last step of the last task, per `CONTRIBUTING.md`'s Versioning section.

---

## File Structure

New files:
- `shared/src/commonMain/kotlin/com/jellemax/detour/drive/Obd2Pids.kt` — SAE J1979 PID byte decode.
- `shared/src/commonTest/kotlin/com/jellemax/detour/drive/Obd2PidsTest.kt`
- `shared/src/commonTest/kotlin/com/jellemax/detour/data/SettingsVehicleDeviceTest.kt`
- `app/src/main/java/com/jellemax/detour/obd2/Obd2Connection.kt` — `ObdTelemetry`,
  `Obd2ConnectionState`, the SPP socket/handshake/polling singleton.
- `app/src/main/java/com/jellemax/detour/ui/Obd2PairingScreen.kt` — new Settings sub-page.

Modified files:
- `shared/src/commonMain/kotlin/com/jellemax/detour/data/Settings.kt` — `VehicleDevice.obd2Address`,
  encode/decode extraction, `setObd2Address`.
- `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt` — `btReceiver` ACL
  handlers open/close the OBD2 socket; `effectiveSpeedMps`/`speedIsReal` gain `freshObdTelemetry()`.
- `app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt` — new `SettingsPage.OBD2` spoke.
- `app/build.gradle.kts` — version bump.

---

### Task 1: `VehicleDevice.obd2Address` field + `Settings` encode/decode extraction

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/Settings.kt`
- Test: `shared/src/commonTest/kotlin/com/jellemax/detour/data/SettingsVehicleDeviceTest.kt`

**Interfaces:**
- Produces: `data class VehicleDevice(address: String, name: String, mode: TravelMode, obd2Address: String? = null)`.
  `internal fun encodeVehicleDevice(d: VehicleDevice): JsonObject`,
  `internal fun decodeVehicleDevice(address: String, v: JsonElement): VehicleDevice` (promoted from
  the inline logic in `readVehicleDevices`/`writeVehicleDevices`, same extraction shape #61's Task 1
  used for `TripStore.decodeTrip`). `fun Settings.setObd2Address(address: String, obd2Address: String?)`.
  Consumed by Task 6's pairing screen.

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/jellemax/detour/data/SettingsVehicleDeviceTest.kt`:

```kotlin
package com.jellemax.detour.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Characterises the pure JSON encode/decode [Settings] uses for
 *  [Settings.VehicleDevice] — extracted so the OBD2 field's round trip and
 *  backward-compat default are testable without a real [Prefs] backend. */
class SettingsVehicleDeviceTest {

    @Test
    fun obd2AddressRoundTripsThroughEncodeAndDecode() {
        val device = Settings.VehicleDevice(
            address = "AA:BB:CC:DD:EE:FF", name = "My Car", mode = TravelMode.CAR,
            obd2Address = "11:22:33:44:55:66",
        )
        val decoded = Settings.decodeVehicleDevice(device.address, Settings.encodeVehicleDevice(device))
        assertEquals(device, decoded)
    }

    @Test
    fun aDeviceWithNoObd2AdapterDecodesWithNullObd2Address() {
        val device = Settings.VehicleDevice("AA:BB:CC:DD:EE:FF", "My Car", TravelMode.CAR)
        val decoded = Settings.decodeVehicleDevice(device.address, Settings.encodeVehicleDevice(device))
        assertNull(decoded.obd2Address)
    }

    @Test
    fun anEntrySavedBeforeObd2ExistedDecodesWithNullObd2Address() {
        // New-format entry from before this field existed: {mode, name}, no obd2Address key.
        val old: JsonObject = buildJsonObject {
            put("mode", TravelMode.MOTO.name)
            put("name", "My Bike")
        }
        val decoded = Settings.decodeVehicleDevice("11:22:33", old)
        assertEquals(Settings.VehicleDevice("11:22:33", "My Bike", TravelMode.MOTO, null), decoded)
    }

    @Test
    fun theOldestFormatWithNoNameOrObd2AddressDecodesUsingTheAddressAsName() {
        // Oldest format (v1.24): {address: "MODE"} as a bare JSON string, not an object.
        val decoded = Settings.decodeVehicleDevice("11:22:33", JsonPrimitive("CAR"))
        assertEquals(Settings.VehicleDevice("11:22:33", "11:22:33", TravelMode.CAR, null), decoded)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.jellemax.detour.data.SettingsVehicleDeviceTest"`
Expected: FAIL — `Settings.encodeVehicleDevice`/`decodeVehicleDevice` don't exist yet, and
`VehicleDevice` has no `obd2Address` parameter.

- [ ] **Step 3: Add the field, extract encode/decode, add the setter**

Modify `shared/src/commonMain/kotlin/com/jellemax/detour/data/Settings.kt`. The original
`readVehicleDevices` referred to the type as `kotlinx.serialization.json.JsonObject` fully
qualified (no short-name import) — add these two imports alongside the existing
`kotlinx.serialization.json.*` imports so the extracted functions below can use the short names:

```kotlin
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
```

Change the
`VehicleDevice` data class (line 90):

```kotlin
    /** A Bluetooth device the user assigned to a vehicle. [name] is kept so the
     *  Settings list can show it even when the device isn't currently reachable.
     *  [obd2Address] is a *separate* paired device — the OBD2 dongle plugged into
     *  the port, independent of [address] (the car's stereo/headunit, or a moto
     *  intercom) that this same vehicle is auto-detected by; a vehicle can have
     *  one, the other, both, or neither. */
    data class VehicleDevice(
        val address: String,
        val name: String,
        val mode: TravelMode,
        val obd2Address: String? = null,
    )
```

Replace `readVehicleDevices` (the function containing the inline `when (v) { ... }` block) with:

```kotlin
    private fun readVehicleDevices(): Map<String, VehicleDevice> {
        val raw = prefs.string("vehicle_devices").takeIf { it.isNotEmpty() } ?: return emptyMap()
        return runCatching {
            jsonObjectOf(raw).mapValues { (addr, v) -> decodeVehicleDevice(addr, v) }
        }.getOrDefault(emptyMap())
    }

    /** New format: `{address: {mode, name, obd2Address?}}`. Old format (v1.28,
     *  pre-OBD2): `{address: {mode, name}}`, no `obd2Address` key — decodes to
     *  `null`. Oldest format (v1.24): `{address: "MODE"}` as a bare JSON string,
     *  no name — falls back to the address as the display name. */
    internal fun decodeVehicleDevice(address: String, v: JsonElement): VehicleDevice = when (v) {
        is JsonObject -> VehicleDevice(
            address,
            v.optString("name", address),
            TravelMode.of(v.optString("mode")),
            v.optString("obd2Address").takeIf { it.isNotBlank() },
        )
        else -> VehicleDevice(address, address, TravelMode.of(v.toString().trim('"')), null)
    }

    internal fun encodeVehicleDevice(d: VehicleDevice): JsonObject = buildJsonObject {
        put("mode", d.mode.name)
        put("name", d.name)
        d.obd2Address?.let { put("obd2Address", it) }
    }
```

Replace the body of `writeVehicleDevices` (the `buildJsonObject { map.forEach { ... } }` block) to
call the extracted encoder:

```kotlin
    private fun writeVehicleDevices(map: Map<String, VehicleDevice>) {
        _vehicleDevices.value = map
        val json = buildJsonObject {
            map.forEach { (addr, d) -> put(addr, encodeVehicleDevice(d)) }
        }
        prefs.putString("vehicle_devices", json.toString())
    }
```

If `writeVehicleDevices`'s trailing lines (persisting `json` via `prefs.putString`) differ from
this — the tail wasn't shown above the extraction point — keep whatever persistence call already
follows the JSON build unchanged; only the JSON-building body changes.

Add the setter next to `addVehicleDevice`/`removeVehicleDevice`:

```kotlin
    /** Assign or clear [address]'s OBD2 adapter. `null` un-pairs it — the
     *  vehicle keeps its auto-detect [VehicleDevice.address] either way. */
    fun setObd2Address(address: String, obd2Address: String?) {
        val current = _vehicleDevices.value[address] ?: return
        val next = _vehicleDevices.value.toMutableMap()
        next[address] = current.copy(obd2Address = obd2Address)
        writeVehicleDevices(next)
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.jellemax.detour.data.SettingsVehicleDeviceTest"`
Expected: PASS, 4 tests green.

- [ ] **Step 5: Run the shared metadata check**

Run: `./gradlew :shared:compileCommonMainKotlinMetadata`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Confirm no other call site breaks**

Run: `grep -rn "VehicleDevice(" app/src shared/src` — every existing positional-argument call site
(e.g. `VehicleDevice(address, name, mode)` in `addVehicleDevice`) still compiles because
`obd2Address` defaults to `null`; there should be no call site passing 4 positional args that
would now bind wrong. Confirm by eye, no code change expected here.

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/Settings.kt \
        shared/src/commonTest/kotlin/com/jellemax/detour/data/SettingsVehicleDeviceTest.kt
git commit -m "feat(shared): add VehicleDevice.obd2Address, extract Settings vehicle-device codec"
```

---

### Task 2: `Obd2Pids` SAE J1979 decode functions

**Files:**
- Create: `shared/src/commonMain/kotlin/com/jellemax/detour/drive/Obd2Pids.kt`
- Test: `shared/src/commonTest/kotlin/com/jellemax/detour/drive/Obd2PidsTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `Obd2Pids.PID_SPEED/PID_THROTTLE/PID_RPM: String` (the 4-hex-digit mode+PID request
  strings), `fun parseSpeedKmh(dataBytes: List<Int>): Double?`,
  `fun parseThrottlePct(dataBytes: List<Int>): Double?`, `fun parseRpm(dataBytes: List<Int>): Double?`.
  Each `dataBytes` entry is `0..255` (one decoded response byte, header/prompt already stripped by
  the caller). Consumed by Task 3's `Obd2Connection`.

- [ ] **Step 1: Write the failing tests**

Create `shared/src/commonTest/kotlin/com/jellemax/detour/drive/Obd2PidsTest.kt`:

```kotlin
package com.jellemax.detour.drive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Characterises [Obd2Pids]' SAE J1979 byte decode for maxke24/Detour#62 —
 *  pure math, no I/O, fixtures are the PID spec's own byte tables so no
 *  physical adapter is needed to verify these. */
class Obd2PidsTest {

    // --- Speed (mode 01 PID 0D): one byte, km/h direct -----------------------

    @Test
    fun speedIsTheRawByteInKmh() {
        assertEquals(50.0, Obd2Pids.parseSpeedKmh(listOf(50)))
    }

    @Test
    fun speedOfZeroIsAValidReadingNotAbsence() {
        assertEquals(0.0, Obd2Pids.parseSpeedKmh(listOf(0)))
    }

    @Test
    fun speedAtTheByteCeilingIs255KmH() {
        assertEquals(255.0, Obd2Pids.parseSpeedKmh(listOf(255)))
    }

    @Test
    fun anEmptySpeedResponseIsNull() {
        assertNull(Obd2Pids.parseSpeedKmh(emptyList()))
    }

    // --- Throttle (mode 01 PID 11): one byte, A*100/255 -----------------------

    @Test
    fun throttleAtTheByteCeilingIsFullyOpen() {
        assertEquals(100.0, Obd2Pids.parseThrottlePct(listOf(255)))
    }

    @Test
    fun throttleAtZeroIsFullyClosed() {
        assertEquals(0.0, Obd2Pids.parseThrottlePct(listOf(0)))
    }

    @Test
    fun throttleAtHalfByteIsRoughlyHalfOpen() {
        assertEquals(50.19607843137255, Obd2Pids.parseThrottlePct(listOf(128)))
    }

    @Test
    fun aMissingThrottleResponseIsNull() {
        assertNull(Obd2Pids.parseThrottlePct(emptyList()))
    }

    // --- RPM (mode 01 PID 0C): two bytes, (256*A + B)/4 ------------------------

    @Test
    fun rpmCombinesBothBytes() {
        // (256*0x1A + 0xF8) / 4 = (256*26 + 248) / 4 = 6816/4 = 1704.0
        assertEquals(1704.0, Obd2Pids.parseRpm(listOf(0x1A, 0xF8)))
    }

    @Test
    fun rpmOfZeroBytesIsZeroRpm() {
        assertEquals(0.0, Obd2Pids.parseRpm(listOf(0, 0)))
    }

    @Test
    fun rpmMissingTheSecondByteIsNull() {
        assertNull(Obd2Pids.parseRpm(listOf(0x1A)))
    }

    @Test
    fun rpmWithNoBytesAtAllIsNull() {
        assertNull(Obd2Pids.parseRpm(emptyList()))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.jellemax.detour.drive.Obd2PidsTest"`
Expected: FAIL — `Obd2Pids` does not exist yet.

- [ ] **Step 3: Write `Obd2Pids`**

Create `shared/src/commonMain/kotlin/com/jellemax/detour/drive/Obd2Pids.kt`:

```kotlin
package com.jellemax.detour.drive

/**
 * SAE J1979 mode-01 PID byte decoding for maxke24/Detour#62 — pure math, no
 * I/O, no ELM327 text-protocol handling (that's [Obd2Connection]'s job on the
 * app side; this only sees the decoded data bytes of an already-parsed
 * response). Every function returns null on a short/empty [dataBytes] rather
 * than throwing — a malformed or truncated adapter response is normal
 * (cheap-clone firmware quality varies), not exceptional.
 */
object Obd2Pids {
    /** Mode 01, PID 0D — vehicle speed. Request string sent verbatim to the
     *  adapter (`ATE0`'d, so no echo to strip from the request itself). */
    const val PID_SPEED = "010D"
    const val PID_THROTTLE = "0111"
    const val PID_RPM = "010C"

    /** One byte, km/h direct. 0 is a valid reading (stopped), not absence —
     *  absence is an empty [dataBytes], not any particular byte value. */
    fun parseSpeedKmh(dataBytes: List<Int>): Double? =
        dataBytes.getOrNull(0)?.toDouble()

    /** One byte, `A * 100 / 255` — the byte's full 0..255 range maps onto 0..100%. */
    fun parseThrottlePct(dataBytes: List<Int>): Double? =
        dataBytes.getOrNull(0)?.let { it * 100.0 / 255.0 }

    /** Two bytes, `(256*A + B) / 4` — quarter-RPM resolution. */
    fun parseRpm(dataBytes: List<Int>): Double? {
        val a = dataBytes.getOrNull(0) ?: return null
        val b = dataBytes.getOrNull(1) ?: return null
        return (256.0 * a + b) / 4.0
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.jellemax.detour.drive.Obd2PidsTest"`
Expected: PASS, 12 tests green.

- [ ] **Step 5: Run the shared metadata check**

Run: `./gradlew :shared:compileCommonMainKotlinMetadata`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/drive/Obd2Pids.kt \
        shared/src/commonTest/kotlin/com/jellemax/detour/drive/Obd2PidsTest.kt
git commit -m "feat(shared): add Obd2Pids SAE J1979 speed/throttle/RPM decode"
```

---

### Task 3: `Obd2Connection` — Bluetooth SPP socket, handshake, polling loop

**Files:**
- Create: `app/src/main/java/com/jellemax/detour/obd2/Obd2Connection.kt`

**Interfaces:**
- Consumes: `Obd2Pids.PID_SPEED/PID_THROTTLE/PID_RPM`, `.parseSpeedKmh`, `.parseThrottlePct`, `.parseRpm`.
  `com.jellemax.detour.drive.backoffDelayMs` is `internal` to `shared`'s `drive` package and is
  **not** visible from `app/` — this task reimplements the same doubling shape locally (a small,
  self-contained function) rather than widening that visibility for one caller outside the module.
- Produces: `data class ObdTelemetry(hasSpeed: Boolean, speedKmh: Double, hasThrottle: Boolean, throttlePct: Double, hasRpm: Boolean, rpmValue: Double, receivedAtMs: Long)`.
  `enum class Obd2ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, FAILED }`.
  `object Obd2Connection { val telemetry: StateFlow<ObdTelemetry?>; val connectionState: StateFlow<Obd2ConnectionState>; fun connect(context: Context, address: String); fun disconnect() }`.
  Consumed by Task 4 (wiring into `btReceiver`) and Task 5 (`freshObdTelemetry`).

**No unit test for this task** — no Robolectric, no instrumented test source set, and Bluetooth
socket I/O can't be exercised without a real adapter (Global Constraints). Verified by manual
on-device pairing once the user has a physical ELM327, per the spec's Testing section — that
verification is explicitly out of scope for this session.

- [ ] **Step 1: Write `Obd2Connection`**

Create `app/src/main/java/com/jellemax/detour/obd2/Obd2Connection.kt`:

```kotlin
package com.jellemax.detour.obd2

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.jellemax.detour.drive.Obd2Pids
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A single OBD2 reading. `receivedAtMs` is stamped on arrival, not carried
 *  from the adapter — same convention as `BoardTelemetry.receivedAtMs`, so a
 *  disconnected/stalled adapter reads as stale on the consumer side rather
 *  than freezing on its last number. Each `hasX` is independent: one PID's
 *  response failing this poll cycle doesn't blank the other two. */
data class ObdTelemetry(
    val hasSpeed: Boolean, val speedKmh: Double,
    val hasThrottle: Boolean, val throttlePct: Double,
    val hasRpm: Boolean, val rpmValue: Double,
    val receivedAtMs: Long,
)

enum class Obd2ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

/**
 * Bluetooth Classic (SPP) connection to a paired ELM327-compatible adapter
 * for maxke24/Detour#62. A process-wide singleton, matching `BleNavServer`'s
 * shape — [connect]/[disconnect] are called from `TripTrackingService`'s
 * Bluetooth-vehicle-detect receiver (Task 4), independent of whether a trip
 * is active, so the pairing screen (Task 6) can also show a live reading
 * without a trip running.
 */
@SuppressLint("MissingPermission") // caller (TripTrackingService) already gates on hasBtPermission()
object Obd2Connection {
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private const val HANDSHAKE_TIMEOUT_MS = 2_000L
    private const val POLL_TIMEOUT_MS = 1_000L
    private const val POLL_INTERVAL_MS = 1_000L
    private const val BASE_RETRY_MS = 5_000L
    private const val MAX_RETRY_MS = 60_000L
    private const val MAX_DOUBLINGS = 5

    private val _telemetry = MutableStateFlow<ObdTelemetry?>(null)
    val telemetry: StateFlow<ObdTelemetry?> = _telemetry

    private val _connectionState = MutableStateFlow(Obd2ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<Obd2ConnectionState> = _connectionState

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Same doubling-backoff shape as `com.jellemax.detour.drive.backoffDelayMs`
     *  (that function is `internal` to `shared`'s `drive` package, not visible
     *  here) — a persistently unresponsive or incompatible clone must not
     *  retry in a tight loop. */
    private fun retryDelayMs(failures: Int): Long {
        if (failures <= 0) return BASE_RETRY_MS
        val doubled = BASE_RETRY_MS shl minOf(failures, MAX_DOUBLINGS)
        return minOf(doubled, MAX_RETRY_MS)
    }

    fun connect(context: Context, address: String) {
        if (job?.isActive == true) return
        job = scope.launch { runConnectionLoop(context, address) }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        _connectionState.value = Obd2ConnectionState.DISCONNECTED
        _telemetry.value = null
    }

    private suspend fun runConnectionLoop(context: Context, address: String) {
        var failures = 0
        while (scope.isActive) {
            _connectionState.value = Obd2ConnectionState.CONNECTING
            var socket: BluetoothSocket? = null
            try {
                val device = context.getSystemService(BluetoothManager::class.java)
                    ?.adapter?.getRemoteDevice(address)
                    ?: throw IOException("Bluetooth adapter unavailable")
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                val input = socket.inputStream
                val output = socket.outputStream
                handshake(input, output)
                _connectionState.value = Obd2ConnectionState.CONNECTED
                failures = 0
                pollLoop(input, output)
            } catch (e: IOException) {
                failures++
                _connectionState.value = Obd2ConnectionState.FAILED
                _telemetry.value = null
            } finally {
                runCatching { socket?.close() }
            }
            if (!scope.isActive) return
            delay(retryDelayMs(failures))
        }
    }

    private fun handshake(input: InputStream, output: OutputStream) {
        sendCommand(output, "ATZ")
        readUntilPrompt(input, HANDSHAKE_TIMEOUT_MS)
        sendCommand(output, "ATE0")
        readUntilPrompt(input, HANDSHAKE_TIMEOUT_MS)
        sendCommand(output, "ATSP0")
        readUntilPrompt(input, HANDSHAKE_TIMEOUT_MS)
    }

    private suspend fun pollLoop(input: InputStream, output: OutputStream) {
        while (scope.isActive) {
            val speed = pollPid(input, output, Obd2Pids.PID_SPEED)?.let { Obd2Pids.parseSpeedKmh(it) }
            val throttle = pollPid(input, output, Obd2Pids.PID_THROTTLE)?.let { Obd2Pids.parseThrottlePct(it) }
            val rpm = pollPid(input, output, Obd2Pids.PID_RPM)?.let { Obd2Pids.parseRpm(it) }
            _telemetry.value = ObdTelemetry(
                hasSpeed = speed != null, speedKmh = speed ?: 0.0,
                hasThrottle = throttle != null, throttlePct = throttle ?: 0.0,
                hasRpm = rpm != null, rpmValue = rpm ?: 0.0,
                receivedAtMs = System.currentTimeMillis(),
            )
            delay(POLL_INTERVAL_MS)
        }
    }

    /** Sends [pid], reads the response, and returns its data bytes with the
     *  `41 <pid>` echo header stripped — null on a timeout, a malformed
     *  response, or a header that doesn't match the PID just requested (a
     *  desynced clone answering the previous command late). */
    private fun pollPid(input: InputStream, output: OutputStream, pid: String): List<Int>? {
        sendCommand(output, pid)
        val raw = readUntilPrompt(input, POLL_TIMEOUT_MS) ?: return null
        val tokens = raw.trim().split(Regex("\\s+"))
            .mapNotNull { it.toIntOrNull(16) }
        val modeByte = ("4" + pid[1]).toIntOrNull(16) // request "010D" -> response mode byte 0x41
        val pidByte = pid.substring(2).toIntOrNull(16)
        if (tokens.size < 2 || tokens[0] != modeByte || tokens[1] != pidByte) return null
        return tokens.drop(2)
    }

    private fun sendCommand(output: OutputStream, command: String) {
        output.write("$command\r".toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    /** Reads bytes until the `>` prompt ELM327 terminates every response
     *  with, or [timeoutMs] elapses — never a newline, which some firmwares
     *  omit. Null on timeout with nothing usable read. */
    private fun readUntilPrompt(input: InputStream, timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val buffer = StringBuilder()
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val b = input.read()
                if (b == -1) break
                val c = b.toChar()
                if (c == '>') return buffer.toString()
                buffer.append(c)
            } else {
                Thread.sleep(20)
            }
        }
        return buffer.toString().takeIf { it.isNotBlank() }
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. `Obd2Connection` is not referenced anywhere yet, so this only confirms
it compiles standalone.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/obd2/Obd2Connection.kt
git commit -m "feat(app): add Obd2Connection SPP socket, handshake, and PID polling loop"
```

---

### Task 4: Wire `Obd2Connection` into `TripTrackingService`'s `btReceiver`

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt`

**Interfaces:**
- Consumes: `Obd2Connection.connect(context, address)`, `.disconnect()`, `Settings.vehicleDevices`.
- Produces: nothing new — this task only opens/closes the socket on the existing ACL events.
  Consumed conceptually by Task 5 (the socket must be connecting before its telemetry is useful),
  but there's no compile-time dependency between the two tasks' changes.

**No unit test** — same carve-out as Task 3; this only adds two function calls inside existing,
already-unit-test-exempt `BroadcastReceiver` callbacks.

- [ ] **Step 1: Add the import**

```kotlin
import com.jellemax.detour.obd2.Obd2Connection
```

- [ ] **Step 2: Open the OBD2 socket on ACL-connect for a matching vehicle**

Modify `btReceiver`'s `onReceive` (the block handling `BluetoothDevice.ACTION_ACL_CONNECTED` /
`ACTION_ACL_DISCONNECTED`, grep for `ACTION_ACL_CONNECTED ->` to find it — do not trust an absolute
line number, this file has grown across #61's own tasks):

```kotlin
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = deviceFrom(intent) ?: return
            val address = try { device.address } catch (e: SecurityException) { return } ?: return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    if (Settings.vehicleDevices.value.containsKey(address)) {
                        connectedVehicles.remove(address) // move to newest
                        connectedVehicles.add(address)
                        refreshTripMode()
                    }
                    Settings.vehicleDevices.value.values
                        .firstOrNull { it.obd2Address == address }
                        ?.let { Obd2Connection.connect(context, address) }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    if (connectedVehicles.remove(address)) refreshTripMode()
                    if (Settings.vehicleDevices.value.values.any { it.obd2Address == address }) {
                        Obd2Connection.disconnect()
                    }
                }
            }
        }
    }
```

Only the two new `Settings.vehicleDevices.value.values...`-driven blocks are new; the existing
`connectedVehicles`/`refreshTripMode()` lines are unchanged (shown for placement context — locate
the real insertion point by grepping for `ACTION_ACL_CONNECTED ->`, not by this snippet's line
count).

- [ ] **Step 3: Tear down on Bluetooth-off, alongside the existing vehicle-clear**

Modify `btStateReceiver`'s `STATE_TURNING_OFF, STATE_OFF` branch (grep for
`BluetoothAdapter.STATE_TURNING_OFF`):

```kotlin
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    if (connectedVehicles.isNotEmpty()) {
                        connectedVehicles.clear()
                        refreshTripMode()
                    }
                    Obd2Connection.disconnect()
                }
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all existing tests still pass (no new test in this task).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt
git commit -m "feat(trip): connect/disconnect the OBD2 adapter on its vehicle's ACL events"
```

---

### Task 5: Wire OBD2 into `effectiveSpeedMps` / `speedIsReal`

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt`

**Interfaces:**
- Consumes: `Obd2Connection.telemetry: StateFlow<ObdTelemetry?>`, `ObdTelemetry.hasSpeed/.speedKmh/.receivedAtMs`.
- Produces: `private fun freshObdTelemetry(): ObdTelemetry?`. No new field on `TripStats` — OBD2 is
  a speed *source*, not a new stat; the existing `hardBrakeCount`/etc. fields already reflect
  whichever source `effectiveSpeedMps` picked, unchanged from #61's shape.

**No unit test** — same carve-out as Tasks 3-4. Verified by GPS replay per `detour-gps-replay`:
confirm the GPS-fallback path (`Obd2Connection.telemetry.value == null`, the case a replay fixture
*can* produce) still drives `HardEventDetector`/`StopDetector`/the over-limit accumulator exactly
as #61 left them — this task must add a source without regressing the existing one, which a replay
run can and should confirm even without a real OBD2 reading.

- [ ] **Step 1: Add the import**

```kotlin
import com.jellemax.detour.obd2.Obd2Connection
import com.jellemax.detour.obd2.ObdTelemetry
```

(`Obd2Connection` may already be imported from Task 4 in the same file — if so, only add the
`ObdTelemetry` import.)

- [ ] **Step 2: Add the staleness constant and `freshObdTelemetry()`**

Add the constant next to `BOARD_TELEMETRY_STALE_MS` (grep for it in the companion object):

```kotlin
        /** Same reasoning as [BOARD_TELEMETRY_STALE_MS]: a disconnected/stalled
         *  OBD2 adapter must read as stale, not freeze on its last speed. The
         *  poll loop ticks every ~1s (see Obd2Connection.POLL_INTERVAL_MS); 3s
         *  tolerates one or two missed polls before falling back to GPS. */
        private const val OBD_TELEMETRY_STALE_MS = 3_000L
```

Add `freshObdTelemetry()` immediately after `freshBoardTelemetry()` (grep for
`private fun freshBoardTelemetry`):

```kotlin
    /** Mirrors [freshBoardTelemetry] exactly — see its own KDoc for why
     *  staleness is gated on arrival time rather than trusting the source to
     *  say when it disconnected. */
    private fun freshObdTelemetry(): ObdTelemetry? {
        val telemetry = Obd2Connection.telemetry.value ?: return null
        val age = System.currentTimeMillis() - telemetry.receivedAtMs
        return if (age in 0..OBD_TELEMETRY_STALE_MS) telemetry else null
    }
```

- [ ] **Step 3: Add OBD2 as the top-priority speed source**

Modify the `effectiveSpeedMps` assignment (grep for `val effectiveSpeedMps = freshBoardTelemetry()`):

```kotlin
        val effectiveSpeedMps = freshObdTelemetry()
            ?.takeIf { it.hasSpeed }
            ?.let { it.speedKmh / 3.6 }
            ?: freshBoardTelemetry()
                ?.takeIf { it.hasSpeed }
                ?.let { it.speedKmh / 3.6 }
            ?: speed
```

- [ ] **Step 4: Add OBD2 to the `speedIsReal` discriminator**

Modify the `speedIsReal` assignment (grep for `val speedIsReal = location.hasSpeed()`):

```kotlin
        val speedIsReal = location.hasSpeed() ||
            freshBoardTelemetry()?.takeIf { it.hasSpeed } != null ||
            freshObdTelemetry()?.takeIf { it.hasSpeed } != null
```

- [ ] **Step 5: Compile and confirm no regression**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all existing tests (including #61's `HardEventDetectorTest`,
`StopDetectorTest`) still pass unchanged — they exercise the detectors directly with a `speedMps`
argument, not through `effectiveSpeedMps`, so this task's change is invisible to them.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt
git commit -m "feat(trip): prefer OBD2 speed over board telemetry and GPS when connected"
```

---

### Task 6: `Obd2PairingScreen` + Settings entry point + version bump

**Files:**
- Create: `app/src/main/java/com/jellemax/detour/ui/Obd2PairingScreen.kt`
- Modify: `app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: `Settings.vehicleDevices`, `.setObd2Address`, `Obd2Connection.connect/.disconnect/.telemetry/.connectionState`.
- Produces: nothing consumed by a later task — this is the plan's last task.

**No unit test** — Compose UI, same as #61's `MapHud`/`TripDetailScreen` UI tasks (no Compose
test infra in this repo's `app` module). Verified by on-device pairing once an adapter is
available (out of scope this session, per Global Constraints).

- [ ] **Step 1: Add `SettingsPage.OBD2`**

Modify `app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt` — add to the `SettingsPage`
enum (grep for `private enum class SettingsPage`):

```kotlin
private enum class SettingsPage(val title: String) {
    ROOT("Settings"),
    APPEARANCE_MAP("Appearance & map"),
    TRACKING_VEHICLES("Tracking & vehicles"),
    NAVIGATION("Navigation"),
    FOG("Fog of war"),
    DISPLAYS_MEDIA("Displays & media"),
    SERVERS_SYNC("Servers & sync"),
    OBD2("OBD2 adapter"),
}
```

Add a `HubRow` for it in the `SettingsPage.ROOT ->` block (grep for the last `HubRow(` before the
version-string `Text`, i.e. the `SERVERS_SYNC` row), placed after it:

```kotlin
                    HubRow(
                        icon = Icons.Outlined.Speed,
                        title = SettingsPage.OBD2.title,
                        subtitle = "Connect a vehicle's OBD2 adapter for accurate speed",
                        onClick = { page = SettingsPage.OBD2 },
                    )
```

If `Icons.Outlined.Speed` isn't already imported in this file, add:
`import androidx.compose.material.icons.outlined.Speed`.

Add the page's content dispatch in the `when (page)` block, after the `SettingsPage.SERVERS_SYNC ->`
branch:

```kotlin
                SettingsPage.OBD2 -> Obd2PairingScreen()
```

- [ ] **Step 2: Write `Obd2PairingScreen`**

Create `app/src/main/java/com/jellemax/detour/ui/Obd2PairingScreen.kt`:

```kotlin
package com.jellemax.detour.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.data.Settings
import com.jellemax.detour.obd2.Obd2Connection
import com.jellemax.detour.obd2.Obd2ConnectionState

/**
 * Pair a bonded Bluetooth device as a vehicle's OBD2 adapter, and show a live
 * connection state + reading — the only on-screen way to confirm "this
 * adapter actually works" without a road test (maxke24/Detour#62). A
 * dedicated page rather than folded into [VehicleSection] since a vehicle's
 * OBD2 adapter is a distinct device from its auto-detect [Settings.VehicleDevice.address].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Obd2PairingScreen() {
    val context = LocalContext.current
    val mapping by Settings.vehicleDevices.collectAsStateWithLifecycle()
    val connectionState by Obd2Connection.connectionState.collectAsStateWithLifecycle()
    val telemetry by Obd2Connection.telemetry.collectAsStateWithLifecycle()

    var hasPerm by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_CONNECT,
                ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPerm = granted }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Pair a vehicle's OBD2 adapter (a Bluetooth ELM327 dongle plugged into the " +
                "port) for accurate speed instead of GPS. Not a score to chase, not new " +
                "history — just a more accurate speed reading while it's connected.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!hasPerm) {
            OutlinedButton(onClick = { permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
                Text("Allow Bluetooth")
            }
            return@Column
        }
        val bonded = remember(hasPerm) {
            try {
                context.getSystemService(BluetoothManager::class.java)?.adapter
                    ?.bondedDevices
                    ?.sortedBy { runCatching { it.name }.getOrNull() ?: it.address }
                    ?: emptyList()
            } catch (e: SecurityException) {
                emptyList()
            }
        }
        mapping.values.sortedBy { it.name }.forEach { vehicle ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(vehicle.name, style = MaterialTheme.typography.bodyLarge)
                val pairedName = vehicle.obd2Address?.let { addr ->
                    bonded.firstOrNull { it.address == addr }
                        ?.let { runCatching { it.name }.getOrNull() } ?: addr
                }
                if (pairedName == null) {
                    val unassigned = bonded.filter { it.address != vehicle.address }
                    if (unassigned.isEmpty()) {
                        Text(
                            "No other paired devices to use as an OBD2 adapter.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        unassigned.forEach { device ->
                            val name = runCatching { device.name }.getOrNull() ?: device.address
                            OutlinedButton(onClick = {
                                Settings.setObd2Address(vehicle.address, device.address)
                            }) { Text("Use $name") }
                        }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Adapter: $pairedName", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { Settings.setObd2Address(vehicle.address, null) }) {
                            Text("Forget")
                        }
                    }
                    Text(
                        "Status: ${connectionState.name.lowercase().replaceFirstChar { it.uppercase() }}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (connectionState == Obd2ConnectionState.CONNECTED) {
                        telemetry?.let { t ->
                            Text(
                                buildString {
                                    if (t.hasSpeed) append("Speed: ${t.speedKmh.toInt()} km/h  ")
                                    if (t.hasThrottle) append("Throttle: ${t.throttlePct.toInt()}%  ")
                                    if (t.hasRpm) append("RPM: ${t.rpmValue.toInt()}")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        if (mapping.isEmpty()) {
            Text(
                "Add a vehicle under Tracking & vehicles first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

If `verticalArrangement = Arrangement.spacedBy(12.dp)`'s `12.dp` doesn't resolve (missing
`androidx.compose.ui.unit.dp` import), add `import androidx.compose.ui.unit.dp`.

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Bump `versionName`**

Modify `app/build.gradle.kts` — change `versionName = "1.77.0"` to `versionName = "1.78.0"` (new
feature, backward compatible: old `VehicleDevice` entries decode with `obd2Address = null`, and
GPS/board-telemetry behavior is unchanged when no adapter is paired — per `CONTRIBUTING.md`'s
Versioning section and this plan's Global Constraints).

- [ ] **Step 5: Full verification gate**

Run: `./gradlew :shared:compileCommonMainKotlinMetadata`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests green (Task 1's 4 + Task 2's 12 new, plus every pre-existing
test including #61's).

Run: `./gradlew :app:assembleDebug :app:assembleRelease`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/Obd2PairingScreen.kt \
        app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt \
        app/build.gradle.kts
git commit -m "feat(ui): add OBD2 adapter pairing screen, bump versionName to 1.78.0"
```

## Verification

Gates already run per-task above; the full sequence once more for the finished branch:

```bash
./gradlew :shared:compileCommonMainKotlinMetadata
./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleRelease
```

On-device pairing with a physical ELM327 adapter and a real drive is **not attempted in this
plan** — no adapter confirmed available this session. That verification, and any adapter-specific
firmware quirks it surfaces, is the user's own follow-up step, same as #61 left its GPS-replay
device pass to the user.

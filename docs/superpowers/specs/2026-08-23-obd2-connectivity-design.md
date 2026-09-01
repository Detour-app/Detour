# OBD2 connectivity: reliable speed/throttle/RPM via a paired ELM327 adapter

Closes maxke24/Detour#62. Android only, generic SAE J1979 PIDs only (per the issue's own scope).
Builds directly on #61 (`docs/superpowers/specs/2026-08-23-driving-behavior-stats-design.md`,
branch `worktree-driving-behavior-stats`) — this feature's whole reason to exist is replacing
#61's GPS-Δv/Δt brake/accel derivation with a more accurate vehicle-native signal when one is
available. **Dependency note:** #61 is implemented but not yet merged to `main` (kept on its own
branch pending the user's on-device test pass). This spec's implementation plan branches from
`worktree-driving-behavior-stats`'s tip, not from `origin/main`, so the wiring described below
(the `speedIsReal` guard, `effectiveSpeedMps`'s existing priority chain, `HardEventDetector`) can
be written against code that actually exists rather than against #61's own plan text — the
inverse of the drift #61's own Task 4 hit re-deriving against a stale reading of `main`.

## Scope

- Bluetooth Classic (SPP) connection to a paired ELM327-compatible adapter, opt-in per vehicle.
- AT-command handshake (`ATZ`, `ATE0`, `ATSP0`) + polling loop for three SAE J1979 PIDs: vehicle
  speed (`010D`), throttle position (`0111`), engine RPM (`010C`).
- Speed feeds #61's `HardEventDetector.onSpeedFix` in place of GPS Δv/Δt when connected, with
  automatic fallback to GPS when not.
- New dedicated pairing screen: pick a bonded device, assign it to a vehicle as its OBD2 adapter,
  see live connection state.
- PID-byte decoding (raw bytes → km/h / % / rpm — pure SAE J1979 math) in `shared/`. Bluetooth
  socket I/O, the AT handshake, and the polling loop stay app-only — `commonMain` has no Bluetooth
  socket API, and this mirrors #61's own shared/app split (raw sensor reads stay platform-side,
  the math that interprets them is shared).
- Throttle % and RPM are decoded and exposed live (for the pairing screen's own "is this working"
  readout) but get no new persisted `Trip`/`DrivingStats` field this pass — decode-only, no new
  storage schema, no new UI beyond the pairing screen. Reopen as a separate issue if a concrete
  use for stored throttle/RPM history shows up later.

## Explicitly out of scope (per the issue)

- Lateral acceleration / cornering PIDs — no standardized PID, manufacturer-specific and not
  worth chasing.
- Brake-pedal state — also non-standard across makes.
- iOS — MFi/iAP2 accessory certification is a much larger lift than Android's classic-Bluetooth
  socket API; a separate follow-up if ever.
- Any PID beyond the generic SAE J1979 set (no manufacturer-specific extensions).
- Adapter compatibility testing/recommendations, and a physical on-device pairing+drive
  verification — **no ELM327 adapter is confirmed available this session.** The implementation
  plan must make the shared PID-decode math fully unit-testable without hardware, and flag the
  app-side Bluetooth socket/pairing code as unverified-live, the same way #61's plan flagged
  `TripTrackingService`'s GPS wiring as verified by replay rather than by a unit test.

## Where this plugs into the existing app

`TripTrackingService` already runs a persistent foreground service purely to watch for a mapped
vehicle's Bluetooth connect/disconnect (`btReceiver`, `ACTION_ACL_CONNECTED`/`_DISCONNECTED`,
`:588-632` in the #61 branch) — this runs independent of whether a trip is active, to *detect*
when one should start. OBD2 connects into this same always-on watcher rather than a second
background service: no new persistent notification, no new battery surface, no duplicated
Bluetooth-permission/lifecycle plumbing.

There is also an existing external-speed-source pattern to mirror exactly: `BoardTelemetry`
(`app/.../ble/BleNavServer.kt:73`) — `hasSpeed`/`speedKmh`/`receivedAtMs`, consulted by
`effectiveSpeedMps` ahead of phone GPS when fresh (`TripTrackingService.kt:1198-1204` in the #61
branch), and gated into #61's `speedIsReal` discriminator so a stale/absent reading never
masquerades as a real zero. OBD2 gets an `ObdTelemetry` data class of the identical shape, slotted
one priority level above `BoardTelemetry`: **OBD2 → board telemetry → phone GPS.**

## Data model

`Settings.VehicleDevice` (`shared/.../data/Settings.kt:90`) gains one field:

```kotlin
data class VehicleDevice(
    val address: String,
    val name: String,
    val mode: TravelMode,
    val obd2Address: String? = null,
)
```

Deliberately independent of the existing `address` (the auto-detect device that picks which
vehicle a trip belongs to) — a car can have both a Bluetooth stereo/headunit *and* a separate
ELM327 dongle plugged into the OBD2 port; they are not the same device, and a vehicle may have
one, the other, both, or neither. `obd2Address == null` is the default, backward-compatible
(existing settings entries decode with no OBD2 adapter assigned, same zero-default convention
`DrivingStats` uses).

## PID decoding — `shared/.../drive/Obd2Pids.kt`

Pure functions, no I/O, no platform dependency — an ELM327 response for a given PID is a fixed
byte layout per SAE J1979:

```kotlin
object Obd2Pids {
    const val PID_SPEED = "010D"
    const val PID_THROTTLE = "0111"
    const val PID_RPM = "010C"

    /** Vehicle speed, km/h direct (mode 01 PID 0D: `A`). Expects the response's
     *  data bytes only (post "41 0D" header stripped by the caller), e.g. a
     *  one-byte `[50]` → 50.0. Null on a short/malformed response. */
    fun parseSpeedKmh(dataBytes: List<Int>): Double? =
        dataBytes.getOrNull(0)?.toDouble()

    /** Throttle position, % (mode 01 PID 11: `A * 100 / 255`). */
    fun parseThrottlePct(dataBytes: List<Int>): Double? =
        dataBytes.getOrNull(0)?.let { it * 100.0 / 255.0 }

    /** Engine RPM (mode 01 PID 0C: `(256*A + B) / 4`). */
    fun parseRpm(dataBytes: List<Int>): Double? {
        val a = dataBytes.getOrNull(0) ?: return null
        val b = dataBytes.getOrNull(1) ?: return null
        return (256.0 * a + b) / 4.0
    }
}
```

The caller (app-side `Obd2Connection`) is responsible for stripping the ELM327 response framing
(echo of the request if echo isn't off, the `41 0D`/`41 11`/`41 0C` mode+PID echo header, the `>`
prompt terminator, whitespace) down to the raw data bytes before calling these — keeping the pure
math ignorant of ELM327's text-protocol quirks, testable with plain `List<Int>` fixtures per SAE
J1979's own byte tables, no fake serial port needed.

## Bluetooth connection — `app/.../obd2/Obd2Connection.kt`

App-only (`commonMain` has no `BluetoothSocket`). Mirrors `SpeedLimitTracker`/`RoadTypeTracker`'s
`State`/backoff shape where it applies to a socket rather than an HTTP fetch:

```kotlin
data class ObdTelemetry(
    val hasSpeed: Boolean, val speedKmh: Double,
    val hasThrottle: Boolean, val throttlePct: Double,
    val hasRpm: Boolean, val rpmValue: Double,
    val receivedAtMs: Long,
)

enum class Obd2ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

object Obd2Connection {
    val telemetry: StateFlow<ObdTelemetry?>
    val connectionState: StateFlow<Obd2ConnectionState>

    fun connect(context: Context, address: String)  // opens SPP socket, runs handshake, starts polling loop
    fun disconnect()
}
```

**Well-known SPP UUID** `00001101-0000-1000-8000-00805F9B34FB` (`createRfcommSocketToServiceRecord`)
— every ELM327 clone advertises this; no discovery needed beyond the bonded-device picker.

**Handshake**, each command terminated by `\r`, response read until the `>` prompt byte (ELM327's
own terminator, not a newline):
1. `ATZ` — reset. Response is adapter-identifying garbage on some clones; not parsed, only used
   to confirm the socket accepts writes and something answers within a timeout.
2. `ATE0` — echo off, so subsequent reads aren't the command reflected back.
3. `ATSP0` — auto-select protocol (works across the OBD-II protocol variants without knowing the
   vehicle's specific one up front).

A handshake step that times out (no `>` within e.g. 2s) marks the connection `FAILED` and backs
off before retrying — see Error handling below.

**Polling loop** (once handshake succeeds): write `010D\r`, parse the response, write `0111\r`,
parse, write `010C\r`, parse, repeat — at roughly the app's existing GPS fix cadence (no need to
poll faster than the consumer reads). Each parse failure (malformed/short/no response) just leaves
that telemetry field's `hasX` false for this cycle rather than tearing down the connection — an
ELM327 clone dropping one PID response occasionally is normal, not a connection failure.

## Wiring into `TripTrackingService`

`btReceiver`'s existing `ACTION_ACL_CONNECTED` handler (`:600-603` on the #61 branch) gains one
more check alongside the existing `Settings.vehicleDevices.value.containsKey(address)` auto-detect
lookup: if the connecting device's address matches some vehicle's `obd2Address`, call
`Obd2Connection.connect(this, address)`. `ACTION_ACL_DISCONNECTED` and the existing
`btStateReceiver`'s Bluetooth-off handling both call `Obd2Connection.disconnect()` for the same
address — same teardown symmetry the existing `connectedVehicles` bookkeeping already has.

A new private `freshObdTelemetry()` mirrors the existing `freshBoardTelemetry()` exactly (staleness
gated on `receivedAtMs`, same shape, same reasoning — a disconnected adapter must read as stale
rather than freeze on its last number):

```kotlin
private fun freshObdTelemetry(): ObdTelemetry? {
    val telemetry = Obd2Connection.telemetry.value ?: return null
    val age = System.currentTimeMillis() - telemetry.receivedAtMs
    return if (age in 0..OBD_TELEMETRY_STALE_MS) telemetry else null
}
```

`effectiveSpeedMps` (`:1198-1204` on the #61 branch) gains OBD2 as the new top priority:

```kotlin
val effectiveSpeedMps = freshObdTelemetry()
    ?.takeIf { it.hasSpeed }
    ?.let { it.speedKmh / 3.6 }
    ?: freshBoardTelemetry()
        ?.takeIf { it.hasSpeed }
        ?.let { it.speedKmh / 3.6 }
    ?: speed
```

`speedIsReal` (`:1213-1214` on the #61 branch, part of #61's own fabricated-zero-speed fix) gains
the same OBD2 check as its board-telemetry arm — an OBD2 reading is exactly as "real" a
measurement as board telemetry or `location.hasSpeed()`, so it slots into the existing
discriminator rather than needing a new guard shape:

```kotlin
val speedIsReal = location.hasSpeed() ||
    freshBoardTelemetry()?.takeIf { it.hasSpeed } != null ||
    freshObdTelemetry()?.takeIf { it.hasSpeed } != null
```

No change to `HardEventDetector` itself — it already takes a plain `speedMps: Double`, agnostic
to which source produced it. This is the whole point of #61's own clock-free, source-agnostic
`onSpeedFix` design: OBD2 slots in for free.

Throttle % and RPM are NOT wired into any detector this pass (per Scope) — `Obd2Connection`
publishes them on `telemetry` for the pairing screen to read live, and nothing else consumes them
yet.

## Pairing UI — `app/.../obd2/Obd2PairingScreen.kt`

New dedicated screen (own entry point, not folded into the existing vehicle-mapping screen,
per the chosen approach):

- Lists bonded Bluetooth Classic devices (same `BluetoothAdapter.bondedDevices` source the
  existing vehicle-mapping screen already reads).
- Pick a vehicle (from `Settings.vehicleDevices`), pick a bonded device, save →
  `Settings.VehicleDevice.obd2Address`. "Forget" clears it back to `null`.
- Live readout while the picked device is connected: connection state
  (`Obd2Connection.connectionState`) and, when `CONNECTED`, the current `ObdTelemetry` values
  (speed/throttle/RPM) — the only way to confirm "this adapter actually works" without a road
  test, since #62 has no physical adapter to verify against this session.

## Error handling

- **Handshake timeout** (no `>` within the per-command timeout): connection marked `FAILED`,
  socket closed, backs off before the next attempt.
- **Malformed/short PID response**: that field's `hasX` stays false for the cycle; connection
  stays `CONNECTED` — a single bad response is normal ELM327-clone noise, not a failure.
- **Repeated failures**: reuse the existing `internal fun backoffDelayMs(throttleMs, ceilingMs,
  failures)` in `shared/.../drive/Backoff.kt` (already used by `SpeedLimitTracker`/
  `RoadTypeTracker` for their own Overpass-fetch backoff) — same shape, applied to reconnect
  attempts instead of HTTP fetches, so a persistently unresponsive or incompatible clone doesn't
  retry in a tight loop forever.
- **Permission**: reuses the existing `hasBtPermission()` check (`BLUETOOTH_CONNECT` on API 31+,
  granted at install below that) already gating `ensureBluetoothWatch()` — no new permission.
- **Socket I/O exception mid-poll** (adapter unplugged, out of range without an ACL disconnect
  event firing first): caught, connection marked `FAILED`, same backoff path as a handshake
  failure.

## Testing

- `Obd2PidsTest` (`shared/commonTest/.../drive/`): SAE J1979 byte-table fixtures for each PID —
  a normal value, a boundary value (0, 255/0xFF max), and a short/empty `dataBytes` list returning
  null for each of `parseSpeedKmh`/`parseThrottlePct`/`parseRpm`. No hardware, no serial port,
  fully exercisable today.
- `Obd2Connection` itself: **no unit test** — same carve-out #61's plan already established for
  `TripTrackingService` (no Robolectric, no instrumented source set in this repo). Verified by
  manual on-device pairing once a physical adapter is available — explicitly flagged as
  unverified-live in this plan, not silently skipped.
- `TripTrackingService` wiring (the `effectiveSpeedMps`/`speedIsReal` priority additions): same
  no-unit-test carve-out, verified by GPS replay per `detour-gps-replay`, same as #61's own
  wiring tasks — a replay fixture can't produce a real OBD2 reading, but can confirm the
  GPS-fallback path (`Obd2Connection.telemetry.value == null`) still behaves exactly as #61 left
  it, i.e. this feature adds a source without regressing the existing one.

## Commit structure

Same independently-revertible-unit convention #61 used:

1. `Settings.VehicleDevice.obd2Address` field (data model, old settings decode with `null`).
2. `Obd2Pids` (shared, pure decode functions) + tests.
3. `ObdTelemetry`/`Obd2ConnectionState`/`Obd2Connection` (app, Bluetooth I/O + handshake + polling
   loop) — no wiring yet, standalone and compilable.
4. Wire `Obd2Connection.connect`/`disconnect` into `btReceiver`'s ACL handlers.
5. Wire OBD2 into `effectiveSpeedMps`'s priority chain and `speedIsReal`.
6. `Obd2PairingScreen` (new screen + navigation entry point) + settings read/write.

## Verification

Gates before considering this done, per `detour-shared-core` and `CONTRIBUTING.md` (same as #61):

```bash
./gradlew :shared:compileCommonMainKotlinMetadata
./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleRelease
```

On-device pairing + a real drive with a physical ELM327 adapter is explicitly **not achievable
this session** (no adapter confirmed available) — flagged as the user's own follow-up step, same
as #61 flagged its GPS-replay verification as the user's own device-in-the-loop step.

`versionName` bump: new feature, backward compatible (old `VehicleDevice` entries decode with
`obd2Address = null`, GPS fallback unchanged when no adapter is paired) → minor bump, per
`CONTRIBUTING.md`'s Versioning section. Exact bump value depends on #61's own version landing
first (this branches from #61's tip, which already bumped to 1.77.0) — this plan bumps to 1.78.0.

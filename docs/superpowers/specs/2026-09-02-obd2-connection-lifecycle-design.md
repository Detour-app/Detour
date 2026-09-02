# OBD2 connection lifecycle: tie the retry loop to trip + UI state

Design date: 2026-09-02
Closes: #96, #97

## Problem

`Obd2Connection` is a process-wide singleton with one connection job. The job's
retry loop runs `while (coroutineContext.isActive)` with a 5–60s doubling
backoff and **never stops on its own**. `TripTrackingService` seeds it
unconditionally:

- `connectConfiguredObd2Adapters()` runs at the Bluetooth-watch seed
  (`ensureBluetoothWatch`) and again on every Bluetooth toggle, calling
  `Obd2Connection.connect()` for **every** vehicle that has an `obd2Address`.
- `btReceiver`'s `ACTION_ACL_CONNECTED` branch also dials an adapter whose
  address matches a configured `obd2Address`.
- Nothing calls `Obd2Connection.disconnect()` on `ACTION_ACL_DISCONNECTED` — the
  plan for PR #93 had it, the whole-branch review removed it, because that
  forever-retry is *also* the only reconnect path (SPP has no OS-level
  auto-reconnect, and the adapter is dialled insecure so a car power-cycle drops
  the link key and forces a fresh dial).

Two defects fall out:

- **#96** — parked car, adapter powered off, tracking service alive for
  auto-detect: the phone attempts an SPP connect to a dead adapter every 5–60s
  around the clock. Idle battery drain with no proximity gate.
- **#97** — with two configured OBD vehicles, `connect()` no-ops while a job is
  active, so the first-iterated address wins the singleton and its
  forever-retrying loop blocks the other. Vehicle A's adapter at home + vehicle
  B's adapter in the car being driven → A wins the race, retries A for the whole
  drive, B is never dialled, `obd2SpeedPct` stays 0.

## Root cause

The connection loop is not tied to any signal for "is this adapter worth
dialling right now". Both defects are the same missing gate.

## When OBD2 is actually useful

- **During a trip** — it is the top-priority speed source
  (`resolveDisplaySpeedMps`) and feeds the per-trip engine summary
  (`obd2SpeedPct`, RPM/throttle stats).
- **While the app UI is up** — the speed HUD on the map and the OBD2 pairing
  screen both show a live reading without a trip running.

Outside both, an open retry loop does nothing but drain.

## Design

Gate the connection on a single predicate:

```
poll OBD2  ⇔  (a trip is active)  OR  (the app UI is visible)
```

Both inputs already exist in `TripTrackingService`:

- `_stats.value != null` — a trip is active.
- `uiVisible` — set by `MapScreen`'s `ON_START`/`ON_STOP` lifecycle observer
  via `setUiVisible(context, Boolean)` → `refresh(context)` →
  `onStartCommand(ACTION_REFRESH)`.

### `TripTrackingService` changes

**1. `resolvedVehicle()` extraction (behaviour-preserving, its own commit).**

`resolvedMode()` currently inlines the "which connected vehicle wins" pick.
Extract it:

```kotlin
/** The connected mapped vehicle that decides the trip — heaviest mode wins
 *  (see MODE_PRIORITY), null when none is connected. */
private fun resolvedVehicle(): Settings.VehicleDevice? {
    val map = Settings.vehicleDevices.value
    return connectedVehicles.mapNotNull { map[it] }
        .maxByOrNull { MODE_PRIORITY.indexOf(it.mode) }
}

private fun resolvedMode(): TravelMode =
    resolvedVehicle()?.mode ?: Settings.tripMode.value
```

No behaviour change: `resolvedMode()` returns exactly what it did.

**2. `desiredObd2Address()` — the target, or null.**

```kotlin
/** Which OBD2 adapter the connection loop should be on right now, or null to
 *  stay disconnected.
 *  - nothing while parked with the app closed and no trip running (#96);
 *  - a trip's resolved vehicle takes priority — that is the vehicle you are
 *    in, so the singleton never has to choose (#97);
 *  - with a manual trip and no connected mapped vehicle, fall back to the sole
 *    configured adapter if there is exactly one;
 *  - else, while the UI is up, whichever connected mapped vehicle has an
 *    adapter. */
private fun desiredObd2Address(): String? {
    if (_stats.value == null && !uiVisible) return null
    val configured = Settings.vehicleDevices.value.values
    if (_stats.value != null) {
        resolvedVehicle()?.obd2Address?.let { return it }
        configured.mapNotNull { it.obd2Address }.singleOrNull()?.let { return it }
    }
    return connectedVehicles.firstNotNullOfOrNull {
        Settings.vehicleDevices.value[it]?.obd2Address
    }
}
```

**3. `reconcileObd2Connections()` — the single reconciler.** Replaces the
current body (which drops a stale link then blind-dials every configured
adapter):

```kotlin
private fun reconcileObd2Connections() {
    val target = desiredObd2Address()
    val linked = Obd2Connection.linkedAddress.value
    if (linked != null && linked != target) Obd2Connection.disconnect()
    if (target != null && Obd2Connection.linkedAddress.value == null) {
        Obd2Connection.connect(applicationContext, target)
    }
}
```

`Obd2Connection.connect()` already no-ops while its job is active, and
`linkedAddress` is nulled by `disconnect()`, so the second `if` re-reads it
after a possible disconnect above. No change to `Obd2Connection` itself.

**4. Call `reconcileObd2Connections()` from every state edge:**

| Site | Why |
| --- | --- |
| `onStartCommand`, `ACTION_REFRESH` | already wired — fires on every `uiVisible` change |
| `beginTrip()` (end of) | trip started, incl. auto-start with the app backgrounded |
| `endTrip()` (after `_stats.value = null`) | trip stopped → drop the adapter |
| `refreshTripMode()` — unconditionally, outside its existing `if (mode changed)` guard | the async `seedConnectedVehicles()` commit lands here; the resolved vehicle (hence the target adapter) can change after `beginTrip` already ran even when the *mode* did not |
| `btReceiver` `ACTION_ACL_CONNECTED` / `ACTION_ACL_DISCONNECTED` | replace the inline `Obd2Connection.connect(...)` call with a `reconcileObd2Connections()` at the end of each branch |
| `btStateReceiver` `STATE_ON` | replace `connectConfiguredObd2Adapters()` |

**5. Delete `connectConfiguredObd2Adapters()`** and its two call sites
(`ensureBluetoothWatch`, `btStateReceiver` `STATE_ON`). The `STATE_OFF` branch
keeps its existing `Obd2Connection.disconnect()`.

**6. `onDestroy`** — set a `@Volatile private var destroyed = true` at the top,
and have `reconcileObd2Connections()` early-return when it is set. `onDestroy`
calls `endTrip()`, which now reconciles after clearing `_stats`; without the
guard, `uiVisible` still true would re-dial an adapter mid-teardown before the
existing `Obd2Connection.disconnect()` runs. The guard is simpler than
depending on the call order in `onDestroy`.

### `Obd2PairingScreen` change

The screen currently shows a live reading only because the service's blind seed
kept a loop running. With the seed gone, add a connect-on-enter:

```kotlin
val liveAddress = mapping.values.firstNotNullOfOrNull { it.obd2Address }
DisposableEffect(liveAddress) {
    if (liveAddress != null && Obd2Connection.linkedAddress.value == null) {
        Obd2Connection.connect(context.applicationContext, liveAddress)
    }
    onDispose {
        // Leave a service-owned link (trip running / map visible) alone;
        // only tear down a link this screen opened for its own readout.
        if (Obd2Connection.linkedAddress.value == liveAddress &&
            !TripTrackingService.isUiVisible() && !TripTrackingService.isTripActive()
        ) {
            Obd2Connection.disconnect()
        }
    }
}
```

Needs two tiny read-only accessors on `TripTrackingService`'s companion
(`isUiVisible()`, `isTripActive()` returning the `uiVisible` flag and
`_stats.value != null`). The explicit "Retry now" / "Use <device>" / "Forget"
button handlers are unchanged — they already call
`Obd2Connection.disconnect()` + `connect()` directly.

Follow `detour-compose-state-hazards` for the `DisposableEffect` key list
during implementation: `liveAddress` is the only key — a new adapter assignment
re-runs the effect and re-points the readout.

### What does NOT change

- `Obd2Connection` — no API change, no lifecycle change. It still owns one job,
  still no-ops `connect()` while active, still retries with backoff *while it is
  running*. The change is entirely in **who starts and stops it, and when**.
- `resolveDisplaySpeedMps` / `freshObdTelemetry` / `speedIsReal` — the speed
  priority chain is untouched. Staleness gating (`OBD_TELEMETRY_STALE_MS`)
  already makes a torn-down adapter read as stale within 3s.
- `ACTION_REFRESH` routing, `reconcileObd2Connections` name, the
  Settings-change path.

## Behaviour: before / after

| Scenario | Before | After |
| --- | --- | --- |
| Parked, engine off, app closed, service alive for auto-detect | SPP connect attempt every 5–60s forever (#96) | No connection loop. Zero attempts. |
| Two OBD vehicles, A's adapter at home (off), B's in the car being driven | A wins the singleton, retries all drive, B never dialled (#97) | Trip resolves to B → B is the only address dialled |
| Drive with the app backgrounded (phone in cradle) | polling | polling (trip active) |
| Trip auto-starts with the app backgrounded | polling (via blind seed) | polling (`beginTrip` → reconcile) |
| Open the app while parked to check the adapter | live reading (blind seed) | live reading (`uiVisible` → reconcile; pairing screen `DisposableEffect`) |
| Trip ends, app backgrounded | keeps polling forever | `endTrip` → reconcile → disconnect |
| Car power-cycle mid-drive (adapter drops link key) | forever-retry re-dials | trip still active → reconcile keeps a loop running → re-dials on the same backoff |
| Manual trip start, no mapped BT vehicle, one adapter configured | polling (blind seed) | polling (sole-adapter fallback) |
| Manual trip start, no mapped BT vehicle, two adapters configured | first-iterated wins (#97) | no OBD — cannot tell which vehicle (documented limit) |

## Known limits after this change

- **Manual trip + multiple configured adapters + no mapped BT vehicle
  connected** → no OBD, because the driven vehicle is unknown. The mapped-device
  auto-detect path (the normal case) always resolves a vehicle. A rider who
  only ever starts trips manually and has 2+ OBD vehicles would need to connect
  the vehicle's auto-detect device, or pair via the OBD2 screen. Narrow;
  documented, not fixed.
- **Service killed and restarted mid-trip**: `reconcile` runs on the restart's
  `onStartCommand`, so OBD recovers *if* trip state survives the restart. Trip
  persistence across a service kill is pre-existing behaviour, out of scope
  here.
- **Two mapped vehicles both connected, both with adapters** (helmet + car
  radio both up): `resolvedVehicle()` picks one by `MODE_PRIORITY`, same tie
  the trip mode already breaks. `linkedAddress` exposes which won. Unchanged
  singleton ambiguity — not introduced here.

## Testing

`Obd2ConnectionTest` covers only pure stream logic; there is no Robolectric or
instrumented source set (same carve-out as #61/#62). The new logic is:

- **`desiredObd2Address()` — pure, unit-testable.** Extract it so it takes its
  inputs as parameters (trip active, uiVisible, `connectedVehicles`,
  `vehicleDevices`) or test it through a thin seam. A `test_*.kt` under
  `app/src/test/.../tracking/` with `assert`-style cases:
  - parked + app closed + no trip → null
  - trip active, resolved vehicle has an adapter → that address
  - trip active, resolved vehicle has none, one adapter configured → that one
  - trip active, resolved vehicle has none, two configured → null
  - no trip, app visible, connected mapped vehicle has an adapter → that address
  - no trip, app visible, no connected mapped vehicle → null
  This is the one runnable check the reconcile logic leaves behind.
- **`reconcileObd2Connections()` wiring** (the `connect`/`disconnect` calls) and
  the `DisposableEffect`: verified by GPS replay per `detour-gps-replay`
  (confirms the trip-active path still drives the speed chain and does not
  regress the GPS fallback) plus manual on-device checks — no physical adapter
  this session, flagged unverified-live, not silently skipped:
  1. app open, parked → loop starts; background the app → loop stops within one
     `ACTION_REFRESH`.
  2. start a trip → loop starts regardless of app state; stop the trip → loop
     stops.
  3. pairing screen open → live reading; leave it with no trip and app
     backgrounding → loop stops.

## Commit structure

Independently revertible, per `docs/refactor/mapscreen/DECISION.md` (no
extraction sharing a commit with the behaviour change it enables):

1. Extract `resolvedVehicle()` from `resolvedMode()` — pure refactor, no
   behaviour change.
2. `desiredObd2Address()` + rewrite `reconcileObd2Connections()` + call it from
   every state edge + delete `connectConfiguredObd2Adapters()` + the two
   `TripTrackingService` read accessors. The #96/#97 fix.
3. `Obd2PairingScreen` `DisposableEffect` connect-on-enter.
4. `desiredObd2Address` unit test.
5. `versionName` bump (from `1.93.2`) + `docs/` (this spec, close #96/#97 in
   the PR body).

## Versioning

Base is `1.93.2` (current `origin/main`, post org transfer — the branch this
spec was drafted on was stale at `1.92.1`). User-visible behaviour of a shipped
feature changes (OBD2 stops polling when parked), backward compatible, no
data-format, wire-protocol, or min-OS break. That is a patch under the
`CONTRIBUTING.md` table (`1.93.2` → `1.93.3`); #96/#97 are labelled
`enhancement` and the change is a design change, which argues minor
(`1.94.0`). Decide at commit time against `CONTRIBUTING.md`'s Versioning
section.

## Verification gates

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest
./gradlew :app:lintDebug
```

# OBD2 Connection Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop `Obd2Connection` from dialling a parked/absent OBD2 adapter forever by tying its connection loop to `(trip active) OR (app UI visible)`.

**Architecture:** `Obd2Connection` is unchanged — it still owns one job and retries with backoff *while running*. The change is entirely in `TripTrackingService`: a single `desiredObd2Address()` decision, reconciled from every state edge (trip start/stop, UI visibility, Bluetooth events, settings change), replacing the unconditional `connectConfiguredObd2Adapters()` seed. `Obd2PairingScreen` gains a `DisposableEffect` to keep its live readout working without that seed.

**Tech Stack:** Kotlin, Android Service + BroadcastReceiver, Jetpack Compose, JUnit4 (`:app:testDebugUnitTest`). No Robolectric / no instrumented source set — service and Compose behaviour is verified by extracting pure functions plus GPS replay + manual on-device checks.

**Spec:** `docs/superpowers/specs/2026-09-02-obd2-connection-lifecycle-design.md`

## Global Constraints

- **Version base:** `versionName` is `1.93.2` on `origin/main`. Bump in the final task only.
- **`versionCode`:** never touched by hand — CI-stamped.
- **Commit trailers:** every commit ends with
  ```
  Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01AA6YEKTr59Gb2ZZQdwkhoo
  ```
- **No extraction shares a commit with the behaviour change it enables** (`docs/refactor/mapscreen/DECISION.md`). Task 1 (extraction) and Task 3 (behaviour) are separate commits.
- **`Obd2Connection` public API does not change.** No new state, no new method, no lifecycle change inside it.
- **`io.github.maxke24.detour`** (applicationId) and `com.jellemax.detour` (namespace) are untouched.
- Match the surrounding file's style; this repo uses **no** `derivedStateOf` / `snapshotFlow`.

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt` | The service; owns `uiVisible`, `_stats`, `connectedVehicles`, all Bluetooth receivers, and OBD reconcile | Extract `resolvedVehicle()`; add file-level `pickObd2Address()`; add `desiredObd2Address()`; rewrite `reconcileObd2Connections()`; delete `connectConfiguredObd2Adapters()`; add `destroyed` guard + `obdWantedByService()` companion accessor; call `reconcileObd2Connections()` from every state edge |
| `app/src/test/java/com/jellemax/detour/tracking/ObdConnectionTargetTest.kt` | Unit test for `pickObd2Address()` | Create |
| `app/src/main/java/com/jellemax/detour/ui/Obd2PairingScreen.kt` | OBD2 pairing + live readout screen | Add a `DisposableEffect` that connects the readout adapter on enter and tears down on exit unless the service still wants it |
| `app/build.gradle.kts` | Version | `versionName` bump (final task) |
| `docs/superpowers/specs/2026-09-02-obd2-connection-lifecycle-design.md` | Spec | Already committed (`fd040db`); no change |

---

## Task 1: Extract `resolvedVehicle()` from `resolvedMode()`

Pure refactor. `resolvedMode()` currently inlines "which connected vehicle wins"; Task 3 needs that vehicle (for its `obd2Address`). Extracting it first, in its own commit, keeps the behaviour change isolated.

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt` (the `resolvedMode()` function, currently around `:823-832`)

**Interfaces:**
- Consumes: `connectedVehicles: LinkedHashSet<String>` (instance field), `Settings.vehicleDevices.value: Map<String, Settings.VehicleDevice>`, `MODE_PRIORITY: List<TravelMode>` (companion, `= listOf(TravelMode.CAR, TravelMode.MOTO)`).
- Produces: `private fun resolvedVehicle(): Settings.VehicleDevice?` — the connected mapped vehicle whose mode ranks highest in `MODE_PRIORITY`, or null when none is connected. `resolvedMode(): TravelMode` keeps its exact current signature and result.

- [ ] **Step 1: Locate `resolvedMode()`**

Run: `grep -n "private fun resolvedMode" app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt`

Current body (verify it matches before editing — the KDoc above it is unchanged):

```kotlin
    private fun resolvedMode(): TravelMode {
        val map = Settings.vehicleDevices.value
        // The heaviest vehicle connected wins, not the last to connect: the
        // helmet intercom and the car radio can both be up while the bike
        // sits in the garage.
        connectedVehicles.mapNotNull { map[it]?.mode }
            .maxByOrNull { MODE_PRIORITY.indexOf(it) }
            ?.let { return it }
        return Settings.tripMode.value
    }
```

- [ ] **Step 2: Replace with the extracted pair**

```kotlin
    /** The connected mapped vehicle that classifies the trip. The heaviest
     *  mode wins (see [MODE_PRIORITY]), not the last to connect: the helmet
     *  intercom and the car radio can both be up while the bike sits in the
     *  garage. Null when no mapped device is connected. */
    private fun resolvedVehicle(): Settings.VehicleDevice? {
        val map = Settings.vehicleDevices.value
        return connectedVehicles.mapNotNull { map[it] }
            .maxByOrNull { MODE_PRIORITY.indexOf(it.mode) }
    }

    private fun resolvedMode(): TravelMode =
        resolvedVehicle()?.mode ?: Settings.tripMode.value
```

Why this is behaviour-identical: the original maps each connected vehicle to its `mode` then takes the `maxByOrNull { MODE_PRIORITY.indexOf(it) }`; the new form takes the same `maxByOrNull` over the vehicles keyed on `MODE_PRIORITY.indexOf(it.mode)` and reads `.mode` off the winner. `maxByOrNull` returns the first element holding the max key in both, and an unmapped mode yields `indexOf == -1` in both. The `?: Settings.tripMode.value` fallback is unchanged.

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the existing unit tests**

Run: `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all existing tests pass. No test exercises `resolvedMode()` directly; this step guards against a compile-clean typo elsewhere.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt
git commit -m "$(cat <<'EOF'
refactor(trip): extract resolvedVehicle() from resolvedMode()

No behaviour change: resolvedMode() returns exactly what it did. Task
needs the winning VehicleDevice, not just its mode, to reach its
obd2Address.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AA6YEKTr59Gb2ZZQdwkhoo
EOF
)"
```

---

## Task 2: `pickObd2Address()` pure decision + test

The connect/disconnect decision, as a file-level pure function — same pattern as the existing `obdSpeedMpsFrom` (`TripTrackingService.kt:1773`, tested in `ObdSpeedResolutionTest.kt`). Written and tested before it is wired in (Task 3).

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt` (add a file-level `fun` after `obdSpeedMpsFrom`, at end of file)
- Create: `app/src/test/java/com/jellemax/detour/tracking/ObdConnectionTargetTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  internal fun pickObd2Address(
      tripActive: Boolean,
      uiVisible: Boolean,
      tripVehicleObd2Address: String?,   // resolvedVehicle()?.obd2Address
      connectedObd2Addresses: List<String>,  // obd2Address of each connectedVehicles entry, in order, nulls dropped
      configuredObd2Addresses: List<String>, // every vehicle's obd2Address, nulls dropped
  ): String?
  ```
  Returns the adapter address the connection loop should be on, or null to stay disconnected.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/jellemax/detour/tracking/ObdConnectionTargetTest.kt`:

```kotlin
package com.jellemax.detour.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [pickObd2Address] is the connect/disconnect decision for the OBD2 adapter,
 * pulled out of [TripTrackingService] so it is testable without a service or a
 * Bluetooth stack (maxke24/Detour#96, #97). The wiring that gathers its inputs
 * and acts on its result is verified by GPS replay + manual on-device checks.
 */
class ObdConnectionTargetTest {

    @Test
    fun parkedWithAppClosedAndNoTripPollsNothing() {
        assertNull(
            pickObd2Address(
                tripActive = false,
                uiVisible = false,
                tripVehicleObd2Address = "AA:BB",
                connectedObd2Addresses = listOf("AA:BB"),
                configuredObd2Addresses = listOf("AA:BB"),
            ),
        )
    }

    @Test
    fun tripActivePicksTheDrivenVehiclesAdapter() {
        assertEquals(
            "CAR:AD",
            pickObd2Address(
                tripActive = true,
                uiVisible = false,
                tripVehicleObd2Address = "CAR:AD",
                connectedObd2Addresses = listOf("CAR:AD", "BIKE:AD"),
                configuredObd2Addresses = listOf("CAR:AD", "BIKE:AD"),
            ),
        )
    }

    @Test
    fun tripActiveWithNoResolvedAdapterFallsBackToTheSoleConfiguredOne() {
        assertEquals(
            "ONLY:AD",
            pickObd2Address(
                tripActive = true,
                uiVisible = false,
                tripVehicleObd2Address = null,
                connectedObd2Addresses = emptyList(),
                configuredObd2Addresses = listOf("ONLY:AD"),
            ),
        )
    }

    @Test
    fun tripActiveWithNoResolvedAdapterAndTwoConfiguredIsAmbiguousSoNothing() {
        assertNull(
            pickObd2Address(
                tripActive = true,
                uiVisible = false,
                tripVehicleObd2Address = null,
                connectedObd2Addresses = emptyList(),
                configuredObd2Addresses = listOf("A:AD", "B:AD"),
            ),
        )
    }

    @Test
    fun appVisibleNoTripPollsTheConnectedVehiclesAdapter() {
        assertEquals(
            "NEAR:AD",
            pickObd2Address(
                tripActive = false,
                uiVisible = true,
                tripVehicleObd2Address = null,
                connectedObd2Addresses = listOf("NEAR:AD"),
                configuredObd2Addresses = listOf("NEAR:AD", "FAR:AD"),
            ),
        )
    }

    @Test
    fun appVisibleNoTripNoConnectedVehiclePollsNothing() {
        assertNull(
            pickObd2Address(
                tripActive = false,
                uiVisible = true,
                tripVehicleObd2Address = null,
                connectedObd2Addresses = emptyList(),
                configuredObd2Addresses = listOf("FAR:AD"),
            ),
        )
    }

    @Test
    fun tripActiveTakesPriorityOverAConnectedNonDrivenAdapter() {
        // Bike adapter is connected, but the trip resolved to the car (no
        // adapter). Ambiguity rule wins: not the bike's.
        assertNull(
            pickObd2Address(
                tripActive = true,
                uiVisible = true,
                tripVehicleObd2Address = null,
                connectedObd2Addresses = listOf("BIKE:AD"),
                configuredObd2Addresses = listOf("BIKE:AD", "CAR:AD"),
            ),
        )
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.jellemax.detour.tracking.ObdConnectionTargetTest"`
Expected: FAIL — `pickObd2Address` unresolved reference.

- [ ] **Step 3: Implement `pickObd2Address`**

In `TripTrackingService.kt`, immediately after the `obdSpeedMpsFrom` function (end of file, after the closing `}` of the class), add:

```kotlin

/** Which OBD2 adapter the connection loop should be on right now, or null to
 *  stay disconnected. Pure so the connect/disconnect decision is testable
 *  without a service; the caller ([TripTrackingService.desiredObd2Address])
 *  gathers the inputs and acts on the result.
 *
 *  - nothing while parked with the app closed and no trip running (#96);
 *  - a running trip polls its resolved vehicle's adapter — that is the vehicle
 *    you are in, so the one-connection singleton never has to choose (#97) —
 *    or, if that vehicle has none, the sole configured adapter if there is
 *    exactly one; two-or-more is ambiguous, so nothing;
 *  - otherwise, while the UI is up, the first connected mapped vehicle that
 *    has an adapter. */
internal fun pickObd2Address(
    tripActive: Boolean,
    uiVisible: Boolean,
    tripVehicleObd2Address: String?,
    connectedObd2Addresses: List<String>,
    configuredObd2Addresses: List<String>,
): String? {
    if (!tripActive && !uiVisible) return null
    if (tripActive) {
        return tripVehicleObd2Address ?: configuredObd2Addresses.singleOrNull()
    }
    return connectedObd2Addresses.firstOrNull()
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.jellemax.detour.tracking.ObdConnectionTargetTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt app/src/test/java/com/jellemax/detour/tracking/ObdConnectionTargetTest.kt
git commit -m "$(cat <<'EOF'
feat(trip): pickObd2Address() — the OBD2 connect/disconnect decision

Pure function + test. Not wired in yet. Decides which adapter (if any)
the connection loop should hold, from trip state, UI visibility and the
configured/connected vehicle sets.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AA6YEKTr59Gb2ZZQdwkhoo
EOF
)"
```

---

## Task 3: Wire the decision into `TripTrackingService`

The behaviour change. `reconcileObd2Connections()` becomes the single reconciler, driven by `pickObd2Address` via a new `desiredObd2Address()`; the unconditional `connectConfiguredObd2Adapters()` seed is deleted; every state edge calls the reconciler; `onDestroy` is guarded against a re-dial.

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt`

**Interfaces:**
- Consumes: `pickObd2Address(...)` (Task 2), `resolvedVehicle()` (Task 1), `Obd2Connection.linkedAddress: StateFlow<String?>`, `Obd2Connection.connect(Context, String)`, `Obd2Connection.disconnect()`, `connectedVehicles`, `_stats`, `uiVisible` (all already in the file).
- Produces:
  - `private fun desiredObd2Address(): String?`
  - `private fun reconcileObd2Connections()` — rewritten body, same name/signature (already called from `ACTION_REFRESH` at `:920`).
  - `TripTrackingService.Companion.obdWantedByService(): Boolean` — used by Task 4.
  - `@Volatile private var destroyed = false` (instance field).

- [ ] **Step 1: Add `desiredObd2Address()` and rewrite `reconcileObd2Connections()`**

Replace the whole current `reconcileObd2Connections()` (the KDoc + body, currently `:753-766`):

```kotlin
    /** Config changed in Settings (ACTION_REFRESH) — a vehicle or its OBD
     *  address was added or removed. Drop a live OBD link whose address is no
     *  longer mapped to any vehicle before dialing the current set: otherwise
     *  [Obd2Connection]'s retry loop keeps hammering an adapter the user just
     *  unpaired every 5–60s for the life of the service. The pairing screen's
     *  Forget button already disconnects for this reason; vehicle removal
     *  routes here instead. */
    private fun reconcileObd2Connections() {
        val configured = Settings.vehicleDevices.value.values.mapNotNull { it.obd2Address }.toSet()
        Obd2Connection.linkedAddress.value?.let {
            if (it !in configured) Obd2Connection.disconnect()
        }
        connectConfiguredObd2Adapters()
    }
```

with:

```kotlin
    /** Which OBD2 adapter [Obd2Connection] should be on, or null to stay
     *  disconnected. See [pickObd2Address] for the rules. */
    private fun desiredObd2Address(): String? {
        val map = Settings.vehicleDevices.value
        return pickObd2Address(
            tripActive = _stats.value != null,
            uiVisible = uiVisible,
            tripVehicleObd2Address = resolvedVehicle()?.obd2Address,
            connectedObd2Addresses = connectedVehicles.mapNotNull { map[it]?.obd2Address },
            configuredObd2Addresses = map.values.mapNotNull { it.obd2Address },
        )
    }

    /** Bring [Obd2Connection] in line with [desiredObd2Address]: drop a link to
     *  the wrong adapter (or any link at all when none is wanted), open one to
     *  the right adapter when idle. Called from every edge that can change the
     *  answer — trip start/stop, UI visibility ([ACTION_REFRESH]), a Bluetooth
     *  connect/disconnect/toggle, and a Settings change. Replaces the old
     *  unconditional dial-every-configured-adapter seed: a parked adapter is no
     *  longer retried around the clock (#96), and only the vehicle being driven
     *  is ever dialled, so an absent adapter can't block a present one (#97). */
    private fun reconcileObd2Connections() {
        if (destroyed) return
        val target = desiredObd2Address()
        if (Obd2Connection.linkedAddress.value.let { it != null && it != target }) {
            Obd2Connection.disconnect()
        }
        if (target != null && Obd2Connection.linkedAddress.value == null) {
            Obd2Connection.connect(applicationContext, target)
        }
    }
```

- [ ] **Step 2: Delete `connectConfiguredObd2Adapters()`**

Delete the whole function (KDoc + body, currently `:740-751`):

```kotlin
    /** No ACL_CONNECTED-equivalent seed exists for OBD2: an ELM327 SPP device
     *  shows up in neither the HEADSET nor A2DP profiles [seedConnectedVehicles]
     *  queries, so "already connected" can't be detected directly. Attempt every
     *  configured adapter unconditionally instead — [Obd2Connection.connect] is a
     *  no-op if a loop is already running, and its own backoff/retry handles an
     *  adapter that isn't there yet. Called on the initial watch seed and again
     *  whenever Bluetooth is toggled back on (see [btStateReceiver]). */
    private fun connectConfiguredObd2Adapters() {
        Settings.vehicleDevices.value.values.forEach { vehicle ->
            vehicle.obd2Address?.let { Obd2Connection.connect(applicationContext, it) }
        }
    }
```

- [ ] **Step 3: Add the `destroyed` field and companion accessor**

Add the instance field next to the other `@Volatile private var` fields (near `:411`):

```kotlin
    @Volatile private var destroyed = false
```

Add to the companion object, next to `setUiVisible` (near `:281`):

```kotlin
        /** True when [Obd2Connection] should be held open for something other
         *  than the OBD2 pairing screen's own readout — a running trip or a
         *  visible map. The pairing screen reads this to decide whether to tear
         *  its link down on exit. */
        fun obdWantedByService(): Boolean = uiVisible || _stats.value != null
```

- [ ] **Step 4: Call `reconcileObd2Connections()` from the remaining edges**

**(a) `ensureBluetoothWatch()`** (`:722-738`) — replace the trailing `connectConfiguredObd2Adapters()` (`:737`) with `reconcileObd2Connections()`:

```kotlin
        btRegistered = true
        seedConnectedVehicles()
        reconcileObd2Connections()
    }
```

**(b) `btReceiver`** (`:654-679`) — the `ACTION_ACL_CONNECTED` branch currently ends with an inline `Obd2Connection.connect(...)`; the `ACTION_ACL_DISCONNECTED` branch has none. Replace so both end with a reconcile:

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
                    reconcileObd2Connections()
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    if (connectedVehicles.remove(address)) refreshTripMode()
                    reconcileObd2Connections()
                }
            }
        }
    }
```

Note the removed comment block (`// Obd2Connection tracks only "an" OBD2 link...`) — its "needs a public API change" caveat is now resolved by the reconcile approach; do not carry it forward.

**(c) `btStateReceiver`** (`:684-704`) — the `STATE_ON` branch calls `connectConfiguredObd2Adapters()` (`:700`); replace with `reconcileObd2Connections()`. The `STATE_TURNING_OFF, STATE_OFF` branch keeps its existing `Obd2Connection.disconnect()`:

```kotlin
                BluetoothAdapter.STATE_ON -> {
                    seedConnectedVehicles()
                    // STATE_OFF called Obd2Connection.disconnect(); nothing
                    // re-dials a phone-initiated SPP link on its own. Reconcile
                    // picks it back up if a trip or the UI still wants it.
                    reconcileObd2Connections()
                }
```

**(d) `beginTrip()`** (`:930-983`) — after the mode is applied to `_stats` (`:979`), add:

```kotlin
        _stats.value = _stats.value?.copy(mode = mode)
        reconcileObd2Connections()
        ensureLocationUpdates()
```

**(e) `endTrip()`** — after `_stats.value = null` (`:1086`), add:

```kotlin
        _stats.value = null
        reconcileObd2Connections()
```

**(f) `refreshTripMode()`** (`:836-844`) — the async `seedConnectedVehicles()` commit lands here and can change the resolved vehicle even when the mode did not. Add an unconditional reconcile at the end, outside the existing `if`:

```kotlin
    private fun refreshTripMode() {
        val mode = resolvedMode()
        if (_stats.value != null && _stats.value?.mode != mode) {
            _stats.update { it?.copy(mode = mode) }
            stopMotionSensors()
            startMotionSensors(mode)
            updateNotification()
        }
        reconcileObd2Connections()
    }
```

**(g) `onDestroy()`** (`:1664`) — set `destroyed` first thing, so the `endTrip()` call later in `onDestroy` cannot re-dial through its new reconcile before the existing `Obd2Connection.disconnect()` runs:

```kotlin
    override fun onDestroy() {
        destroyed = true
        if (::fusedClient.isInitialized) {
```

Leave the existing `Obd2Connection.disconnect()` at `:1673` where it is.

- [ ] **Step 5: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. If it fails on an unresolved `connectConfiguredObd2Adapters` — grep for a missed call site: `grep -n connectConfiguredObd2Adapters app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt` must return nothing.

- [ ] **Step 6: Run all unit tests**

Run: `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. `ObdConnectionTargetTest`, `ObdSpeedResolutionTest`, `Obd2ConnectionTest` all pass.

- [ ] **Step 7: Tier-0 greps for the touched service area**

Run: `.claude/skills/detour-staged-refactor/scripts/tier0-greps.sh origin/main app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt`
Expected: no new `CoroutineScope` in the file, no `Dispatchers` added to `shared/commonMain`. This task adds none.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt
git commit -m "$(cat <<'EOF'
fix(trip): hold the OBD2 link only during a trip or with the UI up (#96, #97)

reconcileObd2Connections() now dials the adapter pickObd2Address() names
and drops any other link, called from every state edge (trip start/stop,
UI visibility, Bluetooth connect/disconnect/toggle, settings change).
Deletes the unconditional connectConfiguredObd2Adapters() seed.

- #96: parked + app closed + no trip => no connection loop at all, so no
  around-the-clock SPP dial to a powered-off adapter.
- #97: a running trip dials only its resolved vehicle's adapter, so an
  absent adapter can no longer win the one-connection singleton and
  starve the present one.

onDestroy sets `destroyed` so endTrip()'s reconcile can't re-dial during
teardown.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AA6YEKTr59Gb2ZZQdwkhoo
EOF
)"
```

---

## Task 4: Keep the pairing screen's live readout working

`Obd2PairingScreen` showed a live reading only because the deleted seed kept a loop running. Navigating to it disposes `MapScreen`, which sets `uiVisible = false` (`MapScreen.kt:406`), so `desiredObd2Address()` returns null there. Add a `DisposableEffect` that opens the readout link on enter and tears it down on exit — unless the service still wants it.

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/Obd2PairingScreen.kt`

**Interfaces:**
- Consumes: `Obd2Connection.connect/disconnect/linkedAddress`, `TripTrackingService.obdWantedByService()` (Task 3), `mapping: Map<String, Settings.VehicleDevice>` and `context` (already in scope, `:45-46`).

- [ ] **Step 1: Add the import**

In the import block, add (keep alphabetical order — it sorts just before `LaunchedEffect`):

```kotlin
import androidx.compose.runtime.DisposableEffect
```

And add:

```kotlin
import com.jellemax.detour.tracking.TripTrackingService
```

- [ ] **Step 2: Add the `DisposableEffect`**

Right after the existing `LaunchedEffect(Unit)` 1-second tick block (`:56-61`), add:

```kotlin
    // The service only holds the OBD2 link during a trip or with the map up
    // (see reconcileObd2Connections); this screen is neither, so open the
    // readout link ourselves while it is on screen. `readoutAddress` is the
    // same adapter the "Retry now" button targets. On exit, hand back to the
    // service — but leave a link it still wants (a trip is running) alone.
    val readoutAddress = mapping.values.firstNotNullOfOrNull { it.obd2Address }
    DisposableEffect(readoutAddress) {
        if (readoutAddress != null && Obd2Connection.linkedAddress.value == null) {
            Obd2Connection.connect(context.applicationContext, readoutAddress)
        }
        onDispose {
            if (Obd2Connection.linkedAddress.value == readoutAddress &&
                !TripTrackingService.obdWantedByService()
            ) {
                Obd2Connection.disconnect()
            }
        }
    }
```

Key list is `readoutAddress` alone: assigning an adapter to a vehicle (a "Use <device>" tap) changes it, and the effect re-points the readout — the tap handler's own `disconnect()` + `connect()` (`:176-177`) already covers the immediate switch, and the re-keyed effect converges to the same state on the next composition. Do not key on `mapping` (it changes on every unrelated vehicle edit) or `Unit` (a re-assign would then never re-point).

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Tier-0 greps**

Run: `.claude/skills/detour-compose-state-hazards/scripts/check-preconditions.sh`
Expected: PASS on all five (this task touches neither `MapScreen` nor `rememberUpdatedState`/`withFrameNanos` counts).

Run: `.claude/skills/detour-staged-refactor/scripts/tier0-greps.sh origin/main app/src/main/java/com/jellemax/detour/ui/Obd2PairingScreen.kt`
Expected: the new `DisposableEffect` line is printed for review (a key-list change must be read, per compose-state-hazards §1); the connect on enter and `disconnect()` in `onDispose` move together (§2b), so no removal is missing.

- [ ] **Step 5: Run all unit tests**

Run: `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/Obd2PairingScreen.kt
git commit -m "$(cat <<'EOF'
fix(obd2): pairing screen opens its own readout link

The deleted connectConfiguredObd2Adapters() seed used to keep a loop
running; without it, navigating to this screen (which disposes MapScreen
and clears uiVisible) left the readout dead. A DisposableEffect now opens
the link on enter and hands back to the service on exit, leaving a link
the service still wants (a running trip) untouched.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AA6YEKTr59Gb2ZZQdwkhoo
EOF
)"
```

---

## Task 5: Version bump + close the issues

**Files:**
- Modify: `app/build.gradle.kts` (`versionName`, `:76`)
- Modify: `CHANGELOG.md` if one exists at repo root (CI enforces an entry on a bump — check `.github/workflows` output from a prior run, or `ls CHANGELOG.md`)

**Interfaces:** none.

- [ ] **Step 1: Decide the bump**

Read `CONTRIBUTING.md`'s Versioning section. The change alters user-visible behaviour of a shipped feature (OBD2 stops polling when parked) but breaks no data format, wire protocol, or min OS. `#96`/`#97` are labelled `enhancement` and the spec calls this a design change → **minor**: `1.93.2` → `1.94.0`. If CONTRIBUTING's wording points to patch instead, use `1.93.3` and note why in the commit.

- [ ] **Step 2: Bump `versionName`**

`app/build.gradle.kts:76` — `versionName = "1.93.2"` → `versionName = "1.94.0"`.

- [ ] **Step 3: CHANGELOG entry (only if `CHANGELOG.md` exists at repo root)**

Run: `ls CHANGELOG.md 2>/dev/null && head -20 CHANGELOG.md`

If it exists, add an entry matching the existing format, e.g.:

```markdown
## 1.94.0

- OBD2 adapter: the connection is now held only while a trip is running or the
  app is open, instead of being retried around the clock. Fixes idle battery
  drain from a parked adapter (#96) and an absent adapter blocking a present
  one on a two-vehicle setup (#97).
```

- [ ] **Step 4: Compile + full test**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :shared:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts CHANGELOG.md
git commit -m "$(cat <<'EOF'
chore: versionName 1.93.2 -> 1.94.0 for the OBD2 lifecycle change

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AA6YEKTr59Gb2ZZQdwkhoo
EOF
)"
```

(`CHANGELOG.md` drops out of the `git add` harmlessly if it does not exist.)

---

## Verification (before opening the PR)

Automated — all must pass:

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:assembleDebug :app:assembleRelease   # R8 catches what debug does not
./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest
./gradlew :app:lintDebug
```

Behavioural — `Obd2Connection` cannot be unit-tested here and no physical adapter is available this session; these are **flagged unverified-live**, not skipped:

1. **GPS replay, no adapter** (`detour-gps-replay`, route (ii)): replay a drive. Confirm the speed HUD, camera follow and trip stats behave exactly as on `origin/main` — `desiredObd2Address()` returns null with no adapter configured, so `Obd2Connection` is never dialled and the GPS path is unchanged. Report the two observations + the route file.
2. **Manual, with an adapter** (hand to a tester):
   - App open, parked, engine off → `Obd2PairingScreen` shows a live reading; background the app (no trip) → within one `ACTION_REFRESH` the link drops (`connectionState` → `DISCONNECTED`).
   - Start a trip (manual or auto) → link comes up regardless of app foreground/background; stop the trip → link drops.
   - Two vehicles configured, only one adapter powered → that adapter connects on the drive; the absent one is never dialled (check logcat `Obd2Connection` tag shows no attempts to the absent address).
   - Park with the app closed for 10 min → logcat shows no repeated SPP connect attempts (#96).

## Self-Review notes

- **Spec coverage:** every spec section maps to a task — `resolvedVehicle` extraction (T1), `desiredObd2Address`/`pickObd2Address` (T2), `reconcileObd2Connections` rewrite + all call sites + `connectConfiguredObd2Adapters` deletion + `destroyed` guard (T3), pairing-screen `DisposableEffect` + `obdWantedByService` accessor (T3/T4), testing (T2 + Verification), commit structure (task split), versioning (T5).
- **Type consistency:** `pickObd2Address` signature is identical in the Task 2 interface block, the test, and the Task 3 call. `resolvedVehicle(): Settings.VehicleDevice?` matches between T1 and its T3 use. `obdWantedByService(): Boolean` matches between T3 (definition) and T4 (call).
- **Known deviation from the spec:** the spec's `desiredObd2Address` sketch let a trip-active-but-ambiguous case fall through to "any connected adapter"; this plan returns null there instead (matches the spec's own before/after table row "Manual trip start, no mapped BT vehicle, two adapters configured → no OBD"). The pure test `tripActiveTakesPriorityOverAConnectedNonDrivenAdapter` pins it.

# Issue 90 — Parked Dormancy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** While the phone is parked (no trip, no convoy, map backgrounded, stationary), Detour runs no foreground service and shows no notification; an OS geofence around the parked position wakes trip tracking when the rider leaves.

**Architecture:** `TripTrackingService` gains a stop path driven by a pure `dormancyDecision()` function. When it decides to go dormant it arms one `GEOFENCE_TRANSITION_EXIT` geofence (`ParkGeofence`) and calls `stopSelf()`. A manifest `GeofenceWakeReceiver` restarts the service on exit — legal from the background under Android's geofencing FGS-start exemption. The circle position/arrive-depart loop that lived inside the service moves its parked case to a 15-minute `CircleSyncWorker` (WorkManager); the service keeps its own 2-minute loop for when it is alive.

**Tech Stack:** Kotlin, Android SDK 34+, `com.google.android.gms:play-services-location:21.3.0` (already a dep — provides `GeofencingClient`), `kotlinx-coroutines-play-services:1.8.1` (already a dep — provides `.await()`), `androidx.work:work-runtime-ktx` (NEW), JUnit4 (`app/src/test/`, plain JVM, no Robolectric).

**Spec:** `docs/superpowers/specs/2026-09-03-issue-90-parked-dormancy-design.md`

## Global Constraints

- **Versioning:** bump `versionName` in `app/build.gradle.kts` from `1.97.1` to `1.98.0` (minor — new feature, backward compatible, no wire/data/OS break). Never touch `versionCode` (CI-stamped).
- **No `shared/` changes.** `CirclePresence.tick()` is already the shared seam; only Android code moves. Do not edit anything under `shared/`.
- **PR, never push to `main`.** Work is on branch `feat/issue-90-parked-dormancy`.
- **Commit message trailer** on every commit:
  ```
  Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01GwbRHp9GZQp7F8h825tkAk
  ```
- **Test placement:** pure decision logic goes in a top-level function in package `com.jellemax.detour.tracking` (same package as the service, own file), tested in `app/src/test/java/com/jellemax/detour/tracking/`. This is the established pattern — see `pickObd2Address` / `ObdConnectionTargetTest`, `cappedFixDtSec` / `CappedFixDtSecTest`.
- **Test style:** JUnit4, `import org.junit.Test`, `import org.junit.Assert.*`. Class KDoc states what contract it covers and that the wiring around it is verified by GPS replay / manual on-device checks. Backtick sentence test names or camelCase, both are in the tree.
- **`app/src/test/` runs on every PR** via `.github/workflows/build.yml` (`:app:testDebugUnitTest`). Not path-gated.

---

## File Structure

**New files:**

| File | Responsibility |
|---|---|
| `app/src/main/java/com/jellemax/detour/tracking/Dormancy.kt` | Pure `dormancyDecision(...)` — the 5-boolean truth table deciding STAY_ALIVE / STOP_WITH_GEOFENCE / STOP_BARE. No Android imports. |
| `app/src/test/java/com/jellemax/detour/tracking/DormancyTest.kt` | Truth-table coverage of `dormancyDecision`. |
| `app/src/main/java/com/jellemax/detour/tracking/ParkGeofence.kt` | `object` wrapping `GeofencingClient`: `arm(ctx, lat, lon)` / `disarm(ctx)`. One geofence, id `"park"`, EXIT-only, radius `RADIUS_M = 150f`. |
| `app/src/main/java/com/jellemax/detour/tracking/GeofenceWakeReceiver.kt` | `BroadcastReceiver`: on EXIT → disarm + `TripTrackingService.startMonitoring`. |
| `app/src/main/java/com/jellemax/detour/notif/CircleSyncWorker.kt` | `CoroutineWorker`: one-shot fix → `CirclePresence.tick(...)`. Companion `schedule(context)`. |

**Modified files:**

| File | Change |
|---|---|
| `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt` | Call `maybeGoDormant()` after mode-affecting events; implement the three decision branches; gate + allow unregister of activity-recognition transitions; disarm `ParkGeofence` on every start. |
| `app/src/main/java/com/jellemax/detour/tracking/BootReceiver.kt` | Also `CircleSyncWorker.schedule(context)`. |
| `app/src/main/java/com/jellemax/detour/MainActivity.kt` | Also `CircleSyncWorker.schedule(this)` next to the `CircleNotifyService.refresh` call. |
| `app/src/main/AndroidManifest.xml` | Register `GeofenceWakeReceiver` (`exported="false"`). |
| `app/build.gradle.kts` | Add `androidx.work:work-runtime-ktx:2.9.1`; bump `versionName`. |
| `docs/PLAY_LOCATION_DECLARATION.md` | Lines 254-258: Geofencing → ticked; rewrite the "registers no geofences" paragraph. |
| `docs/CIRCLES_AND_CONVOYS.md` | Line 286: "neither registers OS geofences" → corrected. |

---

## Task 1: `dormancyDecision` pure function

**Files:**
- Create: `app/src/main/java/com/jellemax/detour/tracking/Dormancy.kt`
- Test: `app/src/test/java/com/jellemax/detour/tracking/DormancyTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  ```kotlin
  enum class DormancyDecision { STAY_ALIVE, STOP_WITH_GEOFENCE, STOP_BARE }
  fun dormancyDecision(
      autoDetect: Boolean,
      tripActive: Boolean,
      convoyActive: Boolean,
      uiVisible: Boolean,
      stationary: Boolean,
  ): DormancyDecision
  ```

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/jellemax/detour/tracking/DormancyTest.kt`:

```kotlin
package com.jellemax.detour.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [dormancyDecision] is the stop-path decision pulled out of
 * [TripTrackingService] (issue #90): given what is currently active, does the
 * always-on tracker stay foreground, stop and leave an OS geofence to wake it,
 * or stop outright with nothing armed. The wiring that feeds it live state and
 * acts on the result — arming the geofence, calling stopSelf — is verified by
 * `adb shell dumpsys activity services` and a GPS replay, not here.
 */
class DormancyTest {

    @Test fun `a running trip always stays alive`() {
        for (autoDetect in listOf(true, false)) {
            assertEquals(
                DormancyDecision.STAY_ALIVE,
                dormancyDecision(autoDetect, tripActive = true, convoyActive = false, uiVisible = false, stationary = true),
            )
        }
    }

    @Test fun `a joined convoy stays alive even parked with the map closed`() {
        assertEquals(
            DormancyDecision.STAY_ALIVE,
            dormancyDecision(autoDetect = true, tripActive = false, convoyActive = true, uiVisible = false, stationary = true),
        )
    }

    @Test fun `a visible map stays alive even with auto-detect off`() {
        assertEquals(
            DormancyDecision.STAY_ALIVE,
            dormancyDecision(autoDetect = false, tripActive = false, convoyActive = false, uiVisible = true, stationary = true),
        )
    }

    @Test fun `auto-detect off and nothing active stops bare - no geofence`() {
        // AC 2: "no service and no geofence registered at all". Off beats
        // stationary: with nothing to detect, IDLE-mode watching is pointless.
        for (stationary in listOf(true, false)) {
            assertEquals(
                DormancyDecision.STOP_BARE,
                dormancyDecision(autoDetect = false, tripActive = false, convoyActive = false, uiVisible = false, stationary = stationary),
            )
        }
    }

    @Test fun `auto-detect on, stationary, nothing active - stop and arm the geofence`() {
        assertEquals(
            DormancyDecision.STOP_WITH_GEOFENCE,
            dormancyDecision(autoDetect = true, tripActive = false, convoyActive = false, uiVisible = false, stationary = true),
        )
    }

    @Test fun `auto-detect on but still moving on foot stays alive to watch for a drive`() {
        assertEquals(
            DormancyDecision.STAY_ALIVE,
            dormancyDecision(autoDetect = true, tripActive = false, convoyActive = false, uiVisible = false, stationary = false),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.jellemax.detour.tracking.DormancyTest"`
Expected: FAIL — `Dormancy.kt` does not exist, `dormancyDecision` unresolved.

- [ ] **Step 3: Write minimal implementation**

`app/src/main/java/com/jellemax/detour/tracking/Dormancy.kt`:

```kotlin
package com.jellemax.detour.tracking

/** What [TripTrackingService] should do with itself once nothing needs it
 *  foreground — see [dormancyDecision] and issue #90. */
enum class DormancyDecision {
    /** Keep the foreground service; request location for the resolved mode. */
    STAY_ALIVE,
    /** Register a wake geofence at the parked position, then stop. */
    STOP_WITH_GEOFENCE,
    /** Stop outright: nothing to detect, nothing to wake for. */
    STOP_BARE,
}

/**
 * The stop-path decision. Ordered so that anything actively using the service
 * wins first; then auto-detect being off ends the service unconditionally
 * (issue #90 AC 2 — "no service and no geofence registered at all"); then a
 * stationary phone with auto-detect on parks behind a geofence; otherwise the
 * rider is moving on foot and the service stays up in IDLE to catch a drive
 * starting.
 */
fun dormancyDecision(
    autoDetect: Boolean,
    tripActive: Boolean,
    convoyActive: Boolean,
    uiVisible: Boolean,
    stationary: Boolean,
): DormancyDecision = when {
    tripActive || convoyActive || uiVisible -> DormancyDecision.STAY_ALIVE
    !autoDetect -> DormancyDecision.STOP_BARE
    stationary -> DormancyDecision.STOP_WITH_GEOFENCE
    else -> DormancyDecision.STAY_ALIVE
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.jellemax.detour.tracking.DormancyTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/tracking/Dormancy.kt \
        app/src/test/java/com/jellemax/detour/tracking/DormancyTest.kt
git commit -m "$(cat <<'EOF'
feat(tracking): dormancyDecision — the stop-path truth table for #90

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GwbRHp9GZQp7F8h825tkAk
EOF
)"
```

---

## Task 2: `ParkGeofence` + `GeofenceWakeReceiver` + manifest

No unit test — both are thin wrappers over `GeofencingClient` and `BroadcastReceiver` with no pure logic, and `app/` has no Robolectric (same call the tree already makes for `ParkGeofence`-shaped code — see `ObdConnectionTargetTest`'s KDoc). Deliverable is verified by `adb` in Task 4. This task ends when the app compiles with the receiver registered.

**Files:**
- Create: `app/src/main/java/com/jellemax/detour/tracking/ParkGeofence.kt`
- Create: `app/src/main/java/com/jellemax/detour/tracking/GeofenceWakeReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml` (add `<receiver>` beside `.tracking.BootReceiver`, around line 142)

**Interfaces:**
- Consumes: `TripTrackingService.startMonitoring(context)` (existing companion function, already public).
- Produces:
  ```kotlin
  object ParkGeofence {
      const val ID = "park"
      const val RADIUS_M = 150f
      fun arm(context: Context, lat: Double, lon: Double)
      fun disarm(context: Context)
  }
  class GeofenceWakeReceiver : BroadcastReceiver()   // registered in manifest
  ```

- [ ] **Step 1: Write `ParkGeofence.kt`**

`app/src/main/java/com/jellemax/detour/tracking/ParkGeofence.kt`:

```kotlin
package com.jellemax.detour.tracking

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/**
 * The one OS geofence Detour registers (issue #90): a circle around the
 * position where the rider parked, transition EXIT only, no expiry. Its whole
 * job is to let [TripTrackingService] stop itself while parked and still be
 * woken by the system when the rider rides away — a foreground-service start
 * from [GeofenceWakeReceiver] is on Android's background-start exemption list
 * precisely for a geofencing transition.
 *
 * Not to be confused with circle arrive/depart, which is still evaluated
 * on-device against fixes that already arrive (`GeofenceEvaluator` in
 * `shared/`) and goes nowhere near this API.
 *
 * [RADIUS_M] is the landing default. Bigger = the service wakes earlier into
 * the drive (less leading trace lost to geofence-callback latency) but fires
 * on a rider who just walks 200 m and back. Tune from a measured wake latency
 * — see the spec's "Radius & wake latency" and the follow-up issue.
 */
object ParkGeofence {

    const val ID = "park"
    const val RADIUS_M = 150f

    private const val ACTION = "com.jellemax.detour.GEOFENCE_EXIT"

    fun arm(context: Context, lat: Double, lon: Double) {
        if (!hasLocationPermission(context)) return
        val geofence = Geofence.Builder()
            .setRequestId(ID)
            .setCircularRegion(lat, lon, RADIUS_M)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()
        val request = GeofencingRequest.Builder()
            // No INITIAL_TRIGGER_* — arming while already inside the circle
            // must not fire an immediate spurious wake.
            .setInitialTrigger(0)
            .addGeofence(geofence)
            .build()
        try {
            LocationServices.getGeofencingClient(context)
                .addGeofences(request, pendingIntent(context))
                .addOnFailureListener { Log.w("ParkGeofence", "arm failed", it) }
        } catch (e: SecurityException) {
            Log.w("ParkGeofence", "arm denied", e)
        }
    }

    fun disarm(context: Context) {
        LocationServices.getGeofencingClient(context)
            .removeGeofences(listOf(ID))
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceWakeReceiver::class.java).setAction(ACTION)
        // Geofencing requires a mutable PendingIntent (the system writes the
        // transition result into it).
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
```

- [ ] **Step 2: Write `GeofenceWakeReceiver.kt`**

`app/src/main/java/com/jellemax/detour/tracking/GeofenceWakeReceiver.kt`:

```kotlin
package com.jellemax.detour.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/**
 * Wakes [TripTrackingService] when the rider leaves the position they parked
 * at (issue #90). Starting a foreground service from here is allowed from the
 * background: a geofencing transition is on Android's FGS background-start
 * exemption list
 * (developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).
 */
class GeofenceWakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w("GeofenceWake", "geofence event error ${event.errorCode}")
            return
        }
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_EXIT) return
        ParkGeofence.disarm(context)
        try {
            TripTrackingService.startMonitoring(context)
        } catch (e: Exception) {
            // Background-start still refused (permission revoked while parked);
            // tracking resumes next time the app is opened.
            Log.w("GeofenceWake", "could not start tracker", e)
        }
    }
}
```

- [ ] **Step 3: Register the receiver in the manifest**

In `app/src/main/AndroidManifest.xml`, immediately after the `.tracking.BootReceiver` `<receiver>` block (around line 148):

```xml
        <receiver
            android:name=".tracking.GeofenceWakeReceiver"
            android:exported="false" />
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :app:compilePhoneDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/tracking/ParkGeofence.kt \
        app/src/main/java/com/jellemax/detour/tracking/GeofenceWakeReceiver.kt \
        app/src/main/AndroidManifest.xml
git commit -m "$(cat <<'EOF'
feat(tracking): ParkGeofence + wake receiver for #90

One EXIT-only geofence around the parked position; the receiver restarts
TripTrackingService on exit (allowed from background — geofencing transition
is an FGS background-start exemption).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GwbRHp9GZQp7F8h825tkAk
EOF
)"
```

---

## Task 3: Wire dormancy into `TripTrackingService`

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt`

**Interfaces:**
- Consumes: `dormancyDecision(...)` and `DormancyDecision` (Task 1); `ParkGeofence.arm` / `ParkGeofence.disarm` (Task 2).
- Produces: no new public surface. Behaviour change only.

Context — the current shape (verified line numbers, re-`grep` before editing, the file is ~1851 lines):
- `onStartCommand` ends (~line 938-940) with `ensureLocationUpdates(); registerActivityTransitions(); return START_STICKY`.
- `registerActivityTransitions()` (~line 1170) builds its `PendingIntent` inline and has a `transitionsRegistered` guard.
- `handleTransition(intent)` (~line 1206) processes STILL/IN_VEHICLE/WALKING; STILL ENTER sets `stationary = entering` when no trip.
- `endTrip()` (~line 1107-1111) sets `stationary = false`, `pendingStopAtMs = null`, then `ensureLocationUpdates(); updateNotification()`.
- Companion state read for the decision: `_stats.value` (trip), `convoyActive`, `uiVisible`, `stationary`, `Settings.autoDetectDrives.value`. `convoyActive` and `uiVisible` are `private` in the companion — add internal accessors (below).
- `lastLocation: Location?` (~line 373) is the last raw fix; `_lastFix.value` is the published `Fix?`.

- [ ] **Step 1: Add companion accessors for `convoyActive` / `uiVisible`**

In the `companion object`, next to the existing `private var convoyActive` / `private var uiVisible`, add:

```kotlin
        /** For the instance's own dormancy check — see [dormancyDecision]. */
        internal fun isConvoyActive() = convoyActive
        internal fun isUiVisible() = uiVisible
```

- [ ] **Step 2: Hoist the activity-transition `PendingIntent` and add an unregister path**

Replace the inline `PendingIntent.getForegroundService(...)` inside `registerActivityTransitions()` with a private helper used by both register and unregister:

```kotlin
    private fun activityTransitionPendingIntent(): PendingIntent =
        PendingIntent.getForegroundService(
            this, 1,
            Intent(this, TripTrackingService::class.java).setAction(ACTION_TRANSITION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
```

Gate registration on the setting, and add the mirror:

```kotlin
    private fun registerActivityTransitions() {
        if (transitionsRegistered) return
        if (!Settings.autoDetectDrives.value) return          // NEW: AC 2
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACTIVITY_RECOGNITION,
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        // ... unchanged transition list ...
        try {
            ActivityRecognition.getClient(this)
                .requestActivityTransitionUpdates(
                    ActivityTransitionRequest(transitions), activityTransitionPendingIntent())
                .addOnSuccessListener { transitionsRegistered = true }
        } catch (e: SecurityException) { /* unchanged */ }
    }

    private fun unregisterActivityTransitions() {
        if (!transitionsRegistered) return
        try {
            ActivityRecognition.getClient(this)
                .removeActivityTransitionUpdates(activityTransitionPendingIntent())
        } catch (e: SecurityException) { /* nothing to clean up */ }
        transitionsRegistered = false
    }
```

- [ ] **Step 3: Add `maybeGoDormant()`**

Add this private method (place it next to `currentMode()` / `ensureLocationUpdates()`):

```kotlin
    /**
     * The stop path (issue #90). Called after every event that can change
     * whether anything still needs this service foreground. Idempotent — a
     * STAY_ALIVE decision does nothing and the ordinary mode machinery runs
     * as before.
     */
    private fun maybeGoDormant() {
        val decision = dormancyDecision(
            autoDetect = Settings.autoDetectDrives.value,
            tripActive = _stats.value != null,
            convoyActive = isConvoyActive(),
            uiVisible = isUiVisible(),
            stationary = stationary,
        )
        when (decision) {
            DormancyDecision.STAY_ALIVE -> return
            DormancyDecision.STOP_WITH_GEOFENCE -> {
                val fix = _lastFix.value ?: lastLocation?.let {
                    Pair(it.latitude, it.longitude)
                }?.let { Fix(it.first, it.second, 0.0, null, 0f, 0L, 0L) }
                if (fix == null) return          // no position yet; next SLEEP fix arms it
                ParkGeofence.arm(this, fix.lat, fix.lon)
                stopDormant()
            }
            DormancyDecision.STOP_BARE -> {
                ParkGeofence.disarm(this)
                unregisterActivityTransitions()
                stopDormant()
            }
        }
    }

    private fun stopDormant() {
        if (::fusedClient.isInitialized) fusedClient.removeLocationUpdates(locationCallback)
        activeMode = null
        flushTrace()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
```

Note: the `Fix(...)` fallback only needs `lat`/`lon` — the geofence ignores the rest. If constructing a throwaway `Fix` reads badly to review, add a tiny `private fun lastKnownLatLon(): Pair<Double, Double>?` instead and pass the pair. Either is fine; do not add a new type.

- [ ] **Step 4: Call `maybeGoDormant()` from the three event tails**

1. End of `onStartCommand`, after `registerActivityTransitions()` and before `return START_STICKY`:
   ```kotlin
       ensureLocationUpdates()
       registerActivityTransitions()
       ParkGeofence.disarm(this)        // NEW: awake now, the parked geofence has no job
       maybeGoDormant()                 // NEW
       return START_STICKY
   ```
   (`disarm` before `maybeGoDormant` so a STOP_WITH_GEOFENCE decision in this same pass re-arms cleanly.)

2. End of `handleTransition(intent)`, after the `when (event.activityType)` loop closes:
   ```kotlin
       maybeGoDormant()                 // NEW — a STILL ENTER may have just parked us
   ```

3. In `endTrip()`, the existing tail `ensureLocationUpdates(); updateNotification()` becomes:
   ```kotlin
       ensureLocationUpdates()
       updateNotification()
       maybeGoDormant()                 // NEW
   ```

- [ ] **Step 5: Guard `ensureLocationUpdates()`'s `SecurityException` path**

`ensureLocationUpdates()` already calls `stopSelf()` in its `catch (e: SecurityException)`. Leave it — but add `ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)` right before that `stopSelf()` so a permission-revocation mid-run also clears the notification rather than leaving a dangling one for the ~5 s until the process dies.

- [ ] **Step 6: Build and run the existing test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — no existing test touches this path; `DormancyTest` still green.

Run: `./gradlew :app:compilePhoneDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Manual device verification (AC 1 + AC 2)**

Read the `detour-adb` skill first. With a debug build on a device, location + background-location + activity-recognition granted:

```bash
# AC 1: auto-detect ON, park, background the app, wait out the standstill window
adb shell dumpsys activity services com.jellemax.detour   # expect: no TripTrackingService
adb shell dumpsys notification | grep -A2 detour          # expect: no ongoing tracker notification
adb shell cmd location providers                          # sanity: geofence client alive
# AC 2: Settings → Auto-detect drives OFF
adb shell dumpsys activity services com.jellemax.detour   # expect: no TripTrackingService
# geofence dump — none registered:
adb shell dumpsys deviceidle | grep -i geofence || echo "no geofence"
```

Record the observed output in the PR body.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt
git commit -m "$(cat <<'EOF'
feat(tracking): stop the service while parked, wake it by geofence (#90)

TripTrackingService gains a stop path: dormancyDecision picks between staying
foreground, stopping behind a park geofence, or stopping outright when
auto-detect is off. Activity-recognition transitions are now gated on the
setting and unregistered on the bare-stop path.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GwbRHp9GZQp7F8h825tkAk
EOF
)"
```

---

## Task 4: `CircleSyncWorker` + WorkManager dependency + scheduling

**Files:**
- Modify: `app/build.gradle.kts` (add dependency)
- Create: `app/src/main/java/com/jellemax/detour/notif/CircleSyncWorker.kt`
- Modify: `app/src/main/java/com/jellemax/detour/MainActivity.kt`
- Modify: `app/src/main/java/com/jellemax/detour/tracking/BootReceiver.kt`

**Interfaces:**
- Consumes: `CirclePresence.tick(lat, lon, accuracyM, fixTimeMs, fixAgeMs, nowMs): Long` (existing, `shared/`, already called from `TripTrackingService.circleSyncLoop`); `Account.signedIn`, `SyncClient.configured()` (existing).
- Produces:
  ```kotlin
  class CircleSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
      companion object { fun schedule(context: Context) }
  }
  ```

- [ ] **Step 1: Add the dependency**

In `app/build.gradle.kts`, in the `dependencies { }` block near the other AndroidX entries:

```kotlin
    // Parked-state circle position sync (#90): the 2-min loop inside
    // TripTrackingService dies when the service stops while parked, so a
    // 15-min periodic worker carries the "still here" post + on-device
    // circle geofence evaluation from then on. WorkManager's own persistence
    // survives reboot.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
```

- [ ] **Step 2: Write `CircleSyncWorker.kt`**

`app/src/main/java/com/jellemax/detour/notif/CircleSyncWorker.kt`:

```kotlin
package com.jellemax.detour.notif

import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.jellemax.detour.data.Account
import com.jellemax.detour.data.CirclePresence
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.SyncClient
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * The parked half of circle presence (issue #90). While a trip, convoy or the
 * map keeps [com.jellemax.detour.tracking.TripTrackingService] alive, that
 * service's own 2-minute `circleSyncLoop` posts this device's fix and runs
 * [CirclePresence.tick]. The moment it stops (parked, app backgrounded), this
 * worker takes over at WorkManager's 15-minute floor — a coarser "last seen"
 * cadence, which is within a Life360-style circle's tolerance for a phone that
 * is not moving.
 *
 * Overlap with the service's loop at drive start/end is harmless: a duplicate
 * post of a near-identical position, which the server resolves to the latest.
 */
class CircleSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        Settings.init()
        if (!Account.signedIn || !SyncClient.configured()) return Result.success()

        val client = LocationServices.getFusedLocationProviderClient(applicationContext)
        val loc = try {
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
                ?: client.lastLocation.await()
        } catch (e: SecurityException) {
            return Result.success()   // location permission gone; nothing to do
        } ?: return Result.success()

        val ageMs = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000L
        return try {
            CirclePresence.tick(
                loc.latitude, loc.longitude, loc.accuracy.toDouble(),
                loc.time, ageMs, System.currentTimeMillis(),
            )
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "circle-sync"

        /** Idempotent — [ExistingPeriodicWorkPolicy.KEEP] means repeated calls
         *  (app start, boot) don't reset the period. Cheap when signed out:
         *  [doWork] returns immediately. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CircleSyncWorker>(15, TimeUnit.MINUTES).build(),
            )
        }
    }
}
```

- [ ] **Step 3: Schedule from `MainActivity`**

In `app/src/main/java/com/jellemax/detour/MainActivity.kt`, `onCreate`, right after the existing `CircleNotifyService.refresh(this)` line (~line 95):

```kotlin
        CircleSyncWorker.schedule(this)
```

Add the import `com.jellemax.detour.notif.CircleSyncWorker`.

- [ ] **Step 4: Schedule from `BootReceiver`**

In `app/src/main/java/com/jellemax/detour/tracking/BootReceiver.kt`, inside `onReceive`, after the `CircleNotifyService.refresh(context)` try/catch:

```kotlin
        try {
            CircleSyncWorker.schedule(context)
        } catch (e: Exception) {
            // WorkManager not ready this early is rare and self-heals on next app open.
        }
```

Add the import `com.jellemax.detour.notif.CircleSyncWorker`.

- [ ] **Step 5: Build**

Run: `./gradlew :app:compilePhoneDebugKotlin`
Expected: BUILD SUCCESSFUL (pulls `androidx.work` on first run).

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Manual device verification (AC 6)**

With a signed-in debug build, a server configured, in a circle with sharing on:

```bash
adb shell dumpsys jobscheduler | grep -A5 circle-sync        # periodic job present
# Force one run:
adb shell cmd jobscheduler run -f com.jellemax.detour <jobId>
adb logcat -d | grep -iE "CircleFixes|CirclePresence"        # a fix was posted
```

Confirm on another device / the web view that this device's "last seen" updated while parked with the app closed. Record in PR body.

- [ ] **Step 7: Commit**

```bash
git add app/build.gradle.kts \
        app/src/main/java/com/jellemax/detour/notif/CircleSyncWorker.kt \
        app/src/main/java/com/jellemax/detour/MainActivity.kt \
        app/src/main/java/com/jellemax/detour/tracking/BootReceiver.kt
git commit -m "$(cat <<'EOF'
feat(circles): CircleSyncWorker — parked-state position sync (#90)

The service's 2-min circleSyncLoop dies when it stops while parked; a 15-min
WorkManager periodic worker carries CirclePresence.tick from then on.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GwbRHp9GZQp7F8h825tkAk
EOF
)"
```

---

## Task 5: Remove the parked-only path from the service's own circle loop — decision

**Files:**
- Modify (maybe): `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt`

The spec keeps `circleSyncLoop` in the service unchanged and accepts brief overlap. Confirm that is still right and leave a one-line comment so the next reader does not "fix" the apparent duplication.

- [ ] **Step 1: Add the orientation comment**

Above `circleSyncLoop`'s KDoc in `TripTrackingService.kt` (or extend the existing KDoc), add:

```kotlin
     * Since #90 this loop only runs while the service is alive (trip, convoy,
     * visible map). The parked case moved to [com.jellemax.detour.notif.CircleSyncWorker];
     * a brief overlap at drive start/end double-posts a near-identical
     * position, which is harmless.
```

- [ ] **Step 2: Build**

Run: `./gradlew :app:compilePhoneDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt
git commit -m "$(cat <<'EOF'
docs(tracking): note circleSyncLoop is the alive-only path since #90

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GwbRHp9GZQp7F8h825tkAk
EOF
)"
```

---

## Task 6: Docs + version bump

**Files:**
- Modify: `docs/PLAY_LOCATION_DECLARATION.md` (lines ~254-258)
- Modify: `docs/CIRCLES_AND_CONVOYS.md` (line ~286)
- Modify: `app/build.gradle.kts` (`versionName`)

- [ ] **Step 1: `PLAY_LOCATION_DECLARATION.md`**

Replace the paragraph that currently reads:

```
Leave **Geofencing** unticked. Detour registers no geofences: the auto-stop
"back where you started" check is plain distance arithmetic against the trip's
own origin, and a circle's arrive/depart events are evaluated the same way, on
device, against fixes that already arrive (`GeofenceEvaluator` in `shared/`).
Neither goes near the Geofencing API.
```

with:

```
Tick **Geofencing**. Detour registers exactly one geofence: a single
transition-EXIT circle around the position where the rider parked, used only
to let the trip-tracking foreground service stop itself while the phone is
stationary and be woken by the system when the rider rides away (`ParkGeofence`
in `app/.../tracking/`). It carries no radius of interest beyond that wake and
is removed the moment the service starts.

The auto-stop "back where you started" check and a circle's arrive/depart
events still use no geofence API — both are plain on-device arithmetic against
fixes that already arrive (`GeofenceEvaluator` in `shared/`).
```

- [ ] **Step 2: `CIRCLES_AND_CONVOYS.md`**

The line at ~286 currently reads (inside the "One collector, two sinks" paragraph):

```
There is one location stream; convoys and circles are both sinks on it, and the
highest active cadence wins. Neither platform opens a second subscription, and
neither registers OS geofences.
```

Change the last sentence to:

```
Neither platform opens a second subscription. Circle arrive/depart uses no OS
geofence — it is on-device `GeofenceEvaluator` arithmetic. (Android separately
registers one unrelated geofence for parked-state service dormancy, issue #90;
it plays no part in circle presence.)
```

- [ ] **Step 3: Version bump**

In `app/build.gradle.kts`, change `versionName = "1.97.1"` to `versionName = "1.98.0"`.

- [ ] **Step 4: Commit**

```bash
git add docs/PLAY_LOCATION_DECLARATION.md docs/CIRCLES_AND_CONVOYS.md app/build.gradle.kts
git commit -m "$(cat <<'EOF'
docs: Play geofencing declaration + versionName 1.98.0 (#90)

Parked-dormancy registers one wake geofence, so the Play location form's
Geofencing box is now ticked. Circle presence still uses none.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GwbRHp9GZQp7F8h825tkAk
EOF
)"
```

---

## Task 7: Full verification + PR

- [ ] **Step 1: Full test + lint**

```bash
./gradlew :app:testDebugUnitTest :app:compilePhoneDebugKotlin
./gradlew :app:lintPhoneDebug            # check no new manifest / PendingIntent warnings
```
Expected: all green. Address any new lint on `PendingIntent` mutability or missing-permission.

- [ ] **Step 2: GPS replay A/B (AC 4) where possible without driving**

Read the `detour-gps-replay` skill. Replay a recorded route that starts from a cold parked state:
1. Park the mock, let the service go dormant (confirm via `dumpsys`).
2. Manually fire the geofence exit: `adb shell am broadcast -a com.jellemax.detour.GEOFENCE_EXIT -n com.jellemax.detour/.tracking.GeofenceWakeReceiver` (debug only) — or drive the mock out of the 150 m circle.
3. Let the replay run the route; compare the recorded trip's distance/duration to the same route replayed with the service already running.

Record both numbers and the delta in the PR body. Note explicitly that the true geofence-callback latency (AC 5) still needs a real drive.

- [ ] **Step 3: Push and open the PR**

```bash
git push -u origin feat/issue-90-parked-dormancy
```

Then create the PR — **use the `detour-pr-writing` skill for the body.** It must:
- Lead with measured before/after: shade notification present 24/7 → present only during an actual drive.
- State what changed (the six components).
- Carry the known limits: 15-min parked circle cadence (was 2), geofence-callback latency eats the first ~30–120 s of trace on a geofence-only wake.
- List the acceptance criteria still open and assigned to the author: AC 3 (real drive starts tracking unopened), AC 4 (replay A/B on road), AC 5 (measured leading-trace loss + final radius).
- Link the follow-up issue for radius tuning (create it: "Tune ParkGeofence.RADIUS_M from measured geofence wake latency", reference #90).

PR body ends with:
```
🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

- [ ] **Step 4: Report to the user** the PR URL, the created follow-up issue URL, and the three ACs left for them to close on the road.

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
|---|---|
| Component 1 — dormancy trigger | Task 1 (pure fn), Task 3 (wiring) |
| Component 2 — `ParkGeofence` | Task 2 |
| Component 3 — `GeofenceWakeReceiver` | Task 2 |
| Component 4 — `CircleSyncWorker` | Task 4 |
| Component 5 — AR gating | Task 3 (steps 2, 4) |
| Component 6 — call sites | Task 3 (step 4), Task 4 (steps 3-4) |
| Component 7 — docs | Task 6 |
| Radius & wake latency default (150m) | Task 2 (`RADIUS_M`), documented; measurement in Task 7 step 2 + follow-up issue |
| Testing table | Task 1 (`DormancyTest`), Task 3 step 7 (adb AC1/AC2), Task 4 step 6 (adb AC6), Task 7 step 2 (replay) |
| Non-goal: no channel split | not done, by design — no task |
| Non-goal: no `shared/` change | Global Constraints |
| Version → 1.98.0 | Task 6 step 3 |

**Placeholder scan:** every code step carries full source. `maybeGoDormant`'s `Fix(...)` fallback is spelled out with an accepted alternative. adb commands are concrete. No "TBD".

**Type consistency:**
- `dormancyDecision(autoDetect, tripActive, convoyActive, uiVisible, stationary)` — same 5-param order in Task 1 def, Task 1 tests, Task 3 call site.
- `DormancyDecision.{STAY_ALIVE, STOP_WITH_GEOFENCE, STOP_BARE}` — consistent across Tasks 1 and 3.
- `ParkGeofence.arm(context, lat, lon)` / `disarm(context)` — Task 2 def matches Task 3 calls.
- `CircleSyncWorker.schedule(context)` — Task 4 def matches MainActivity/BootReceiver calls.
- `CirclePresence.tick(lat, lon, accuracyM, fixTimeMs, fixAgeMs, nowMs)` — Task 4 call matches the verified signature in `shared/src/commonMain/.../CirclePresence.kt:146`.

# Issue 90 — Parked dormancy: no foreground service, no notification while parked

**Status:** approved design, pre-implementation
**Issue:** #90
**Branch:** `feat/issue-90-parked-dormancy`
**Target version:** `versionName` 1.97.1 → 1.98.0 (minor — new feature, no wire/data/OS break)

## Problem

`TripTrackingService` is always-on by design. `BootReceiver` starts it at boot,
`MapScreen` and the Android Auto `SpinScreen` start it on every `ON_START`, and
nothing ever stops it — `stop()` ends the *trip* and leaves the service alive.
Because it runs with `FOREGROUND_SERVICE_TYPE_LOCATION` it must hold an ongoing,
non-dismissible notification. So every rider carries a permanent Detour row in
the shade — parked, asleep, on holiday — reading "Standing by" / "Watching for
trips" / (worse) "Auto-tracking off" after the feature is disabled. Circle
members get a second permanent row from `CircleNotifyService`.

The service is genuinely cheap while parked (`LocationMode.SLEEP` = passive
fixes only). The cost is perception and shade real estate, not battery.

## Proposed behaviour

While parked — no trip running, no convoy joined, map not foregrounded, phone
stationary past the standstill window — Detour runs **no** foreground service
and shows **no** notification.

- When the service resolves to that state, it registers **one OS geofence**
  (`GeofencingClient`) around the parked position and calls `stopSelf()`.
- On `GEOFENCE_TRANSITION_EXIT`, a `BroadcastReceiver` starts
  `TripTrackingService`, which posts its notification at that point and behaves
  exactly as today: probe, confirm, record.
- Activity-recognition `IN_VEHICLE`/`STILL` transitions stay registered as a
  **companion** wake — delivered by `PendingIntent`, no live service needed —
  covering the case a geofence exit is slow to fire.
- With `auto_detect_drives=false`, the service stops whenever nothing is active
  and registers **no geofence and no AR transitions at all**. Tracking then
  resumes only on app open, a manual "Go", or re-enabling the setting.

The notification a rider sees once a drive starts is unchanged.

## Non-goals

- **No notification-channel split.** Once the service does not run while parked,
  the permanent-notification problem is solved wholesale; the channel-sharing
  complaint (muting "Trip tracking" also silences trip-ended + badges) becomes
  moot because there is no ongoing notification to mute. The issue's own
  position: the notification *during an actual drive* is fine as-is.
- **No `shared/` change.** `CirclePresence.tick()` is already the shared
  per-pass decision (`shared/src/commonMain/.../CirclePresence.kt`); only the
  Android `while`/`delay` shell moves. iOS (`CircleSync.loop`) is untouched.
- **iOS region monitoring** — the equivalent lever there — is out of scope
  (per issue).
- **On-road acceptance criteria (issue AC 3, 4, 5)** are not closed by this PR.
  It ships with a chosen radius and a written measurement plan; #140
  tracks tuning the radius from real wake-latency numbers. Those AC boxes
  stay unchecked until the author validates on road.
- **`CircleNotifyService`'s permanent row is out of scope.** This issue is about
  the *trip-tracking* notification. Circle members with a notify-enabled circle
  still have `CircleNotifyService` (its own `REMOTE_MESSAGING` foreground
  service) running while parked — a separate always-on row for a separate
  feature. #142 covers making that one dormant too.
- **Dormancy needs two runtime permissions the app treats as optional.**
  `STOP_WITH_GEOFENCE` engages only with `ACTIVITY_RECOGNITION` (feeds the
  `stationary` signal) *and* `ACCESS_BACKGROUND_LOCATION` (so the wake geofence
  actually fires in the background) granted. Missing either, the service stays
  always-on exactly as pre-#90 — a safe degradation, not a regression, but it
  means AC 1 is conditional on both grants.

## Architecture

### Component 1 — dormancy trigger (`TripTrackingService`)

`currentMode()` today resolves to `TRIP | PROBE | LIVE | SLEEP | IDLE`. Add a
dormancy check evaluated whenever the mode is (re)computed — at the tail of
`onStartCommand` after `ensureLocationUpdates()`, and after `endTrip()` /
`handleTransition()` already re-run it.

The decision is extracted as a **pure function** so it is unit-testable without
a `Service`:

```kotlin
// app/src/main/java/com/jellemax/detour/tracking/Dormancy.kt
enum class DormancyDecision { STAY_ALIVE, STOP_WITH_GEOFENCE, STOP_BARE }

fun dormancyDecision(
    autoDetect: Boolean,
    tripActive: Boolean,
    convoyActive: Boolean,
    uiVisible: Boolean,
    stationary: Boolean,
): DormancyDecision = when {
    tripActive || convoyActive || uiVisible -> STAY_ALIVE
    !autoDetect                             -> STOP_BARE
    stationary                              -> STOP_WITH_GEOFENCE
    else                                    -> STAY_ALIVE   // IDLE: on foot, still watching
}
```

Applied in the service:

- `STAY_ALIVE` — unchanged; request location for the resolved mode.
- `STOP_WITH_GEOFENCE` — first, if `ACCESS_BACKGROUND_LOCATION` is not granted
  (Android 10+), fall back to `STAY_ALIVE`: a geofence transition is only
  delivered to a backgrounded app with that permission, so without it the
  service would stop and never wake. Otherwise `ParkGeofence.arm(this, lat, lon)`
  at `lastLocation` (or `_lastFix.value`), then
  `stopForeground(STOP_FOREGROUND_REMOVE)` + `stopSelf()`. If no position is
  known yet, fall back to `STAY_ALIVE` for this pass (the next SLEEP fix arms it).
- `STOP_BARE` — `ParkGeofence.disarm(this)` (defensive), unregister AR
  transitions, `stopSelf()`.

`onDestroy()` already flushes the trace and joins an in-flight `endTrip()` save;
that path is unchanged and covers the `stopSelf()` teardown.

### Component 2 — `ParkGeofence` (new)

`app/src/main/java/com/jellemax/detour/tracking/ParkGeofence.kt` — a plain
`object` wrapping `LocationServices.getGeofencingClient(context)`.

```kotlin
object ParkGeofence {
    const val ID = "park"
    const val RADIUS_M = 150f          // see "Radius & wake latency" below

    fun arm(context: Context, lat: Double, lon: Double)   // GEOFENCE_TRANSITION_EXIT,
                                                          // no expiry, initialTrigger = 0
    fun disarm(context: Context)                          // removeGeofences(listOf(ID))
}
```

- Permission guard mirrors the service's `canStart`: needs fine-or-coarse
  location; `arm` no-ops without it (`addOnFailureListener` logs, no crash).
- `INITIAL_TRIGGER_EXIT` is **not** set — arming while already outside the fence
  must not fire an immediate wake.
- The `PendingIntent` targets `GeofenceWakeReceiver` (below),
  `FLAG_UPDATE_CURRENT or FLAG_MUTABLE` (geofencing requires mutable).

### Component 3 — `GeofenceWakeReceiver` (new)

`app/src/main/java/com/jellemax/detour/tracking/GeofenceWakeReceiver.kt`,
manifest-registered, `android:exported="false"`.

```kotlin
override fun onReceive(context: Context, intent: Intent) {
    val event = GeofencingEvent.fromIntent(intent) ?: return
    if (event.hasError()) return
    if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_EXIT) return
    ParkGeofence.disarm(context)
    TripTrackingService.startMonitoring(context)   // exempt from FGS bg-start:
                                                   // geofencing transition
}
```

Starting a foreground service from here is on Android's documented
background-start exemption list (geofencing **and** activity-recognition
transitions are both exempt —
<https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start>).

Also: `TripTrackingService.onStartCommand` calls `ParkGeofence.disarm(this)`
unconditionally on every start — once the service is awake for any reason
(map opened, manual Go, AR probe, boot), the parked geofence has no job.

### Component 4 — `CircleSyncWorker` (new)

`circleSyncLoop` inside `TripTrackingService` posts this device's fix to each
shared circle every ~2 min and runs `CirclePresence.tick()` (geofence
arrive/depart evaluation). It dies with the service. Relocate the *parked* case
to WorkManager; the service keeps its own loop for when it is alive.

`app/src/main/java/com/jellemax/detour/notif/CircleSyncWorker.kt`:

```kotlin
class CircleSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!Account.signedIn || !SyncClient.configured()) return Result.success()
        val loc = fusedClient.getCurrentLocation(PRIORITY_BALANCED_POWER_ACCURACY, null).await()
            ?: fusedClient.lastLocation.await()
            ?: return Result.success()
        val ageMs = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000
        CirclePresence.tick(loc.latitude, loc.longitude, loc.accuracy.toDouble(),
                            loc.time, ageMs, System.currentTimeMillis())
        return Result.success()
    }
}
```

- Scheduled with `enqueueUniquePeriodicWork("circle-sync", KEEP,
  PeriodicWorkRequestOf<CircleSyncWorker>(15, MINUTES))` from `MainActivity.onCreate`
  and `BootReceiver` — the two places `CircleNotifyService.refresh` is already
  called. `KEEP` so app restarts don't reset the period.
- **Cadence regression, accepted:** 15 min (WorkManager's floor) vs the
  service's 2 min while parked. A circle is Life360-style presence, not a live
  feed (`CirclePresence` KDoc); 15-min "last seen" freshness for a phone that is
  not moving is acceptable. While *driving*, the service is alive and its 2-min
  loop is unchanged.
- **Overlap with the service's loop:** guarded. The worker returns early while
  `TripTrackingService.circleSyncHandledByService()` is true, so the two never
  tick `CirclePresence` concurrently (its evaluator state is not thread-safe)
  and there is no duplicate `postFix`.
- **Background location:** with `ACCESS_BACKGROUND_LOCATION` granted (app
  already requests it) `getCurrentLocation` returns a fresh fix; without it,
  `lastLocation` (cached) is used and `CirclePresence.isFixTrusted(ageMs)`
  correctly declines to drive a geofence decision off a stale fix while still
  posting "last seen". This is the designed `FIX_TRUST_MS` behaviour.
- New dependency: `androidx.work:work-runtime-ktx` (version via
  `gradle/libs.versions.toml`). No `WorkManagerInitializer` change needed —
  the default manifest-merge provider is fine.

### Component 5 — activity-recognition gating

`registerActivityTransitions()` currently runs unconditionally once per service
start. Gate it on `Settings.autoDetectDrives.value`; add
`unregisterActivityTransitions()` (`removeActivityTransitionUpdates(pendingIntent)`)
called from the `STOP_BARE` path. `handleTransition` already checks
`autoDetectDrives` before opening a probe window, so a late-delivered
transition after toggle-off is already inert — this change stops the
`PendingIntent` from waking a stopped service at all, satisfying AC 2's
"no service ... at all".

The AR `PendingIntent` stays `PendingIntent.getForegroundService(...)`; the
exemption covers it from dormant. Geofence is the load-bearing wake, AR the
companion.

### Component 6 — call sites

| File | Change |
|---|---|
| `MapScreen.kt` `onLocationGranted` / `ON_START` | unchanged — `startMonitoring` already fires; `onStartCommand` now disarms the geofence |
| `car/SpinScreen.kt` `ON_START` | unchanged — same reason |
| `MainActivity.onCreate` | add `CircleSyncWorker` schedule next to `CircleNotifyService.refresh(this)` |
| `BootReceiver.onReceive` | add `CircleSyncWorker` schedule; `startMonitoring` stays (starts, then dormancy re-stops if parked) |
| `SettingsScreen.kt` auto-detect toggle | unchanged — `TripTrackingService.refresh(context)` already called; the `ACTION_REFRESH` pass now runs the dormancy check |
| `AndroidManifest.xml` | register `GeofenceWakeReceiver` |

### Component 7 — docs

- `docs/PLAY_LOCATION_DECLARATION.md:254-258` — **Geofencing** becomes a ticked
  declaration; delete the "Detour registers no geofences" paragraph and replace
  with a note that the parked-dormancy geofence is a single EXIT fence around
  the last position, used only to wake trip tracking.
- `docs/CIRCLES_AND_CONVOYS.md:286` — "neither registers OS geofences" →
  corrected to note the Android parked-wake geofence (still no geofence for
  circle arrive/depart — that stays on-device `GeofenceEvaluator`).

## Radius & wake latency

`ParkGeofence.RADIUS_M = 150f` as the landing default.

Reasoning (to be replaced with a measured number — issue AC 5):

- Android geofence EXIT callbacks are OS-batched; observed latency is commonly
  tens of seconds to ~2 min, worse with Doze.
- At 30 km/h a rider clears a 150 m radius in ~18 s; the geofence callback then
  lands some tens of seconds later, so the service typically wakes 30–120 s
  into the drive.
- The lead-in is recovered two ways already in the code: `SLEEP` mode keeps a
  passive location request alive until `stopSelf()`, and auto-start backdates
  `beginTrip(startTimeMs = …)` to when the fast-run detector says the drive
  really began, not to the confirming fix.
- Larger radius = earlier wake, more false wakes (a rider who walks the dog
  200 m and comes back). 150 m is the starting compromise.

**Measurement plan (author, post-merge):** with the `detour-gps-replay` A/B
protocol — record a real drive from a cold parked state, note wall-clock delta
between first movement and the service's first `TRIP`-mode fix, and the
distance/duration delta of the geofence-woken trip vs. the same route recorded
with the service already running. Tune `RADIUS_M` from the wake delta. Tracked
in #140.

## Testing

| Test | Location | Asserts |
|---|---|---|
| `DormancyTest` | `app/src/test/` | `dormancyDecision(...)` truth table — every combination of the five booleans → expected `DormancyDecision`; regression cases: auto-detect off while UI visible = `STAY_ALIVE`; auto-detect on + stationary + backgrounded = `STOP_WITH_GEOFENCE` |
| `ParkGeofenceTest` | `app/src/test/` | the radius/latency arithmetic helper (distance cleared at speed `v` for radius `r`), if extracted; else skipped |
| `CirclePresenceTest` | `shared/src/commonTest/` (existing) | already covers `tick()` — no change |
| replay A/B | manual, `detour-gps-replay` | geofence-woken trip matches a service-alive recording within the protocol's tolerance — **author runs on road** |
| `adb shell dumpsys activity services com.jellemax.detour` | manual | parked + backgrounded + auto-detect on past standstill window → no running foreground service, no shade notification (issue AC 1) |
| `adb` | manual | `auto_detect_drives=false` + nothing active → no service, and `dumpsys deviceidle` / geofence dump shows no registered geofence (issue AC 2) |

`./gradlew :app:testDebugUnitTest` gates on every PR (`build.yml`); `shared/`
is untouched so `:shared:compileCommonMainKotlinMetadata` is not in play.

## Risks

1. **Geofence never fires** (OEM location throttling, Doze on a cheap phone).
   Mitigation: AR `IN_VEHICLE` companion wake; and the geofence is re-armed on
   every return to standstill, so a single miss is not permanent. Worst case
   degrades to today's behaviour for that one drive (missed auto-start), not a
   crash.
2. **`CircleSyncWorker` deferred by Doze** past 15 min. Accepted — a parked
   phone's position is not changing; "last seen" going 30–40 min stale for a
   stationary device is within the feature's Life360-style tolerance.
3. **Double trip auto-start** if geofence EXIT and AR `IN_VEHICLE` both wake the
   service. `onStartCommand` is idempotent for starts (`_stats.value == null`
   guard on `beginTrip`); the second start is a no-op refresh.
4. **Play review** flags the new geofence use. Mitigated by the declaration
   update landing in the same release (Component 7).

## Acceptance criteria mapping

| Issue AC | Closed by | In this PR? |
|---|---|---|
| Parked + backgrounded + auto-detect on → no notification, no FGS | Components 1–3 | yes, with `ACTIVITY_RECOGNITION` + `ACCESS_BACKGROUND_LOCATION` granted (see Non-goals); `CircleNotifyService`'s row is separate and out of scope |
| `auto_detect_drives=false` → no service, no geofence | Components 1, 5 | yes |
| Riding away starts tracking without opening the app | Components 2–3, real drive | code yes / **verify: author** |
| Auto-start quality does not regress (replay A/B) | measurement plan | **verify: author** |
| Leading-trace loss stated with a measured number | measurement plan | **verify: author**, #140 |
| Circle posting + arrive/depart still work while parked | Component 4 | yes |
| `PLAY_LOCATION_DECLARATION.md` rewritten | Component 7 | yes |
| `CIRCLES_AND_CONVOYS.md:286` corrected | Component 7 | yes |

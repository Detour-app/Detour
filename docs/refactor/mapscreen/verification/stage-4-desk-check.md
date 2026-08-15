# Stage 4 desk check — CameraAuthority wiring, on device

Task 3 of `docs/refactor/mapscreen/plans/2026-08-13-stage-4-camera-authority.md`.

| Field | Value |
|---|---|
| Device | `RFCT42HS9WY`, Galaxy Z Fold 3 (SM-F926B), Android 15 / SDK 35, 1768×2208 |
| Variant | `io.github.maxke24.detour.debug` v1.74 (release variant **not installed** on this device) |
| Commit under test | `81f44e0` *refactor(map): give the camera's follow and park state one owner* |
| Route | `tools/mocklocation/routes/stop-start.txt` — 762 lines, 9.7 km, mean 46 km/h, 1000 ms interval |
| Captures | `/tmp/stage4/` (nothing written into the repo) |
| Date | 2026-08-15, 14:41–15:03 local |

## How each transition was observed

Two instruments, both mechanical rather than eyeballed, because the panel is at brightness 0:

1. **The follow button's `contentDescription`**, which is `MapChrome.kt:94` reading
   `camAuthority.following` directly: `"Stop following my location"` when following,
   `"Follow my location"` when not. Read with `uiautomator dump`.
2. **The button's tint fraction**, computed off `screencap -p -d 4630947232161729154` over the
   glyph box `[1658,279]-[1711,332]`: **0.30 = tinted/following, 0.00 = untinted/parked**.
   One `screencap` round-trip is ~420 ms, so a burst of these times a transition to under a
   second — far better than the ~2.2 s a `uiautomator dump` costs. `/tmp/stage4/fpoll.py`.
3. **The vehicle dot's centroid in screen space** (`/tmp/stage4/dot.py`), which separates
   "the button says parked" from "the camera actually stopped tracking".

Constants the expected numbers come from: `CAM_RESUME_QUIET_MS = 8_000`,
`CAM_RESUME_SPEED_MPS = 3.0` (`MapCameraTuning.kt:55-56`), fixes at 1 Hz.

---

## 1. Follows at rest — **PASS**

Captures `01-launch.png`, `t1-a.png`, `t1-b.png`, `t1-c.png` (t = 0, +6 s, +12 s, +18 s).

- Follow button: `"Stop following my location"`, tint **0.30**.
- Vehicle dot centroid: **(883,979) → (858,975) → (883,977) → (883,963)** — fixed in screen
  space across 18 s (the 858 sample is mid-lerp between fixes).
- Map content over the same window: `compare -metric RMSE t1-a.png t1-c.png` = **2176.27
  (3.32 %)**, versus 0 for a self-compare. The map moved a long way under a stationary dot,
  which is what following looks like.

## 2. A drag parks it, a tap does not — **PASS**

**Tap does not park.** `input tap 500 700` (a pure DOWN/UP with no MOVE, so it never leaves
the slop circle): before `FOLLOWING`, after 3 s still `FOLLOWING`, tint 0.30.
`t2-after-tap.png` / `.xml`.
Stronger version, a long-press pin drop (`input swipe 600 800 600 800 900`) which *does* set a
destination: tint stayed **0.30** — `t5-0-before.png` → `t5-1-pin.png`. The `ACTION_UP`
`GestureEnd` correctly no-ops on an unparked camera.

**Drag parks.** `input swipe 900 1200 500 700 400`:

- tint **0.00 at t+3 ms** — parked on the first `ACTION_MOVE` past slop (`drag3-000004.png`).
- Dot centroid moved **(883,967) → (491,553)**, Δ(−392,−414), against the swipe's Δ(−400,−500).
  The map went with the finger; the camera let go. `t2-parked.png`.

## 3. Driving resumes it — **PASS**

Clean measurement, `drag3-*.png`, 42-frame burst at ~0.5 s from the end of the swipe:

```
t+7619 ms  0.0     parked
t+8164 ms  0.0     parked
t+8630 ms  0.3     FOLLOWING
```

**Park survived 8.16–8.63 s.** That is `CAM_RESUME_QUIET_MS = 8000` plus the wait for the next
1 Hz fix above `CAM_RESUME_SPEED_MPS`, i.e. exactly the pre-existing rule. Dot re-centred to
(884,960) (`t3-resumed.png`).

Two earlier runs resumed later — one in (9.3, 11.7] s, one past 9.26 s. Both were taken while
the route was in or approaching its standstill section (indices ~360–450 sit at 0.7–4.9 m/s),
where `speedMps >= 3.0` legitimately fails. Not a defect; noted so the numbers reconcile.

## 4. The follow button toggles both ways — **PASS**

`input tap 1685 306` (button bounds `[1622,243][1748,369]`):

| Capture | tint | dot centroid |
|---|---|---|
| `t4-0-before.png` | 0.30 | (883,955) |
| `t4-1-off.png` (+2 s) | 0.00 | (838,911) |
| `t4-2-off-12s.png` (+14 s) | 0.00 | (389,522) |
| `t4-3-on.png` (2nd tap, +3 s) | 0.30 | off-region, camera in flight |
| `t4-4-on-settled.png` (+7 s) | 0.30 | (883,958) |

Off: untints, the camera stops tracking and the vehicle walks out of centre. Critically it did
**not** self-resume across 12 s of driving — correct, because `FollowToggled` clears
`followMe` and leaves `camSuspended` false, so `FollowCamera.shouldWatch` is false and the
drive-off collector never runs. On: re-tints and flies back to centre.

## 5. A shortcut chip frames and parks — **PASS**

Setup needed: the debug install had **no** `files/saved_places.json`, so one saved place was
created through the UI (long-press pin → "Save pin" → name `DeskCheck` → Save). See
*Device state left behind* below.

Chip tap at (505,1664), 30-frame burst:

```
t+4 ms      0.0    parked immediately
…
t+8485 ms   0.0    still parked, vehicle dot off-screen the whole time
t+9004 ms   0.3    FOLLOWING again
```

- **Framed, not snapped back.** `chip-004061.png` shows the camera sitting on Kolonel
  Begaultlaan / Kanaal Leuven-Dijle (the saved place, ~2.6 km from the vehicle), the follow
  button untinted, the "Go" arrow now enabled, and no vehicle dot anywhere on the map. The
  dot only reappears at (883,979) when the park expires.
- **Park lasted 8.49 s** — the same 8 s window as a drag, which is right: `DestinationFramed`
  stamps `lastGestureMs` exactly as `Gesture` does.

## 6. A spin parks without re-centring — **PASS on the park and the asymmetry; the
candidate-framing half NOT PERFORMED**

### 6a. Spin parks — PASS

Dice at (1510,1837). tint **0.00 at t+3 ms** on every attempt.

### 6b. The `lastGestureMs` asymmetry survived — PASS (this is the decisive measurement)

The problem: while a spin is in flight, `FollowCamera.shouldWatch` is false, so the park holds
regardless of `lastGestureMs`. Two full spins here ran to the non-round-trip branch's
`withTimeout(30_000)` (CAR is `roundTrip = false`) and one ran ≥ 84 s, so both hypotheses —
stamped and unstamped — predict the same outcome for a slow spin. The discriminator has to be
a spin that ends **inside** the 8 s quiet window.

`MapScreen.kt:1578` gives one: a second dice tap while spinning cancels `spinJob`. So —
spin, then cancel at 3.7 s, `lastGestureMs` already ~5 minutes stale before the spin:

```
T0            input tap 1510 1837   (spin)
t+3     ms    0.0   parked
t+3043  ms    0.0   parked
t+3739  ms          input tap 1510 1837   (cancel — spinning = false, no candidates)
t+3740  ms    0.3   FOLLOWING
```

`spinc-*.png`. **The spin park lasted 3.74 s** and released in the very next frame after the
spin ended — **4.3 s inside** the quiet window that a drag park is bound by. If the wiring had
symmetrised `SpinStarted` by stamping `lastGestureMs`, resume could not have arrived before
t+8000 ms. It arrived at t+3740 ms. The asymmetry is intact.

Corroboration from the slow path: the full spin ended at ~30 s with the snackbar *"Road
servers are slow right now — try again"*, and the camera was `FOLLOWING` again immediately
after (`t6-final.png`, tint 0.30).

### 6c. Candidates stay framed — NOT PERFORMED

**The spin cannot produce candidates from this host.** Three attempts, all ending in the same
place: `pickCandidate` × 3 hitting `withTimeout(30_000)` and surfacing *"Road servers are slow
right now — try again"*; no candidate card ever composed, so there was never anything on
screen for the camera to be dragged off. Evidence: `t6-1.xml`, `t6-3.xml` and the t+30091 ms
row of the full-spin log — the bottom card returns to `25 km / Car · any direction` with the
error snackbar and no candidates. This is the same Overpass unavailability the stage-4 plan
records, not a regression.

What that leaves untested is `cameraForPoints(candidates + loc, …)` — a camera *aiming* call
that stage 4 did not touch (it is `camTarget`/frame-loop territory, explicitly #21's). The
authority half of the same code path — park held for the whole spin, camera not dragged back
to the vehicle while the spin owns the screen — is confirmed above and by transition 5, which
shows the identical park holding a camera on a point 2.6 km away while the vehicle drove on.

---

## Spin park vs drag park

| | park duration | bounded by |
|---|---|---|
| Drag (`Gesture` + `GestureEnd`) | **8.16 – 8.63 s** | `CAM_RESUME_QUIET_MS = 8000` + next 1 Hz fix |
| Spin (`SpinStarted`) | **3.74 s**, released < 0.6 s after the spin ended | `FollowCamera.shouldWatch` only — no quiet window at all |

## Crashes

None. `adb logcat -d -s AndroidRuntime:E System.err:W` returned **0 lines**; `logcat -b crash`
is empty; no `FATAL` and no Detour exception anywhere in the main buffer. Nothing to report.

## Verdict

**No behaviour change detected.** Every observed transition matched the rule the three
`remember`ed vars encoded before the move, and the two timings that could have exposed a
silent unification of `Gesture` and `SpinStarted` came out 8.6 s and 3.7 s respectively.

## Caveats and device state left behind

- **Real location providers were left enabled** (Wi-Fi and cell). The `detour-gps-replay`
  skill warns this can blend real fixes into the mock stream. No teleporting was seen on the
  map and the dot tracked the route throughout.
- **An operator error inflated the recorded trip.** A second `start-replay.sh` was issued at
  14:53 while the first replay thread was still running, so two threads emitted fixes from
  different points on the route for ~90 s (`MockLocation` logged *"replay finished at point
  762/762"* and *"replay finished at point 192/762"* one second apart). The debug variant's
  in-progress trip ran up to **~1538 km** as a result. After a clean `stop-replay.sh` +
  single start, distance advanced 1.4 km in 5 minutes — normal. **This is a harness artefact,
  not a tracking or camera defect**, and no park/resume measurement depends on it: the drag
  timing (transition 3) was taken at 14:50 under a single thread, and the spin timings at
  15:00 after the clean restart. The bogus trip was left in the debug variant's history
  rather than deleted.
- **`files/saved_places.json` now exists on the debug install** with one entry, `DeskCheck`,
  created for transition 5. It did not exist before this check. Left in place rather than
  removed, since deleting app data is out of bounds here — delete it from the app if unwanted.
- `stop-replay.sh` was used to stop the harness; the service record is gone
  (`dumpsys activity services com.jellemax.mocklocation` → 0 records), so `onDestroy` ran
  `removeTestProvider` and the device is not pinned to a stale mock position.
- Screen brightness (`screen_brightness = 0`) and `stay_on_while_plugged_in = 2` verified
  unchanged at the end. No repo file was modified and nothing was committed.

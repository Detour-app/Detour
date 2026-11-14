# Desk battery/frame profile

`battery-profile.sh` measures what the map costs while a replayed ride moves it: frame-clock
ticks, main-thread CPU, the MapLibre surface rate, and wakelock/sensor time. It is the
"measure first" step of #892, so a change to the frame loops in `ui/MapCamera.kt` or to
`tracking/LocationRequests.kt` can be compared before and after at a desk.

It does **not** measure drain. The emulator has no mAh, no GNSS and a 60 Hz panel; real drain,
LTPO and GPS cost come from the ride on #809.

## What it measures

| Column | Source | Why that source |
| --- | --- | --- |
| `ticks_per_s` | `atrace view`: top-level `Choreographer#doFrame` slices on the app's main thread (tid = pid) | `gfxinfo` misses frames MapLibre draws on its own GL surface; the frame clock is what the four `withFrameNanos` loops tick on |
| `main_cpu_pct` | `top -H -p <pid>`, second sample = average over the window | the frame loops run on the main thread |
| `surface_fps` | `dumpsys SurfaceFlinger --timestats`, `totalFrames` of the app's `SurfaceView` layer ÷ window | MapLibre's own render rate, separate from Compose |
| `wakelock_s`, `sensor_s` | `dumpsys batterystats --checkin` since `--reset`, partial wakelocks and sensor time (summed over sensors) for the app's uid | GPS through the fused provider is charged to Play Services' uid, not the app's, so it is not in these columns; the `--charged` dump next to them has the rest |

The capture is atrace's text output rather than a binary perfetto trace: same
`tracing_mark_write` events, and it needs no `trace_processor` on the host. If atrace's ring
buffer wraps, `ticks_per_s` reads `n/a` with a warning instead of an undercount. Raw files for every
row stay in the output directory, next to `summary.tsv`, so a number can be re-derived with
`summarize.py` without re-running anything.

## Rows

| Row | Build | Route window | Measures |
| --- | --- | --- | --- |
| `still` | release (minified) | `public-stop-start.txt` lines 1-492: 53 s moving, then the 439 s standstill | 120 s in, at rest |
| `moving` | release (minified) | `turn-circle.txt` looped, **off-route** (#38's marker numbers were taken off-route) | 30 s in, 45 km/h constant turn |
| `navigated` | **debug (unminified)** | `public-stop-start.txt` lines 1-1100, navigated in-app | 120 s in |

`navigated` uses the debug build because a release build with no routing server hands
navigation to an external maps app (`routeOpensExternally`, `ui/RoutesScreen.kt`). Its absolute
CPU is inflated by the unminified build: compare it only before/after against itself, never
with the other two rows.

Default window is 180 s (`--seconds`, 30-360). The release build replays at 1x only (it has no
`DebugReplayReceiver` to scale its clock), so a run of `still,moving` takes about 13 minutes.

## One-time setup

**A dedicated, fresh AVD.** The replay records a fake ride into the app's history, and
`batterystats --reset` wipes the device's battery history, so never point this at an AVD or
phone whose data matters. The script refuses a physical device unless `--allow-device` is
passed.

```sh
avdmanager create avd -n detour-profile -k "system-images;android-36;google_apis;x86_64" -d pixel_6
emulator -avd detour-profile -no-snapshot &   # headless hosts: add -no-window -gpu swiftshader_indirect
```

`google_apis` (not AOSP): the app reads Play Services' fused provider.

**The mock-location harness** (see `detour-gps-replay`):

```sh
(cd tools/mocklocation && ./gradlew assembleDebug)
adb -s emulator-5554 install tools/mocklocation/build/outputs/apk/debug/DetourMockLocation-debug.apk
adb -s emulator-5554 shell appops set com.jellemax.mocklocation android:mock_location allow
```

**The release build.** Without `RELEASE_KEYSTORE` the release APK is unsigned. Sign it locally
with the debug keystore, and install it **only on the dedicated AVD**, which has never had a
release-signed one, so nothing has to be uninstalled to make room:

```sh
./gradlew :app:assembleRelease
BT="$ANDROID_HOME/build-tools/36.0.0"
"$BT/zipalign" -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk /tmp/detour-release-aligned.apk
"$BT/apksigner" sign --ks ~/.android/debug.keystore --ks-pass pass:android \
    --out /tmp/detour-release-debugsigned.apk /tmp/detour-release-aligned.apk
adb -s emulator-5554 install /tmp/detour-release-debugsigned.apk
```

Open it once by hand and get past first-run screens to the map; the script launches
`MainActivity` and grants location, and measures whatever is on screen.

**For `navigated` only:** the debug build, pointed at a routing server.

```sh
./gradlew :app:assembleDebug
adb -s emulator-5554 install app/build/outputs/apk/debug/app-debug.apk
```

Either the dev rig with the Belgium extract (`public-stop-start.txt` is in Leuven, outside the
default Luxembourg extract; Belgium needs about 8 GB of heap and a few GB of disk, see
`tools/dev-rig/README.md` "Another region"):

```sh
RIG_EXTRACT_URL=https://download.geofabrik.de/europe/belgium-latest.osm.pbf RIG_GH_HEAP=8g \
    tools/dev-rig/up.sh emulator-5554
```

or any GraphHopper with Belgium in its graph, set with
`RIG_ROUTING_URL=<url> tools/dev-rig/point-emulator.sh emulator-5554`.

## Running it

```sh
tools/battery-profile/battery-profile.sh --serial emulator-5554                    # still, moving
tools/battery-profile/battery-profile.sh --serial emulator-5554 --rows navigated   # needs you at the terminal
```

Per row it force-stops the Detour variants not under test, checks with `pidof` that exactly one
Detour process runs before and after the window (and that it didn't restart), unplugs the
battery in batterystats for the window (a `trap` runs `dumpsys battery reset` on any exit), and
prints one table at the end. `navigated` pushes a GPX of its window to
`/sdcard/Download/battery-profile-navigated.gpx` (thinned to one point per 25 m, about 44 in
all: the standstill is ~215 distinct fixes jittering within 12 m, and `RouteGpx` thins by
index, so keeping them would stack via stops there) and waits for
you to import it in Routes and start navigating.

Output: `build/battery-profile/<timestamp>/` (`--out` to choose), `summary.tsv` plus every raw
capture.

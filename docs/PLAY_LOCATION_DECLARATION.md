# Play Console — background location declaration

What Play needs before Detour can ship with `ACCESS_BACKGROUND_LOCATION`.
Form lives at: **Play Console → Policy and programmes → App content →
Sensitive app permissions → Location permissions → Start declaration**.

> **Push-to-talk is currently switched off in the app** — the server relay drops
> voice frames, so the talk button is not reachable. `RECORD_AUDIO` and
> `FOREGROUND_SERVICE_MICROPHONE` are still declared in the manifest and
> `ConvoyLiveService` still declares `location|microphone`, so the microphone
> half of this declaration is still required and the copy below still applies.
> The convoy video shows the feature working, which it did when recorded. If
> voice is not back by the time this is submitted, re-record that take without
> the microphone shots and say in the form that the capability is present but
> disabled — describing a feature a reviewer cannot reach is what gets a
> declaration bounced.

## Why Detour needs it

`TripTrackingService` monitors activity recognition and location so a ride
starts and ends on its own. The user puts the phone in a pocket or a bar mount
and rides; the app is not on screen. Foreground-only location stops the moment
the app leaves the foreground, which is exactly when a ride happens.

## Prominent disclosure — required BEFORE the system dialog

Play policy: the app must show its own disclosure screen, in-app, before the
runtime prompt, and the prompt only fires if the user accepts.

Implemented as `BackgroundLocationDisclosure` in
`app/src/main/java/com/jellemax/detour/ui/MapScreen.kt`. `onLocationGranted()`
sets `showBgLocationDisclosure` instead of launching the system prompt
directly; **Allow** raises the prompt, **Not now** dismisses it and foreground
tracking still works. The wording names the app, the data, the purpose and the
fact that collection continues while the app is not in use — all four are
policy requirements, so do not trim it:

```
Detour collects location data to start, record and finish your rides
automatically, even when the app is closed or not in use.

Without this, a ride only records while Detour is open on screen. Your routes
stay on this device unless you turn on sync to your own server.
```

## Form answers

### Which feature requires background location?

```
Automatic trip tracking. Detour detects when the user starts driving or riding
a motorcycle and records the route, distance, duration,
average and top speed, and — on a motorcycle — lean angle and cornering g. The
trip ends by itself when the user stops. The recorded route also permanently
uncovers that area on the app's fog-of-war map and counts toward the
per-municipality road coverage statistic.
```

### Why can't the feature work with foreground location only?

```
A ride is precisely the time the app is not on screen. The phone is in a pocket,
in a jacket, or on a handlebar mount with the screen off. Foreground-only
location would end the recording at the moment tracking needs to begin, leaving
every trip a broken fragment.

Detour also has to decide on its own when a trip begins. It watches for a
sustained driving/cycling pace via Activity Recognition and only then promotes
the session to a foreground service. That watching happens while the app is in
the background, so background location is required for the detection itself,
not only for the recording that follows.
```

### Data handling

```
Location is stored on the device. It is uploaded only to the user's own
self-hosted sync server when the user signs in and enables sync. It is never
sold, never shared with third parties, and no advertising or analytics SDK
receives it.
```

Keep this consistent with `docs/privacy.html` and the Data safety form.

## Video

### Requirements Play enforces

- Link must open without a login. **Unlisted YouTube** is the safest choice;
  Google Drive works only if sharing is "Anyone with the link".
- Must show the real app, live, on a device or emulator. Slideshows and mockups
  get rejected.
- Must show the in-app prominent disclosure and then the system permission
  dialog, in that order.
- Must show the feature actually using the permission.
- Narration or on-screen captions in English. 30–90 seconds is plenty.
- Do not password-protect, do not set an expiry.

### Shot list

As recorded in `docs/play/permission-video.mp4` (2:03):

1. **0:00–0:10** — Fresh install, app opens on the world map, nothing granted.
2. **0:10–0:21** — The three runtime prompts in order: location → physical
   activity → notifications.
3. **0:21–0:31** — The app's own disclosure, held long enough to read. This is
   the shot the declaration turns on.
4. **0:31–0:42** — Tapping **Allow** opens Android's own location page;
   **Allow all the time** is selected there.
5. **0:42–1:12** — The phone moves at driving speed, the trip starts on its
   own, and time, distance, speed and cornering g tick up.
6. **1:12–1:32** — Detour leaves the foreground (Settings on screen) while the
   trip keeps recording.
7. **1:32–1:44** — Back in the app: the timer and distance advanced while it
   was in the background. That jump is the proof.
8. **1:44–2:03** — Trip history shows the saved trip with its route, distance
   and speed.

Two things are deliberately **not** in the video:

- **The notification shade.** It is the obvious place to show the ongoing-trip
  notification, but on a real phone it also shows incoming personal messages
  and the system's "Mock location is on" banner. The timer jump in shot 7
  proves the same thing without either.
- **The launcher and the Settings root.** Both carry personal content — the
  wallpaper on one, the account name and photo on the other. Shot 6 uses
  Settings → Date and time, which carries neither.

### How to record

`tools/record_permission_video.sh` does the capture and the captions. Plug in a
phone with USB debugging on, install a release-equivalent build, then:

```
./tools/record_permission_video.sh
```

It clears the app's data so the permission prompts fire fresh, starts
`screenrecord`, and walks the five shots above one prompt at a time — press
enter when each is done. It pulls the take to `docs/play/permission-video-raw.mp4`.

Watch the take, adjust the caption times, then render every video with:

```
./tools/render_play_videos.sh
```

Do **not** call `record_permission_video.sh caption` directly on a raw other
than the trip one: `FINAL` defaults to `permission-video.mp4`, so it silently
overwrites the trip video with whatever footage you passed. The wrapper sets
`FINAL` and `CAPTIONS_FILE` for each video and refuses to start while another
ffmpeg is running — two renders writing the same file produce a truncated file
whose duration reads as a few seconds.

Caption times are checked with **accurate** seeking (`ffmpeg -i FILE -ss N`).
Putting `-ss` before `-i` seeks by keyframe and lands several seconds off on
screenrecord's variable framerate, which is how the first caption pass ended up
mistimed.

### Faking the drive

The recording needs the phone to move. Auto-start is speed-based
(`TripTrackingService.onStartDetectorLocation`, `FAST_SPEED_MPS = 7.0`, three
fixes, 8 s, 120 m), so a simulated route drives it just as a real one does.

Feeding mock fixes from adb alone does **not** work. `cmd location providers
set-test-provider-location` reaches `LocationManager`, but Detour reads Play
Services' fused provider, which only honours mocks from the app the system has
designated — and only when the Location carries a fresh
`elapsedRealtimeNanos`. Granting `android:mock_location` to the shell does not
help either; the fix has to come from a designated app.

`tools/mocklocation/` is that app: a standalone one-service build that replays
a route through `setTestProviderLocation`. Set it up once:

```
cd tools/mocklocation && ./gradlew assembleDebug
adb install -r build/outputs/apk/debug/DetourMockLocation-debug.apk
adb shell appops set com.jellemax.mocklocation android:mock_location allow
```

The route is a plain "lon lat" per line file, one point per second. Scoped
storage blocks `/sdcard`, so write it into the app's own data directory:

```
adb shell "run-as com.jellemax.mocklocation sh -c 'cat > files/route.txt'" < route.txt
adb shell am start-foreground-service -n com.jellemax.mocklocation/.MockService \
    --es route /data/data/com.jellemax.mocklocation/files/route.txt --ei intervalMs 1000
adb shell am stopservice -n com.jellemax.mocklocation/.MockService
```

Build the route from a real driving line so the recorded track follows actual
roads — an OSRM query densified to one point per second at ~45 km/h works well.

**Force-stop the release app first** (`adb shell am force-stop
io.github.maxke24.detour`). It monitors for trips, and a mock stream will
otherwise write a fabricated ride into your real history.

The script records the `.debug` variant by default. It carries the `.debug`
applicationId suffix, so it installs alongside the real app and the `pm clear`
cannot touch your trip history. It is otherwise the same UI.

### The video is a simulated drive

Worth deciding consciously before submitting: the app behaviour, the
permission flow and the recorded trip are all genuine, but the GPS input is
replayed rather than driven. Recording on an emulator — which Google's own
guidance accepts for these declarations — is the same thing by a different
route. If you would rather submit a real drive, re-record with
`tools/record_permission_video.sh` on an actual ride; nothing else changes.

Captions matter: `screenrecord` captures no audio, so the burned-in text is the
only narration the reviewer gets.

## Foreground service permissions (separate form)

App content → **Foreground service permissions**. Every ticked box gets its own
**Video link** field, so four boxes means four links — the same URL can be
pasted into more than one field where one video covers both tasks.

| Box | Video |
|---|---|
| Location → Other (trip recording) | `docs/play/permission-video.mp4` |
| Location → Navigation | `docs/play/navigation-video.mp4` |
| Location → User-initiated location sharing | `docs/play/convoy-video.mp4` |
| Microphone → Background audio input | `docs/play/convoy-video.mp4` (same link) |

Detour declares two foreground service types
(`app/src/main/AndroidManifest.xml`):

| Service | Type | What it does |
|---|---|---|
| `TripTrackingService` | `location` | Records the ride; also feeds `NavEngine` for turn-by-turn, and posts a low-cadence fix to any circle the user shares with |
| `ConvoyLiveService` | `location\|microphone` | Streams position to convoy members, captures push-to-talk |

### FOREGROUND_SERVICE_LOCATION

Under **Background location updates**, tick:

- **User-initiated location sharing** — two features now, both opt-in.
  `ConvoyLiveService`: when the user joins a convoy, their position is streamed
  to the other riders in it. Its notification reads "Sharing location,
  listening for push-to-talk". The user starts this deliberately by joining;
  leaving the convoy stops the service. **Circles**: `TripTrackingService`
  additionally posts the latest fix, every couple of minutes, to each circle
  the user has joined *and* left the per-circle sharing switch on for. Nothing
  is posted for a user who is in no circle or has paused every one, and the
  pause is enforced server-side as well as on the device.
- **Navigation** — `NavEngine` drives in-app turn-by-turn to the spun
  destination, off the fixes `TripTrackingService` publishes. The screen is
  often off or the app behind the driver's music app while this runs.
- **Other** — see below.

Tick **Geofencing**. Detour registers exactly one geofence: a single
transition-EXIT circle around the position where the rider parked, used only
to let the trip-tracking foreground service stop itself while the phone is
stationary and be woken by the system when the rider rides away (`ParkGeofence`
in `app/.../tracking/`). It carries no radius of interest beyond that wake and
is removed the moment the service starts.

The auto-stop "back where you started" check and a circle's arrive/depart
events still use no geofence API — both are plain on-device arithmetic against
fixes that already arrive (`GeofenceEvaluator` in `shared/`).

"Other" description:

```
Automatic trip recording. Detour detects that the user has started driving,
riding or cycling and records the route, distance, duration, average and top
speed, and on a motorcycle the lean angle and cornering g. Recording continues
with the app in the background, which is the normal case — the phone is in a
pocket or on a handlebar mount. A persistent notification shows the trip is
being recorded and offers a one-tap End trip action. This is not sharing, not
navigation and not geofencing, so none of the listed categories fits it.
```

### The Navigation video

`docs/play/navigation-video.mp4` (1:46), recorded the same way as the trip one.
Shot list: search a destination → the app routes to it → **Go → Navigate in
app** → turn-by-turn runs with distance-to-turn and arrival time → the app
leaves the foreground → back in, further along the route with the remaining
distance counted down.

Three things had to be right, and each one cost a take:

1. **The Go button opens a chooser** (Navigate in app / Google Maps / Waze /
   Other app). A script that taps Go and then waits records nothing but an open
   menu. `Navigate in app` has to be tapped too.
2. **The mock route must come from the app's own router.** Feeding an OSRM line
   while the app routes through the self-hosted GraphHopper puts a red
   "Off route" banner on screen within seconds. Query the same `/route`
   endpoint the app uses (`RoutingClient.route`) and densify *that* geometry —
   config is in `local.properties`, and the request needs a `User-Agent` header
   or Cloudflare Access answers 403.
3. **Feed fixes at 2 Hz, and mock `network` and `passive` as well as
   `gps`/`fused`.** At 1 Hz over only gps/fused, the fused provider mixes in
   the phone's real position — the trip distance jumps to hundreds of
   kilometres and navigation flaps on and off route, especially right after the
   app returns from the background.

Trip auto-detection is switched off for this take (`auto_detect_drives=false`
in the app's prefs). Trip recording has its own video; leaving it on here only
puts a distance counter on screen that the navigation shot does not need.

### FOREGROUND_SERVICE_MICROPHONE

Tick **Background audio input**, not Other.

```
Push-to-talk inside a convoy. While riders are in a convoy together, holding
the talk button captures microphone audio and streams it to the others, the
same as an intercom. The rider is on a bike with the phone mounted or pocketed,
so capture has to work with the app off screen. The microphone is only ever
opened while a convoy is joined and the talk button is held; the foreground
service notification is visible the whole time the convoy is active.
```

### The convoy video

`docs/play/convoy-video.mp4` (1:16) covers **both** the User-initiated location
sharing box and the microphone box, so the same link goes in both fields.

Shot list: map with no convoy → Friends → **Go live** on a convoy → the
microphone runtime prompt (it only appears at this point, never at launch) →
the row flips to **Stop live** → back on the map, convoy chip and push-to-talk
button present → the button is held and turns red while the mic is open →
**Stop live** ends both.

Recorded on the real, signed-in app: convoys go through `Api.request` to the
sync server, so the `.debug` build (signed out) cannot reach them. Two things
were done to keep that safe:

- **A throwaway solo convoy.** Created with no invites, so no live position was
  broadcast to another rider, and deleted afterwards. Going live on the real
  convoy would have streamed location to whoever is in it.
- **RECORD_AUDIO revoked before the take**, so the prompt is on camera, and
  revoked again afterwards to leave the app as it was.

The video shows the signed-in username, one friend's username with their ride
count and distance, and the saved-place chips. That was a deliberate choice —
re-record or blur those regions if it should not be public.

A two-device convoy with audio actually crossing between riders would be
stronger evidence still, but needs a second phone on a second account.

## Also on the same page

`RECORD_AUDIO` and `FOREGROUND_SERVICE_MICROPHONE` (convoy push-to-talk) do not
need this declaration, but the microphone must appear in Data safety and the
privacy policy. `ACTIVITY_RECOGNITION` needs no declaration.

## Timeline

Form fields: ~20 minutes with the copy above. Video: ~30 minutes including the
emulator route. Disclosure dialog code: ~30 minutes. Review after submission is
typically a few days and can bounce more than once.

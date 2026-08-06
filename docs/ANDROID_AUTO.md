# Android Auto

The car screen lives in `app/src/main/java/com/jellemax/detour/car/`, in the
same APK and process as the phone UI. `DetourCarAppService` is what Android
Auto binds to; `SpinScreen` is the first screen, `NavScreen` the turn-by-turn one.

## Why it works on the Desktop Head Unit but not in the car

This is not a bug and no manifest change fixes it. Android Auto applies a
different rule to the two:

- The **Desktop Head Unit** honours the *Unknown sources* developer toggle, so a
  sideloaded APK — a debug build from Android Studio, or the signed release APK
  downloaded from the GitHub Actions build — shows up and runs.
- A **real head unit** does not. Google's
  [Test Android apps for cars](https://developer.android.com/training/cars/testing)
  states that *Unknown sources* "applies to media, messaging notifications, and
  parked apps but **doesn't apply to apps built using the Android for Cars App
  Library**", and that "to test your app in real vehicles, you must install it
  from a trusted source such as Google Play".

Detour's car screen *is* an Android for Cars App Library app
(`androidx.car.app`, `CarAppService`, `androidx.car.app.category.NAVIGATION`), so
it falls squarely under the exclusion. Installed by sideloading — `adb install`,
a file manager, a browser download — it will never appear in the car's app
launcher, no matter how the phone's developer settings are configured. The car
checks *who installed the package*, not what the APK contains.

## Getting it onto a real head unit

Install it through Google Play. Neither option below goes through app review or
makes anything public, but both need a Play Console developer account.

**Internal app sharing** — the lightest route, and the one to use for a quick
test drive:

1. In the [Play Console](https://play.google.com/console), open **Internal app
   sharing** and upload `app-release.apk` from the GitHub Actions build. The
   uploaded APK does not need to be signed with a Play upload key, so the
   artifact CI already produces works as-is.
2. Add the Google account used on the phone to the uploaders/testers list.
3. On the phone, enable internal app sharing in the Play Store: **Settings →
   About → Play Store version**, tap it 7 times.
4. Open the share link from the console on the phone and install from there.

**Internal test track** — better once the car app is something you use rather
than test: uploads get a version code check and stay installed as normal Play
updates, so the head unit picks up new builds without re-sharing a link.

Either way the installer becomes the Play Store, which is the only thing the
head unit is actually checking.

Uninstall any sideloaded copy first. A debug build installs alongside the
release one (`applicationIdSuffix = ".debug"`, see `app/build.gradle.kts`), and
that one stays invisible in the car regardless — only the Play-installed
`io.github.maxke24.detour` will list.

## After it appears

Reconnect the phone to the car once the install source is right; head units
cache the app list across a session. If the icon still does not show, confirm
the app is not switched off under **Android Auto → Customise launcher** on the
phone, and open Detour on the phone once so it has location permission —
`SpinScreen` cannot spin without it and shows a "Location needed" message
instead.

## What turn-by-turn actually consists of

`NavScreen` owes the head unit three separate things, and only the first is the
one you see while Detour is the app on screen:

- **The template** (`onGetTemplate`) — the `NavigationTemplate` with a
  `RoutingInfo` card. Rebuilt only when the maneuver, the rounded distance or
  the ETA minute changes: the host redraws on every `invalidate()`, and at one
  GPS fix a second an identical redraw is pure traffic over the projection link.
- **The trip** (`NavigationManager.updateTrip`) — what feeds the instrument
  cluster and the host's own turn card, i.e. everything the car shows when the
  driver is looking at a *different* car app. A navigation app that never calls
  it renders correctly on its own screen and appears to the car as if nothing
  is being navigated.
- **The voice** (`NavVoice`) — the only one of the three that works while you
  are looking at the road. Android Auto has no voice API: a projected app
  speaks through the phone's TTS, and the head unit routes it by
  `AudioAttributes` *usage*. `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` is what puts
  it on the cabin speakers and ducks the radio;
  `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` is what keeps the music playing quietly
  under it instead of stopping for the whole drive. Prompts fire at 800 m,
  300 m and at the turn, once each, and the speaker button on the nav screen
  mutes them (`Settings.voiceGuidance`).

### Maneuvers throw

`Maneuver.Builder(type).build()` is not total. The roundabout enter-and-exit
types are rejected without an exit number —
`IllegalArgumentException: Maneuver missing roundaboutExitNumber` — so building
one from a GraphHopper `sign: 6` instruction without passing its `exit_number`
crashes the app on the first roundabout of the drive. GraphHopper sends the
number; it has to be forwarded, and where it is absent (0, or negative when the
router cannot tell) the plain `TYPE_ROUNDABOUT_ENTER_CCW` is the one that
builds. The same care applies to anything else built from router output: the
whole per-fix loop runs inside a `try`, because an exception escaping a
coroutine here takes the process down mid-drive rather than dropping a frame.

## Keeping the map moving

GPS arrives about once a second. A map that only moves when a fix lands reads
as a broken app, so `CarMapRenderer` eases the camera toward the last fix on a
~30 ms timer and pushes it only when the step is big enough to see — the same
follow loop the phone map uses, with two car-specific differences:

- It is driven by a timer, not `Choreographer`/`withFrameNanos`. The map lives
  on a `VirtualDisplay` that keeps running with the phone's own screen off, and
  vsync callbacks do not.
- Overlay state (route, position, cameras, friends) is held by the renderer
  rather than pushed per fix. A full `MapOverlays.render` re-serialises the
  route polyline — a few thousand points on a long route — into GeoJSON and
  hands the map a new line to tessellate. Once a second, on a head unit, that
  alone is enough to make everything feel stuck; the per-fix update is now the
  one-point position source, and the line is re-pushed only when the route
  changes (start, reroute) or a new surface needs refilling.

## Debugging on the car

With the phone connected to the head unit over USB, `adb` still works over
Wi-Fi (`adb connect`), or use wireless Android Auto and plain `adb`:

```sh
adb logcat -s CarApp:V CarAppService:V GH.CarClientManager:V GH.AppsFilter:V
```

`GH.*` tags come from the Android Auto host itself and say whether it saw the
app and why it filtered it out — that is where an install-source rejection shows
up, rather than as anything from this app.

## Things that differ from the DHU at runtime

Worth knowing when something renders correctly on the desktop and not in the
car:

- **Surfaces are recreated.** The DHU creates one surface and keeps it. A real
  head unit hands out a fresh one every time the user switches to another car
  app and back, so `SurfaceCallback.onSurfaceAvailable` fires repeatedly and
  everything torn down in `onSurfaceDestroyed` has to be rebuildable — see the
  MapView handling in `CarMapRenderer`.
- **Screen size and density vary.** The DHU reports 800x480 at 160dpi by
  default; real units are larger and denser, and often not 16:9. Size the car
  HUD in dp, never raw pixels.
- **The visible area is smaller than the surface.** The host draws its own
  chrome over part of what the app renders and reports the usable rectangle
  through `onVisibleAreaChanged`, which arrives independently of the surface.

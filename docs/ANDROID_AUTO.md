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

# Stage 1 desk checklist — on-device verification of the MapScreen split

**Branch:** `refactor/mapscreen-split`
**Device:** Galaxy Z Fold 3 (`SM_F926B`), serial `RFCT42HS9WY`, Android 16
**Build under test:** `io.github.maxke24.detour.debug` (already installed; not rebuilt or reinstalled)
**Display:** inner panel, display id `4630947232161729154` (1768×2208), used for every `screencap`
**Date:** 2026-08-12, 08:03–08:21 local device time

All screenshots live under
`/tmp/claude-1000/-home-andre-Projects-Detour/b8377e9c-09a6-41c2-b1cb-c82f473fc14e/scratchpad/desk/`
(paths below are relative to that directory). UI hierarchies were dumped with
`uiautomator dump` alongside most screenshots and are stored as `.xml` next to them.

**Verdict up front:** 9 of 10 items pass, 1 not performed (rotation, blocked by a
setting I was not permitted to change). **No crash, no exception, no stack trace**
in `logcat` for the entire session — the crash buffer is empty and the only
`AndroidRuntime` lines belong to my own `uiautomator` invocations. One cosmetic
oddity was found (persistent map dim after the search dialog) and was traced to
code that is **byte-identical to `main`**, so it is pre-existing and not a
regression from the split.

---

## 1. App launches; map renders vector tiles — PASS

`01-launch.png`

Cold launch (`am force-stop` then `am start`) reached the map in under 8 s. The
basemap is genuinely rendered, not a blank surface: building footprints, road
casings and two named streets ("Eikenstraat", "Olmendreef") are drawn, the
MapLibre attribution logo sits bottom-left, and the blue location puck is on
screen with its accuracy halo. The fog-of-war cleared disc is visible around the
current position.

Only `System.err` output was three benign SLF4J "Failed to load class
StaticLoggerBinder / defaulting to NOP logger" warnings at startup.

## 2. Top chrome: search pill + avatar, and a two-button rail — PASS

`01-launch.png`, hierarchy `01-launch.xml`

- Search pill spanning the width with placeholder `Where to?` (TextView bounds
  `[163,142][1620,205]`).
- Avatar circle at the pill's right end: a blue circle at `[1674,147][1693,200]`
  rendering `?` as the fallback initial (no username set). Confirmed as the
  avatar, not a help button, by `MapChrome.kt:60` ("a full-width search pill
  with an avatar that opens the Hub") and the `onAvatarClick` wiring at
  `MapChrome.kt:144,174`.
- Below it, right-aligned, exactly two round buttons:
  `content-desc="Stop following my location"` at `[1658,279][1711,332]` and
  `content-desc="Map layers"` at `[1658,405][1711,458]`.

## 3. Layers button opens the panel, and a second tap closes it — PASS

`03a-layers-open.png` (open), `03b-layers-closed.png` (closed)

First tap on the layers button (1684, 431) slid in an inline panel containing an
eye icon, the label **"Fog of war"** and a switch in the ON position; the layers
icon itself turned blue to show the active state.

Second tap on the same button closed the panel. Verified two ways: the
screenshot shows the panel gone and the layers icon back to black, and the
`uiautomator` dump (`03b-layers-closed.xml`) no longer contains the text
"Fog of war" at all. This is the behaviour the recent bug fix targeted, and it
works.

## 4. Search pill opens a full-screen dialog; suggestions appear; back arrow dismisses — PASS

`04a-search-open.png`, `04b-search-suggestions.png`, `04c-search-dismissed.png`

Tapping the pill opened a full-screen dialog with a back arrow at the top-left,
a focused field with placeholder "Search address or place" (blue focus ring),
and the soft keyboard up (the split foldable layout).

Typing `Brussel` produced seven geocoder suggestions within ~5 s, each with a
place-pin leading icon:

- Bruxelles - Brussel, Brussel-Hoofdstad - Bruxelles-Capitale, België
- Brussel-Hoofdstad - Bruxelles-Capitale, Région de Bruxelles-Capitale - Brussels Hoofdstedelijk Gewest, België
- Brussels Airport, Steenokkerzeel, België
- Brussels Airport, Zaventem, België
- Bruxelles - Brussel, België
- Brussels Airport-Zaventem, Zaventem, België
- Bruxelles - Brussel - Brussels, Schaerbeek - Schaarbeek, België

The back arrow dismissed the dialog and returned to the map with the `Where to?`
pill restored (`04c.xml` contains no search field).

**Cosmetic side effect noted — see "Additional finding" below:** the map surface
stays visibly dimmed after this dialog is dismissed.

## 5. Collapsed spin dock at the bottom — PASS

`01-launch.png`, hierarchy `01-launch.xml`

All five elements present, left to right:

| element | evidence |
| --- | --- |
| mode icon | car glyph at the dock's left edge |
| "N km" label | `text="25 km"` at `[163,1790][267,1843]` |
| direction line | `text="Car · any direction"` at `[163,1843][433,1885]` |
| chevron / expand affordance | `content-desc="Expand"` at `[459,1806][522,1869]` |
| round dice button | `content-desc="Spin"` at `[1479,1806][1542,1869]`, filled blue |
| round Go button | `content-desc="Go"` at `[1626,1806][1689,1869]`, grey/disabled with no destination |

## 6. Dock's left cell expands into the spin sheet; chevron collapses it — PASS

`06a-sheet-expanded.png`, `06b-direction-scrolled.png`, `06c-sheet-collapsed.png`

Tapping the dock's left cell (300, 1830) expanded the sheet. Every element the
checklist names is present:

- drag handle (the small pill centred at the top of the sheet)
- heading **"Spin a destination"** with a collapse chevron
  (`content-desc="Collapse"` at `[1626,668][1689,731]`)
- destination-type pill row: **Road** (selected), **Viewpoint**,
  **Food & drink**, **Sight**
- **Radius** slider, value `25 km`, thumb about a quarter along the track
- **Min distance** slider, value `Off`, thumb at the far left
- **Direction** pill row: Any (selected), North, North-east, East, South-east,
  South, South-west, We… (clipped at the right edge)
- full-width blue **Spin** button with a dice icon
- **Go** (disabled) / **Track car** row at the bottom

Horizontal scrolling of the direction row was confirmed by swiping it left
(`input swipe 1400 1518 500 1518`): the row then read North-east, East,
South-east, South, South-west, **West**, **North-west** — two pills that were
off-screen before scrolled into view (`06b.xml`).

The chevron collapsed the sheet back to the dock; `06c.xml` no longer contains
"Spin a destination", "Radius" or any direction pill.

Slider interactivity was also exercised incidentally: dragging Radius to the far
left changed it to `7 km` and the collapsed dock label followed
(`06i-radius-min.png` shows `7 km / Car · any direction`). The radius was
restored to `25 km` afterwards and left that way.

## 7. Bottom navigation shows four modes; switching updates the dock — PASS

`07-mode-walk.png`, `07-mode-bike.png`, hierarchies `mode-walk.xml`, `mode-bike.xml`

Four modes present: **Walk**, **Bike**, **Moto**, **Car**. The dock label, the
distance and the dock's mode icon all track the selection:

| mode selected | dock label | dock icon |
| --- | --- | --- |
| Car (initial) | `25 km` / `Car · any direction` | car |
| Walk | `3 km` / `Walk · any direction` | walking figure |
| Bike | `10 km` / `Bike · any direction` | bicycle |

The selected item in the nav bar also gets its highlighted pill background. Car
was reselected afterwards so the app was left in the mode it started in.

## 8. Long-press drops a destination pin and a "Save pin" chip appears — PASS

`08a-longpress.png`, hierarchy `longpress.xml`

`input swipe 600 1150 600 1150 1000` (a 1 s press) dropped a red teardrop
destination pin at the pressed point, with its ground shadow. A **"Save pin"**
assist chip with a `+` leading icon appeared bottom-left among the shortcut
chips (`text="Save pin"` in the dump). The dock's **Go** button changed from
grey to an enabled blue-tinted state, since there is now a destination.

## 9. Dice / Spin button — PASS (succeeded; no error path needed)

`09a-dice-immediate.png` (t+3 s), `09b-dice-later.png` (t+11 s), `09c-dice-t45.png` (t+45 s)

Tapping the dice replaced the dice glyph with an in-place circular progress
indicator, which kept spinning for roughly 40 s. It then resolved
**successfully** — no error message, so the error path was not exercised and no
error text is available to quote. The result is the candidates card:

- title **"Pick a destination"**
- subtitle **"All three are on the map — tap a pin or a row."**
- three rows with coloured letter badges: **A** "Option 1 / ~ straight-line
  20.3 km", **B** "Option 2 / ~ straight-line 21.1 km", **C** "Option 3 /
  ~ straight-line 24.8 km"
- **Cancel** and **Reroll** buttons

So routing/candidate generation is reachable and working from this build; the
checklist's expectation of a possible failure did not materialise. `logcat`
(`AndroidRuntime:E`, `System.err:W`) was empty across the whole spin. The card
was dismissed with Cancel.

Note on appearance: the map behind the card is zoomed out and looks uniformly
soft/blurred. That is the fog-of-war "frost" (a downscaled, re-upscaled map
snapshot, `MapLibreMap.kt:551-605`) covering ground that has never been driven —
expected, not a rendering fault.

## 10. Rotation — NOT PERFORMED

`10a-rotated.png`, `10b-resumed.png`

`adb shell settings put system user_rotation 1` was issued as instructed but had
**no effect**: this device has auto-rotate enabled
(`settings get system accelerometer_rotation` → `1`), so the window manager
ignores `user_rotation` and the sensor keeps the panel in portrait. Confirmed
after the write: `dumpsys display` still reports `mCurrentOrientation=0`,
`dumpsys window displays` still reports `cur=1768x2208`, and the screenshot is
still 1768×2208 portrait.

Forcing the rotation would have required also writing
`settings put system accelerometer_rotation 0`, i.e. modifying a third device
setting. That is outside the two settings prepared for this session, so per the
run's constraints the check was skipped rather than worked around. The app
itself is not orientation-locked (no `screenOrientation` in
`app/src/main/AndroidManifest.xml`), so this is purely a device-state
limitation, not an app limitation.

**Pre-existing value warning:** `user_rotation` was **already `1`** before I
touched it (read before the write). I therefore restored it to `1`, not to `0`,
so the device is left exactly as found. Final state:
`accelerometer_rotation=1`, `user_rotation=1`.

Substitute evidence for the underlying concern (does the map screen survive
being torn down and rebuilt, and does the dock still show sensible values):

- Home → relaunch, activity resumed from the back stack: map re-rendered, dock
  still `25 km / Car · any direction`, pin and "Save pin" chip still present
  (`10b-resumed.png`).
- Four full process kills (`am force-stop`) and cold relaunches over the session
  all came back to a correctly rendered map with the dock reading
  `25 km / Car · any direction` (`06j-relaunch.png`, `07a-fresh.png`,
  `11-final-state.png`).

This is not a substitute for a real configuration change, and item 10 remains
formally not performed.

---

## Additional finding (not on the checklist): map stays dimmed after the search dialog

**Not a regression from this split.** Reported for completeness only.

Dismissing the full-screen search dialog leaves the MapLibre surface with a
persistent uniform grey wash of roughly 14 % — the chrome above it is unaffected.
Measured at the same map pixel (900, 700) across the session:

| moment | pixel |
| --- | --- |
| after launch | `rgb(223,234,240)` |
| layers panel opened / closed | `rgb(223,234,240)` |
| **after search dialog dismissed** | **`rgb(187,196,201)`** |
| after collapsing the spin sheet | `rgb(187,196,201)` |
| after a cold relaunch | `rgb(223,234,240)` |

Reproduced 2 of 2 attempts from a freshly launched process (`04c-search-dismissed.png`,
`07b-repro-after-search.png`, `07c-repro-settled.png`).

Properties established by experiment:

- Only the map surface dims. Chrome sampled in the same pair of screenshots is
  unchanged: dock `rgb(251,252,252)` → `rgb(249,249,250)`, bottom nav
  `rgb(237,243,250)` → `rgb(237,243,250)`.
- It survives panning the map (`06e-after-pan.png`), expanding and collapsing the
  spin sheet, and changing the spin radius.
- It is **not** the fog-of-war overlay: switching **Fog of war** off removes the
  cleared disc and the fog entirely, yet the wash remains
  (`06h-fog-off.png`, still `rgb(187,196,201)` at the map edge). `FogView.onDraw`
  returns immediately when inactive (`MapLibreMap.kt:640`), so the fog cannot be
  the source.
- It is **not** the spin-sheet scrim: expanding and collapsing the sheet on a
  freshly launched process leaves the map at `rgb(223,234,240)`
  (`06k-expanded-again.png`, `06l-collapsed-again.png`).
- It clears only on process restart.

**Why this is pre-existing rather than caused by the split:** the composable
responsible, `SearchDialog`, moved from `MapScreen.kt` into the new
`app/src/main/java/com/jellemax/detour/ui/MapDialogs.kt` (lines 59–175).
Diffing it against `main`'s copy (`MapScreen.kt` lines 1843–1959), normalising
only the `private`→`internal` visibility change the move required, reports the
two byte-identical. `MapLibreMap.kt` — which owns the map surface and the fog
view — is not among the files this branch touches at all. So the behaviour
predates the refactor; it is most likely a platform compositing artefact from
placing a full-screen `Dialog` over the MapLibre GL surface.

Worth filing separately, but it is not evidence against the split.

---

## Actions taken on the device, and cleanup

Only these were used: `am start`, `am force-stop`, `input tap/text/swipe/keyevent`,
`screencap`, `uiautomator dump`, `logcat`, read-only `dumpsys` and
`settings get`, and the one `settings put system user_rotation` the run
authorised. **No `uninstall`, no `pm clear`, no `pm revoke`, no account or
lock-credential change, no other setting written.**

In-app state changed during testing and then restored:

| in-app state | changed to | restored |
| --- | --- | --- |
| Fog of war | off | **on** (verified via panel screenshot) |
| Spin radius | 7 km, then 23 km | **25 km** (verified in `restored-final.xml`) |
| Travel mode | Walk, Bike | **Car** |
| Destination pin | dropped by long-press | cleared by the final relaunch |

The app was left freshly launched on the map screen with the dock reading
`25 km / Car · any direction` and the map rendering normally at
`rgb(223,234,240)` (`11-final-state.png`).

## Summary table

| # | Item | Result | Screenshot |
| --- | --- | --- | --- |
| 1 | Launch, vector basemap renders | PASS | `01-launch.png` |
| 2 | Search pill + avatar, two-button rail | PASS | `01-launch.png` |
| 3 | Layers panel opens, second tap closes | PASS | `03a-layers-open.png`, `03b-layers-closed.png` |
| 4 | Search dialog, suggestions, back arrow | PASS | `04a`–`04c` |
| 5 | Collapsed spin dock, all five elements | PASS | `01-launch.png` |
| 6 | Spin sheet expand, scroll, collapse | PASS | `06a`, `06b`, `06c` |
| 7 | Four travel modes, dock follows | PASS | `07-mode-walk.png`, `07-mode-bike.png` |
| 8 | Long-press pin + "Save pin" chip | PASS | `08a-longpress.png` |
| 9 | Dice/Spin (succeeded, 3 candidates) | PASS | `09a`, `09b`, `09c` |
| 10 | Rotation | NOT PERFORMED (auto-rotate on; would need a third setting write) | `10a-rotated.png`, `10b-resumed.png` |

**Crashes / exceptions / stack traces: none.** `logcat -b crash` is empty;
`logcat -d -s AndroidRuntime:E System.err:W` produced nothing beyond three
startup SLF4J NOP-logger warnings.

**Overall:** nothing visible is broken by the eleven-file split. Every surface
that moved — top chrome, layers panel, search dialog, spin dock, spin sheet,
travel-mode bar, long-press pin flow, candidates card — renders and behaves
correctly on the device.

---
name: detour-adb
description: >-
  Drive, inspect or install the Detour Android app on a physical device or emulator over
  adb. Use this whenever a task involves adb, a connected phone, an AVD, installing an APK,
  reading the app's on-device data, granting or revoking a runtime permission, capturing a
  screenshot or UI dump, or reproducing app behaviour by hand — and read it before the first
  adb command, not after one fails. It carries the package identity table (the Kotlin
  package is NOT the applicationId), the rules for what app data is readable on which
  variant, and the list of adb operations that destroy user data and must never be used as a
  workaround for a blocked step.
---

# Working with a Detour device over adb

## Preconditions

```sh
.claude/skills/detour-adb/scripts/check-preconditions.sh
```

Three assertions against `app/build.gradle.kts` and the debug source set, printed one line
each as `PASS`/`FAIL`, non-zero exit if any failed. If any fails, the identity table below is
stale — re-derive it from `app/build.gradle.kts` and fix this skill before running any
command here.

## Identity: three names, and only one of them works on the command line

The Kotlin namespace and the installed package name are deliberately different, and
`app/build.gradle.kts:44-46` says why in the source: the namespace is the Kotlin package and
the R class, invisible outside the build; the applicationId is the identity Play and the
device see, and Play fixes it permanently at first upload. So the split is not going to be
tidied up — plan around it.

| What | Value | Where |
|---|---|---|
| Kotlin package / namespace | `com.jellemax.detour` | source, `app/build.gradle.kts:47` |
| Release applicationId | `io.github.maxke24.detour` | `app/build.gradle.kts:51` |
| Debug applicationId | `io.github.maxke24.detour.debug` | `applicationIdSuffix`, `app/build.gradle.kts:100` |
| Mock-location harness | `com.jellemax.mocklocation` | `tools/mocklocation/build.gradle.kts:11` |

A component name mixes both halves — applicationId first, then the fully-qualified class:

```sh
adb shell am start -n io.github.maxke24.detour.debug/com.jellemax.detour.MainActivity
```

Grepping the source only ever shows you `com.jellemax.detour.*`. Pasting that as the package
half gives `Error: Activity class does not exist`, which reads like a build problem and is
not. That is the single most common wasted minute on this repo.

Both variants can be installed at once — that is what the `.debug` suffix is for
(`app/build.gradle.kts:97-99` states the reason: so a debug build installs alongside the
release-signed app "instead of forcing an uninstall, which would take the trip history with
it"). Before every stateful command, confirm which one you are talking to:

```sh
.claude/skills/detour-adb/scripts/variants.sh [serial]
```

One line per package: installed or not, version, and whether `run-as` actually works on it —
probed by running `run-as <pkg> true`, because that is the exact capability that decides
whether the data-reading half of this skill is available to you. The serial defaults to
`$ANDROID_SERIAL`, or to the only attached device.

## Never do these

Each of these looks, in the moment, like the pragmatic way past a blocked step. Each
destroys data the user cannot get back, and none of them is ever actually required.

- **`adb uninstall` on either variant.**
- **`pm clear`** on either variant — including "just to reset the settings". It clears
  `files/` too, which is the trip history and the fog traces.
- **Reinstalling over a differently-signed build** to get past an install error. Same
  outcome, one step removed.
- **Factory-resetting or wiping an AVD that has data in it.** Create a fresh AVD instead.

**None of these has a script in `scripts/`, and none ever will.** Everything else in this
skill was extracted so it could be run without retyping; these are left as prose precisely so
that running one takes a deliberate act of typing it out.

This has already happened here. An agent blocked by `pm revoke` on an OEM build uninstalled
and reinstalled the `.debug` variant to reach a clean "permission not granted" state. Its own
report said, under "Concern: app data was wiped": *"any login session, cached routes,
settings, or trip history that existed on this variant before I started is gone"*. The user
had to log back in and reconfigure.

> The quotes above used to be a citation to `.superpowers/sdd/task-5-report.md`. That path is
> **gitignored scratch, and every plan's Task 5 overwrites it** — so the reference resolved to
> whatever the most recent plan happened to write, which is how a reader checking it ends up
> at an unrelated report and concludes this section is wrong. The evidence is quoted inline
> now. Do not cite `.superpowers/` from a skill: it is per-session scratch, it rotates, and it
> is not in the repository a reader clones.

**Do not count on a backup to undo it.** `app/src/main/res/xml/backup_rules.xml` and
`data_extraction_rules.xml` now `<include>` the whole `files/accounts` subtree in
`<cloud-backup>` — every account-scoped store (`trips.json`, `traces.jsonl`,
`deleted_trips.json`, `edited_modes.json`, `badges.json`, `routes.json`, `saved_places.json`,
`municipalities.json`), under whichever hashed or `_local` bucket each lives in. Only
`recent_searches.json`, which stays at the `filesDir` root outside that subtree on purpose, has
**no cloud copy at all**. The login session in `shared_prefs/settings.xml` and
`shared_prefs/routing_server.xml` are excluded from cloud backup deliberately too, and appear
only under `<device-transfer>`, which is a phone-to-phone copy adb cannot trigger. You also
cannot tell from a shell whether any backup has ever run for this package, or whether the
signed-in rider's bucket is even the one that got restored. Treat the on-device copy as the
only copy.

If a step appears to require a wiped install, it requires a throwaway emulator instead. Say
that and stop. Do not improvise on the user's phone.

## When a permission command is refused

`pm grant`, `pm revoke` and `appops set` are not available to the `shell` user on every
build. A OnePlus CPH2449 on Android 16 (SDK 36) refused all three — the errors below are
quoted from that run's report rather than cited, for the reason given above:

```
SecurityException: Neither user 2000 nor current process has
android.permission.REVOKE_RUNTIME_PERMISSIONS
```

— and the `GRANT_RUNTIME_PERMISSIONS` and `MANAGE_APP_OPS_MODES` equivalents. That is OEM
policy, not a malformed command. No flag, no `-u 0`, no retry works around it, and the
absence of one of these permissions is not evidence that some other adb route exists.

Three acceptable responses, in order:

1. **A throwaway emulator.** AOSP / Google APIs images give `shell` the grant and revoke
   permissions OEM builds withhold, and a fresh AVD has no user data to lose. Anything that
   needs a permission matrix belongs there.
2. **The Settings UI**, driven by hand or by `uiautomator`:
   ```sh
   adb shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS \
     -d package:io.github.maxke24.detour.debug
   ```
   Note the ceiling: Android only stops showing the system permission dialog after **two**
   denials, so some first-run dialog states are genuinely unreachable on an
   already-granted install without revoking.
3. **Say it cannot be automated on this device and stop.** "This check needs an emulator or a
   manual pass; here is the exact state I could and could not reach" is a complete, useful
   answer. A destroyed install is not.

Whichever you pick, record the pre-change grant state so it can be put back:

```sh
adb shell dumpsys package io.github.maxke24.detour.debug | grep -A20 'runtime permissions'
```

## Reading the app's data

`run-as` works **only on a debuggable package** — here, the `.debug` variant. It is how
`docs/DEBUG_INTENTS.md:98-137` seeds trip history, and it is the only route to app-private
files without root.

```sh
.claude/skills/detour-adb/scripts/list-data-files.sh [serial] [package]
```

Lists `files/` and `shared_prefs/` with sizes and dates, and refuses cleanly if the package is
not debuggable. It lists and never `cat`s — see the credential rule below. It also gets the
quoting right, which is the trap: `adb shell run-as PKG sh -c 'ls -l files'` loses its
arguments and lists the data directory root instead, which looks like a plausible answer to a
different question.

Every file below except `recent_searches.json` lives under `files/accounts/<key>/`, where
`<key>` is `sha256(sub)` truncated to 16 hex characters — it cannot be guessed, so list the
directory first:

```sh
adb shell run-as io.github.maxke24.detour.debug ls files/accounts
```

A signed-out install has exactly one bucket, the literal `_local`. A signed-in one has that
account's hash, and possibly `_local` too if anything was recorded before signing in. Then
read one file deliberately:

```sh
adb shell run-as io.github.maxke24.detour.debug cat files/accounts/_local/trips.json
```

| File in `filesDir` | Defined at | Notes |
|---|---|---|
| `accounts/<key>/trips.json`, `accounts/<key>/deleted_trips.json`, `accounts/<key>/edited_modes.json` | `shared/src/commonMain/kotlin/com/jellemax/detour/data/TripStore.kt:31-33` | trips are keyed by `startTimeMs`; `Trip` has no id field |
| `accounts/<key>/traces.jsonl` | `.../data/TraceStore.kt:27` | one JSON **array per line = one segment**, each point `[lat, lon, timeMs, speedKmh, leanDeg]` (`TraceStore.kt:12-23`). `wc -l` counts segments, not points |
| `accounts/<key>/saved_places.json` | `.../data/SavedPlaces.kt:24` | |
| `accounts/<key>/badges.json` | `.../data/Badges.kt:61` | |
| `recent_searches.json` | `.../data/RecentSearchStore.kt:10` | the one file **not** under `accounts/<key>/` — stays at the `filesDir` root on purpose, so it stays out of the backed-up subtree |
| `accounts/<key>/routes.json` | `.../data/Routes.kt:106` | |
| `accounts/<key>/municipalities.json` | `.../data/Coverage.kt:116` | learned boundaries, cached |
| `shared_prefs/settings.xml` | `.../data/Settings.kt:146,160,221` | holds `auth_token` |
| `shared_prefs/routing_server.xml` | `.../data/RoutingServer.kt:65` | holds the Cloudflare Access client secret |

**Never paste the contents of `settings.xml` or `routing_server.xml` into a report, a commit,
a log excerpt or a pasted terminal transcript.** They are live credentials: a bearer token
and a CF Access client secret. The repo already treats them as such — both are excluded from
cloud backup precisely so they cannot outlive a revocation
(`data_extraction_rules.xml:4-11`). If you need to know whether an account is signed in,
report the fact ("an `auth_token` key is present"), never the value.

## What is not readable, and stop looking

The **release install's app-private data cannot be read over adb.** This is a boundary, not
a puzzle:

- `run-as` refuses a non-debuggable package.
- `adb root` is refused by `adbd` on a production build.
- Since Android 12, `adb backup` no longer carries app data for a non-debuggable app, so
  `adb backup`/`adb restore` is not a way around it either.
- The app's own backup rules target Google Drive and device-to-device transfer, neither of
  which adb reaches.

To get a real ride out of a release install, use the app: **Share on the trip detail screen
exports GPX** (`app/src/main/java/com/jellemax/detour/ui/TripDetailScreen.kt:444-447`).
`Gpx.writeForShare` writes into `cacheDir/shared/`
(`app/src/main/java/com/jellemax/detour/data/Gpx.kt:63-64`, `SHARE_DIR = "shared"` at `:28`)
— the only path the FileProvider is scoped to (`app/src/main/res/xml/file_paths.xml`) — and
hands a `content://` Uri to an `ACTION_SEND` chooser (`TripDetailScreen.kt:197,446`).

**Where the file ends up is whatever the receiving app does with it.** Do not claim a fixed
destination such as `/sdcard/Download/gpx/`. Ask the user which app they shared to, or pick a
receiver whose output path you know, then look there.

## Driving the app without touching data

Prefer these over hand-navigation: faster, repeatable, and they cannot wipe anything. Full
list and rationale in `docs/DEBUG_INTENTS.md`.

```sh
# Raise the real "Trip ended" notification for the newest trip in history
adb shell am broadcast \
  -n io.github.maxke24.detour.debug/com.jellemax.detour.debug.DebugTripEndedReceiver

# ...for a specific trip, by its start time
adb shell am broadcast \
  -n io.github.maxke24.detour.debug/com.jellemax.detour.debug.DebugTripEndedReceiver \
  --el start_ms 1786449800000

# Open a trip's detail screen directly (a production extra, not a debug hook)
adb shell am start -n io.github.maxke24.detour.debug/com.jellemax.detour.MainActivity \
  --el open_trip_start_ms 1786449800000

adb logcat -s DebugTripEnded   # the receiver logs which trip it picked
```

These exist only in the debug build — `app/src/debug/AndroidManifest.xml` is merged into
debug variants only, so none of it reaches release. Seeding history is documented at
`docs/DEBUG_INTENTS.md:98-137`; note the hazard it flags before you seed on a signed-in
build: `endTrip()` calls `SyncClient.syncQuietly()`, so synthetic trips can escape onto the
user's real sync server.

For anything that needs the device to **move**, do not fake the outcome — replay a route.
See the `detour-gps-replay` skill.

## Capturing state

```sh
.claude/skills/detour-adb/scripts/capture-state.sh <scratchpad>/ [serial [logcat-tag...]]
```

Takes a screenshot, a `uiautomator` hierarchy and a logcat snapshot at the same moment and
writes all three into the directory you name, printing the byte count of each. The screenshot
goes first, so anything that changes between the two shows up as a difference between the
artifacts rather than being hidden. The UI dump goes to `/dev/tty` rather than to a file, so
nothing is left on the user's device. A suspiciously small PNG is warned about — that is
usually a screen that is off, or `FLAG_SECURE`.

For a video, which needs a device-side file and a manual stop, stay on the command:

```sh
adb shell screenrecord --time-limit 30 /sdcard/r.mp4   # Ctrl-C to stop early, then adb pull
```

Write screenshots, recordings and dumps into the session scratchpad, never into the repo.

Assert what you **observed**, and name the artifact that shows it. "The snackbar appeared"
backed by a `uiautomator` text node plus a screenshot is a result. The same sentence backed
by nothing is a guess, and unverified device claims are this project's known failure mode —
a wrong claim in a report gets cited by later work and costs commits to undo.

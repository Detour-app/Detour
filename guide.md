# Implementation guide — fixing the audit findings

This guide turns `audit.md` into an ordered list of small, self-contained tasks.
It is written for an AI coding assistant to execute one task at a time.

## Ground rules — read before every task

1. **One task per commit.** Do the task, verify it, commit with the message given,
   then stop or move to the next task. Never batch tasks into one commit.
2. **Touch only the files the task lists.** If you believe another file must change,
   stop and say so instead of changing it.
3. **Never reformat, rename, or "clean up" code you aren't asked to change.**
   This repo's comment style (long "why" comments) is deliberate — keep every
   existing comment unless the task says otherwise.
4. **Verify before committing.** Every task has a "Verify" section. If verification
   fails, fix your change or revert it — never commit red.
   - App changes: `./gradlew :app:assembleDebug` must succeed.
   - Wear changes: `./gradlew :wear:assembleDebug` must succeed.
   - Backend changes: `dotnet build backend/Detour.slnx` and both test suites must pass.
5. **Do not upgrade any dependency version** unless a task says to. In particular
   MapLibre stays pinned at 11.8.0 (Kotlin 2.0 compatibility).
6. **Tasks marked [HUMAN] need something only the repo owner can do** (create a
   keystore, add GitHub secrets, rewrite git history). Do the code part, then state
   clearly what the human must do — do not attempt their part.
7. If a task's description conflicts with what you find in the code, stop and
   report the conflict. The code may have moved since the audit.

Commit messages: conventional commits, subject ≤ 50 chars, end body with nothing
(no AI attribution needed beyond what the harness adds).

---

## Phase 1 — sync server hardening

Dropped. Every finding here was against a backend that no longer exists: the
service was rebuilt in .NET (`backend/`) with identity moved to Keycloak, which
answers the whole phase — rate limiting, registration defaulting closed, the
proxy-header trust question and the mid-merge error handling are all properties
of the new service, covered by its own tests.

## Phase 2 — Android app: security & correctness

### Task 2.1 — keep credentials out of cloud backup

**Problem** (audit 1.2): `settings.xml` (auth token) and `routing_server.xml`
(CF secret) are backed up to Google Drive.

**Files**: `app/src/main/res/xml/backup_rules.xml`,
`app/src/main/res/xml/data_extraction_rules.xml`.

**Change**: remove the two `sharedpref` include lines from `<full-backup-content>`,
from `<cloud-backup>`, **and keep them in `<device-transfer>`** (phone-to-phone
transfer is direct, not stored in the cloud — keeping settings there preserves the
seamless-new-phone experience). Add an XML comment in both files explaining that
the pref files hold the sync bearer token and CF Access secret, and the config-file
export (Settings → Server config file) is the supported way to carry credentials
across reinstalls.

**Verify**: `./gradlew :app:assembleDebug`.

**Commit**: `fix(app): keep credentials out of cloud backup`

### Task 2.2 — validate BLE board telemetry

**Problem** (audit 1.1, validation half): `parseTelemetry` in
`app/src/main/java/com/jellemax/detour/ble/BleNavServer.kt` accepts any
double, including NaN/Infinity/absurd values, and TripTrackingService treats board
data as truth.

**Change**: in `parseTelemetry`, after parsing, return `null` (drop the packet)
unless: `speedKmh.isFinite() && speedKmh in 0.0..350.0` when `hasSpeed`, and
`leanDeg.isFinite() && abs(leanDeg) <= 70.0` when `hasLean`. Follow the existing
comment style — one short comment saying why out-of-range packets are dropped
whole rather than clamped (a garbage packet means a firmware/transport bug; a
clamped garbage value would still be recorded as a real reading).

Do NOT change characteristic permissions in this task (that pairs with a firmware
change — see [HUMAN] note at the end of the guide).

**Verify**: `./gradlew :app:assembleDebug`.

**Commit**: `fix(ble): drop out-of-range telemetry packets`

### Task 2.3 — plausibility-gate the G-force pipeline

**Problem** (audit §7): rides record physically impossible max G (6.7 g observed).
The lean pipeline already has a slew gate + plausibility cap; G has only an EMA.

**File**: `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt`.

**Change**, mirroring the lean constants' style and placement:
1. Add constants next to `G_EMA_ALPHA`:
   - `MAX_PLAUSIBLE_G = 2.0` — a road vehicle's real cornering/braking envelope;
     anything above is a pothole or the mount resonating, not the vehicle.
   - `MAX_G_SLEW = 0.5` — max believable change between two ~60 ms samples.
2. In the `TYPE_ACCELEROMETER` branch: compute `rawG` as now; if
   `abs(rawG - currentG) > MAX_G_SLEW`, skip the sample (mirror the lean slew-gate
   `if`); otherwise fold into the EMA as now; then only update `maxG` when
   `currentG <= MAX_PLAUSIBLE_G`.
3. Write comments in the same voice as the lean ones (explain *why*, reference the
   observed 6.7 g false max).

**Verify**: `./gradlew :app:assembleDebug`.

**Commit**: `fix(tracking): slew-gate and cap g-force readings`

### Task 2.4 — status bar contrast + predictive back

**Problems** (audit §7): white status-bar icons over the light map; logcat warns
`OnBackInvokedCallback is not enabled`.

**Files**: `app/src/main/AndroidManifest.xml`,
`app/src/main/java/com/jellemax/detour/MainActivity.kt` (and
`app/src/main/java/com/jellemax/detour/ui/Theme.kt` if the theme decision
lives there — read both first).

**Change**:
1. Manifest `<application>`: add `android:enableOnBackInvokedCallback="true"`.
2. Where the app already decides light vs dark (follow the existing
   `Settings.theme` / sun-position logic — do not re-derive it), set
   `WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = isLightTheme`
   so status bar icons are dark on the light theme and light on the dark theme.
   In Compose this belongs in a `LaunchedEffect`/`SideEffect` keyed on the resolved
   dark/light boolean, wherever the app theme composable already knows it.

**Verify**: `./gradlew :app:assembleDebug`; if a device is connected, launch and
screenshot: clock must be dark on the light theme. Press back from a sub-screen:
no `OnBackInvokedCallback` warning in logcat, and back still navigates (not exits)
from History/Settings.

**Commit**: `fix(ui): status bar contrast, predictive back opt-in`

### Task 2.5 — map polish: camera padding, camera minzoom, attribution

**Problems** (audit §7 items 2–4).

**Files**: `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt`,
`app/src/main/java/com/jellemax/detour/ui/MapLibreMap.kt`. Read both before
editing; find where the camera is fitted to a route/candidate bounds, where the
speed-camera symbol layer is added, and where the MapView/attribution is set up.

**Three small changes**:
1. **Camera fit padding**: every `fitBounds`-style call after a spin/route must
   pass extra bottom padding roughly equal to the expanded card height so the
   route isn't hidden under the card. The card's height in px is knowable where
   the camera call is made (the composable knows its own layout); a pragmatic
   constant (e.g. 40% of screen height as bottom padding, existing padding on the
   other sides) is acceptable — say so in a comment.
2. **Speed cameras minzoom**: on the camera symbol layer, set `minZoom = 11f`
   (MapLibre `SymbolLayer.setMinZoom` or the style-building equivalent used in
   this codebase) with a comment: below city zoom the icons pile into a blob and
   planning zoom doesn't need them.
3. **Attribution margins**: raise the MapLibre attribution + logo above the
   collapsed card so OSM credit is never covered — MapLibre's
   `uiSettings.setAttributionMargins` / logo margins, with a bottom margin
   matching the collapsed bar's height.

Keep each change minimal; do not restructure MapScreen.

**Verify**: `./gradlew :app:assembleDebug`; on a device: spin → whole loop visible
above the card; zoom out → camera icons disappear; attribution visible with the
card collapsed.

**Commit**: `fix(map): fit padding, camera minzoom, attribution margins`

### Task 2.6 — gzip the sync upload

**Problem** (audit 3.2): `traces.jsonl` is >1 MB and re-uploaded raw on every sync.
Server side accepts gzip after Task 1.5.

**File**: `app/src/main/java/com/jellemax/detour/data/Api.kt`.

**Change**: in `request()`, when `body != null`, write
`GZIPOutputStream`-compressed bytes and set `Content-Encoding: gzip`. Keep it
unconditional (the paired server accepts it after Task 1.5; there is no
third-party server to stay compatible with — note that in a comment).

**Verify**: `./gradlew :app:assembleDebug`; against a locally running updated
server: register + `/sync` round-trip succeeds (run the app or a small curl
equivalence test with a gzipped body).

**Commit**: `feat(sync): gzip request bodies`

### Task 2.7 — geocoder public fallback becomes opt-in

**Problem** (audit 2.1): search silently falls back to `photon.komoot.io`, sending
query + user location to a third party even when the user self-hosts.

**Files**: `app/src/main/java/com/jellemax/detour/data/Settings.kt`,
`app/src/main/java/com/jellemax/detour/data/Geocoder.kt`,
`app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt`.

**Change**:
1. `Settings`: add a persisted `StateFlow<Boolean>` `geocoderPublicFallback`,
   default **true** when no custom/baked geocoder is configured (public Photon is
   then the only option and the app must keep working), default **false**
   otherwise. Simplest faithful rule: store the pref with default `true`, but in
   `Geocoder.search` only consult it when a non-public primary exists.
2. `Geocoder.search`: build `endpoints` as today, but only append `PUBLIC` after a
   non-public primary when the setting allows it.
3. `SettingsScreen`: add a toggle under the search-server field, copy in the
   existing settings voice, e.g. title "Fall back to public search" and
   description "If your search server is unreachable, retry via the public
   Photon instance (komoot.io) — sends the query and your approximate location
   off your own hardware."
4. `README.md`: add a short "What leaves your device" paragraph under
   Self-hosting: Overpass sees spin center/radius, OpenFreeMap sees the viewport,
   public Photon sees searches unless self-hosted with fallback off.

**Verify**: `./gradlew :app:assembleDebug`.

**Commit**: `feat(search): make public-Photon fallback opt-in`

### Task 2.8 — history duration format

**Problem** (audit §7 item 5): "7:19" is ambiguous.

**Files**: `app/src/main/java/com/jellemax/detour/ui/Format.kt` (read first —
the formatter likely lives here) and its call site in `HistoryScreen.kt`.

**Change**: format durations as `"7 min"` (< 1 h) and `"1 h 12 min"` (≥ 1 h),
dropping seconds in history (seconds stay wherever the live trip card uses them —
do not change the live formatter if it is shared; add a separate function if
needed, three similar lines beat a clever abstraction).

**Verify**: `./gradlew :app:assembleDebug`.

**Commit**: `fix(history): unambiguous duration format`

### Task 2.9 — spin result survives recreation

**Problem** (audit §7, functional bug 1): activity recreation wipes the spin
result/route (all `remember`, no ViewModel).

**File**: `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt`.

**This is the riskiest app task — smallest possible diff.** Do NOT introduce a
ViewModel or restructure state. Approach:
1. Read MapScreen's state declarations. Identify the minimal set that reproduces
   a spin result on screen: the chosen destination/candidates/loop `RouteResult`
   (polyline, waypoints, distance) and whichever flag marks "result shown".
2. Introduce one top-level `object SpinResultHolder` (same file or a tiny new file
   in `data/`) holding those values in a `MutableStateFlow` — process-scoped
   retention, surviving recreation (not process death; state that must survive
   process death already lives in stores).
3. On successful spin, write to the holder; on MapScreen composition, seed the
   `remember` state from the holder; on "clear/new spin", reset it.

If after reading the file this cannot be done without touching more than ~40
lines, STOP and report what a correct fix needs instead of forcing it.

**Verify**: `./gradlew :app:assembleDebug`; on device: spin, then
`adb shell am start -n io.github.maxke24.detour.debug/com.jellemax.detour.MainActivity`
(recreate — the activity keeps the old namespace, only the applicationId
changed), route
still shown.

**Commit**: `fix(map): keep spin result across recreation`

---

## Phase 3 — build & CI

### Task 3.1 — [HUMAN] release signing + minified release builds

**Problem** (audit 3.1 + 5.1): CI publishes debug APKs with a per-run signature;
users must uninstall to update.

**Code part** (do this):
1. `app/build.gradle.kts`: add a `signingConfigs.release` block reading
   `RELEASE_KEYSTORE_B64` is not usable directly — read from env:
   keystore path `RELEASE_KEYSTORE` (file path), `RELEASE_KEYSTORE_PASSWORD`,
   `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`. Only apply the signing config
   when `RELEASE_KEYSTORE` is set, so local debug builds are untouched.
2. Release build type: `isMinifyEnabled = true`,
   `proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")`,
   and create `app/proguard-rules.pro` (start empty with a comment; MapLibre and
   Play Services ship consumer rules).
3. `.github/workflows/build.yml`: decode `secrets.RELEASE_KEYSTORE_B64` to a file,
   export the four env vars from secrets, build `assembleRelease`, publish
   `app-release.apk` (rename step unchanged otherwise). Keep the existing
   explanatory comments and add one for the signing step. Also upload
   `app/build/outputs/mapping/release/mapping.txt` as a release asset.
4. `README.md` Build section: note releases are signed, and how to verify
   (`apksigner verify --print-certs`).

**Human part** (state it, don't do it): create the keystore once
(`keytool -genkeypair ...`), add the four GitHub secrets, and accept that the
first signed release requires existing users to uninstall once (signature change
from debug → release). Print these instructions at the end of the task.

**Verify**: `./gradlew :app:assembleRelease` locally *without* the env set must
still produce an (unsigned or debug-signed) build successfully — minification
errors surface here; fix missing keep rules in `proguard-rules.pro` if R8 fails.

**Commit**: `feat(ci): signed, minified release builds`

### Task 3.2 — CI on pull requests

**File**: `.github/workflows/build.yml`.

**Change**: add `pull_request:` to `on:`. Guard the release steps (rename, upload
artifact is fine to keep; the `Publish release` step must run only on
`github.event_name == 'push'`). Keep comments.

**Verify**: YAML parses (`python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/build.yml'))"`).

**Commit**: `ci: build pull requests, release only on main`

---

## Phase 4 — repo hygiene & docs

### Task 4.1 — remove the Waveshare scrape, document the BLE protocol

**Change**:
1. `git rm -r waveshare_docs/` (audit 6.1 — copyrighted vendor scrape).
2. Check `docs/WAVESHARE_DISPLAY_SETUP.md` for references to the local copy;
   point them at the live wiki URL instead.
3. Append a "BLE protocol" section to `docs/WAVESHARE_DISPLAY_SETUP.md`
   documenting, from `BleNavServer.kt` (read it — do not invent): the service
   UUID, each characteristic UUID with direction and JSON payload fields (nav,
   music, time, art, telemetry), the MTU expectation, and the 250 ms telemetry
   cadence / 2 s staleness rule.
4. Tell the human: history still contains the scrape; purging needs
   `git filter-repo --invert-paths --path waveshare_docs/` **before** the repo
   goes public, and a force-push. [HUMAN]

**Verify**: `git status` clean apart from the removal; both gradle modules still
build (nothing referenced the directory).

**Commit**: `chore: drop vendored Waveshare docs, document BLE protocol`

### Task 4.2 — consolidate server docs

**Change** (audit 6.3): keep `backend/README.md` as the single entry point.
- Read all five `server/*_GUIDE.md` files. Fold any still-true, non-duplicated
  content into `INSTALL.md` (short sections; link, don't paste, where INSTALL.md
  already covers it).
- Delete `PHASE3_MULTIPLAYER_GUIDE.md`, `SYNC_SETUP_GUIDE.md`,
  `PROFILES_UPDATE_GUIDE.md` (historical).
- Keep `CLAUDE_SETUP_GUIDE.md` and `PHOTON_SETUP_GUIDE.md` only if they contain
  setup steps INSTALL.md lacks; if kept, add a first line: *"This document is a
  prompt for an AI assistant performing the setup — start at INSTALL.md."*
- Replace the example username `jelle` with `alice` wherever it appears in
  remaining docs.

**Verify**: no dangling links: `grep -rn "GUIDE.md" README.md docs server` and fix
any reference to a deleted file.

**Commit**: `docs: consolidate server guides into INSTALL.md`

### Task 4.3 — CONTRIBUTING.md, SECURITY.md, README privacy

**Change** (audit 6.4):
1. `CONTRIBUTING.md`: prerequisites (JDK 17, Android SDK 35), build commands for
   both modules, how to run the sync server locally + `verify.sh`, PR expectations
   (one topic per PR, build must pass), and the house comment style: comments
   explain *why*, not what; keep them.
2. `SECURITY.md`: report vulnerabilities privately to the repo owner's email
   (leave a `TODO(owner): confirm contact` placeholder rather than guessing),
   what's in scope (sync server, BLE surface, app data handling).
3. `README.md`: add the "What leaves your device" section if Task 2.7 hasn't
   already, and an attribution line: map data © OpenStreetMap contributors (ODbL);
   tiles by OpenFreeMap; geocoding by Photon/komoot when the public fallback is
   used.

**Verify**: files exist, markdown renders (no broken code fences).

**Commit**: `docs: contributing, security policy, privacy notes`

### Task 4.4 — Badges screen ordering + search dedupe (polish)

**Change** (audit §7 items 6–7):
1. `app/src/main/java/com/jellemax/detour/ui/BadgesScreen.kt`: put a compact
   badges section (the earned/total header already exists) *before* Coverage, or
   simply move the Coverage block below the badge categories — read the file and
   pick the smaller diff.
2. `app/src/main/java/com/jellemax/detour/data/Geocoder.kt`: after `parse`,
   dedupe results: drop a result whose name equals a previous one and whose
   location is within ~250 m of it (reuse `RoadRoulette.distanceMeters`).

**Verify**: `./gradlew :app:assembleDebug`.

**Commit**: `fix(ui): badge ordering, dedupe search results`

---

## Deferred — do NOT attempt without the owner

- **BLE encrypted characteristics** (audit 1.1): requires pairing support in the
  ESP32 firmware (separate codebase). Owner decision.
- **Git history purge** (Task 4.1 note): destructive, owner runs it.
- **MapScreen split into state holder + files** (audit 4.1): large mechanical
  refactor; do only when explicitly asked, as pure-move commits.
- **Unit test suites** (audit 4.2): valuable but open-ended; ask the owner which
  target first (server pytest port of verify.sh is the best start).
- **Version catalog / dependency bumps** (audit 5.2): owner-triggered; MapLibre
  pin must survive.

## Task completion checklist (repeat every task)

- [ ] Only listed files touched
- [ ] Build green
- [ ] Task's own Verify steps done
- [ ] Existing comments preserved
- [ ] One commit, given message

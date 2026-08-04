# Map Roulette — codebase audit

Audit date: 2026-07-29, at commit `4fafdfe` (plus uncommitted BLE board-telemetry work).
Scope: Android app (`app/`, `wear/`), sync server (`server/`), installer, CI, docs, repo
hygiene — with open-sourcing in mind.

Verified on the connected device (Samsung, versionCode 38 / 1.31): the installed build is
`DEBUGGABLE` with `ALLOW_BACKUP`, and `adb run-as` reads `shared_prefs/settings.xml`
including the `auth_token` — confirming findings 1.2 and 3.1 below in practice.
`files/traces.jsonl` is already 1.07 MB, confirming the sync-payload growth in 3.2.
The foreground `TripTrackingService` is running as designed.

Overall: the code is in unusually good shape for a personal project. Comments explain
*why*, privacy rules are enforced in one place each and documented, secrets are kept out
of the repo and the APK deliberately, and the server validates its inputs. The findings
below are ranked: a handful of real bugs and security gaps first, then the things that
will bite once strangers build, deploy, and contribute to this.

---

## 1. Security

### 1.1 BLE GATT server accepts writes and serves reads without encryption — HIGH

**What**: `BleNavServer.kt` creates all characteristics with plain `PERMISSION_READ` /
`PERMISSION_WRITE` (the new telemetry characteristic in the uncommitted diff included).
There is no bonding or encryption requirement.

**Why it matters**:
- Anyone in radio range can connect and *write* the telemetry characteristic. Board
  speed/lean is "treated as truth over the phone's" in `TripTrackingService`, so a
  nearby attacker can inject fake speed into your live HUD, recorded top speed, and
  trace points.
- Anyone in radio range can *read/subscribe to* the nav, music and time characteristics
  while the feature is on: your navigation destination, maneuvers, and now-playing
  track leak to any BLE central that connects.

The setting is off by default and the threat model (someone following a motorcycle with
a BLE sniffer) is thin — but once open-sourced, the protocol is public and this becomes
the sort of thing that gets a CVE-shaped issue filed against it.

**How to fix**: use `PERMISSION_READ_ENCRYPTED` / `PERMISSION_WRITE_ENCRYPTED` (ideally
`_MITM`) so Android enforces pairing/bonding, and pair the board once. The ESP32 side
(NimBLE) supports Just Works bonding. If bonding the display is unacceptable, at minimum
document the exposure next to the setting, and validate telemetry values
(`speedKmh in 0..400`, `leanDeg in ±70`) before publishing them — currently any float,
including NaN/Infinity, passes straight through `optDouble` into the stats pipeline.

### 1.2 Auth token and CF Access secret are included in Google cloud backup — HIGH

**What**: `backup_rules.xml` and `data_extraction_rules.xml` include
`sharedpref/settings.xml` (contains `auth_token`) and `sharedpref/routing_server.xml`
(contains the Cloudflare Access client secret) in cloud backup and device transfer.

**Why it matters**: these credentials leave "hardware you own" and land in Google Drive
backups. That directly undercuts the project's own privacy story (`ConfigFile.kt` even
says "it is a credential, not a backup"). A restored backup also silently resurrects a
bearer token the user may believe was revoked.

**How to fix**: either exclude the two pref files from `<cloud-backup>` (keep
`trips.json`/`traces.jsonl` if you want data to survive reinstall — though see 2.3), or
move the token and CF secret into a pref file that is excluded. The config-file
export/import flow already exists as the sanctioned way to carry credentials across
reinstalls, so excluding them from backup costs the user one import.

### 1.3 Sync server: no rollback on a failed write transaction — HIGH (correctness + integrity)

**What**: in `sync_server.py`, `do_sync()` raises `HttpError(400, ...)` *mid-loop* (e.g.
"trip missing startTimeMs" after some trips were already inserted). There is no
`conn.rollback()` anywhere. Connections are per-thread and long-lived, and sqlite3's
default isolation opens an implicit transaction on the first write.

**Why it matters**: after a failed sync, the thread's connection is left with an open
transaction holding partial writes. The *next* request handled by that same thread that
reaches a `conn.commit()` commits the leftover partial rows — a half-imported trip set
from a malformed request becomes permanent, attributed correctly by user_id but
inconsistent with what the client thinks happened. The same pattern applies to any
handler that raises between `execute` and `commit` (e.g. an unexpected
`sqlite3.IntegrityError` in `do_friend_request` on a concurrent duplicate request).

**How to fix**: wrap write blocks in `try/except: conn.rollback(); raise`, or more simply
use the connection as a context manager (`with conn: ...` commits/rolls back
atomically) inside `_write_lock`. Also consider validating the full trips list *before*
inserting any of it — all validation is cheap and it makes the endpoint all-or-nothing.

### 1.4 Rate limiting trusts a spoofable header — MEDIUM

**What**: `Handler.client_ip()` prefers `CF-Connecting-IP` over the socket address.

**Why it matters**: correct behind the Cloudflare tunnel, but this repo is about to be
public and `install.sh` installs the server for anyone. A deployer who exposes the port
directly (or behind a different proxy) gets a rate limiter any attacker can reset per
request by sending a random `CF-Connecting-IP`. Brute-forcing passwords and invite codes
then costs nothing.

**How to fix**: gate the header on an env var (e.g. `TRUSTED_PROXY=cloudflare`), default
to the socket peer address. One line, and the comment already explains the tunnel case.

### 1.5 Bearer tokens never expire and accumulate — MEDIUM

**What**: `tokens` rows live forever (`last_used_ms` is written at issue time and never
updated or checked). Every login mints a new token; nothing prunes old ones. There is
also no CLI to revoke an API key (`api_keys` can only be inserted).

**Why it matters**: a token that leaks (via 1.2, a stolen config file export, a lost
phone) is valid forever unless the user thinks to log out from that exact session. For a
multi-user open-source deployment this is the first thing a reviewer will flag.

**How to fix**: update `last_used_ms` in `authenticate()`, expire tokens unused for N
days (a `DELETE` on startup or per-auth is enough at this scale), and add
`--revoke-key USER` / `--revoke-tokens USER` CLI commands. Optionally cap tokens per
user, oldest-out.

### 1.6 64 MB request bodies parsed fully in memory — LOW

**What**: `MAX_BODY = 64 MiB`; `json.loads(self.rfile.read(length))` on a
`ThreadingHTTPServer` (one thread per connection, no cap).

**Why it matters**: a handful of concurrent 64 MB posts from an authenticated (or, for
`/auth/*`, unauthenticated) client can take hundreds of MB of RAM on the small LXC the
installer builds. Registration + sync means any registered user can do this trivially.

**How to fix**: 64 MB is sized for a full trace history; per-endpoint limits would let
`/auth/*` cap at a few KB while `/sync` keeps the large limit. Even that is optional —
but document the assumption.

### 1.7 `ride.html` embeds JSON into a `<script>` block unescaped — LOW

**What**: `ha_ride_html()` does `RIDE_HTML % {"geojson": json.dumps(geo)}`. `json.dumps`
does not escape `</script>`. The `mode` field comes from user-supplied trip JSON.

**Why it matters**: a user can only XSS *their own* dashboard page today (trips are never
served across users), so impact is minimal — but it's a one-line fix and the pattern
will be copied if the server grows pages.

**How to fix**: `json.dumps(geo).replace("</", "<\\/")` (the standard `</script>` guard).

### 1.8 Registration open by default in the server itself — LOW

**What**: `REGISTRATION_OPEN` defaults to `"1"` in `sync_server.py`; the installer
defaults it to closed unless `--open-registration`, so the two defaults disagree.

**Why**: someone running `python3 sync_server.py` by hand (the documented CLI) gets open
registration without realizing. Safer to fail closed and make openness the explicit
choice everywhere.

**How**: flip the server default to closed (or require either `REGISTRATION_OPEN=1` or
`INVITE_CODE` to be set to enable registration at all), and align the docs.

---

## 2. Privacy (matters double once the repo is public)

### 2.1 Geocoder silently fails over to a public third-party server — MEDIUM

**What**: `Geocoder.search()` falls back to `photon.komoot.io` whenever the self-hosted
instance errors, sending the query **and the user's current lat/lon** (as ranking bias).

**Why it matters**: the README sells "your trips and traces live on hardware you own."
A user who self-hosts Photon precisely to avoid third parties still leaks searches +
location to komoot the moment their server hiccups — with no indication in the UI.

**How to fix**: make the fallback opt-in (a Settings toggle, default off when a custom
geocoder is configured), or at least surface it ("searched via public Photon") so the
behavior is visible. Same consideration applies to the public Overpass endpoints in
`RoadRoulette` and `SpeedCameras` — those are inherent to the feature, but a short
"What leaves your device" section in the README would state it honestly: Overpass gets
your chosen spin center/radius, the public geocoder may get searches, OpenFreeMap tiles
see your viewport.

### 2.2 Trip + trace history goes to Google Drive backup

**What**: `trips.json` and `traces.jsonl` are in cloud-backup rules (see 1.2).

**Why**: a complete location history in a Google backup is exactly the data the
self-hosted sync server exists to keep private. Users with a sync server don't need the
Google copy at all (first sync restores everything).

**How**: consider excluding the data files from `<cloud-backup>` (keep
`<device-transfer>`, which is phone-to-phone), or document the trade-off in the README
so users can disable backup for the app knowingly.

---

## 3. Correctness / robustness

### 3.1 CI releases are debug builds signed with a throwaway key — HIGH (known pain, unfixed)

**What**: `build.yml` publishes `assembleDebug` output as the GitHub release. Debug APKs
are signed with the runner's auto-generated debug keystore, which is different on every
run (and different from your local one — you've already hit the "signing key mismatch,
back up with `run-as` and reinstall" failure mode).

**Why it matters**: every published release potentially breaks in-place updates for every
user (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), forcing uninstall and data loss for anyone
without a sync server. Debug builds are also debuggable (`run-as` works on them — that's
how the backup trick works, and it works for anything else with adb access too) and skip
all R8 optimization.

**How to fix**: create one release keystore, put it in GitHub secrets
(base64-encoded) + `signingConfigs.release` reading from env, and publish
`assembleRelease`. Document the SHA-256 of the signing cert in the README so users can
verify APKs. This is the single highest-value change before strangers install releases.

### 3.2 Full-history sync payload grows without bound — MEDIUM

**What**: every sync uploads *every* trip and *every* trace line the phone holds, and the
server replies with the full union, which replaces the local stores
(`SyncClient.sync()`, documented in `TraceStore`). No gzip on either direction of the
sync body (the client sends `Accept-Encoding: gzip` but the server never compresses,
and the upload is raw).

**Why it matters**: after a year of riding this is tens of MB of JSON per sync, on every
trip end, over mobile data. It also collides with `MAX_BODY` eventually.

**How to fix** (incremental, in order of value):
1. gzip both directions (`Content-Encoding: gzip` upload is ~10 lines on each side; JSON
   traces compress ~10:1);
2. delta sync for traces: client sends line hashes it holds, server replies with which
   are new + missing lines — the dedupe-by-hash design already makes this natural;
3. same for trips keyed on `startTimeMs`.

### 3.3 `ride_window` 24-hour fallback can swallow the next ride's points — LOW

**What**: a trip with no recorded end gets `start + 24h` as its window; `/ha/*`
aggregates then attribute the *next* ride's points (and its lean/speed peaks) to the
unended trip.

**How**: cap the window at the next trip's `start_ms` (`SELECT MIN(start_ms) ... >
start`), falling back to 24 h only when there is no later trip.

### 3.4 `Settings.init` is racy — LOW

**What**: `if (::prefs.isInitialized) return` guards initialization, but the service
(main thread) and any other caller can interleave; also every setter assumes `init` ran.

**Why**: benign today because all callers are on the main thread, but it's the kind of
invisible contract a contributor breaks. `@Volatile`/synchronized init, or
`Settings.init` from an `Application` subclass once, removes the whole class of bug.

### 3.5 HttpURLConnection + hand-rolled gzip in five places — LOW

**What**: `Api`, `RoutingServer` (×2), `Geocoder`, `SpeedCameras`… each re-implements
timeouts, gzip decode, CF headers, error handling; user-agents disagree
(`MapRoulette/1.4`, `/1.11`, `BuildConfig.VERSION_NAME`).

**How**: extract one small `Http.get/post(url, headers, body)` helper (or adopt OkHttp,
which also brings connection reuse and HTTP/2 — one dependency, big deletion). Not
urgent; do it the next time one of them needs a change. The stale hardcoded user-agent
versions at least should read `BuildConfig.VERSION_NAME`.

---

## 4. Architecture & code quality (contributor experience)

### 4.1 `MapScreen.kt` is a 2,220-line composable holding most of the app's logic — MEDIUM

**What**: spin logic, candidate picking, navigation start/stop, camera control, search,
saved places, Google Maps/Waze handoffs, and a dozen UI components live in one file;
`MapScreen()` itself spans ~1,150 lines with nested `fun`s and ~30 `remember`/state vars.

**Why it matters**: it works, and Karpathy-style restraint is fine for a solo project —
but this file is where every contributor's first PR will land, and reviewing a diff
against it is painful. Testing any of the decision logic (candidate scoring, spin
retries, mode selection) currently requires a device.

**How**: no framework rewrite needed. Two mechanical splits pay for themselves:
1. move the pure logic (spin/candidate/roulette orchestration) into a plain state-holder
   class (`MapScreenState` or a ViewModel) that the composable observes;
2. move the leaf composables (`SpeedHud`, `CandidatesCard`, `MapToolbar`, dialogs, the
   `navigate*` intent helpers) into their own files.
Do it as a pure-move commit so `git blame` stays useful.

### 4.2 No tests at all — MEDIUM

**What**: zero unit tests in `app/` and `wear/`; the server has `verify.sh` (a good
end-to-end script) but no unit tests; CI runs neither.

**Why**: for open source, tests are also documentation and a safety net for drive-by
PRs. Plenty of this code is pure and cheaply testable: `RoadRoulette` geometry
(circumcircle curvature, point-in-circle), `NavEngine`, `Format`, `TraceStore.parseLines`
tolerance, `Settings.readVehicleDevices` migration, and on the server the merge rules
(`clean_stats`, `clean_badges`, badge earliest-wins, trip upsert, fog reciprocity —
`verify.sh` covers the last ones but a `pytest` suite runs in CI without a live server).

**How**: add JUnit + a `test` task to `build.yml`; port `verify.sh`'s assertions to
pytest against a `Handler` bound to an ephemeral port. Start with the sync merge logic —
it guards user data.

### 4.3 Raw `Thread {}` mixed with coroutines — LOW

**What**: `SyncClient.syncQuietly`, `checkBadges`, `maybeDiscoverMunicipality` spawn bare
threads; the UI layer uses coroutines.

**How**: a single `CoroutineScope(SupervisorJob() + Dispatchers.IO)` in the service (and
`withContext(Dispatchers.IO)` elsewhere) gives structured cancellation and one idiom for
contributors to copy. Mechanical change, no behavior difference today.

### 4.4 Manual `org.json` parsing throughout — LOW / optional

Typed but verbose, and silent-default `optString`/`optDouble` calls hide schema
mistakes. `kotlinx.serialization` would shrink `RoutingServer.parseRoute`,
`Geocoder.parse`, the stores, and `ConfigFile` considerably. Only worth doing if you're
touching those files anyway; the current code is correct.

### 4.5 Hardcoded UI strings, no `strings.xml` — LOW

All user-facing text is inline Kotlin ("Trip ended — saved to history.", settings labels,
etc.). Fine for a personal app; blocks localization PRs — which are among the most common
first-time contributions an open-source app gets. Extract when convenient, or state in
CONTRIBUTING.md that English-only is a deliberate choice.

### 4.6 Belgium-shaped defaults — document them

Trajectcontrole logic, `admin_level=8` municipality resolution, installer defaults
(`europe/belgium`, `be`), the Photon country index. All reasonable, mostly configurable
already — but the README should say plainly which features assume Belgium/NL-style OSM
tagging (coverage and average-speed sections chiefly) so a user in another country knows
what to expect and what flags to change.

---

## 5. Build, dependencies, CI

### 5.1 Release build has no minification — tied to 3.1

`isMinifyEnabled = false`. Once CI builds real releases, enable R8 with a small keep file
(MapLibre and Play Services ship consumer rules; expect near-zero friction). Smaller APK,
and stack traces in issues stay readable if you also publish `mapping.txt` with each
release.

### 5.2 Dependency management: no version catalog, no update automation

Versions are string literals across three gradle files; compose BOM 2024.09.02, AGP
8.5.2, Kotlin 2.0.20, Gradle 8.9 are all a year-plus behind (mind the pinned MapLibre
11.8.0 — the Kotlin-compat constraint is recorded in memory/docs; put that reason in a
comment next to the pin so contributors don't "helpfully" bump it).

**How**: move to `gradle/libs.versions.toml`, add Dependabot or Renovate (works for
gradle + github-actions ecosystems), and let CI catch breakage.

### 5.3 CI has no PR gate

`build.yml` runs on push to main only. Add `pull_request` trigger for build + (future)
tests + lint, keeping the release job on-main-only. Otherwise every contributor PR is
merged blind.

### 5.4 `versionCode`/`versionName` grep in CI is fragile — LOW

Works, but a formatting change breaks releases silently at the `test -n` step. Fine to
keep; alternatively read from a `-PversionName` gradle property or a VERSION file that
both gradle and CI consume.

---

## 6. Repo hygiene for open-sourcing

### 6.1 Remove `waveshare_docs/` — copyrighted vendor scrape in-tree — HIGH (for release)

2.2 MB of saved-page HTML/JS/images from Waveshare's wiki, committed. It's redistributed
copyrighted content (their JS bundles included), it bloats every clone, and
`docs/WAVESHARE_DISPLAY_SETUP.md` already links the live wiki. Delete it, and since the
repo will be re-published anyway, use `git filter-repo` to purge it from history (the
`.git` dir is 7.6 MB; this is most of it). Do the history rewrite *before* the repo goes
public — after, it's forever.

### 6.2 The BLE display's firmware is referenced but absent

Comments cite "moto_hud's ble_central.cpp" and the telemetry protocol spans both sides,
but the ESP32 firmware isn't in the repo and isn't linked. Open-sourcing the app without
it makes the external-display feature un-buildable and the protocol half-documented.
Either add the firmware (a `firmware/` dir or a linked sibling repo) or write the
protocol down: service/characteristic UUIDs, JSON payload schemas, MTU expectations,
the 250 ms telemetry cadence. `docs/WAVESHARE_DISPLAY_SETUP.md` is the natural home.

### 6.3 Server docs sprawl

`server/` holds INSTALL.md plus five `*_GUIDE.md` files (two addressed to an AI assistant
performing the setup, several describing historical phases). A newcomer can't tell which
is current. Keep `INSTALL.md` as the single entry point, fold the still-true content of
the guides into it or into `docs/`, and delete the phase/history documents (git history
keeps them). The AI-orientated guides are genuinely novel — if you want to keep them,
label them clearly ("prompt for an assistant doing this setup") so they aren't mistaken
for reference docs.

### 6.4 Missing standard open-source files

- **CONTRIBUTING.md** — build prereqs (JDK 17, SDK 35), how to run the server locally,
  what `verify.sh` checks, PR expectations, and the project's stated code style (the
  heavy "why" commenting is a house style worth writing down; it's the repo's best
  feature).
- **Issue/PR templates** — cheap, filters noise.
- **SECURITY.md** — where to report the class of issues in section 1 privately.
- README already good; add the privacy paragraph (2.1) and a screenshot of the watch/HUD
  if you have one.

### 6.5 Small nits

- `INSTALL.md` example usernames/commands reference `jelle` — swap for `alice`.
- `FUTURE.md` — fine to keep; consider migrating actionable items to GitHub issues at
  release so contributors can pick them up.
- In-app OSM attribution: MapLibre shows the style attribution, but spin results,
  speed cameras, and coverage are Overpass-derived — add an "About" line crediting
  © OpenStreetMap contributors (ODbL) to be squarely within the norms.
- `notifyTripEnded`/`buildNotification` use `android.R.drawable.*` system icons; use
  in-app drawables (system ones vary per OEM and can be themed unreadable).
- Cleartext HTTP is blocked by default on API 28+: a user pointing the app at a plain
  `http://` LAN server gets silent failures. Either document "HTTPS required (use the
  tunnel)" in INSTALL.md, or add a `network_security_config` permitting cleartext to
  RFC1918 addresses only.

---

## 7. Runtime testing on the device (hands-on session)

Tested on the connected Samsung (1440×3120, One UI), v1.31 debug build, driving the real
UI over adb: launch, spin, map interaction, every menu screen, theme switch, search.

### Performance — measured, and good

- **Cold start: 716 ms** to first frame (`am start -W`), on a debug build with no R8.
  A minified release will only improve this. No startup jank visible.
- **Frame stats after spin + route render**: 193 frames, 3.1 % janky, p50 5 ms /
  p99 69 ms. The few slow frames coincide with the route GeoJSON upload — the
  earlier per-frame-upload fix (`ee5b495`) is clearly holding.
- **Sustained map panning**: 289 frames, 7.6 % janky, p50 8 ms, p99 24 ms. Fluid in
  hand; no dropped-input feel at 1440p on vector tiles.
- **Memory**: 372 MB PSS / 505 MB RSS, of which 184 MB is Graphics (MapLibre GL
  surfaces at this resolution) and 86 MB native heap. High-ish in absolute terms but
  normal for a GL vector map app; nothing suggests a leak across a few minutes of
  navigation between screens. Worth a one-off check with the Memory Profiler after a
  long navigation session, since nobody has ever watched it over an hour.
- **CPU with the map visible: ~10.7 %** of one core — that's the deliberate LIVE-mode
  1 Hz/200 ms location firehose plus map rendering while the screen is on. Fine while
  looking at it; the SLEEP/IDLE modes can't be judged in a desk session (would need a
  day of batterystats — worth doing once before release notes claim "cut background
  drain").
- **Spin (moto, 120 km loop)**: ~5–6 s from tap to "Loop found: 128,9 km" with the
  route drawn and camera fitted. Acceptable for what it does (Overpass + GraphHopper
  round-trip with retries); a progress indicator exists. No error surfaced.
- **Search**: type-ahead against the self-hosted Photon answers in well under a second
  per keystroke.

### Functional findings

- **Spin result does not survive activity recreation — real bug.** After the activity
  was relaunched (equivalent to rotation, split-screen resize, theme change, or process
  death in background), the found loop, its polyline and the "Loop found" state were
  gone; the card reset to defaults. All of `MapScreen`'s state lives in `remember` with
  no `rememberSaveable`/ViewModel (ties into §4.1). On a phone that's mostly hidden by
  the locked portrait orientation; it will surface the moment anyone uses the app in a
  car head unit, foldable, or split screen. Persist at least: last spin result,
  candidates, and active navigation target.
- **Trip data quality: the sensor filtering still lets impossible values through.**
  History shows a moto ride (Wed 29 Jul, 07:16) with **max G 6.7 g** — physically
  impossible on a road bike (real cornering/braking peaks are ≤ ~1.3 g); that's a
  pothole/mount resonance spike surviving the EMA (α=0.15 damps but, over a ~100 ms
  burst of 60 Hz samples, does not remove a 10 g shock). Another ride shows **max lean
  65°** — exactly `MAX_PLAUSIBLE_LEAN_DEG`, i.e. a glitch that landed on the clamp
  (recorded before the uncommitted slew-gate fix, which should help). The G pipeline
  deserves the same treatment lean just got: slew-rate gate or median-of-N before the
  EMA, and a plausibility cap (~2 g for a vehicle) on the recorded max.
- **`OnBackInvokedCallback is not enabled`** warned in logcat on every screen exit:
  predictive back gesture (Android 13+) isn't opted in. One manifest line
  (`android:enableOnBackInvokedCallback="true"`) plus verifying the dialogs still
  dismiss correctly.

### UI/UX and design review

The design is genuinely good: consistent Material 3, one accent color per theme, cards
with clear hierarchy, and every setting explained in honest plain language (the
"Now playing" permission explanation and the red config-file token warning are
best-in-class). The collapsed spin bar (`moto · 120 km · Spin`) is excellent progressive
disclosure. The dark theme (amber on near-black, map style switching with it) is
distinctive and cohesive. Issues found, in rough priority:

1. **Status bar contrast on the light theme**: white clock/icons over the pale map are
   near-invisible (see screenshot). The activity draws edge-to-edge but doesn't set
   light-status-bar appearance when the light theme is active. Fix via
   `WindowInsetsControllerCompat.isAppearanceLightStatusBars = true` keyed off the same
   day/night decision the theme already makes.
2. **Route-fit camera ignores the bottom card**: after a spin, the camera fits the loop
   to the full screen, so the expanded card covers almost half the route. Pass the
   card's height as bottom padding to the camera-fit call (MapLibre supports
   asymmetric padding), or collapse the card automatically once a result lands.
3. **Speed-camera icons pile up at low zoom**: four+ camera glyphs overlap into an
   unreadable black blob near the city center (screenshot 05). Give the layer a
   `minzoom` (~11) or enable symbol collision fade; at loop-planning zoom the cameras
   are noise anyway.
4. **MapLibre attribution is half-covered** by the expanded spin card. OSM/OpenFreeMap
   attribution should stay fully visible in every card state — move it above the card
   (MapLibre lets you set attribution margins) or into the collapsed layout. This is a
   license-hygiene item, not just cosmetics (ties into §6.5).
5. **Duration formatting in History is ambiguous**: "1:12:36" next to "7:19" — the
   latter reads as either 7 h 19 m or 7 m 19 s. Use "7 min" / "1 h 12 min" style, or
   always three segments.
6. **Search results near-duplicate**: "Kortrijk, België" and "Kortrijk,
   West-Vlaanderen, België" as adjacent entries. Dedupe on (name, coordinates-ish) or
   include the type (city vs municipality) in the label so the difference is legible.
7. **Badges screen leads with Coverage**: the screen is named "Badges" but the first
   viewport is municipality coverage; actual badges appear only after scrolling. Either
   rename the entry ("Progress"), or put a compact badge strip first.
8. **Destructive actions — verified safe**: both the per-trip delete
   (`HistoryScreen.kt` `confirmDelete`) and Settings' "Reset explored area"
   (`SettingsScreen.kt` `confirmReset`) sit behind `AlertDialog` confirmations. No
   change needed; noted so nobody re-checks.

Screenshots from the session are in the scratchpad (not committed): main screen, spin
result, collapsed bar, menu, history, badges, settings (×4), dark map, search.

## Suggested order of attack

1. **Before open-sourcing (blockers)**: 6.1 history purge, 3.1 release signing, 1.2
   backup rules, 1.1 BLE permissions (or documented limitation + input validation),
   6.4 SECURITY.md/CONTRIBUTING.md.
2. **Server hardening (small diffs, big trust)**: 1.3 rollback, 1.4 trusted-proxy flag,
   1.5 token expiry/revocation, 1.8 registration default, 1.7 script-tag escape.
3. **Soon after**: 2.1 geocoder fallback opt-in + README privacy section, 5.3 PR CI,
   4.2 first tests (server merge logic), 3.2 gzip on sync.
4. **From the device session (§7)**: G-force plausibility gate (bad data is being
   recorded on every ride now), spin-state survival across recreation, status-bar
   contrast, camera-fit padding, camera-icon minzoom, attribution visibility,
   predictive back opt-in.
5. **Opportunistic**: 4.1 MapScreen split, 4.3 coroutines, 5.2 version catalog +
   Dependabot, 3.2 delta sync, 4.5 string extraction, §7 duration format + search
   dedupe + Badges screen ordering.

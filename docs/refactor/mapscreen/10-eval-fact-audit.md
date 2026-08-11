# Fact audit of the five MapScreen split proposals

Adversarial read of `00-inventory.md` and `01`–`05`, checked line-by-line against the
tree at `07fe490`. Nothing here is an opinion about which pattern is better. Every row
is a claim that is either true against the source, false against the source, or not
checkable from the source.

## Method

- Every count was re-derived from the files, not from any report. Counts are given
  twice where scope matters: **whole file** vs **inside `MapScreen`'s composable body
  (`MapScreen.kt:419–1837`)**. Several apparent disagreements between proposals are
  only this scoping difference, and the audit says so rather than picking a winner.
- Regexes excluded `import` lines and full-line comments, and excluded dotted receivers
  where relevant (`x.collectAsStateWithLifecycle()` is not matched by `\bcollect…`
  preceded by a non-dot).
- Proposal 4's classification table was parsed programmatically and checked for
  contiguity, overlap, stated-vs-actual span, and per-class totals.
- No build was run, no file outside this one was modified. Line numbers are from the
  working tree; `app/build/outputs/mapping/**` hits were ignored as build artefacts.
- Where a proposal's number is achievable only under an unstated assumption, the
  verdict is **PLAUSIBLE (unshown)**, not "true".

---

## Verified true / Refuted / Unverifiable

### Group 1 — counts and classification

| # | Claim | Source | Verdict |
|---|---|---|---|
| 1.1 | P2: `LaunchedEffect(` declarations = **32** | `MapScreen.kt:419–1837` → 32 call sites (437, 467, 503, 555, 562, 568, 582, 652, 700, 747, 782, 858, 874, 907, 913, 921, 928, 940, 1024, 1062, 1087, 1094, 1106, 1120, 1128, 1145, 1174, 1236, 1251, 1271, 1338, 1345) | **TRUE**, exactly. Whole file is 35 (+1863, 1869 in `SearchDialog`, 3107 in `ActiveTripCard`). |
| 1.2 | P2: top-level `remember*` declarations = **59** | `^    (va[lr]) …remember` in `419–1837` → **59**. Total remember-family *call sites* in the body = 61 (adds the two nested `remember { mutableStateOf(…) }` at 1654 and 1697); whole file = 76 | **TRUE** for the stated metric. |
| 1.3 | P2: `DisposableEffect` = **4** | 602, 629, 669, 1144 | **TRUE**. |
| 1.4 | P2: `collectAsStateWithLifecycle()` = **21** | 438, 448, 470, 471, 479, 483, 484, 486, 487, 488, 491, 492, 493, 497, 498, 499, 544, 545, 546, 588, 590 | **TRUE** for MapScreen's body. Whole file = 23 (+2250 in `NavIconButton`, +3063 in `NavButton`). |
| 1.5 | P4: classification sums to exactly **3,193**, ranges contiguous and non-overlapping | Parsed all 101 rows: stated `n` sums to 3193; actual spans sum to 3193; last row ends at 3193; zero gaps, zero overlaps; every stated `n` equals `end−start+1`; class totals (a 934 / b 327 / c 178 / d 1545 / e 209) reproduce exactly | **TRUE.** The single most rigorously checkable number in the folder, and it checks out. |
| 1.6 | P4: **934 lines (29.3%) of domain logic** | Arithmetic true (1.5). But the bucket contains 88 lines of `withFrameNanos` loops (`1247–1264`, `1265–1334`) that cannot leave Compose, and 62 lines of bare `var … by remember` declarations (`446–466`, `514–540`, `541–554`) | **TRUE as an upper bound, and P4 says so** at `04:207` ("934 is the *upper bound*, not the deliverable") and nets it to **800**. The headline at `04:4` drops that qualifier. Decide on 800, not 934. |
| 1.7 | P4: `:shared` commonMain has **38 files** | `find shared/src/commonMain -name '*.kt'` → **36** | **REFUTED** (minor). 43 across the whole module. |
| 1.8 | P1: `1839–3193` is **1355 lines** | 3193−1839+1 = 1355 | **TRUE.** |
| 1.9 | P1: that range is "**27** `private` composables and **7** `private` helper functions" | Actual: **24** private composables + **7** non-composable helpers = 31 private funs. P1's own Phase-2 tables (`01:141–206`) enumerate exactly 24 composables | **REFUTED.** Self-contradicting: the prose says 27, its own tables list 24. |
| 1.10 | Inventory: "all **27** private composables/helpers" have zero external references | 31 private funs in the tail, 36 in the file. The *zero external references* half is **true** (only `TravelMode.icon` and `seedRouteNavigation` escape the file) | **REFUTED on the count, TRUE on the substance.** Same off-by-4 as P1 — one report inherited it from the other. |
| 1.11 | P1: those 1355 lines are "cleanly parameterised" and "**none of them touch MapScreen's state**" | True w.r.t. MapScreen's `remember`ed locals. False w.r.t. global state: `SearchDialog` calls `RecentSearchStore.load/save` (1853, 1858) and `Geocoder.search` (1883) inside a debounced `LaunchedEffect`; `NavIconButton`/`NavButton` read `Settings.preferredNavApp` (2250, 3063); `launchNav` writes `Settings.setPreferredNavApp` (2148); `PushToTalkButton` drives `PushToTalk` (2930, 2935); four helpers call `context.startActivity` | **OVERSTATED.** ~120 of the 1355 lines (`SearchDialog`) are I/O + debounce, not presentation — which is exactly why P4 classifies `1865–1894` as domain **(a)**. |
| 1.12 | P5: `TripTrackingService.lastFix` is fanned out to **ten** consumers in MapScreen (5 raw `.collect`, 5 as key/`rememberUpdatedState`) | Raw: 704, 1026, 1065, 1147, 1180. Non-raw: 555, 1236, 1250, 1338, 1345. Total 10 | **TRUE**, exactly as itemised. |
| 1.13 | Inventory H11: **six** concurrent collectors on that StateFlow | 5 raw `.collect` + `collectAsStateWithLifecycle` at 487 = 6 *subscriptions* | **TRUE.** Not a contradiction with 1.12 — 6 subscriptions, 10 consumer sites. Repo-wide there are **13** consumer sites: add `net/ConvoyLiveClient.kt:382`, `car/NavScreen.kt:205`, `car/SpinScreen.kt:131`. |
| 1.14 | P5: **nine** implicit state machines (SM1–SM9) | SM1 (camera authority) and SM5 (trajectcontrole, `1174–1230`) are genuine multi-state machines with closure-local state; SM2/SM3 are real; the remaining four are boolean pairs and an already-pure `when` at `1689–1694` | **UNVERIFIABLE as stated** — "state machine" is not a property the source can confirm. P5 concedes this itself at `05:992` ("only three of the nine are worth it"). Treat the honest claim as **three**. |
| 1.15 | P5 C3: destination/route/candidates written from **thirteen** places | Printed list has 12 entries; actual distinct write sites = 13 (the list merges `1718` and `1720`) | **TRUE** (list is one short of its own headline). |
| 1.16 | P5 C2: camera authority written from **nine** places | 674–689, 700–712, 810–813, 837–838, 987, 1403, 1586–1589, 1678–1679, 1829–1830 | **TRUE**, all nine verified. |
| 1.17 | P5 C11: 208 import lines; body `419–1837` = 1419 lines; UI tree `1529–1837` = 309 lines | 206 `^import` lines (208 is the last import's line number); 1419 ✓; 309 ✓ | **TRUE** (import figure is a line-number/count conflation, harmless). |

### Group 2 — claimed post-refactor sizes

| # | Claim | Source | Verdict |
|---|---|---|---|
| 2.1 | P1: MapScreen.kt → **~1,565** | Summing P1's own move tables: Phase 1 moves ≈167 code lines, Phase 2 moves ≈1,327 → 3193 − 1,494 = **1,699**. Reaching 1,565 additionally needs ~134 of MapScreen's 206 imports to become unused | **PLAUSIBLE (unshown).** The block arithmetic alone gives ~1,700. The tail (24 Material3/icons-heavy composables) plausibly owns 130 imports, but P1 never itemises it. Its aggregate table (`01:219–232`) *is* internally consistent, so this is an unshown step, not a wrong one. |
| 2.2 | P2: MapScreen.kt → **~300** | Retained ranges (`02:203`): 419-448, 470-501, 526, 540-546, 560-590, 601-625, 872-915, 1529-1612, 1798-1836 = **293 code lines** | **TRUE for the listed content**, but ~300 excludes the new file's own import block (a 293-line Compose file needs 60–80 imports), so the realistic figure is **350–380**. |
| 2.3 | P3: MapScreen.kt → **≈1,840** after step 2 ("pure moves") | Step 2 moves 210-211, 213-219, 316-367, 416-417, 1839-2055, 2057-2121, 2123-2279, 2281-2697, 2699-2897, 2899-2954, 2956-3046, 3048-3099, 3101-3148, 3150-3193 ≈ 1,300 code lines → 3193 − 1,300 ≈ **1,893**, less shed imports | **PLAUSIBLE**, and the closest of the five to falling out of its own tables without an import assumption. |
| 2.4 | P4: MapScreen.kt → **≈2,355** | 3193 − 838 = 2355. The 838 = 800 net domain lines + ~38 orphaned imports, itemised block-by-block at `04:212–229` | **TRUE arithmetically**, and the most transparently derived number in the folder. |
| 2.5 | P5: MapScreen.kt → **~640** | P5's *full-MVI* table (`05:351–369`) itemises `ui/map/MapScreen.kt` at **400** (sources 419-448, 555-559, 566-590, 1529-1837 = 369 code lines). The **~640** belongs to the *targeted* variant at `05:391` and is asserted with no per-file breakdown | **UNVERIFIABLE.** Two different numbers (400 and 640) for two different variants; only the 400 is itemised. |
| 2.6 | Implied premise: the five numbers are comparable | They are not. Content moved out: **P4 ≈838** (domain only, all 1,545 UI lines stay) · **P1 ≈1,494** (presentation only, all state/effects stay) · **P3 ≈1,300** then further · **P2 ≈2,900** (presentation *and* state) · **P5 ≈2,790** (same). P1 and P4 are near-**disjoint**: doing both lands MapScreen.kt at roughly **1,000 lines** | **CONFIRMED: not the same measurement.** Any table that ranks 300 / 640 / 1565 / 1840 / 2355 side by side is comparing four different scopes of work. |

### Group 3 — claimed defects in the current code

| # | Claim | Source | Verdict |
|---|---|---|---|
| 3.1a | P4: `MapScreen.kt:1037` and `:1075` await Overpass **inside** the `lastFix` collector | `1026` opens `TripTrackingService.lastFix.collect {`; `1037` is `val ways = withContext(Dispatchers.IO) { RoadRoulette.speedLimitWays(pos) }` inside it. `1065` opens a second collector; `1075` is `withContext(Dispatchers.IO) { SpeedCameras.near(pos) }` inside it. `lastFix` is `StateFlow<Fix?>` (`TripTrackingService.kt:233`), so it conflates while the collector is suspended | **TRUE**, verbatim at both line numbers. |
| 3.1b | P4: `car/NavScreen.kt:365–377` already fixed it | KDoc at exactly 365–377: *"The Overpass fetch runs in its own coroutine rather than inline… awaiting a mirror here suspended onFix itself… while every fix that landed meanwhile was conflated away."* Implementation at 386 (`cameraFetchJob = lifecycleScope.launch { … }`) with an `isActive` re-entry guard at 383. `car/SpinScreen.kt:254–264` does the same for the speed-limit fetch and says *"Same fix as [NavScreen.checkCameras]"* at 263 | **TRUE.** Two independent car-side fixes, both documented; the phone has neither. This is the strongest single defect claim in the folder. |
| 3.2 | P3: `AppRoot` uses a bare `AnimatedContent`, so Map→Hub→Map disposes the composition and resets every `rememberSaveable` | `MainActivity.kt:172–225`: `AnimatedContent(targetState = screen) { when (current) { … Screen.MAP -> MapScreen(...) } }`. No `NavHost`, no `rememberSaveableStateHolder`, no `SaveableStateProvider` anywhere in the file. Without a `SaveableStateHolder` the subtree's saveable entries are unregistered on exit, so `radiusKm` (449), `minRadiusKm` (450), `poiKind` (462), `directionDeg` (463), `settingsCollapsed` (526) all revert; every `remember` and every `LaunchedEffect(Unit)` restarts; `MapView` (592) is rebuilt and the style reloaded (652) | **TRUE.** Two precision notes: (i) it disposes MapScreen's *subtree*, not "the whole composition"; (ii) the `rememberSaveable` bullet alone is fixable with ~4 lines of `rememberSaveableStateHolder()` in `AppRoot` — no ViewModel needed. The other four bullets (plain `remember`, `LaunchedEffect(Unit)` restarts, `MapView` rebuild, style reload) do need state that outlives composition, so P3's headline survives. |
| 3.3 | P3: `displaySpeedKmh` is read inside the `Scaffold` content lambda → per-frame recomposition of "the bottom half of the tree" | Read at `1641` and `1643`; enclosing scopes are `Box`/`Column`/`Row`/`let` — all inline or plain Kotlin, so the nearest restart scope is the `Scaffold` content lambda opened at `1541`. Written every frame by `1251–1263` | **TRUE in mechanism, imprecise in both directions.** *Understated:* the invalidated scope is the entire content lambda `1541–1795`, which includes `AndroidView(factory = { mapView })` at 1547 and the top chrome — not just the bottom half. *Overstated:* `1259–1261` snaps to `target` once the gap is under `SPEED_EPS_KMH` (0.15 km/h, `247`), and `mutableDoubleStateOf` skips notification on an equal write, so at steady cruise it stops invalidating entirely. Recomposition is **bursty after each 1 Hz fix**, not continuous "whenever the vehicle is moving". The comment at `245–247` documents exactly this. |
| 3.4 | Inventory H9: `error` (460) has **12 writers and 1 reader**; permission/nav errors invisible when the sheet is collapsed | Writers: 728, 731, 778, 984, 991, 998, 1011, 1394, 1400, 1459, 1498, 1508 = **12**. Reader: `1771` (`SpinSheet(error = error)`), one. `SpinSheet` composes only when `bottomCard == EXPANDED` (1693), which needs `!navigating && displayCandidates.isEmpty() && !settingsCollapsed`; `settingsCollapsed` defaults **true** (526) | **TRUE, and the inventory's writer list is the only complete one.** P5 says "twelve" but lists 11 (omits 1400). P3 says "ten" and lists 10 (omits 1394). The inventory's list at `00:132` is correct. |
| 3.5 | Inventory: the comment at `MapScreen.kt:374–375` about RoutesScreen needing `SpinResultHolder` is **stale** | The comment reads *"seedRouteNavigation() below (and RoutesScreen.kt, which calls it) need to write into this holder from outside"*. `RoutesScreen.kt:202` **does** call `seedRouteNavigation`. But nothing outside `MapScreen.kt` touches `SpinResultHolder` or `SpinResult` (only 403, 453, 468, all in-file), and `seedRouteNavigation` returns `Unit`, so neither type is exposed in its signature — both could be `private` today | **SUBSTANCE TRUE, WORD WRONG.** It is not *stale* (nothing changed under it); it is a **misattributed justification** — placed above `internal data class SpinResult` to explain an `internal` that only `seedRouteNavigation` actually needs. The inventory's conclusion ("only `seedRouteNavigation` genuinely needs non-private visibility") is correct. |

### Group 4 — the KMP claims

| # | Claim | Source | Verdict |
|---|---|---|---|
| 4.1 | `com.jellemax.detour.data` is in commonMain | `shared/src/commonMain/kotlin/com/jellemax/detour/data/` — **36 files** | **TRUE**, with a fact no proposal states: the **same package name is also used by `app/src/main/java/com/jellemax/detour/data/`** (`RouteFiles.kt`, `AndroidSync.kt`, `ConfigFile.kt`, `Gpx.kt`). Adding `shared/…/data/Fix.kt` (P4 `04:372`) is therefore a same-package addition across two modules — legal, but a reader can no longer tell from the package which module a symbol is in. |
| 4.2 | P4: `pickThreeCandidates` is at `shared/…/SpinPicker.kt:27`, called from `iosApp/Detour/SpinModel.swift:129`, and MapScreen re-implements it inline at `1472–1489` | `SpinPicker.kt:27` `suspend fun pickThreeCandidates(…)`. `SpinModel.swift:129` `candidates = try await SpinPickerKt.pickThreeCandidates(…)`, with a Swift doc comment at :107–110 saying the rules "all live in `pickThreeCandidates` in `:shared`, so the two platforms cannot drift on any of it." MapScreen `1472–1494` rolls `(1..3).map { async(Dispatchers.IO) { runCatching { pickCandidate(…) } } }.awaitAll()`, then `mapNotNull { it.getOrNull() }`, then throws the first exception if empty — the same algorithm | **TRUE, and P4 undersold it.** The two copies have **diverged**: `SpinPicker.kt:44–48` re-throws any per-roll `CancellationException` before the empty check, with a comment saying why ("A cancellation is never a failed roll"); MapScreen's copy has the identical `runCatching` but **no such guard**. MapScreen additionally wraps in `withTimeout(30_000)` (1472), which `pickThreeCandidates` has not — so a naive swap silently drops the timeout. P4 flags the timeout at `04:728`; it does not flag the cancellation guard. |
| 4.3 | `commonTest` has real tests | 3 files, 839 lines, **60 `@Test` methods** (`ParsingTest` 24, `GroupsTest` 22, `RoutesTest` 14). Plus `androidUnitTest/RouteStoreLoadOrderTest.kt` (1 test) and `app/src/test/` (2 files, 8 tests) | **TRUE.** `commonTest` is by a wide margin the best-tested surface in the repo, and `shared/build.gradle.kts:52` already declares `kotlin("test")`. |
| 4.4 | P4: `commonMain` lacks `Dispatchers.IO`; `shared/` uses zero `Dispatchers.*` | `grep -rn Dispatchers shared/src` → two hits, both `iosMain/FlowWatcher.kt` (`Dispatchers.Main`). Zero `withContext` anywhere in `shared/`. `Http.kt:27–30` states the design: *"Everything here is suspending… every caller already wrapped it in a background dispatcher; those wrappers become plain suspend calls."* Every shared API MapScreen wraps is `suspend`: `RoadRoulette.speedLimitWays:263`, `SpeedCameras.near:56`, `CircleFixes.othersFixes:46`, `Geocoder.search:32` | **TRUE, on weaker evidence than the claim needs.** P4 proves *no one uses it* (`04:1041`), not *it is unavailable*. The stronger true statement: `Dispatchers.IO` is not published in kotlinx-coroutines' common metadata, and this module's hierarchy (androidTarget + 3 ios targets) has no jvm∩native intermediate source set, so `commonMain` genuinely cannot reference it. The conclusion holds. |
| 4.5 | P4: the `Dispatchers.IO` wrappers at `:1037`, `:1075`, `:1113`, `:1379`, `:1477`, `:1883` are vestigial; `:1407` and `:570` are not | All six wrap `suspend` Ktor calls (4.4) — vestigial. `:1407` wraps `ExploredArea.load()`, which is **not** `suspend` (`ExploredArea.kt:50`) and reads files via okio — genuinely blocking | **TRUE**, all eight correctly sorted. |
| 4.6 | Task premise: `shared/` is consumed by `app/`, `iosApp/` **and `wear/`** | `app/build.gradle.kts:135` has `implementation(project(":shared"))`. `iosApp/Detour/*.swift` × many `import DetourShared`. **`wear/build.gradle.kts` has no `project(":shared")` dependency** and `wear/src` contains zero `com.jellemax.detour.data` references | **REFUTED (the premise, not a proposal).** `:shared` has **two** consumers, not three. This slightly weakens the "third consumer" framing but does not change P4's case, which rests on the iOS consumer. |

### Group 5 — duplication with `car/`

Establishing the true list. All eleven rows verified on both sides.

| # | Logic | Phone | Car | Real divergence |
|---|---|---|---|---|
| D1 | 6 camera tuning constants | `MapScreen.kt:236–238`, `253–255` | `CarMapRenderer.kt:53–55`, `67–69` | none — byte-identical values |
| D2 | `CIRCLE_FIX_POLL_MS = 120_000L` | `:279` | `CarMapRenderer.kt:78` | none; car comment says *"see MapScreen's CIRCLE_FIX_POLL_MS"* |
| D3 | `smoothBearing` | `:224–230` (7) | `CarMapRenderer.kt:470–475` (6) | signature: `Float?` + default `alpha` vs non-null `Float`. Car doc says *"Same as the phone map's."* |
| D4 | Camera ease loop | `:1294–1332` (39) | `CarMapRenderer.kt:391–425`+`429–431` (38) | `withFrameNanos` vs `delay(33)`; `dt` clamp `0.1` vs `0.25`. Otherwise line-for-line |
| D5 | Circle-fix poll | `:1105–1119` (15) | `CarMapRenderer.kt:139–150` (12) | phone clears on sign-out; car skips the poll instead |
| D6 | Ambient speed-limit prefetch + snap + 3-miss hysteresis | `:1026–1054` (29) | `SpinScreen.kt:265–296` (32) | **car fetches off the collector via `limitFetchJob` (277) with an `isActive` guard (272); phone fetches inline (1037)**. Car names its constants (`SpinScreen.kt:52–61`); phone inlines `2.0`/`500.0`/`10_000`/`3` |
| D7 | Speed-camera prefetch | `:1062–1083` (22) | `NavScreen.kt:378–396` (19) | **same inline-vs-`cameraFetchJob` divergence** (386, guard at 383). Car names `CAMERA_FETCH_MARGIN_M`/`_THROTTLE_MS` (56–57) |
| D8 | Camera-warning latch + `ToneGenerator` chime | `:1145–1167` (23) | `NavScreen.kt:397–416` (20) | car also speaks (412) and toasts (413); phone falls back to the ambient limit when there is no route (1161), car uses `progress?.speedLimitKmh` only (405) |
| D9 | Arrival + reroute policy | `:1359–1389` (31) | `NavScreen.kt:243–277` (35) | car names `ARRIVE_METERS`/`OFF_ROUTE_METERS`/`REROUTE_COOLDOWN_MS` (53–55); phone inlines `40`/`60`/`15_000`. Car's own comment at `:242`: *"Same arrival/reroute policy as MapScreen.kt's navigating LaunchedEffect."* |
| D10 | `fetchLocation` (fused provider) | `:714–734` (21) | `SpinScreen.kt:298–310` (13), `SearchScreen.kt:164–173` (10) | **three** variants; only the phone tries `getCurrentLocation(PRIORITY_HIGH_ACCURACY)` before `lastLocation` |
| D11 | `geo:` handoff | `:3188–3192` (5) | `SpinScreen.kt:348–351` (4), `SearchScreen.kt:159–162` (4) | phone `startActivity`, car `startCarApp(ACTION_NAVIGATE)`; the two car copies are character-identical |

**True magnitude: ≈199 lines phone-side, ≈186 car-side.** Plus `* 3.6` inlined at 7 sites
(`NavScreen.kt:224`, `SpinScreen.kt:150`, `MapScreen.kt:1161`, `:1250`, `:1341`, `:1356`,
`:1357`) and `maneuverType` (`NavScreen.kt:572–593`) duplicating `ui/Navigation.kt`'s
`signIcon()` table — a car↔`Navigation.kt` duplication, not a MapScreen one.

| # | Estimate | Verdict |
|---|---|---|
| 5.1 | Inventory: "roughly **200–250** lines" have a near-verbatim twin | **TRUE**, at the low end. ~199 phone-side. Its per-item breakdown (`00:726–729`) matches D1–D5 and D8–D9 closely. |
| 5.2 | P5 C9: five specific citations (`CarMapRenderer.kt:53–69`, `:383–431`, `:470–475`, `:78`, `NavScreen.kt:378–415`) | **ALL FIVE TRUE.** The most precisely cited duplication list of the four. |
| 5.3 | P4: per-file removal `CarMapRenderer ~73`, `NavScreen ~62`, `SpinScreen ~46` = **181** | **PLAUSIBLE but optimistic.** Matches my ~186 car-side total, but assumes a shared abstraction absorbs the divergences in D6/D7 (job-guarded fetch), D8 (voice/toast), D9 (named constants) — each of which is a real behavioural difference, not formatting. |
| 5.4 | P5: "**≈45 lines** removed from `car/`" | **TRUE** for its narrower scope (D1 + D3 + D8 ≈ 42). Self-consistent with its targeted variant. |
| 5.5 | P1: 7-row table, "fixed by this proposal?" = Yes on 2, No on 5 (≈13 lines removed) | **TRUE and the most honest of the four.** P1 correctly identifies that a pure file split cannot share stateful loops across a Compose/non-Compose boundary (`01:729–732`). |
| 5.6 | P3: "`car/SpinScreen.kt:265-295` and `car/NavScreen.kt:375-405` are near-duplicates; delete ~60 lines" | **TRUE in substance, ranges off.** `SpinScreen` is 265–**296**; `NavScreen`'s warn latch is 397–**416** (375–405 clips it and includes unrelated KDoc). ~60 is between P5's 45 and P4's 181 and is the most defensible middle estimate. |

### Group 6 — dependency claims (P3)

| # | Claim | Source | Verdict |
|---|---|---|---|
| 6.1 | lifecycle `2.8.6` at `app/build.gradle.kts:142-143` | `142` `lifecycle-runtime-compose:2.8.6`, `143` `lifecycle-runtime-ktx:2.8.6` | **TRUE.** |
| 6.2 | Compose BOM `2024.09.02` at `:136`; `activity-compose:1.9.2` at `:140` | Verified at both lines | **TRUE.** |
| 6.3 | `lifecycle-viewmodel-compose:2.8.6` / `-savedstate:2.8.6` "pinned to the version already used" | **Neither artifact is currently declared.** The *version* 2.8.6 does match 6.1 | **TRUE as written** — P3 presents both as additions (`03:295–296`), not as existing entries. No version skew. |
| 6.4 | `kotlinx-coroutines-test:1.8.1` is **required and absent** | Absent: `grep -rn coroutines-test **/*.kts` → zero hits repo-wide. Version matches `kotlinx-coroutines-play-services:1.8.1` at `:149`. Required: `viewModelScope` dispatches on `Dispatchers.Main.immediate`, which throws in plain JVM JUnit without `Dispatchers.setMain` | **TRUE on all three sub-claims.** |
| 6.5 | P3: "the version `:shared` uses (`shared/build.gradle.kts:37`)" | Line **36** is `kotlinx-coroutines-core:1.8.1`; line 37 is `kotlinx-serialization-json:1.7.1` | **REFUTED** (off by one; the version 1.8.1 is right). |
| 6.6 | P3: "root `build.gradle.kts:4-5`" for Kotlin 2.0.20 **and** AGP 8.5.2 | Lines 4–5 are the two Kotlin plugins (2.0.20 ✓). AGP 8.5.2 is at lines **2–3** | **PARTLY REFUTED** (versions correct, citation covers only half). |
| 6.7 | P3: no Robolectric; `junit:junit:4.13.2` only, at `:163` | Verified — `testImplementation("junit:junit:4.13.2")` at exactly 163, sole test dependency | **TRUE.** |
| 6.8 | Implied: a version catalog exists | **`gradle/libs.versions.toml` does not exist.** `gradle/` contains only `wrapper/`; all versions are inline string literals | **N/A — no proposal claims one.** All five correctly cite inline coordinates. Worth stating because it makes every "pin to the existing version" step a literal string edit. |

### Group 7 — other load-bearing claims

| # | Claim | Verdict |
|---|---|---|
| 7.1 | P1: `internal` reaches `app/src/test`, "exactly the trick `HistoryScreen.kt:72` and `:120` already use" | **TRUE.** `internal data class TraceSegment` (72), `internal fun matchTripPoints` (120), consumed by `app/src/test/java/com/jellemax/detour/ui/TripTraceMatchingTest.kt` in package `com.jellemax.detour.ui`. |
| 7.2 | P1 / P2 / P5: `TravelMode.icon` (213–219) is used by three other screens | **TRUE**, exactly: `RoutesScreen.kt:303`, `HistoryScreen.kt:317`, `RouteEditorScreen.kt:386`. `BadgesScreen.kt:60` is an unrelated `BadgeKind.icon`. |
| 7.3 | P1: same-package top-level moves need no imports anywhere | **TRUE** — all proposed targets stay in `com.jellemax.detour.ui`, so `MainActivity.kt:223` and `RoutesScreen.kt:202` are untouched. This is the load-bearing fact under P1's "zero behaviour risk", and it holds. |
| 7.4 | P2 con #3: `collectAsStateWithLifecycle()` inside a factory does not isolate recomposition | **TRUE.** A `@Composable` factory's state reads are attributed to the caller's restart scope. P2 volunteering this against its own proposal is the single most credible passage in the folder. |
| 7.5 | P3: `RouteResult`/`RouteCandidate` are commonMain data classes, not `Parcelable`/`@Serializable` | **TRUE.** `SpinPicker.kt:11` `data class RouteCandidate(…)`, plain. The `TransactionTooLargeException` risk for a round-trip polyline is real and correctly reasoned. |
| 7.6 | Inventory H10: `myLocation` has two writers with different accuracy gates | **TRUE.** `555–558` gates on `accuracyMeters <= 100f`; `fetchLocation` at `724` writes `lastLocation` ungated. Any "unification" is a behaviour change. |
| 7.7 | Inventory: `spin()` at 1403 sets `camSuspended` but **not** `lastGestureMs`, unlike the other three park sites (810/813, 837/838, 1677/1679, 1829/1830) | **TRUE**, verified at all five sites. A latent asymmetry any camera-authority refactor must decide about deliberately. |
| 7.8 | P4: `car/NavScreen.kt:74–79` documents *not* sharing nav state with MapScreen as intentional | **TRUE**, KDoc at exactly 74–79. Correctly used by P4 to argue for `class` over `object`. |
| 7.9 | P2 / P5: the `car/` package is in the **same Gradle module** as `ui/`, so app-level extraction is reusable by `car/` for free | **TRUE** — both are under `app/src/main/java/com/jellemax/detour/`. This is why P4's `:shared` hop is optional for the car use case and mandatory only for the iOS one. |

---

## Corrected ground-truth numbers

A decision should be made on these, not on any proposal's figures.

**File shape**
| | |
|---|---:|
| `MapScreen.kt` total | **3,193** |
| `^import` lines | **206** (last at 208) |
| `MapScreen` composable body | **419–1837** = 1,419 |
| UI tree inside it | **1529–1837** = 309 |
| Presentational tail | **1839–3193** = 1,355 |
| Private funs in the tail | **31** (24 `@Composable` + 7 helpers) |
| Private funs in the file | **36**; composables **25** (24 private + `MapScreen`) |

**Inside `MapScreen.kt:419–1837`** — whole-file figure in brackets where different
| | |
|---|---:|
| top-level `remember*` declarations | **59** |
| remember-family call sites | **61** [76] |
| `LaunchedEffect(` | **32** [35] |
| `DisposableEffect(` | **4** [4] |
| `collectAsStateWithLifecycle()` | **21** [23] |
| `rememberSaveable` | **5** [5] |
| `rememberUpdatedState` | **8** [8] |
| `mutableStateOf`-family | **43** [43] |
| `derivedStateOf` / `snapshotFlow` / `produceState` | **0 / 0 / 0** |

**`TripTrackingService.lastFix`** — 6 subscriptions from MapScreen (5 raw `.collect` at
704/1026/1065/1147/1180 + `collectAsStateWithLifecycle` at 487), **10 consumer sites in
MapScreen**, **13 repo-wide** (+`ConvoyLiveClient.kt:382`, `car/NavScreen.kt:205`,
`car/SpinScreen.kt:131`).

**`error` (460)** — **12 writers** (728, 731, 778, 984, 991, 998, 1011, 1394, 1400, 1459,
1498, 1508), **1 reader** (1771), gated behind `settingsCollapsed == false` (default `true`, 526).

**`:shared`** — 43 `.kt` total: **36 commonMain**, 3 commonTest (**60 `@Test`**), 1 androidMain,
2 iosMain, 1 androidUnitTest. Zero `Dispatchers.*` and zero `withContext` in commonMain.
**Two consumers: `app/` and `iosApp/`. Not `wear/`.**

**`car/` duplication** — **≈199 lines phone-side / ≈186 car-side** across the eleven items
D1–D11 above.

**Realistic MapScreen.kt sizes, on comparable accounting** (code lines moved, before
import shedding):

| Scope of work | Moved | MapScreen.kt |
|---|---:|---:|
| P4 domain-only | ~838 | **~2,355** |
| P3 step-2 pure moves | ~1,300 | **~1,890** |
| P1 phases 1–2 | ~1,494 | **~1,700** (P1 says 1,565, needing ~134 shed imports) |
| **P1 + P4 combined** | **~2,330** | **~865** |
| P2 all 15 holders + tail | ~2,900 | **~295 code + imports ≈ 350** |
| P5 targeted MVI + `ui/map/` | ~2,790 | 400 itemised / **640 asserted** |

---

## Per-proposal accuracy scorecard

**00 — Inventory · 9/10.**
The reference document. Its writer list for `error` is the only complete one (3.4); its
duplication estimate is right (5.1); H10 and the 1403 asymmetry (7.6, 7.7) are findings no
proposal matched. −1 for "27 private composables/helpers" (actual 31 in the tail, 36 in the
file — the error four proposals inherited), and for calling the 374–375 comment "stale" when
it is a misattributed justification.

**01 — Mechanical split · 7/10.**
Its load-bearing facts are all true: 1,355 lines (1.8), same-package moves need no imports
(7.3), `internal` reaches `app/src/test` with a precedent (7.1), `TravelMode.icon`'s three
consumers (7.2), and the most honest car/ table in the folder (5.5). −2 for "27 private
composables" contradicted by its own tables (1.9). −1 for "none of them touch MapScreen's
state" (1.11) — `SearchDialog` alone is ~120 lines of debounce + Geocoder I/O. The ~1,565
is plausible but needs an unstated 134-import shed (2.1).

**02 — Compose state holders · 9/10.**
**Every raw count is exactly right** (59 / 32 / 4 / 21 — 1.1–1.4), which no other proposal
achieves, and its scoping (MapScreen's body) is the right one. Its file table is internally
consistent and its ~300 falls out of its own listed ranges (2.2). Its con #3 (7.4) argues
against itself correctly. −1: the ~300 omits the new file's own import block (realistic
~350–380), and it never says that reaching 300 means relocating ~2,900 lines — an order more
work than P1's 1,494 or P4's 838.

**03 — ViewModel / UiState · 7/10.**
Found the concern nobody else declared and it is real (3.2). Its recomposition analysis
(3.3) is mechanically correct — and it is the only proposal that notices the fix belongs to
the *composable split*, not the ViewModel (`03:900–902`). All dependency claims are true
(6.1–6.4, 6.7). −1 for undercounting `error`'s writers as ten (3.4). −1 for two citation
slips (6.5 `shared:37`→36; 6.6 AGP at 2–3 not 4–5). −1 because "disposes the whole
composition" overstates scope, and because the `rememberSaveable` bullet — presented as
ViewModel-only — is fixable with four lines of `rememberSaveableStateHolder` in `AppRoot`.

**04 — Domain services / `:shared` · 9/10.**
The only proposal whose central number is machine-checkable, and it checks out to the line
(1.5). Labels its own 934 an upper bound and nets it to 800 (1.6). The `1037`/`1075`
finding is exactly right and exactly cited (3.1). The `pickThreeCandidates` divergence is
real (4.2). Its `Dispatchers.IO` sort of the eight wrapper sites is correct on all eight
(4.5). −1 for "38 files" (actual 36, 1.7), for proving *unused* rather than *unavailable*
on `Dispatchers.IO` (4.4), and for a car/-removal estimate that assumes away the real D6–D9
divergences (5.3).

**05 — MVI / reducer · 7/10.**
The `lastFix` fan-out table is exact, all ten sites (1.12); C2's nine camera write sites are
exact (1.16); C9's five duplication citations are the most precise in the folder (5.2).
−1 for "twelve writers" printed as an eleven-item list (3.4). −1 for "thirteen places"
printed as twelve (1.15). −1 because its headline output number, **~640**, appears in no
table — the only itemised MapScreen figure it gives is 400, for a different variant (2.5).
Its own verdict (three machines, not nine) is more defensible than its framing.

---

## Claims where a proposal UNDERSOLD itself

1. **P4 on `pickThreeCandidates`.** It calls this "divergence has already happened"
   (`04:43`) and treats it as duplication. It is worse than duplication: `SpinPicker.kt:44–48`
   carries an explicit `CancellationException` guard, with a comment explaining that a
   cancelled roll must not be counted as a failed one, and **MapScreen's copy at `1474–1489`
   has the identical `runCatching` but not the guard**. The shared copy has a correctness
   fix the phone copy never received — the same shape as the `1037`/`1075` finding.

2. **P4 on the `1037`/`1075` stall.** It cites one fixed site (`NavScreen.kt:365–377`).
   There are **two**: `car/SpinScreen.kt:254–296` independently moved the *speed-limit*
   fetch off the collector (`limitFetchJob`, 277) with a comment saying "Same fix as
   `NavScreen.checkCameras`". The car surface hit this twice and fixed it twice; the phone
   has neither. That is a stronger pattern than one precedent.

3. **P3 on `displaySpeedKmh`.** It says "the bottom half of the map tree". The invalidated
   scope is the **entire `Scaffold` content lambda `1541–1795`** — the `AndroidView` holding
   the MapView (1547), the navigation banner, the top chrome and the PTT button included.

4. **P2's counts.** All four are exactly right and it presents them as approximate table
   rows. `59` top-level `remember*` declarations in one function body is the single most
   arresting number available for arguing that this file has a state-ownership problem, and
   P2 buries it in a summary table on line 16.

5. **P1 on `private`.** Its observation that the split "turns 12 nominally-private helpers
   into genuinely-private ones" (`01:212`) is true and undersold — `private` in a 3,193-line
   file is not an access control, and 36 declarations currently rely on it.

6. **Inventory on `car/` duplication.** Its "roughly 200–250 lines" undersells the *kind* of
   divergence it found: five of the eleven duplicated items (D6, D7, D8, D9, D10) are not
   copies but **copies that have since drifted**, and in D6/D7 the car copy is strictly the
   better one. That reframes the whole duplication argument from tidiness to defect risk.

---

## Convergence: recommendations reached independently

| # | Recommendation | Proposals | Audit verdict |
|---|---|---|---|
| C1 | Move the presentational tail (`1839–3193`, ± the pill rows) into its own files | 01, 02, 03, 05 | **Supported.** 1,355 lines, 24 composables + 7 helpers, and only **two** symbols in the whole file have any external reference. Zero-risk under C4. |
| C2 | Give `TravelMode.icon` its own file | 01, 02, 03, 05 | **Supported.** Three external consumers verified (7.2); it is the only reason a package move would cost more than 2 import lines. |
| C3 | Extract `SpinResult`/`SpinResultHolder`/`seedRouteNavigation` into one file, keeping `seedRouteNavigation` non-private so `RoutesScreen.kt:202` compiles unchanged | 01, 02, 03, 05 | **Supported**, and the audit adds: `SpinResult` and `SpinResultHolder` can become `private` in that file (3.5). |
| C4 | Keep new files in `com.jellemax.detour.ui`; promote moved symbols `private`→`internal`; `internal` reaches `app/src/test` | 01, 02, 03, 05 | **Supported**, with an in-repo precedent (7.1). This is what makes C1 genuinely zero-risk. |
| C5 | Unify camera-easing constants + `smoothBearing` into one tuning file and point `car/` at it | 01, 02, 04, 05 | **Supported.** D1 + D3 verified; the only disagreement is the destination (`ui/`, `map/`, or `:shared`). |
| C6 | Extract the three hazard machines (ambient limit `1018–1056`, camera warn `1134–1167`, section average `1169–1230`) as testable plain classes | 03, 04, 05 (and 02 as a Compose holder) | **Supported.** ~190 lines with five closure-local `var`s (`1063–1064`, `1146`, `1175–1179`) that no test can reach today. |
| C7 | Point `car/` at whatever C6 produces | 03, 04, 05 | **Supported but sequence-critical.** D6–D9 have drifted; the car copies are the *better* ones on D6/D7. Extract from the car version, not the phone version. |
| C8 | Move the Overpass fetch off the `lastFix` collector | 04 (explicit), 03 (step 3), 05 (SM6) | **Supported — and this is a bug fix, not a refactor.** Should be its own commit, before or independent of any split. |
| C9 | Do **not** collapse state into one monolithic `UiState`/`MapState` | 03 (explicit), 02 (implicit, 15 holders), 05 (three machines) | **Supported.** `displaySpeedKmh` is written per frame (`1251–1263`); `camTarget*` are written per fix and read only from a coroutine (`1265–1333`), so they cost nothing today and would cost a full-tree recomposition inside a shared state object. |
| C10 | Hoist `SpeedHud` behind a narrow parameter so `displaySpeedKmh` stops invalidating the map content lambda | 03 (explicit), 01/02/05 (as a side effect of C1) | **Supported.** Fixed by the composable split; no ViewModel, no holder, no reducer required. |

**Ten independent convergences.** C1–C5 are agreed by four proposals each, cost nothing
architecturally, and every factual claim under them survived this audit.

---

## Contradictions a decision must resolve

1. **Is `SearchDialog`'s debounce presentation or domain?** P1 counts `1839–3193` as 1,355
   presentational lines "that already have clean interfaces"; P4 classifies `1865–1894`
   inside it as **(a) domain logic**. Both cannot be the frame. *Audit: P4 is right about
   those 30 lines* — `RecentSearchStore.load/save` + `Geocoder.search` + a 300 ms debounce is
   not presentation. It does not change C1; the file still moves, it just is not "zero logic".

2. **Where do the three hazard machines live?** Four different answers for the same ~190
   lines: `ui/MapRoadHazardState.kt` as a Compose holder (P2 #8), `app/nav/*.kt` as plain
   classes (P3 step 3), `shared/…/drive/*.kt` in commonMain (P4), `app/map/SectionTracker.kt`
   (P5). *Only P4's choice imposes constraints*: commonMain has no `System.currentTimeMillis()`
   (used at 1032, 1070, 1152, 1189) and no `Dispatchers.IO` (4.4), so the code must be
   rewritten, not moved. P2's choice is the only one `car/` **cannot** reuse — `car/` has no
   composition to `remember` into.

3. **Package layout.** P1 requires flat `com.jellemax.detour.ui` for zero-import moves; P2
   offers `ui.map` as an optional last step; P3 uses `ui/` + `nav/`; P5 insists on
   `com.jellemax.detour.map` "**not** `ui.map` — the reducer is not UI". This must be decided
   once, first; changing it later re-touches every moved file.

4. **Does composition lifetime need a ViewModel?** P3 says yes and calls it "the one the
   other proposals cannot address by moving code between files" (`03:172`). *Audit: the
   defect is real (3.2), the prescription is heavier than one of its five symptoms needs.*
   `rememberSaveableStateHolder()` in `AppRoot` is ~4 lines and fixes the `rememberSaveable`
   bullet alone; the other four (plain `remember`, `LaunchedEffect(Unit)` restarts, MapView
   rebuild, style reload) do need state outliving composition. Neither "just add a holder"
   nor "you need MVVM" is the whole truth.

5. **Extract from the phone or from the car?** Every proposal that touches C7 assumes the
   phone version is the source. D6 and D7 show the car version is strictly better (fetch off
   the collector, named constants, re-entry guard). Extracting from the phone would port the
   stall into shared code and then need C8 applied on top.

6. **Is total LOC growth acceptable?** P1 +6% (~3,394), P2 +11% (~3,535), P5 +11% (~3,585)
   full / +2% targeted, P4 +~500 (~15%). All four state it openly. It is a decision, not a
   dispute — but the "MapScreen.kt is now N lines" headline is what will get quoted, and
   §"Corrected ground-truth numbers" shows those headlines are measuring four different
   amounts of work.

---

## What the evidence supports, regardless of pattern

1. **`MapScreen.kt:1037` and `:1075` are a defect, not a smell.** A `withContext(Dispatchers.IO)`
   inside a `StateFlow` collector conflates away every fix that lands during a slow Overpass
   round-trip — with the camera, HUD and turn card frozen behind it. The car surface hit this
   twice and fixed it twice, with written KDoc both times (`car/NavScreen.kt:365–377`,
   `car/SpinScreen.kt:254–264`). **Fix this first, in its own commit, on the car's pattern
   (`Job` + `isActive` guard). It does not depend on any proposal being adopted.**

2. **The presentational tail moves, whoever wins.** 1,355 lines, only two symbols with any
   external reference, same-package so no import churn, and an in-repo `internal`-to-test
   precedent. Four of five proposals independently prescribe it, and every fact underpinning
   it survived audit. It also fixes the `displaySpeedKmh` recomposition scope for free.

3. **`error` is broken in the product, not just in the code.** Twelve writers, one reader,
   behind a card that is collapsed by default. A user denied location permission (778) or
   whose navigation request failed (1011) sees nothing. This is a **behaviour change to
   schedule deliberately**, not something to "clean up" mid-refactor — the inventory is right
   that fixing it silently is a change, not a refactor.

4. **The ~199 duplicated lines with `car/` have already drifted, and the car is ahead.**
   On D6/D7 (fetch off the collector) and D9 (named thresholds) the car copy is the better
   one. Any shared extraction must be taken from `car/` and back-ported to the phone —
   the opposite of the direction every proposal assumes.

5. **`:shared` is a real, exercised, two-consumer KMP module — with real constraints.**
   36 commonMain files, 60 passing-shape `@Test`s in commonTest, a live Swift consumer at
   `SpinModel.swift:129`. But it has **two** consumers, not three (`wear/` does not depend on
   it), and commonMain has no `Dispatchers.IO` and no `System.currentTimeMillis()` — the four
   fix-driven blocks all use both. Moving to `:shared` is a rewrite; moving to `app/` is a
   move. That distinction, not the module's existence, is the decision.

6. **`pickThreeCandidates` is duplicated *and* the copies have diverged.** `shared/…/SpinPicker.kt:27`
   is called from iOS and carries a cancellation guard; `MapScreen.kt:1474–1489` is the same
   algorithm without it, plus a `withTimeout(30_000)` the shared one lacks. Reconciling these
   two is a small, self-contained, high-value commit that needs no architecture decision.

7. **Composition lifetime is a genuine unowned defect.** `MainActivity.kt:172` swaps screens
   with a bare `AnimatedContent`. Glancing at the Hub mid-drive loses the trajectcontrole
   average, the camera-warning latch, the prefetched camera set, the spin controls, and
   rebuilds the MapView. `SpinResultHolder` (369–385) is a hand-rolled workaround for exactly
   this, for exactly one field group, and its own comment says so. Whatever pattern is chosen,
   **the AppRoot navigation seam has to be part of the conversation** — it is the one thing
   file-level splitting provably cannot touch.

8. **Decide the scope before the pattern.** The five headline numbers (300 / 640 / 1,565 /
   1,840 / 2,355) correspond to 2,900 / 2,790 / 1,494 / 1,300 / 838 lines of relocation.
   P1 and P4 are near-disjoint: doing both, with no state-management pattern at all, lands
   `MapScreen.kt` at roughly **865 lines** — smaller than three of the five proposals' own
   targets, while introducing no ViewModel, no reducer, no holder and no new dependency.

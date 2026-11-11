# Detour

## Filing issues

File an issue only for something that needs its own change: a defect a rider
or self-hoster can hit, a missing piece other work depends on, or a decision
someone has to make. Review nits, "could be `internal`", a test that would be
nice, and suspicions nobody has confirmed go in the PR's *Known limits*, not in
a new issue. Before filing, search the open issues (`gh issue list --search`)
and comment on a match instead of opening a duplicate.

Every issue filed via `gh issue create` gets labels at creation, not after:

- One type label: `bug`, `enhancement`, `documentation`, or `chore`.
- One priority label if you can judge it (`p0-now`/`p1-next`/`p2-soon`/
  `p3-later`); otherwise `needs-triage`.

Never leave an issue with zero labels or with a type but no priority/
`needs-triage`. What each priority means, and the first-match-wins ladder for
picking one, is `CONTRIBUTING.md`'s "Triage" section.

## Versioning

Before creating a commit that will land on `main`, check whether `versionName`
in `app/build.gradle.kts` needs a semver bump — don't wait to be asked.

- Fix, no behaviour/API break -> bump patch (`1.76.0` -> `1.76.1`)
- New feature, backward compatible -> bump minor (`1.76.1` -> `1.77.0`)
- Breaking change (data format, wire protocol, min OS) -> bump major
- Docs, refactor, chore, test-only -> no bump

Mixed feature+fix in one PR bumps for the higher of the two. CI's `version`
check fails a PR that changes `app/src/main` or `shared/src/*Main` without a
bump; a refactor there gets the `no-bump` label instead. Full rationale
and the release-tag mechanics this feeds are in `CONTRIBUTING.md`'s
"Versioning" section — read it if a case doesn't fit the table above.

`versionCode` is derived from `versionName` (`major*10000 + minor*100 +
patch`) in `app/build.gradle.kts`; never set it by hand — bumping
`versionName` bumps it for free.

## Reviewing Kotlin

Every review in this repo — human or agent, a full PR or one changed file —
checks the diff against `docs/guidelines/`. The checklist is
`docs/guidelines/checklist.md`; a failure there is a finding, not a preference.

Route by what changed, and read the section before citing it:

| The diff touches | Read |
| --- | --- |
| a new file, or a file being split | `boundaries.md` (§8) — especially §8.4, the seven-parameter gate |
| state, a store, a presenter, a `remember` | `state.md` (§4) |
| `shared/`, `expect`/`actual`, an interface, a `StateFlow` | `multiplatform.md` (§5, §6) |
| where a file lives, or a new package | `architecture.md` (§1-3) |
| names, constants, comments | `conventions.md` (§7) |
| any shared logic | `testing.md` (§10) |

Cite the section number in the finding (`§8.4`), so the reader can check the
rule rather than take the finding on trust. Section numbers are stable across
files. If the guideline is wrong for the case in front of you, say so and why —
a documented exception is fine, a silent one is rot.

## Where things live

A map to skip the search step. Kotlin packages are under `com/jellemax/detour/`;
"app" is `app/src/main/java/…`, "shared" is `shared/src/commonMain/kotlin/…`.
Which surface new logic belongs to is the `detour-shared-core` skill; which `ui/`
package a file belongs to is `detour-ui-structure`.

| Area | Where |
| --- | --- |
| Domain logic, stores, API/sync clients (KMP) | shared `data/` — `*Store` (trips, traces, circles, convoys, friends, speed cameras…), `Api`, `SyncClient`, `Auth`/`Oidc`, `Routes`/`RoutingClient`/`NavEngine`, `Settings`, `FeatureFlags` |
| Live-drive logic (KMP) | shared `drive/` — camera warnings, speed limit and section average, convoy gap/relay (`ConvoyRelay`, `RelaySocket`), hard events, stop detection, traffic ETA |
| Presenters + UI state (KMP) | shared `presentation/` — `<Screen>Presenter` / `<Screen>State` pairs |
| Shared tests | `shared/src/commonTest/`, `shared/src/androidHostTest/` |
| Android screens (Compose) | app `ui/` — map screen is `MapScreen` plus its `Map*` files (`MapHud`, `MapCamera`, `MapNavigation`, `MapLibreMap`, …); settings are `Settings*` |
| Map camera / follow / mode policy | app `map/` (`FollowCamera`, `CameraAuthority`, `NavPolicy`, `ModeSwitch`) |
| Trip recording, location, geofences | app `tracking/` (`TripTrackingService`, `TripSession`, `TripStartDetector`/`TripEndDetector`, `FusedLocationSource`) |
| Android Auto | app `car/` (`DetourCarAppService`, `DetourCarSession`, `CarMapRenderer`, `NavScreen`) |
| Notifications, push, circle sync worker | app `notif/` |
| In-app APK update | app `update/` |
| BLE handlebar display, OBD2, audio | app `ble/`, `obd2/`, `audio/` |
| App entry + Android-only data glue | app root (`DetourApplication`, `MainActivity`), app `data/` |
| App unit tests | `app/src/test/` |
| iOS app (SwiftUI) | `iosApp/Detour/` — one `*.swift` per screen/feature, mirroring the Android names |
| Backend API (.NET) | `backend/Detour/Detour.Api/` (`Controllers/`, `Contracts/`, `Services/`, `Live/` WebSocket, `Notifications/`) |
| Backend domain + persistence | `backend/Detour/Detour.Domain/`, `Detour.Database/` (`Repositories/`, `EntityConfigurations/`, `Migrations/`) |
| Backend tests | `backend/Detour/Detour.Domain.Tests/`, `Detour.InfraTests/` |
| Backend cross-cutting libs | `backend/Shared/` (middlewares, caching, SSE, rate limiting, health checks) |
| Self-host deploy | `docker/prod/` (compose, `README.md`), dev stack `docker/dev/` |
| API request collection | `bruno/` |
| Data importers (cameras, POIs, roads, speed limits, municipalities) | `tools/*-importer/` |
| GPS replay routes | `tools/mocklocation/` (`detour-gps-replay` skill) |
| CI | `.github/workflows/` — `build.yml` (Android + release), `version.yml`, `ios.yml`, `backend.yml`, `compose.yml` |
| System architecture, services | `docs/ARCHITECTURE.md`; backend contract `docs/BACKEND_SPEC.md` |
| Map-screen refactor specs, divergence register | `docs/refactor/mapscreen/` |
| Review guidelines | `docs/guidelines/` (routing table above) |

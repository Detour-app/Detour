# In-app update check against GitHub releases

Implements the MVP of [#10](https://github.com/maxke24/Detour/issues/10). A sideloaded Detour
build has no way to learn that a newer one exists. CI already publishes everything the app
would need to find out.

Scope is narrower than #10 as filed, on two deliberate calls recorded under
[Decisions](#decisions): the update source is fixed to the repository that built the APK, and
a downloaded-but-not-installed APK is not remembered across launches.

## What the app does

1. Once an hour, while in the foreground, ask the building repository for its latest release.
2. If that release is newer than the running build, show a banner in the Hub and post one
   notification for that version.
3. On request, download the phone APK, verify it, and open the install sheet.

## Verified against the live API

Every claim below was checked with `gh api` and `curl` against `maxke24/Detour` on
2026-09-01, not inferred from the workflow.

`GET /repos/maxke24/Detour/releases/latest` returns:

```json
{
  "tag_name": "v1.87.0",
  "name": "v1.87.0",
  "prerelease": false,
  "assets": [
    { "name": "detour-1.87.0.apk",      "size": 45809687 },
    { "name": "detour-wear-1.87.0.apk", "size": 39816388 },
    { "name": "mapping.txt",            "size": 47632225 }
  ]
}
```

Two things this settles:

**The watch APK is a real trap.** `detour-wear-1.87.0.apk` also begins with `detour-`, so any
prefix match hands a phone the watch build. Selection must be exact.

**#10's "no redirect off github.com" constraint is not implementable.** A release asset
download always redirects to a signed, short-lived URL on a different host:

```
https://github.com/maxke24/Detour/releases/download/v1.87.0/detour-1.87.0.apk
  -> https://release-assets.githubusercontent.com/...?se=2026-09-01T09%3A21%3A01Z...
  200
```

The rule becomes **HTTPS only, and the final host must be `github.com` or
`*.githubusercontent.com`** — a pinned redirect target rather than no redirect.

## Architecture

Pure decision logic in `shared/commonMain`, I/O split by platform. iOS will never consume this
— the App Store owns that path — so the placement is not about reuse today. It is about
testability, since the pure half is the only part of this feature anything can test, and
`commonTest` is the best-protected test location in the repo
(`.claude/skills/detour-shared-core` §7). A second platform with a sideload channel would find
the logic already shared.

| Unit | Where | Responsibility |
|---|---|---|
| `UpdateCheck` | `shared/commonMain/.../data/` | Pure: parse a release, parse `update.json`, compare versions, select an artifact. No I/O. |
| `UpdateClient` | `shared/commonMain/.../data/` | `suspend` fetch over the existing `Http` client. Caller supplies the dispatcher; `commonMain` has no `Dispatchers`. |
| `UpdateDownloader` | `app/.../update/` | Streams the APK to `filesDir/updates/`, reports progress, hashes while writing. |
| `UpdateInstaller` | `app/.../update/` | `PackageInstaller` session, the unknown-sources gate, and the abort outcome. |
| `UpdateState` | `app/.../update/` | `object` holding a `StateFlow<UpdateStatus>`, the same shape as `SpinResultHolder`. |

`UpdateState` is an object rather than screen state on purpose. Since #82, leaving the Hub
disposes its composition; a download held in a `remember` would restart because the rider
glanced at the map.

### Version comparison

Compare `tag_name.removePrefix("v")` against `BuildConfig.VERSION_NAME`, as dotted numbers.

`versionCode` is unusable for this: `app/build.gradle.kts:75` reads it from `VERSION_CODE`,
which CI stamps from the run number, so it has no relationship to the tag. String comparison
is also wrong — `"1.10.0" < "1.9.0"` lexically.

### Picking the artifact

CI publishes an `update.json` release asset:

```json
{
  "version": "1.88.0",
  "artifacts": {
    "android-phone": { "asset": "detour-1.88.0.apk",      "size": 45809687, "sha256": "..." },
    "android-wear":  { "asset": "detour-wear-1.88.0.apk", "size": 39816388, "sha256": "..." }
  }
}
```

The app asks for its own platform key and never guesses a filename. This answers the watch-APK
trap, gives a place for the integrity check #10 wanted, and gives a future platform somewhere
to appear.

A release without `update.json` — anything published before this lands, or a fork whose CI has
not caught up — falls back to exact-name `detour-<version>.apk`. If that is absent too, the
banner appears but links to the release page instead of offering a download.

## The gate

The feature is off unless **both** conditions hold.

**A `githubRelease` build type** carries `REQUEST_INSTALL_PACKAGES`:

```kotlin
create("githubRelease") {
    initWith(getByName("release"))
    matchingFallbacks += listOf("release")
}
```

with the permission in `app/src/githubRelease/AndroidManifest.xml` and nowhere else. The Play
bundle is built from `release` and never carries it. A build type rather than a product
flavor, for the reason `app/build.gradle.kts:139-145` already gives about `automotive`: a
flavor dimension renames every existing variant task and would break the workflow and a dozen
skill references.

Since API 26 — this app's `minSdk` — an app cannot initiate an install without that
permission. It is install-time and never prompts by itself; the user-facing consent is the
separate per-app "Install unknown apps" toggle, read with `canRequestPackageInstalls()`.

**A non-blank `BuildConfig.UPDATE_REPO`**, supplied by CI as `${{ github.repository }}` and
empty in every other build. Blank means inert, degrading the way `BuildDefaults` already
degrades a missing secret rather than crashing. This is what stops a *local*
`assembleGithubRelease` from offering an update it could never install, since a locally signed
APK cannot update a CI-signed one.

Baking the repository in at build time, rather than exposing it as a setting, is what makes
the signing problem disappear: there is no way to point the app at a source whose key differs
from its own.

## Flow

`onStart` only, not `onResume`. Returning from the install sheet, a permission screen or the
browser all fire `onResume`, and re-entering the check on the way back from the thing the
check just started is how a state machine chases its own tail. The hourly throttle would mask
it; naming the callback means nobody has to rely on that.

```
MainActivity.onStart
  UPDATE_REPO blank?                      -> inert
  now - lastCheckMs < 1h?                 -> skip
  stamp lastCheckMs                       (before the request, not after)
  GET /releases/latest
  isNewer(VERSION_NAME, tag)?      no     -> UpToDate
  fetch update.json, artifactFor("android-phone")
                                          -> Available(version, asset, size, sha256)
       Hub banner, sticky until installed or superseded
       notifiedVersion != version         -> post notification, store version
  request -> Downloading(progress), stream to filesDir/updates/, sha256 while writing
          -> verify size and sha256
          -> Downloaded
  install -> canRequestPackageInstalls()? no -> ACTION_MANAGE_UNKNOWN_APP_SOURCES
          -> PackageInstaller session, commit, IntentSender
             STATUS_FAILURE_ABORTED       -> back to Downloaded, file kept for this session
```

`lastCheckMs` is stamped before the request so a device with no connectivity does not retry on
every resume.

Each check deletes anything in `updates/` that is not the version currently on offer, so
46 MB files cannot accumulate.

`app/src/main/res/xml/file_paths.xml` gains `<files-path name="updates" path="updates/" />`.
`filesDir`, not `cacheDir`, because the system can evict cache at any moment. The existing
scoping comment's property is preserved: trips and traces stay unreachable.

## Failure handling

| Case | Behaviour |
|---|---|
| Offline, DNS failure, 403 rate limit | Silent. No banner, no toast, no error state. |
| Release has no `update.json` | Fall back to exact-name; failing that, link to the release page. |
| sha256 or size mismatch | Delete the file, offer retry. Never install an unverified APK. |
| `POST_NOTIFICATIONS` denied | Banner only. Already requested elsewhere (`MapScreen.kt:556`), so no new permission. |
| Install permission not granted | Banner button goes to settings rather than failing at the sheet. |
| Install sheet dismissed | Back to `Downloaded`; the file survives the session. |

Silence is the default on purpose. This is a background courtesy, and a rider mid-ride should
never see it complain.

## Testing

`shared/src/commonTest/.../UpdateCheckTest.kt`:

- `1.9.0` vs `1.10.0` in both directions, equal versions, an older candidate, a missing patch
  segment, and a malformed tag
- manifest parsing: a missing platform key, malformed JSON, unknown extra fields
- artifact selection: an explicit assertion that `android-phone` never resolves to
  `detour-wear-*.apk`

The download and install halves are Android plus OS dialogs. This repo has no Robolectric, no
`compose-ui-test` and no `androidTest` source set, so they are hand-verified — but a real
end-to-end run is available: install **v1.86.0** and let it discover **v1.87.0**, both of which
are published releases. That exercises the whole path against production data.

## Decisions

**The update source is the repository that built the APK, and cannot be changed at runtime.**
#10 asked for a configurable source. Android refuses an in-place update signed with a
different key, so any source other than the one holding the release keystore ends at
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` — recoverable only by uninstalling, which takes the
rider's trips and traces with it. A setting whose only correct value is the default is not a
setting.

**A downloaded-but-not-installed APK is not remembered across launches.** #10 specified this;
it is deferred to keep the first change reviewable. The file survives a dismissed sheet within
a session, so the state machine already has the shape; persisting it is additive.

## Out of scope

- Cross-launch resume of a downloaded APK.
- Pre-releases. `/releases/latest` skips them, and with no channel switching there is nothing
  to point at one.
- iOS. The App Store owns that path.
- The watch updating itself. `android-wear` appears in the manifest so a future surface has it;
  nothing reads it yet.
- What the notification looks like beyond "one per version" — the existing channels in
  `TripEndedNotification.kt` set the pattern.

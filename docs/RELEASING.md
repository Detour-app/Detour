# Releasing

A push to `main` builds, signs, and publishes. There is no separate release
command: `.github/workflows/build.yml` produces four artifacts from one Gradle
invocation and ships them to two places.

| Artifact | Where it goes |
| --- | --- |
| `app-release.aab` | Play, internal testing track |
| `wear-release.aab` | Play, same release (routed to watches) |
| `detour-<version>.apk` | GitHub release, for sideloading |
| `detour-wear-<version>.apk` | GitHub release, for sideloading to a watch |

Play is uploaded before the GitHub release is cut, so a bundle Play rejects
never leaves a tag behind claiming the version shipped.

Internal track means testers on that list get it within minutes with no review
queue. **Nothing here writes to production** — promoting a build is a manual
step in the Play Console.

## Versioning

`versionName` in `app/build.gradle.kts` is yours to bump; it names the tag and
the GitHub release. Push twice without changing it and the second run replaces
that version's GitHub release rather than adding a second one.

`versionCode` is stamped by CI and ignores the literal in the file:

```
phone = 1000 + GITHUB_RUN_NUMBER * 2
watch = phone + 1
```

Play refuses any upload whose code isn't higher than every code it has already
accepted, and the two artifacts share an applicationId so their codes must also
differ from each other. The run number only ever increases, which is the one
property that matters. The `+1000` floor clears the codes already published as
GitHub-release APKs, so a sideloaded install still sees Play's copy as newer.

A local build keeps the literal `versionCode` — nothing about day-to-day
development changes.

## One-time Play Console setup

The Play Developer API cannot create an application, and it cannot perform the
**first** upload for one. That upload is manual, once:

1. Build a bundle locally and upload it under **Internal testing → Create new
   release** in the Play Console. Complete the store listing, content rating,
   and data safety form — Play blocks the release until all three are done.
2. Under **Internal testing → Testers**, create a tester list and add yourself.

Everything after that is CI's.

## Service account

CI authenticates as a Google Cloud service account that the Play Console has
granted release permission.

1. Google Cloud Console → **IAM & Admin → Service Accounts → Create**. No
   project roles are needed; Play grants its own permissions separately.
2. On that account, **Keys → Add key → Create new key → JSON**. Download it.
3. Play Console → **Users and permissions → Invite new users**, paste the
   service account's email. Grant **Release to testing tracks** on the Detour
   app only. Anything wider is more authority than the workflow uses.

## Repository secrets

Settings → Secrets and variables → Actions. All five are required on a push to
`main`; the workflow fails with the missing names rather than letting a build
run and break later.

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_B64` | `base64 -w0 release.jks` |
| `RELEASE_KEYSTORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | key alias inside the keystore |
| `RELEASE_KEY_PASSWORD` | key password |
| `PLAY_SERVICE_ACCOUNT_JSON` | the JSON key file's full contents |

The keystore here is the **upload** key. Play App Signing re-signs with its own
key before distributing, which is why an app installed from Play cannot be
updated by an APK downloaded from GitHub — the signatures differ. Pick one
source per device.

## The watch app is a separate artifact

It used to be embedded in the phone APK via `wearApp(project(":wear"))`. That
only ever auto-installed on Wear OS 1.x, and this watch app is `minSdk 30`
(Wear OS 3), so the embedded copy was 40 MB of payload that never ran — and
Play refuses a bundle carrying one. It now builds and uploads on its own, with
the same applicationId and the same upload key. Play routes it to watches by
the `<uses-feature android:name="android.hardware.type.watch" />` in
`wear/src/main/AndroidManifest.xml`; removing that line would send the watch
build to phones.

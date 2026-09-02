# Releasing

A push to `main` builds, signs, and publishes. There is no separate release
command: `.github/workflows/build.yml` produces two artifacts from one Gradle
invocation and ships them to two places.

| Artifact | Where it goes |
| --- | --- |
| `app-release.aab` | Play, internal testing track |
| `detour-<version>.apk` | GitHub release, for sideloading |

The GitHub release is cut first and Play second. Since the in-app update check
landed, that release is how every sideloaded install learns a new version
exists, so a Play verdict — which is about store policy, not about whether the
artifact is fit to sideload — no longer withholds it. A Play failure still
fails the job.

Internal track means testers on that list get it within minutes with no review
queue. **Nothing here writes to production** — promoting a build is a manual
step in the Play Console.

## Versioning

`versionName` in `app/build.gradle.kts` is yours to bump; it names the tag and
the GitHub release. Push twice without changing it and the second run replaces
that version's GitHub release rather than adding a second one.

`versionCode` is stamped by CI and ignores the literal in the file:

```
versionCode = 1000 + GITHUB_RUN_NUMBER * 2
```

Play refuses any upload whose code isn't higher than every code it has already
accepted. The run number only ever increases, which is the one property that
matters. The `+1000` floor clears the codes already published as GitHub-release
APKs, so a sideloaded install still sees Play's copy as newer.

The stride of 2 is vestigial: it left an odd slot for the watch bundle, which
shared the phone's applicationId and so needed a code of its own. The watch app
is gone (#57), but the stride stays — halving it would emit codes Play has
already accepted.

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

Settings → Secrets and variables → Actions. The four signing secrets are
required on a push to `main`; the workflow fails with the missing names rather
than letting a build run and break later.

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_B64` | `base64 -w0 release.jks` |
| `RELEASE_KEYSTORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | key alias inside the keystore |
| `RELEASE_KEY_PASSWORD` | key password |
| `PLAY_SERVICE_ACCOUNT_JSON` | the JSON key file's full contents (only read when Play publishing is switched on, below) |

## Switching Play publishing on

Play publishing is **off by default**. Until the Console account is verified and
the app has had its first bundle uploaded by hand, every API upload comes back
rejected — and a failed upload would take the whole run down with it, including
the GitHub release the APKs are attached to. Off means the step is skipped, not
that it fails.

Turn it on once Play is ready: Settings → Secrets and variables → Actions →
**Variables** → `PUBLISH_TO_PLAY` = `true`. With the variable unset, or set to
anything else, a push to `main` still builds, signs, tags and publishes the
GitHub release; it just doesn't talk to Play. If the variable says `true` but
`PLAY_SERVICE_ACCOUNT_JSON` is empty, the run warns and skips rather than
failing.

## Signing

The keystore is the **upload** key. Play App Signing re-signs with its own key
before distributing, which is why an app installed from Play cannot be updated
by an APK downloaded from GitHub — the signatures differ. Pick one source per
device.

# In-app update check against GitHub releases — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A sideloaded Detour build discovers, downloads and installs a newer release published by the repository that built it.

**Architecture:** Pure decision logic (version compare, release/manifest parsing, artifact selection) lives in `shared/commonMain` with tests in `commonTest`; the fetch goes through the existing `Http` client; download and install are Android-only. The feature is inert unless the build is the `githubRelease` variant *and* `BuildConfig.UPDATE_REPO` is non-blank.

**Tech Stack:** Kotlin Multiplatform, Ktor (`Http`), okio, Compose, Android `PackageInstaller`, GitHub Releases API.

**Spec:** `docs/superpowers/specs/2026-09-01-github-release-update-check-design.md`

---

## File structure

| File | Created/Modified | Responsibility |
|---|---|---|
| `shared/src/commonMain/kotlin/com/jellemax/detour/data/UpdateCheck.kt` | Create | Pure: version compare, release + manifest parsing, artifact selection |
| `shared/src/commonTest/kotlin/com/jellemax/detour/data/UpdateCheckTest.kt` | Create | Tests for all of the above |
| `shared/src/commonMain/kotlin/com/jellemax/detour/data/UpdateClient.kt` | Create | `suspend` fetch of the release and its manifest |
| `shared/src/commonMain/kotlin/com/jellemax/detour/data/Settings.kt` | Modify | Two prefs accessors |
| `app/build.gradle.kts` | Modify | `githubRelease` build type, `UPDATE_REPO` field |
| `app/src/githubRelease/AndroidManifest.xml` | Create | `REQUEST_INSTALL_PACKAGES`, that variant only |
| `app/src/main/res/xml/file_paths.xml` | Modify | `updates/` path for the FileProvider |
| `app/src/main/java/com/jellemax/detour/update/UpdateState.kt` | Create | Process-scoped status holder |
| `app/src/main/java/com/jellemax/detour/update/UpdateDownloader.kt` | Create | Streaming download + sha256 |
| `app/src/main/java/com/jellemax/detour/update/UpdateInstaller.kt` | Create | `PackageInstaller` session, unknown-sources gate |
| `app/src/main/java/com/jellemax/detour/update/InstallResultReceiver.kt` | Create | Launches the install sheet, records its outcome |
| `app/src/main/AndroidManifest.xml` | Modify | Registers that receiver |
| `app/src/main/java/com/jellemax/detour/update/UpdateNotification.kt` | Create | One notification per version |
| `app/src/main/java/com/jellemax/detour/ui/UpdateBanner.kt` | Create | The Hub banner |
| `app/src/main/java/com/jellemax/detour/ui/HubScreen.kt` | Modify | Render the banner |
| `app/src/main/java/com/jellemax/detour/MainActivity.kt` | Modify | Kick the check from `onStart` |
| `.github/workflows/build.yml` | Modify | Build the variant, emit `update.json`, publish it |

---

### Task 1: Version comparison

**Files:**
- Create: `shared/src/commonMain/kotlin/com/jellemax/detour/data/UpdateCheck.kt`
- Test: `shared/src/commonTest/kotlin/com/jellemax/detour/data/UpdateCheckTest.kt`

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/jellemax/detour/data/UpdateCheckTest.kt`:

```kotlin
package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers UpdateCheck.kt — the only part of the update feature anything can
 * test. The download and the install are Android plus OS dialogs; this is the
 * arithmetic and the parsing they depend on.
 */
class UpdateCheckTest {

    @Test
    fun aHigherPatchIsNewer() {
        assertTrue(UpdateCheck.isNewer(installed = "1.86.0", candidate = "1.86.1"))
    }

    /**
     * The reason this is not a string comparison: lexically "1.10.0" sorts
     * before "1.9.0", so a string compare stops offering updates at the tenth
     * minor of any major and nobody notices until it has been broken for weeks.
     */
    @Test
    fun tenIsNewerThanNine() {
        assertTrue(UpdateCheck.isNewer(installed = "1.9.0", candidate = "1.10.0"))
        assertFalse(UpdateCheck.isNewer(installed = "1.10.0", candidate = "1.9.0"))
    }

    @Test
    fun theSameVersionIsNotNewer() {
        assertFalse(UpdateCheck.isNewer(installed = "1.86.0", candidate = "1.86.0"))
    }

    @Test
    fun anOlderCandidateIsNotNewer() {
        assertFalse(UpdateCheck.isNewer(installed = "1.87.0", candidate = "1.86.9"))
    }

    /** A missing segment reads as zero, so 1.87 and 1.87.0 are the same build. */
    @Test
    fun aMissingPatchSegmentReadsAsZero() {
        assertFalse(UpdateCheck.isNewer(installed = "1.87.0", candidate = "1.87"))
        assertTrue(UpdateCheck.isNewer(installed = "1.87", candidate = "1.87.1"))
    }

    /**
     * Anything unparseable is "not newer", never "newer". The failure mode of
     * guessing wrong here is offering a rider a download that cannot install.
     */
    @Test
    fun aMalformedVersionIsNeverNewer() {
        assertFalse(UpdateCheck.isNewer(installed = "1.87.0", candidate = "nightly"))
        assertFalse(UpdateCheck.isNewer(installed = "1.87.0", candidate = ""))
        assertFalse(UpdateCheck.isNewer(installed = "garbage", candidate = "1.88.0"))
    }

    /** CI tags releases `v<versionName>`; the caller strips it, so this must
     *  not silently accept a tag that still carries the prefix. */
    @Test
    fun aTagPrefixIsNotStrippedHere() {
        assertFalse(UpdateCheck.isNewer(installed = "1.87.0", candidate = "v1.88.0"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*UpdateCheckTest*'`
Expected: FAIL — `Unresolved reference 'UpdateCheck'`

- [ ] **Step 3: Write minimal implementation**

Create `shared/src/commonMain/kotlin/com/jellemax/detour/data/UpdateCheck.kt`:

```kotlin
package com.jellemax.detour.data

/**
 * The decidable half of the update check: is the published release newer than
 * this build, and which file should be downloaded for it.
 *
 * Pure by design. The fetch is [UpdateClient]'s and the install is Android's,
 * and neither can be tested here — this can, and it holds the two rules that
 * fail silently when wrong.
 */
object UpdateCheck {

    /**
     * Whether [candidate] is a newer version than [installed].
     *
     * Dotted numbers compared component-wise, missing segments read as zero.
     * Not a string comparison: "1.10.0" sorts before "1.9.0" lexically.
     *
     * Anything unparseable is false. Offering an update the app cannot reason
     * about ends at an install sheet the rider cannot complete.
     */
    fun isNewer(installed: String, candidate: String): Boolean {
        val a = parseVersion(installed) ?: return false
        val b = parseVersion(candidate) ?: return false
        val width = maxOf(a.size, b.size)
        for (i in 0 until width) {
            val ai = a.getOrElse(i) { 0 }
            val bi = b.getOrElse(i) { 0 }
            if (bi != ai) return bi > ai
        }
        return false
    }

    /** Null for anything that is not dot-separated non-negative integers. */
    private fun parseVersion(v: String): List<Int>? {
        if (v.isBlank()) return null
        val parts = v.split(".")
        return parts.map { it.toIntOrNull() ?: return null }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*UpdateCheckTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/UpdateCheck.kt \
        shared/src/commonTest/kotlin/com/jellemax/detour/data/UpdateCheckTest.kt
git commit -m "feat(update): compare release versions as dotted numbers"
```

---

### Task 2: Release and manifest parsing, artifact selection

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/UpdateCheck.kt`
- Test: `shared/src/commonTest/kotlin/com/jellemax/detour/data/UpdateCheckTest.kt`

- [ ] **Step 1: Write the failing test**

Append inside `class UpdateCheckTest`, before its closing brace:

```kotlin
    // --- release parsing --------------------------------------------------

    /** Trimmed from the real response for maxke24/Detour v1.87.0, captured
     *  2026-09-01. The asset list order and the wear entry are as published. */
    private fun releaseJson() = """
        {
          "tag_name": "v1.87.0",
          "name": "v1.87.0",
          "prerelease": false,
          "assets": [
            {"name": "detour-1.87.0.apk", "size": 45809687,
             "browser_download_url": "https://github.com/o/r/releases/download/v1.87.0/detour-1.87.0.apk"},
            {"name": "detour-wear-1.87.0.apk", "size": 39816388,
             "browser_download_url": "https://github.com/o/r/releases/download/v1.87.0/detour-wear-1.87.0.apk"},
            {"name": "update.json", "size": 412,
             "browser_download_url": "https://github.com/o/r/releases/download/v1.87.0/update.json"}
          ]
        }
    """.trimIndent()

    @Test
    fun aReleaseYieldsItsVersionWithoutTheTagPrefix() {
        val r = UpdateCheck.parseRelease(releaseJson())
        assertNotNull(r)
        assertEquals("1.87.0", r.version)
        assertFalse(r.prerelease)
    }

    @Test
    fun anAssetIsFoundByExactName() {
        val r = UpdateCheck.parseRelease(releaseJson())
        assertNotNull(r)
        assertEquals(
            "https://github.com/o/r/releases/download/v1.87.0/update.json",
            r.assetUrl("update.json"),
        )
        assertNull(r.assetUrl("nothing-like-this.apk"))
    }

    @Test
    fun unparseableReleaseJsonYieldsNoRelease() {
        assertNull(UpdateCheck.parseRelease("{\"tag_name\":"))
        assertNull(UpdateCheck.parseRelease(""))
    }

    // --- manifest and artifact selection ----------------------------------

    private fun manifestJson() = """
        {
          "version": "1.87.0",
          "artifacts": {
            "android-phone": {"asset": "detour-1.87.0.apk", "size": 45809687, "sha256": "aaaa"},
            "android-wear":  {"asset": "detour-wear-1.87.0.apk", "size": 39816388, "sha256": "bbbb"}
          }
        }
    """.trimIndent()

    /**
     * The trap this whole manifest exists for: `detour-wear-1.87.0.apk` also
     * begins with `detour-`, so any prefix match hands a phone the watch build
     * — an APK that installs as a different application and looks like a
     * corrupt update.
     */
    @Test
    fun thePhoneArtifactIsNeverTheWatchBuild() {
        val m = UpdateCheck.parseManifest(manifestJson())
        assertNotNull(m)
        val phone = UpdateCheck.artifactFor(m, UpdateCheck.PLATFORM_ANDROID_PHONE)
        assertNotNull(phone)
        assertEquals("detour-1.87.0.apk", phone.asset)
        assertEquals(45809687L, phone.size)
        assertEquals("aaaa", phone.sha256)
    }

    @Test
    fun anAbsentPlatformYieldsNoArtifact() {
        val m = UpdateCheck.parseManifest(manifestJson())
        assertNotNull(m)
        assertNull(UpdateCheck.artifactFor(m, "ios"))
    }

    /** A newer CI may add keys this build has never heard of. Ignoring them
     *  rather than failing is what lets an old app read a new manifest. */
    @Test
    fun unknownFieldsAreIgnored() {
        val m = UpdateCheck.parseManifest(
            """
            {
              "version": "1.88.0",
              "channel": "stable",
              "artifacts": {
                "android-phone": {"asset": "a.apk", "size": 1, "sha256": "c", "signedBy": "x"}
              }
            }
            """.trimIndent()
        )
        assertNotNull(m)
        assertEquals("a.apk", UpdateCheck.artifactFor(m, UpdateCheck.PLATFORM_ANDROID_PHONE)?.asset)
    }

    @Test
    fun unparseableManifestYieldsNothing() {
        assertNull(UpdateCheck.parseManifest("not json"))
        assertNull(UpdateCheck.parseManifest(""))
    }

    /** The fallback for a release published before update.json existed. */
    @Test
    fun theConventionalAssetNameIsBuiltFromTheVersion() {
        assertEquals("detour-1.88.0.apk", UpdateCheck.conventionalPhoneAsset("1.88.0"))
    }
```

Add these imports at the top of the test file:

```kotlin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
```

- [ ] **Step 2: Run test to verify it fails**

Run: `devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*UpdateCheckTest*'`
Expected: FAIL — `Unresolved reference 'parseRelease'`

- [ ] **Step 3: Write minimal implementation**

Append to `UpdateCheck.kt`, inside `object UpdateCheck`:

```kotlin
    /** The platform key this build asks the manifest for. */
    const val PLATFORM_ANDROID_PHONE = "android-phone"

    data class Release(
        val version: String,
        val prerelease: Boolean,
        /** Asset name to its `browser_download_url`. */
        val assets: Map<String, String>,
    ) {
        fun assetUrl(name: String): String? = assets[name]
    }

    data class Artifact(val asset: String, val size: Long, val sha256: String)

    data class UpdateManifest(val version: String, val artifacts: Map<String, Artifact>)

    /** Null for anything unreadable — same contract as `TripStore.load`'s catch
     *  and `RelayProtocol.decode`: a bad payload is "no update", never a throw
     *  reaching a screen. */
    fun parseRelease(text: String): Release? = try {
        val o = jsonObjectOf(text)
        val assets = (o.optArray("assets")?.objects() ?: emptyList()).associate {
            it.optString("name") to it.optString("browser_download_url")
        }
        Release(
            version = o.optString("tag_name").removePrefix("v"),
            prerelease = o.optBoolean("prerelease", false),
            assets = assets,
        )
    } catch (e: Exception) {
        null
    }

    fun parseManifest(text: String): UpdateManifest? = try {
        val o = jsonObjectOf(text)
        val artifacts = o.optObject("artifacts") ?: throw IllegalArgumentException("no artifacts")
        UpdateManifest(
            version = o.optString("version"),
            artifacts = artifacts.keys.mapNotNull { key ->
                val a = artifacts.optObject(key) ?: return@mapNotNull null
                key to Artifact(
                    asset = a.optString("asset"),
                    size = a.optLong("size", 0L),
                    sha256 = a.optString("sha256"),
                )
            }.toMap(),
        )
    } catch (e: Exception) {
        null
    }

    fun artifactFor(manifest: UpdateManifest, platform: String): Artifact? =
        manifest.artifacts[platform]

    /**
     * The asset name CI has used since before `update.json` existed. Only for
     * a release that carries no manifest — a fork whose workflow has not caught
     * up, or anything published before this feature landed.
     */
    fun conventionalPhoneAsset(version: String): String = "detour-$version.apk"
```

- [ ] **Step 4: Run test to verify it passes**

Run: `devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*UpdateCheckTest*'`
Expected: PASS — 15 tests

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/UpdateCheck.kt \
        shared/src/commonTest/kotlin/com/jellemax/detour/data/UpdateCheckTest.kt
git commit -m "feat(update): parse a release and its manifest, select by platform"
```

---

### Task 3: The fetch

**Files:**
- Create: `shared/src/commonMain/kotlin/com/jellemax/detour/data/UpdateClient.kt`

No test. `Http` is a concrete Ktor client with no injection seam — `detour-shared-core` §4 says to test the parsing and not the fetch, and Task 2 is that parsing. This file is wiring only.

- [ ] **Step 1: Write the implementation**

```kotlin
package com.jellemax.detour.data

/**
 * Asks a GitHub repository whether it has published something newer.
 *
 * `suspend` with no dispatcher of its own: `commonMain` has none, so the caller
 * picks — `withContext(Dispatchers.IO)` on Android. Same contract as every
 * other network API here.
 */
object UpdateClient {

    /** GitHub asks for this on the REST API; without it you get the v3 default,
     *  which is the same today and need not stay that way. */
    private val HEADERS = mapOf("Accept" to "application/vnd.github+json")

    data class PendingUpdate(
        val version: String,
        val asset: String,
        val downloadUrl: String,
        /** 0 when the release carries no manifest — nothing to verify against. */
        val size: Long,
        /** Blank when the release carries no manifest. */
        val sha256: String,
    )

    /**
     * The newest release of [repo] if it is newer than [installedVersion],
     * else null.
     *
     * Returns null when there is simply nothing to offer: the release is not
     * newer, the manifest names no artifact for this platform, or a release
     * with no manifest has no conventionally-named asset either.
     *
     * Failures fetching or parsing the release itself — offline, rate-limited,
     * any non-2xx — throw, which is what [Throws] is for. The caller decides
     * what silence means; on Android that is a silent skip until the next
     * hourly check.
     */
    @Throws(Exception::class)
    suspend fun newerThan(repo: String, installedVersion: String): Available? {
        if (repo.isBlank()) return null
        val releaseText = Http.get("https://api.github.com/repos/$repo/releases/latest", HEADERS)
        val release = UpdateCheck.parseRelease(releaseText) ?: return null
        if (!UpdateCheck.isNewer(installedVersion, release.version)) return null

        // Only a release published before update.json existed may fall back to
        // the conventional filename. If the asset is *there* but unreadable,
        // that is a transient failure, not a manifest-less release — falling
        // back would silently downgrade a checksummed download to an unchecked
        // one, letting a network blip decide whether the APK gets verified.
        // No update this hour; the next check retries.
        val manifestUrl = release.assetUrl("update.json")
        val artifact = if (manifestUrl == null) {
            null
        } else {
            val text = runCatching { Http.get(manifestUrl) }.getOrNull() ?: return null
            val manifest = UpdateCheck.parseManifest(text) ?: return null
            UpdateCheck.artifactFor(manifest, UpdateCheck.PLATFORM_ANDROID_PHONE) ?: return null
        }

        val assetName = artifact?.asset ?: UpdateCheck.conventionalPhoneAsset(release.version)
        val url = release.assetUrl(assetName) ?: return null
        return PendingUpdate(
            version = release.version,
            asset = assetName,
            downloadUrl = url,
            size = artifact?.size ?: 0L,
            sha256 = artifact?.sha256 ?: "",
        )
    }
}
```

- [ ] **Step 2: Verify it compiles for every target**

Run: `devcontainer-exec ./gradlew :shared:compileCommonMainKotlinMetadata`
Expected: BUILD SUCCESSFUL. This is the check that catches `java.*` leaking into `commonMain`.

- [ ] **Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/UpdateClient.kt
git commit -m "feat(update): fetch the latest release and its manifest"
```

---

### Task 4: Persisted check state

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/Settings.kt`

- [ ] **Step 1: Add the accessors**

Add next to `lastSyncMs` / `setLastSyncMs` (around `Settings.kt:476`), matching their shape exactly:

```kotlin
    /** When the update check last ran, throttling it to once an hour. Stamped
     *  before the request, not after: a device with no connectivity would
     *  otherwise retry on every foreground. */
    fun lastUpdateCheckMs(): Long = prefs.long("last_update_check_ms", 0L)

    fun setLastUpdateCheckMs(tsMs: Long) {
        prefs.put("last_update_check_ms", tsMs)
    }

    /** The version a notification has already been posted for. One per version,
     *  so a rider who declines an update is not told about it hourly. */
    fun notifiedUpdateVersion(): String = prefs.string("notified_update_version", "")

    fun setNotifiedUpdateVersion(version: String) {
        prefs.put("notified_update_version", version)
    }
```

- [ ] **Step 2: Verify it compiles**

Run: `devcontainer-exec ./gradlew :shared:compileCommonMainKotlinMetadata`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/Settings.kt
git commit -m "feat(update): persist the check throttle and the notified version"
```

---

### Task 5: The build variant and the gate

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/githubRelease/AndroidManifest.xml`

- [ ] **Step 1: Add the build type**

In `app/build.gradle.kts`, inside `buildTypes { ... }`, after the `release { ... }` block and before `create("automotive")`:

```kotlin
        // The APK published to GitHub Releases, and the only build allowed to
        // update itself. Identical to release in every other way — same R8
        // config, same signing, same applicationId — so the artifact a rider
        // installs is the release build plus one permission.
        //
        // A separate build type because the permission has to be absent from
        // the Play bundle, and a manifest source set keys off a variant. Play
        // is built with bundleRelease, which stays on `release` and never sees
        // REQUEST_INSTALL_PACKAGES. A build type rather than a flavor for the
        // reason the automotive block below gives: a flavor dimension renames
        // every existing variant task.
        create("githubRelease") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
        }
```

- [ ] **Step 2: Add the repository field**

In `app/build.gradle.kts`, in `defaultConfig { ... }` next to the other `buildConfigField` lines (around line 84):

```kotlin
        // The repository whose releases this build updates itself from, passed
        // by CI as github.repository so a fork's build points at the fork.
        // Blank everywhere else, which makes the whole feature inert — a local
        // build is signed with a different key and could never install a CI
        // APK anyway.
        buildConfigField("String", "UPDATE_REPO",
            "\"${System.getenv("UPDATE_REPO") ?: ""}\"")
```

- [ ] **Step 3: Add the variant manifest**

Create `app/src/githubRelease/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- This variant only. The Play bundle is built from `release` and must not
     carry REQUEST_INSTALL_PACKAGES: a store-installed copy updating itself
     from GitHub is against Play policy, and the permission is restricted.

     Install-time, so it never prompts. The rider-facing consent is the
     separate per-app "Install unknown apps" toggle, read with
     canRequestPackageInstalls() — see UpdateInstaller. -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
</manifest>
```

- [ ] **Step 4: Verify both variants build and only one has the permission**

```bash
devcontainer-exec ./gradlew :app:assembleGithubRelease :app:bundleRelease
```

Expected: BUILD SUCCESSFUL. Then confirm the split:

```bash
devcontainer-exec ./gradlew :app:processGithubReleaseMainManifest :app:processReleaseMainManifest
for v in githubRelease release; do
  f=$(find app/build/intermediates/merged_manifest/$v -name AndroidManifest.xml 2>/dev/null | head -1)
  if [ -z "$f" ]; then echo "$v: MANIFEST NOT FOUND — check failed, do NOT interpret as zero"; continue; fi
  echo "$v: $(python3 -c "
import xml.etree.ElementTree as ET
r=ET.parse('$f').getroot()
ns='{http://schemas.android.com/apk/res/android}'
print(sum(1 for e in r.findall('uses-permission') if e.get(ns+'name')=='android.permission.REQUEST_INSTALL_PACKAGES'))")"
done
```

Expected: `githubRelease: 1`, `release: 0`.

**Parse the XML; do not grep the string.** Two ways a text search gets this wrong, both
observed on this project. A bare `grep -c` on a glob prints nothing when the glob matches
nothing, which reads exactly like a clean `0`. And the manifest merger preserves source XML
comments verbatim — the comment in `app/src/githubRelease/AndroidManifest.xml` contains the
words `REQUEST_INSTALL_PACKAGES`, so a plain-string count returns **2**, not 1. Counting
`<uses-permission>` elements by their `android:name` is immune to both. This check is the
entire argument that the Play artifact ships without a restricted permission, so it must not
be able to pass by being wrong.

**Do not use a bare `grep -c` on a glob here.** If the glob matches nothing, grep prints nothing and exits non-zero, which reads exactly like a clean `0` — and this check *is* the entire argument that the Play bundle ships without a restricted permission. The loop above fails loudly instead. The verified path shape is
`app/build/intermediates/merged_manifest/<variant>/<task>/AndroidManifest.xml`, confirmed by running
`:app:processDebugMainManifest` on 2026-09-01; note the task is `process<Variant>MainManifest`, not
`process<Variant>Manifest`.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/githubRelease/AndroidManifest.xml
git commit -m "feat(update): a githubRelease variant that may install packages"
```

---

### Task 6: FileProvider path

**Files:**
- Modify: `app/src/main/res/xml/file_paths.xml`

- [ ] **Step 1: Add the scoped path**

Replace the file with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Scoped to the one cache subdirectory GPX exports are written to, and the
     one files subdirectory update APKs are written to. Trips and traces live
     at the filesDir root and are deliberately not reachable from here.

     updates/ is under filesDir rather than cacheDir because the system can
     evict cache at any moment, and an evicted APK is a 46 MB download thrown
     away between the download finishing and the rider tapping install. -->
<paths>
    <cache-path name="shared" path="shared/" />
    <files-path name="updates" path="updates/" />
</paths>
```

- [ ] **Step 2: Verify it builds**

Run: `devcontainer-exec ./gradlew :app:assembleGithubRelease`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/xml/file_paths.xml
git commit -m "feat(update): let the FileProvider reach the updates directory"
```

---

### Task 7: The status holder

**Files:**
- Create: `app/src/main/java/com/jellemax/detour/update/UpdateState.kt`

- [ ] **Step 1: Write the implementation**

```kotlin
package com.jellemax.detour.update

import com.jellemax.detour.data.UpdateClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the app currently knows about a newer release.
 *
 * An object rather than screen state on purpose. Since #82 the Hub keeps its
 * saved state but its composition is still disposed when the rider goes to the
 * map, and a download held in a `remember` would restart from zero because
 * somebody glanced at the map. Same reasoning, and the same shape, as
 * SpinResultHolder.
 */
sealed interface UpdateStatus {
    /** Nothing known, or nothing newer. */
    data object None : UpdateStatus
    data class Available(val update: UpdateClient.PendingUpdate) : UpdateStatus
    data class Downloading(val update: UpdateClient.PendingUpdate, val fraction: Float) : UpdateStatus
    data class Downloaded(val update: UpdateClient.PendingUpdate, val path: String) : UpdateStatus
    /** The download failed or the file did not verify. The banner offers a retry. */
    data class Failed(val update: UpdateClient.PendingUpdate) : UpdateStatus
}

object UpdateState {
    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.None)
    val status: StateFlow<UpdateStatus> = _status

    fun set(status: UpdateStatus) {
        _status.value = status
    }

    /** The update currently on offer, whatever phase it is in. */
    fun current(): UpdateClient.PendingUpdate? = when (val s = _status.value) {
        is UpdateStatus.Available -> s.update
        is UpdateStatus.Downloading -> s.update
        is UpdateStatus.Downloaded -> s.update
        is UpdateStatus.Failed -> s.update
        UpdateStatus.None -> null
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `devcontainer-exec ./gradlew :app:compileGithubReleaseKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/update/UpdateState.kt
git commit -m "feat(update): hold update status outside the composition"
```

---

### Task 8: The download

**Files:**
- Create: `app/src/main/java/com/jellemax/detour/update/UpdateDownloader.kt`

- [ ] **Step 1: Write the implementation**

```kotlin
package com.jellemax.detour.update

import android.content.Context
import android.util.Log
import com.jellemax.detour.data.UpdateClient
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Streams an update APK to `filesDir/updates/` and verifies it.
 *
 * Not on the shared Http client: that returns `bodyAsText()`, which for a 46 MB
 * binary means holding it in memory as a String. This streams, reports
 * progress, and hashes as it writes so the file is read once.
 */
object UpdateDownloader {

    private const val DIR = "updates"

    /** GitHub redirects release assets to a signed, short-lived URL on a
     *  different host — verified 2026-09-01, `release-assets.githubusercontent.com`.
     *  The redirect cannot be refused, so it is pinned instead: HTTPS, and a
     *  host GitHub actually serves assets from. This ends in an installable
     *  package; an open redirect here is an arbitrary-APK install. */
    private fun allowed(url: URL): Boolean =
        url.protocol == "https" &&
            (url.host == "github.com" || url.host.endsWith(".githubusercontent.com"))

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /**
     * Deletes every file in `updates/` except [keep].
     *
     * Called on each check, so a superseded 46 MB APK cannot sit there
     * forever — and, since nothing is persisted across launches in this
     * version, so yesterday's abandoned download is not mistaken for today's.
     */
    fun prune(context: Context, keep: String?) {
        dir(context).listFiles()?.forEach {
            if (it.name != keep) it.delete()
        }
    }

    /**
     * Downloads [update] and returns the file, or null on any failure.
     *
     * [onProgress] receives 0f..1f, or -1f when the server sends no length.
     * Blocking: call from `Dispatchers.IO`.
     */
    fun download(
        context: Context,
        update: UpdateClient.PendingUpdate,
        onProgress: (Float) -> Unit,
    ): File? {
        val url = runCatching { URL(update.downloadUrl) }.getOrNull() ?: return null
        if (!allowed(url)) {
            Log.w("DetourUpdate", "refusing download from ${url.host}")
            return null
        }
        val target = File(dir(context), update.asset)
        val digest = MessageDigest.getInstance("SHA-256")
        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 30_000
            }
            // responseCode first, deliberately. getURL() does not report the
            // redirect target until the response headers have arrived —
            // Android's libcore says so outright — so checking it before any
            // I/O just re-tests the URL allowed() already passed above, and
            // would wave through a redirect to anywhere. Still a gate rather
            // than a postmortem: this runs before a single body byte is read.
            //
            // libcore also refuses any protocol-switching redirect in either
            // direction, so an https -> http downgrade never reaches here.
            val code = connection.responseCode
            if (!allowed(connection.url)) {
                Log.w("DetourUpdate", "refusing redirect to ${connection.url.host}")
                return null
            }
            // Without this a 404 streams its HTML body into the file and the
            // rider is offered an "APK" that is an error page. A manifest-less
            // release has no size or hash to catch that later.
            if (code !in 200..299) {
                Log.w("DetourUpdate", "download refused: HTTP $code")
                return null
            }
            val total = connection.contentLengthLong
            var read = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        onProgress(if (total > 0) read.toFloat() / total else -1f)
                    }
                }
            }
            if (!verify(target, digest, update, read)) {
                target.delete()
                return null
            }
            target
        } catch (e: Exception) {
            Log.w("DetourUpdate", "download failed", e)
            target.delete()
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** Size and hash both, when the manifest supplied them.
     *
     *  A blank sha256 now means one thing only: the release carries no
     *  update.json at all, i.e. it predates the manifest. UpdateClient returns
     *  null rather than falling back when a manifest is present but
     *  unreadable, so a transient network failure can no longer arrive here
     *  looking like a manifest-less release and skip verification. The install
     *  sheet still shows the signer either way. */
    private fun verify(
        file: File,
        digest: MessageDigest,
        update: UpdateClient.PendingUpdate,
        read: Long,
    ): Boolean {
        if (update.size > 0 && read != update.size) {
            Log.w("DetourUpdate", "size mismatch: got $read want ${update.size}")
            return false
        }
        if (update.sha256.isNotBlank()) {
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (!hex.equals(update.sha256, ignoreCase = true)) {
                Log.w("DetourUpdate", "sha256 mismatch")
                return false
            }
        }
        return true
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `devcontainer-exec ./gradlew :app:compileGithubReleaseKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/update/UpdateDownloader.kt
git commit -m "feat(update): stream the APK to filesDir and verify it"
```

---

### Task 9: The install

**Files:**
- Create: `app/src/main/java/com/jellemax/detour/update/UpdateInstaller.kt`

- [ ] **Step 1: Write the implementation**

```kotlin
package com.jellemax.detour.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * Hands a downloaded APK to the system installer.
 *
 * PackageInstaller rather than ACTION_VIEW: it reports the outcome back through
 * an IntentSender, so "the rider dismissed the sheet"
 * (STATUS_FAILURE_ABORTED) is distinguishable from "it failed". ACTION_VIEW
 * returns nothing and leaves the app guessing.
 */
object UpdateInstaller {

    const val ACTION_INSTALL_RESULT = "com.jellemax.detour.INSTALL_RESULT"

    /**
     * Whether the rider has granted this app the per-app "Install unknown apps"
     * permission. REQUEST_INSTALL_PACKAGES in the manifest only makes the app
     * eligible to ask; this is the consent.
     */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Sends the rider to the one settings screen that grants it, rather than
     *  letting the install fail and leaving them to find it. */
    fun requestPermission(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Opens the install sheet for [apk]. Returns false if the session could not
     * be created at all; the sheet's own outcome arrives at
     * [ACTION_INSTALL_RESULT].
     */
    fun install(context: Context, apk: File): Boolean = try {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("detour", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val intent = Intent(ACTION_INSTALL_RESULT).setPackage(context.packageName)
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
        true
    } catch (e: Exception) {
        Log.w("DetourUpdate", "install session failed", e)
        false
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `devcontainer-exec ./gradlew :app:compileGithubReleaseKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/update/UpdateInstaller.kt
git commit -m "feat(update): open the install sheet through PackageInstaller"
```

---

### Task 9b: Receive the install outcome

**Files:**
- Create: `app/src/main/java/com/jellemax/detour/update/InstallResultReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`

This is not optional plumbing. `PackageInstaller.commit` does not show the install sheet
directly — it first sends back `STATUS_PENDING_USER_ACTION` carrying the Intent that *is* the
sheet. Without a receiver launching that Intent, `commit` appears to succeed and nothing ever
appears on screen.

- [ ] **Step 1: Write the receiver**

```kotlin
package com.jellemax.detour.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * The other half of [UpdateInstaller]. PackageInstaller reports through an
 * IntentSender, and the first thing it reports is usually
 * STATUS_PENDING_USER_ACTION — an Intent that has to be launched to show the
 * install sheet at all. Committing without handling that looks like a silent
 * no-op.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirm?.let { context.startActivity(it) }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                // The process is about to be replaced by the new build, so
                // there is nothing to update in the UI. Clearing the status
                // keeps a stale banner off the screen if it is not.
                UpdateState.set(UpdateStatus.None)
                UpdateDownloader.prune(context, keep = null)
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // The rider dismissed the sheet. Keep the file and the
                // Downloaded state, so saying yes later costs a tap rather
                // than another 46 MB. This is the distinction ACTION_VIEW
                // cannot make, and the reason for using PackageInstaller.
                Log.d("DetourUpdate", "install dismissed by user")
            }

            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w("DetourUpdate", "install failed: status=$status msg=$msg")
                UpdateState.current()?.let { UpdateState.set(UpdateStatus.Failed(it)) }
            }
        }
    }
}
```

- [ ] **Step 2: Register it**

In `app/src/main/AndroidManifest.xml`, alongside the existing `<receiver>` at line 142:

```xml
        <receiver
            android:name=".update.InstallResultReceiver"
            android:exported="false">
            <intent-filter>
                <action android:name="com.jellemax.detour.INSTALL_RESULT" />
            </intent-filter>
        </receiver>
```

`exported="false"` because `UpdateInstaller` sets the package on the Intent — only this app
can deliver it, and an exported receiver here would let any app drive the install state.

- [ ] **Step 3: Verify it compiles**

Run: `devcontainer-exec ./gradlew :app:assembleGithubRelease`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/update/InstallResultReceiver.kt \
        app/src/main/AndroidManifest.xml
git commit -m "feat(update): launch the install sheet and record its outcome"
```

---

### Task 10: The notification

**Files:**
- Create: `app/src/main/java/com/jellemax/detour/update/UpdateNotification.kt`

Read `app/src/main/java/com/jellemax/detour/notif/TripEndedNotification.kt` first — it is the pattern this follows, including the `getNotificationChannel(...) == null` guard.

- [ ] **Step 1: Write the implementation**

```kotlin
package com.jellemax.detour.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jellemax.detour.MainActivity
import com.jellemax.detour.data.Settings

/**
 * One notification per available version, never repeated.
 *
 * The check runs in the foreground, so this always posts while the rider is
 * already in the app — it is a breadcrumb for after they leave, not an
 * announcement. The Hub banner is what tells them now.
 */
object UpdateNotification {

    private const val CHANNEL_ID = "updates"
    private const val NOTIFICATION_ID = 4201

    fun notifyOnce(context: Context, version: String) {
        if (Settings.notifiedUpdateVersion() == version) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Requested elsewhere for trips and circles; if it was refused, the
            // banner is the whole prompt. Stamp anyway so a refused permission
            // does not re-attempt hourly.
            Settings.setNotifiedUpdateVersion(version)
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Updates", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("Detour $version is available")
                .setContentText("Open Detour to install it.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
        Settings.setNotifiedUpdateVersion(version)
    }
}
```

`android.R.drawable.ic_popup_sync` is a framework drawable, so it needs no resource of our own — `TripEndedNotification.kt:56` uses `android.R.drawable.ic_menu_mylocation` the same way.

- [ ] **Step 2: Verify it compiles**

Run: `devcontainer-exec ./gradlew :app:compileGithubReleaseKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/update/UpdateNotification.kt
git commit -m "feat(update): notify once per available version"
```

---

### Task 11: The Hub banner

**Files:**
- Create: `app/src/main/java/com/jellemax/detour/ui/UpdateBanner.kt`
- Modify: `app/src/main/java/com/jellemax/detour/ui/HubScreen.kt`

- [ ] **Step 1: Write the banner**

```kotlin
package com.jellemax.detour.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jellemax.detour.update.UpdateStatus

/**
 * The standing "you are out of date" state, not an announcement — so no dismiss
 * button. It goes away when the update is installed, or when a newer one
 * replaces it.
 */
@Composable
fun UpdateBanner(
    status: UpdateStatus,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (status is UpdateStatus.None) return
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (status) {
                is UpdateStatus.Available -> Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Detour ${status.update.version} is available",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onDownload) { Text("Download") }
                }

                is UpdateStatus.Downloading -> {
                    Text(
                        "Downloading ${status.update.version}…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // -1f means the server sent no length; an indeterminate bar
                    // is honest where a fake percentage is not.
                    if (status.fraction >= 0f) {
                        LinearProgressIndicator(
                            progress = { status.fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }

                is UpdateStatus.Downloaded -> Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Detour ${status.update.version} is ready",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onInstall) { Text("Install") }
                }

                is UpdateStatus.Failed -> Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Download of ${status.update.version} failed",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onDownload) { Text("Retry") }
                }

                UpdateStatus.None -> Unit
            }
        }
    }
}
```

- [ ] **Step 2: Render it in the Hub**

In `HubScreen.kt`, add these imports:

```kotlin
import com.jellemax.detour.update.UpdateDownloader
import com.jellemax.detour.update.UpdateInstaller
import com.jellemax.detour.update.UpdateState
import com.jellemax.detour.update.UpdateStatus
```

Inside the composable, next to the other `collectAsStateWithLifecycle` calls:

```kotlin
    val updateStatus by UpdateState.status.collectAsStateWithLifecycle()
```

Then in the `Column`, immediately before `AccountCard(`:

```kotlin
            UpdateBanner(
                status = updateStatus,
                onDownload = {
                    val update = UpdateState.current() ?: return@UpdateBanner
                    UpdateState.set(UpdateStatus.Downloading(update, -1f))
                    scope.launch(Dispatchers.IO) {
                        val file = UpdateDownloader.download(context, update) { f ->
                            UpdateState.set(UpdateStatus.Downloading(update, f))
                        }
                        UpdateState.set(
                            if (file != null) UpdateStatus.Downloaded(update, file.path)
                            else UpdateStatus.Failed(update)
                        )
                    }
                },
                onInstall = {
                    val s = updateStatus as? UpdateStatus.Downloaded ?: return@UpdateBanner
                    if (!UpdateInstaller.canInstall(context)) {
                        UpdateInstaller.requestPermission(context)
                    } else {
                        UpdateInstaller.install(context, java.io.File(s.path))
                    }
                },
            )
```

Verified on 2026-09-01: `HubScreen` already has `val context = LocalContext.current` (line 82)
and already imports `kotlinx.coroutines.Dispatchers` and `kotlinx.coroutines.withContext`. It
does **not** have a `CoroutineScope`. So add exactly:

```kotlin
    val scope = rememberCoroutineScope()
```

beside the other `remember` calls, plus these two imports:

```kotlin
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
```

- [ ] **Step 3: Verify it compiles**

Run: `devcontainer-exec ./gradlew :app:compileGithubReleaseKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/UpdateBanner.kt \
        app/src/main/java/com/jellemax/detour/ui/HubScreen.kt
git commit -m "feat(update): show a standing update banner in the Hub"
```

---

### Task 12: Wire the check to onStart

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/MainActivity.kt`

- [ ] **Step 1: Add the check**

Add to `MainActivity`:

```kotlin
    /**
     * onStart, not onResume. Returning from the install sheet, the unknown-
     * sources settings screen or a browser all fire onResume, and re-entering
     * the check on the way back from the thing the check just started is how a
     * state machine chases its own tail. The hourly throttle would mask it.
     */
    override fun onStart() {
        super.onStart()
        checkForUpdate()
    }

    private fun checkForUpdate() {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - Settings.lastUpdateCheckMs() < 60 * 60 * 1000L) return
        // Stamped before the request: a device with no connectivity would
        // otherwise retry on every foreground.
        Settings.setLastUpdateCheckMs(now)
        lifecycleScope.launch(Dispatchers.IO) {
            val update = runCatching {
                UpdateClient.newerThan(repo, BuildConfig.VERSION_NAME)
            }.getOrNull()
            // Silent on failure. This is a background courtesy; a rider mid-ride
            // is never told the update check could not reach GitHub.
            if (update == null) {
                UpdateDownloader.prune(this@MainActivity, keep = null)
                return@launch
            }
            UpdateDownloader.prune(this@MainActivity, keep = update.asset)
            if (UpdateState.current()?.version != update.version) {
                UpdateState.set(UpdateStatus.Available(update))
            }
            UpdateNotification.notifyOnce(this@MainActivity, update.version)
        }
    }
```

Verified on 2026-09-01: `MainActivity` is `class MainActivity : ComponentActivity()` and
already imports `androidx.lifecycle.lifecycleScope` (:22), `com.jellemax.detour.data.Settings`
(:28), `kotlinx.coroutines.Dispatchers` (:52), `kotlinx.coroutines.launch` (:53). It overrides
`onCreate` and `onNewIntent` but **not** `onStart`, so the new override is genuinely new.

Add only the imports it does not already have:

```kotlin
import com.jellemax.detour.data.UpdateClient
import com.jellemax.detour.update.UpdateDownloader
import com.jellemax.detour.update.UpdateNotification
import com.jellemax.detour.update.UpdateState
import com.jellemax.detour.update.UpdateStatus
```

`BuildConfig` is in this file's own package, so it needs no import.

The `UpdateState.current()?.version != update.version` guard is what stops a
re-check from throwing away an in-flight download of the same version.

- [ ] **Step 2: Verify it compiles**

Run: `devcontainer-exec ./gradlew :app:compileGithubReleaseKotlin :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. The debug variant must still compile — `UPDATE_REPO` exists in every variant, only the permission does not.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/MainActivity.kt
git commit -m "feat(update): check hourly from onStart, silently"
```

---

### Task 13: CI publishes the variant and the manifest

**Files:**
- Modify: `.github/workflows/build.yml`

- [ ] **Step 1: Build the new variant**

At line 139, change the assemble line so the phone APK comes from the new variant while the Play bundle stays on `release`:

```yaml
          ./gradlew :app:assembleGithubRelease :app:bundleRelease \
                    :wear:assembleRelease :wear:bundleRelease
```

- [ ] **Step 2: Pass the repository in**

In the same step's `env:` block, add:

```yaml
          UPDATE_REPO: ${{ github.repository }}
```

- [ ] **Step 3: Rename from the new output path**

At lines 155-160, the phone APK now lands under `githubRelease/`:

```yaml
      - name: Rename APKs
        run: |
          cp app/build/outputs/apk/githubRelease/app-githubRelease*.apk \
             detour-${{ steps.version.outputs.name }}.apk
          cp wear/build/outputs/apk/release/wear-release*.apk \
             detour-wear-${{ steps.version.outputs.name }}.apk
```

- [ ] **Step 4: Generate the manifest**

Add a step immediately before `Publish release`:

```yaml
      # The app reads this to learn which asset belongs to its platform. Not a
      # glob on the release's asset list: detour-wear-<ver>.apk also starts with
      # detour-, so a prefix match hands a phone the watch build.
      - name: Generate update manifest
        if: github.event_name == 'push'
        run: |
          phone="detour-${{ steps.version.outputs.name }}.apk"
          wear="detour-wear-${{ steps.version.outputs.name }}.apk"
          cat > update.json <<EOF
          {
            "version": "${{ steps.version.outputs.name }}",
            "artifacts": {
              "android-phone": {
                "asset": "$phone",
                "size": $(stat -c%s "$phone"),
                "sha256": "$(sha256sum "$phone" | cut -d' ' -f1)"
              },
              "android-wear": {
                "asset": "$wear",
                "size": $(stat -c%s "$wear"),
                "sha256": "$(sha256sum "$wear" | cut -d' ' -f1)"
              }
            }
          }
          EOF
          cat update.json
```

- [ ] **Step 5: Publish it**

In the `Publish release` step's `files:` list, add `update.json` as a fourth line:

```yaml
          files: |
            detour-${{ steps.version.outputs.name }}.apk
            detour-wear-${{ steps.version.outputs.name }}.apk
            app/build/outputs/mapping/release/mapping.txt
            update.json
```

- [ ] **Step 6: Check the mapping path still resolves**

The `mapping.txt` line still points at `app/build/outputs/mapping/release/`. The phone APK now comes from `githubRelease`, so its mapping is at `app/build/outputs/mapping/githubRelease/`. Change that line to match, or the published mapping will not de-obfuscate the published APK:

```yaml
            app/build/outputs/mapping/githubRelease/mapping.txt
```

- [ ] **Step 7: Commit**

```bash
git add .github/workflows/build.yml
git commit -m "ci: publish the githubRelease APK and an update manifest"
```

---

### Task 14: Version bump

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Bump**

New feature, backward compatible, so a minor bump per `CONTRIBUTING.md`'s table: `1.87.0` → `1.88.0`.

- [ ] **Step 2: Full verification**

```bash
devcontainer-exec ./gradlew :shared:testDebugUnitTest :app:testDebugUnitTest \
    :shared:compileCommonMainKotlinMetadata :app:assembleGithubRelease :app:bundleRelease
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "chore: 1.88.0"
```

---

## Manual verification

Nothing above proves the download or the install works — no Robolectric, no `compose-ui-test`, no `androidTest`. There is a real end-to-end test available, and it should be run before the PR is marked ready:

1. Build the variant locally with the repo baked in:
   `UPDATE_REPO=maxke24/Detour devcontainer-exec ./gradlew :app:assembleGithubRelease`
2. Edit `versionName` to `1.86.0`, rebuild, install that APK.
3. Open the app. Within a second the Hub should show "Detour 1.87.0 is available" — the real published release.
4. Tap Download, watch the progress bar, then Install.
5. The install will be refused with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, because the local build is signed with the developer's debug key and the release is signed with the CI keystore. **That is the expected outcome and confirms the whole path up to the installer.** Only a CI-built APK can complete the final step.
6. Restore `versionName` before committing.

Also confirm the permission split on the built artifacts:

```bash
devcontainer-exec ./gradlew :app:assembleGithubRelease :app:bundleRelease
for v in githubRelease release; do
  f=$(find app/build/intermediates/merged_manifest/$v -name AndroidManifest.xml 2>/dev/null | head -1)
  [ -n "$f" ] || { echo "$v: MANIFEST NOT FOUND"; continue; }
  echo "$v: $(grep -c REQUEST_INSTALL_PACKAGES "$f")"
done   # expect githubRelease: 1, release: 0
```

package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    // --- release parsing --------------------------------------------------

    /** Trimmed from the real response for maxke24/Detour v1.87.0, captured
     *  2026-09-01, asset order as published. Releases from then still carry a
     *  watch APK — Wear OS support was dropped in #57, long after v1.87.0 —
     *  and an installed app still has to parse them, so the fixture keeps it
     *  rather than describing a release GitHub does not have. */
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
     * Why selection is keyed by platform rather than by filename:
     * `detour-wear-1.87.0.apk` shares the `detour-` prefix with the phone
     * build, so any name-based match — the conventional-asset fallback below,
     * or the glob this manifest replaces — could hand a phone the wrong APK.
     * `artifactFor` looks up unrelated map keys instead.
     *
     * CI stopped emitting `android-wear` when Wear OS support was dropped
     * (#57), but the second entry stays in this fixture: it is what makes the
     * assertion mean anything, and a manifest carrying a sibling platform is
     * exactly what the next surface would produce.
     */
    @Test
    fun thePhoneArtifactIsNeverASiblingPlatformsBuild() {
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
}

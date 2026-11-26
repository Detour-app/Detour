package com.jellemax.detour.data

import kotlinx.serialization.json.JsonObject

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

    /** Null for anything that is not dot-separated integers. */
    private fun parseVersion(v: String): List<Int>? {
        if (v.isBlank()) return null
        val parts = v.split(".")
        return parts.map { it.toIntOrNull() ?: return null }
    }

    /** The platform key this build asks the manifest for. */
    const val PLATFORM_ANDROID_PHONE = "android-phone"

    data class Release(
        val version: String,
        val prerelease: Boolean,
        /** Asset name to its `browser_download_url`. */
        val assets: Map<String, String>,
        /** The release body GitHub already hands back with everything else in
         *  this response — null rather than blank, so a caller can `?:` past
         *  it with one check instead of two (#295).
         *
         *  Markdown, as GitHub's generator writes it. Carried verbatim: this
         *  type does no filtering, and the Android surface renders it with a
         *  Markdown renderer rather than showing the source (#357). What has
         *  not changed since #295 is that it is never treated as trusted
         *  **HTML** — no WebView, and no markup path at all. Links are live,
         *  because the body comes from the repo that built this APK
         *  (`BuildConfig.UPDATE_REPO`, baked from `github.repository`), which
         *  is already trusted to hand the app a binary it installs. See
         *  CONTRIBUTING.md's "Release notes" section. */
        val notes: String? = null,
        /** The release's display name, which for the rolling `dev` pre-release
         *  is the only place the build's commit is written (#1064). */
        val name: String = "",
        /** Asset name to the size and digest GitHub reports for it. The
         *  digest is blank for an asset uploaded before GitHub started
         *  computing one. Only the dev channel reads this: a tagged release
         *  carries `update.json`, which predates GitHub's digests. */
        val assetDetails: Map<String, Artifact> = emptyMap(),
    ) {
        fun assetUrl(name: String): String? = assets[name]
    }

    data class Artifact(val asset: String, val size: Long, val sha256: String)

    data class UpdateManifest(val version: String, val artifacts: Map<String, Artifact>)

    /** Null for anything unreadable — same contract as `TripStore.load`'s catch
     *  and `RelayProtocol.decode`: a bad payload is "no update", never a throw
     *  reaching a screen. */
    fun parseRelease(text: String): Release? = try {
        releaseFrom(jsonObjectOf(text))
    } catch (e: Exception) {
        null
    }

    /**
     * The `/releases` list response, which is an array of exactly the objects
     * [parseRelease] reads one of — same fields, bodies included, so a whole
     * range of releases costs one request rather than one per version (#358).
     *
     * Empty for anything unreadable, and an individually-unreadable entry is
     * dropped rather than failing the list: same contract as [parseRelease],
     * and the same shape `Routes.load` already uses for a stored array.
     */
    fun parseReleaseList(text: String): List<Release> = try {
        jsonArrayOf(text).objects().mapNotNull { runCatching { releaseFrom(it) }.getOrNull() }
    } catch (e: Exception) {
        emptyList()
    }

    /** One release object, shared by [parseRelease] and [parseReleaseList] so
     *  the two cannot drift about what a release is. */
    private fun releaseFrom(o: JsonObject): Release {
        val assetObjects = o.optArray("assets")?.objects() ?: emptyList()
        val assets = assetObjects.associate {
            it.optString("name") to it.optString("browser_download_url")
        }
        val details = assetObjects.associate {
            val name = it.optString("name")
            name to Artifact(
                asset = name,
                size = it.optLong("size", 0L),
                sha256 = it.optString("digest").takeIf { d -> d.startsWith(SHA256_PREFIX) }
                    ?.removePrefix(SHA256_PREFIX).orEmpty(),
            )
        }
        return Release(
            version = o.optString("tag_name").removePrefix("v"),
            prerelease = o.optBoolean("prerelease", false),
            assets = assets,
            notes = o.optString("body").trim().ifBlank { null },
            name = o.optString("name"),
            assetDetails = details,
        )
    }

    /** GitHub's asset `digest` is `<algorithm>:<hex>`; only SHA-256 is one the
     *  downloader can check. */
    private const val SHA256_PREFIX = "sha256:"

    /** The tag `dev.yml` moves to every push to `main`. */
    const val DEV_TAG = "dev"

    /** The one asset `dev.yml` attaches to the dev pre-release. */
    const val DEV_ASSET = "detour-dev.apk"

    /** A dev build as its release names it: `Dev build (<versionName>, <sha>)`. */
    data class DevBuild(val versionName: String, val sha: String) {
        /** What the rider is shown, and the key the notification, the partial
         *  download's sidecar and the row all use: versionName alone often
         *  stays the same across pushes, the commit never does. */
        val label: String get() = "$versionName ($sha)"
    }

    private val DEV_NAME = Regex("""^Dev build \((.+), ([0-9a-fA-F]{7,40})\)$""")

    /** Null for a name `dev.yml` did not write — a hand-edited release, or a
     *  workflow that changed its format. No guess: an unreadable name is no
     *  update, as with [isNewer]. */
    fun parseDevBuild(releaseName: String): DevBuild? {
        val m = DEV_NAME.matchEntire(releaseName.trim()) ?: return null
        return DevBuild(m.groupValues[1].trim(), m.groupValues[2].lowercase())
    }

    /**
     * Whether the dev release at [releaseSha] is a different commit from the
     * [installedSha] this build was made from. The release carries the short
     * sha and the build the full one, so they are compared on the shorter's
     * length. Blank on either side is false: without both commits there is
     * nothing to compare, and offering an update on a guess could put a rider
     * in a loop of reinstalling the build they already have.
     */
    fun isOtherBuild(installedSha: String, releaseSha: String): Boolean {
        if (installedSha.isBlank() || releaseSha.isBlank()) return false
        val n = minOf(installedSha.length, releaseSha.length)
        return !installedSha.take(n).equals(releaseSha.take(n), ignoreCase = true)
    }

    fun parseManifest(text: String): UpdateManifest? = try {
        val o = jsonObjectOf(text)
        val artifacts = o.optObject("artifacts") ?: return null
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
}

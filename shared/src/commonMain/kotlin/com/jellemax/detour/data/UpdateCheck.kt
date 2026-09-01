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

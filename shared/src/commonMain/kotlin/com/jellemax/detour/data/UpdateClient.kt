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

    data class Available(
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
     * Null is also the answer for every failure — offline, rate-limited, a
     * malformed payload. This is a background courtesy; it does not report
     * problems to a rider who did not ask.
     */
    @Throws(Exception::class)
    suspend fun newerThan(repo: String, installedVersion: String): Available? {
        if (repo.isBlank()) return null
        val releaseText = Http.get("https://api.github.com/repos/$repo/releases/latest", HEADERS)
        val release = UpdateCheck.parseRelease(releaseText) ?: return null
        if (!UpdateCheck.isNewer(installedVersion, release.version)) return null

        // The manifest is the authority on which file this platform wants. A
        // release published before it existed falls back to the name CI has
        // always used; see UpdateCheck.conventionalPhoneAsset.
        val manifestUrl = release.assetUrl("update.json")
        val artifact = manifestUrl
            ?.let { runCatching { Http.get(it, HEADERS) }.getOrNull() }
            ?.let { UpdateCheck.parseManifest(it) }
            ?.let { UpdateCheck.artifactFor(it, UpdateCheck.PLATFORM_ANDROID_PHONE) }

        val assetName = artifact?.asset ?: UpdateCheck.conventionalPhoneAsset(release.version)
        val url = release.assetUrl(assetName) ?: return null
        return Available(
            version = release.version,
            asset = assetName,
            downloadUrl = url,
            size = artifact?.size ?: 0L,
            sha256 = artifact?.sha256 ?: "",
        )
    }
}

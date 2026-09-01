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
    suspend fun newerThan(repo: String, installedVersion: String): PendingUpdate? {
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

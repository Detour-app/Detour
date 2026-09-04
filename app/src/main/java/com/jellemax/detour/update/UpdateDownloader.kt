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
     *
     * Streams to a `.part` name and renames to [update]'s final asset name
     * only after [verify] passes. `prune` deletes by name and can run
     * concurrently from a background check; without this, a check running
     * mid-stream can unlink the file the downloader still has open (silent on
     * Linux) while the digest it's accumulating is unaffected — verify()
     * passes, and the caller publishes `Downloaded` for a path that's already
     * gone (#166). A `.part` name is never a `prune` keep-name, so prune can
     * still delete it mid-stream, but then the rename below fails cleanly
     * instead of a ghost success reaching the rider.
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
        val part = File(dir(context), "${update.asset}.part")
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
                part.outputStream().use { output ->
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
            if (!verify(part, digest, update, read)) {
                part.delete()
                return null
            }
            if (!part.renameTo(target)) {
                // part is gone (pruned mid-stream) or target already exists
                // from a concurrent download of the same asset. Either way,
                // there is nothing installable at a name the rider was
                // promised — fail rather than publish a path that doesn't
                // resolve to the verified bytes.
                Log.w("DetourUpdate", "rename to final name failed")
                part.delete()
                return null
            }
            target
        } catch (e: Exception) {
            Log.w("DetourUpdate", "download failed", e)
            part.delete()
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

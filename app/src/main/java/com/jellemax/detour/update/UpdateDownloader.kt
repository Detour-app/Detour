package com.jellemax.detour.update

import android.content.Context
import android.util.Log
import com.jellemax.detour.data.UpdateClient
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
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

    /** One page-aligned chunk; the same buffer size the file is re-hashed with
     *  on a resume, so neither loop is the slow one. */
    private const val BUFFER = 64 * 1024

    private const val TIMEOUT_MS = 30_000

    /** `HttpURLConnection` names every other code this file cares about but
     *  not 206, which is the one a resume turns on. */
    private const val HTTP_PARTIAL = 206

    /** Swapped by tests: the mockable android.jar throws from every
     *  `android.util.Log` method, and these refuse-and-retry paths all log. A
     *  seam here keeps that guard rail on for the rest of the module, which has
     *  nine test files documenting a deliberate no-Android-APIs rule — a
     *  module-wide `unitTests.isReturnDefaultValues` would turn every one of
     *  their stubs from a loud throw into a plausible `null`. */
    internal var log: (String, Throwable?) -> Unit = { message, error ->
        Log.w("DetourUpdate", message, error)
    }

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
     * Deletes every file in `updates/` except [keep], its in-progress `.part`
     * and that partial's `.meta` sidecar.
     *
     * Called on each check, so a superseded 46 MB APK cannot sit there forever
     * — but the partial the rider is resuming is not superseded, and deleting
     * it here would mean every background check silently restarted a download
     * from zero.
     */
    fun prune(context: Context, keep: String?) = prune(dir(context), keep)

    internal fun prune(dir: File, keep: String?) {
        val kept = if (keep == null) emptySet() else setOf(keep, "$keep.part", "$keep.part.meta")
        dir.listFiles()?.forEach {
            if (it.name !in kept) it.delete()
        }
    }

    /**
     * Why one attempt stopped.
     *
     * [Interrupted] is the only retryable one, and it says so about the disk as
     * much as about the transfer: the [bytes] it names are either good and
     * resumable, or zero because the attempt cleared a partial it could not
     * vouch for. Either way the next attempt makes progress rather than
     * repeating this one. [Refused] is the server's or the manifest's
     * considered no — a status code, a bad name, a digest that did not match —
     * and retrying it only spends the rider's battery.
     */
    sealed interface Outcome {
        data class Done(val file: File) : Outcome
        data class Interrupted(val bytes: Long) : Outcome
        data object Refused : Outcome
    }

    /**
     * What this response lets the attempt do with the partial already on disk.
     *
     * Three answers, not two: declining to resume is not the same as being able
     * to start over, because a body measured from an offset cannot be written
     * to a file that no longer has one.
     */
    internal sealed interface Resume {
        /** The body continues the partial exactly. Append from [from]. */
        data class Append(val from: Long) : Resume

        /** Discard the partial; this body is the whole artefact from zero. */
        data object Restart : Resume

        /** Discard the partial and write nothing: this body is a fragment
         *  measured from an offset that is about to stop existing. */
        data object Fragment : Resume
    }

    /**
     * One download attempt for [update], resuming from a partial when one is on
     * disk and provably the same artefact.
     *
     * [onProgress] receives 0f..1f, or -1f when the total length is unknown.
     * Blocking: call from `Dispatchers.IO`. Retries and backoff belong to the
     * caller — the download service — because they are policy, and one call
     * here is one attempt.
     */
    fun download(
        context: Context,
        update: UpdateClient.PendingUpdate,
        onProgress: (Float) -> Unit,
    ): Outcome = attempt(dir(context), update, onProgress = onProgress)

    /**
     * The attempt itself, against a plain directory so it can be tested.
     *
     * **Resume is fail-closed**, and [resumeFrom] is where that is decided —
     * read its contract before changing anything here.
     *
     * **The digest still covers the whole file.** [MessageDigest] state cannot
     * be persisted, so a resume re-hashes what is already on disk before
     * appending — see [transfer]. That is the property [verify] depends on, and
     * the one a careless resume breaks first: hashing only the appended tail
     * would let a corrupt prefix through the SHA-256 check that is the last
     * thing standing between a manifest and an install (CWE-494).
     *
     * **Against `prune` racing this.** `prune` deletes by name and a background
     * check can run mid-stream; unlinking a file this still has open succeeds
     * silently on Linux, so the stream and the digest would finish happily on
     * bytes nobody can open (#166). Two things stop that now:
     * `UpdateChecker.performCheck` returns early without pruning while
     * `UpdateState.status` is `Downloading`, and `prune` keeps `<keep>.part`
     * and `<keep>.part.meta` alongside `<keep>` so a partial for the current
     * asset is never the thing swept up. If both are somehow bypassed the
     * rename in [publish] fails rather than a ghost success reaching the rider.
     *
     * **Every exception is retryable**, including ones that will never come
     * good. That is deliberate: the partial is only ever appended to after
     * [resumeFrom] proves it matches, so a wrong guess here cannot corrupt
     * anything — it can only cost a bounded number of backed-off retries the
     * caller was going to allow anyway. Sorting IOException from the rest would
     * trade that for a taxonomy that gets a genuinely transient failure wrong
     * sooner or later, and a rider stuck on "Failed" with a good half-file on
     * disk is the worse outcome. The partial stays: its sidecar says what it
     * is, and the next attempt either resumes it or discards it on the
     * evidence.
     *
     * [allowHost] is the redirect gate and defaults to the production
     * allowlist. It is a parameter only so the tests can serve from 127.0.0.1;
     * no production call site passes it.
     */
    internal fun attempt(
        dir: File,
        update: UpdateClient.PendingUpdate,
        allowHost: (URL) -> Boolean = ::allowed,
        onProgress: (Float) -> Unit,
    ): Outcome {
        val paths = UpdatePartFiles.resolve(dir, update.asset) ?: run {
            log("refusing asset name from manifest", null)
            return Outcome.Refused
        }
        val url = runCatching { URL(update.downloadUrl) }.getOrNull() ?: return Outcome.Refused
        if (!allowHost(url)) {
            log("refusing download from ${url.host}", null)
            return Outcome.Refused
        }

        val have = if (paths.part.exists()) paths.part.length() else 0L
        var connection: HttpURLConnection? = null
        return try {
            connection = open(url, have)
            // responseCode first, deliberately. getURL() does not report the
            // redirect target until the response headers have arrived —
            // Android's libcore says so outright — so checking it before any
            // I/O just re-tests the URL allowHost already passed above, and
            // would wave through a redirect to anywhere. Still a gate rather
            // than a postmortem: this runs before a single body byte is read.
            // Re-run on every attempt, because every attempt is its own request
            // and its own redirect chain — GitHub's signed asset URLs are
            // short-lived, so a resume is genuinely re-negotiated, not replayed.
            //
            // libcore also refuses any protocol-switching redirect in either
            // direction, so an https -> http downgrade never reaches here.
            val code = connection.responseCode
            if (!allowHost(connection.url)) {
                log("refusing redirect to ${connection.url.host}", null)
                return Outcome.Refused
            }
            // Without this a 404 streams its HTML body into the file and the
            // rider is offered an "APK" that is an error page. A manifest-less
            // release has no size or hash to catch that later. Refused rather
            // than Interrupted: a status code is the server's considered
            // answer, and retrying it just spends the rider's battery.
            if (code !in 200..299) {
                log("download refused: HTTP $code", null)
                return Outcome.Refused
            }

            val etag = connection.getHeaderField("ETag").orEmpty()
            val resume = resumeFrom(
                have = have,
                code = code,
                contentRange = connection.getHeaderField("Content-Range"),
                sidecar = paths.meta.takeIf { it.exists() }?.readText(),
                update = update,
                etag = etag,
            )
            // Null means there is nowhere to put this body; the partial has
            // been cleared, so the next attempt sends no Range and is answered
            // with the whole artefact.
            val from = prepare(paths, resume) ?: return Outcome.Interrupted(0L)
            paths.meta.writeText(PartMeta.of(update, etag).encode())

            val digest = MessageDigest.getInstance("SHA-256")
            val read = transfer(connection, paths.part, from, digest, update.size, onProgress)

            if (update.size > 0 && read < update.size) {
                // The stream ended early — a dropped connection, not a corrupt
                // file. Keep the partial and its sidecar so the next attempt
                // picks up here. Only the manifest's size is trusted for this:
                // Content-Length describes the *encoded* body, and
                // HttpURLConnection transparently gunzips a response it asked
                // to be gzipped, so a shortfall measured against it would be an
                // artefact of the encoding rather than of the transfer. A
                // manifest-less release therefore cannot detect truncation
                // here — it never could, and it has no digest either.
                log("download interrupted at $read of ${update.size}", null)
                return Outcome.Interrupted(read)
            }
            publish(paths, digest, update, read)
        } catch (e: Exception) {
            // Retryable, whatever it was, and the partial stays — see the
            // "Every exception is retryable" paragraph above for why.
            log("download failed", e)
            Outcome.Interrupted(if (paths.part.exists()) paths.part.length() else 0L)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Carries out [resume] against the files on disk and returns the offset the
     * body is to be written from, or null when it may not be written at all.
     *
     * Everything but [Resume.Append] clears the partial and its sidecar first:
     * bytes that cannot be shown to belong to this artefact must not survive
     * into one that is about to be installed.
     */
    private fun prepare(paths: UpdatePartFiles.Paths, resume: Resume): Long? {
        if (resume is Resume.Append) return resume.from
        paths.part.delete()
        paths.meta.delete()
        if (resume is Resume.Fragment) {
            log("discarding a partial the server would not match", null)
            return null
        }
        return 0L
    }

    /** The request, phrased as a resume when there are [have] bytes to resume
     *  from. Redirects are followed here and gated in [attempt] afterwards. */
    private fun open(url: URL, have: Long): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }

    /**
     * Whether the [have] bytes on disk may be continued, given the response's
     * [code], its `Content-Range` and the `.part.meta` [sidecar] written beside
     * them.
     *
     * **Fail-closed, and this is the whole of it.** [Resume.Append] is returned
     * only when the sidecar agrees with [update] on all four of version,
     * digest, size and ETag *and* the server answered the `Range` request with
     * a 206 starting exactly where the partial ends. Anything else — a 200, a
     * different start, a `Content-Range` this code cannot read, a missing or
     * stale sidecar — discards the partial, because bytes that cannot be shown
     * to belong to this artefact must not be spliced into one that is about to
     * be installed (CWE-494).
     *
     * Having discarded it, the body still has to go somewhere, and only a body
     * that starts at zero can start the file over. A 206's offset was measured
     * against the partial just thrown away; written from zero it would leave a
     * file made of the wrong half, under a fresh sidecar vouching for it. That
     * is [Resume.Fragment]: write nothing, and let the next attempt — which
     * now has no partial and so sends no `Range` — ask for the whole artefact.
     */
    internal fun resumeFrom(
        have: Long,
        code: Int,
        contentRange: String?,
        sidecar: String?,
        update: UpdateClient.PendingUpdate,
        etag: String,
    ): Resume {
        // Which byte of the artefact this body starts at. A 200 is the whole
        // thing and starts at zero however the request was phrased; a 206
        // starts wherever its Content-Range says, which is -1 when it says
        // nothing this code can read.
        val bodyStart = if (code == HTTP_PARTIAL) contentRangeStart(contentRange) else 0L
        val vouched = PartMeta.decode(sidecar.orEmpty())?.matches(update, etag) == true
        if (have > 0 && bodyStart == have && vouched) return Resume.Append(have)
        return if (bodyStart == 0L) Resume.Restart else Resume.Fragment
    }

    /**
     * Writes the body into [part] — appending when [from] is non-zero — and
     * feeds [digest] the whole artefact, on-disk prefix first. Returns the byte
     * count now in the file.
     *
     * [expected] is the manifest's size, the whole artefact. `Content-Length`
     * is only the remainder on a 206, so the offset goes back on — and [from]
     * is zero on a 200 by construction, since only [Resume.Append] makes it
     * non-zero and that requires a 206. -1 means "no idea", which [onProgress]
     * passes on as indeterminate.
     */
    private fun transfer(
        connection: HttpURLConnection,
        part: File,
        from: Long,
        digest: MessageDigest,
        expected: Long,
        onProgress: (Float) -> Unit,
    ): Long {
        if (from > 0) {
            part.inputStream().use { old -> readChunks(old) { buf, n -> digest.update(buf, 0, n) } }
        }
        val declared = connection.contentLengthLong
        val total = when {
            expected > 0 -> expected
            declared > 0 -> declared + from
            else -> -1L
        }
        var read = from
        connection.inputStream.use { input ->
            FileOutputStream(part, from > 0).use { output ->
                readChunks(input) { buf, n ->
                    output.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    read += n
                    onProgress(if (total > 0) read.toFloat() / total else -1f)
                }
            }
        }
        return read
    }

    /** Reads [input] to its end in [BUFFER]-sized chunks, handing each to
     *  [onChunk] as the buffer and the count of bytes in it. The buffer is
     *  reused between chunks, so [onChunk] must consume it before returning.
     *  Not `copyTo`: both callers need the bytes as they pass, one to hash them
     *  and one to hash, count and report them. */
    private inline fun readChunks(input: InputStream, onChunk: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            onChunk(buf, n)
        }
    }

    /** Verifies the finished partial and gives it the name the rider was
     *  promised. Nothing installable exists until the rename succeeds. */
    private fun publish(
        paths: UpdatePartFiles.Paths,
        digest: MessageDigest,
        update: UpdateClient.PendingUpdate,
        read: Long,
    ): Outcome {
        if (!verify(paths.part, digest, update, read)) {
            paths.part.delete()
            paths.meta.delete()
            return Outcome.Refused
        }
        if (!paths.part.renameTo(paths.target)) {
            // part is gone (pruned mid-stream) or target already exists from a
            // concurrent download of the same asset. Either way, there is
            // nothing installable at a name the rider was promised — fail
            // rather than publish a path that doesn't resolve to the verified
            // bytes.
            log("rename to final name failed", null)
            paths.part.delete()
            paths.meta.delete()
            return Outcome.Refused
        }
        paths.meta.delete()
        return Outcome.Done(paths.target)
    }

    /** The first byte offset in a `Content-Range: bytes <start>-<end>/<total>`
     *  header, or -1 for anything else — an absent header, an
     *  unsatisfied-range form with no start offset, or a range unit this code
     *  never asked for. -1 never equals a partial's length, so every one of
     *  those declines the resume, and — unlike 0 — it is not a legal offset
     *  either, so it cannot be mistaken for a body that starts at the
     *  beginning. `UpdateDownloaderResumeTest` pins both halves of that. */
    internal fun contentRangeStart(header: String?): Long {
        val spec = header?.removePrefix("bytes ")?.substringBefore('-') ?: return -1L
        return spec.trim().toLongOrNull() ?: -1L
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
            log("size mismatch: got $read want ${update.size}", null)
            return false
        }
        if (update.sha256.isNotBlank()) {
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (!hex.equals(update.sha256, ignoreCase = true)) {
                log("sha256 mismatch", null)
                return false
            }
        }
        return true
    }
}

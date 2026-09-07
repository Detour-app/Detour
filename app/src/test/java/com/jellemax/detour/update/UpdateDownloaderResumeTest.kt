package com.jellemax.detour.update

import com.jellemax.detour.data.UpdateClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class UpdateDownloaderResumeTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dir: File

    /** Every refuse-and-retry path here logs, and the mockable android.jar
     *  throws from `android.util.Log`. Swapping the seam keeps that guard rail
     *  on for the rest of the module rather than disabling it build-wide;
     *  restoring it in [tearDown] means test order cannot leak the no-op. */
    private val realLog = UpdateDownloader.log

    private val body = ByteArray(64 * 1024) { (it % 251).toByte() }
    private val bodyHex = MessageDigest.getInstance("SHA-256").digest(body)
        .joinToString("") { "%02x".format(it) }

    @Before fun setUp() {
        UpdateDownloader.log = { _, _ -> }
        server = MockWebServer().also { it.start() }
        dir = tmp.newFolder("updates")
    }

    @After fun tearDown() {
        UpdateDownloader.log = realLog
        server.shutdown()
    }

    private fun update() = UpdateClient.PendingUpdate(
        version = "2.14.0",
        asset = "detour-2.14.0.apk",
        downloadUrl = server.url("/detour-2.14.0.apk").toString(),
        size = body.size.toLong(),
        sha256 = bodyHex,
    )

    /** MockWebServer serves 127.0.0.1, which the production host allowlist
     *  refuses by design. Tests override the gate; nothing else does. */
    private fun attempt(u: UpdateClient.PendingUpdate = update()): UpdateDownloader.Outcome =
        UpdateDownloader.attempt(dir, u, allowHost = { true }, onProgress = {})

    private fun full(etag: String = "\"v1\"") = MockResponse()
        .setResponseCode(200)
        .setHeader("ETag", etag)
        .setBody(Buffer().write(body))

    /** The first half only. `setBody` stamps its own Content-Length, so this is
     *  a well-formed short response rather than a truncated one — the shortfall
     *  is found by comparing what arrived against the manifest's size, which is
     *  exactly how a real dropped transfer that reconnects cleanly presents. */
    private fun firstHalf(etag: String = "\"v1\"") = MockResponse()
        .setResponseCode(200)
        .setHeader("ETag", etag)
        .setBody(Buffer().write(body, 0, body.size / 2))

    @Test fun downloadsAndVerifies() {
        server.enqueue(full())
        val outcome = attempt()
        assertTrue(outcome is UpdateDownloader.Outcome.Done)
        val file = (outcome as UpdateDownloader.Outcome.Done).file
        assertEquals("detour-2.14.0.apk", file.name)
        assertTrue(body.contentEquals(file.readBytes()))
        assertFalse(File(dir, "detour-2.14.0.apk.part").exists())
        assertFalse(File(dir, "detour-2.14.0.apk.part.meta").exists())
    }

    @Test fun resumesFromAPartialAndProducesTheSameBytes() {
        // First attempt is cut off half way.
        server.enqueue(firstHalf())
        assertTrue(attempt() is UpdateDownloader.Outcome.Interrupted)
        val part = File(dir, "detour-2.14.0.apk.part")
        assertEquals((body.size / 2).toLong(), part.length())

        // Second attempt asks for the rest and gets it.
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("ETag", "\"v1\"")
                .setHeader(
                    "Content-Range",
                    "bytes ${body.size / 2}-${body.size - 1}/${body.size}",
                )
                .setBody(Buffer().write(body, body.size / 2, body.size - body.size / 2))
        )
        val outcome = attempt()
        assertTrue(outcome is UpdateDownloader.Outcome.Done)
        assertTrue(body.contentEquals((outcome as UpdateDownloader.Outcome.Done).file.readBytes()))

        server.takeRequest()
        val resumed = server.takeRequest()
        assertEquals("bytes=${body.size / 2}-", resumed.getHeader("Range"))
    }

    @Test fun aServerThatIgnoresRangeRestartsRatherThanAppending() {
        server.enqueue(firstHalf())
        attempt()
        server.enqueue(full())          // 200, whole body, Range ignored

        val outcome = attempt()
        assertTrue(outcome is UpdateDownloader.Outcome.Done)
        assertTrue(body.contentEquals((outcome as UpdateDownloader.Outcome.Done).file.readBytes()))
    }

    @Test fun aChangedEtagDiscardsThePartial() {
        server.enqueue(firstHalf())
        attempt()
        server.enqueue(full(etag = "\"v2\""))

        val outcome = attempt()
        assertTrue(outcome is UpdateDownloader.Outcome.Done)
        assertTrue(body.contentEquals((outcome as UpdateDownloader.Outcome.Done).file.readBytes()))
    }

    /**
     * A 206 whose Content-Range does not begin where the partial ends is a
     * server answering a question nobody asked. The only assertion that
     * separates a correct implementation from one that trusts the status code
     * is the byte comparison: appending this whole body to the half already on
     * disk yields 96 KiB of spliced, overlapping ranges — a file that was
     * "downloaded successfully" and is not the artefact.
     *
     * The length assertion is not redundant with `contentEquals`; it names the
     * failure (too long) rather than reporting only that the bytes differ.
     */
    @Test fun aWrongContentRangeStartRestarts() {
        server.enqueue(firstHalf())
        assertTrue(attempt() is UpdateDownloader.Outcome.Interrupted)
        assertEquals((body.size / 2).toLong(), File(dir, "detour-2.14.0.apk.part").length())

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("ETag", "\"v1\"")
                .setHeader("Content-Range", "bytes 0-${body.size - 1}/${body.size}")
                .setBody(Buffer().write(body))
        )

        val outcome = attempt()
        assertTrue(outcome is UpdateDownloader.Outcome.Done)
        val file = (outcome as UpdateDownloader.Outcome.Done).file
        assertEquals(body.size.toLong(), file.length())
        assertTrue(body.contentEquals(file.readBytes()))
    }

    /**
     * The status code cannot be the only thing separating two artefacts. A
     * release re-cut under the same version answers the `Range` request
     * perfectly — 206, the offset asked for — and every other discard case here
     * arrives as a 200, which decides the question before the sidecar is ever
     * read. Drop the [PartMeta] clause from the resume condition and this is
     * the only test that notices.
     *
     * The body served is the *correct* second half, deliberately: the digest
     * would wave a blind resume straight through, so passing this can only come
     * from checking the evidence rather than from being lucky about the bytes.
     *
     * The three assertions after the discard are the ones that matter. A 206
     * body is a fragment; having decided not to continue the partial, there is
     * nowhere to put it, and writing it from offset zero would leave 32 KiB of
     * the wrong half on disk under a sidecar claiming it is the right one.
     */
    @Test fun aMatchingRangeWithADifferentEtagIsNotResumed() {
        server.enqueue(firstHalf())
        assertTrue(attempt() is UpdateDownloader.Outcome.Interrupted)

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("ETag", "\"v2\"")
                .setHeader(
                    "Content-Range",
                    "bytes ${body.size / 2}-${body.size - 1}/${body.size}",
                )
                .setBody(Buffer().write(body, body.size / 2, body.size - body.size / 2))
        )
        val discarded = attempt()
        assertTrue(discarded is UpdateDownloader.Outcome.Interrupted)
        assertEquals(0L, (discarded as UpdateDownloader.Outcome.Interrupted).bytes)
        assertFalse(File(dir, "detour-2.14.0.apk.part").exists())
        assertFalse(File(dir, "detour-2.14.0.apk.part.meta").exists())

        // Nothing is left to resume from, so the next attempt asks for the
        // whole artefact and gets it.
        server.enqueue(full(etag = "\"v2\""))
        val outcome = attempt()
        assertTrue(outcome is UpdateDownloader.Outcome.Done)
        assertTrue(body.contentEquals((outcome as UpdateDownloader.Outcome.Done).file.readBytes()))

        server.takeRequest()
        server.takeRequest()
        assertNull(server.takeRequest().getHeader("Range"))
    }

    @Test fun aPartialWithNoSidecarIsDiscarded() {
        File(dir, "detour-2.14.0.apk.part").writeBytes(ByteArray(1000) { 7 })
        server.enqueue(full())

        val outcome = attempt()
        assertTrue(outcome is UpdateDownloader.Outcome.Done)
        assertTrue(body.contentEquals((outcome as UpdateDownloader.Outcome.Done).file.readBytes()))
    }

    @Test fun aDigestMismatchFailsAndDeletesTheFile() {
        server.enqueue(full())
        val outcome = attempt(update().copy(sha256 = "0".repeat(64)))
        assertTrue(outcome is UpdateDownloader.Outcome.Refused)
        assertFalse(File(dir, "detour-2.14.0.apk").exists())
        assertFalse(File(dir, "detour-2.14.0.apk.part").exists())
    }

    @Test fun anUnsafeAssetNameIsRefusedBeforeAnyRequest() {
        val outcome = attempt(update().copy(asset = "../escape.apk"))
        assertTrue(outcome is UpdateDownloader.Outcome.Refused)
        assertEquals(0, server.requestCount)
    }

    @Test fun aNon2xxIsRefused() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("<html>no</html>"))
        val outcome = attempt()
        assertTrue(outcome is UpdateDownloader.Outcome.Refused)
        assertFalse(File(dir, "detour-2.14.0.apk").exists())
    }

    /**
     * The allowlist is re-checked *after* the redirect chain, and this is the
     * only test that can tell. Every other test here passes `allowHost = {
     * true }`, so the seam is otherwise only ever used to switch the gate off.
     *
     * The second server answers correctly and with the right bytes — the same
     * body, so the digest would wave it straight through. Nothing but the
     * post-redirect check stands between the manifest's URL and an APK served
     * by a host nobody vetted (CWE-494), which is the shape of the real attack:
     * a redirect off github.com to somewhere else. Delete the re-check in
     * `attempt` and this goes green as `Done`.
     */
    @Test fun aRedirectToADisallowedHostIsRefused() {
        val elsewhere = MockWebServer().also { it.start() }
        try {
            elsewhere.enqueue(full())
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", elsewhere.url("/detour-2.14.0.apk").toString())
            )

            // Both servers are on 127.0.0.1, so the port is what distinguishes
            // them: the manifest's URL passes, the redirect target does not.
            val outcome = UpdateDownloader.attempt(
                dir,
                update(),
                allowHost = { it.port == server.port },
                onProgress = {},
            )

            assertTrue(outcome is UpdateDownloader.Outcome.Refused)
            assertFalse(File(dir, "detour-2.14.0.apk").exists())
            assertFalse(File(dir, "detour-2.14.0.apk.part").exists())
            assertFalse(File(dir, "detour-2.14.0.apk.part.meta").exists())
        } finally {
            elsewhere.shutdown()
        }
    }

    @Test fun contentRangeStartReadsTheFirstOffset() {
        assertEquals(500L, UpdateDownloader.contentRangeStart("bytes 500-999/1000"))
    }

    /**
     * -1 is not a nicety, it is the sentinel the resume decision turns on: it
     * can never equal a partial's length, and — unlike 0 — it is not a legal
     * offset either, so a header this code cannot read is never mistaken for a
     * body that starts at the beginning. Make either return 0 and the parser
     * fails open; [anUnreadableContentRangeOnAManifestLessReleaseWritesNothing]
     * is what that costs.
     */
    @Test fun anUnreadableContentRangeIsMinusOneRatherThanZero() {
        assertEquals(-1L, UpdateDownloader.contentRangeStart(null))
        assertEquals(-1L, UpdateDownloader.contentRangeStart(""))
        assertEquals(-1L, UpdateDownloader.contentRangeStart("bytes */1000"))
        assertEquals(-1L, UpdateDownloader.contentRangeStart("items 0-9/10"))
        assertEquals(-1L, UpdateDownloader.contentRangeStart("bytes abc-999/1000"))
    }

    /** …and a body that really does start at zero stays distinguishable from
     *  all of those. */
    @Test fun aRangeStartingAtZeroIsZero() {
        assertEquals(0L, UpdateDownloader.contentRangeStart("bytes 0-999/1000"))
    }

    /**
     * What the -1 sentinel is worth end to end, on the release that has no
     * other net: a manifest-less one, where `verify`'s size and digest gates
     * are both vacuous.
     *
     * A 206 whose Content-Range cannot be parsed is a body of unknown
     * provenance. Read as -1 it matches no partial and cannot start the file
     * over either, so nothing is written. Read as 0 — the fail-open mutation —
     * it would be written from offset zero, pass both vacuous gates, be renamed
     * to the installable name and reach the package installer as a fragment.
     */
    @Test fun anUnreadableContentRangeOnAManifestLessReleaseWritesNothing() {
        File(dir, "detour-2.14.0.apk.part").writeBytes(ByteArray(1000) { 7 })
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("ETag", "\"v1\"")
                .setHeader("Content-Range", "bytes ?-?/?")
                .setBody(Buffer().write(body, body.size / 2, body.size - body.size / 2))
        )

        val outcome = attempt(update().copy(sha256 = "", size = 0L))

        assertTrue(outcome is UpdateDownloader.Outcome.Interrupted)
        assertEquals(0L, (outcome as UpdateDownloader.Outcome.Interrupted).bytes)
        assertFalse(File(dir, "detour-2.14.0.apk").exists())
        assertFalse(File(dir, "detour-2.14.0.apk.part").exists())
        assertFalse(File(dir, "detour-2.14.0.apk.part.meta").exists())
    }
}

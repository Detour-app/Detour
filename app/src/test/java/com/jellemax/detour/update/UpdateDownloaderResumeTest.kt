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

    private val body = ByteArray(64 * 1024) { (it % 251).toByte() }
    private val bodyHex = MessageDigest.getInstance("SHA-256").digest(body)
        .joinToString("") { "%02x".format(it) }

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        dir = tmp.newFolder("updates")
    }

    @After fun tearDown() = server.shutdown()

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
}

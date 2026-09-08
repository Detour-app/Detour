package com.jellemax.detour.update

import com.jellemax.detour.data.UpdateClient
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

/**
 * [UpdateDownloader.verifiedExisting] — the reconcile [UpdateChecker.performCheck]
 * runs after a process restart, where [UpdateState] is empty but a verified APK
 * from a previous run is still in `updates/`.
 */
class UpdateVerifiedExistingTest {

    @get:Rule val tmp = TemporaryFolder()

    private val realLog = UpdateDownloader.log

    private val body = ByteArray(64 * 1024) { (it % 251).toByte() }
    private val bodyHex = MessageDigest.getInstance("SHA-256").digest(body)
        .joinToString("") { "%02x".format(it) }

    private lateinit var dir: File

    @Before fun setUp() {
        UpdateDownloader.log = { _, _ -> }
        dir = tmp.newFolder("updates")
    }

    @After fun tearDown() {
        UpdateDownloader.log = realLog
    }

    private fun update() = UpdateClient.PendingUpdate(
        version = "2.14.0",
        asset = "detour-2.14.0.apk",
        downloadUrl = "https://github.com/x/y/releases/download/2.14.0/detour-2.14.0.apk",
        size = body.size.toLong(),
        sha256 = bodyHex,
    )

    @Test fun returnsTheFileWhenItIsOnDiskAndTheDigestMatches() {
        val apk = File(dir, "detour-2.14.0.apk").apply { writeBytes(body) }

        assertEquals(apk, UpdateDownloader.verifiedExisting(dir, update()))
        assertTrue(apk.exists())
    }

    @Test fun returnsNullWhenNoFileIsOnDisk() {
        assertNull(UpdateDownloader.verifiedExisting(dir, update()))
    }

    @Test fun deletesTheFileAndReturnsNullWhenTheDigestDoesNotMatch() {
        val apk = File(dir, "detour-2.14.0.apk").apply { writeBytes(body + 1) }

        assertNull(UpdateDownloader.verifiedExisting(dir, update()))
        assertFalse(apk.exists())
    }

    @Test fun deletesTheFileAndReturnsNullWhenTheSizeDoesNotMatch() {
        File(dir, "detour-2.14.0.apk").writeBytes(body)

        assertNull(UpdateDownloader.verifiedExisting(dir, update().copy(size = body.size + 1L)))
        assertFalse(File(dir, "detour-2.14.0.apk").exists())
    }

    /**
     * A manifest-less release has no digest to re-check, so a bare filename
     * match is all the evidence there is — too little to offer an install
     * across a restart. It re-downloads instead, exactly as before this fix.
     */
    @Test fun returnsNullForAManifestLessReleaseEvenWithAFileOnDisk() {
        val apk = File(dir, "detour-2.14.0.apk").apply { writeBytes(body) }

        assertNull(UpdateDownloader.verifiedExisting(dir, update().copy(sha256 = "", size = 0L)))
        assertTrue("a manifest-less file is left alone, not deleted", apk.exists())
    }

    @Test fun returnsNullForAnUnsafeAssetName() {
        assertNull(UpdateDownloader.verifiedExisting(dir, update().copy(asset = "../escape.apk")))
    }

    /** The in-progress `.part` is not something to offer for install. */
    @Test fun ignoresAPartialWithNoFinalFile() {
        File(dir, "detour-2.14.0.apk.part").writeBytes(body)

        assertNull(UpdateDownloader.verifiedExisting(dir, update()))
    }
}

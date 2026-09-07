package com.jellemax.detour.update

import com.jellemax.detour.data.UpdateClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartMetaTest {

    private val update = UpdateClient.PendingUpdate(
        version = "2.14.0",
        asset = "detour-2.14.0.apk",
        downloadUrl = "https://github.com/o/r/releases/download/v2.14.0/detour-2.14.0.apk",
        size = 40_000_000L,
        sha256 = "abc123",
    )

    @Test fun roundTrips() {
        val meta = PartMeta("2.14.0", "abc123", 40_000_000L, "\"etag-1\"")
        assertEquals(meta, PartMeta.decode(meta.encode()))
    }

    @Test fun decodeRejectsAMalformedRecord() {
        assertNull(PartMeta.decode(""))
        assertNull(PartMeta.decode("2.14.0\nabc123"))
        assertNull(PartMeta.decode("2.14.0\nabc123\nnot-a-number\netag"))
    }

    @Test fun matchesTheSameArtefactAndEtag() {
        val meta = PartMeta.of(update, "\"etag-1\"")
        assertTrue(meta.matches(update, "\"etag-1\""))
    }

    @Test fun rejectsADifferentVersionHashSizeOrEtag() {
        val meta = PartMeta.of(update, "\"etag-1\"")
        assertFalse(meta.matches(update.copy(version = "2.15.0"), "\"etag-1\""))
        assertFalse(meta.matches(update.copy(sha256 = "def456"), "\"etag-1\""))
        assertFalse(meta.matches(update.copy(size = 1L), "\"etag-1\""))
        assertFalse(meta.matches(update, "\"etag-2\""))
    }

    @Test fun aServerThatSendsNoEtagStillMatchesItsOwnRecord() {
        // Both sides blank is agreement, not a mismatch: GitHub's asset host
        // does send one, but a mirror that does not must still be resumable.
        val meta = PartMeta.of(update, "")
        assertTrue(meta.matches(update, ""))
        assertFalse(meta.matches(update, "\"etag-1\""))
    }
}

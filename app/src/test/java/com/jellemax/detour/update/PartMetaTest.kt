package com.jellemax.detour.update

import com.jellemax.detour.data.UpdateClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartMetaTest {

    private fun update() = UpdateClient.PendingUpdate(
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

    /**
     * `encode` joins exactly 4 fields with `\n`, so a newline embedded in any
     * field can only ever push the split count above 4 — never leave it at 4
     * with the fields shifted. This pins that structural property directly:
     * a field with an embedded newline widens the split count, and `decode`
     * must reject rather than silently accept a 5-part record.
     *
     * The newline sits in the *last* field on purpose: a lenient decode such
     * as `split("\n", limit = 4)` or `take(4)` would otherwise absorb or drop
     * the overflow silently and still parse — putting it in `etag` is what
     * makes those regressions observable rather than accidentally still
     * rejected for an unrelated reason (an invalid size field).
     */
    @Test fun decodeRejectsARecordWithMoreThanFourPartsFromAnEmbeddedNewline() {
        assertNull(PartMeta.decode("2.14.0\nabc123\n40000000\netag-1\nEVIL"))
    }

    /**
     * The property that matters: a field that genuinely contains a newline
     * must not round-trip into a different, wrong [PartMeta] — it must fail
     * closed. If `decode` were ever loosened (e.g. `split("\n", limit = 4)`
     * or `take(4)`), this would start round-tripping to a corrupted record
     * instead of failing, which is exactly the regression this guards.
     */
    @Test fun roundTripOfAFieldContainingANewlineDecodesToNullRatherThanAWrongRecord() {
        val meta = PartMeta("2.14.0", "abc123", 40_000_000L, "etag-1\nEVIL")
        assertNull(PartMeta.decode(meta.encode()))
    }

    @Test fun decodeRejectsATrailingNewline() {
        assertNull(PartMeta.decode("2.14.0\nabc123\n40000000\n\"etag-1\"\n"))
    }

    @Test fun matchesTheSameArtefactAndEtag() {
        val meta = PartMeta.of(update(), "\"etag-1\"")
        assertTrue(meta.matches(update(), "\"etag-1\""))
    }

    @Test fun rejectsADifferentVersionHashSizeOrEtag() {
        val meta = PartMeta.of(update(), "\"etag-1\"")
        assertFalse(meta.matches(update().copy(version = "2.15.0"), "\"etag-1\""))
        assertFalse(meta.matches(update().copy(sha256 = "def456"), "\"etag-1\""))
        assertFalse(meta.matches(update().copy(size = 1L), "\"etag-1\""))
        assertFalse(meta.matches(update(), "\"etag-2\""))
    }

    @Test fun aServerThatSendsNoEtagStillMatchesItsOwnRecord() {
        // Both sides blank is agreement, not a mismatch: GitHub's asset host
        // does send one, but a mirror that does not must still be resumable.
        val meta = PartMeta.of(update(), "")
        assertTrue(meta.matches(update(), ""))
        assertFalse(meta.matches(update(), "\"etag-1\""))
    }
}

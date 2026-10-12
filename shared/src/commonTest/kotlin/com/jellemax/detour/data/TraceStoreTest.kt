package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals

/** [TraceStore.withUnsynced]: the /sync write-back keeping lines the upload never carried (#693). */
class TraceStoreTest {

    @Test
    fun aLineFlushedDuringTheRoundTripSurvivesTheWriteBack() {
        val uploaded = listOf("[a]", "[b]")
        // The recording trip flushed "[c]" after the payload was read.
        val current = uploaded + "[c]"

        val kept = TraceStore.withUnsynced(server = listOf("[a]", "[b]", "[x]"), uploaded.toSet(), current)

        assertEquals(listOf("[a]", "[b]", "[x]", "[c]"), kept, "the mid-sync segment was wiped and never reached the server")
    }

    @Test
    fun aLineTheServerDroppedIsNotRestoredFromTheUpload() {
        // Uploaded and not returned: the server's answer stands, as before.
        val kept = TraceStore.withUnsynced(server = listOf("[b]"), setOf("[a]", "[b]"), listOf("[a]", "[b]"))

        assertEquals(listOf("[b]"), kept)
    }

    @Test
    fun aLineTheServerAlreadyReturnedIsNotDuplicated() {
        val kept = TraceStore.withUnsynced(server = listOf("[a]", "[c]"), setOf("[a]"), listOf("[a]", "[c]"))

        assertEquals(listOf("[a]", "[c]"), kept)
    }

    @Test
    fun blankLinesAreDroppedFromBothSides() {
        val kept = TraceStore.withUnsynced(server = listOf("[a]", " "), emptySet(), listOf("", "[c]"))

        assertEquals(listOf("[a]", "[c]"), kept)
    }
}

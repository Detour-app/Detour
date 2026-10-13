package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * Covers [writeAtomically], the write under every store's `writeText` (#694).
 *
 * The property that matters is the one a plain truncate-then-write lacked: if
 * the write does not complete, the file on disk is still the old one, whole.
 */
class AtomicWriteTest {

    private val dir = "/data/files/accounts/_local".toPath()
    private val trips = dir / "trips.json"

    private fun FakeFileSystem.textAt(path: Path) = read(path) { readUtf8() }

    @Test
    fun writesANewFileAndCreatesItsDirectory() {
        val fs = FakeFileSystem()
        fs.writeAtomically(trips, "[]")
        assertEquals("[]", fs.textAt(trips))
    }

    @Test
    fun replacesAnExistingFileAndLeavesNoTempFileBehind() {
        val fs = FakeFileSystem()
        fs.writeAtomically(trips, """[{"startTimeMs":1}]""")
        fs.writeAtomically(trips, "[]")
        assertEquals("[]", fs.textAt(trips))
        assertEquals(listOf(trips), fs.list(dir))
    }

    /** A rename that always fails: the moment between "temp file written" and
     *  "temp file in place", which is where a kill lands in the field.
     *  [FakeFileSystem] cannot fail one operation on its own. */
    private class MoveFails(delegate: FakeFileSystem) : ForwardingFileSystem(delegate) {
        override fun atomicMove(source: Path, target: Path) {
            throw IOException("simulated rename failure")
        }
    }

    @Test
    fun aWriteThatNeverLandsLeavesTheOldFileWholeAndCleansUp() {
        val fake = FakeFileSystem()
        val old = """[{"startTimeMs":1},{"startTimeMs":2}]"""
        fake.writeAtomically(trips, old)

        assertFailsWith<IOException> { MoveFails(fake).writeAtomically(trips, "[]") }

        assertEquals(old, fake.textAt(trips))
        assertEquals(listOf(trips), fake.list(dir))
    }
}

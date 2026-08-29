package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * Covers [AccountFiles] — moving an install's existing files under an account
 * bucket without a window in which a store cannot find them, and handing the
 * anonymous bucket to the first account that signs in.
 *
 * Driven against okio's [FakeFileSystem] rather than the ambient
 * `Platform.fileSystem`, which is `FileSystem.SYSTEM` on both platforms. The
 * shape — a pure function taking its dependency as a parameter, with an
 * ambient wrapper elsewhere supplying the real one — is the same one
 * [CredentialMigration] uses, for the same reason.
 */
class AccountFilesTest {

    private val root = "/data/files".toPath()

    private fun fsWithRootFiles(vararg names: String): FakeFileSystem {
        val fs = FakeFileSystem()
        fs.createDirectories(root)
        names.forEach { name ->
            fs.write(root / name) { writeUtf8("contents-of-$name") }
        }
        return fs
    }

    private fun FakeFileSystem.textAt(path: Path): String = read(path) { readUtf8() }

    @Test
    fun everyScopedFileMovesIntoTheAnonymousBucket() {
        val fs = fsWithRootFiles("trips.json", "traces.jsonl", "badges.json")

        AccountFiles.migrate(fs, root)

        val bucket = root / "accounts" / "_local"
        assertEquals("contents-of-trips.json", fs.textAt(bucket / "trips.json"))
        assertEquals("contents-of-traces.jsonl", fs.textAt(bucket / "traces.jsonl"))
        assertEquals("contents-of-badges.json", fs.textAt(bucket / "badges.json"))
        assertFalse(fs.exists(root / "trips.json"))
    }

    @Test
    fun everyNameOnTheScopedListActuallyMovesNotJustTheOnesOtherTestsUse() {
        // theScopedListIsExactlyTheEightRiderFilesAndNotTheSearchCache pins the
        // constant, but nothing calls migrate() with all eight names and checks
        // what actually moved. A loop hardcoding three names would leave every
        // other test in this file green while five of the eight rider-data
        // file types kept pooling across every account on the device — this is
        // what would catch that.
        val fs = fsWithRootFiles(*AccountFiles.SCOPED_NAMES.toTypedArray())

        AccountFiles.migrate(fs, root)

        val bucket = root / "accounts" / "_local"
        AccountFiles.SCOPED_NAMES.forEach { name ->
            assertEquals("contents-of-$name", fs.textAt(bucket / name))
            assertFalse(fs.exists(root / name))
        }
    }

    @Test
    fun theDeviceScopedFileStaysAtTheRoot() {
        val fs = fsWithRootFiles("trips.json", "recent_searches.json")

        AccountFiles.migrate(fs, root)

        assertTrue(fs.exists(root / "recent_searches.json"))
        assertFalse(fs.exists(root / "accounts" / "_local" / "recent_searches.json"))
    }

    @Test
    fun migratingTwiceIsTheSameAsMigratingOnce() {
        val fs = fsWithRootFiles("trips.json")

        AccountFiles.migrate(fs, root)
        AccountFiles.migrate(fs, root)

        assertEquals("contents-of-trips.json", fs.textAt(root / "accounts" / "_local" / "trips.json"))
    }

    @Test
    fun aPartialRunFinishesOnTheNextPassAndDoesNotDisturbWhatMoved() {
        val fs = fsWithRootFiles("trips.json", "badges.json")
        val bucket = root / "accounts" / "_local"
        // Simulate a run that moved trips.json and stopped.
        fs.createDirectories(bucket)
        fs.atomicMove(root / "trips.json", bucket / "trips.json")
        fs.write(bucket / "trips.json") { writeUtf8("already-migrated") }

        AccountFiles.migrate(fs, root)

        assertEquals("already-migrated", fs.textAt(bucket / "trips.json"))
        assertEquals("contents-of-badges.json", fs.textAt(bucket / "badges.json"))
    }

    @Test
    fun anAlreadyScopedFileIsNotOverwrittenByALeftoverAtTheRoot() {
        val fs = fsWithRootFiles("trips.json")
        val bucket = root / "accounts" / "_local"
        fs.createDirectories(bucket)
        fs.write(bucket / "trips.json") { writeUtf8("the-real-history") }

        AccountFiles.migrate(fs, root)

        assertEquals("the-real-history", fs.textAt(bucket / "trips.json"))
    }

    @Test
    fun theScopedListIsExactlyTheEightRiderFilesAndNotTheSearchCache() {
        // A literal list, deliberately, rather than deriving the expectation
        // from SCOPED_NAMES — which would assert the list equals itself.
        // Nothing else here would notice a name going missing: the migration
        // tests below name three files, so dropping routes.json or
        // municipalities.json from the list leaves every one of them green
        // while that rider's data stays pooled across accounts.
        assertEquals(
            listOf(
                "trips.json",
                "deleted_trips.json",
                "edited_modes.json",
                "traces.jsonl",
                "badges.json",
                "saved_places.json",
                "routes.json",
                "municipalities.json",
            ),
            AccountFiles.SCOPED_NAMES,
        )
    }

    @Test
    fun aFreshInstallWithNoFilesMigratesWithoutError() {
        val fs = FakeFileSystem()
        fs.createDirectories(root)

        AccountFiles.migrate(fs, root)

        assertFalse(fs.exists(root / "accounts" / "_local" / "trips.json"))
    }

    @Test
    fun theFirstAccountToSignInAdoptsTheAnonymousBucket() {
        val fs = fsWithRootFiles("trips.json")
        AccountFiles.migrate(fs, root)

        val adopted = AccountFiles.adopt(fs, root, "a3f1c8e29b4d7061")

        assertTrue(adopted)
        assertEquals(
            "contents-of-trips.json",
            fs.textAt(root / "accounts" / "a3f1c8e29b4d7061" / "trips.json"),
        )
        assertFalse(fs.exists(root / "accounts" / "_local"))
    }

    @Test
    fun aSecondAccountDoesNotAdoptAndGetsAnEmptyBucket() {
        val fs = fsWithRootFiles("trips.json")
        AccountFiles.migrate(fs, root)
        AccountFiles.adopt(fs, root, "aaaaaaaaaaaaaaaa")
        // Rider A signs out and records something with nobody signed in.
        fs.createDirectories(root / "accounts" / "_local")
        fs.write(root / "accounts" / "_local" / "trips.json") { writeUtf8("recorded-signed-out") }

        val adopted = AccountFiles.adopt(fs, root, "bbbbbbbbbbbbbbbb")

        assertFalse(adopted)
        assertFalse(fs.exists(root / "accounts" / "bbbbbbbbbbbbbbbb" / "trips.json"))
        assertEquals(
            "recorded-signed-out",
            fs.textAt(root / "accounts" / "_local" / "trips.json"),
        )
    }

    @Test
    fun adoptingWithNoAnonymousBucketIsANoOp() {
        val fs = FakeFileSystem()
        fs.createDirectories(root)

        assertFalse(AccountFiles.adopt(fs, root, "a3f1c8e29b4d7061"))
    }

    @Test
    fun anAccountReSigningInDoesNotReAdopt() {
        val fs = fsWithRootFiles("trips.json")
        AccountFiles.migrate(fs, root)
        AccountFiles.adopt(fs, root, "a3f1c8e29b4d7061")

        assertFalse(AccountFiles.adopt(fs, root, "a3f1c8e29b4d7061"))
    }

    @Test
    fun adoptingWithAnEmptyKeyIsRejectedAndDoesNotTouchTheAnonymousBucket() {
        val fs = fsWithRootFiles("trips.json")
        AccountFiles.migrate(fs, root)

        val adopted = AccountFiles.adopt(fs, root, "")

        assertFalse(adopted)
        assertEquals(listOf("_local"), fs.list(root / "accounts").map { it.name })
        assertEquals(
            "contents-of-trips.json",
            fs.textAt(root / "accounts" / "_local" / "trips.json"),
        )
    }
}

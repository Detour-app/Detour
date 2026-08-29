package com.jellemax.detour.data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.ForwardingFileSystem
import okio.IOException
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

    /**
     * Both of these are process-global and outlive the test that set them.
     * `migrated` disarms `accountDir()`'s check for every later test in the
     * same JVM — including RouteStoreLoadOrderTest, which then fails via a
     * different throw depending on file order — and a key left in
     * [AccountScope] points every later `accountFile()` somewhere unexpected.
     * Nothing depends on either today; leaving them set makes the suite
     * order-sensitive by construction, which is how it stops being true.
     */
    @AfterTest
    fun restoreProcessGlobals() {
        AccountFiles.resetMigratedForTest()
        AccountScope.clear()
    }

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
    fun anInstallAlreadySignedInWhenItUpgradedEndsUpUnderItsOwnKeyInOneLaunch() {
        // The majority upgrade path, and the one nothing used to run: this
        // install has never called exchangeCode, so adopt() has never been
        // reached, but auth_scope_key is on disk because a token refresh wrote
        // it. If one launch does not leave these under accounts/<key>, the
        // launch after it points at a bucket that has never existed, the
        // rider's history reads as empty, and the first write into the new
        // bucket makes adopt() refuse `_local` for good — routes.json is not
        // synced, so nothing brings it back.
        val fs = fsWithRootFiles("trips.json", "routes.json")

        AccountFiles.reconcileAtLaunch(fs, root, "a3f1c8e29b4d7061")

        val bucket = root / "accounts" / "a3f1c8e29b4d7061"
        assertEquals("contents-of-trips.json", fs.textAt(bucket / "trips.json"))
        assertEquals("contents-of-routes.json", fs.textAt(bucket / "routes.json"))
        assertFalse(
            fs.exists(root / "accounts" / "_local"),
            "the anonymous bucket was left behind, so the next write into accounts/<key> strands it",
        )
    }

    @Test
    fun aLaunchWithNoStoredKeyMigratesAndLeavesTheBucketUnclaimed() {
        // Nobody has ever signed in on this device. The files still have to
        // reach `_local`, and `_local` has to stay claimable — adopting it to
        // the empty key would name a directory nothing can ever resolve.
        val fs = fsWithRootFiles("trips.json")

        AccountFiles.reconcileAtLaunch(fs, root, "")

        assertEquals(
            "contents-of-trips.json",
            fs.textAt(root / "accounts" / "_local" / "trips.json"),
        )
        assertEquals(listOf("_local"), fs.list(root / "accounts").map { it.name })
    }

    @Test
    fun relaunchingAfterAdoptionLeavesTheAdoptedBucketAlone() {
        // reconcileAtLaunch runs on every launch, so it has to be idempotent
        // against its own output as well as against a fresh install. The
        // second call must not recreate `_local` or move anything.
        val fs = fsWithRootFiles("trips.json")
        AccountFiles.reconcileAtLaunch(fs, root, "a3f1c8e29b4d7061")

        AccountFiles.reconcileAtLaunch(fs, root, "a3f1c8e29b4d7061")

        assertEquals(listOf("a3f1c8e29b4d7061"), fs.list(root / "accounts").map { it.name })
        assertEquals(
            "contents-of-trips.json",
            fs.textAt(root / "accounts" / "a3f1c8e29b4d7061" / "trips.json"),
        )
    }

    /**
     * A rename that fails for one named source and works for everything else.
     *
     * [FakeFileSystem] has no way to make a single operation fail, and the
     * behaviour under test — one bad `atomicMove` out of eight — has no other
     * reachable trigger from a test. Forwarding is okio's own supported seam
     * for this.
     */
    private class MoveFailsFor(
        delegate: FakeFileSystem,
        private val failingSourceName: String,
    ) : ForwardingFileSystem(delegate) {
        override fun atomicMove(source: Path, target: Path) {
            if (source.name == failingSourceName) throw IOException("simulated rename failure")
            super.atomicMove(source, target)
        }
    }

    @Test
    fun oneFailedMoveCostsThatFileAndNeitherTheOtherSevenNorTheAdoption() {
        // The whole of I8: before the per-file catch, one failed atomicMove
        // aborted the remaining names *and* propagated past adopt(), so
        // Settings.init pointed the scope at accounts/<key> — a directory
        // this run never created. History then reads empty, the first write
        // creates the directory, and adopt() refuses `_local` for good.
        val fs = fsWithRootFiles(*AccountFiles.SCOPED_NAMES.toTypedArray())

        AccountFiles.reconcileAtLaunch(MoveFailsFor(fs, "traces.jsonl"), root, "a3f1c8e29b4d7061")

        val bucket = root / "accounts" / "a3f1c8e29b4d7061"
        AccountFiles.SCOPED_NAMES.filter { it != "traces.jsonl" }.forEach { name ->
            assertEquals(
                "contents-of-$name",
                fs.textAt(bucket / name),
                "$name was aborted by an unrelated file's failed move",
            )
        }
        assertTrue(
            fs.exists(root / "traces.jsonl"),
            "the name that failed must stay at the root so the next launch retries it",
        )
        assertFalse(
            fs.exists(root / "accounts" / "_local"),
            "adopt() was skipped, so accounts/<key> is not where the files are",
        )
    }

    @Test
    fun aFailedAdoptionIsNotSwallowedSoTheCallerCanLeaveTheScopeWhereTheFilesAre() {
        // The other half of the same decision. migrate() absorbs its own
        // failures; adopt()'s must reach Settings.init, which skips
        // AccountScope.set on a throw and leaves the scope on `_local` —
        // where these files still are.
        val fs = fsWithRootFiles("trips.json")

        assertFailsWith<IOException> {
            AccountFiles.reconcileAtLaunch(MoveFailsFor(fs, "_local"), root, "a3f1c8e29b4d7061")
        }

        assertEquals(
            "contents-of-trips.json",
            fs.textAt(root / "accounts" / "_local" / "trips.json"),
        )
    }

    /**
     * Records what [AccountScope.current] said at the instant the adoption
     * renamed the bucket.
     *
     * Asserting the outcome alone — adopted, `_local` gone, scope on the key
     * — cannot see the ordering at all: on one thread the two lines are
     * genuinely interchangeable, which is what [AccountFiles.adoptAndActivate]'s
     * own doc says. The hazard is a *concurrent* store call resolving
     * `accountFile()` while the rename is in flight, and what decides whether
     * that call lands in `_local` or creates the `accounts/<key>` that
     * poisons adoption forever is precisely the scope at this instant.
     */
    private class RecordsScopeDuringAdoption(
        delegate: FakeFileSystem,
    ) : ForwardingFileSystem(delegate) {
        var scopeAtRename: String? = null

        override fun atomicMove(source: Path, target: Path) {
            if (source.name == AccountScope.ANONYMOUS) scopeAtRename = AccountScope.current()
            super.atomicMove(source, target)
        }
    }

    @Test
    fun adoptAndActivateClaimsTheBucketBeforeItPointsTheScopeAtIt() {
        // The pair used to be two adjacent lines in Auth.store, where nothing
        // could reach them: moving a session means writing Settings, which
        // needs platform prefs this target does not have. Taking the
        // FileSystem as a parameter is what makes the order assertable — the
        // same argument reconcileAtLaunch's own doc makes.
        val fs = fsWithRootFiles("trips.json")
        AccountFiles.migrate(fs, root)
        val watched = RecordsScopeDuringAdoption(fs)

        val adopted = AccountFiles.adoptAndActivate(watched, root, "a3f1c8e29b4d7061")

        assertTrue(adopted)
        assertEquals(
            AccountScope.ANONYMOUS,
            watched.scopeAtRename,
            "the scope had already moved while the bucket was still being claimed, so a " +
                "store call in that window would create accounts/<key> and poison adopt forever",
        )
        assertEquals(
            "contents-of-trips.json",
            fs.textAt(root / "accounts" / "a3f1c8e29b4d7061" / "trips.json"),
        )
        assertFalse(fs.exists(root / "accounts" / "_local"), "`_local` outlived its adoption")
        assertEquals("a3f1c8e29b4d7061", AccountScope.current())
    }

    @Test
    fun adoptAndActivateStillMovesTheScopeWhenThereWasNothingToAdopt() {
        // A second account signs in: it adopts nothing and must still write
        // into its own bucket. Skipping the scope move here would leave the
        // new rider writing into the previous one's directory.
        val fs = fsWithRootFiles("trips.json")
        AccountFiles.migrate(fs, root)
        AccountFiles.adopt(fs, root, "aaaaaaaaaaaaaaaa")

        val adopted = AccountFiles.adoptAndActivate(fs, root, "bbbbbbbbbbbbbbbb")

        assertFalse(adopted)
        assertEquals("bbbbbbbbbbbbbbbb", AccountScope.current())
    }

    @Test
    fun aStrayEntryInTheAccountsDirectoryDoesNotBlockAdoptionForever() {
        // "Nothing else may live in accounts/" was a comment, and adopt()
        // depended on it: one .nomedia, one restored backup fragment, one
        // temp file and `_local` could never be claimed again. A bucket is a
        // directory named with sixteen hex characters; anything else is not
        // evidence that an account has owned data here.
        val fs = fsWithRootFiles("trips.json")
        AccountFiles.migrate(fs, root)
        fs.write(root / "accounts" / ".nomedia") { writeUtf8("") }

        assertTrue(AccountFiles.adopt(fs, root, "a3f1c8e29b4d7061"))
        assertEquals(
            "contents-of-trips.json",
            fs.textAt(root / "accounts" / "a3f1c8e29b4d7061" / "trips.json"),
        )
    }

    @Test
    fun aRealSecondBucketStillBlocksAdoption() {
        // The other side of the filter above: loosening it must not start
        // handing a second rider the anonymous bucket. Kept next to the stray
        // entry test so the two are read together.
        val fs = fsWithRootFiles("trips.json")
        AccountFiles.migrate(fs, root)
        fs.createDirectories(root / "accounts" / "aaaaaaaaaaaaaaaa")

        assertFalse(AccountFiles.adopt(fs, root, "bbbbbbbbbbbbbbbb"))
        assertTrue(fs.exists(root / "accounts" / "_local"))
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

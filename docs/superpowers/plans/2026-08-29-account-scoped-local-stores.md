# Account-Scoped Local Stores Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every on-disk store an account dimension, so a second rider signing in on a device
cannot have the first rider's rides, traces, badges and saved places uploaded into their server
account (#73).

**Architecture:** `appFile()` in `Files.kt` is the single path constructor for all 29 store call
sites. It is split into `deviceFile()` and a new `accountFile()` resolving under
`accounts/<key>/`, where the key is `sha256(sub)` truncated to 16 hex characters. A new
`AccountScope` object owns the key, a one-way migration of existing files into `accounts/_local/`,
and the adoption of that anonymous bucket by the first account to sign in.

**Tech Stack:** Kotlin Multiplatform (`shared/`), okio for paths and hashing, kotlinx-coroutines
`StateFlow`, `kotlin.test` in `commonTest`, `okio-fakefilesystem` (new, test-only).

## Global Constraints

- Branch is `feat/account-scoped-stores`, stacked on `feat/shared-circle-presence`. Do not rebase
  onto `main`.
- `versionName` in `app/build.gradle.kts:76` goes `1.83.0` → `1.84.0` in Task 5 only. Never touch
  `versionCode` — it is CI-stamped.
- Commit messages are conventional-commits. **No trailers of any kind** — no `Co-Authored-By`, no
  `Claude-Session`. Ignore the example in the Bash tool prompt, which contains both.
- All builds and tests run in the devcontainer: `devcontainer-exec ./gradlew …`. Never install
  anything on the host. Never run a bare `./gradlew build`.
- `commonMain` has no `Dispatchers` in code, no logger, no `java.*`. It has three non-sealed
  interfaces (`Prefs`, `RelaySocket`, `BearerSource`), each with more than one implementation; do
  not add a fourth — this plan needs none.
- `catch (e: CancellationException) { throw e }` goes ahead of every generic `catch`.
- The account key is `sha256(sub).hex().take(16)`. The anonymous bucket is the literal `_local`.
  The container directory is the literal `accounts`.
- Account-scoped files: `trips.json`, `deleted_trips.json`, `edited_modes.json`, `traces.jsonl`,
  `badges.json`, `saved_places.json`, `routes.json`, `municipalities.json`. Device-scoped:
  `recent_searches.json` **only**.
- Never `adb uninstall` and never `pm clear` on either app variant, for any reason. Never paste the
  contents of `shared_prefs/settings.xml` or `routing_server.xml` anywhere — they hold a live bearer
  token and a Cloudflare Access client secret. Report presence, never values.

---

## File Structure

**Create:**

- `shared/src/commonMain/kotlin/com/jellemax/detour/data/AccountScope.kt` — the key: deriving it,
  holding the current one, and the anonymous fallback. No file operations.
- `shared/src/commonMain/kotlin/com/jellemax/detour/data/AccountFiles.kt` — the migration and the
  adoption rule, both taking their `FileSystem` and root `Path` as parameters. Split from
  `AccountScope` because these are the only two functions here that touch a disk, and keeping them
  apart is what makes them testable against a fake. `Files.kt` stays what its own doc says it is:
  whole-file operations over okio, no policy.
- `shared/src/commonTest/kotlin/com/jellemax/detour/data/AccountScopeTest.kt` — key derivation and
  the current-bucket rules.
- `shared/src/commonTest/kotlin/com/jellemax/detour/data/AccountFilesTest.kt` — migration and
  adoption, over `FakeFileSystem`.

**Modify:**

- `shared/src/commonMain/kotlin/com/jellemax/detour/data/Files.kt:17` — `appFile` → `deviceFile`,
  add `accountFile` and `accountDir`.
- `shared/src/commonMain/kotlin/com/jellemax/detour/data/Auth.kt` — add `subjectFrom`; set and clear
  the scope key; invalidate the caching stores.
- `shared/src/commonMain/kotlin/com/jellemax/detour/data/Settings.kt` — persist and restore
  `auth_scope_key`; run the migration in `init()`.
- `shared/src/commonMain/kotlin/com/jellemax/detour/data/SyncClient.kt` — refuse to sync with no key.
- Seven store files — repoint call sites, add `reset()` where state is cached.
- `shared/build.gradle.kts:53-55` — add the `commonTest` fake-filesystem dependency.
- `app/src/main/res/xml/backup_rules.xml`, `app/src/main/res/xml/data_extraction_rules.xml`.
- `app/build.gradle.kts:76`, `docs/DEBUG_INTENTS.md`, three skills under `.claude/skills/`.

---

## Task 1: The account key

**Files:**
- Create: `shared/src/commonMain/kotlin/com/jellemax/detour/data/AccountScope.kt`
- Create: `shared/src/commonTest/kotlin/com/jellemax/detour/data/AccountScopeTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/Auth.kt` (add `subjectFrom` beside
  `usernameFrom` at `:375`)

**Interfaces:**
- Consumes: nothing.
- Produces: `AccountScope.ANONYMOUS: String` (`"_local"`), `AccountScope.ACCOUNTS_DIR: String`
  (`"accounts"`), `AccountScope.keyFrom(subject: String, username: String): String`,
  `AccountScope.current(): String`, `AccountScope.set(key: String)`, `AccountScope.clear()`,
  `Auth.subjectFrom(accessToken: String): String`.

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/jellemax/detour/data/AccountScopeTest.kt`:

```kotlin
package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Covers [AccountScope]'s key derivation. The key names a directory that ends
 * up inside a Google Drive backup, so "does it contain a rider identifier" is
 * part of what these assert, not just "is it stable".
 */
class AccountScopeTest {

    @Test
    fun theSubjectIsPreferredOverTheUsername() {
        val key = AccountScope.keyFrom(subject = "9f2b1a44-3c7e", username = "andre")
        assertEquals(AccountScope.keyFrom(subject = "9f2b1a44-3c7e", username = "someone-else"), key)
    }

    @Test
    fun theUsernameIsTheFallbackWhenThereIsNoSubject() {
        val key = AccountScope.keyFrom(subject = "", username = "andre")
        assertTrue(key.isNotEmpty())
        assertEquals(AccountScope.keyFrom(subject = "", username = "andre"), key)
    }

    @Test
    fun noSubjectAndNoUsernameYieldsNoKey() {
        assertEquals("", AccountScope.keyFrom(subject = "", username = ""))
    }

    @Test
    fun theKeyIsSixteenLowercaseHexCharacters() {
        val key = AccountScope.keyFrom(subject = "9f2b1a44-3c7e", username = "")
        assertEquals(16, key.length)
        assertTrue(key.all { it in "0123456789abcdef" }, "not hex: $key")
    }

    @Test
    fun theKeyDoesNotContainTheIdentifierItCameFrom() {
        val key = AccountScope.keyFrom(subject = "", username = "andre")
        assertTrue(!key.contains("andre"), "the username leaked into the directory name: $key")
    }

    @Test
    fun differentSubjectsGetDifferentKeys() {
        assertNotEquals(
            AccountScope.keyFrom(subject = "rider-a", username = ""),
            AccountScope.keyFrom(subject = "rider-b", username = ""),
        )
    }

    @Test
    fun currentIsTheAnonymousBucketUntilAKeyIsSet() {
        AccountScope.clear()
        assertEquals("_local", AccountScope.current())
    }

    @Test
    fun currentIsTheKeyOnceSetAndTheBucketAgainAfterClear() {
        AccountScope.set("a3f1c8e29b4d7061")
        assertEquals("a3f1c8e29b4d7061", AccountScope.current())
        AccountScope.clear()
        assertEquals("_local", AccountScope.current())
    }

    @Test
    fun aBlankKeyIsRefusedRatherThanBecomingADirectoryNamedNothing() {
        AccountScope.set("a3f1c8e29b4d7061")
        AccountScope.set("")
        assertEquals("_local", AccountScope.current())
    }
}
```

Also add, in the same file, the token-parsing test for `Auth.subjectFrom`. The token below is a
real-shaped unsigned JWT: three dot-separated segments, the middle one base64url of
`{"sub":"9f2b1a44","preferred_username":"andre"}`.

```kotlin
class SubjectFromTokenTest {

    // header.payload.signature. The payload segment is verified base64url of
    // {"sub":"9f2b1a44","preferred_username":"andre"} — decoded and checked
    // while writing this plan, so it does not need re-deriving. okio's
    // decodeBase64() accepts url-safe input, which is what a JWT uses.
    private val token =
        "e30." +
            "eyJzdWIiOiI5ZjJiMWE0NCIsInByZWZlcnJlZF91c2VybmFtZSI6ImFuZHJlIn0" +
            ".sig"

    @Test
    fun readsTheSubjectClaim() {
        assertEquals("9f2b1a44", Auth.subjectFrom(token))
    }

    @Test
    fun aTokenWithNoPayloadSegmentYieldsNothing() {
        assertEquals("", Auth.subjectFrom("notatoken"))
    }

    @Test
    fun anUndecodablePayloadYieldsNothingRatherThanThrowing() {
        assertEquals("", Auth.subjectFrom("e30.!!!not-base64!!!.sig"))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*AccountScopeTest*' --tests '*SubjectFromTokenTest*'`

Expected: FAIL — compilation error, `Unresolved reference: AccountScope`.

- [ ] **Step 3: Write `AccountScope`**

Create `shared/src/commonMain/kotlin/com/jellemax/detour/data/AccountScope.kt`:

```kotlin
package com.jellemax.detour.data

import kotlin.concurrent.Volatile
import okio.ByteString.Companion.encodeUtf8

/**
 * Which rider the files on disk belong to.
 *
 * Every store used to write fixed names straight into the app-private
 * directory, so one device held one set of files no matter how many people
 * signed in on it — and [SyncClient.sync] then faithfully uploaded whatever
 * was there to whoever was signed in. See #73 for what that does to a rider
 * who lends their phone.
 *
 * The key is a hash rather than the name it came from, for two reasons that
 * both matter. A directory name has to be a legal filename whatever the
 * identity provider chose to issue, and this one ends up inside a Google
 * Drive backup (see `app/src/main/res/xml/data_extraction_rules.xml`), where
 * a rider's handle or email address has no business being.
 */
internal object AccountScope {

    /** The bucket for data recorded with nobody signed in. Adopted by the
     *  first account to sign in on a device — see [AccountFiles.adopt]. */
    const val ANONYMOUS = "_local"

    /** The one directory under the app-private root that holds per-account
     *  buckets. Backed up as a subtree, which is why nothing else may live
     *  in it. */
    const val ACCOUNTS_DIR = "accounts"

    /** Read by [accountDir] on whatever thread a store call arrives on, and
     *  written by [Auth.store]/[Auth.clear] on another. `@Volatile` for the
     *  same reason `Coverage.cache` is. */
    @Volatile
    private var key: String = ""

    /** The bucket to read and write in right now. */
    fun current(): String = key.ifEmpty { ANONYMOUS }

    /**
     * Points every subsequent [accountFile] at [newKey].
     *
     * A blank key falls back to [ANONYMOUS] rather than being ignored, and
     * the difference matters: ignoring it would leave the *previous* rider's
     * key in place, so a session that establishes with nothing to key on
     * would write the new rider's rides straight into the old rider's
     * directory — a worse version of the defect this exists to fix. Landing
     * in the anonymous bucket is recoverable and, because
     * [SyncClient.sync] refuses to upload from it while signed in, cannot
     * reach anyone's server account.
     */
    fun set(newKey: String) {
        key = newKey
    }

    /** Back to the anonymous bucket. Called on sign-out. */
    fun clear() = set("")

    /**
     * The directory name for a session, or `""` when there is nothing to key
     * on — which [SyncClient.sync] treats as a refusal rather than a reason
     * to fall back to [ANONYMOUS].
     *
     * [subject] is preferred because it survives a rider renaming themselves
     * server-side; [username] is the fallback for a provider that issues no
     * `sub`. Truncated to 16 hex characters: a directory name, not a
     * security boundary — the collision it has to avoid is between the
     * handful of accounts one phone sees, and 64 bits is far past that.
     */
    fun keyFrom(subject: String, username: String): String {
        val source = subject.ifEmpty { username }
        if (source.isEmpty()) return ""
        return source.encodeUtf8().sha256().hex().take(16)
    }
}
```

- [ ] **Step 4: Add `subjectFrom` to `Auth`**

In `shared/src/commonMain/kotlin/com/jellemax/detour/data/Auth.kt`, directly below `usernameFrom`
(which ends at `:379`), add:

```kotlin
    /**
     * The provider's stable identifier for this rider, read out of the same
     * token payload [usernameFrom] reads. Used to name the on-disk bucket
     * their files live in, which is why `sub` is preferred over the handle:
     * a rider who renames themselves must not lose their history.
     *
     * Signature verification is deliberately absent for the same reason it is
     * in [usernameFrom] — this token arrived from the provider over TLS and
     * is being read for a label. The API is the party that has to verify it,
     * and does.
     */
    internal fun subjectFrom(accessToken: String): String {
        val payload = accessToken.split(".").getOrNull(1) ?: return ""
        val json = payload.decodeBase64()?.utf8() ?: return ""
        return runCatching { jsonObjectOf(json).optString("sub") }.getOrDefault("")
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*AccountScopeTest*' --tests '*SubjectFromTokenTest*'`

Expected: PASS, 12 tests.

- [ ] **Step 6: Prove the key test is not vacuous**

Change `take(16)` to `take(8)` in `AccountScope.keyFrom`. Re-run. Expected: FAIL on
`theKeyIsSixteenLowercaseHexCharacters`. Revert.

- [ ] **Step 7: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/AccountScope.kt \
        shared/src/commonMain/kotlin/com/jellemax/detour/data/Auth.kt \
        shared/src/commonTest/kotlin/com/jellemax/detour/data/AccountScopeTest.kt
git commit -m "feat(data): key an on-disk bucket by the token's subject claim"
```

---

## Task 2: Migration and adoption

**Files:**
- Create: `shared/src/commonMain/kotlin/com/jellemax/detour/data/AccountFiles.kt`
- Modify: `shared/build.gradle.kts:53-55` (commonTest dependencies)
- Test: `shared/src/commonTest/kotlin/com/jellemax/detour/data/AccountFilesTest.kt` (create)

**Interfaces:**
- Consumes: `AccountScope.ANONYMOUS`, `AccountScope.ACCOUNTS_DIR` from Task 1.
- Produces: `AccountFiles.SCOPED_NAMES: List<String>`,
  `AccountFiles.migrate(fs: FileSystem, root: Path)`,
  `AccountFiles.adopt(fs: FileSystem, root: Path, key: String): Boolean`.

- [ ] **Step 1: Add the test-only fake filesystem dependency**

In `shared/build.gradle.kts`, replace the `commonTest.dependencies` block (currently at `:53-55`):

```kotlin
        commonTest.dependencies {
            implementation(kotlin("test"))
            // okio's own in-memory FileSystem, so the migration in
            // AccountFiles is testable without touching a real disk. Test-only,
            // and pinned to the version commonMain already uses for okio
            // itself. This is what finally makes Platform.kt's "a fake in
            // tests" true — nothing had ever supplied one.
            implementation("com.squareup.okio:okio-fakefilesystem:3.9.0")
        }
```

- [ ] **Step 2: Write the failing test**

Create `shared/src/commonTest/kotlin/com/jellemax/detour/data/AccountFilesTest.kt`:

```kotlin
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
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*AccountFilesTest*'`

Expected: FAIL — compilation error, `Unresolved reference: AccountFiles`.

- [ ] **Step 4: Write `AccountFiles`**

Create `shared/src/commonMain/kotlin/com/jellemax/detour/data/AccountFiles.kt`:

```kotlin
package com.jellemax.detour.data

import okio.FileSystem
import okio.Path

/**
 * Getting an existing install's files under an account bucket, and handing
 * that bucket to the first rider who signs in.
 *
 * Both operations take their [FileSystem] and root [Path] as parameters
 * rather than reading `Platform.fileSystem` and `appFilesDir()` directly.
 * That is what makes them testable at all — the ambient ones are
 * `FileSystem.SYSTEM` on both platforms — and it is the shape
 * [CredentialMigration] already uses for the same reason: the pure step
 * takes its stores, the ambient wrapper supplies the real ones.
 */
internal object AccountFiles {

    /**
     * Every file that belongs to a rider rather than to the device.
     *
     * `recent_searches.json` is deliberately absent. It is a geocoder
     * convenience cache, and keeping it at the root keeps it out of the
     * `accounts` subtree that cloud backup now carries wholesale — typed
     * addresses are the one thing here worth not putting in Google Drive.
     * The cost is that it is still shared between riders on one device,
     * which is a smaller leak accepted knowingly, not one overlooked.
     */
    val SCOPED_NAMES = listOf(
        "trips.json",
        "deleted_trips.json",
        "edited_modes.json",
        "traces.jsonl",
        "badges.json",
        "saved_places.json",
        "routes.json",
        "municipalities.json",
    )

    /**
     * Moves anything still at the root into the anonymous bucket.
     *
     * The condition is **per file, not per run**: there is no "have I
     * migrated yet" marker, so a run that dies halfway simply finishes on the
     * next launch. That also means a file already in the bucket wins over a
     * leftover at the root — the bucket is where the app has been writing
     * since the first successful pass, so the root copy is the stale one.
     *
     * Called eagerly from [Settings.init], before any store reads, so no
     * store ever has to look in two places for one file.
     */
    fun migrate(fs: FileSystem, root: Path) {
        val bucket = root / AccountScope.ACCOUNTS_DIR / AccountScope.ANONYMOUS
        for (name in SCOPED_NAMES) {
            val from = root / name
            if (!fs.exists(from)) continue
            val to = bucket / name
            if (fs.exists(to)) continue
            fs.createDirectories(bucket)
            fs.atomicMove(from, to)
        }
    }

    /**
     * Hands the anonymous bucket to [key], if this is the first account ever
     * to sign in on this device.
     *
     * "First ever" needs no stored flag: it is whether `accounts/` holds
     * anything other than the anonymous bucket. Once some account owns data
     * here, a later sign-in gets its own empty bucket and whatever was
     * recorded signed out stays where it is — visible signed out, and never
     * uploaded to an account that did not record it.
     *
     * Returns whether it adopted, so a caller can tell "your rides are now
     * under your account" from "nothing to do".
     */
    fun adopt(fs: FileSystem, root: Path, key: String): Boolean {
        if (key.isEmpty()) return false
        val accounts = root / AccountScope.ACCOUNTS_DIR
        val anonymous = accounts / AccountScope.ANONYMOUS
        if (!fs.exists(anonymous)) return false
        val others = fs.list(accounts).filter { it.name != AccountScope.ANONYMOUS }
        if (others.isNotEmpty()) return false
        fs.atomicMove(anonymous, accounts / key)
        return true
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*AccountFilesTest*'`

Expected: PASS, 10 tests.

- [ ] **Step 6: Prove the adoption guard is not vacuous**

Delete the line `if (others.isNotEmpty()) return false` from `adopt`. Re-run. Expected: FAIL on
`aSecondAccountDoesNotAdoptAndGetsAnEmptyBucket`. Restore the line.

- [ ] **Step 7: Commit**

```bash
git add shared/build.gradle.kts \
        shared/src/commonMain/kotlin/com/jellemax/detour/data/AccountFiles.kt \
        shared/src/commonTest/kotlin/com/jellemax/detour/data/AccountFilesTest.kt
git commit -m "feat(data): migrate existing files into an anonymous bucket, adopted on first sign-in"
```

---

## Task 3: Split the path layer and repoint every store

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/Files.kt:17`
- Modify: `TripStore.kt` (`:74,90,115,136,160,164,176,182,196`), `TraceStore.kt`
  (`:54,66,104,109,113`), `Badges.kt` (`:152,163,168,176`), `SavedPlaces.kt` (`:57,83,87`),
  `Routes.kt` (`:149,168,172`), `Coverage.kt` (`:137,268`), `RecentSearchStore.kt` (`:24,28`)
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/Settings.kt` (`init()`)

**Interfaces:**
- Consumes: `AccountScope.current()`, `AccountScope.ACCOUNTS_DIR` (Task 1);
  `AccountFiles.migrate(fs, root)` (Task 2).
- Produces: `deviceFile(name: String): Path`, `accountFile(name: String): Path`,
  `accountDir(): Path`.

- [ ] **Step 1: Rewrite the path functions**

In `shared/src/commonMain/kotlin/com/jellemax/detour/data/Files.kt`, replace line 17
(`/** A file in the app-private directory. */` plus the `appFile` declaration) with:

```kotlin
/**
 * A file belonging to the device rather than to any rider — one copy, shared
 * by everyone who signs in here.
 *
 * This is what [accountFile]'s counterpart used to be called, back when it
 * was the only one and every store used it by default. The rename is
 * deliberate: an unqualified `appFile` that silently means device-scoped is
 * the exact shape of #73, and a name that reads as "the normal one" is how
 * the next store inherits the bug. Picking a scope is now a decision the
 * compiler makes you make.
 */
internal fun deviceFile(name: String): Path = appFilesDir() / name

/** The directory holding the current rider's files. */
internal fun accountDir(): Path =
    appFilesDir() / AccountScope.ACCOUNTS_DIR / AccountScope.current()

/**
 * A file belonging to whoever is signed in, or to the anonymous bucket when
 * nobody is. Resolved per call rather than cached, because the answer changes
 * the moment [Auth.store] or [Auth.clear] moves the session.
 */
internal fun accountFile(name: String): Path = accountDir() / name
```

- [ ] **Step 2: Repoint every call site**

Replace `appFile(` with `accountFile(` in all of: `TripStore.kt`, `TraceStore.kt`, `Badges.kt`,
`SavedPlaces.kt`, `Routes.kt`, `Coverage.kt`.

Replace `appFile(` with `deviceFile(` in `RecentSearchStore.kt` **only** (both sites, `:24` and
`:28`).

Verify none remain:

```bash
grep -rn 'appFile(' shared/ app/ iosApp/
```

Expected: no output.

- [ ] **Step 3: Run the migration eagerly at startup**

In `shared/src/commonMain/kotlin/com/jellemax/detour/data/Settings.kt`, inside `init()`, immediately
after the line `_authUsername.value = secure.string("auth_username")` (currently `:185`), add:

```kotlin
        // Before any store reads, so nothing ever has to look in two places
        // for one file. Per-file and unconditional, so a run that dies halfway
        // finishes on the next launch without a marker to get out of step.
        AccountScope.set(secure.string("auth_scope_key"))
        AccountFiles.migrate(fileSystem, appFilesDir())
```

- [ ] **Step 4: Verify it compiles and the suite still passes**

Run: `devcontainer-exec ./gradlew :shared:compileCommonMainKotlinMetadata :shared:testDebugUnitTest :app:testDebugUnitTest`

Expected: BUILD SUCCESSFUL. Shared test count is 324 + 22 from Tasks 1-2 = **346**; app **61**.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/
git commit -m "refactor(data): make every store name the scope its files belong to"
```

---

## Task 4: Session switch

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/Auth.kt` (`store` at `:349`,
  `clear` at `:278`)
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/Settings.kt`
  (`setSession` at `:250`)
- Modify: `SavedPlaces.kt`, `Routes.kt`, `Coverage.kt`, `TraceStore.kt` (add `reset()`)
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/SyncClient.kt` (`sync` at `:95`)
- Test: `shared/src/commonTest/kotlin/com/jellemax/detour/data/AccountScopeTest.kt` (extend)

**Interfaces:**
- Consumes: `AccountScope.set/clear/keyFrom` (Task 1), `AccountFiles.adopt` (Task 2),
  `Auth.subjectFrom` (Task 1).
- Produces: `SavedPlaces.reset()`, `Routes.reset()`, `Coverage.reset()`, `TraceStore.reset()`.

- [ ] **Step 1: Add `reset()` to the three caching stores plus the trace version bump**

In `SavedPlaces.kt`, after `ensureLoaded()` (which ends at `:35`):

```kotlin
    /** Drops this rider's places so the next [ensureLoaded] reads the new
     *  account's file. The read-through stores need no equivalent — they hit
     *  the file on every call, so moving the directory is enough. */
    fun reset() {
        loaded = false
        _places.value = emptyList()
    }
```

In `Routes.kt`, after `ensureLoaded()` (ends at `:118`), the same shape:

```kotlin
    /** Drops this rider's routes so the next [ensureLoaded] reads the new
     *  account's file. See [SavedPlaces.reset] for why only the caching
     *  stores need one. */
    fun reset() {
        loaded = false
        _routes.value = emptyList()
    }
```

In `Coverage.kt`, after `load()` (ends at `:145`):

```kotlin
    /** Drops the learned boundaries and the not-found set, both of which are
     *  derived from one rider's traces. */
    fun reset() {
        cache = null
        misses = emptySet()
    }
```

In `TraceStore.kt`, after `version` (`:41`):

```kotlin
    /** Nothing is cached here — [loadAll] reads the file every call — but the
     *  fog layer redraws off [version], so it has to be told the ground moved
     *  or it keeps showing the previous rider's territory until something
     *  else happens to bump it. */
    fun reset() {
        _version.value++
    }
```

- [ ] **Step 2: Persist the key alongside the session**

In `Settings.kt`, change `setSession` (`:250`) to take and store the key. Add the parameter last so
the existing argument order is untouched:

```kotlin
    fun setSession(
        accessToken: String,
        refreshToken: String,
        expiresAtMs: Long,
        username: String,
        scopeKey: String,
    ) {
```

and, after the existing `secure.put("auth_username", username)` line:

```kotlin
        secure.put("auth_scope_key", scopeKey)
```

- [ ] **Step 3: Wire `Auth.store` and `Auth.clear`**

In `Auth.kt`, in `store()`, replace the `Settings.setSession(...)` call with:

```kotlin
        val username = usernameFrom(access).ifBlank { Settings.authUsername.value }
        val scopeKey = AccountScope.keyFrom(subject = subjectFrom(access), username = username)
        Settings.setSession(
            accessToken = access,
            // Absent on a client configured without refresh tokens: keep the one
            // we have rather than silently downgrading the session to 15 minutes.
            refreshToken = o.optString("refresh_token").ifBlank { Settings.refreshToken.value },
            expiresAtMs = nowMs() + o.optLong("expires_in", 0L) * 1000L,
            username = username,
            scopeKey = scopeKey,
        )

        if (establishesSession) {
            // Adoption before the scope moves: adopt() reads the anonymous
            // bucket, and pointing accountFile() at the new key first would
            // leave a fresh empty directory beside the data it was supposed
            // to claim.
            AccountFiles.adopt(fileSystem, appFilesDir(), scopeKey)
            AccountScope.set(scopeKey)
            resetAccountScopedStores()
        }
```

In `clear()`, after `FriendFog.clear()`:

```kotlin
        AccountScope.clear()
        resetAccountScopedStores()
```

And add, as a private member of `Auth`:

```kotlin
    /** The stores that hold a rider's file contents in memory. Everything else
     *  reads through to the file on every call, so moving the directory is all
     *  those need. */
    private fun resetAccountScopedStores() {
        SavedPlaces.reset()
        Routes.reset()
        Coverage.reset()
        TraceStore.reset()
    }
```

- [ ] **Step 4: Make `sync()` refuse without a key**

In `SyncClient.kt`, at the top of `sync()`, directly after `Settings.init()`:

```kotlin
        // Signed in but with nothing to key a bucket on: the files being read
        // below belong to the anonymous bucket, which is not this session's.
        // Uploading them is precisely #73, so this refuses instead. A sync
        // that does not happen is recoverable; one that puts another rider's
        // history into this account is not.
        if (Account.signedIn.value && AccountScope.current() == AccountScope.ANONYMOUS) {
            throw AuthException("This session has no account identity; not uploading local data")
        }
```

- [ ] **Step 5: Extend the tests**

Append to `AccountScopeTest.kt`:

```kotlin
class SessionSwitchTest {

    @Test
    fun signingOutReturnsWritesToTheAnonymousBucket() {
        AccountScope.set("a3f1c8e29b4d7061")
        AccountScope.clear()
        assertEquals(AccountScope.ANONYMOUS, AccountScope.current())
    }

    @Test
    fun aDifferentAccountMovesTheBucketWithoutASignOutInBetween() {
        AccountScope.set("aaaaaaaaaaaaaaaa")
        AccountScope.set("bbbbbbbbbbbbbbbb")
        assertEquals("bbbbbbbbbbbbbbbb", AccountScope.current())
    }
}
```

- [ ] **Step 6: Run the full suite**

Run: `devcontainer-exec ./gradlew :shared:testDebugUnitTest :app:testDebugUnitTest :shared:compileCommonMainKotlinMetadata`

Expected: BUILD SUCCESSFUL. Shared **348**, app **61**.

- [ ] **Step 7: Prove the sync refusal is not vacuous**

Delete the `throw AuthException(...)` line added in Step 4 and confirm nothing fails — this refusal
has no unit test, because `sync()` does network I/O with no seam. **Report that gap explicitly**
rather than writing a test that asserts nothing; it is covered by reading, and by the
`AccountScope.current()` tests that pin the value it branches on. Restore the line.

- [ ] **Step 8: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/
git commit -m "fix(data): move the on-disk bucket when the session moves"
```

---

## Task 5: Backup rules, docs, skills and the version bump

**Files:**
- Modify: `app/src/main/res/xml/backup_rules.xml`, `app/src/main/res/xml/data_extraction_rules.xml`
- Modify: `app/build.gradle.kts:76`
- Modify: `docs/DEBUG_INTENTS.md:98-126`
- Modify: `.claude/skills/detour-trip-data/SKILL.md` (`:132-140`) and
  `.claude/skills/detour-trip-data/scripts/check-preconditions.sh:40`
- Modify: `.claude/skills/detour-adb/SKILL.md` (the `filesDir` table and its `run-as` examples)
- Modify: `.claude/skills/detour-gps-replay/SKILL.md:318`

**Interfaces:**
- Consumes: the `accounts/<key>/` layout from Task 3.
- Produces: nothing.

- [ ] **Step 1: Rewrite `backup_rules.xml`**

Replace the two `<include>` lines and extend the comment:

```xml
<full-backup-content>
    <!-- The whole accounts subtree, not two named files. Android's include
         paths cannot enumerate per-account directories, and the alternative
         was a second scoping mechanism alongside the directory one just to
         preserve the old exact-path scope.

         This deliberately broadens what is backed up: badges, saved places,
         routes and learned coverage had no cloud copy at all before, and now
         have one. recent_searches.json stays outside this subtree, at the
         files/ root, so typed addresses do not travel to Google Drive. -->
    <include domain="file" path="accounts" />
</full-backup-content>
```

- [ ] **Step 2: Rewrite `data_extraction_rules.xml`**

Replace `<include domain="file" path="trips.json" />` and
`<include domain="file" path="traces.jsonl" />` with `<include domain="file" path="accounts" />` in
**both** `<cloud-backup>` and `<device-transfer>`, and add the same explanatory comment as Step 1
above the `<cloud-backup>` block. Leave the `sharedpref` includes in `<device-transfer>` exactly as
they are.

- [ ] **Step 3: Update `docs/DEBUG_INTENTS.md`**

The seeding recipe at `:98-126` writes to `files/trips.json`, which is now
`files/accounts/<key>/trips.json`. Replace the three `files/trips.json` occurrences with the scoped
path and add above the recipe:

```markdown
> The directory is `sha256(sub)` truncated to 16 hex characters, so it cannot be guessed — list it
> first: `adb shell run-as io.github.maxke24.detour.debug ls files/accounts`. A signed-out install
> has exactly one, `_local`. A signed-in one has that account's hash, and possibly `_local` too if
> anything was recorded before signing in.
```

- [ ] **Step 4: Update the three skills**

In `.claude/skills/detour-trip-data/SKILL.md`, the file table at `:132-140`: add a column or a note
recording that every row except `recent_searches.json` now lives under `files/accounts/<key>/`.

In `.claude/skills/detour-trip-data/scripts/check-preconditions.sh:40`, the assertion
`check 'TraceStore still writes traces.jsonl' 1 "$(count 'traces.jsonl' "$STORE")"` still passes —
the constant is unchanged, only the directory moved. **Verify by running it** rather than assuming:

```bash
.claude/skills/detour-trip-data/scripts/check-preconditions.sh
```

Expected: all `PASS`. If any line fails, fix the script — a failing precondition script is the
mechanism working, not an obstacle.

In `.claude/skills/detour-adb/SKILL.md`, update the `filesDir` table and the
`run-as … cat files/trips.json` example to the scoped path, with the same "list the directory
first" note as Step 3.

In `.claude/skills/detour-gps-replay/SKILL.md:318`, update the
`run-as … cat files/trips.json` verification step the same way.

- [ ] **Step 5: Bump the version**

In `app/build.gradle.kts:76`: `versionName = "1.83.0"` → `versionName = "1.84.0"`.

Minor, not major, though the on-disk layout changes and the migration is one-way. No rider loses
data, the migration is automatic and silent, and the downgrade path a major bump protects is not one
an Android install takes. Recorded in the spec so the reasoning is visible rather than inferred.

- [ ] **Step 6: Verify the whole build**

Run: `devcontainer-exec ./gradlew :shared:testDebugUnitTest :app:testDebugUnitTest :shared:compileCommonMainKotlinMetadata :app:assembleDebug`

Expected: BUILD SUCCESSFUL, shared **348**, app **61**.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/res/xml/ app/build.gradle.kts docs/DEBUG_INTENTS.md .claude/skills/
git commit -m "chore: back up the accounts subtree, bump to 1.84.0, and repoint the adb recipes"
```

---

## Self-review notes

**Spec coverage.** §1 → Task 3. §2 → Task 1. §3 → Task 4 Step 1/3. §4 → Task 2. §5 → Task 5.
§6 (eager migration, no fallback) → Task 3 Step 3. §Tests → Tasks 1, 2, 4. §Version → Task 5.

**Known coverage gap, stated rather than papered over.** `sync()`'s refusal (Task 4 Step 4) has no
unit test: `sync()` performs network I/O with no seam, and this codebase does not fake `Http`.
Task 4 Step 7 makes the implementer confirm the gap by mutation rather than discover it in review.

**Not covered here, and deliberately.** Prefs are not scoped — `Settings.lastSyncMs` and the
per-circle `notifyArrivals`/`lastSeenEventTsMs` keys still span accounts. Same class of defect,
much smaller blast radius (no upload path), and its own pass. `_local` is never garbage-collected.
Both are recorded as follow-ups in the spec.

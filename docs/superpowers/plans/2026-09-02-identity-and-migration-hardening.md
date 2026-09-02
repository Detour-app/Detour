# Identity fallback and migration completion — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop two silent mis-attributions of rider identity — a credential migration whose loser returns before it finishes, and a username fallback that carries the previous rider's name into a new session.

**Architecture:** Part 1 replaces `migrateOnce()`'s compare-and-swap with a real lock, added as a fourth `Platform.kt` expect. Part 2 extracts the fallback decision as a pure function gated on the account-scope key, and documents a load-bearing realm setting.

**Tech Stack:** Kotlin Multiplatform (`shared/` commonMain + androidMain + iosMain + commonTest), `kotlin.test`, Gradle. Gradle runs through `devcontainer-exec`.

**Spec:** `docs/superpowers/specs/2026-09-02-identity-and-migration-hardening-design.md` — read it first, especially Part 1's severity argument, which is the reason this is worth a `Platform.kt` concern.

**Working directory:** `/home/andre/Projects/Detour/.claude/worktrees/identity-hardening`

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `shared/src/commonMain/.../data/Platform.kt` | Modify | Add `expect class PlatformLock` |
| `shared/src/androidMain/.../data/Platform.android.kt` | Modify | `actual` over `ReentrantLock` |
| `shared/src/iosMain/.../data/Platform.ios.kt` | Modify | `actual` over `NSLock` |
| `shared/src/commonMain/.../data/CredentialMigration.kt` | Modify | Lock replaces the CAS |
| `shared/src/commonMain/.../data/Auth.kt` | Modify | `carriedUsername` + its use in `store` |
| `shared/src/commonTest/.../data/AuthUsernameFallbackTest.kt` | Create | The four fallback cases |
| `docker/prod/README.md` | Modify | Realm checklist gains `editUsernameAllowed` |
| `app/build.gradle.kts` | Modify | `versionName` → `1.97.1` |

---

### Task 1: `PlatformLock`

**Files:** `Platform.kt`, `Platform.android.kt`, `Platform.ios.kt`

- [ ] **Step 1: Add the expect**

`shared/src/commonMain/kotlin/com/jellemax/detour/data/Platform.kt` opens with a doc saying "The three things the shared core needs from whatever OS it is running on". Update that sentence to four and add, after the existing declarations:

```kotlin
/**
 * Mutual exclusion, for the one thing in the shared core that needs it.
 *
 * This is the fourth concern in a file whose doc used to say three, and it is
 * added deliberately rather than by drift. [CredentialMigration.migrateOnce]
 * has to be *finished*, not merely started, before `Settings.init()` reads the
 * secure store on its next line — and no primitive already available to
 * `commonMain` provides that. `kotlinx.coroutines.sync.Mutex` is suspending and
 * both call sites are not; an atomic compare-and-swap makes one caller win but
 * lets the other return early, which is the bug this closes.
 *
 * Deliberately minimal: no tryLock, no timeout, no reentrancy contract beyond
 * what the two actuals happen to give. One caller, one use.
 */
expect class PlatformLock() {
    fun <T> withLock(block: () -> T): T
}
```

- [ ] **Step 2: Android actual**

In `shared/src/androidMain/kotlin/com/jellemax/detour/data/Platform.android.kt`:

```kotlin
actual class PlatformLock actual constructor() {
    private val lock = java.util.concurrent.locks.ReentrantLock()
    actual fun <T> withLock(block: () -> T): T = lock.withLock(block)
}
```

Add `import kotlin.concurrent.withLock`. If that import collides with anything, use the explicit `lock.lock() / try { block() } finally { lock.unlock() }` form instead — say which you used.

- [ ] **Step 3: iOS actual**

In `shared/src/iosMain/kotlin/com/jellemax/detour/data/Platform.ios.kt`:

```kotlin
actual class PlatformLock actual constructor() {
    private val lock = platform.Foundation.NSLock()
    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }
}
```

- [ ] **Step 4: Compile all three source sets**

```bash
devcontainer-exec ./gradlew :shared:compileCommonMainKotlinMetadata --console=plain
devcontainer-exec ./gradlew :shared:compileDebugKotlinAndroid --console=plain
```

Expected: `BUILD SUCCESSFUL` for both. **The iOS actual cannot be compiled on this Linux host** — Kotlin/Native for iOS is not available here. `compileCommonMainKotlinMetadata` type-checks the `expect` against the common intersection but does **not** compile `iosMain`. That gap is closed by CI (`ios.yml` runs `:shared:iosSimulatorArm64Test` on a macOS runner) once this is pushed. Say so in your report rather than implying you built it.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/Platform.kt \
        shared/src/androidMain/kotlin/com/jellemax/detour/data/Platform.android.kt \
        shared/src/iosMain/kotlin/com/jellemax/detour/data/Platform.ios.kt
git commit -m "Add PlatformLock, the shared core's fourth platform concern (#132)"
```

---

### Task 2: `migrateOnce()` holds the lock

**Files:** `shared/src/commonMain/kotlin/com/jellemax/detour/data/CredentialMigration.kt`

- [ ] **Step 1: Replace the CAS**

Current state (near line 100 and 115):

```kotlin
    private val migratedOnce = AtomicBoolean(false)
    ...
    fun migrateOnce() {
        if (!migratedOnce.compareAndSet(false, true)) return
        migrateGroup(prefs("settings"), SESSION_GROUP)
        migrateGroup(prefs(RoutingServer.PREFS), SERVER_GROUP)
    }
```

Replace the field and the body with:

```kotlin
    private val migrationLock = PlatformLock()
    private var migrated = false
```

and

```kotlin
    fun migrateOnce() = migrationLock.withLock {
        if (migrated) return@withLock
        migrated = true
        migrateGroup(prefs("settings"), SESSION_GROUP)
        migrateGroup(prefs(RoutingServer.PREFS), SERVER_GROUP)
    }
```

Keep the existing KDoc on `migrateOnce` and add to it:

```
     * Holds [PlatformLock] for the whole migration rather than winning a
     * compare-and-swap, because a caller has to know the migration is *finished*
     * when this returns. `Settings.init()` reads `access_token`, `refresh_token`
     * and `auth_username` out of the secure store on its very next lines, and on
     * an install still holding plaintext those values are not there until this
     * has run. A loser that returned early would read three empty strings,
     * derive an empty account-scope key, and land the rider on `accounts/_local`
     * — which is #73: the next account to sign in adopts that history as its own.
     *
     * The cost is that the loser blocks, up to the 1.6-1.8s Keystore read [step]
     * measures, and on the upgrade path that can be the main thread. Once per
     * process, only on installs that still hold plaintext, and correct — where
     * returning early is fast and wrong.
```

- [ ] **Step 2: Remove the now-unused atomics imports and `@OptIn`**

Deleting the `AtomicBoolean` leaves `import kotlin.concurrent.atomics.AtomicBoolean`, `import kotlin.concurrent.atomics.ExperimentalAtomicApi` and the `@OptIn(ExperimentalAtomicApi::class)` on the object unused. Remove all three. (That annotation was the only `@OptIn` in `shared/commonMain`, added by #43; it goes away with the CAS.)

Verify none of them is used elsewhere in the file before deleting:

```bash
grep -n "Atomic\|OptIn" shared/src/commonMain/kotlin/com/jellemax/detour/data/CredentialMigration.kt
```

- [ ] **Step 3: Verify**

```bash
devcontainer-exec ./gradlew :shared:testDebugUnitTest --console=plain
```

Expected: `BUILD SUCCESSFUL`. `CredentialMigrationTest`'s 16 cases must all still pass — the lock changes when callers return, not what the migration does.

- [ ] **Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/CredentialMigration.kt
git commit -m "Hold the migration lock until the migration is finished (#132)"
```

---

### Task 3: The username fallback, test-first

**Files:** `Auth.kt`, `shared/src/commonTest/kotlin/com/jellemax/detour/data/AuthUsernameFallbackTest.kt`

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/jellemax/detour/data/AuthUsernameFallbackTest.kt`:

```kotlin
package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The one rule: a session that cannot name its rider shows no name, rather than
 * the previous rider's. See Auth.carriedUsername.
 */
class AuthUsernameFallbackTest {

    private val riderA = "8f1c2d3e-aaaa-4444-9999-000000000001"
    private val riderB = "8f1c2d3e-bbbb-4444-9999-000000000002"

    private fun keyOf(subject: String) = AccountScope.keyFrom(subject = subject, username = "")

    @Test
    fun theStoredNameIsKeptWhenTheTokenIsForTheSameRider() {
        // The ordinary case this fallback exists for: a realm that stopped
        // sending preferred_username should not blank a signed-in rider's name.
        assertEquals(
            "ada",
            Auth.carriedUsername(subject = riderA, storedScopeKey = keyOf(riderA), stored = "ada"),
        )
    }

    @Test
    fun theStoredNameIsDroppedWhenTheTokenIsForADifferentRider() {
        // The sharp edge. Carrying "ada" here names rider B as rider A, and
        // every isMe / place.owner comparison in the app then agrees.
        assertEquals(
            "",
            Auth.carriedUsername(subject = riderB, storedScopeKey = keyOf(riderA), stored = "ada"),
        )
    }

    @Test
    fun theStoredNameIsDroppedWhenTheTokenCarriesNoSubject() {
        // No subject means no way to prove same-rider, so the safe answer is
        // the blank one — an opaque or encrypted access token lands here.
        assertEquals(
            "",
            Auth.carriedUsername(subject = "", storedScopeKey = keyOf(riderA), stored = "ada"),
        )
    }

    @Test
    fun theStoredNameIsDroppedWhenNoBucketHasBeenClaimedYet() {
        // Nothing to compare against, so nothing can be proven.
        assertEquals(
            "",
            Auth.carriedUsername(subject = riderA, storedScopeKey = "", stored = "ada"),
        )
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
devcontainer-exec ./gradlew :shared:testDebugUnitTest --console=plain
```

Expected: compile failure, `unresolved reference: carriedUsername`. Any other failure — stop and report.

- [ ] **Step 3: Add `carriedUsername`**

In `Auth.kt`, next to `usernameFrom` / `subjectFrom`:

```kotlin
    /**
     * The stored username, but only when [subject] identifies the same rider the
     * stored bucket belongs to — blank otherwise.
     *
     * [usernameFrom] reads `preferred_username` out of the **access token**, and
     * an access token is the resource server's artifact: RFC 9068 and the OAuth
     * 2.0 BCP both tell clients to treat it as opaque, so its shape is not a
     * contract this client can rely on. An opaque or encrypted token, a realm
     * that drops the claim, or a provider that changes the payload all make that
     * read come back blank without anything in this repo changing.
     *
     * What must not happen then is the previous value being carried forward: on
     * an account switch that names the second rider as the first, and every
     * `isMe` comparison and the `place.owner` ownership check in the app agrees
     * with it. A blank name is a visible bug; a wrong name is a silent one.
     *
     * Same-rider is decided through the account-scope key rather than by storing
     * the subject: [AccountScope.keyFrom] is `subject.ifEmpty { username }`
     * hashed, so with a subject present the username is not an input and the key
     * for this token can be compared against the one already persisted.
     */
    internal fun carriedUsername(subject: String, storedScopeKey: String, stored: String): String {
        if (subject.isEmpty() || storedScopeKey.isEmpty()) return ""
        val keyForThisToken = AccountScope.keyFrom(subject = subject, username = "")
        return if (keyForThisToken == storedScopeKey) stored else ""
    }
```

- [ ] **Step 4: Use it in `store()`**

`Auth.store` currently reads (around line 447):

```kotlin
        val username = usernameFrom(access).ifBlank { Settings.authUsername.value }
        val scopeKey = AccountScope.keyFrom(subject = subjectFrom(access), username = username)
```

Replace with:

```kotlin
        val subject = subjectFrom(access)
        val username = usernameFrom(access)
            .ifBlank { carriedUsername(subject, secure.string("auth_scope_key"), Settings.authUsername.value) }
        val scopeKey = AccountScope.keyFrom(subject = subject, username = username)
```

`secure` must be whatever `Auth` already uses to reach the secure store — **check how `Settings`/`Auth` reads `auth_scope_key` elsewhere in this file and match it exactly** rather than introducing a new accessor. If `Auth` has no such reference, use `securePrefs().string("auth_scope_key")` and say so.

Note `subjectFrom(access)` was previously called inline on the next line; it is now hoisted into a local and used twice. That is the only change to the `scopeKey` line.

- [ ] **Step 5: Run the tests**

```bash
devcontainer-exec ./gradlew :shared:testDebugUnitTest :app:testDebugUnitTest --console=plain
```

Expected: `BUILD SUCCESSFUL`, four new tests pass, nothing regresses. `AuthTest` and `AccountScope`-related tests are the ones most likely to notice — report their counts.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/Auth.kt \
        shared/src/commonTest/kotlin/com/jellemax/detour/data/AuthUsernameFallbackTest.kt
git commit -m "Never carry one rider's username into another's session (#25)"
```

---

### Task 4: Document the load-bearing realm setting

**Files:** `docker/prod/README.md`

- [ ] **Step 1: Read the checklist first**

```bash
grep -n -i "realm" docker/prod/README.md | head -20
```

Find the six-step realm checklist (roles, both clients, a username policy, an administrator).

- [ ] **Step 2: Add the item**

Add an item in the checklist's own voice and numbering, saying:

- set `editUsernameAllowed` to `false` (Keycloak's default, so a hand-created realm already has it);
- what depends on it: friend relationships and circle membership are stored against the username, so a rename detaches a rider from their own relationships until the server keys them on `sub` (tracked separately);
- that `loginWithEmailAllowed: true` already means the handle a rider thinks of as theirs is not necessarily the one the system keys on.

Match the file's existing formatting. Do not restructure the checklist.

- [ ] **Step 3: Commit**

```bash
git add docker/prod/README.md
git commit -m "Record that editUsernameAllowed is load-bearing (#25)"
```

---

### Task 5: Version bump

- [ ] **Step 1**

`app/build.gradle.kts` line ~80: `versionName = "1.97.0"` → `versionName = "1.97.1"`. Patch: both parts are fixes with no behaviour or API break. Do **not** touch `versionCode`.

- [ ] **Step 2: Commit**

```bash
git add app/build.gradle.kts
git commit -m "Bump versionName to 1.97.1"
```

---

### Task 6: Full verification

- [ ] **Step 1: The gates a pull request runs**

```bash
.claude/skills/detour-compose-state-hazards/scripts/check-secret-fields.sh
devcontainer-exec ./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest --console=plain
devcontainer-exec ./gradlew :shared:compileCommonMainKotlinMetadata --console=plain
devcontainer-exec ./gradlew :app:assembleRelease --console=plain
```

All must pass.

- [ ] **Step 2: Confirm the atomics are gone**

```bash
grep -rn "ExperimentalAtomicApi\|kotlin.concurrent.atomics" shared/src/
```

Expected: no output. If anything else in `shared/` uses them, leave it and say so.

- [ ] **Step 3: Confirm the diff**

```bash
git diff origin/main --stat
```

Expected: the spec, this plan, and the eight files in the File Structure table. Report anything else.

- [ ] **Step 4: Report**

Paste the real test counts. State explicitly that `iosMain`'s `actual` was **not** compiled locally and that CI is what covers it.

---

## Notes for the implementer

- **Do not add a `tryLock`, timeout or reentrancy contract** to `PlatformLock`. One caller, one use; a wider surface invites a second use that has not been thought about.
- **Do not make `migrateOnce()` suspend.** Both call sites are non-suspending and on the hot path; that was considered and rejected in the spec.
- **Do not touch the five client identity comparisons** (`CircleNotifyService.kt:162`, `FriendsScreen.kt:333`, `CirclesScreen.kt:442,446,541`) or the server's `FriendsController`. Re-keying on `sub` is split into its own issue and depends on a wire change.
- **Do not make the blank-username case throw.** A session that cannot name its rider is degraded, not invalid; refusing to store would sign out a rider whose realm merely stopped sending a claim.
- All Gradle goes through `devcontainer-exec`; `git` and edits stay on the host.

# Encrypted Credential Store Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the six credential values out of plaintext preferences into an Android Keystore–encrypted store, behind a `Prefs` interface that lets iOS follow later.

**Architecture:** `expect class Prefs` becomes `interface Prefs` in commonMain with the same ten
methods. A second expect, `securePrefs()`, opens one encrypted bag. Android backs it with
AES/GCM keys in `AndroidKeyStore`; iOS keeps `NSUserDefaults` behind the same interface until a
follow-up. A pure, unit-tested migration copies the values across in two phases and only
deletes the plaintext once the ciphertext has round-tripped across a process restart.

**Tech Stack:** Kotlin Multiplatform, `javax.crypto` + `android.security.keystore` (no new
dependency), `kotlin.test` in `commonTest`.

Spec: [`../specs/2026-08-15-encrypted-credential-store-design.md`](../specs/2026-08-15-encrypted-credential-store-design.md).
Closes [#26](https://github.com/maxke24/Detour/issues/26) in part.

## Global Constraints

- **All Gradle runs happen in the devcontainer.** Never on the host — the host JDK is 26 with
  no Android SDK. Prefix: `docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard`.
  Always the numeric uid:gid, never `-u dev`.
- **Never run a bare `./gradlew build`.** Name the task.
- **Never uninstall, `pm clear`, or `pm revoke` on the device.** A previous session destroyed a
  user's login and four saved traces that way. `adb install -r` only.
- **No new dependencies.** `androidx.security:security-crypto` is deprecated as of
  1.1.0-beta01 (4 June 2025) and must not be added. Use `javax.crypto` and
  `android.security.keystore` directly.
- **No `Co-Authored-By` and no `Claude-Session` trailer** on any commit.
- **`commonMain` has no `Dispatchers`, no logging and no `java.*`.** Keep it that way; the
  check that catches a violation is `:shared:compileCommonMainKotlinMetadata`.
- **A decrypt failure returns the default. It never throws.** The key can vanish on a device
  restore or an app-data clear, and a throw out of `Settings.init()` crashes the app on every
  launch thereafter.
- **Do not pass an IV to `Cipher.init(ENCRYPT_MODE, …)`.** Keystore keys default to
  `setRandomizedEncryptionRequired(true)`, which makes that throw. Let the cipher generate the
  IV and read it back from `cipher.iv`.
- **`minSdk = 26`.** `setIsStrongBoxBacked` is API 28+, so it needs a version guard and a
  fallback.
- **No `setUserAuthenticationRequired`.** The app reads tokens from a foreground service while
  the screen is off; requiring user presence would break trip recording.

## File Structure

| File | Responsibility |
|---|---|
| `shared/src/commonMain/.../data/Platform.kt` | **Modify.** `expect class Prefs` → `interface Prefs`; add `expect fun securePrefs()`. |
| `shared/src/androidMain/.../data/Platform.android.kt` | **Modify.** `actual class Prefs` → `SharedPrefsStore : Prefs`; add `securePrefs()`. |
| `shared/src/iosMain/.../data/Platform.ios.kt` | **Modify.** `actual class Prefs` → `UserDefaultsPrefs : Prefs`; add `securePrefs()`. |
| `shared/src/androidMain/.../data/SecretBox.kt` | **Create.** The Keystore key and the seal/open pair. Isolated so the crypto is readable on its own. |
| `shared/src/androidMain/.../data/KeystorePrefs.kt` | **Create.** `Prefs` over `SecretBox` + a SharedPreferences file. |
| `shared/src/commonMain/.../data/CredentialMigration.kt` | **Create.** The two-phase migration, pure and platform-free. |
| `shared/src/commonTest/.../data/CredentialMigrationTest.kt` | **Create.** With a fake `Prefs` — possible only because of the interface. |
| `shared/src/commonMain/.../data/Settings.kt` | **Modify.** Read/write the four session keys from the secure store; run the migration. |
| `shared/src/commonMain/.../data/RoutingServer.kt` | **Modify.** Read/write the two CF fields from the secure store; fix `clearCustom`. |
| `app/src/main/res/xml/data_extraction_rules.xml`, `backup_rules.xml` | **Modify.** Exclude the secure store. |
| `.claude/skills/detour-shared-core/` | **Modify.** The zero-interface assertion and its §4 row. |

---

### Task 1: The interface

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/Platform.kt`
- Modify: `shared/src/androidMain/kotlin/com/jellemax/detour/data/Platform.android.kt`
- Modify: `shared/src/iosMain/kotlin/com/jellemax/detour/data/Platform.ios.kt`
- Modify: `.claude/skills/detour-shared-core/SKILL.md`
- Modify: `.claude/skills/detour-shared-core/scripts/check-preconditions.sh`

**Interfaces:**
- Consumes: nothing.
- Produces: `interface Prefs` with the ten existing methods, in `com.jellemax.detour.data`. Platform classes `SharedPrefsStore` (androidMain) and `UserDefaultsPrefs` (iosMain), both `internal`.

No behaviour changes. The skill correction is in this task because this task is what makes the
skill's assertion false — leaving the repo in a state where its own check fails, even for one
commit, is how a check stops being trusted.

- [ ] **Step 1: Convert the declaration in commonMain**

In `Platform.kt`, replace `expect class Prefs {` with `interface Prefs {` and leave the ten
member signatures exactly as they are. Then extend the file's KDoc — the existing block above
`Prefs` explains what the bag is; add why it is now an interface:

```kotlin
/**
 * A named bag of primitives. SharedPreferences on Android, NSUserDefaults on
 * iOS — both are already string-keyed with typed accessors, so this maps onto
 * them without either side emulating the other.
 *
 * An interface rather than an `expect class` because there is now more than one
 * implementation per platform: Android has a plain store and a Keystore-encrypted
 * one, chosen by [prefs] versus [securePrefs]. CONTRIBUTING.md:39 — "a port earns
 * an interface when it has more than one implementation" — is the bar, and this
 * clears it. It is the first interface in commonMain; the 33 `object` singletons
 * around it are still the right pattern for everything that has one implementation.
 *
 * Writes are fire-and-forget on both platforms (Android `apply()`, iOS's own
 * lazy synchronisation), matching what the Android code already relied on.
 */
interface Prefs {
```

- [ ] **Step 2: Rename the Android implementation**

In `Platform.android.kt`, change `actual class Prefs(private val p: SharedPreferences) {` to
`internal class SharedPrefsStore(private val p: SharedPreferences) : Prefs {`, and delete the
`actual` keyword from all ten members (they become `override`).

Then change the factory:

```kotlin
actual fun prefs(name: String): Prefs =
    SharedPrefsStore(requireContext().getSharedPreferences(name, Context.MODE_PRIVATE))
```

- [ ] **Step 3: Rename the iOS implementation**

In `Platform.ios.kt`, change `actual class Prefs(private val bag: String) {` to
`internal class UserDefaultsPrefs(private val bag: String) : Prefs {`, delete `actual` from
all ten members (they become `override`), and change the factory:

```kotlin
actual fun prefs(name: String): Prefs = UserDefaultsPrefs(name)
```

- [ ] **Step 4: Verify both targets compile**

```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
  ./gradlew :shared:compileCommonMainKotlinMetadata :shared:testDebugUnitTest :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`. `compileCommonMainKotlinMetadata` is the check that catches a
commonMain declaration the iOS target cannot honour, and it is path-gated in CI on `shared/**`,
which this changes — so run it explicitly rather than relying on the PR build.

- [ ] **Step 5: Correct the skill's assertion**

In `.claude/skills/detour-shared-core/scripts/check-preconditions.sh`, the check asserting zero
non-sealed interfaces in commonMain must now expect **one**, and its message must name it.
Change the expected count and reword the label to something like:

```
commonMain has exactly ONE non-sealed interface (Prefs — three implementations, CONTRIBUTING.md:39)
```

- [ ] **Step 6: Correct the skill's body**

In `.claude/skills/detour-shared-core/SKILL.md`, the §4 table row at line 131 currently reads:

```markdown
| Interfaces / DI | **Zero interfaces, 33 `object` singletons** | match the pattern; see §2 test 2 |
```

Replace it with a row that records the precedent and its reason:

```markdown
| Interfaces / DI | **One interface (`Prefs`), 33 `object` singletons** | `Prefs` earned it under CONTRIBUTING.md:39 — three implementations (plain Android, Keystore-encrypted Android, plain iOS). Everything with one implementation is still an `object`; see §2 test 2 |
```

Also check §2 test 2's prose ("`commonMain` has **zero** interfaces … adding the first
interface needs an argument") and update the count there, keeping the requirement that an
argument is needed.

- [ ] **Step 7: Verify the skill script passes**

```bash
.claude/skills/detour-shared-core/scripts/check-preconditions.sh
```

Expected: `7 checks, 0 failed`, exit 0.

- [ ] **Step 8: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/Platform.kt \
        shared/src/androidMain/kotlin/com/jellemax/detour/data/Platform.android.kt \
        shared/src/iosMain/kotlin/com/jellemax/detour/data/Platform.ios.kt \
        .claude/skills/detour-shared-core/
git commit -m "refactor(shared): make Prefs an interface

The port needs two Android implementations - a plain store and a
Keystore-encrypted one - and CONTRIBUTING.md:39 says a port earns an interface
when it has more than one implementation. This is the first interface in
commonMain; the 33 object singletons around it are still right for everything
with a single implementation.

No behaviour change: the ten method signatures are untouched and the two
platform classes are the same code under new names.

detour-shared-core asserted zero interfaces, so its check and its section 4 row
are corrected in the same commit rather than left failing."
```

---

### Task 2: The migration

**Files:**
- Create: `shared/src/commonMain/kotlin/com/jellemax/detour/data/CredentialMigration.kt`
- Create: `shared/src/commonTest/kotlin/com/jellemax/detour/data/CredentialMigrationTest.kt`

**Interfaces:**
- Consumes: `Prefs` from Task 1.
- Produces, used by Task 4:
  - `internal enum class SecretType { Text, Number }`
  - `internal data class SecretKey(val name: String, val type: SecretType)`
  - `internal object CredentialMigration` with `MARKER: String`, `MARKER_VALUE: String`, `SESSION_KEYS: List<SecretKey>`, `SERVER_KEYS: List<SecretKey>`, `enum class Outcome { Copied, Verified, NothingToDo }`, and `fun step(plain: Prefs, secure: Prefs, keys: List<SecretKey>): Outcome`

**Why the keys are typed.** `access_token_expires_at` is written with `put(key, Long)`. On
Android, `SharedPreferences.getString` on a key stored as a long throws `ClassCastException`,
so a migration that treated every key as text would crash on exactly the install it was
written for.

**Why deletion waits for the next run.** Writing the marker and then immediately checking it
would prove only that the cipher works in-process. Reading it at the *start* of a later run
proves it survived a process restart and a fresh `Cipher` init against the Keystore — which is
where a key that is present but unusable shows up.

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/jellemax/detour/data/CredentialMigrationTest.kt`:

```kotlin
package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [CredentialMigration] — moving six credential values out of plaintext
 * preferences without a window in which they exist in neither store.
 *
 * The shape under test is deliberate: the plaintext is kept until a marker written
 * on an earlier run reads back, because that is the only evidence that this device
 * can actually decrypt what was written. Testable at all only because `Prefs` is an
 * interface, which is what lets [FakePrefs] exist.
 */
class CredentialMigrationTest {

    /** An in-memory [Prefs]. `failReads` models a Keystore that has lost its key. */
    private class FakePrefs(var failReads: Boolean = false) : Prefs {
        val map = mutableMapOf<String, Any>()
        override fun string(key: String, def: String): String =
            if (failReads) def else map[key] as? String ?: def
        override fun bool(key: String, def: Boolean): Boolean =
            if (failReads) def else map[key] as? Boolean ?: def
        override fun float(key: String, def: Float): Float =
            if (failReads) def else map[key] as? Float ?: def
        override fun long(key: String, def: Long): Long =
            if (failReads) def else map[key] as? Long ?: def
        override fun put(key: String, value: String) { map[key] = value }
        override fun put(key: String, value: Boolean) { map[key] = value }
        override fun put(key: String, value: Float) { map[key] = value }
        override fun put(key: String, value: Long) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun clear() { map.clear() }
    }

    private fun plainWithSession() = FakePrefs().apply {
        put("access_token", "at")
        put("refresh_token", "rt")
        put("access_token_expires_at", 1234L)
        put("auth_username", "andre")
    }

    @Test
    fun firstRunCopiesEverythingAndKeepsTheOriginals() {
        val plain = plainWithSession()
        val secure = FakePrefs()

        val outcome = CredentialMigration.step(plain, secure, CredentialMigration.SESSION_KEYS)

        assertEquals(CredentialMigration.Outcome.Copied, outcome)
        assertEquals("at", secure.string("access_token", ""))
        assertEquals(1234L, secure.long("access_token_expires_at", 0L))
        // The whole point: the fallback is still there.
        assertEquals("at", plain.string("access_token", ""))
        assertEquals(1234L, plain.long("access_token_expires_at", 0L))
    }

    @Test
    fun theSecondRunDeletesTheOriginalsOnceTheMarkerReadsBack() {
        val plain = plainWithSession()
        val secure = FakePrefs()

        CredentialMigration.step(plain, secure, CredentialMigration.SESSION_KEYS)
        val second = CredentialMigration.step(plain, secure, CredentialMigration.SESSION_KEYS)

        assertEquals(CredentialMigration.Outcome.Verified, second)
        assertEquals("", plain.string("access_token", ""))
        assertEquals(0L, plain.long("access_token_expires_at", 0L))
        // And the values are still readable from where they moved to.
        assertEquals("at", secure.string("access_token", ""))
    }

    @Test
    fun anUnreadableSecureStoreKeepsThePlaintextAndRetriesTheCopy() {
        val plain = plainWithSession()
        val secure = FakePrefs()

        CredentialMigration.step(plain, secure, CredentialMigration.SESSION_KEYS)
        // The Keystore key is gone: every read returns the default, so the marker
        // does not read back and nothing may be deleted.
        secure.failReads = true
        val second = CredentialMigration.step(plain, secure, CredentialMigration.SESSION_KEYS)

        assertEquals(CredentialMigration.Outcome.Copied, second)
        assertEquals("at", plain.string("access_token", ""))
    }

    @Test
    fun runningRepeatedlyChangesNothingOnceThePlaintextIsGone() {
        val plain = plainWithSession()
        val secure = FakePrefs()

        repeat(4) { CredentialMigration.step(plain, secure, CredentialMigration.SESSION_KEYS) }
        val settled = CredentialMigration.step(plain, secure, CredentialMigration.SESSION_KEYS)

        assertEquals(CredentialMigration.Outcome.NothingToDo, settled)
        assertEquals("at", secure.string("access_token", ""))
    }

    @Test
    fun aFreshInstallWithNothingToMoveStillArmsTheMarker() {
        val plain = FakePrefs()
        val secure = FakePrefs()

        val first = CredentialMigration.step(plain, secure, CredentialMigration.SESSION_KEYS)

        assertEquals(CredentialMigration.Outcome.NothingToDo, first)
        // Armed anyway, so a later run does not mistake a fresh install for an
        // interrupted migration and start copying blanks over real values.
        assertTrue(secure.string(CredentialMigration.MARKER, "").isNotEmpty())
    }

    @Test
    fun blankValuesAreNotCopiedOverTheOnesAlreadyMoved() {
        val plain = FakePrefs().apply { put("access_token", "") }
        val secure = FakePrefs().apply { put("access_token", "already-here") }

        CredentialMigration.step(plain, secure, CredentialMigration.SESSION_KEYS)

        assertEquals("already-here", secure.string("access_token", ""))
    }

    @Test
    fun theServerKeysAreTheTwoCloudflareFields() {
        assertEquals(
            listOf("clientId", "clientSecret"),
            CredentialMigration.SERVER_KEYS.map { it.name },
        )
    }

    @Test
    fun theExpiryIsTheOnlyNumericSecret() {
        val numeric = (CredentialMigration.SESSION_KEYS + CredentialMigration.SERVER_KEYS)
            .filter { it.type == SecretType.Number }
        assertEquals(listOf("access_token_expires_at"), numeric.map { it.name })
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
  ./gradlew :shared:testDebugUnitTest --tests "com.jellemax.detour.data.CredentialMigrationTest"
```

Expected: **FAIL** at compilation — `Unresolved reference: CredentialMigration`.

- [ ] **Step 3: Write the implementation**

Create `shared/src/commonMain/kotlin/com/jellemax/detour/data/CredentialMigration.kt`:

```kotlin
package com.jellemax.detour.data

/** Whether a secret is stored as text or as a number, so it is read back the same way. */
internal enum class SecretType { Text, Number }

/**
 * One credential value. The type is carried because `access_token_expires_at` is a
 * `Long`: Android's `SharedPreferences.getString` throws `ClassCastException` on a key
 * written with `putLong`, so a migration that assumed text would crash on precisely
 * the installs it exists for.
 */
internal data class SecretKey(val name: String, val type: SecretType)

/**
 * Moves credentials from the plaintext stores into the encrypted one, in two phases,
 * so there is never a moment where they exist in neither.
 *
 *   run 1   copy plaintext -> secure, write the marker, keep the originals
 *   run 2+  marker reads back?  yes -> delete the originals
 *                               no  -> keep them and copy again
 *
 * Deletion waits for a *later* run on purpose. Writing the marker and checking it in
 * the same run would only prove the cipher works in-process; reading it at the start
 * of the next run proves it survived a process restart and a fresh cipher init against
 * the Keystore, which is where a key that is present but unusable actually shows up.
 *
 * Pure: it takes both stores as parameters and touches nothing else, so it is testable
 * in commonTest against a fake.
 */
internal object CredentialMigration {

    /** Written into the secure store, and read back later as proof it decrypts. */
    const val MARKER = "__migration"
    const val MARKER_VALUE = "v1"

    /** The session, from the `settings` bag. */
    val SESSION_KEYS = listOf(
        SecretKey("access_token", SecretType.Text),
        SecretKey("refresh_token", SecretType.Text),
        SecretKey("access_token_expires_at", SecretType.Number),
        SecretKey("auth_username", SecretType.Text),
    )

    /** The Cloudflare Access service token, from the `routing_server` bag. */
    val SERVER_KEYS = listOf(
        SecretKey("clientId", SecretType.Text),
        SecretKey("clientSecret", SecretType.Text),
    )

    enum class Outcome { Copied, Verified, NothingToDo }

    fun step(plain: Prefs, secure: Prefs, keys: List<SecretKey>): Outcome {
        // Read before writing: "was the marker there when this run started".
        val armedEarlier = secure.string(MARKER, "") == MARKER_VALUE

        if (!armedEarlier) {
            var copied = 0
            for (k in keys) {
                when (k.type) {
                    SecretType.Text -> {
                        val v = plain.string(k.name, "")
                        if (v.isNotEmpty()) { secure.put(k.name, v); copied++ }
                    }
                    SecretType.Number -> {
                        val v = plain.long(k.name, 0L)
                        if (v != 0L) { secure.put(k.name, v); copied++ }
                    }
                }
            }
            secure.put(MARKER, MARKER_VALUE)
            return if (copied > 0) Outcome.Copied else Outcome.NothingToDo
        }

        var removed = 0
        for (k in keys) {
            val present = when (k.type) {
                SecretType.Text -> plain.string(k.name, "").isNotEmpty()
                SecretType.Number -> plain.long(k.name, 0L) != 0L
            }
            if (present) { plain.remove(k.name); removed++ }
        }
        return if (removed > 0) Outcome.Verified else Outcome.NothingToDo
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
  ./gradlew :shared:testDebugUnitTest --tests "com.jellemax.detour.data.CredentialMigrationTest"
```

Expected: `BUILD SUCCESSFUL`. Confirm the count rather than trusting the exit code:

```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
  grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  shared/build/test-results/testDebugUnitTest/TEST-com.jellemax.detour.data.CredentialMigrationTest.xml
```

Expected: `tests="8" skipped="0" failures="0" errors="0"`

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/CredentialMigration.kt \
        shared/src/commonTest/kotlin/com/jellemax/detour/data/CredentialMigrationTest.kt
git commit -m "feat(shared): add the two-phase credential migration

Copy and keep on the first run; delete the plaintext only once a marker
written earlier reads back. Checking the marker in the same run that wrote it
would prove only that the cipher works in-process - reading it at the start of
a later run proves it survived a restart and a fresh cipher init, which is
where a Keystore key that is present but unusable shows up.

Keys carry their type because access_token_expires_at is a Long and Android's
getString throws ClassCastException on it, which would have crashed on exactly
the installs this exists for.

Eight tests against a fake Prefs - possible only now that Prefs is an
interface."
```

---

### Task 3: The Android encrypted store

**Files:**
- Create: `shared/src/androidMain/kotlin/com/jellemax/detour/data/SecretBox.kt`
- Create: `shared/src/androidMain/kotlin/com/jellemax/detour/data/KeystorePrefs.kt`
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/Platform.kt`
- Modify: `shared/src/androidMain/kotlin/com/jellemax/detour/data/Platform.android.kt`
- Modify: `shared/src/iosMain/kotlin/com/jellemax/detour/data/Platform.ios.kt`

**Interfaces:**
- Consumes: `Prefs` from Task 1.
- Produces, used by Task 4: `expect fun securePrefs(): Prefs` in commonMain.

- [ ] **Step 1: Declare the expect**

In `Platform.kt`, immediately after `expect fun prefs(name: String): Prefs`:

```kotlin
/**
 * The one bag for credentials. Encrypted at rest on Android, where the key lives in
 * the Keystore and never leaves it.
 *
 * No name parameter: there is exactly one secure bag, and a name would be a second
 * way to say the same thing. Still four expects in this file — [Prefs] became an
 * interface, so the platform surface did not grow.
 */
expect fun securePrefs(): Prefs
```

- [ ] **Step 2: Write the crypto**

Create `shared/src/androidMain/kotlin/com/jellemax/detour/data/SecretBox.kt`:

```kotlin
package com.jellemax.detour.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM over a key that lives in the Android Keystore and never leaves it.
 *
 * Written against the platform directly rather than androidx.security-crypto, which was
 * deprecated in 1.1.0-beta01 (4 June 2025) "in favour of existing platform APIs and direct
 * use of Android Keystore". That is also what the OWASP Mobile Application Security Cheat
 * Sheet asks for, so the deprecation removed a dependency rather than forcing one.
 *
 * Both entry points return null rather than throwing. The key can genuinely disappear —
 * a device restore, an app-data clear, a Keystore fault — and a throw would propagate out
 * of Settings.init() and crash the app on every launch, permanently, because the failure
 * is persistent. Returning null means "no value", which the sign-in flow already handles.
 */
internal object SecretBox {

    private const val ALIAS = "detour_credential_store"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val IV_BYTES = 12

    /** Base64 of `IV || ciphertext`, or null if the key is unavailable. */
    fun seal(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORM)
        // No IV is supplied: Keystore keys default to setRandomizedEncryptionRequired(true),
        // which makes passing one throw. The cipher generates it and we read it back.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val body = cipher.doFinal(plain.encodeToByteArray())
        Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
    }.getOrNull()

    /** The plaintext, or null if the blob is corrupt or the key is gone. */
    fun open(blob: String): String? = runCatching {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        if (raw.size <= IV_BYTES) return null
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES),
        )
        cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES).decodeToString()
    }.getOrNull()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        // StrongBox is a separate security chip and is the strongest option the cheat sheet
        // names, but plenty of devices do not have one and generation throws there. Ask,
        // then fall back to the ordinary hardware-backed keystore.
        return runCatching {
            generator.init(spec(strongBox = true))
            generator.generateKey()
        }.getOrElse {
            generator.init(spec(strongBox = false))
            generator.generateKey()
        }
    }

    private fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
        ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        // Deliberately NOT setUserAuthenticationRequired: the trip service reads tokens
        // with the screen off, and requiring user presence would stop recording.
        .apply {
            if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setIsStrongBoxBacked(true)
            }
        }
        .build()
}
```

- [ ] **Step 3: Write the store**

Create `shared/src/androidMain/kotlin/com/jellemax/detour/data/KeystorePrefs.kt`:

```kotlin
package com.jellemax.detour.data

import android.content.SharedPreferences

/**
 * A [Prefs] whose values are sealed by [SecretBox] before they reach disk.
 *
 * The SharedPreferences file underneath is an ordinary one. It does not need to be
 * protected itself — what is in it is ciphertext, and the key is in the Keystore.
 *
 * Every value is stored as text, including numbers and booleans: one code path for
 * sealing, and the typed getters parse on the way out. A parse failure returns the
 * default for the same reason a decrypt failure does — see [SecretBox].
 */
internal class KeystorePrefs(private val p: SharedPreferences) : Prefs {

    private fun read(key: String): String? =
        p.getString(key, null)?.let { SecretBox.open(it) }

    private fun write(key: String, value: String) {
        val sealed = SecretBox.seal(value) ?: return
        p.edit().putString(key, sealed).apply()
    }

    override fun string(key: String, def: String): String = read(key) ?: def
    override fun bool(key: String, def: Boolean): Boolean = read(key)?.toBooleanStrictOrNull() ?: def
    override fun float(key: String, def: Float): Float = read(key)?.toFloatOrNull() ?: def
    override fun long(key: String, def: Long): Long = read(key)?.toLongOrNull() ?: def

    override fun put(key: String, value: String) = write(key, value)
    override fun put(key: String, value: Boolean) = write(key, value.toString())
    override fun put(key: String, value: Float) = write(key, value.toString())
    override fun put(key: String, value: Long) = write(key, value.toString())

    override fun remove(key: String) { p.edit().remove(key).apply() }
    override fun clear() { p.edit().clear().apply() }
}
```

- [ ] **Step 4: Wire both factories**

In `Platform.android.kt`, after the existing `actual fun prefs`:

```kotlin
actual fun securePrefs(): Prefs =
    KeystorePrefs(requireContext().getSharedPreferences(SECURE_STORE, Context.MODE_PRIVATE))

/** The file name, also referenced by the backup rules that exclude it. */
internal const val SECURE_STORE = "secure"
```

In `Platform.ios.kt`, after the existing `actual fun prefs`:

```kotlin
/**
 * Not yet encrypted. iOS keeps NSUserDefaults behind the same interface so the
 * Keychain implementation is a self-contained follow-up rather than a rewrite —
 * it cannot be verified from this repo's CI (no Swift test target), and shipping
 * security-critical code on a compile alone is how surfaces drift apart.
 */
actual fun securePrefs(): Prefs = UserDefaultsPrefs("secure")
```

- [ ] **Step 5: Verify**

```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
  ./gradlew :shared:compileCommonMainKotlinMetadata :shared:testDebugUnitTest :app:compileDebugKotlin
grep -c 'androidx.security' shared/build.gradle.kts app/build.gradle.kts
```

Expected: `BUILD SUCCESSFUL`, then `0` for both files — no dependency was added.

- [ ] **Step 6: Commit**

```bash
git add shared/src/androidMain/kotlin/com/jellemax/detour/data/SecretBox.kt \
        shared/src/androidMain/kotlin/com/jellemax/detour/data/KeystorePrefs.kt \
        shared/src/commonMain/kotlin/com/jellemax/detour/data/Platform.kt \
        shared/src/androidMain/kotlin/com/jellemax/detour/data/Platform.android.kt \
        shared/src/iosMain/kotlin/com/jellemax/detour/data/Platform.ios.kt
git commit -m "feat(shared): add a Keystore-backed Prefs for Android

AES-GCM with a 256-bit key generated in the Android Keystore, StrongBox where
the device has one and the ordinary hardware-backed keystore where it does
not. No new dependency: androidx.security-crypto was deprecated in June 2025
in favour of exactly this.

seal and open return null rather than throwing. The key can vanish on a device
restore or an app-data clear, and a throw would propagate out of
Settings.init() and crash the app on every launch thereafter - where returning
'no value' just means signing in again.

No setUserAuthenticationRequired: the trip service reads tokens with the
screen off.

iOS keeps NSUserDefaults behind the same interface for now, so the Keychain
implementation is a self-contained follow-up."
```

---

### Task 4: Move the six values

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/Settings.kt`
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/RoutingServer.kt`

**Interfaces:**
- Consumes: `securePrefs()` from Task 3; `CredentialMigration` from Task 2.
- Produces: nothing.

- [ ] **Step 1: Give Settings a secure handle and run the migration**

In `Settings.kt`, beside `private var store: Prefs?` (`:41-42`), add:

```kotlin
    private var secureStore: Prefs? = null
    private val secure: Prefs get() = secureStore ?: error("Settings.init() not called")
```

In `init()`, immediately after `store = prefs("settings")`:

```kotlin
        secureStore = securePrefs()
        // Two phases: this run copies and keeps, a later run deletes once the marker
        // reads back. See CredentialMigration.
        CredentialMigration.step(prefs, secure, CredentialMigration.SESSION_KEYS)
```

Then change the four session reads from `prefs` to `secure`:

```kotlin
        _accessToken.value = secure.string("access_token")
        _refreshToken.value = secure.string("refresh_token")
        _accessTokenExpiresAtMs.value = secure.long("access_token_expires_at", 0L)
        _authUsername.value = secure.string("auth_username")
```

- [ ] **Step 2: Write the session to the secure store**

In `setSession(...)`, change the four `prefs.put` calls to `secure.put`:

```kotlin
        secure.put("access_token", accessToken)
        secure.put("refresh_token", refreshToken)
        secure.put("access_token_expires_at", expiresAtMs)
        secure.put("auth_username", username)
```

- [ ] **Step 3: Move the two Cloudflare fields**

In `RoutingServer.kt`, `loadCustom()` currently reads both from `p`. Change it to read the
credential fields from the secure store, running the migration first so an install that has
not yet been migrated still finds them:

```kotlin
    fun loadCustom(): ServerConfig? {
        val p = prefs(PREFS)
        val s = securePrefs()
        CredentialMigration.step(p, s, CredentialMigration.SERVER_KEYS)
        val url = p.string("url")
        if (!p.bool("saved", false) || url.isBlank()) return null
        return ServerConfig(
            url = url,
            clientId = s.string("clientId"),
            clientSecret = s.string("clientSecret"),
            enabled = true,
        )
    }
```

`save()` writes the URL to the plain store and the credentials to the secure one:

```kotlin
    fun save(config: ServerConfig) {
        prefs(PREFS).apply {
            put("saved", true)
            put("url", config.url.trim())
        }
        securePrefs().apply {
            put("clientId", config.clientId.trim())
            put("clientSecret", config.clientSecret.trim())
        }
    }
```

- [ ] **Step 4: Fix `clearCustom`**

`clearCustom()` currently clears one store, which would now leave the credential behind — so
"Remove custom server" would keep the secret. It must clear both keys from the secure store,
but **must not** call `clear()` on it: the session lives there too, and removing a server
would sign the user out.

```kotlin
    /** Clearing the secure store wholesale would take the session with it, so the two
     *  Cloudflare keys are removed by name. */
    fun clearCustom() {
        prefs(PREFS).clear()
        securePrefs().apply {
            CredentialMigration.SERVER_KEYS.forEach { remove(it.name) }
        }
    }
```

- [ ] **Step 5: Verify**

```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
  ./gradlew :shared:compileCommonMainKotlinMetadata :shared:testDebugUnitTest \
            :app:compileDebugKotlin :app:assembleRelease
```

Expected: `BUILD SUCCESSFUL`. `assembleRelease` is included because R8 catches what a debug
build does not.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/Settings.kt \
        shared/src/commonMain/kotlin/com/jellemax/detour/data/RoutingServer.kt
git commit -m "feat(shared): read and write the six credentials from the secure store

The session's four keys and the two Cloudflare Access fields now live in the
encrypted bag. Everything else - theme, units, fog radius, zoom, the
notification preferences - stays in plaintext where it can still be read while
debugging, which is the split #26 asked for.

clearCustom removes the two Cloudflare keys by name rather than clearing the
secure store, because the session is in there too and 'Remove custom server'
must not sign the user out."
```

---

### Task 5: Keep the secure store off backups

**Files:**
- Modify: `app/src/main/res/xml/data_extraction_rules.xml`
- Modify: `app/src/main/res/xml/backup_rules.xml`

**Interfaces:**
- Consumes: the store name `secure` from Task 3.
- Produces: nothing.

Both files are allowlists — they name what *is* backed up — so the new store is already
excluded by omission. The change is to say so, because a future edit that adds
`<include domain="sharedpref" path="secure.xml" />` would look reasonable and would ship
ciphertext that can never be decrypted.

- [ ] **Step 1: Note it in `data_extraction_rules.xml`**

Extend the existing comment block, which already explains why `settings.xml` and
`routing_server.xml` are excluded from `<cloud-backup>`:

```xml
     secure.xml holds the same credentials now, encrypted. It is excluded from BOTH
     cloud-backup and device-transfer, and unlike the other two that is not a policy
     choice: its AES key lives in the Android Keystore, which never leaves the device,
     so a restored or transferred copy is ciphertext nobody can open. It would degrade
     to "not signed in" rather than crash — reads fail soft — but transferring bytes
     that cannot be read is not a feature.
```

- [ ] **Step 2: Note it in `backup_rules.xml`**

Add the equivalent sentence to that file's comment block, which covers Android 11 and below:

```xml
     secure.xml is excluded here too: its key is in the Keystore and does not travel,
     so a restored copy cannot be decrypted.
```

- [ ] **Step 3: Verify the allowlists still name only the two data files**

```bash
grep -c 'include domain' app/src/main/res/xml/backup_rules.xml
grep -c 'include domain' app/src/main/res/xml/data_extraction_rules.xml
grep -c 'secure' app/src/main/res/xml/data_extraction_rules.xml app/src/main/res/xml/backup_rules.xml
```

Expected: `2` for `backup_rules.xml`; `6` for `data_extraction_rules.xml` (two under
`<cloud-backup>`, four under `<device-transfer>`); and a non-zero mention of `secure` in each,
from the comments just added. If either `include` count changed, an allowlist entry was added
by mistake — revert it.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/xml/data_extraction_rules.xml app/src/main/res/xml/backup_rules.xml
git commit -m "docs(backup): say why the secure store is excluded from both rules

Both files are allowlists, so the new store was already excluded by omission.
Written down because adding it back would look like a reasonable edit: its AES
key is in the Android Keystore and does not leave the device, so a restored or
transferred copy is ciphertext nobody can open."
```

---

### Task 6: Device verification

**Files:** none.

**Interfaces:**
- Consumes: everything above.
- Produces: the observations for the PR description.

The crypto path cannot be unit-tested in this repo — `androidUnitTest` runs on a JVM with no
Keystore, and `shared/` has no instrumented test source set. This is the only gate on it.

- [ ] **Step 1: Read the package identity, then build and install**

Read `.claude/skills/detour-adb/SKILL.md` for the package table first — the Kotlin namespace
is **not** the applicationId, and the debug build has its own suffix.

```bash
adb devices
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: `RFCT42HS9WY device`, `BUILD SUCCESSFUL`, `Success`. **Never `adb uninstall`** — it
destroys the user's login and saved traces.

- [ ] **Step 2: Confirm the ciphertext is unreadable**

Open the app, sign in if it is not already, then:

```bash
adb shell run-as io.github.maxke24.detour.debug cat /data/data/io.github.maxke24.detour.debug/shared_prefs/secure.xml
```

Expected: keys named `access_token`, `refresh_token` and so on, with Base64 values that are
**not** the token. Record one value verbatim in the report. Then confirm the plaintext copy is
still present after this first run — that is the migration's first phase working:

```bash
adb shell run-as io.github.maxke24.detour.debug cat /data/data/io.github.maxke24.detour.debug/shared_prefs/settings.xml | grep -c access_token
```

Expected: `1` — kept, not yet deleted.

- [ ] **Step 3: Confirm the second run deletes the plaintext**

Force-stop and reopen the app, then re-read `settings.xml`:

```bash
adb shell am force-stop io.github.maxke24.detour.debug
adb shell am start -n io.github.maxke24.detour.debug/com.jellemax.detour.MainActivity
sleep 5
adb shell run-as io.github.maxke24.detour.debug cat /data/data/io.github.maxke24.detour.debug/shared_prefs/settings.xml | grep -c access_token
```

Expected: `0` — the marker read back, so the originals were removed. Confirm the app still
shows you as signed in.

- [ ] **Step 4: Confirm a lost key degrades rather than crashes**

This is the most important check in the task, because the failure it guards against is
permanent. Delete the Keystore entry out from under the app and restart it:

```bash
adb shell run-as io.github.maxke24.detour.debug ls /data/data/io.github.maxke24.detour.debug/shared_prefs/
adb shell am force-stop io.github.maxke24.detour.debug
adb shell run-as io.github.maxke24.detour.debug sh -c \
  'sed -i "s/>[A-Za-z0-9+/=]\{20,\}</>corrupted</g" /data/data/io.github.maxke24.detour.debug/shared_prefs/secure.xml'
adb shell am start -n io.github.maxke24.detour.debug/com.jellemax.detour.MainActivity
sleep 5
adb shell dumpsys activity activities | grep -m1 topResumedActivity
```

Corrupting the ciphertext models a key that no longer decrypts what was written, which is what
a device restore produces. Expected: the app is **still the resumed activity** — it opens and
shows a signed-out state rather than crashing. If it crashes, `SecretBox` is throwing somewhere
it should be returning null, and that is a blocker.

Then sign in again and confirm the session persists across a restart.

- [ ] **Step 5: Record the result**

Write each observation into the task report with what you actually saw. If step 4 crashes, say
so plainly and stop — a soft-failure bug found here is worth far more than a completed
checklist.

- [ ] **Step 6: Full sweep, then push**

```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
  ./gradlew :shared:compileCommonMainKotlinMetadata :shared:testDebugUnitTest \
            :app:testDebugUnitTest :app:assembleRelease
.claude/skills/detour-shared-core/scripts/check-preconditions.sh
git push -u origin feat/encrypted-credential-store
```

All must pass.

---

## Self-Review

**Spec coverage**

| Spec section | Task |
|---|---|
| Preconditions 1-7 | Task 1 Step 7 and Task 3 Step 5 assert the inverted ones flip |
| `interface Prefs`, ten methods unchanged | Task 1 |
| `expect fun securePrefs()`, expect count stays 4 | Task 3 Step 1 |
| Android AES-GCM, Keystore, StrongBox + fallback | Task 3 Step 2 |
| No supplied IV | Task 3 Step 2, in code and comment |
| No `setUserAuthenticationRequired` | Task 3 Step 2, in code and comment |
| All four types stored as text | Task 3 Step 3 |
| Soft failure on decrypt | Task 3 Step 2 (`getOrNull`), Task 6 Step 4 (device) |
| Two-phase migration | Task 2, verified on device in Task 6 Steps 2-3 |
| The six values, typed keys | Task 2 (`SESSION_KEYS`, `SERVER_KEYS`) |
| `clearCustom` must clear both | Task 4 Step 4 |
| Backup + device-transfer exclusion | Task 5 |
| iOS stays on NSUserDefaults behind the interface | Task 3 Step 4 |
| Skill correction | Task 1 Steps 5-6 |
| Out of scope: iOS Keychain, Settings testability, trips.json, QR, #24 | no task, correctly |

**Placeholder scan:** none. Every code step carries complete code; every command carries its
expected output.

**Type consistency:** `SecretType`, `SecretKey(name, type)`, `CredentialMigration.step(plain,
secure, keys)`, `Outcome.{Copied, Verified, NothingToDo}`, `MARKER`, `MARKER_VALUE`,
`SESSION_KEYS`, `SERVER_KEYS` are defined in Task 2 and used with those exact names in Tasks 2
and 4. `SecretBox.seal`/`open` are defined in Task 3 Step 2 and used in Step 3.
`securePrefs()` is declared in Task 3 Step 1 and called in Tasks 3 and 4. `SharedPrefsStore`
and `UserDefaultsPrefs` are introduced in Task 1 and referenced in Task 3 Step 4.

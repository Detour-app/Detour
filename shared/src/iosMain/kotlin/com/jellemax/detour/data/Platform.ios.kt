package com.jellemax.detour.data

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSLock
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask

/**
 * NSUserDefaults keys are flat across the whole app, unlike Android's separate
 * SharedPreferences files, so the bag name is folded into the key. Two bags
 * ("settings" and "routing_server") therefore cannot collide even where they
 * use the same key name.
 */
internal class UserDefaultsPrefs(private val bag: String) : Prefs {

    private val defaults = NSUserDefaults.standardUserDefaults

    private fun k(key: String) = "$bag.$key"

    // objectForKey is the only way to tell "absent" from "stored zero/false":
    // the typed getters return 0/false for a missing key, which would silently
    // override a non-zero default such as fog radius or the default zoom.
    private fun has(key: String) = defaults.objectForKey(k(key)) != null

    override fun string(key: String, def: String): String =
        defaults.stringForKey(k(key)) ?: def

    override fun bool(key: String, def: Boolean): Boolean =
        if (has(key)) defaults.boolForKey(k(key)) else def

    override fun float(key: String, def: Float): Float =
        if (has(key)) defaults.floatForKey(k(key)) else def

    override fun long(key: String, def: Long): Long =
        if (has(key)) defaults.integerForKey(k(key)) else def

    override fun put(key: String, value: String) = defaults.setObject(value, k(key))
    override fun put(key: String, value: Boolean) = defaults.setBool(value, k(key))
    override fun put(key: String, value: Float) = defaults.setFloat(value, k(key))
    override fun put(key: String, value: Long) = defaults.setInteger(value, k(key))

    override fun remove(key: String) = defaults.removeObjectForKey(k(key))

    /** Only the keys belonging to this bag, since the store is app-wide. */
    override fun clear() {
        val prefix = "$bag."
        defaults.dictionaryRepresentation().keys
            .filterIsInstance<String>()
            .filter { it.startsWith(prefix) }
            .forEach { defaults.removeObjectForKey(it) }
    }
}

actual fun prefs(name: String): Prefs = UserDefaultsPrefs(name)

/**
 * The plaintext store [securePrefs] used to return, kept around only as the source
 * for [migrateLegacySecureStoreToKeychain] — see that function's doc for why this
 * cannot be folded into [CredentialMigration.SESSION_GROUP]'s own plaintext bag.
 */
private val legacySecurePrefs by lazy { UserDefaultsPrefs("secure") }

private val keychainPrefs by lazy { KeychainPrefs() }

/**
 * A second migration, distinct from [CredentialMigration.SESSION_GROUP]: its source
 * is `secure.*` in `NSUserDefaults`, not `settings.*`.
 *
 * On any iOS install that already opened the app before this change, the session's
 * two-phase migration has already run to completion: the six credential values were
 * copied out of the `settings`/`routing_server` bags into the `secure` bag (still
 * `NSUserDefaults`, still plaintext), the copies were verified, and both
 * `__migration_session`/`__migration_server` markers are armed. Reusing
 * [CredentialMigration.SESSION_GROUP] here would read that armed marker as "already
 * migrated to the Keychain", take the delete branch immediately, and remove the only
 * copy of the session from a Keychain that was never written — signing every such
 * install out and destroying its Cloudflare Access token. A group with its own name
 * gets its own marker ([SecretGroup.marker] derives from it), so this cannot arm
 * itself off a run that was migrating between two entirely different stores.
 */
private val KEYCHAIN_GROUP = SecretGroup(
    name = "keychain",
    keys = CredentialMigration.SESSION_GROUP.keys,
)

// Guards migrateLegacySecureStoreToKeychain the same way CredentialMigration.migrated
// guards migrateOnce(): CredentialMigration.step() may run at most once per process
// per group, or a second call in the same run sees the marker its own first call just
// armed and deletes the plaintext before a process restart has proven the Keychain
// write actually survived.
private val secureStoreMigrationLock = PlatformLock()
private var secureStoreMigrated = false

private fun migrateLegacySecureStoreToKeychain() = secureStoreMigrationLock.withLock {
    if (secureStoreMigrated) return@withLock
    secureStoreMigrated = true
    if (CredentialMigration.groupHasPlaintext(legacySecurePrefs, KEYCHAIN_GROUP)) {
        CredentialMigration.step(legacySecurePrefs, keychainPrefs, KEYCHAIN_GROUP)
    }
}

/**
 * Keychain-backed, per #42. [migrateLegacySecureStoreToKeychain] runs at most once per
 * process before the store is handed out, so every reader — `Settings.init()` included —
 * sees credentials already moved rather than racing the migration.
 */
actual fun securePrefs(): Prefs {
    migrateLegacySecureStoreToKeychain()
    return keychainPrefs
}

/**
 * Documents rather than Application Support: these are the user's own trips
 * and traces, and putting them here is what lets the GPX exports show up over
 * iTunes/Finder file sharing later without moving the store.
 */
actual fun appFilesDir(): Path =
    (NSSearchPathForDirectoriesInDomains(
        NSDocumentDirectory, NSUserDomainMask, true,
    ).first() as String).toPath()

actual val fileSystem: FileSystem get() = FileSystem.SYSTEM

/**
 * An [NSLock]. Not recursive — NSRecursiveLock would be — which is fine:
 * [PlatformLock] promises no reentrancy and the one caller does not recurse
 * (`migrateGroup` reaches only `prefs`, `securePrefs` and `groupHasPlaintext`).
 *
 * `withLock` is written out rather than borrowed from an extension because
 * Kotlin/Native has no `Lock.withLock` for `NSLocking`.
 */
actual class PlatformLock actual constructor() {
    private val lock = NSLock()

    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

/**
 * A fresh [NSNumberFormatter] already carries `NSLocale.currentLocale`, so it
 * reports the separator the user's region setting writes numbers with. Falls
 * back to '.' if the property ever comes back empty; the binding types it
 * nullable.
 */
actual fun systemDecimalSeparator(): Char =
    NSNumberFormatter().decimalSeparator?.firstOrNull() ?: '.'

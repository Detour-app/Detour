package com.jellemax.detour.data

import kotlin.concurrent.AtomicInt
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
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
 * Not yet encrypted. iOS keeps NSUserDefaults behind the same interface so the
 * Keychain implementation is a self-contained follow-up rather than a rewrite —
 * it cannot be verified from this repo's CI (no Swift test target), and shipping
 * security-critical code on a compile alone is how surfaces drift apart.
 */
actual fun securePrefs(): Prefs = UserDefaultsPrefs("secure")

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

// kotlin.concurrent.AtomicInt is Kotlin/Native's own atomics API — stable at this
// project's Kotlin version (2.0.20), no opt-in required — unlike
// java.util.concurrent.atomic on the Android side. There is no AtomicBoolean in
// this package at 2.0.20 (kotlin.concurrent.atomics.AtomicBoolean only arrived in
// 2.1, behind @ExperimentalAtomicApi), so an Int standing in for the flag — 0
// unclaimed, 1 claimed — is the stable equivalent. Same contract as the Android
// actual: compareAndSet claims the flip for exactly one caller. Not verified from
// this repo's CI (Native compilations only link on macOS; see shared/build.gradle.kts),
// so treat this alongside the other iOS-only code here.
private val migrationClaimed = AtomicInt(0)

actual fun tryClaimMigration(): Boolean = migrationClaimed.compareAndSet(0, 1)

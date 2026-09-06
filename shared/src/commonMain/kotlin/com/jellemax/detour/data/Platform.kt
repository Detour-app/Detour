package com.jellemax.detour.data

import okio.FileSystem
import okio.Path

/**
 * The four things the shared core needs from whatever OS it is running on:
 * a small key-value store, a private directory to write files into, a file
 * system to reach that directory with, and mutual exclusion.
 *
 * Deliberately not a general "platform services" interface. Anything bigger
 * than this — location, audio, Bluetooth — stays on the platform side of the
 * boundary and is pushed *into* the core, rather than the core reaching out
 * for it. That is what keeps this file from growing into a second app.
 */

/**
 * A named bag of primitives. SharedPreferences on Android, NSUserDefaults on
 * iOS — both are already string-keyed with typed accessors, so this maps onto
 * them without either side emulating the other.
 *
 * An interface rather than an `expect class` because there is now more than one
 * implementation per platform: Android has a plain store and a Keystore-encrypted
 * one, chosen by [prefs] versus [securePrefs]. CONTRIBUTING.md:40 — "a port earns
 * an interface when it has more than one implementation" — is the bar, and this
 * clears it. It is the first interface in commonMain; the 33 `object` singletons
 * around it are still the right pattern for everything that has one implementation.
 *
 * Writes are fire-and-forget on both platforms (Android `apply()`, iOS's own
 * lazy synchronisation), matching what the Android code already relied on.
 */
interface Prefs {
    fun string(key: String, def: String = ""): String
    fun bool(key: String, def: Boolean): Boolean
    fun float(key: String, def: Float): Float
    fun long(key: String, def: Long): Long

    fun put(key: String, value: String)
    fun put(key: String, value: Boolean)
    fun put(key: String, value: Float)
    fun put(key: String, value: Long)

    fun remove(key: String)
    fun clear()
}

/** Opens (or creates) the preference bag called [name]. */
expect fun prefs(name: String): Prefs

/**
 * The one bag for credentials. Encrypted at rest on Android, where the key lives in
 * the Keystore and never leaves it.
 *
 * No name parameter: there is exactly one secure bag, and a name would be a second
 * way to say the same thing. That kept this file at four expects — [Prefs] became
 * an interface, so the platform surface did not grow. [PlatformLock] is the fifth,
 * and its own doc says why it was worth one.
 */
expect fun securePrefs(): Prefs

/** App-private directory for the trip/trace/coverage files. */
expect fun appFilesDir(): Path

/** The real file system on both platforms; a fake in tests. */
expect val fileSystem: FileSystem

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

/**
 * The decimal separator this device's locale writes numbers with: '.' in en-US,
 * ',' in nl-BE. The one thing the shared core asks the OS about formatting.
 *
 * Nothing in `presentation` calls this. The formatters take the separator as an
 * argument (defaulting to '.') and the render path resolves it once via
 * [Settings.decimalSeparatorChar] and passes it down — a mapper that read the
 * locale itself would be impure and untestable, the same reason `nowMs` is a
 * parameter rather than a clock read.
 */
expect fun systemDecimalSeparator(): Char

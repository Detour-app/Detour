package com.jellemax.detour.data

import okio.FileSystem
import okio.Path

/**
 * The three things the shared core needs from whatever OS it is running on:
 * a small key-value store, a private directory to write files into, and a
 * file system to reach that directory with.
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
 * way to say the same thing. Still four expects in this file — [Prefs] became an
 * interface, so the platform surface did not grow.
 */
expect fun securePrefs(): Prefs

/** App-private directory for the trip/trace/coverage files. */
expect fun appFilesDir(): Path

/** The real file system on both platforms; a fake in tests. */
expect val fileSystem: FileSystem

/**
 * Atomically claims [CredentialMigration]'s once-per-process migration run: returns
 * `true` to exactly one caller per process, `false` to every other. Backed by a
 * platform atomic rather than a plain `var` — `commonMain` has no synchronisation
 * primitive or `Dispatchers` of its own, so this is the one piece of that migration
 * that has to live per-platform.
 *
 * Without it, two coroutines entering `migrateOnce()` at cold start could both read an
 * unclaimed guard and both run [CredentialMigration.step] for the same group. The worst
 * case was mild — `step` is idempotent — but advisory rather than enforced.
 */
internal expect fun tryClaimMigration(): Boolean

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
 * Writes are fire-and-forget on both platforms (Android `apply()`, iOS's own
 * lazy synchronisation), matching what the Android code already relied on.
 */
expect class Prefs {
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

/** App-private directory for the trip/trace/coverage files. */
expect fun appFilesDir(): Path

/** The real file system on both platforms; a fake in tests. */
expect val fileSystem: FileSystem

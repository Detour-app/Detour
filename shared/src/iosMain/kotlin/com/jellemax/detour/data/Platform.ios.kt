package com.jellemax.detour.data

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
actual class Prefs(private val bag: String) {

    private val defaults = NSUserDefaults.standardUserDefaults

    private fun k(key: String) = "$bag.$key"

    // objectForKey is the only way to tell "absent" from "stored zero/false":
    // the typed getters return 0/false for a missing key, which would silently
    // override a non-zero default such as fog radius or the default zoom.
    private fun has(key: String) = defaults.objectForKey(k(key)) != null

    actual fun string(key: String, def: String): String =
        defaults.stringForKey(k(key)) ?: def

    actual fun bool(key: String, def: Boolean): Boolean =
        if (has(key)) defaults.boolForKey(k(key)) else def

    actual fun float(key: String, def: Float): Float =
        if (has(key)) defaults.floatForKey(k(key)) else def

    actual fun long(key: String, def: Long): Long =
        if (has(key)) defaults.integerForKey(k(key)) else def

    actual fun put(key: String, value: String) = defaults.setObject(value, k(key))
    actual fun put(key: String, value: Boolean) = defaults.setBool(value, k(key))
    actual fun put(key: String, value: Float) = defaults.setFloat(value, k(key))
    actual fun put(key: String, value: Long) = defaults.setInteger(value, k(key))

    actual fun remove(key: String) = defaults.removeObjectForKey(k(key))

    /** Only the keys belonging to this bag, since the store is app-wide. */
    actual fun clear() {
        val prefix = "$bag."
        defaults.dictionaryRepresentation().keys
            .filterIsInstance<String>()
            .filter { it.startsWith(prefix) }
            .forEach { defaults.removeObjectForKey(it) }
    }
}

actual fun prefs(name: String): Prefs = Prefs(name)

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

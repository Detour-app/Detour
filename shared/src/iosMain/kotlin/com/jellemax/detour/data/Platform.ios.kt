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
 * Documents rather than Application Support: these are the user's own trips
 * and traces, and putting them here is what lets the GPX exports show up over
 * iTunes/Finder file sharing later without moving the store.
 */
actual fun appFilesDir(): Path =
    (NSSearchPathForDirectoriesInDomains(
        NSDocumentDirectory, NSUserDomainMask, true,
    ).first() as String).toPath()

actual val fileSystem: FileSystem get() = FileSystem.SYSTEM

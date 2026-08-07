package com.jellemax.detour.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Android needs a Context to reach either preferences or the files dir, and
 * the shared core has no way to be handed one at every call site. So the
 * application Context is stashed once at startup instead.
 *
 * Held as the *application* context, so this is not a leak: it lives exactly
 * as long as the process does.
 */
@SuppressLint("StaticFieldLeak")
private var appContext: Context? = null

/** Call once from Application.onCreate (and from any Service that may start
 *  the process on its own, before touching the shared core). Idempotent. */
fun initSharedCore(context: Context) {
    if (appContext == null) appContext = context.applicationContext
}

private fun requireContext(): Context = appContext
    ?: error("initSharedCore(context) has not been called")

actual class Prefs(private val p: SharedPreferences) {
    actual fun string(key: String, def: String): String = p.getString(key, def) ?: def
    actual fun bool(key: String, def: Boolean): Boolean = p.getBoolean(key, def)
    actual fun float(key: String, def: Float): Float = p.getFloat(key, def)
    actual fun long(key: String, def: Long): Long = p.getLong(key, def)

    actual fun put(key: String, value: String) { p.edit().putString(key, value).apply() }
    actual fun put(key: String, value: Boolean) { p.edit().putBoolean(key, value).apply() }
    actual fun put(key: String, value: Float) { p.edit().putFloat(key, value).apply() }
    actual fun put(key: String, value: Long) { p.edit().putLong(key, value).apply() }

    actual fun remove(key: String) { p.edit().remove(key).apply() }
    actual fun clear() { p.edit().clear().apply() }
}

actual fun prefs(name: String): Prefs =
    Prefs(requireContext().getSharedPreferences(name, Context.MODE_PRIVATE))

actual fun appFilesDir(): Path = requireContext().filesDir.absolutePath.toPath()

actual val fileSystem: FileSystem get() = FileSystem.SYSTEM

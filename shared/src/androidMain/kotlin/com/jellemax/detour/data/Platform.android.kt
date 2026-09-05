package com.jellemax.detour.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
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

internal class SharedPrefsStore(private val p: SharedPreferences) : Prefs {
    override fun string(key: String, def: String): String = p.getString(key, def) ?: def
    override fun bool(key: String, def: Boolean): Boolean = p.getBoolean(key, def)
    override fun float(key: String, def: Float): Float = p.getFloat(key, def)
    override fun long(key: String, def: Long): Long = p.getLong(key, def)

    override fun put(key: String, value: String) { p.edit().putString(key, value).apply() }
    override fun put(key: String, value: Boolean) { p.edit().putBoolean(key, value).apply() }
    override fun put(key: String, value: Float) { p.edit().putFloat(key, value).apply() }
    override fun put(key: String, value: Long) { p.edit().putLong(key, value).apply() }

    override fun remove(key: String) { p.edit().remove(key).apply() }
    override fun clear() { p.edit().clear().apply() }
}

actual fun prefs(name: String): Prefs =
    SharedPrefsStore(requireContext().getSharedPreferences(name, Context.MODE_PRIVATE))

actual fun securePrefs(): Prefs =
    KeystorePrefs(requireContext().getSharedPreferences(SECURE_STORE, Context.MODE_PRIVATE))

/** The file name. Excluded from backup by omission: `backup_rules.xml` and
 *  `data_extraction_rules.xml` are include-only allowlists, so nothing named here is what
 *  keeps this store out of a backup — never being listed is. */
internal const val SECURE_STORE = "secure"

actual fun appFilesDir(): Path = requireContext().filesDir.absolutePath.toPath()

actual val fileSystem: FileSystem get() = FileSystem.SYSTEM

/** A [ReentrantLock]. Reentrancy is not part of [PlatformLock]'s contract and
 *  nothing relies on it; it is simply what the JDK's own `Lock` gives. */
actual class PlatformLock actual constructor() {
    private val lock = ReentrantLock()
    actual fun <T> withLock(block: () -> T): T = lock.withLock(block)
}

/** The locale this separator was resolved for, paired with the separator
 *  itself. One `@Volatile` reference rather than two fields, so a reader can
 *  never see a locale published ahead of the character it goes with. */
@Volatile private var separatorCache: Pair<Locale, Char>? = null

/** `DecimalFormatSymbols` follows `Locale.getDefault()`, which is what a test
 *  moves when it wants to see this device look Belgian.
 *
 *  Resolved once per locale, not once per call. Under SYSTEM this sits behind
 *  every distance and g readout in the app, including the ones a `LazyColumn`
 *  recomposes for each trip-history row on a fling, and `getInstance()` builds
 *  a fresh symbol set off the locale's ICU data each time it is asked.
 *
 *  Keyed on the [Locale] rather than cached outright, so changing the device
 *  language recomputes on the very next call instead of leaving a stale
 *  separator behind until the process is killed — the same reason the tests
 *  can move `Locale.getDefault()` mid-method and still see the new one. */
actual fun systemDecimalSeparator(): Char {
    val locale = Locale.getDefault()
    separatorCache?.let { (cached, separator) -> if (cached == locale) return separator }
    val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
    separatorCache = locale to separator
    return separator
}

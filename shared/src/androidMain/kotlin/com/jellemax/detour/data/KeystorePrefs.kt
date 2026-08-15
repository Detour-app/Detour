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
        val sealed = SecretBox.seal(value)
        if (sealed == null) {
            // Degrade to absent, not to stale. Leaving the old ciphertext would keep
            // serving a token that has just been refreshed or revoked, and the read path
            // already treats "cannot decrypt" as "no value".
            p.edit().remove(key).apply()
            return
        }
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

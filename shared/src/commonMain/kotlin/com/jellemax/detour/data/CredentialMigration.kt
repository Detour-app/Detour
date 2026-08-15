package com.jellemax.detour.data

/** Whether a secret is stored as text or as a number, so it is read back the same way. */
internal enum class SecretType { Text, Number }

/**
 * One credential value. The type is carried because `access_token_expires_at` is a
 * `Long`: Android's `SharedPreferences.getString` throws `ClassCastException` on a key
 * written with `putLong`, so a migration that assumed text would crash on precisely
 * the installs it exists for.
 */
internal data class SecretKey(val name: String, val type: SecretType)

/**
 * A set of credentials that migrate together, and the marker that records whether *this*
 * set has been copied and verified.
 *
 * The marker is per-group and not shared. Both groups migrate into the same secure store,
 * so a single marker would let the first group to run arm the second — which would then
 * take the delete branch and remove its plaintext without ever having copied it.
 */
internal data class SecretGroup(
    val marker: String,
    val keys: List<SecretKey>,
)

/**
 * Moves credentials from the plaintext stores into the encrypted one, in two phases,
 * so there is never a moment where they exist in neither.
 *
 *   run 1   copy plaintext -> secure, write the marker, keep the originals
 *   run 2+  marker reads back?  yes -> delete the originals
 *                               no  -> keep them and copy again
 *
 * Deletion waits for a *later* run on purpose. Writing the marker and checking it in
 * the same run would only prove the cipher works in-process; reading it at the start
 * of the next run proves it survived a process restart and a fresh cipher init against
 * the Keystore, which is where a key that is present but unusable actually shows up.
 *
 * Pure: it takes both stores as parameters and touches nothing else, so it is testable
 * in commonTest against a fake.
 */
internal object CredentialMigration {

    const val MARKER_VALUE = "v1"

    /** The session, from the `settings` bag. */
    val SESSION_GROUP = SecretGroup(
        marker = "__migration_session",
        keys = listOf(
            SecretKey("access_token", SecretType.Text),
            SecretKey("refresh_token", SecretType.Text),
            SecretKey("access_token_expires_at", SecretType.Number),
            SecretKey("auth_username", SecretType.Text),
        ),
    )

    /** The Cloudflare Access service token, from the `routing_server` bag. */
    val SERVER_GROUP = SecretGroup(
        marker = "__migration_server",
        keys = listOf(
            SecretKey("clientId", SecretType.Text),
            SecretKey("clientSecret", SecretType.Text),
        ),
    )

    enum class Outcome { Copied, Verified, NothingToDo }

    fun step(plain: Prefs, secure: Prefs, group: SecretGroup): Outcome {
        // Read before writing: "was the marker there when this run started".
        val armedEarlier = secure.string(group.marker, "") == MARKER_VALUE

        if (!armedEarlier) {
            var copied = 0
            for (k in group.keys) {
                when (k.type) {
                    SecretType.Text -> {
                        val v = plain.string(k.name, "")
                        if (v.isNotEmpty()) { secure.put(k.name, v); copied++ }
                    }
                    SecretType.Number -> {
                        val v = plain.long(k.name, 0L)
                        if (v != 0L) { secure.put(k.name, v); copied++ }
                    }
                }
            }
            secure.put(group.marker, MARKER_VALUE)
            return if (copied > 0) Outcome.Copied else Outcome.NothingToDo
        }

        var removed = 0
        for (k in group.keys) {
            val present = when (k.type) {
                SecretType.Text -> plain.string(k.name, "").isNotEmpty()
                SecretType.Number -> plain.long(k.name, 0L) != 0L
            }
            if (present) { plain.remove(k.name); removed++ }
        }
        return if (removed > 0) Outcome.Verified else Outcome.NothingToDo
    }
}

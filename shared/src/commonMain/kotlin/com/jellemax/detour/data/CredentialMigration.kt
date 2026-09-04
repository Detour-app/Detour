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
 *
 * Derived from [name] rather than passed in, so that defect cannot come back through a
 * third group added by copy-paste with the marker left pointing at the group it was
 * copied from. Two groups can now only collide by also being called the same thing.
 * Deriving it costs nothing here because the two markers already on real devices,
 * `__migration_session` and `__migration_server`, are exactly what this formula produces
 * from the names `session` and `server` — a marker that changed would read back blank on
 * an install that had already migrated, and re-run a completed migration.
 */
internal data class SecretGroup(
    val name: String,
    val keys: List<SecretKey>,
) {
    val marker: String get() = "__migration_$name"
}

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
        name = "session",
        keys = listOf(
            SecretKey("access_token", SecretType.Text),
            SecretKey("refresh_token", SecretType.Text),
            SecretKey("access_token_expires_at", SecretType.Number),
            SecretKey("auth_username", SecretType.Text),
            SecretKey("auth_rider_id", SecretType.Text),
        ),
    )

    /** The two keys the retired Cloudflare Access service token used to live under,
     *  in both the plaintext `routing_server` bag and the secure store — see
     *  [purgeRetiredServerToken]. */
    private val RETIRED_SERVER_KEYS = listOf("clientId", "clientSecret")

    enum class Outcome { Copied, Verified, NothingToDo }

    // Guarded by [migrationLock] rather than by an atomic compare-and-swap.
    // Two coroutines at cold start could both read `false` and both run the
    // migration, and RoutingServer.load() is on the hot path from IO coroutines
    // — but mutual exclusion alone was never the whole requirement. A plain
    // `var` read and written under the lock is correct here precisely because
    // no caller ever observes it from outside the lock.
    private val migrationLock = PlatformLock()
    private var migrated = false

    /**
     * Runs both credential groups' migration exactly once per process, whichever of
     * `Settings.init()` or `RoutingServer.loadCustom()` gets here first — the other
     * call becomes a no-op. Replaces what used to be two separate once-per-process
     * guards (one per call site) with a single one, since both call sites exist to
     * protect the same invariant: see [step]'s doc for why calling it more than once
     * per process per group is unsafe.
     *
     * Both call sites still have to call this rather than only one: `initSharedCore`
     * documents that a Service may start the process without `Settings.init()` ever
     * running first, so `RoutingServer` cannot rely on `Settings` having gone first,
     * and vice versa.
     *
     * Holds [PlatformLock] for the whole migration rather than winning a
     * compare-and-swap, because a caller has to know the migration is *finished*
     * when this returns. `Settings.init()` reads `access_token`, `refresh_token`
     * and `auth_username` out of the secure store on its very next lines, and on
     * an install still holding plaintext those values are not there until this
     * has run. A loser that returned early would read three empty strings,
     * derive an empty account-scope key, and land the rider on `accounts/_local`
     * — which is #73: the next account to sign in adopts that history as its own.
     *
     * The cost is that the loser blocks, up to the 1.6-1.8s Keystore read [step]
     * measures, and on the upgrade path that can be the main thread. Once per
     * process, only on installs that still hold plaintext, and correct — where
     * returning early is fast and wrong.
     */
    fun migrateOnce() = migrationLock.withLock {
        if (migrated) return@withLock
        migrated = true
        migrateGroup(prefs("settings"), SESSION_GROUP)
        purgeRetiredServerToken(prefs(RoutingServer.PREFS), securePrefs())
    }

    /**
     * Wipes any Cloudflare Access service token an earlier build left behind, in
     * either [plain] (the `routing_server` bag) or [secure], whichever phase of
     * [step]'s copy-then-delete dance an install had reached. Unlike
     * [migrateGroup]/[step], this is a plain unconditional delete — there is
     * nothing to copy or verify once the value is retired, only somewhere old
     * to remove it from. `internal`, not private, for the same reason
     * [groupHasPlaintext] is: a `Prefs` fake can drive it from commonTest.
     */
    internal fun purgeRetiredServerToken(plain: Prefs, secure: Prefs) {
        for (key in RETIRED_SERVER_KEYS) {
            plain.remove(key)
            secure.remove(key)
        }
    }

    /**
     * Calls [step] only if [group] still has plaintext worth copying or verifying.
     * [step] itself has to touch the Keystore-backed [securePrefs] even when there
     * is nothing to do — reading the marker back is how it tells "nothing to
     * migrate" from "verification pending" apart. That read measured 1.6-1.8s on
     * a Galaxy S928B (issue #54 §1's own cold-start numbers), on every single
     * cold start, forever, for both groups — because nothing ever stopped calling
     * [step] once a group's migration was actually done.
     *
     * [plain] not having any of [group]'s keys is that done state, checkable
     * without touching Keystore at all: either this install never had legacy
     * plaintext credentials, or an earlier run already deleted them after they
     * verified. Either way [step] would do nothing this call — the copy phase has
     * nothing to copy, and the delete phase has nothing left to delete — so
     * skipping it costs nothing.
     */
    private fun migrateGroup(plain: Prefs, group: SecretGroup) {
        if (groupHasPlaintext(plain, group)) step(plain, securePrefs(), group)
    }

    /** Whether any of [group]'s keys still has a plaintext value in [plain]. Pure
     *  and Keystore-free, unlike [step] — see [migrateGroup]'s doc for why that
     *  matters. Internal, not private, so it is testable against [Prefs] fakes
     *  the same way [step] is. */
    internal fun groupHasPlaintext(plain: Prefs, group: SecretGroup): Boolean =
        group.keys.any { k ->
            when (k.type) {
                SecretType.Text -> plain.string(k.name, "").isNotEmpty()
                SecretType.Number -> plain.long(k.name, 0L) != 0L
            }
        }

    /**
     * Advances [group]'s migration by one phase: copies plaintext to [secure] and arms
     * the marker if it isn't armed yet, or deletes the plaintext from [plain] if the
     * marker already read back.
     *
     * Callers MUST invoke this at most once per process per [group]. The marker
     * distinguishes an *earlier run* from *this run*, but a marker is just a value in
     * [secure] — this function has no way to tell a second call in the same process
     * from a call on a genuinely later run; it can only see whether the marker is
     * there. Call it more than once per process (e.g. unguarded from a function invoked
     * on every request) and the second call sees the first call's own marker and takes
     * the delete branch immediately, destroying the plaintext fallback before the
     * round-trip across a process restart it exists to prove ever happened. Guarding
     * against that is the caller's job — see [migrateOnce], the single guarded entry
     * point both real call sites go through.
     */
    fun step(plain: Prefs, secure: Prefs, group: SecretGroup): Outcome {
        // Read before writing: "was the marker there when this run started".
        val armedEarlier = secure.string(group.marker, "") == MARKER_VALUE

        if (!armedEarlier) {
            var copied = 0
            for (k in group.keys) {
                // Copy only into an empty slot: if the marker write itself failed to
                // seal on an earlier run, this phase runs again, and a slot that
                // already holds a value is one the user set *after* that earlier run
                // (a new sign-in) — the stale plaintext must not clobber it.
                when (k.type) {
                    SecretType.Text -> {
                        val v = plain.string(k.name, "")
                        if (v.isNotEmpty() && secure.string(k.name, "").isEmpty()) {
                            secure.put(k.name, v); copied++
                        }
                    }
                    SecretType.Number -> {
                        val v = plain.long(k.name, 0L)
                        if (v != 0L && secure.long(k.name, 0L) == 0L) {
                            secure.put(k.name, v); copied++
                        }
                    }
                }
            }
            secure.put(group.marker, MARKER_VALUE)
            return if (copied > 0) Outcome.Copied else Outcome.NothingToDo
        }

        // Verify each value individually before deleting it: the marker reading back
        // proves only that *the marker* round-tripped through the cipher, not that
        // every key did. `KeystorePrefs.write` can drop a key on a sealing failure,
        // and a mid-loop key regeneration can leave earlier keys in this same batch
        // unreadable while the marker (written last) is fine. A key that does not
        // read back the same value is re-copied instead of deleted; the next run
        // checks again.
        var removed = 0
        for (k in group.keys) {
            when (k.type) {
                SecretType.Text -> {
                    val v = plain.string(k.name, "")
                    if (v.isEmpty()) continue
                    if (secure.string(k.name, "") == v) { plain.remove(k.name); removed++ }
                    else secure.put(k.name, v)
                }
                SecretType.Number -> {
                    val v = plain.long(k.name, 0L)
                    if (v == 0L) continue
                    if (secure.long(k.name, 0L) == v) { plain.remove(k.name); removed++ }
                    else secure.put(k.name, v)
                }
            }
        }
        return if (removed > 0) Outcome.Verified else Outcome.NothingToDo
    }
}

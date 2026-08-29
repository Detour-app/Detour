package com.jellemax.detour.data

import kotlin.concurrent.Volatile
import okio.FileSystem
import okio.Path

/**
 * Getting an existing install's files under an account bucket, and handing
 * that bucket to the first rider who signs in.
 *
 * Both operations take their [FileSystem] and root [Path] as parameters
 * rather than reading `Platform.fileSystem` and `appFilesDir()` directly.
 * That is what makes them testable at all — the ambient ones are
 * `FileSystem.SYSTEM` on both platforms — and it is the shape
 * [CredentialMigration] already uses for the same reason: the pure step
 * takes its stores, the ambient wrapper supplies the real ones.
 */
internal object AccountFiles {

    /** Whether [migrate] has run this process. `accountDir` refuses to resolve
     *  a path before it has — see that function for why silence would be worse
     *  than a crash. `@Volatile` because the first store read can be on a
     *  different thread from `Settings.init`. */
    @Volatile
    internal var migrated: Boolean = false
        private set

    /**
     * Every file that belongs to a rider rather than to the device.
     *
     * `recent_searches.json` is deliberately absent. It is a geocoder
     * convenience cache, and keeping it at the root keeps it out of the
     * `accounts` subtree that cloud backup now carries wholesale — typed
     * addresses are the one thing here worth not putting in Google Drive.
     * The cost is that it is still shared between riders on one device,
     * which is a smaller leak accepted knowingly, not one overlooked.
     */
    val SCOPED_NAMES = listOf(
        "trips.json",
        "deleted_trips.json",
        "edited_modes.json",
        "traces.jsonl",
        "badges.json",
        "saved_places.json",
        "routes.json",
        "municipalities.json",
    )

    /**
     * Moves anything still at the root into the anonymous bucket.
     *
     * The condition is **per file, not per run**: there is no "have I
     * migrated yet" marker, so a run that dies halfway simply finishes on the
     * next launch. That also means a file already in the bucket wins over a
     * leftover at the root — the bucket is where the app has been writing
     * since the first successful pass, so the root copy is the stale one.
     *
     * That losing root copy is never deleted; it stays on disk indefinitely.
     * Deliberate, not an oversight — this function has no way to tell a
     * genuinely stale leftover from a file it doesn't fully understand, and
     * destroying a file it isn't certain about is worse than leaving a
     * harmless duplicate behind.
     *
     * Called eagerly from [Settings.init], before any store reads, so no
     * store ever has to look in two places for one file — which is why
     * there is no read-path fallback.
     */
    fun migrate(fs: FileSystem, root: Path) {
        try {
            val bucket = root / AccountScope.ACCOUNTS_DIR / AccountScope.ANONYMOUS
            for (name in SCOPED_NAMES) {
                val from = root / name
                if (!fs.exists(from)) continue
                val to = bucket / name
                if (fs.exists(to)) continue
                fs.createDirectories(bucket)
                fs.atomicMove(from, to)
            }
        } finally {
            // Set even on a partial run. What this flag guards is "migrate was
            // attempted before anything read a path", not "every file made it":
            // the loop's condition is per-file, so whatever did not move is
            // retried next launch. Setting it only on success would turn one
            // failed move into a permanent refusal to start.
            migrated = true
        }
    }

    /**
     * Hands the anonymous bucket to [key], if this is the first account ever
     * to sign in on this device.
     *
     * "First ever" needs no stored flag: it is whether `accounts/` holds
     * anything other than the anonymous bucket. Once some account owns data
     * here, a later sign-in gets its own empty bucket and whatever was
     * recorded signed out stays where it is — visible signed out, and never
     * uploaded to an account that did not record it.
     *
     * Returns whether it adopted, so a caller can tell "your rides are now
     * under your account" from "nothing to do".
     */
    fun adopt(fs: FileSystem, root: Path, key: String): Boolean {
        if (key.isEmpty()) return false
        val accounts = root / AccountScope.ACCOUNTS_DIR
        val anonymous = accounts / AccountScope.ANONYMOUS
        if (!fs.exists(anonymous)) return false
        val others = fs.list(accounts).filter { it.name != AccountScope.ANONYMOUS }
        if (others.isNotEmpty()) return false
        fs.atomicMove(anonymous, accounts / key)
        return true
    }

    /**
     * The whole on-disk half of a launch: get this install's files into a
     * bucket, then make sure the bucket belongs to whoever the install is
     * signed in as. [Settings.init] calls this once, before anything reads a
     * store path.
     *
     * One function rather than two adjacent calls at that call site because
     * the order between them is load-bearing and cannot be asserted there —
     * `Settings` needs platform prefs this module's test target does not
     * have — while here it is a property of a function that takes its
     * [FileSystem] as a parameter. [AccountScope.set] deliberately stays at
     * the call site: it is process state, not disk state, and it has to
     * happen after this returns for the reason [Auth.store] gives at its own
     * adopt/set pair.
     *
     * [storedKey] is `auth_scope_key` as persisted. It is non-empty on an
     * install that was **already signed in when it upgraded**: that install
     * has never run `Auth.exchangeCode`, so nothing has ever claimed the
     * anonymous bucket its files just moved into, and `Auth.refresh` writes
     * the key on the first token refresh. Without the [adopt] below, its next
     * launch points [accountDir] at a directory that has never existed and
     * the rider's entire history reads as empty — permanently, because the
     * first write into the new bucket makes [adopt]'s "no other bucket
     * exists" guard refuse `_local` from then on. Trips, traces and badges
     * would come back from the server union; `routes.json` is not synced at
     * all, so nothing restores it.
     *
     * A throwing [migrate] skips [adopt] rather than adopting a half-moved
     * bucket: whatever did not move is retried next launch, and it can only
     * be retried while the bucket it belongs in is still `_local`.
     */
    fun reconcileAtLaunch(fs: FileSystem, root: Path, storedKey: String) {
        migrate(fs, root)
        if (storedKey.isEmpty()) return
        adopt(fs, root, storedKey)
    }
}

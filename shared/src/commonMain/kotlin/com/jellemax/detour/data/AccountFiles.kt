package com.jellemax.detour.data

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
        val bucket = root / AccountScope.ACCOUNTS_DIR / AccountScope.ANONYMOUS
        for (name in SCOPED_NAMES) {
            val from = root / name
            if (!fs.exists(from)) continue
            val to = bucket / name
            if (fs.exists(to)) continue
            fs.createDirectories(bucket)
            fs.atomicMove(from, to)
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
}

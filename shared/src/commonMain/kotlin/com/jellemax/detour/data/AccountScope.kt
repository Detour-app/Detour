package com.jellemax.detour.data

import kotlin.concurrent.Volatile
import okio.ByteString.Companion.encodeUtf8

/**
 * Which rider the files on disk belong to.
 *
 * Every store used to write fixed names straight into the app-private
 * directory, so one device held one set of files no matter how many people
 * signed in on it — and [SyncClient.sync] then faithfully uploaded whatever
 * was there to whoever was signed in. See #73 for what that does to a rider
 * who lends their phone.
 *
 * The key is a hash rather than the name it came from, for two reasons that
 * both matter. A directory name has to be a legal filename whatever the
 * identity provider chose to issue, and this one ends up inside a Google
 * Drive backup (see `app/src/main/res/xml/data_extraction_rules.xml`), where
 * a rider's handle or email address has no business being.
 */
internal object AccountScope {

    /** The bucket for data recorded with nobody signed in. Adopted by the
     *  first account to sign in on a device — see [AccountFiles.adopt]. */
    const val ANONYMOUS = "_local"

    /** The one directory under the app-private root that holds per-account
     *  buckets. Backed up as a subtree, which is why nothing else may live
     *  in it. */
    const val ACCOUNTS_DIR = "accounts"

    /** Read by [accountDir] on whatever thread a store call arrives on, and
     *  written by [Auth.store]/[Auth.clear] on another. `@Volatile` for the
     *  same reason `Coverage.cache` is. */
    @Volatile
    private var key: String = ""

    /** The bucket to read and write in right now. */
    fun current(): String = key.ifEmpty { ANONYMOUS }

    /**
     * Points every subsequent [accountFile] at [newKey].
     *
     * A blank key falls back to [ANONYMOUS] rather than being ignored, and
     * the difference matters: ignoring it would leave the *previous* rider's
     * key in place, so a session that establishes with nothing to key on
     * would write the new rider's rides straight into the old rider's
     * directory — a worse version of the defect this exists to fix. Landing
     * in the anonymous bucket is recoverable and, because
     * [SyncClient.sync] refuses to upload from it while signed in, cannot
     * reach anyone's server account.
     */
    fun set(newKey: String) {
        key = newKey
    }

    /** Back to the anonymous bucket. Called on sign-out. */
    fun clear() = set("")

    /**
     * The directory name for a session, or `""` when there is nothing to key
     * on — which [SyncClient.sync] treats as a refusal rather than a reason
     * to fall back to [ANONYMOUS].
     *
     * [subject] is preferred because it survives a rider renaming themselves
     * server-side; [username] is the fallback for a provider that issues no
     * `sub`. Truncated to 16 hex characters: a directory name, not a
     * security boundary — the collision it has to avoid is between the
     * handful of accounts one phone sees, and 64 bits is far past that.
     */
    fun keyFrom(subject: String, username: String): String {
        val source = subject.ifEmpty { username }
        if (source.isEmpty()) return ""
        return source.encodeUtf8().sha256().hex().take(16)
    }
}

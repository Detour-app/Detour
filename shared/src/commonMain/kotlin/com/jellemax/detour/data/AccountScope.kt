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
     *  in it — and [AccountFiles.adopt] enforces that rather than trusting
     *  it, using [KEY_LENGTH] to tell a bucket from a stray entry. */
    const val ACCOUNTS_DIR = "accounts"

    /** How many hex characters a bucket name has. Shared with
     *  [AccountFiles.adopt], which recognises a bucket by it: two copies of
     *  this number would let the two disagree about what a bucket is. */
    const val KEY_LENGTH = 16

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
        return source.encodeUtf8().sha256().hex().take(KEY_LENGTH)
    }

    /**
     * Which bucket this launch owns, from what the secure store already holds.
     *
     * [storedKey] — `auth_scope_key` — wins whenever it is there, and on the
     * install this exists for it is not there. An install that was **already
     * signed in when it upgraded** has never run `Auth.exchangeCode`, and only
     * `Auth.store` writes that key, so nothing has written it yet;
     * `Auth.clear` then blanks it again on every sign-out, every 401 and every
     * issuer change. Waiting for the next token refresh to write it leaves
     * `accounts/_local` holding this rider's whole history **unclaimed** for
     * the rest of the session — and the next account to sign in adopts it,
     * renders A's trips, traces, badges and saved places as their own, and
     * uploads them into their own server account on the first sync. That is
     * #73, on the majority upgrade path, from inside the change that exists to
     * close it.
     *
     * Deriving it needs nothing new. The token is already on disk and
     * `Auth.subjectFrom` is an unsigned base64 decode, so an access token long
     * past its fifteen minutes still yields the right `sub`; [username] is the
     * same fallback [keyFrom] always had, for a provider that issues none.
     *
     * [refreshToken] is the gate because it is the field that answers "is
     * there a session at all" (see `Settings.refreshToken`), and `Auth.clear`
     * blanks it in the same write that blanks the key. Without it a
     * signed-out install would derive a key from the departed rider's stale
     * token and adopt their bucket on their behalf.
     *
     * Every input arrives by parameter rather than being read here: the caller
     * is `Settings.init`, which needs platform prefs this module's test target
     * does not have, and the whole point is that this half is assertable
     * anyway. Same seam, same reason, as [AccountFiles.reconcileAtLaunch].
     */
    fun keyAtLaunch(
        storedKey: String,
        refreshToken: String,
        accessToken: String,
        username: String,
    ): String {
        if (storedKey.isNotEmpty()) return storedKey
        if (refreshToken.isBlank()) return ""
        return keyFrom(subject = Auth.subjectFrom(accessToken), username = username)
    }
}

package com.jellemax.detour.presentation

import com.jellemax.detour.data.AuthException
import com.jellemax.detour.data.HttpStatusException
import com.jellemax.detour.data.ServerConfig
import okio.IOException

/**
 * What the three status rows at the top of the Servers & sync spoke say.
 *
 * State, not explanation: each string answers "what is set up right now", and
 * the copy that says why it matters lives behind the spoke's "Learn more".
 * Follows [SettingsHubState] — already formatted, no Android types, so the
 * assertions live in `commonTest` rather than in a Compose harness this repo
 * does not have.
 */
data class ServersSyncStatus(
    val server: String,
    val sync: String,
    val backup: String,
)

/**
 * The rider-visible host of an address, or the address itself when it does not
 * parse as one. Deliberately not `Url`/`URI`: commonMain has neither, and a
 * half-typed address ("nas.local", "https://") must still render as *something*
 * rather than throw inside a status row.
 *
 * Userinfo is dropped because an address pasted with credentials in it would
 * otherwise put them on screen; the port is kept, because "nas.local:8989" is
 * how a rider recognises their own box.
 */
private fun hostOf(address: String): String {
    val host = address.trim()
        .substringAfter("://")
        .substringBefore('/')
        .substringBefore('?')
        .substringAfterLast('@')
    return host.ifBlank { address.trim() }
}

/**
 * Pure map from what is stored to the three status lines.
 *
 * [custom] is the whole config rather than its `url`, because a split
 * deployment can leave `url` empty and still be configured — reading only
 * `url` would report "Built-in server" to someone who typed three addresses.
 *
 * [nowMs] is a parameter for the same reason it is one on [relativeAge]: a
 * function that reads the clock cannot be asserted on.
 */
fun serversSyncStatusFrom(
    custom: ServerConfig?,
    builtInAvailable: Boolean,
    authUsername: String,
    lastSyncMs: Long,
    nowMs: Long,
): ServersSyncStatus {
    val address = listOfNotNull(
        custom?.url, custom?.apiUrl, custom?.routingUrl, custom?.geocoderUrl,
    ).firstOrNull { it.isNotBlank() }
    return ServersSyncStatus(
        server = when {
            address != null -> hostOf(address)
            builtInAvailable -> "Built-in server"
            else -> "Public servers only"
        },
        sync = when {
            authUsername.isBlank() -> "Not signed in"
            lastSyncMs <= 0L -> "Signed in as $authUsername · never synced"
            else -> "Signed in as $authUsername · synced ${relativeAge(lastSyncMs, nowMs)}"
        },
        // There is no stored "last exported" stamp anywhere in the app, so this
        // row reports what an export would capture rather than when one last
        // happened. Adding the stamp is a storage change and out of this
        // change's scope.
        backup = if (address != null || builtInAvailable) "Server address ready to export"
        else "No address to export yet",
    )
}

/**
 * The line shown after a sync, an export or an import that threw.
 *
 * Replaces `"… failed: ${e.message}"`, which handed the rider "HTTP 502",
 * "Unable to resolve host …" or a kotlinx-serialization parse error and asked
 * them to work out which of their five addresses caused it. Every branch here
 * is a thing the rider can act on; nothing carries the exception's own text,
 * which is also why this is worth a test — the mapping is the whole feature.
 *
 * [action] is the verb ("Sync", "Export", "Import") rather than three
 * functions: the reasons do not differ by action, only the subject does.
 */
fun failureText(action: String, e: Throwable): String {
    val reason = when {
        // Before HttpStatusException: AuthException is one too, and it is the
        // only status the rider fixes by signing in rather than by retrying.
        e is AuthException -> "the sign-in has expired. Sign in again."
        e is HttpStatusException && e.code in 401..403 -> "the server refused the sign-in."
        e is HttpStatusException && e.code == 404 -> "the server has nothing at that address."
        e is HttpStatusException && e.code >= 500 -> "the server hit a problem. Try again later."
        e is HttpStatusException -> "the server answered ${e.code}."
        e is IOException -> "it could not be opened. Check your connection and try again."
        else -> "the contents were not what this app expected."
    }
    return "$action failed: $reason"
}

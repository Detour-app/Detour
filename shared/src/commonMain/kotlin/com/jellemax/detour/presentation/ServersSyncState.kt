package com.jellemax.detour.presentation

import com.jellemax.detour.data.ServerConfig
import com.jellemax.detour.data.SpinFailure
import com.jellemax.detour.data.failureReason
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
data class ServersSyncState(
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
fun serversSyncStateFrom(
    custom: ServerConfig?,
    builtInAvailable: Boolean,
    authUsername: String,
    lastSyncMs: Long,
    nowMs: Long,
): ServersSyncState {
    val address = listOfNotNull(
        custom?.url, custom?.apiUrl, custom?.routingUrl, custom?.geocoderUrl,
    ).firstOrNull { it.isNotBlank() }
    return ServersSyncState(
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
 * them to work out which of their five addresses caused it. The reasons are
 * [failureReason], in data/ so a loop spin's warning can use them too.
 *
 * [action] is the verb ("Sync", "Export", "Import") rather than three
 * functions: the reasons do not differ by action, only the subject does.
 */
fun failureText(action: String, e: Throwable): String = "$action failed: ${failureReason(e)}"

/**
 * [failureText] for an export or import through the file picker, where there
 * is no network: an [IOException] there is the picked file refusing to open,
 * not the connection, so it must not send the rider to check their signal.
 */
fun fileFailureText(action: String, e: Throwable): String =
    if (e is IOException) "$action failed: the file could not be opened. Pick it again or choose another."
    else failureText(action, e)

/**
 * [failureText] for a spin, whose own dead ends ("No roads found within
 * radius", a fallback timeout) are rider-facing sentences thrown as
 * [SpinFailure]: those keep their words, and only everything else — a network
 * or parser failure — is mapped (#487).
 */
fun spinFailureText(e: Throwable): String =
    if (e is SpinFailure) e.message.orEmpty() else failureText("Spin", e)

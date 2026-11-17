package com.jellemax.detour.ui

import android.util.Log
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The app-wide way to tell the rider something happened — `docs/guidelines/multiplatform.md`
 * §5.4's bucket 2: an action that failed ([showError], with a Retry where re-running the same
 * call is safe), or one that worked and has news ([showNotice]).
 *
 * An interface because it has two implementations: [rememberAppMessages]'s snackbar, which
 * `AppRoot` provides, and [LocalAppMessages]'s default, which logs and drops so a preview or a
 * composable outside `AppRoot` never crashes for want of a host.
 */
interface AppMessages {
    /**
     * [message] is a rider sentence, never an exception's own text (§5.4). [retry] is offered
     * as a Retry action; pass it only when running the same call again is safe (a load, a sync,
     * a send), never for a delete. It runs after the snackbar is tapped, on the app's scope —
     * the screen that asked may be gone by then, so it is `suspend` and runs in that scope's
     * coroutine rather than launching on the screen's.
     */
    fun showError(message: String, retry: (suspend () -> Unit)? = null)

    /** Success or news, not an error: "Synced", "Not asked: …". */
    fun showNotice(message: String)
}

/**
 * The [AppMessages] of the app, provided once by `AppRoot` over `NavDisplay`.
 *
 * A `CompositionLocal` is a design decision here (`state-holders.md` §14.2, detekt's
 * `CompositionLocalAllowlist`), so the argument: the messages are app-scoped — one host, under
 * every destination, outliving any screen — and the §5.4 migration has about fifteen screens
 * read them. As a parameter it would thread through every `NavDisplay` entry and into each
 * screen's sub-composables that report a failure, which is the drilling §14.2's table rules
 * out; and the default is sensible (log and drop), which a tree-scoped local needs.
 *
 * `staticCompositionLocalOf`: `AppRoot` provides one instance for the Activity's life, so reads
 * need no tracking. Read it in composition and capture it; do not re-read it in a callback.
 */
val LocalAppMessages = staticCompositionLocalOf<AppMessages> { DroppedAppMessages }

/**
 * The snackbar-backed [AppMessages]. [scope] must outlive the screens that call it — `AppRoot`'s
 * — because a notice can fire after its screen left (`PlannedRideStart`'s refused invitees).
 */
@Composable
fun rememberAppMessages(host: SnackbarHostState, scope: CoroutineScope): AppMessages =
    remember(host, scope) { SnackbarAppMessages(host, scope) }

private class SnackbarAppMessages(
    private val host: SnackbarHostState,
    private val scope: CoroutineScope,
) : AppMessages {
    override fun showError(message: String, retry: (suspend () -> Unit)?) {
        if (retry == null) {
            show(message)
            return
        }
        // A second failed tap while the first is up would queue a copy behind it.
        if (host.currentSnackbarData?.visuals?.message == message) return
        scope.launch {
            val result = host.showSnackbar(
                message = message,
                actionLabel = RETRY_LABEL,
                // An action makes the default Indefinite: it would never leave, and every
                // later message would queue behind it (as in MapPermissions' location snackbar).
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) retry()
        }
    }

    override fun showNotice(message: String) = show(message)

    private fun show(message: String) {
        scope.launch { host.showSnackbar(message) }
    }
}

private object DroppedAppMessages : AppMessages {
    override fun showError(message: String, retry: (suspend () -> Unit)?) {
        Log.w(TAG, "no AppMessages provided, dropped error: $message")
    }

    override fun showNotice(message: String) {
        Log.i(TAG, "no AppMessages provided, dropped notice: $message")
    }
}

/**
 * The actions a screen runs against one store, so that store's one-shot `actionError` can say
 * which call a Retry would re-run. The store records *what* failed, not *which* call; this
 * remembers the last action started through [launch], and whether re-running it is safe.
 */
class StoreActions internal constructor() {
    internal var lastRetry: (suspend () -> Unit)? = null
        private set

    /**
     * Runs [action] on [scope] (the screen's, as before). [retryable]: running it again with the
     * same inputs is safe — a send, an accept, a create — so its failure offers Retry; false for
     * a delete, a leave or a decline.
     */
    fun launch(scope: CoroutineScope, retryable: Boolean, action: suspend () -> Unit) {
        lastRetry = action.takeIf { retryable }
        scope.launch { action() }
    }
}

/**
 * Shows a store's one-shot [actionError] through [LocalAppMessages] (§5.4's bucket 2), then
 * [clear]s it, with Retry when the action behind it was started as retryable. A Retry that
 * fails again sets the error again and shows again while the screen is up; once the screen has
 * gone nobody reads it, and the next load's `starting()` drops it.
 */
@Composable
fun rememberStoreActions(actionError: String?, clear: () -> Unit): StoreActions {
    val messages = LocalAppMessages.current
    val actions = remember { StoreActions() }
    val currentClear by rememberUpdatedState(clear)
    LaunchedEffect(actionError) {
        if (actionError == null) return@LaunchedEffect
        messages.showError(actionError, actions.lastRetry)
        currentClear()
    }
    return actions
}

/**
 * A reload that failed while the last good content is still on screen: not §5.4's "never
 * arrived" (that is [ErrorState], in place of the content), so the content stays and the
 * failure goes to the snackbar with [retry]. [loadError] is not one-shot — it stays until the
 * next load starts — so re-entering the screen inside the store's freshness window says it again.
 */
@Composable
fun StaleLoadErrorEffect(loadError: String?, hasContent: Boolean, retry: suspend () -> Unit) {
    val messages = LocalAppMessages.current
    val currentRetry by rememberUpdatedState(retry)
    LaunchedEffect(loadError, hasContent) {
        if (loadError != null && hasContent) messages.showError(loadError) { currentRetry() }
    }
}

private const val TAG = "DetourAppMessages"

/** The snackbar action for [AppMessages.showError]'s retry; matches [ErrorState]'s button. */
internal const val RETRY_LABEL = "Retry"

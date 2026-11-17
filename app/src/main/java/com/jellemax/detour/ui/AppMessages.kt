package com.jellemax.detour.ui

import android.util.Log
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
     * the screen that asked may be gone by then, so it must not launch on that screen's scope.
     */
    fun showError(message: String, retry: (() -> Unit)? = null)

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
    override fun showError(message: String, retry: (() -> Unit)?) {
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
    override fun showError(message: String, retry: (() -> Unit)?) {
        Log.w(TAG, "no AppMessages provided, dropped error: $message")
    }

    override fun showNotice(message: String) {
        Log.i(TAG, "no AppMessages provided, dropped notice: $message")
    }
}

private const val TAG = "DetourAppMessages"

/** The snackbar action for [AppMessages.showError]'s retry; matches [ErrorState]'s button. */
internal const val RETRY_LABEL = "Retry"

package com.jellemax.detour.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.convoy.ConvoyLiveService
import com.jellemax.detour.data.Account
import com.jellemax.detour.data.FeatureFlags
import com.jellemax.detour.data.refreshQuietly
import com.jellemax.detour.presentation.avatarInitialOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The rider's account: avatar, display name, sign out, delete account.
 *
 * v1 scope only — the prototype's `isProfile` block also has an email row, a
 * "verified" line, avatar editing, and "Change password" / "Two-factor &
 * sessions" rows that open an account console. None of that exists here yet:
 * `Account` exposes no email, only [Account.username], and there is no
 * account-console URL to send the rider to. Building those rows against
 * placeholder data would ship something that looks actionable and isn't,
 * which is worse than leaving them out — they're follow-ups, not this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(onBack: () -> Unit, onSignedOut: () -> Unit) {
    val username by Account.username.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var signingOut by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var signOutError by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { SubScreenTopBar("Profile & account", onBack, scrollBehavior) },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ListCard {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            avatarInitialOf(username),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        username.ifBlank { "Signed out" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            OutlinedButton(
                enabled = !signingOut,
                onClick = { confirmSignOut = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, Modifier.size(18.dp))
                Text(
                    "Sign out",
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (signOutError.isNotEmpty()) {
                Text(signOutError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            // Quieter than "Sign out" on purpose: the rare, irreversible action
            // is not the one the eye should land on. Play requires it in-app (#700).
            TextButton(
                enabled = !signingOut,
                onClick = { confirmDelete = true },
                modifier = Modifier.align(Alignment.CenterHorizontally),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Delete account", style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    // Both dialogs flip the same busy/error pair: one request at a time, one message line.
    val onStart = { signingOut = true; signOutError = "" }
    val onFail = { message: String -> signingOut = false; signOutError = message }
    if (confirmSignOut) {
        SignOutDialog(
            scope, onStart, onSignOut = onSignedOut, onFail = onFail, onDismiss = { confirmSignOut = false },
        )
    }
    if (confirmDelete) {
        DeleteAccountDialog(
            scope, onStart, onDelete = onSignedOut, onFail = onFail, onDismiss = { confirmDelete = false },
        )
    }
}

/**
 * The confirm for "Sign out", and the call behind it. [scope] is the screen's
 * for the reason [DeleteAccountDialog] gives.
 */
@Composable
private fun SignOutDialog(
    scope: CoroutineScope,
    onStart: () -> Unit,
    onSignOut: () -> Unit,
    onFail: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    ConfirmDialog(
        title = "Sign out?",
        text = "Signing back in takes a trip through your browser. Trips, explored " +
            "area and badges stay on this device.",
        confirmLabel = "Sign out",
        onConfirm = {
            // A signed-out session must not keep broadcasting: leaves the
            // live socket with no valid identity behind it otherwise. Same
            // order and reasoning as FriendsScreen's own sign-out.
            ConvoyLiveService.stop(context)
            onStart()
            scope.launch {
                // Only [Auth.clear] and the push-token write can surface a
                // failure here: the revoke call to the realm is swallowed
                // inside Auth.signOut by design, so a throw means the
                // *local* sign-out did not finish. Navigating on that would
                // leave the rider on the map still holding a live session.
                runCatching { Account.signOut() }
                    // Flags: the server's signed-out defaults, not compiled ones.
                    .onSuccess { FeatureFlags.refreshQuietly(); onSignOut() }
                    .onFailure {
                        // runCatching swallows cancellation too; leaving
                        // the screen is not a failed sign-out to report.
                        if (it is CancellationException) throw it
                        onFail(
                            "Sign-out did not finish — you may still be " +
                                "signed in on this device. Try again.",
                        )
                    }
            }
        },
        onDismiss = onDismiss,
    )
}

/**
 * The confirm for "Delete account" (#700), and the call behind it.
 *
 * [scope] is the screen's, not one remembered here: [ConfirmDialog] dismisses
 * before it confirms, so a scope owned by this dialog would be cancelled the
 * moment the delete started.
 */
@Composable
private fun DeleteAccountDialog(
    scope: CoroutineScope,
    onStart: () -> Unit,
    onDelete: () -> Unit,
    onFail: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    ConfirmDialog(
        title = "Delete your account?",
        text = "This deletes your account on the server: trips, explored area, badges, " +
            "friends, circles and shared routes. The copies on this device are erased " +
            "too. This cannot be undone.",
        confirmLabel = "Delete account",
        onConfirm = {
            // Same reason as sign-out: no live socket for an identity that is going away.
            ConvoyLiveService.stop(context)
            onStart()
            scope.launch {
                // A throw means the request failed or went unanswered: the rider is
                // still signed in, and a retry is safe (Account.deleteAccount). IO
                // because the tail erases the account's files (§11).
                runCatching { withContext(Dispatchers.IO) { Account.deleteAccount() } }
                    .onSuccess { FeatureFlags.refreshQuietly(); onDelete() }
                    .onFailure {
                        if (it is CancellationException) throw it
                        onFail("Your account may not have been deleted — check your connection and try again.")
                    }
            }
        },
        onDismiss = onDismiss,
    )
}

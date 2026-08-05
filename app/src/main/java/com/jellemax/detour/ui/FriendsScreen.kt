package com.jellemax.detour.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.convoy.ConvoyLiveService
import com.jellemax.detour.data.Account
import com.jellemax.detour.data.BadgeStore
import com.jellemax.detour.data.Convoy
import com.jellemax.detour.data.Convoys
import com.jellemax.detour.data.Coverage
import com.jellemax.detour.data.FriendLists
import com.jellemax.detour.data.FriendStats
import com.jellemax.detour.data.Friends
import com.jellemax.detour.data.PendingReset
import com.jellemax.detour.data.RiderStats
import com.jellemax.detour.data.SyncClient
import com.jellemax.detour.net.ConvoyLiveClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val username by Account.username.collectAsStateWithLifecycle()
    var addOpen by remember { mutableStateOf(false) }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SubScreenTopBar(
                if (username.isBlank()) "Account" else "Friends",
                onBack, scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!SyncClient.configured(context)) {
                Text(
                    "No sync server configured. Set one in Settings first — " +
                        "friends live on your own server.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }
            if (username.isBlank()) {
                SignInSection()
            } else {
                FriendsSection(username, onAddFriend = { addOpen = true })
                ConvoysSection()
            }
        }
    }

    if (addOpen) {
        AddFriendDialog(onDismiss = { addOpen = false })
    }
}

@Composable
private fun SignInSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var forgotOpen by remember { mutableStateOf(false) }
    var resetOpen by remember { mutableStateOf(false) }
    // A tapped link from a reset mail lands here; opening the form with the
    // code already in it is the whole point of the deep link.
    val linkToken by PendingReset.token.collectAsStateWithLifecycle()
    LaunchedEffect(linkToken) { if (linkToken.isNotBlank()) resetOpen = true }

    fun run(block: () -> Unit) {
        busy = true
        error = null
        note = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                error = e.message ?: "Failed"
            }
            busy = false
        }
    }

    Text(
        "Sign in to sync your rides and compare stats with friends. " +
            "Your trips and explored map stay private — friends only ever see " +
            "totals and badges.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = user, onValueChange = { user = it },
        label = { Text("Username") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = password, onValueChange = { password = it },
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = invite, onValueChange = { invite = it },
        label = { Text("Invite code (only if your server asks)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = email, onValueChange = { email = it },
        label = { Text("Email (new accounts, optional)") },
        supportingText = { Text("Only used to mail you a link if you forget your password.") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    error?.let {
        Text(it, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
    }
    note?.let {
        Text(it, style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { run { Account.login(context, user.trim(), password) } },
            enabled = !busy && user.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.weight(1f),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("Sign in")
        }
        OutlinedButton(
            onClick = {
                run { Account.register(context, user.trim(), password, invite.trim(), email.trim()) }
            },
            enabled = !busy && user.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.weight(1f),
        ) { Text("Create account") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { forgotOpen = true }, enabled = !busy) {
            Text("Forgot password")
        }
        TextButton(onClick = { resetOpen = true }, enabled = !busy) {
            Text("I have a reset code")
        }
    }
    Text(
        "Passwords must be at least 8 characters.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (forgotOpen) {
        ForgotPasswordDialog(
            initial = user.trim(),
            onDismiss = { forgotOpen = false },
            onSent = {
                forgotOpen = false
                note = "If that account has an email on file, a reset link is on its way. " +
                    "Open it on this phone, or paste the code under \"I have a reset code\"."
            },
        )
    }
    if (resetOpen) {
        ResetPasswordDialog(
            initialToken = linkToken,
            onDismiss = {
                resetOpen = false
                PendingReset.clear()
            },
            onDone = {
                resetOpen = false
                PendingReset.clear()
                password = ""
                note = "Password changed. Sign in with the new one — every other device " +
                    "was signed out."
            },
        )
    }
}

/** Asks the server to mail a reset link. The server answers the same either
 *  way, so this can only ever report "sent, if there was anywhere to send". */
@Composable
private fun ForgotPasswordDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSent: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var who by remember { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Forgot password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Your server mails the link, so this only works if your account " +
                        "has an email address on it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = who, onValueChange = { who = it },
                    label = { Text("Username or email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && who.isNotBlank(),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                Account.forgotPassword(context, who.trim())
                            }
                            onSent()
                        } catch (e: Exception) {
                            error = e.message ?: "Could not reach the server"
                        }
                        busy = false
                    }
                },
            ) { Text("Send link") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Spends a reset code — either the one a deep link arrived with, or one
 *  pasted out of the mail by hand when the link wasn't tappable. */
@Composable
private fun ResetPasswordDialog(
    initialToken: String,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(initialToken) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set a new password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text("Reset code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("New password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && token.isNotBlank() && password.length >= 8,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                Account.resetPassword(context, token.trim(), password)
                            }
                            onDone()
                        } catch (e: Exception) {
                            error = e.message ?: "Could not reach the server"
                        }
                        busy = false
                    }
                },
            ) { Text("Change password") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FriendsSection(username: String, onAddFriend: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lists by remember { mutableStateOf<FriendLists?>(null) }
    var stats by remember { mutableStateOf<List<FriendStats>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Bumped after every mutation so the lists below reload.
    var reloads by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloads) {
        try {
            val loaded = withContext(Dispatchers.IO) { Friends.lists(context) to Friends.stats(context) }
            lists = loaded.first
            stats = loaded.second
            error = null
        } catch (e: Exception) {
            error = e.message ?: "Could not reach the server"
        }
    }

    // Own totals, computed the same way BadgesScreen and the Hub do — the
    // server never sends them back to us, only to friends, so this is the
    // only way to put "me" in my own leaderboard.
    val own by produceState<FriendStats?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            val coverage = Coverage.compute(context)
            val riderStats = BadgeStore.stats(context, coverage)
            val badgeIds = BadgeStore.refresh(context, riderStats).states
                .filter { it.earned }.map { it.def.id }
            FriendStats(username, riderStats, badgeIds)
        }
    }

    /** Runs a mutation, then reloads; never leaves [busy] stuck on failure. */
    fun act(scope: CoroutineScope, block: () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                reloads++
            } catch (e: Exception) {
                error = e.message ?: "Failed"
            }
            busy = false
        }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Signed in as", style = MaterialTheme.typography.labelSmall)
                Text(username, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = {
                // A signed-out session must not keep broadcasting: leaves the
                // live socket with no valid identity behind it otherwise.
                ConvoyLiveService.stop(context)
                act(scope) { Account.signOut(context) }
            }) {
                Text("Sign out")
            }
        }
    }

    error?.let {
        Text(it, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
    }

    val loaded = lists
    if (loaded == null) {
        CircularProgressIndicator()
        return
    }

    // Requests first — answering them is the one thing here that's actually
    // time-sensitive; the leaderboard just sits and waits to be looked at.
    if (loaded.incoming.isNotEmpty()) {
        Text("Requests", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        for (name in loaded.incoming) {
            RequestRow(
                name = name,
                busy = busy,
                onAccept = { act(scope) { Friends.respond(context, name, true) } },
                onDecline = { act(scope) { Friends.respond(context, name, false) } },
            )
        }
    }

    if (loaded.outgoing.isNotEmpty()) {
        Text(
            "Waiting on: ${loaded.outgoing.joinToString(", ")}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Leaderboard", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        TextButton(onClick = onAddFriend) {
            Icon(Icons.Outlined.Add, contentDescription = null, Modifier.size(18.dp))
            Text("Add a friend")
        }
    }
    if (stats.isEmpty()) {
        Text(
            "No friends yet. Add one above — you'll see their totals, " +
                "never their routes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        val ranked = (stats + listOfNotNull(own))
            .sortedByDescending { it.stats.totalDistanceMeters }
        ranked.forEachIndexed { i, friend ->
            LeaderboardRow(rank = i + 1, friend = friend, isMe = friend.username == username)
        }
    }
}

@Composable
private fun RequestRow(name: String, busy: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
    Card {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(
                    enabled = !busy,
                    onClick = onAccept,
                    modifier = Modifier.size(30.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                ) { Icon(Icons.Outlined.Check, contentDescription = "Accept $name", Modifier.size(16.dp)) }
                IconButton(
                    enabled = !busy,
                    onClick = onDecline,
                    modifier = Modifier.size(30.dp),
                ) { Icon(Icons.Outlined.Close, contentDescription = "Decline $name", Modifier.size(16.dp)) }
            }
        }
    }
}

/** One leaderboard row: rank, initial avatar, name, trailing distance. The
 *  signed-in user's own row (synthesized locally, see [own] above) gets a
 *  primary outline and a tinted background so it's easy to find at a glance. */
@Composable
private fun LeaderboardRow(rank: Int, friend: FriendStats, isMe: Boolean) {
    Card(
        modifier = if (isMe) {
            Modifier.border(
                1.5.dp,
                MaterialTheme.colorScheme.primary,
                RoundedCornerShape(12.dp),
            )
        } else Modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (isMe) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$rank",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (rank == 1) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(20.dp),
            )
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    friend.username.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    friend.username + if (isMe) " (you)" else "",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${friend.stats.tripCount} rides · ${friend.badgeIds.size} badges",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "%,.0f km".format(friend.stats.totalDistanceMeters / 1000),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** "Add a friend" moved behind the top bar's + — this dialog is the whole form. */
@Composable
private fun AddFriendDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a friend") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Their username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank(),
                onClick = {
                    val target = name.trim()
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) { Friends.request(context, target) }
                            status = if (result == "accepted") "You are now friends with $target"
                                else "Request sent to $target"
                        } catch (e: Exception) {
                            error = e.message ?: "Failed"
                        }
                        busy = false
                    }
                },
            ) { if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * Convoys: the "granted access" gate for live location + push-to-talk (see
 * server/sync/sync_server.py's convoy tables). Inviting requires already
 * being friends — the server enforces it, this just surfaces the error if
 * you try anyway. `liveConvoyId` is read from [ConvoyLiveClient] itself
 * (not local UI state) so this screen always reflects whether
 * [ConvoyLiveService] is actually running — it keeps running, and the map's
 * friend markers keep updating, even after this screen is closed, until
 * "Stop live" or leaving the convoy actually stops it.
 */
@Composable
private fun ConvoysSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var convoys by remember { mutableStateOf<List<Convoy>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloads by remember { mutableIntStateOf(0) }
    var createOpen by remember { mutableStateOf(false) }
    var inviteFor by remember { mutableStateOf<Convoy?>(null) }
    val liveConvoyId by ConvoyLiveClient.activeConvoyId.collectAsStateWithLifecycle()

    // Mic permission is asked for before starting the service, not after -
    // ConvoyLiveService can only declare the foreground microphone type when
    // it's actually held, so asking late would mean going live without PTT
    // ever working until the next relaunch. Starting regardless of the
    // result (granted or denied) still gets you live location; PTT just
    // won't work if denied.
    var pendingLiveConvoyId by remember { mutableStateOf<Int?>(null) }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        pendingLiveConvoyId?.let { ConvoyLiveService.start(context, it) }
        pendingLiveConvoyId = null
    }
    fun goLive(convoyId: Int) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            ConvoyLiveService.start(context, convoyId)
        } else {
            pendingLiveConvoyId = convoyId
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(reloads) {
        try {
            convoys = withContext(Dispatchers.IO) { Convoys.list(context) }
            error = null
        } catch (e: Exception) {
            error = e.message ?: "Could not reach the server"
        }
    }

    fun act(block: () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                reloads++
            } catch (e: Exception) {
                error = e.message ?: "Failed"
            }
            busy = false
        }
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Convoys", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        TextButton(onClick = { createOpen = true }) {
            Icon(Icons.Outlined.Add, contentDescription = null, Modifier.size(18.dp))
            Text("New convoy")
        }
    }

    error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

    if (convoys.isEmpty()) {
        Text(
            "No convoys yet. Start one to share live location and push-to-talk " +
                "with friends who join it — nothing is shared until they accept.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        for (convoy in convoys) {
            ConvoyRow(
                convoy = convoy,
                busy = busy,
                live = liveConvoyId == convoy.id,
                onAccept = { act { Convoys.respond(context, convoy.id, true) } },
                onDecline = { act { Convoys.respond(context, convoy.id, false) } },
                onLeave = {
                    if (liveConvoyId == convoy.id) ConvoyLiveService.stop(context)
                    act { Convoys.leave(context, convoy.id) }
                },
                onInvite = { inviteFor = convoy },
                onToggleLive = {
                    if (liveConvoyId == convoy.id) {
                        ConvoyLiveService.stop(context)
                    } else {
                        goLive(convoy.id)
                    }
                },
            )
        }
    }

    if (createOpen) {
        CreateConvoyDialog(
            onDismiss = { createOpen = false },
            onCreate = { name -> act { Convoys.create(context, name) }; createOpen = false },
        )
    }
    inviteFor?.let { convoy ->
        InviteToConvoyDialog(
            convoy = convoy,
            onDismiss = { inviteFor = null },
            onInvite = { target -> act { Convoys.invite(context, convoy.id, target) }; inviteFor = null },
        )
    }
}

@Composable
private fun ConvoyRow(
    convoy: Convoy,
    busy: Boolean,
    live: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onLeave: () -> Unit,
    onInvite: () -> Unit,
    onToggleLive: () -> Unit,
) {
    Card {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(convoy.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                if (convoy.status == "invited") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(
                            enabled = !busy,
                            onClick = onAccept,
                            modifier = Modifier.size(30.dp),
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        ) { Icon(Icons.Outlined.Check, contentDescription = "Accept ${convoy.name}", Modifier.size(16.dp)) }
                        IconButton(enabled = !busy, onClick = onDecline, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Outlined.Close, contentDescription = "Decline ${convoy.name}", Modifier.size(16.dp))
                        }
                    }
                }
            }
            Text(
                convoy.members.joinToString(", ") {
                    it.username + if (it.status == "invited") " (invited)" else ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (convoy.status == "accepted") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = onToggleLive) {
                        Icon(
                            if (live) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                            contentDescription = null, Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (live) "Stop live" else "Go live")
                    }
                    OutlinedButton(enabled = !busy, onClick = onInvite) { Text("Invite") }
                    TextButton(enabled = !busy, onClick = onLeave) { Text("Leave") }
                }
            }
        }
    }
}

@Composable
private fun CreateConvoyDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New convoy") },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Convoy name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim()) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Invite by username, same shape as [AddFriendDialog] — the server rejects
 *  anyone not already a friend, so there's nothing else to validate here. */
@Composable
private fun InviteToConvoyDialog(convoy: Convoy, onDismiss: () -> Unit, onInvite: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invite to ${convoy.name}") },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Friend's username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onInvite(name.trim()) }) { Text("Invite") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

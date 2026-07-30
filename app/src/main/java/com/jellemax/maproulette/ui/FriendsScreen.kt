package com.jellemax.maproulette.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.maproulette.data.Account
import com.jellemax.maproulette.data.BadgeStore
import com.jellemax.maproulette.data.Coverage
import com.jellemax.maproulette.data.FriendLists
import com.jellemax.maproulette.data.FriendStats
import com.jellemax.maproulette.data.Friends
import com.jellemax.maproulette.data.RiderStats
import com.jellemax.maproulette.data.SyncClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(onBack: () -> Unit) {
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
            if (!SyncClient.configured) {
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
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun run(block: () -> Unit) {
        busy = true
        error = null
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
    error?.let {
        Text(it, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
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
            onClick = { run { Account.register(context, user.trim(), password, invite.trim()) } },
            enabled = !busy && user.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.weight(1f),
        ) { Text("Create account") }
    }
    Text(
        "Passwords must be at least 8 characters.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            TextButton(onClick = { act(scope) { Account.signOut(context) } }) {
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

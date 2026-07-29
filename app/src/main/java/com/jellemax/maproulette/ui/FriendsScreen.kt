package com.jellemax.maproulette.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.maproulette.data.Account
import com.jellemax.maproulette.data.FriendLists
import com.jellemax.maproulette.data.FriendStats
import com.jellemax.maproulette.data.Friends
import com.jellemax.maproulette.data.SyncClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(onBack: () -> Unit) {
    val username by Account.username.collectAsStateWithLifecycle()

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
            if (username.isBlank()) SignInSection() else FriendsSection(username)
        }
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
private fun FriendsSection(username: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lists by remember { mutableStateOf<FriendLists?>(null) }
    var stats by remember { mutableStateOf<List<FriendStats>>(emptyList()) }
    var addName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    // Bumped after every mutation so the lists below reload.
    var reloads by remember { mutableIntStateOf(0) }

    androidx.compose.runtime.LaunchedEffect(reloads) {
        try {
            val loaded = withContext(Dispatchers.IO) { Friends.lists(context) to Friends.stats(context) }
            lists = loaded.first
            stats = loaded.second
            error = null
        } catch (e: Exception) {
            error = e.message ?: "Could not reach the server"
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
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

    Text("Add a friend", style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = addName, onValueChange = { addName = it },
            label = { Text("Their username") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(
            enabled = !busy && addName.isNotBlank(),
            onClick = {
                val target = addName.trim()
                act(scope) {
                    val result = Friends.request(context, target)
                    status = if (result == "accepted") "You are now friends with $target"
                        else "Request sent to $target"
                }
                addName = ""
            },
        ) { Text("Send") }
    }

    val loaded = lists
    if (loaded == null) {
        CircularProgressIndicator()
        return
    }

    if (loaded.incoming.isNotEmpty()) {
        Text("Requests", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        for (name in loaded.incoming) {
            Card {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    Row {
                        IconButton(
                            enabled = !busy,
                            onClick = { act(scope) { Friends.respond(context, name, true) } },
                        ) { Icon(Icons.Default.Check, contentDescription = "Accept $name") }
                        IconButton(
                            enabled = !busy,
                            onClick = { act(scope) { Friends.respond(context, name, false) } },
                        ) { Icon(Icons.Default.Close, contentDescription = "Decline $name") }
                    }
                }
            }
        }
    }

    if (loaded.outgoing.isNotEmpty()) {
        Text(
            "Waiting on: ${loaded.outgoing.joinToString(", ")}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Text("Leaderboard", style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary)
    if (stats.isEmpty()) {
        Text(
            "No friends yet. Send a request above — you'll see their totals, " +
                "never their routes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        for (friend in stats) {
            FriendCard(friend, busy) { act(scope) { Friends.remove(context, friend.username) } }
        }
    }
}

@Composable
private fun FriendCard(friend: FriendStats, busy: Boolean, onRemove: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(friend.username, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                TextButton(onClick = onRemove, enabled = !busy) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                FriendStat("Distance", "%,.0f km".format(friend.stats.totalDistanceMeters / 1000))
                FriendStat("Top speed", "%.0f km/h".format(friend.stats.topSpeedKmh))
                FriendStat("Rides", "${friend.stats.tripCount}")
                FriendStat("Badges", "${friend.badgeIds.size}")
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                FriendStat("Longest ride", "%,.0f km".format(friend.stats.longestTripMeters / 1000))
                FriendStat("Places", "${friend.stats.municipalitiesVisited}")
                FriendStat("Best coverage", "%.1f%%".format(friend.stats.bestCoveragePercent))
            }
        }
    }
}

@Composable
private fun FriendStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

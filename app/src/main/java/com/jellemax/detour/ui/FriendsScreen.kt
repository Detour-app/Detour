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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.auth.AuthBrowser
import com.jellemax.detour.auth.PendingSignIn
import com.jellemax.detour.convoy.ConvoyLiveService
import com.jellemax.detour.data.Account
import com.jellemax.detour.data.ConvoysStore
import com.jellemax.detour.data.Features
import com.jellemax.detour.data.FriendStats
import com.jellemax.detour.data.Friends
import com.jellemax.detour.data.FriendsStore
import com.jellemax.detour.data.Group
import com.jellemax.detour.data.Groups
import com.jellemax.detour.data.RiderRef
import com.jellemax.detour.data.RiderStats
import com.jellemax.detour.data.SyncClient
import com.jellemax.detour.data.handleFor
import com.jellemax.detour.net.ConvoyLiveClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val username by Account.username.collectAsStateWithLifecycle()
    var addOpen by remember { mutableStateOf(false) }

    // Signing in from here announces itself by this screen swapping its own
    // contents — the button becomes the friends list. Consuming the one-shot
    // stops the map from announcing the same sign-in a second time whenever
    // the rider next navigates back to it.
    LaunchedEffect(username) {
        if (username.isNotBlank()) PendingSignIn.clearSignedIn()
    }

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
            if (!SyncClient.configured()) {
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

/**
 * Signing in is a browser trip now, so there is no form here: the realm owns the
 * password, and this screen owns one button and whatever the trip came back
 * with. Creating an account, changing a password and recovering one all happen
 * on the realm's own pages, which is why they are no longer offered here.
 */
@Composable
private fun SignInSection() {
    val context = LocalContext.current
    // Sign-in is a suspending call now: the realm may have to be asked for.
    // This scope is the composition's, so the launched body runs on the main
    // thread — which is what Oidc's single-thread contract requires of
    // begin()/abandon(). Do not move this to Dispatchers.IO.
    val scope = rememberCoroutineScope()
    // Set by MainActivity when a redirect fails to become a session; consumed
    // here because this is the screen the rider is looking at.
    val error by PendingSignIn.error.collectAsStateWithLifecycle()
    val busy by PendingSignIn.busy.collectAsStateWithLifecycle()

    Text(
        "Sign in to sync your rides and compare stats with friends. " +
            "Your trips and explored map stay private — friends only ever see " +
            "totals and badges.",
        style = MaterialTheme.typography.bodyMedium,
    )
    if (!AuthBrowser.configured) {
        Text(
            "No server or sign-in realm is configured, so there is nobody to " +
                "sign in to. Set your server address under Settings → Servers & sync.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    error?.let {
        Text(it, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
    }
    Button(
        onClick = {
            // begin() is reached from inside this launch, so PendingSignIn.begin()
            // marks the screen busy for the probe as well as the browser trip:
            // on a slow server the resolve is the part the rider waits through.
            // It also sets error to null, so no separate clear() is needed.
            PendingSignIn.begin()
            scope.launch {
                val failure = AuthBrowser.start(context)
                when (failure) {
                    // The browser is open; MainActivity's redirect handler owns
                    // the rest, including clearing busy.
                    null -> {}
                    AuthBrowser.StartFailure.InvalidRealmUrl -> PendingSignIn.fail(
                        "The sign-in realm address is not a valid URL. Check it " +
                            "under Settings → Servers & sync."
                    )
                    AuthBrowser.StartFailure.NoBrowserAvailable ->
                        PendingSignIn.fail("No browser available to sign in with.")
                    AuthBrowser.StartFailure.NoRealmAdvertised ->
                        PendingSignIn.fail(
                            "Your server did not say which realm to sign in to. " +
                                "Update the server, or set the sign-in realm URL " +
                                "under Settings → Servers & sync."
                        )
                    AuthBrowser.StartFailure.NotConfigured ->
                        PendingSignIn.fail(
                            "No identity provider is configured. Set the sign-in " +
                                "realm URL under Settings → Servers & sync."
                        )
                }
            }
        },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Text("Sign in")
    }
    Text(
        "Opens your browser. New accounts and password changes happen there too.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FriendsSection(username: String, onAddFriend: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by FriendsStore.state.collectAsStateWithLifecycle()
    // Own identity, for refreshOwn below — separate from [username] because
    // it resolves a beat later (a /me round trip after sign-in sets the
    // token), and this effect must not miss that second arrival: see the key
    // list below.
    val riderId by Account.riderId.collectAsStateWithLifecycle()
    // Not in the store: signing out is Account's business, not the friend
    // list's, so there is no store `busy` slot it could occupy. It still has to
    // gate the request rows — a revoke POST on a slow connection used to leave
    // Accept/Decline tappable, which is how you answer a friend request on your
    // way out the door. The old code got this for free from one shared local.
    var signingOut by remember { mutableStateOf(false) }

    // Keyed on both: [username] arrives first (set synchronously with the
    // session), [riderId] a beat later once /me answers (see Account.riderId's
    // own doc). Keying on username alone would fire refreshOwn once, while
    // riderId is still blank — its own guard would no-op that call, and
    // nothing would ever ask again for the rest of this session. The repeat
    // firing costs nothing: reloadIfStale is a no-op inside its freshness
    // window, and refreshOwn's second attempt is the one that actually lands.
    LaunchedEffect(username, riderId) {
        // See CirclesScreen: re-entering within the freshness window shows the
        // list it already has rather than re-fetching it.
        FriendsStore.reloadIfStale()
        // Coverage.compute() walks every trace point against every boundary;
        // keep it off the main thread, same reasoning as BadgesScreen/
        // HubScreen/CoverageMapScreen — see refreshOwn's own doc in
        // FriendsStore.kt for the full contract.
        withContext(Dispatchers.IO) { FriendsStore.refreshOwn(RiderRef(riderId, username)) }
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
            TextButton(
                enabled = !signingOut,
                onClick = {
                    // A signed-out session must not keep broadcasting: leaves the
                    // live socket with no valid identity behind it otherwise.
                    ConvoyLiveService.stop(context)
                    signingOut = true
                    scope.launch {
                        Account.signOut()
                        signingOut = false
                    }
                },
            ) {
                Text("Sign out")
            }
        }
    }

    state.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
    }

    val loaded = state.lists
    if (loaded == null) {
        CircularProgressIndicator()
        return
    }

    // Requests first — answering them is the one thing here that's actually
    // time-sensitive; the leaderboard just sits and waits to be looked at.
    if (loaded.incoming.isNotEmpty()) {
        Text("Requests", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        for (rider in loaded.incoming) {
            RequestRow(
                name = rider.username,
                busy = state.busy || signingOut,
                onAccept = { scope.launch { FriendsStore.respond(rider.id, true) } },
                onDecline = { scope.launch { FriendsStore.respond(rider.id, false) } },
            )
        }
    }

    if (loaded.outgoing.isNotEmpty()) {
        Text(
            "Waiting on: ${loaded.outgoing.joinToString(", ") { it.username }}",
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
    if (state.leaderboard.isEmpty()) {
        Text(
            "No friends yet. Add one above — you'll see their totals, " +
                "never their routes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        // The own row arrives as its own typed field, so which row is "me" is known
        // before the sort. Concatenating into a bare list and recovering it with a
        // comparison afterwards is what #133 found here: information discarded and
        // then guessed at. Carrying the flag is correct offline and cannot disagree.
        val ranked = (
            state.leaderboard.map { LeaderboardEntry(it, isMe = false) } +
                listOfNotNull(state.own?.let { LeaderboardEntry(it, isMe = true) })
            ).sortedByDescending { it.friend.stats.totalDistanceMeters }
        ranked.forEachIndexed { i, entry ->
            LeaderboardRow(rank = i + 1, friend = entry.friend, isMe = entry.isMe)
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

/** A leaderboard row and whether it is the signed-in rider's own, kept
 *  together through the sort. */
private data class LeaderboardEntry(val friend: FriendStats, val isMe: Boolean)

/** One leaderboard row: rank, initial avatar, name, trailing distance. The
 *  signed-in user's own row (synthesized by `FriendsStore.refreshOwn`) gets a
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
                    friend.rider.username.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    friend.rider.username + if (isMe) " (you)" else "",
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
                            val result = withContext(Dispatchers.IO) { Friends.request(target) }
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
 * Convoys: the "granted access" gate for live location + push-to-talk — one
 * of the two kinds [Groups] manages (see docs/CIRCLES_AND_CONVOYS.md; the
 * other is circles, on their own [CirclesScreen]). Inviting requires already
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
    val state by ConvoysStore.state.collectAsStateWithLifecycle()
    var createOpen by remember { mutableStateOf(false) }
    var inviteFor by remember { mutableStateOf<Group?>(null) }
    val liveConvoyId by ConvoyLiveClient.activeConvoyId.collectAsStateWithLifecycle()
    // "Live" above only means this device is *trying* to stay connected. A
    // relay it can't reach, a join the server rejects, and a peer who simply
    // isn't sending fixes yet all look the same without these - which is
    // exactly how a convoy where nobody saw anybody stayed silent.
    val liveConnected by ConvoyLiveClient.connected.collectAsStateWithLifecycle()
    val livePeers by ConvoyLiveClient.peers.collectAsStateWithLifecycle()
    val liveError by ConvoyLiveClient.lastError.collectAsStateWithLifecycle()

    // Mic permission is asked for before starting the service, not after -
    // ConvoyLiveService can only declare the foreground microphone type when
    // it's actually held, so asking late would mean going live without PTT
    // ever working until the next relaunch. Starting regardless of the
    // result (granted or denied) still gets you live location; PTT just
    // won't work if denied.
    var pendingLiveConvoyId by remember { mutableStateOf<String?>(null) }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        pendingLiveConvoyId?.let { ConvoyLiveService.start(context, it) }
        pendingLiveConvoyId = null
    }
    fun goLive(convoyId: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            ConvoyLiveService.start(context, convoyId)
        } else {
            pendingLiveConvoyId = convoyId
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(Unit) {
        ConvoysStore.reloadIfStale()
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

    state.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

    if (!Features.liveRelay) {
        DisabledFeatureNotice(Features.liveRelayReason)
    }

    if (state.convoys.isEmpty()) {
        Text(
            "No convoys yet. Start one to share live location and push-to-talk " +
                "with friends who join it — nothing is shared until they accept.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        for (convoy in state.convoys) {
            ConvoyRow(
                convoy = convoy,
                busy = state.busy,
                live = liveConvoyId == convoy.id,
                liveEnabled = Features.liveRelay,
                liveStatus = when {
                    !Features.liveRelay -> Features.liveRelayNotice
                    liveConvoyId != convoy.id -> null
                    !liveConnected -> liveError ?: "Connecting…"
                    livePeers.isEmpty() -> "Connected — nobody else live yet"
                    else -> "Connected — " +
                        livePeers.keys.map { convoy.members.handleFor(it) }.sorted().joinToString(", ")
                },
                liveStatusIsError = Features.liveRelay &&
                    liveConvoyId == convoy.id && !liveConnected && liveError != null,
                onAccept = { scope.launch { ConvoysStore.respond(convoy.id, true) } },
                onDecline = { scope.launch { ConvoysStore.respond(convoy.id, false) } },
                onLeave = {
                    if (liveConvoyId == convoy.id) ConvoyLiveService.stop(context)
                    scope.launch { ConvoysStore.leave(convoy.id) }
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
            onCreate = { name -> scope.launch { ConvoysStore.create(name) }; createOpen = false },
        )
    }
    inviteFor?.let { convoy ->
        InviteToConvoyDialog(
            convoy = convoy,
            onDismiss = { inviteFor = null },
            onInvite = { target -> scope.launch { ConvoysStore.invite(convoy.id, target) }; inviteFor = null },
        )
    }
}

@Composable
private fun ConvoyRow(
    convoy: Group,
    busy: Boolean,
    live: Boolean,
    /** False while the relay has no server: the button stays visible, so the
     *  feature is discoverable, but it cannot be pressed into failing. */
    liveEnabled: Boolean,
    liveStatus: String?,
    liveStatusIsError: Boolean,
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
                    Button(enabled = !busy && liveEnabled, onClick = onToggleLive) {
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
                liveStatus?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (liveStatusIsError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
private fun InviteToConvoyDialog(convoy: Group, onDismiss: () -> Unit, onInvite: (String) -> Unit) {
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

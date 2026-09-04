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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.rounded.Check as AcceptIcon
import androidx.compose.material.icons.rounded.Close as DeclineIcon
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import com.jellemax.detour.data.Friends
import com.jellemax.detour.data.FriendsStore
import com.jellemax.detour.data.Group
import com.jellemax.detour.data.Groups
import com.jellemax.detour.data.SyncClient
import com.jellemax.detour.data.handleFor
import com.jellemax.detour.net.ConvoyLiveClient
import com.jellemax.detour.presentation.FriendsPresenter
import com.jellemax.detour.presentation.LeaderboardRow
import com.jellemax.detour.presentation.friendsBoardStateFrom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(onBack: () -> Unit) {
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
            ) {
                // Only offered once signed in — the dialog it opens calls
                // Friends.request, which needs a session.
                if (username.isNotBlank()) {
                    IconButton(
                        onClick = { addOpen = true },
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.PersonAdd, contentDescription = "Add a friend")
                        }
                    }
                }
            }
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
            when {
                !SyncClient.configured() -> Text(
                    "No sync server configured. Set one in Settings first — " +
                        "friends live on your own server.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                username.isBlank() -> SignInSection()
                else -> {
                    FriendsSection(username)
                    // Convoys: deliberately untouched. Out of scope for this
                    // whole redesign effort (spec §2/§5/§9) — it drives an
                    // Android-only WebSocket singleton (ConvoyLiveClient), a
                    // foreground service and a RECORD_AUDIO permission flow
                    // that have nowhere to go in a shared presenter, and the
                    // prototype has its own convoy screens this batch does
                    // not build. Left rendered as-is below; awaits its own
                    // effort.
                    ConvoysSection()
                }
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

/**
 * The friend list and leaderboard, once signed in. No local sign-out button
 * here any more — [ProfileScreen] already owns sign-out (and already stops
 * [ConvoyLiveService] first, same ordering FriendsScreen used to duplicate),
 * and the prototype's own `isFriends.html` doesn't show one either. Signing
 * IN is unaffected: [SignInSection] above is untouched.
 */
@Composable
private fun FriendsSection(username: String) {
    val scope = rememberCoroutineScope()
    // Own identity, for the presenter's refresh below — separate from
    // [username] because it resolves a beat later (a /me round trip after
    // sign-in sets the token), and this effect must not miss that second
    // arrival: see the key list on the LaunchedEffect below.
    val riderId by Account.riderId.collectAsStateWithLifecycle()

    val presenter = remember { FriendsPresenter() }
    // Keyed on both, same reasoning as the old refreshOwn call this replaces:
    // [username] arrives first, [riderId] a beat later once /me answers.
    // [FriendsPresenter.refresh] blocks on disk and CPU (its own doc is
    // explicit: commonMain has no Dispatchers to hop off of), so the wrap
    // below is mandatory, not stylistic — same contract as BadgesScreen/
    // HubScreen/CoverageMapScreen.
    LaunchedEffect(username, riderId) {
        withContext(Dispatchers.IO) { presenter.refresh() }
    }
    val storeState by FriendsStore.state.collectAsStateWithLifecycle()

    storeState.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
    }

    val loaded = storeState.lists
    if (loaded == null) {
        CircularProgressIndicator()
        return
    }

    val board = remember(storeState, riderId, username) {
        friendsBoardStateFrom(storeState.leaderboard, storeState.own, loaded)
    }

    // Requests first — answering them is the one thing here that's actually
    // time-sensitive; the leaderboard just sits and waits to be looked at.
    if (board.incoming.isNotEmpty()) {
        Text("Requests", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        ListCard {
            board.incoming.forEachIndexed { i, rider ->
                if (i > 0) CardDivider()
                RequestRow(
                    name = rider.username,
                    busy = storeState.busy,
                    onAccept = { scope.launch { FriendsStore.respond(rider.id, true) } },
                    onDecline = { scope.launch { FriendsStore.respond(rider.id, false) } },
                )
            }
        }
    }

    board.waitingOnLabel?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Text("Leaderboard", style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary)
    // Gated on "no friends", not "no rows" — `own` keeps `rows` non-empty for
    // any signed-in rider regardless of whether they have friends, so
    // `board.rows.isEmpty()` here was unreachable in practice (see
    // FriendsStateTest.rowsHoldOnlyTheOwnRowWhenThereAreNoFriends).
    if (board.rows.none { !it.isMe }) {
        Text(
            "No friends yet. Add one — you'll see their totals, never their routes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        ListCard {
            board.rows.forEachIndexed { i, row ->
                if (i > 0) CardDivider()
                LeaderboardRowItem(rank = i + 1, row = row)
            }
        }
    }
}

@Composable
private fun RequestRow(name: String, busy: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
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
            ) { Icon(Icons.Rounded.AcceptIcon, contentDescription = "Accept $name", Modifier.size(16.dp)) }
            IconButton(
                enabled = !busy,
                onClick = onDecline,
                modifier = Modifier.size(30.dp),
            ) { Icon(Icons.Rounded.DeclineIcon, contentDescription = "Decline $name", Modifier.size(16.dp)) }
        }
    }
}

/**
 * One leaderboard row: rank, initial avatar, name, trailing distance — all but
 * the rank number arriving pre-formatted on [row] from [friendsBoardStateFrom].
 * Named `Item` because [com.jellemax.detour.presentation.LeaderboardRow] — the
 * data this renders — already owns the plain name one package over.
 *
 * The leader (rank 1) and the signed-in rider's own row both draw a primary
 * outline on the avatar and a primary-tinted rank/distance; [LeaderboardRow.isMe]
 * additionally tints the whole row's background, so "you" stays easy to find
 * even when you're not in front.
 */
@Composable
private fun LeaderboardRowItem(rank: Int, row: LeaderboardRow) {
    val highlight = row.isMe || rank == 1
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (row.isMe) {
                    Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f))
                } else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$rank",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (highlight) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(22.dp),
        )
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(
                    if (highlight) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    } else Modifier
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(row.avatarInitial, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text(
                row.username + if (row.isMe) " (you)" else "",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (row.isMe) FontWeight.Bold else FontWeight.Medium,
            )
            Text(
                row.statsLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            row.distanceLabel,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** "Add a friend" moved behind the top bar's + — this dialog is the whole form. */
@Composable
private fun AddFriendDialog(onDismiss: () -> Unit) {
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
 *
 * Deliberately untouched by the redesign — see the comment above its call
 * site in [FriendsScreen] for why.
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

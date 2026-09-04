package com.jellemax.detour.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add as AddIcon
import androidx.compose.material.icons.rounded.Check as AcceptIcon
import androidx.compose.material.icons.rounded.Close as DeclineIcon
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ShareLocation
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import com.jellemax.detour.data.Account
import com.jellemax.detour.data.CirclesState
import com.jellemax.detour.data.CirclesStore
import com.jellemax.detour.data.Features
import com.jellemax.detour.data.Group
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.data.SavedPlace
import com.jellemax.detour.data.SavedPlaces
import com.jellemax.detour.data.SyncClient
import com.jellemax.detour.notif.CircleNotifySettings
import com.jellemax.detour.notif.CircleNotifyService
import com.jellemax.detour.presentation.CircleDetailPresenter
import com.jellemax.detour.presentation.CircleMemberRow
import com.jellemax.detour.presentation.CircleRow
import com.jellemax.detour.presentation.CirclesListPresenter
import com.jellemax.detour.presentation.CirclesListState
import com.jellemax.detour.presentation.circleDetailStateFrom
import com.jellemax.detour.presentation.circlesListStateFrom
import kotlinx.coroutines.launch

/** A plain geofence radius suggestion — big enough that ordinary GPS jitter
 *  at a house or a workplace doesn't sit right on the line, small enough
 *  that it doesn't spill onto a neighbour's place. Editable per share. */
private const val DEFAULT_CIRCLE_PLACE_RADIUS_M = 150.0

/**
 * Circles: the same [Groups] gate as convoys, opposite policy — a circle
 * survives being alone in it, has a pause switch instead of push-to-talk,
 * and shows a low-cadence last-known position plus shared places and their
 * arrival/departure events instead of a live map (docs/CIRCLES_AND_CONVOYS.md
 * sections 2 and 7). Kept as its own screen entirely, not folded into
 * [FriendsScreen]'s convoy section — the doc calls merging the two UIs "the
 * one merge with no payoff", since a circle and a convoy card have almost
 * nothing visually in common once sharing state, places and events are on
 * screen.
 */
/**
 * The chrome both Circles destinations share.
 *
 * The two gates — no sync server, not signed in — sit here rather than in each
 * screen, because they are the same message and returning early from either
 * destination means the same thing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CirclesScaffold(
    title: String,
    onBack: () -> Unit,
    error: String?,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    // Two different reasons a rider can't be shown circles yet, gated
    // separately rather than folded into one blank-id check: signed out
    // (blank username) gets the sign-in nudge below; signed in but /me
    // hasn't answered yet (blank riderId, non-blank username) gets an
    // honest "still loading" message instead of the same "sign in" text,
    // which would be false — every check past this gate (isMe, ownership)
    // compares by id, so letting a rider in before it resolves would just
    // show those fail closed with nothing on screen to explain why.
    val username by Account.username.collectAsStateWithLifecycle()
    val riderId by Account.riderId.collectAsStateWithLifecycle()
    // Same three-tier gate the body below checks — [actions] (the list's
    // "new circle" button) is only ever useful once the body would actually
    // render content to act on, so it shares the gate rather than needing
    // its own busy/blank checks at the call site.
    val ready = SyncClient.configured() && username.isNotBlank() && riderId.value.isNotBlank()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { SubScreenTopBar(title, onBack, scrollBehavior) { if (ready) actions() } },
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
                        "circles live on your own server.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }
            if (username.isBlank()) {
                Text(
                    "Sign in under Friends first — circles share that same friends list.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }
            if (riderId.value.isBlank()) {
                Text(
                    "Setting up your account — check back in a moment.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            content()
        }
    }
}

/**
 * The circles list.
 *
 * Opening one used to mean writing `CirclesStore.selectedId` and letting this
 * screen re-render into a detail view — navigation state living in a store, and
 * the third of the three single-value models #68 set out to remove. Back was
 * intercepted in this screen's own top bar, which de-selected before it would let
 * `onBack()` leave. Now [onOpenCircle] pushes a [Destination.CircleDetail] and
 * back is an ordinary pop.
 *
 * `selectedId` itself stays, because it was never only navigation: it also scopes
 * the store's `places` and `events` and drives `loadDetail`. What changed is the
 * direction — the destination now tells the store what is selected, rather than
 * the store telling the UI where it is.
 */
@Composable
fun CirclesScreen(onBack: () -> Unit, onOpenCircle: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val state by CirclesStore.state.collectAsStateWithLifecycle()
    val riderId by Account.riderId.collectAsStateWithLifecycle()
    var createOpen by remember { mutableStateOf(false) }

    // The presenter owns only the load kick — CirclesStore is mutable and
    // every mutation below reloads it directly, so there is nothing else for
    // a cached snapshot here to do but go stale. See CirclesListPresenter's
    // KDoc. Fires on every entry, and stepping Hub -> Circles -> Hub ->
    // Circles is not a new visit; reloadIfStale skips the round trip inside
    // its window.
    val presenter = remember { CirclesListPresenter() }
    LaunchedEffect(Unit) { presenter.refresh() }

    // Pure map from the store's raw circles to display rows, recomputed on
    // the render path rather than cached — see circlesListStateFrom's KDoc.
    val listState = remember(state.circles, riderId) { circlesListStateFrom(state.circles, riderId) }

    CirclesScaffold(
        title = "Circles",
        onBack = onBack,
        error = state.error,
        actions = {
            IconButton(onClick = { createOpen = true }, modifier = Modifier.padding(end = 8.dp)) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.AddIcon, contentDescription = "New circle")
                }
            }
        },
    ) {
        CircleListSection(
            listState = listState,
            busy = state.busy,
            onOpen = onOpenCircle,
            onAccept = { id ->
                scope.launch {
                    if (CirclesStore.respond(id, true)) CircleNotifyService.refresh(context)
                }
            },
            onDecline = { id ->
                scope.launch {
                    if (CirclesStore.respond(id, false)) CircleNotifyService.refresh(context)
                }
            },
        )
    }

    if (createOpen) {
        CreateCircleDialog(
            onDismiss = { createOpen = false },
            onCreate = { name ->
                scope.launch {
                    if (CirclesStore.create(name)) CircleNotifyService.refresh(context)
                }
                createOpen = false
            },
        )
    }
}

/**
 * One circle.
 *
 * [CircleDetailPresenter.open] does `selectOnly(circleId)` then
 * `reloadIfStale()` — the same load-kick-only shape as [CirclesListPresenter],
 * see its KDoc. That call refreshes the circle *list* if stale; it does not
 * fetch this pane's places/events. That fetch is a second, separate call —
 * `CirclesStore.select(circle.id)` — owned by [CircleDetailSection]'s own
 * `LaunchedEffect(circle.id)`, exactly as before: firing it here too would
 * double the request the moment both mount together.
 *
 * A [circleId] that names no real circle pops back to the list rather than
 * showing a blank screen. The store used to absorb this — `loaded` drops a
 * `selectedId` that turns out not to exist, and the screen then rendered the list
 * because `selected` was null — but a stack entry has no such fallback, so it is
 * written down. Only once the list has actually loaded, or a bogus id would pop
 * during the first frame of a legitimate open.
 */
@Composable
fun CircleDetailScreen(circleId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val riderId by Account.riderId.collectAsStateWithLifecycle()
    val state by CirclesStore.state.collectAsStateWithLifecycle()
    var inviteFor by remember { mutableStateOf<Group?>(null) }

    val presenter = remember { CircleDetailPresenter() }
    LaunchedEffect(circleId) { presenter.open(circleId) }

    val circle = state.circles.find { it.id == circleId }

    LaunchedEffect(circle, state.circles, state.busy) {
        if (circle == null && state.circles.isNotEmpty() && !state.busy) onBack()
    }

    // Nothing clears selectedId on the way out, and that matches what the app
    // already did: the old top bar de-selected only when *it* was tapped, so a
    // system back from a circle detail left selectedId set. The difference is
    // that a stale selectedId is now inert — the list reads the stack, not the
    // store, so it can no longer reopen a detail nobody asked for. It still
    // scopes places and events, which is what it was always for.

    CirclesScaffold(
        title = circle?.name ?: "Circle",
        onBack = onBack,
        error = state.error,
        actions = {
            // The prototype's top-bar person_add, same treatment as the
            // list's "New circle" button above: the label survives as the
            // contentDescription, not visible text.
            circle?.let { c ->
                IconButton(
                    onClick = { inviteFor = c },
                    enabled = !state.busy,
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.PersonAdd, contentDescription = "Invite")
                    }
                }
            }
        },
    ) {
        circle?.let {
            CircleDetailSection(
                circle = it,
                riderId = riderId,
                state = state,
                onLeave = {
                    scope.launch {
                        if (CirclesStore.leave(it.id)) CircleNotifyService.refresh(context)
                    }
                },
                onToggleSharing = { sharing ->
                    scope.launch {
                        if (CirclesStore.setSharing(it.id, sharing)) CircleNotifyService.refresh(context)
                    }
                },
            )
        }
    }

    inviteFor?.let { c ->
        InviteToCircleDialog(
            circle = c,
            onDismiss = { inviteFor = null },
            onInvite = { target ->
                scope.launch {
                    if (CirclesStore.invite(c.id, target) != null) CircleNotifyService.refresh(context)
                }
                inviteFor = null
            },
        )
    }
}

@Composable
private fun CircleListSection(
    listState: CirclesListState,
    busy: Boolean,
    onOpen: (String) -> Unit,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit,
) {
    if (listState.invited.isNotEmpty()) {
        Text("Invites", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        ListCard {
            listState.invited.forEachIndexed { i, row ->
                if (i > 0) CardDivider()
                CircleInviteRow(
                    row = row,
                    busy = busy,
                    onAccept = { onAccept(row.id) },
                    onDecline = { onDecline(row.id) },
                )
            }
        }
    }

    Text("Your circles", style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary)
    if (listState.accepted.isEmpty()) {
        Text(
            "No circles yet. A circle is always-on, low-cadence location sharing with " +
                "family or roommates — unlike a convoy it doesn't end when a ride does, " +
                "and there's no push-to-talk.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    ListCard {
        listState.accepted.forEachIndexed { i, row ->
            if (i > 0) CardDivider()
            HubRow(
                icon = Icons.Rounded.ShareLocation,
                title = row.name,
                subtitle = row.memberLine,
                trailingText = if (row.sharing) "Sharing" else "Not sharing",
                onClick = { onOpen(row.id) },
                paintCard = false,
            )
        }
    }
}

@Composable
private fun CircleInviteRow(row: CircleRow, busy: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(row.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(row.memberLine, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconButton(
                enabled = !busy,
                onClick = onAccept,
                modifier = Modifier.size(30.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) { Icon(Icons.Rounded.AcceptIcon, contentDescription = "Accept ${row.name}", Modifier.size(16.dp)) }
            IconButton(enabled = !busy, onClick = onDecline, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Rounded.DeclineIcon, contentDescription = "Decline ${row.name}", Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun CircleDetailSection(
    circle: Group,
    riderId: RiderId,
    state: CirclesState,
    onLeave: () -> Unit,
    onToggleSharing: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val savedPlaces by SavedPlaces.places.collectAsStateWithLifecycle()
    var shareOpen by remember { mutableStateOf(false) }

    // Local-only preference (see CircleNotifySettings) - not part of `circle`
    // itself, unlike `sharing`, which really is server state.
    var notifyEnabled by remember(circle.id) {
        mutableStateOf(CircleNotifySettings.notifyEnabled(circle.id))
    }
    var showBatteryPrompt by remember { mutableStateOf(false) }

    // Offers the exemption at most once, ever, and only when it would
    // actually help (a phone already ignoring battery optimizations for
    // this app has nothing to gain from being asked) - see
    // CircleNotifySettings.batteryPromptShown's doc for why the flag is set
    // here rather than on the dialog's own buttons.
    fun maybeOfferBatteryPrompt() {
        if (CircleNotifySettings.batteryPromptShown()) return
        val ignoring = context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        if (ignoring) return
        CircleNotifySettings.setBatteryPromptShown()
        showBatteryPrompt = true
    }

    // POST_NOTIFICATIONS is requested here, when notifications are actually
    // turned on for the first time - not bundled into the app-start
    // permission sweep MapScreen already runs for the trip/badge
    // notifications that feature predates. A denial settles the switch back
    // off rather than leaving it showing "on" for something that can't
    // actually arrive.
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notifyEnabled = granted
        CircleNotifySettings.setNotifyEnabled(circle.id, granted)
        if (granted) maybeOfferBatteryPrompt()
        CircleNotifyService.refresh(context)
    }

    fun onToggleNotify(enabled: Boolean) {
        notifyEnabled = enabled
        CircleNotifySettings.setNotifyEnabled(circle.id, enabled)
        if (enabled &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // The launcher's own callback finishes the job (settles
            // notifyEnabled to what was actually granted, offers the
            // battery prompt, refreshes the service) once the user answers.
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (enabled) maybeOfferBatteryPrompt()
        CircleNotifyService.refresh(context)
    }

    // This *list* still only ever reflects the last time this section loaded
    // it - on open, after a mutation, or the refresh button below - even
    // though a local notification for a new arrival/departure can now
    // arrive live (see CircleNotifyService): re-fetching on every relay
    // frame just to keep a section that might not even be open in sync
    // isn't worth it, and tapping that notification lands back here anyway,
    // which reloads it. Keyed on the circle id alone, not a reload counter,
    // now that CirclesStore owns the reload itself - so that a circle
    // change while this section stays mounted cancels a stale in-flight
    // load rather than letting CirclesStore.loadDetail finish it only to
    // discard the result.
    LaunchedEffect(circle.id) {
        CirclesStore.select(circle.id)
    }

    val mine = circle.members.find { it.id == riderId }

    // Pure map from the store's raw circle/places/events to display rows,
    // recomputed on the render path — see circleDetailStateFrom's KDoc.
    // nowMs is a single snapshot taken here and threaded through, never read
    // inside the mapper or per row. detailBusy/detailError are deliberately
    // not part of this mapper's output (same KDoc) — read straight off
    // `state` below, same as before.
    val nowMs = System.currentTimeMillis()
    val detail = circleDetailStateFrom(circle, riderId, state.places, state.events.take(20), nowMs)

    Text("Members", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    ListCard {
        detail.members.forEachIndexed { i, row ->
            if (i > 0) CardDivider()
            CircleMemberListRow(row)
        }
    }

    if (mine != null) {
        ListCard {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Share my location", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (mine.sharing) "Posting your position to this circle every couple of minutes"
                        else "Paused — nothing is being shared",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = mine.sharing, enabled = !state.busy, onCheckedChange = onToggleSharing)
            }
            CardDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Notify me about arrivals", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            !Features.liveRelay -> Features.liveRelayNotice
                            notifyEnabled -> "A notification when someone arrives at or leaves a shared place"
                            else -> "Off"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = notifyEnabled && Features.liveRelay,
                    enabled = !state.busy && Features.liveRelay,
                    onCheckedChange = ::onToggleNotify,
                )
            }
        }
    }

    // Invite moved to the top bar (person_add, see CircleDetailScreen) —
    // Leave stays here, the one action left with no natural home in chrome.
    TextButton(enabled = !state.busy, onClick = onLeave) { Text("Leave") }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Shared places", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { scope.launch { CirclesStore.select(circle.id) } }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
            }
            TextButton(enabled = savedPlaces.isNotEmpty(), onClick = { shareOpen = true }) {
                Icon(Icons.Rounded.AddIcon, contentDescription = null, Modifier.size(18.dp))
                Text("Share")
            }
        }
    }
    state.detailError?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    if (detail.places.isEmpty()) {
        Text(
            "No places shared yet. Sharing one lets the circle see arrivals and " +
                "departures there — the place stays yours, only revoked when you leave.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        ListCard {
            detail.places.forEachIndexed { i, row ->
                if (i > 0) CardDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(row.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                        Text(
                            row.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (row.removable) {
                        IconButton(
                            enabled = !state.detailBusy,
                            onClick = { scope.launch { CirclesStore.unsharePlace(row.serverId) } },
                        ) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove ${row.name}",
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    Text("Recent activity", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    if (detail.events.isEmpty()) {
        Text(
            "No arrivals or departures yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        ListCard {
            detail.events.forEachIndexed { i, row ->
                if (i > 0) CardDivider()
                Text(
                    row.text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }

    if (shareOpen) {
        SharePlaceDialog(
            places = savedPlaces,
            onDismiss = { shareOpen = false },
            onShare = { place, radiusM ->
                scope.launch { CirclesStore.sharePlace(circle.id, place, radiusM) }
                shareOpen = false
            },
        )
    }

    if (showBatteryPrompt) {
        BatteryOptimizationDialog(onDismiss = { showBatteryPrompt = false })
    }
}

/** One-time nudge (see CircleNotifySettings.batteryPromptShown) towards the
 *  battery-optimization exemption list - some OEMs (Xiaomi, Samsung, Huawei)
 *  kill a long-lived background socket well before Android itself would,
 *  which would otherwise show up as arrivals arriving late or not at all. No
 *  direct-grant intent here (that needs its own REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
 *  permission, a heavier ask than this feature needs) - just opens the list
 *  the user finds Detour in and exempts it themselves. */
@Composable
private fun BatteryOptimizationDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Keep arrivals reliable") },
        text = {
            Text(
                "Some phones pause background connections to save battery, which can " +
                    "delay or drop these notifications. Exempting Detour from battery " +
                    "optimization keeps them arriving on time.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                context.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }) { Text("Open settings") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

/** One row of the shared mapper's [CircleMemberRow] — `displayName` already
 *  carries the "(you)"/"· invited" suffixes, `sharing` gates the icon
 *  directly, nothing recomputed here. */
@Composable
private fun CircleMemberListRow(row: CircleMemberRow) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.displayName, style = MaterialTheme.typography.bodyMedium)
        Icon(
            if (row.sharing) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
            contentDescription = if (row.sharing) "Sharing location" else "Not sharing",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun CreateCircleDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New circle") },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Circle name (Family, Roommates…)") },
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

/** Invite by username, same shape as convoy's — the server rejects anyone
 *  not already a friend either way. */
@Composable
private fun InviteToCircleDialog(circle: Group, onDismiss: () -> Unit, onInvite: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invite to ${circle.name}") },
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

/** Pick one of the signed-in user's saved places and a geofence radius to
 *  share into the circle. Only lists [SavedPlace]s — a circle place is a
 *  saved place plus a radius, not a separate thing to create from scratch. */
@Composable
private fun SharePlaceDialog(
    places: List<SavedPlace>,
    onDismiss: () -> Unit,
    onShare: (SavedPlace, Double) -> Unit,
) {
    var picked by remember { mutableStateOf(places.firstOrNull()) }
    var radiusText by remember { mutableStateOf(DEFAULT_CIRCLE_PLACE_RADIUS_M.toInt().toString()) }
    val radius = radiusText.toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share a place") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The circle sees arrivals and departures here, not your live position.",
                    style = MaterialTheme.typography.bodySmall,
                )
                for (place in places) {
                    Text(
                        place.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (picked?.id == place.id) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { picked = place }
                            .padding(vertical = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = radiusText,
                    onValueChange = { radiusText = it },
                    label = { Text("Radius (metres)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            val r = radius
            TextButton(
                enabled = picked != null && r != null && r > 0,
                onClick = { onShare(picked!!, r!!) },
            ) { Text("Share") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}


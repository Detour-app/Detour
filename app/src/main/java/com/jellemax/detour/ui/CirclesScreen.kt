package com.jellemax.detour.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.data.Account
import com.jellemax.detour.data.CircleEvents
import com.jellemax.detour.data.CirclePlace
import com.jellemax.detour.data.CirclePlaces
import com.jellemax.detour.data.Group
import com.jellemax.detour.data.GroupMember
import com.jellemax.detour.data.Groups
import com.jellemax.detour.data.PlaceEvent
import com.jellemax.detour.data.SavedPlace
import com.jellemax.detour.data.SavedPlaces
import com.jellemax.detour.data.SyncClient
import com.jellemax.detour.notif.CircleNotifySettings
import com.jellemax.detour.notif.CircleNotifyService
import com.jellemax.detour.notif.PendingCircleOpen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CirclesScreen(onBack: () -> Unit, openCircleId: Int? = null) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val username by Account.username.collectAsStateWithLifecycle()
    var circles by remember { mutableStateOf<List<Group>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloads by remember { mutableIntStateOf(0) }
    var createOpen by remember { mutableStateOf(false) }
    var inviteFor by remember { mutableStateOf<Group?>(null) }
    // Which circle this screen has open, for its places and events. The map no
    // longer reads it: it draws every circle you're in, all the time, so there
    // is nothing here for leaving the screen to disagree with.
    var selectedId by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(reloads) {
        try {
            circles = withContext(Dispatchers.IO) { Groups.list("circle") }
            error = null
        } catch (e: Exception) {
            error = e.message ?: "Could not reach the server"
        }
    }

    // A tapped arrival/departure notification opens straight to its circle -
    // waits for the list to actually contain it (a cold start races this
    // screen's own load) rather than opening a detail section for an id
    // nothing yet confirms is real.
    LaunchedEffect(circles, openCircleId) {
        if (openCircleId != null && circles.any { it.id == openCircleId }) {
            selectedId = openCircleId
            PendingCircleOpen.clear()
        }
    }

    fun act(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                reloads++
                // Accepting an invite is the one action here that can change
                // which circles want notifications - cheap enough (see the
                // function's own doc) to just call unconditionally rather
                // than singling that action out.
                CircleNotifyService.refresh(context)
            } catch (e: Exception) {
                error = e.message ?: "Failed"
            }
            busy = false
        }
    }

    val selected = circles.find { it.id == selectedId }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SubScreenTopBar(
                selected?.name ?: "Circles",
                onBack = { if (selected != null) selectedId = null else onBack() },
                scrollBehavior,
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
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (selected == null) {
                CircleListSection(
                    circles = circles,
                    busy = busy,
                    onOpen = { selectedId = it.id },
                    onCreate = { createOpen = true },
                    onAccept = { c -> act { Groups.respond("circle", c.id, true) } },
                    onDecline = { c -> act { Groups.respond("circle", c.id, false) } },
                )
            } else {
                CircleDetailSection(
                    circle = selected,
                    username = username,
                    busy = busy,
                    onInvite = { inviteFor = selected },
                    onLeave = {
                        selectedId = null
                        act { Groups.leave("circle", selected.id) }
                    },
                    onToggleSharing = { sharing -> act { Groups.setSharing(selected.id, sharing) } },
                )
            }
        }
    }

    if (createOpen) {
        CreateCircleDialog(
            onDismiss = { createOpen = false },
            onCreate = { name -> act { Groups.create("circle", name) }; createOpen = false },
        )
    }
    inviteFor?.let { circle ->
        InviteToCircleDialog(
            circle = circle,
            onDismiss = { inviteFor = null },
            onInvite = { target -> act { Groups.invite("circle", circle.id, target) }; inviteFor = null },
        )
    }
}

@Composable
private fun CircleListSection(
    circles: List<Group>,
    busy: Boolean,
    onOpen: (Group) -> Unit,
    onCreate: () -> Unit,
    onAccept: (Group) -> Unit,
    onDecline: (Group) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Your circles", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        TextButton(onClick = onCreate) {
            Icon(Icons.Outlined.Add, contentDescription = null, Modifier.size(18.dp))
            Text("New circle")
        }
    }

    if (circles.isEmpty()) {
        Text(
            "No circles yet. A circle is always-on, low-cadence location sharing with " +
                "family or roommates — unlike a convoy it doesn't end when a ride does, " +
                "and there's no push-to-talk.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    for (circle in circles) {
        Card(
            Modifier
                .fillMaxWidth()
                .then(
                    if (circle.status == "accepted") Modifier.clickable { onOpen(circle) }
                    else Modifier
                ),
        ) {
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
                    Text(circle.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    if (circle.status == "invited") {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            IconButton(
                                enabled = !busy,
                                onClick = { onAccept(circle) },
                                modifier = Modifier.size(30.dp),
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            ) { Icon(Icons.Outlined.Check, contentDescription = "Accept ${circle.name}", Modifier.size(16.dp)) }
                            IconButton(enabled = !busy, onClick = { onDecline(circle) }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Outlined.Close, contentDescription = "Decline ${circle.name}", Modifier.size(16.dp))
                            }
                        }
                    }
                }
                Text(
                    circle.members.joinToString(", ") {
                        it.username + if (it.status == "invited") " (invited)" else ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CircleDetailSection(
    circle: Group,
    username: String,
    busy: Boolean,
    onInvite: () -> Unit,
    onLeave: () -> Unit,
    onToggleSharing: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val savedPlaces by SavedPlaces.places.collectAsStateWithLifecycle()
    var places by remember(circle.id) { mutableStateOf<List<CirclePlace>>(emptyList()) }
    var events by remember(circle.id) { mutableStateOf<List<PlaceEvent>>(emptyList()) }
    var placesError by remember(circle.id) { mutableStateOf<String?>(null) }
    var placesBusy by remember(circle.id) { mutableStateOf(false) }
    var dataReloads by remember(circle.id) { mutableIntStateOf(0) }
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

    // This *list* still only ever reflects the last time this screen loaded
    // it - on open, after a mutation, or the refresh button below - even
    // though a local notification for a new arrival/departure can now
    // arrive live (see CircleNotifyService): re-fetching the whole list on
    // every relay frame just to keep a screen that might not even be open
    // in sync isn't worth it, and tapping that notification lands back here
    // anyway, which reloads it.
    LaunchedEffect(circle.id, dataReloads) {
        try {
            places = withContext(Dispatchers.IO) { CirclePlaces.places(circle.id) }
            events = withContext(Dispatchers.IO) { CircleEvents.events(circle.id, sinceMs = 0L) }
                .sortedByDescending { it.tsMs }
            placesError = null
        } catch (e: Exception) {
            placesError = e.message ?: "Could not reach the server"
        }
    }

    fun act(block: suspend () -> Unit) {
        placesBusy = true
        placesError = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                dataReloads++
            } catch (e: Exception) {
                placesError = e.message ?: "Failed"
            }
            placesBusy = false
        }
    }

    val mine = circle.members.find { it.username == username }

    Text("Members", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    for (member in circle.members) {
        MemberRow(member, isMe = member.username == username)
    }

    if (mine != null) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Share my location", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (mine.sharing) "Posting your position to this circle every couple of minutes"
                    else "Paused — nothing is being shared",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = mine.sharing, enabled = !busy, onCheckedChange = onToggleSharing)
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Notify me about arrivals", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (notifyEnabled) "A notification when someone arrives at or leaves a shared place"
                    else "Off",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = notifyEnabled, enabled = !busy, onCheckedChange = ::onToggleNotify)
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy, onClick = onInvite) { Text("Invite") }
        TextButton(enabled = !busy, onClick = onLeave) { Text("Leave") }
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Shared places", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { dataReloads++ }) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
            }
            TextButton(enabled = savedPlaces.isNotEmpty(), onClick = { shareOpen = true }) {
                Icon(Icons.Outlined.Add, contentDescription = null, Modifier.size(18.dp))
                Text("Share")
            }
        }
    }
    placesError?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    if (places.isEmpty()) {
        Text(
            "No places shared yet. Sharing one lets the circle see arrivals and " +
                "departures there — the place stays yours, only revoked when you leave.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        for (place in places) {
            Card {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(place.place.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "Shared by ${place.owner} · ${place.radiusM.toInt()} m radius",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (place.owner == username) {
                        IconButton(
                            enabled = !placesBusy,
                            onClick = { act { CirclePlaces.delete(place.serverId) } },
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Remove ${place.place.name}",
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    Text("Recent activity", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    if (events.isEmpty()) {
        Text(
            "No arrivals or departures yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        for (event in events.take(20)) {
            // The place may since have been unshared/deleted; say so rather
            // than dropping the event, which happened and stays true either way.
            val placeName = places.find { it.place.id == event.placeId }?.place?.name ?: "a since-removed place"
            val verb = if (event.kind == "arrive") "arrived at" else "left"
            Text(
                "${event.username} $verb $placeName — ${relativeAge(event.tsMs)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (shareOpen) {
        SharePlaceDialog(
            places = savedPlaces,
            onDismiss = { shareOpen = false },
            onShare = { place, radiusM ->
                act { CirclePlaces.share(circle.id, place, radiusM) }
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

@Composable
private fun MemberRow(member: GroupMember, isMe: Boolean) {
    val suffix = buildString {
        if (isMe) append(" (you)")
        if (member.status == "invited") append(" · invited")
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(member.username + suffix, style = MaterialTheme.typography.bodyMedium)
        Icon(
            if (member.sharing) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
            contentDescription = if (member.sharing) "Sharing location" else "Not sharing",
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

/** Coarse relative age ("3m ago", "2h ago") — exact seconds never matter for
 *  a feature whose fixes only update every couple of minutes anyway. */
private fun relativeAge(tsMs: Long): String {
    val minutes = (System.currentTimeMillis() - tsMs).coerceAtLeast(0) / 60_000L
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        else -> "${minutes / (60 * 24)}d ago"
    }
}

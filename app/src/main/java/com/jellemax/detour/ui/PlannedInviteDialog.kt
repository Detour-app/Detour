package com.jellemax.detour.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.R
import com.jellemax.detour.data.Account
import com.jellemax.detour.data.CirclesStore
import com.jellemax.detour.data.FriendsStore
import com.jellemax.detour.data.PlannedRide
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.presentation.PlannedInviteOption
import com.jellemax.detour.presentation.plannedInviteChoicesFrom

/**
 * The host's picker for inviting friends and circles to [ride] (#756). What it offers is
 * [plannedInviteChoicesFrom]; the ticks are form state and die with the dialog (§4.2).
 *
 * The request itself runs in the caller's scope through [onInvite], not one of this dialog's:
 * dismissing mid-request would cancel it after `PlannedRidesStore` set `busy`, and a cancelled
 * action leaves `busy` set. The caller closes the dialog on success; a failure stays open with
 * [error], so the host can untick whoever the server refused and send again.
 *
 * Friends and circles load independently, so each section shows its own spinner or failure
 * rather than one waiting on the other.
 */
@Composable
internal fun PlannedInviteDialog(
    ride: PlannedRide,
    busy: Boolean,
    error: String?,
    onInvite: (riderIds: List<RiderId>, circleIds: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val friendsState by FriendsStore.state.collectAsStateWithLifecycle()
    val circlesState by CirclesStore.state.collectAsStateWithLifecycle()
    val riderId by Account.riderId.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { FriendsStore.reloadIfStale() }
    LaunchedEffect(Unit) { CirclesStore.reloadIfStale() }

    val friends = friendsState.lists?.friends
    val circlesLoaded = circlesState.loadedAtMs != null
    val choices = remember(ride, friends, circlesState.circles, riderId) {
        plannedInviteChoicesFrom(ride, friends.orEmpty(), circlesState.circles, riderId)
    }
    var pickedFriends by remember { mutableStateOf(emptySet<String>()) }
    var pickedCircles by remember { mutableStateOf(emptySet<String>()) }
    // Only ticks still on offer are sent: a reload under the open dialog can drop one (they were
    // invited from another device, or unfriended), and the server would refuse the whole request.
    val riderIds = choices.friends.filter { it.id in pickedFriends }.map { RiderId(it.id) }
    val circleIds = choices.circles.filter { it.id in pickedCircles }.map { it.id }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.planned_invite_title, ride.title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (friends != null && circlesLoaded && choices.isEmpty) {
                    Text(stringResource(R.string.planned_invite_nobody), style = MaterialTheme.typography.bodySmall)
                }
                InviteSection(
                    heading = stringResource(R.string.planned_invite_friends),
                    loaded = friends != null,
                    loadError = friendsState.error,
                    options = choices.friends,
                    picked = pickedFriends,
                    onToggle = { id -> pickedFriends = pickedFriends.toggled(id) },
                )
                InviteSection(
                    heading = stringResource(R.string.planned_invite_circles),
                    loaded = circlesLoaded,
                    loadError = circlesState.error,
                    options = choices.circles,
                    picked = pickedCircles,
                    onToggle = { id -> pickedCircles = pickedCircles.toggled(id) },
                )
                if (choices.circles.isNotEmpty()) {
                    Text(
                        stringResource(R.string.planned_invite_circle_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && (riderIds.isNotEmpty() || circleIds.isNotEmpty()),
                onClick = { onInvite(riderIds, circleIds) },
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.planned_invite_send))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.planned_invite_cancel)) } },
    )
}

/** One heading and its tick list. Hidden once loaded with nothing to offer: the dialog's single
 *  "nobody to invite" line covers that, and an empty heading would only repeat it. */
@Composable
private fun InviteSection(
    heading: String,
    loaded: Boolean,
    loadError: String?,
    options: List<PlannedInviteOption>,
    picked: Set<String>,
    onToggle: (String) -> Unit,
) {
    if (loaded && options.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(heading, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        when {
            !loaded && loadError != null ->
                Text(loadError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            !loaded -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else -> options.forEach { option -> InviteOptionRow(option, option.id in picked, onToggle) }
        }
    }
}

@Composable
private fun InviteOptionRow(option: PlannedInviteOption, checked: Boolean, onToggle: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle(option.id) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(option.name, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun Set<String>.toggled(id: String): Set<String> = if (id in this) this - id else this + id

package com.jellemax.detour.ui

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.R
import com.jellemax.detour.data.Account
import com.jellemax.detour.data.PlannedRidesStore
import com.jellemax.detour.data.RsvpAnswer
import com.jellemax.detour.data.SyncClient
import com.jellemax.detour.presentation.PlannedInviteeRow
import com.jellemax.detour.presentation.PlannedRideDetailState
import com.jellemax.detour.presentation.PlannedRideRow
import com.jellemax.detour.presentation.plannedRideDetailStateFrom
import com.jellemax.detour.presentation.plannedRideRowsFrom
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The chrome both planned-ride destinations share, with the same three gates as Circles'
 * scaffold — no sync server, signed out, `/me` not answered yet — for the same reason: planned
 * rides live on the rider's own server, and every ownership check compares rider ids, so a
 * blank id would only make the host's rides read as someone else's with nothing to say why.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlannedScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val username by Account.username.collectAsStateWithLifecycle()
    val riderId by Account.riderId.collectAsStateWithLifecycle()
    val gate = when {
        !SyncClient.configured() -> R.string.planned_no_server
        username.isBlank() -> R.string.planned_sign_in_first
        riderId.value.isBlank() -> R.string.planned_setting_up
        else -> null
    }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { SubScreenTopBar(title, onBack, scrollBehavior) { if (gate == null) actions() } },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (gate != null) {
                Text(stringResource(gate), style = MaterialTheme.typography.bodyMedium)
            } else {
                content()
            }
        }
    }
}

/**
 * Rides this rider hosts or is invited to, soonest first (#754). Read-only: creating, editing
 * and inviting are #755/#756. Reads [PlannedRidesStore] directly — like Circles, there is no
 * cached copy to go stale under a mutation.
 *
 * `rides` is null until the first load lands, so the three bodies stay apart: a spinner while
 * the first load runs, the failure with a retry when it never landed (offline, server down),
 * and "nothing planned" only once the server has actually said so. A failed refresh over a
 * list already shown keeps the list and puts the error above it.
 */
@Composable
fun PlannedRidesScreen(onBack: () -> Unit, onOpenRide: (String) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val state by PlannedRidesStore.state.collectAsStateWithLifecycle()
    val riderId by Account.riderId.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { PlannedRidesStore.reloadIfStale() }
    val rows = remember(state.rides, riderId) { state.rides?.let { plannedRideRowsFrom(it, riderId) } }

    PlannedScaffold(
        title = stringResource(R.string.planned_title),
        onBack = onBack,
        actions = {
            IconButton(
                enabled = !state.busy,
                onClick = { scope.launch { PlannedRidesStore.reload() } },
                modifier = Modifier.padding(end = 8.dp),
            ) {
                if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.planned_refresh))
            }
        },
    ) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                rows == null && state.error != null -> LoadFailed(
                    error = state.error.orEmpty(),
                    onRetry = { scope.launch { PlannedRidesStore.reload() } },
                )
                rows == null -> Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> {
                    state.error?.let { ErrorLine(it) }
                    if (rows.isEmpty()) NothingPlanned()
                    rows.forEach { row -> PlannedRideCard(row, onClick = { onOpenRide(row.id) }) }
                }
            }
        }
    }
}

/**
 * One planned ride (#754): when, where to meet, who is coming, and — for an invitee — the
 * going / maybe / can't answer. Read from the store's list rather than `PlannedRides.get`: the
 * list already carries every field and invitee (BACKEND_SPEC.md §8.1), and one source means an
 * answer sent here shows on the list without a second fetch.
 *
 * A [rideId] the loaded list no longer holds (the host deleted it, or the rider was unfriended)
 * pops back to the list, the way a vanished circle does — only once a load has landed and none
 * is running, or a legitimate open would pop during its first frame.
 */
@Composable
fun PlannedRideDetailScreen(rideId: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val state by PlannedRidesStore.state.collectAsStateWithLifecycle()
    val riderId by Account.riderId.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { PlannedRidesStore.reloadIfStale() }
    val ride = state.rides?.find { it.id == rideId }
    val detail = remember(ride, riderId) { ride?.let { plannedRideDetailStateFrom(it, riderId) } }

    // Not a key: a new onBack lambda on recomposition must not re-run the check.
    val currentOnBack by rememberUpdatedState(onBack)
    LaunchedEffect(ride, state.rides, state.busy) {
        if (ride == null && state.rides != null && !state.busy) currentOnBack()
    }

    PlannedScaffold(title = detail?.title ?: stringResource(R.string.planned_ride), onBack = onBack) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            state.error?.let { ErrorLine(it) }
            if (detail == null) {
                Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                RideFacts(detail)
                if (detail.canAnswer) {
                    AnswerRow(
                        detail = detail,
                        enabled = !state.busy,
                        onAnswer = { answer -> scope.launch { PlannedRidesStore.answer(detail.id, answer) } },
                    )
                }
                Invitees(detail.invitees)
            }
        }
    }
}

@Composable
private fun PlannedRideCard(row: PlannedRideRow, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(formatDate(row.startsAtMs), style = MaterialTheme.typography.bodyMedium)
            Text(
                if (row.hosting) stringResource(R.string.planned_you_host)
                else stringResource(R.string.planned_hosted_by, row.hostName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            row.myAnswer?.let {
                Text(
                    stringResource(R.string.planned_your_answer, stringResource(answerLabel(it))),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun RideFacts(detail: PlannedRideDetailState) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RideWhen(detail)
        RideWhere(detail)
        detail.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun RideWhen(detail: PlannedRideDetailState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(formatDate(detail.startsAtMs), style = MaterialTheme.typography.titleMedium)
        Text(
            if (detail.hosting) stringResource(R.string.planned_you_host)
            else stringResource(R.string.planned_hosted_by, detail.hostName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RideWhere(detail: PlannedRideDetailState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.planned_meeting_point), style = MaterialTheme.typography.labelLarge)
        // No label from the host: the coordinates are the only honest thing to show.
        Text(
            detail.meetingLabel ?: String.format(
                Locale.ROOT, "%.5f, %.5f", detail.meetingLatitude, detail.meetingLongitude,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun AnswerRow(detail: PlannedRideDetailState, enabled: Boolean, onAnswer: (RsvpAnswer) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.planned_are_you_coming), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            detail.answers.forEach { answer ->
                FilterChip(
                    selected = detail.myAnswer == answer,
                    enabled = enabled,
                    onClick = { onAnswer(answer) },
                    label = { Text(stringResource(answerLabel(answer))) },
                )
            }
        }
    }
}

@Composable
private fun Invitees(invitees: List<PlannedInviteeRow>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.planned_invitees), style = MaterialTheme.typography.labelLarge)
        if (invitees.isEmpty()) {
            Text(
                stringResource(R.string.planned_no_invitees),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        invitees.forEach { invitee ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(invitee.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(answerLabel(invitee.answer)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NothingPlanned() {
    Column(
        Modifier.fillMaxWidth().padding(top = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Rounded.Event,
            contentDescription = null,
            Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.planned_empty_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.planned_empty_text),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoadFailed(error: String, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.planned_load_failed), style = MaterialTheme.typography.bodyMedium)
        ErrorLine(error)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.planned_retry)) }
    }
}

@Composable
private fun ErrorLine(error: String) {
    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

private fun answerLabel(answer: RsvpAnswer): Int = when (answer) {
    RsvpAnswer.GOING -> R.string.planned_answer_going
    RsvpAnswer.MAYBE -> R.string.planned_answer_maybe
    RsvpAnswer.DECLINED -> R.string.planned_answer_declined
    RsvpAnswer.PENDING -> R.string.planned_answer_pending
}

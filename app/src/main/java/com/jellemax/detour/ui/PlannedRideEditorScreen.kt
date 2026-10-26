package com.jellemax.detour.ui

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.R
import com.jellemax.detour.data.MeetingPoint
import com.jellemax.detour.data.PlannedRide
import com.jellemax.detour.data.PlannedRideDraft
import com.jellemax.detour.data.PlannedRidesState
import com.jellemax.detour.data.PlannedRidesStore
import com.jellemax.detour.data.RouteStore
import com.jellemax.detour.data.SavedPlace
import com.jellemax.detour.data.SavedPlaces
import com.jellemax.detour.data.SavedRoute
import com.jellemax.detour.presentation.PLANNED_DESCRIPTION_MAX_CHARS
import com.jellemax.detour.presentation.PLANNED_TITLE_MAX_CHARS
import com.jellemax.detour.presentation.PlannedRideForm
import com.jellemax.detour.presentation.PlannedRideFormProblem
import com.jellemax.detour.presentation.meetingPointAt
import com.jellemax.detour.presentation.plannedRideFormFrom
import com.jellemax.detour.presentation.plannedStartMs
import com.jellemax.detour.presentation.plannedStartParts
import com.jellemax.detour.presentation.problems
import com.jellemax.detour.presentation.toDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.TimeZone

/**
 * Plan a new ride, or edit one the rider hosts (#755): title, start, meeting point, an optional
 * saved route (#757), description. A null [rideId] is a new ride. Invitees are not edited here (#756).
 *
 * The meeting point is picked from the rider's saved places — the same list the map offers — so
 * a ride meets somewhere the host has already named. Editing starts from the loaded ride; until
 * the list has it, the form is not shown, so a slow first load cannot be overwritten by a blank
 * form. A ride the list no longer holds (deleted on another device) pops back, as the detail
 * screen does.
 *
 * Back is held while a save runs: leaving would cancel this screen's scope between the request
 * and the store's re-read, and leave [PlannedRidesStore] busy with every action disabled.
 */
@Composable
fun PlannedRideEditorScreen(rideId: String?, onBack: () -> Unit, onFinish: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val state by PlannedRidesStore.state.collectAsStateWithLifecycle()
    val ride = rideId?.let { id -> state.rides?.find { it.id == id } }
    var form by remember { mutableStateOf(if (rideId == null) PlannedRideForm() else null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (rideId != null) PlannedRidesStore.reloadIfStale() }
    // Not a key: a new onBack lambda on recomposition must not re-run the check.
    val currentOnBack by rememberUpdatedState(onBack)
    LaunchedEffect(ride, state.rides, state.busy) {
        if (form == null && ride != null) form = plannedRideFormFrom(ride)
        if (rideId != null && rideVanished(ride, state)) currentOnBack()
    }
    BackHandler(enabled = saving) {}

    PlannedScaffold(
        title = stringResource(if (rideId == null) R.string.planned_new else R.string.planned_edit),
        onBack = { if (!saving) onBack() },
    ) {
        val current = form
        if (current == null) {
            Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PlannedRideFields(current, enabled = !saving, onChange = { form = it })
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                SaveButton(current, saving = saving, busy = state.busy) { draft ->
                    saving = true
                    scope.launch {
                        val saved = saveRide(rideId, draft)
                        saving = false
                        if (saved) onFinish()
                    }
                }
            }
        }
    }
}

/** Loaded, settled, and without the ride: deleted elsewhere, so there is nothing to edit. */
private fun rideVanished(ride: PlannedRide?, state: PlannedRidesState) =
    ride == null && state.rides != null && !state.busy

private suspend fun saveRide(rideId: String?, draft: PlannedRideDraft): Boolean =
    if (rideId == null) PlannedRidesStore.create(draft) != null else PlannedRidesStore.update(rideId, draft)

@Composable
private fun SaveButton(form: PlannedRideForm, saving: Boolean, busy: Boolean, onSave: (PlannedRideDraft) -> Unit) {
    Button(
        enabled = !saving && !busy && form.problems(System.currentTimeMillis()).isEmpty(),
        // Re-checked at the tap: a start that was a minute ahead when the form drew may not be now.
        onClick = { form.toDraft(System.currentTimeMillis())?.let(onSave) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Text(stringResource(R.string.planned_form_save))
    }
}

@Composable
private fun PlannedRideFields(form: PlannedRideForm, enabled: Boolean, onChange: (PlannedRideForm) -> Unit) {
    val problems = form.problems(System.currentTimeMillis())
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = form.title,
            onValueChange = { onChange(form.copy(title = it)) },
            label = { Text(stringResource(R.string.planned_form_title)) },
            singleLine = true,
            enabled = enabled,
            isError = PlannedRideFormProblem.TITLE_TOO_LONG in problems,
            supportingText = if (PlannedRideFormProblem.TITLE_TOO_LONG in problems) {
                { Text(stringResource(R.string.planned_form_too_long, PLANNED_TITLE_MAX_CHARS)) }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        )
        StartField(
            startsAtMs = form.startsAtMs,
            inPast = PlannedRideFormProblem.START_NOT_IN_FUTURE in problems,
            enabled = enabled,
            onPick = { onChange(form.copy(startsAtMs = it)) },
        )
        MeetingPointField(form.meetingPoint, enabled, onPick = { onChange(form.copy(meetingPoint = it)) })
        RouteField(form.route, enabled, onPick = { onChange(form.copy(route = it)) })
        OutlinedTextField(
            value = form.description,
            onValueChange = { onChange(form.copy(description = it)) },
            label = { Text(stringResource(R.string.planned_form_description)) },
            minLines = 3,
            enabled = enabled,
            isError = PlannedRideFormProblem.DESCRIPTION_TOO_LONG in problems,
            supportingText = if (PlannedRideFormProblem.DESCRIPTION_TOO_LONG in problems) {
                { Text(stringResource(R.string.planned_form_too_long, PLANNED_DESCRIPTION_MAX_CHARS)) }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The start, picked as a day and then a time. The day the date picker hands back is held
 *  until the time is confirmed, so cancelling the time keeps the start the form already had. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartField(startsAtMs: Long?, inPast: Boolean, enabled: Boolean, onPick: (Long) -> Unit) {
    val context = LocalContext.current
    val zoneId = remember { TimeZone.getDefault().id }
    var pickingDate by remember { mutableStateOf(false) }
    var pickedDay by remember { mutableStateOf<Long?>(null) }
    val initial = startsAtMs?.let { plannedStartParts(it, zoneId) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldLabel(stringResource(R.string.planned_form_start))
        Text(
            startsAtMs?.let { formatDate(it) } ?: stringResource(R.string.planned_form_not_set),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (inPast) {
            Text(
                stringResource(R.string.planned_form_start_past),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedButton(enabled = enabled, onClick = { pickingDate = true }) {
            Text(stringResource(R.string.planned_form_pick_start))
        }

        if (pickingDate) {
            val today = remember { plannedStartParts(System.currentTimeMillis(), zoneId).dateUtcMidnightMs }
            val dateState = rememberDatePickerState(
                initialSelectedDateMillis = initial?.dateUtcMidnightMs,
                selectableDates = object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= today
                },
            )
            DatePickerDialog(
                onDismissRequest = { pickingDate = false },
                confirmButton = {
                    TextButton(
                        enabled = dateState.selectedDateMillis != null,
                        onClick = { pickedDay = dateState.selectedDateMillis; pickingDate = false },
                    ) { Text(stringResource(R.string.planned_form_next)) }
                },
                dismissButton = {
                    TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.planned_form_cancel)) }
                },
            ) { DatePicker(dateState) }
        }

        val day = pickedDay
        if (day != null) {
            val timeState = rememberTimePickerState(
                initialHour = initial?.hour ?: DEFAULT_START_HOUR,
                initialMinute = initial?.minute ?: 0,
                is24Hour = DateFormat.is24HourFormat(context),
            )
            AlertDialog(
                onDismissRequest = { pickedDay = null },
                text = { TimePicker(timeState) },
                confirmButton = {
                    TextButton(onClick = {
                        onPick(plannedStartMs(day, timeState.hour, timeState.minute, zoneId))
                        pickedDay = null
                    }) { Text(stringResource(R.string.planned_form_save)) }
                },
                dismissButton = {
                    TextButton(onClick = { pickedDay = null }) { Text(stringResource(R.string.planned_form_cancel)) }
                },
            )
        }
    }
}

@Composable
private fun MeetingPointField(point: MeetingPoint?, enabled: Boolean, onPick: (MeetingPoint) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldLabel(stringResource(R.string.planned_meeting_point))
        Text(
            when {
                point == null -> stringResource(R.string.planned_form_not_set)
                // Same fallback as the detail screen: no label, so the coordinates.
                else -> point.label ?: String.format(Locale.ROOT, "%.5f, %.5f", point.latitude, point.longitude)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(enabled = enabled, onClick = { picking = true }) {
            Text(stringResource(R.string.planned_form_pick_meeting))
        }
        if (picking) {
            SavedPlacePicker(
                onPick = { place ->
                    onPick(meetingPointAt(place.name, place.location.lat, place.location.lon))
                    picking = false
                },
                onDismiss = { picking = false },
            )
        }
    }
}

@Composable
private fun SavedPlacePicker(onPick: (SavedPlace) -> Unit, onDismiss: () -> Unit) {
    val places by SavedPlaces.places.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.planned_form_pick_meeting)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (places.isEmpty()) {
                    Text(stringResource(R.string.planned_form_no_places), style = MaterialTheme.typography.bodyMedium)
                }
                places.forEach { place ->
                    Text(
                        place.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(place) }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.planned_form_cancel)) } },
    )
}

/**
 * The ride's route: one of the rider's saved routes, a kept spin included, or none. What is
 * attached is a copy (BACKEND_SPEC.md §8.1), so the name shown is the copy's, not the saved
 * route's current one.
 */
@Composable
private fun RouteField(route: SavedRoute?, enabled: Boolean, onPick: (SavedRoute?) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldLabel(stringResource(R.string.planned_route))
        Text(
            route?.name ?: stringResource(R.string.planned_form_not_set),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = enabled, onClick = { picking = true }) {
                Text(stringResource(R.string.planned_form_pick_route))
            }
            if (route != null) {
                TextButton(enabled = enabled, onClick = { onPick(null) }) {
                    Text(stringResource(R.string.planned_form_remove_route))
                }
            }
        }
        if (picking) {
            SavedRoutePicker(
                onPick = { picked ->
                    onPick(picked)
                    picking = false
                },
                onDismiss = { picking = false },
            )
        }
    }
}

@Composable
private fun SavedRoutePicker(onPick: (SavedRoute) -> Unit, onDismiss: () -> Unit) {
    // ensureLoaded reads the file on first use; off the main thread, as TripDetailScreen does.
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { RouteStore.ensureLoaded() } }
    val routes by RouteStore.routes.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.planned_form_pick_route)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (routes.isEmpty()) {
                    Text(stringResource(R.string.planned_form_no_routes), style = MaterialTheme.typography.bodyMedium)
                }
                routes.forEach { route ->
                    Text(
                        route.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(route) }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.planned_form_cancel)) } },
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
}

/** Where the time picker opens for a new ride: a morning start, the usual for a group ride. */
private const val DEFAULT_START_HOUR = 9

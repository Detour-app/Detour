package com.jellemax.maproulette.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jellemax.maproulette.data.SyncClient
import com.jellemax.maproulette.data.TravelMode
import com.jellemax.maproulette.data.Trip
import com.jellemax.maproulette.data.TripStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Loaded off the main thread: reading + JSON-parsing the store inside a
    // remember{} ran during composition and stalled the first frame (~125 ms on a
    // large history), which is what made opening and scrolling feel stuck. Null
    // means "still loading"; the reloads after an edit go through IO too.
    var trips by remember { mutableStateOf<List<Trip>?>(null) }
    LaunchedEffect(Unit) {
        trips = withContext(Dispatchers.IO) { TripStore.load(context) }
    }
    fun reload() = scope.launch {
        trips = withContext(Dispatchers.IO) { TripStore.load(context) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trip history") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val loaded = trips
        if (loaded != null && loaded.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No trips yet", style = MaterialTheme.typography.bodyLarge)
            }
        } else if (loaded != null) {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(loaded, key = { it.startTimeMs }) { trip ->
                    TripCard(
                        trip = trip,
                        onChangeMode = { newMode ->
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    TripStore.updateMode(context, trip.startTimeMs, newMode)
                                }
                                reload()
                                // Push the correction so it survives a reinstall / other devices.
                                SyncClient.syncQuietly(context)
                            }
                        },
                        onDelete = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    TripStore.delete(context, trip.startTimeMs)
                                }
                                reload()
                            }
                        },
                    )
                }
            }
        }
    }
}

/** One trip, with a vehicle picker (fix a misclassification) and a delete
 *  action (drop a false-positive detection). */
@Composable
private fun TripCard(trip: Trip, onChangeMode: (TravelMode) -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${trip.mode.label} · ${formatDate(trip.startTimeMs)}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Change vehicle",
                            Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        TravelMode.entries.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m.label + if (m == trip.mode) " ✓" else "") },
                                onClick = { menuOpen = false; if (m != trip.mode) onChangeMode(m) },
                            )
                        }
                    }
                }
                IconButton(onClick = { confirmDelete = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete trip",
                        Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TripStat("Duration", formatDurationHistory(trip.durationMs))
                TripStat("Distance", formatDistanceKm(trip.distanceMeters))
                TripStat("Avg", formatSpeedKmh(trip.avgSpeedMps))
                TripStat("Top", formatSpeedKmh(trip.topSpeedMps))
            }
            // A trip only carries the readings its vehicle has; printing "0°"
            // for a car is worse than printing nothing.
            if (trip.mode.tracksMotion) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (trip.mode.tracksLean) {
                        TripStat("Max lean", formatLeanAngle(trip.maxLeanAngleDeg))
                    }
                    if (trip.mode.tracksGForce) {
                        TripStat("Max G", formatGForce(trip.maxGForce))
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this trip?") },
            text = {
                Text("${trip.mode.label} · ${formatDate(trip.startTimeMs)} — " +
                    "${formatDistanceKm(trip.distanceMeters)}. This can't be undone.")
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TripStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

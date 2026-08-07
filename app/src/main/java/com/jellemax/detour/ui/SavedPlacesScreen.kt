package com.jellemax.detour.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.data.GeocodeResult
import com.jellemax.detour.data.Geocoder
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.SavedPlace
import com.jellemax.detour.data.SavedPlaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Manage shortcut locations: add by searching an address, rename, delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedPlacesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { SavedPlaces.ensureLoaded() }
    val places by SavedPlaces.places.collectAsStateWithLifecycle()
    var addOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SavedPlace?>(null) }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { SubScreenTopBar("Saved places", onBack, scrollBehavior) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { addOpen = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add place") },
            )
        },
    ) { padding ->
        if (places.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Default.Place, contentDescription = null,
                    Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("No saved places yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Add Home, Work, or anywhere you spin to often.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(places, key = { it.id }) { place ->
                    Card {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { editing = place }
                                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Place, contentDescription = null)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(place.name, style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold)
                                Text(
                                    "%.5f, %.5f".format(place.location.lat, place.location.lon),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { SavedPlaces.remove(place.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete ${place.name}",
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    if (addOpen) {
        AddPlaceDialog(
            onSave = { name, location ->
                SavedPlaces.add(name, location)
                addOpen = false
            },
            onDismiss = { addOpen = false },
        )
    }
    editing?.let { place ->
        RenameDialog(
            initial = place.name,
            onSave = { SavedPlaces.rename(place.id, it); editing = null },
            onDismiss = { editing = null },
        )
    }
}

/** Search an address, name it, save it. */
@Composable
private fun AddPlaceDialog(
    onSave: (String, LatLon) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeocodeResult>>(emptyList()) }
    var picked by remember { mutableStateOf<GeocodeResult?>(null) }
    var searching by remember { mutableStateOf(false) }

    // Debounced live search, same shape as the map's search dialog.
    LaunchedEffect(query) {
        picked = null
        if (query.length < 3) { results = emptyList(); return@LaunchedEffect }
        delay(400)
        searching = true
        results = try {
            withContext(Dispatchers.IO) { Geocoder.search(query, null) }
        } catch (e: Exception) {
            emptyList()
        }
        searching = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add place") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (Home, Work…)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Address or place") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                val chosen = picked
                if (chosen != null) {
                    Text("Selected: ${chosen.name}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                } else {
                    results.take(5).forEach { r ->
                        Text(
                            r.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { picked = r; query = r.name }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            val chosen = picked
            TextButton(
                onClick = { if (chosen != null) onSave(name, chosen.location) },
                enabled = chosen != null && name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename place") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

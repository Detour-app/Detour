package com.jellemax.detour.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.data.GeocodeResult
import com.jellemax.detour.data.Geocoder
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.SavedPlaces
import com.jellemax.detour.presentation.PlaceRow
import com.jellemax.detour.presentation.PlacesPresenter
import com.jellemax.detour.presentation.placesStateFrom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Manage shortcut locations: add by searching an address, rename, delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedPlacesScreen(onBack: () -> Unit) {
    val presenter = remember { PlacesPresenter() }
    val state by presenter.state.collectAsStateWithLifecycle()
    // PlacesPresenter.refresh() is `suspend` but never actually suspends — it
    // blocks on disk internally (SavedPlaces.ensureLoaded()), so this hop to
    // Dispatchers.IO is what keeps that off the main thread. See its KDoc.
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { presenter.refresh() } }

    // The row list is derived here from SavedPlaces.places directly rather
    // than from presenter.state. add/rename/remove below are synchronous
    // mutations that write straight through that StateFlow before returning;
    // presenter.state only ever tracks whether the initial load happened, so
    // reading a cached list from it would leave the screen stale after any of
    // the three — see PlacesPresenter's KDoc. state.loaded (which DOES only
    // change once, at the initial load) still gates the empty-state text
    // below so it doesn't flash before that first read completes.
    val places by SavedPlaces.places.collectAsStateWithLifecycle()
    val rows = remember(places) { placesStateFrom(places) }

    var addOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PlaceRow?>(null) }
    var menuOpenFor by remember { mutableStateOf<Long?>(null) }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SubScreenTopBar("Saved places", onBack, scrollBehavior) {
                IconButton(onClick = { addOpen = true }, modifier = Modifier.padding(end = 8.dp)) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Add place")
                    }
                }
            }
        },
    ) { padding ->
        if (!state.loaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (rows.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Rounded.Place, contentDescription = null,
                    Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("No saved places yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Add Home, Work, or anywhere you stop often.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                ListCard {
                    rows.forEachIndexed { index, row ->
                        PlaceListRow(
                            row = row,
                            menuOpen = menuOpenFor == row.id,
                            onOpenMenu = { menuOpenFor = row.id },
                            onDismissMenu = { menuOpenFor = null },
                            onRename = { menuOpenFor = null; editing = row },
                            onDelete = { menuOpenFor = null; SavedPlaces.remove(row.id) },
                        )
                        if (index < rows.lastIndex) {
                            CardDivider()
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
    editing?.let { row ->
        RenameDialog(
            initial = row.name,
            onSave = { SavedPlaces.rename(row.id, it); editing = null },
            onDismiss = { editing = null },
        )
    }
}

/**
 * One saved place: icon, name, coordinate subtitle, and an overflow menu for
 * rename/delete — reused inside [ListCard] with a divider between rows, the
 * same shell [SocialScreen] uses for its two rows.
 *
 * The prototype's per-place category icon, street-address subtitle and pin
 * toggle are not built here: [com.jellemax.detour.data.SavedPlace] carries no
 * category or pinned flag, and only a coordinate is stored, not a
 * reverse-geocoded address.
 */
@Composable
private fun PlaceListRow(
    row: PlaceRow,
    menuOpen: Boolean,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Place, contentDescription = null,
                Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                row.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                fontWeight = FontWeight.Medium,
            )
            Text(
                row.subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = onOpenMenu) {
                Icon(
                    Icons.Rounded.MoreVert, contentDescription = "More for ${row.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = onRename)
                DropdownMenuItem(text = { Text("Delete") }, onClick = onDelete)
            }
        }
    }
}

/** Search an address, name it, save it. */
@Composable
private fun AddPlaceDialog(
    onSave: (String, LatLon) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeocodeResult>>(emptyList()) }
    var picked by remember { mutableStateOf<GeocodeResult?>(null) }
    var searching by remember { mutableStateOf(false) }

    // Debounced live search, same shape as the map's search dialog. Picking a
    // result sets `query = picked.name`, which re-keys this effect — only
    // clear `picked` when the query no longer matches what was picked, so
    // that self-triggered restart doesn't null out the just-made selection.
    LaunchedEffect(query) {
        if (query != picked?.name) picked = null
        if (query == picked?.name) return@LaunchedEffect
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

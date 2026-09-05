package com.jellemax.detour.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.data.GeocodeResult
import com.jellemax.detour.data.Geocoder
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RecentSearchStore
import com.jellemax.detour.tracking.TripTrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The place [SearchScreen] just picked, waiting for [MapScreen] to apply it.
 *
 * Search is its own [com.jellemax.detour.nav.Destination] and `NavDisplay` has
 * no scene strategy, so pushing it disposes MapScreen's whole composition —
 * the two are never composed together the way the old `SearchDialog` and
 * `MapScreen` were, and a pick can no longer be written into MapScreen's state
 * inline the way `SearchDialog`'s `onPick` did.
 *
 * One-shot, in the same shape [PendingSignIn][com.jellemax.detour.auth.PendingSignIn]
 * already uses for a value crossing exactly this kind of boundary: set once
 * here, read by MapScreen once it recomposes on the way back, cleared by that
 * reader so a later return to Map (a Hub round trip, say) doesn't replay a
 * stale pick.
 */
internal object PendingSearchPick {
    private val _result = MutableStateFlow<GeocodeResult?>(null)
    val result: StateFlow<GeocodeResult?> = _result.asStateFlow()

    fun set(r: GeocodeResult) {
        _result.value = r
    }

    fun clear() {
        _result.value = null
    }
}

/**
 * Full-screen place search: type to get live suggestions, tap one to make it
 * the destination. Opens with the keyboard up, recents show first, and there
 * is no Search button — results stream in as you type.
 *
 * Promoted from the `SearchDialog` this replaces, with the same debounce, the
 * same recents-then-live-results merge, and the same proximity bias — this is
 * the reference search implementation the app's other surfaces are meant to
 * converge on, not a place to redesign. The pick itself does not come back
 * through [onBack]; see [PendingSearchPick].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()

    // The same accuracy filter MapScreen applies to this fix, read
    // independently: the two screens are never composed at the same time, so
    // there is no `myLocation` of MapScreen's to be handed instead.
    //
    // Sticky, the way the `myLocation` it stands in for was: the fix this is
    // derived from goes in and out of the accuracy gate as the sky opens and
    // closes, and a search must keep biasing off the last good position rather
    // than losing its proximity ranking the moment one fix comes back wide.
    val liveFix by TripTrackingService.lastFix.collectAsStateWithLifecycle()
    var near by remember { mutableStateOf<LatLon?>(null) }
    LaunchedEffect(liveFix) {
        liveFix?.takeIf { it.accuracyMeters <= 100f }?.let { near = LatLon(it.lat, it.lon) }
    }

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeocodeResult>>(emptyList()) }
    var recents by remember { mutableStateOf<List<GeocodeResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    // RecentSearchStore.load() is a synchronous file read (RecentSearchStore.kt),
    // so it runs on Dispatchers.IO instead of inline in a remember{} the way
    // the dialog version did, which blocked this screen's first frame.
    LaunchedEffect(Unit) {
        recents = withContext(Dispatchers.IO) { RecentSearchStore.load() }
        if (query.isEmpty()) results = recents
    }
    val recentNames = remember(recents) { recents.map { it.name }.toSet() }

    fun pick(r: GeocodeResult) {
        // Same fire-and-forget-off-thread reasoning as the load above: save()
        // re-reads the file before writing it (RecentSearchStore.save), so it
        // is just as blocking. Nothing here depends on it finishing before the
        // pop below.
        scope.launch(Dispatchers.IO) { RecentSearchStore.save(r) }
        PendingSearchPick.set(r)
        onBack()
    }

    // Start with the keyboard up so the user types straight away.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Live, debounced suggestions. Matching recents show instantly, then a
    // single Photon lookup runs — it already blends the query match with
    // proximity to the user, so nearby streets and POIs rank first while a
    // famous far place still surfaces where it belongs. Recents are kept on
    // top, then deduped against hits.
    //
    // Keyed on `recents` too, unlike the dialog version: recents now load
    // asynchronously (see above), so this has to re-run once they land to
    // show them instead of nothing on a screen opened with an empty query.
    LaunchedEffect(query, recents) {
        val q = query.trim()
        error = null
        if (q.length < 2) {
            results = if (q.isEmpty()) recents
                else recents.filter { it.name.contains(q, ignoreCase = true) }
            searching = false
            return@LaunchedEffect
        }
        val recentMatches = recents.filter { it.name.contains(q, ignoreCase = true) }
        results = recentMatches
        delay(300)
        searching = true
        try {
            val hits = withContext(Dispatchers.IO) { Geocoder.search(q, near) }
            val seen = HashSet(recentMatches.map { it.name })
            val merged = ArrayList(recentMatches)
            for (hit in hits) if (seen.add(hit.name)) merged.add(hit)
            results = merged
            error = if (merged.isEmpty()) "No results" else null
        } catch (e: Exception) {
            error = e.message ?: "Search failed"
        }
        searching = false
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { SubScreenTopBar("Search", onBack, scrollBehavior) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search address or place") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (searching) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Rounded.Clear, contentDescription = "Clear")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .focusRequester(focusRequester),
            )
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (results.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    ListCard {
                        results.forEachIndexed { index, r ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { pick(r) }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (r.name in recentNames) Icons.Rounded.History else Icons.Rounded.Place,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(16.dp))
                                Text(r.name, style = MaterialTheme.typography.bodyLarge)
                            }
                            if (index != results.lastIndex) CardDivider()
                        }
                    }
                }
            }
        }
    }
}

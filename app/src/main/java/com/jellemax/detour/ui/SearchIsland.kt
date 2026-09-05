package com.jellemax.detour.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The tallest the results card is allowed to grow. Sixteen rows can appear at
 *  once — `Geocoder.search` caps at eight hits and `RecentSearchStore` holds
 *  eight entries — which unbounded would bury the map the island floats over.
 *  Past this the card scrolls instead of growing. */
private val ISLAND_MAX_HEIGHT = 360.dp

/**
 * Destination search: the home sheet's "Where to?" bar *is* the text field, and
 * results rise out of it in a glass card anchored directly above.
 *
 * Composed inside `MapScreen` on purpose, rather than as a `Dialog` or a
 * destination of its own. `NavDisplay` has no scene strategy, so pushing a
 * destination disposes MapScreen's whole composition — camera authority,
 * follow loop and all — and a pick handed back to a screen that was rebuilt
 * underneath it raced its own camera loops back to the rider seconds after
 * landing. Inline, MapScreen is never disposed: [onPick] writes its state and
 * glides its live camera directly, the way this search always did.
 *
 * The search itself is unchanged: same 300 ms debounce, same two-character
 * floor, same recents-then-live-hits merge, same sticky proximity bias. That
 * behaviour is the reference the car and iOS surfaces are meant to converge
 * on, not a place to redesign.
 *
 * ## Growing upward
 *
 * The results are the Column's *first* child and the bar its second, so from a
 * bottom anchor the list expands up the screen rather than down over the map.
 * Two things follow, and neither is optional:
 *
 * - the results carry `weight(1f, fill = false)`, so [modifier] must resolve to
 *   a bounded height. `HomeSheet` supplies that, and a weighted child in an
 *   unbounded Column measures to nothing at all — with no compiler signal.
 * - the keyboard covers exactly the region the results grow into, so whatever
 *   holds this must consume `WindowInsets.ime`. `HomeSheet` does.
 *
 * @param open whether the island is showing. Hoisted so a tap on the map can
 *   close it, the same way `layersOpen` is.
 */
@Composable
fun SearchIsland(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    username: String,
    onAvatarClick: () -> Unit,
    onPick: (GeocodeResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    // The geocoder's proximity bias, held sticky: the fix it derives from goes
    // in and out of the 100 m accuracy gate as the sky opens and closes, and a
    // search must keep biasing off the last good position rather than losing
    // its ranking the moment one fix comes back wide.
    //
    // Read from the tracker rather than taking MapScreen's `myLocation`, which
    // looks like the same value but is not: that one also accepts an ungated
    // write from the fused-location fallback, and a 2 km-accurate fix is fine
    // for centring a map and wrong for ranking street names.
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
    val recentNames = remember(recents) { recents.map { it.name }.toSet() }

    // Back dismisses the island before it reaches the map, where back leaves
    // the app.
    BackHandler(enabled = open) { onOpenChange(false) }

    // Opening loads recents and takes the keyboard; closing resets the query so
    // the next open starts clean, the way a freshly composed dialog used to.
    //
    // RecentSearchStore.load() is a synchronous file read, so it runs on
    // Dispatchers.IO rather than inline in a remember{} — and only on open, so
    // a map session that never searches never touches the file. Reloading per
    // open also keeps the list current after a pick appends to it.
    LaunchedEffect(open) {
        if (!open) {
            query = ""
            error = null
            return@LaunchedEffect
        }
        recents = withContext(Dispatchers.IO) { RecentSearchStore.load() }
        focusRequester.requestFocus()
    }

    fun pick(r: GeocodeResult) {
        // Same fire-and-forget-off-thread reasoning as the load above: save()
        // re-reads the file before writing it, so it is just as blocking, and
        // nothing here waits on it.
        scope.launch(Dispatchers.IO) { RecentSearchStore.save(r) }
        onOpenChange(false)
        onPick(r)
    }

    // Live, debounced suggestions. Matching recents show instantly, then a
    // single Photon lookup runs — it already blends the query match with
    // proximity to the user, so nearby streets and POIs rank first while a
    // famous far place still surfaces where it belongs. Recents are kept on
    // top, then deduped against hits.
    //
    // Keyed on `recents` too: they load asynchronously (see above), so this has
    // to re-run once they land to show them instead of nothing.
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

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (open && (results.isNotEmpty() || error != null)) {
            // Glass, not the default opaque ListCard surface: the bar this
            // hangs off is frosted and so is the map chrome across from it, and
            // an opaque slab between them reads as a different app.
            //
            // Weighted, and that is what makes the upward growth safe: the bar
            // below is measured first at its intrinsic height, and this takes
            // whatever the sheet has left over the keyboard. Unweighted it
            // would claim ISLAND_MAX_HEIGHT off the top and push the bar — the
            // thing being typed into — off the screen on a short phone.
            ListCard(
                modifier = Modifier.weight(1f, fill = false),
                colors = glassCardColors(),
                borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            ) {
                Column(
                    Modifier
                        .heightIn(max = ISLAND_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState()),
                ) {
                    error?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        )
                        if (results.isNotEmpty()) CardDivider()
                    }
                    results.forEachIndexed { index, r ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { pick(r) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (r.name in recentNames) Icons.Rounded.History
                                    else Icons.Rounded.Place,
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
        Card(
            modifier = Modifier.fillMaxWidth().glassBorder(CircleShape),
            shape = CircleShape,
            colors = CardDefaults.cardColors(containerColor = glassContainerColor()),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (open) Modifier else Modifier.clickable { onOpenChange(true) })
                    .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                if (open) {
                    // BasicTextField rather than an OutlinedTextField: this bar
                    // is what the island hangs off, and a 56 dp text field
                    // dropped into a 40 dp pill would jump the anchor — and the
                    // results with it — on every open.
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge
                            .copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.weight(1f).focusRequester(focusRequester),
                        decorationBox = { field ->
                            if (query.isEmpty()) {
                                Text(
                                    "Search address or place",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            field()
                        },
                    )
                } else {
                    Text(
                        "Where to?",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Fixed at the avatar's size whatever it holds, for the same
                // no-jump reason as the field above.
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    when {
                        !open -> Box(
                            Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable(onClick = onAvatarClick),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                username.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                        searching -> CircularProgressIndicator(
                            Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        query.isNotEmpty() -> Box(
                            Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .clickable { query = "" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.Clear,
                                contentDescription = "Clear",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

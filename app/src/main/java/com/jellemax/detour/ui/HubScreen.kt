package com.jellemax.detour.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.data.Account
import com.jellemax.detour.data.BadgeStore
import com.jellemax.detour.data.Coverage
import com.jellemax.detour.data.RiderStats
import com.jellemax.detour.data.RiderTotals
import com.jellemax.detour.data.RouteStore
import com.jellemax.detour.data.SavedPlaces
import com.jellemax.detour.data.SyncClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class HubData(
    val stats: RiderStats,
    val badgesEarned: Int,
    val tripCount: Int,
)

/**
 * "You" screen: reached from the avatar on the map's search pill, and the one
 * place the five destination screens now hang off. Their own back arrows all
 * return here — the map is reached only from Hub's back arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubScreen(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenBadges: () -> Unit,
    onOpenFriends: () -> Unit,
    onOpenCircles: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSavedPlaces: () -> Unit,
    onOpenRoutes: () -> Unit,
) {
    val context = LocalContext.current
    val username by Account.username.collectAsStateWithLifecycle()
    val signedIn = Account.signedIn
    val savedPlaces by SavedPlaces.places.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { RouteStore.ensureLoaded() }
    val savedRoutes by RouteStore.routes.collectAsStateWithLifecycle()

    // Coverage.compute walks every trace point against every boundary, but
    // caches the result — only the first call after trace/municipality data
    // changes pays that cost. Still off-main, behind a produceState, with
    // em-dashes standing in until it lands.
    val data by produceState<HubData?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            val coverage = Coverage.compute()
            val stats = BadgeStore.stats(coverage)
            val earned = BadgeStore.refresh(stats).states.count { it.earned }
            // stats.tripCount is the same number this used to reopen and
            // re-parse trips.json for — a second full read of the file, on the
            // most-visited non-map screen.
            HubData(stats, earned, stats.tripCount)
        }
        // After the value is on screen, never before: if the record has aged
        // past its TTL this folds the whole history, and the rider is not made
        // to wait on it. No-op when the record is fresh.
        withContext(Dispatchers.IO) { RiderTotals.refreshIfStale() }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { SubScreenTopBar("You", onBack, scrollBehavior) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AccountCard(
                username = username,
                signedIn = signedIn,
                synced = SyncClient.configured() && signedIn,
                onClick = if (!signedIn) onOpenFriends else null,
            )

            StatsCard(data)

            HubRow(
                icon = Icons.Outlined.Place,
                title = "Saved places",
                subtitle = savedPlaces.take(3).joinToString(", ") { it.name }
                    .ifBlank { "None yet" },
                onClick = onOpenSavedPlaces,
            )
            HubRow(
                icon = Icons.Outlined.Route,
                title = "Routes",
                subtitle = if (savedRoutes.isEmpty()) "None saved yet"
                    else "${savedRoutes.size} saved",
                onClick = onOpenRoutes,
            )
            HubRow(
                icon = Icons.Outlined.History,
                title = "Trip history",
                subtitle = data?.let { "${it.tripCount} trips" } ?: "—",
                onClick = onOpenHistory,
            )
            HubRow(
                icon = Icons.Outlined.EmojiEvents,
                title = "Badges",
                subtitle = data?.let { "${it.badgesEarned}/${BadgeStore.ALL.size} earned" } ?: "—",
                onClick = onOpenBadges,
            )
            HubRow(
                icon = Icons.Outlined.People,
                title = "Friends",
                subtitle = if (signedIn) "Compare rides and totals" else "Sign in to add friends",
                onClick = onOpenFriends,
            )
            HubRow(
                icon = Icons.Outlined.Group,
                title = "Circles",
                subtitle = if (signedIn) "Share where you are with people you trust"
                    else "Sign in to start a circle",
                onClick = onOpenCircles,
            )
            HubRow(
                icon = Icons.Outlined.Settings,
                title = "Settings",
                subtitle = "Map, tracking, fog and servers",
                onClick = onOpenSettings,
            )
        }
    }
}

@Composable
private fun AccountCard(
    username: String,
    signedIn: Boolean,
    synced: Boolean,
    onClick: (() -> Unit)?,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    username.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Column {
                Text(
                    username.ifBlank { "Signed out" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (signedIn) (if (synced) "Synced" else "Sync not configured")
                    else "Sign in to sync & friends",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatsCard(data: HubData?) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            HubStat("Total km", data?.let { "%,.0f".format(it.stats.totalDistanceMeters / 1000) } ?: "—")
            HubStat("Rides", data?.let { "${it.stats.tripCount}" } ?: "—")
            HubStat("Places", data?.let { "${it.stats.municipalitiesVisited}" } ?: "—")
            HubStat("Badges", data?.let { "${it.badgesEarned}" } ?: "—")
        }
    }
}

@Composable
private fun HubStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

/** One destination row: icon tile, title + subtitle, trailing chevron. Shared
 *  look with the Settings root's rows so Hub and Settings read as one system. */
@Composable
fun HubRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailingCount: Int? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null,
                    Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (trailingCount != null && trailingCount > 0) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        "$trailingCount",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Icon(
                Icons.Outlined.ChevronRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

package com.jellemax.maproulette.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jellemax.maproulette.data.BadgeKind
import com.jellemax.maproulette.data.BadgeState
import com.jellemax.maproulette.data.BadgeStore
import com.jellemax.maproulette.data.Coverage
import com.jellemax.maproulette.data.RiderStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val BadgeKind.icon: ImageVector
    get() = when (this) {
        BadgeKind.DISTANCE -> Icons.Default.Route
        BadgeKind.TOP_SPEED -> Icons.Default.Speed
        BadgeKind.TRIP_DISTANCE -> Icons.Default.Flag
        BadgeKind.MUNICIPALITY -> Icons.Default.LocationCity
        BadgeKind.COVERAGE -> Icons.Default.Map
    }

/** Formats a badge value in the unit its threshold is expressed in. */
private fun BadgeKind.format(value: Double): String = when (this) {
    BadgeKind.DISTANCE, BadgeKind.TRIP_DISTANCE -> "%,.0f km".format(value / 1000)
    BadgeKind.TOP_SPEED -> "%.0f km/h".format(value)
    BadgeKind.MUNICIPALITY -> "%.0f".format(value)
    BadgeKind.COVERAGE -> "%.0f%%".format(value)
}

private data class ScreenData(
    val states: List<BadgeState>,
    val coverage: List<Coverage.Entry>,
    val stats: RiderStats,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BadgesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // Coverage walks every trace point against every boundary; keep it off the
    // main thread, and off the composition's hot path.
    val data by produceState<ScreenData?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            val coverage = Coverage.compute(context)
            val stats = BadgeStore.stats(context, coverage)
            ScreenData(BadgeStore.refresh(context, stats).states, coverage, stats)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Badges") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val loaded = data
        if (loaded == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SummaryCard(loaded.stats, loaded.states.count { it.earned }) }

            // Badges first: they're the compact, scannable summary this screen
            // opens for. Coverage (a whole map's worth of municipality rows)
            // is the deep-dive content, so it goes after — not the first
            // thing between the summary card and the badges you came to check.
            for (kind in BadgeKind.entries) {
                val states = loaded.states.filter { it.def.kind == kind }
                if (states.isEmpty()) continue
                item { SectionHeader(kind.label) }
                items(states.size) { i -> BadgeRow(states[i]) }
            }

            item { SectionHeader("Coverage") }
            if (loaded.coverage.isEmpty()) {
                item {
                    Text(
                        "Drive somewhere and Map Roulette will look up which " +
                            "municipality you were in, then track how much of it " +
                            "you've covered.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            } else {
                items(loaded.coverage.size) { i -> CoverageRow(loaded.coverage[i]) }
            }
        }
    }
}

@Composable
private fun SummaryCard(stats: RiderStats, earnedCount: Int) {
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
            SummaryStat("Badges", "$earnedCount / ${BadgeStore.ALL.size}")
            SummaryStat("Total", "%,.0f km".format(stats.totalDistanceMeters / 1000))
            SummaryStat("Rides", "${stats.tripCount}")
            SummaryStat("Places", "${stats.municipalitiesVisited}")
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp, start = 4.dp),
    )
}

@Composable
private fun CoverageRow(entry: Coverage.Entry) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(entry.name, style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold)
                Text("%.1f%%".format(entry.percent),
                    style = MaterialTheme.typography.bodyLarge)
            }
            LinearProgressIndicator(
                progress = { (entry.percent / 100.0).toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${entry.exploredCells} of ${entry.totalCells} areas explored",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BadgeRow(state: BadgeState) {
    val kind = state.def.kind
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (state.earned) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.surfaceContainerLow,
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
                    .size(48.dp)
                    .clip(CircleShape)
                    .alpha(if (state.earned) 1f else 0.35f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    kind.icon,
                    contentDescription = null,
                    Modifier.size(32.dp),
                    tint = if (state.earned) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        state.def.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (state.earned) FontWeight.Bold else FontWeight.Normal,
                    )
                    Text(
                        kind.format(state.def.threshold),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.earned) {
                    Text(
                        "Earned ${formatDate(state.earnedAtMs!!)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        kind.format(state.value),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

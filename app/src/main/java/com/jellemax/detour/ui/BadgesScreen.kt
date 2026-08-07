package com.jellemax.detour.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.LocationCity
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jellemax.detour.data.BadgeKind
import com.jellemax.detour.data.BadgeState
import com.jellemax.detour.data.BadgeStore
import com.jellemax.detour.data.Coverage
import com.jellemax.detour.data.RiderStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val BadgeKind.icon: ImageVector
    get() = when (this) {
        BadgeKind.DISTANCE -> Icons.Outlined.Route
        BadgeKind.TOP_SPEED -> Icons.Outlined.Speed
        BadgeKind.TRIP_DISTANCE -> Icons.Outlined.Flag
        BadgeKind.MUNICIPALITY -> Icons.Outlined.LocationCity
        BadgeKind.COVERAGE -> Icons.Outlined.Map
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
            val coverage = Coverage.compute()
            val stats = BadgeStore.stats(coverage)
            ScreenData(BadgeStore.refresh(stats).states, coverage, stats)
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { SubScreenTopBar("Badges", onBack, scrollBehavior) },
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
                // LazyVerticalGrid inside a LazyColumn nests badly (both want to
                // scroll); chunking into rows of 3 keeps everything in one list.
                for (row in states.chunked(3)) {
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            row.forEach { state ->
                                BadgeCell(state, Modifier.weight(1f))
                            }
                            repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            item { SectionHeader("Coverage") }
            if (loaded.coverage.isEmpty()) {
                item {
                    Text(
                        "Drive somewhere and Detour will look up which " +
                            "municipality you were in, then track how much of it " +
                            "you've covered.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** One badge in the 3-column grid: a 44dp medal (gold radial gradient once
 *  earned, a progress arc around a dimmed icon while locked) plus a two-line
 *  caption. Replaces the old full-width row — three per screen instead of one
 *  is what makes a whole badge kind scannable without scrolling past it. */
@Composable
private fun BadgeCell(state: BadgeState, modifier: Modifier = Modifier) {
    val kind = state.def.kind
    Column(
        modifier.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Medal(kind.icon, state.earned, state.progress)
        Text(
            state.def.title,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            fontWeight = if (state.earned) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        Text(
            if (state.earned) "Earned ${formatDate(state.earnedAtMs!!)}"
            else "${kind.format(state.value)} / ${kind.format(state.def.threshold)}",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = if (state.earned) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
private fun Medal(icon: ImageVector, earned: Boolean, progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.size(44.dp), contentAlignment = Alignment.Center) {
        if (earned) {
            val primary = MaterialTheme.colorScheme.primary
            val primaryContainer = MaterialTheme.colorScheme.primaryContainer
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(primaryContainer, primary))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null,
                    Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        } else {
            val trackColor = MaterialTheme.colorScheme.outlineVariant
            val progressColor = MaterialTheme.colorScheme.primary
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon, contentDescription = null,
                    Modifier.size(20.dp).alpha(0.4f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 3.dp.toPx()
                drawArc(
                    trackColor, startAngle = -90f, sweepAngle = 360f, useCenter = false,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke),
                )
                drawArc(
                    progressColor, startAngle = -90f, sweepAngle = 360f * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke),
                )
            }
        }
    }
}

package com.jellemax.detour.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jellemax.detour.presentation.RiderCardState

/**
 * The card a tap on a convoy peer or circle member opens (issue #156): the
 * rider's handle and what the app already knows about how they're moving —
 * speed, heading, fix age, distance from own position. Read-only, no network.
 *
 * Stateless: [state] is re-derived from the live peer/circle collections by
 * MapScreen every recomposition, so the numbers here update in place without
 * the card being reopened, and a peer whose fix has expired arrives here with
 * [RiderCardState.stale] set and its speed/heading already dropped.
 */
@Composable
internal fun RiderCard(
    state: RiderCardState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.glassBorder(MaterialTheme.shapes.extraLarge),
        shape = MaterialTheme.shapes.extraLarge,
        colors = glassCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        state.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (state.stale) "Last seen ${state.ageText}" else "Updated ${state.ageText}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Dismiss")
                }
            }
            val stats = buildList {
                state.speedText?.let { add("Speed" to it) }
                state.headingText?.let { add("Heading" to it) }
                state.distanceText?.let { add("Away" to it) }
            }
            if (stats.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    stats.forEach { (label, value) -> RiderStat(label, value) }
                }
            }
        }
    }
}

@Composable
private fun RiderStat(label: String, value: String) {
    Column {
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

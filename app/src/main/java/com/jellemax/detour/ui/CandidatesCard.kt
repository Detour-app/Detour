package com.jellemax.detour.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jellemax.detour.data.GroupMember
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.data.RouteCandidate
import com.jellemax.detour.data.handleFor

/** Spin results awaiting a pick: distance/ETA per candidate, tap one to commit
 *  to it - or, once [convoyVotes] is non-null, tap one to vote on it instead
 *  (see MapScreen's commit rule for how a vote round actually resolves). */
@Composable
internal fun CandidatesCard(
    candidates: List<RouteCandidate>,
    onPick: (Int, RouteCandidate) -> Unit,
    onReroll: () -> Unit,
    onCancel: () -> Unit,
    // Null = a solo spin, not shared with anyone. Non-null (even empty) =
    // a convoy vote is in progress; the map holds rider id -> chosen index.
    convoyVotes: Map<RiderId, Int>? = null,
    // The convoy's own membership, to resolve a voter's id to the handle
    // drawn under a candidate — this card has no store access of its own and
    // should not gain one just to look up a name.
    members: List<GroupMember>,
    // Non-null only pre-share, in a convoy, with a spin actually on screen.
    onShare: (() -> Unit)? = null,
    onGoWithLead: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.glassBorder(MaterialTheme.shapes.extraLarge),
        shape = MaterialTheme.shapes.extraLarge,
        colors = glassCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (convoyVotes == null) "Pick a destination" else "Vote on a destination",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (convoyVotes == null) "All three are on the map — tap a pin or a row."
                else "Everyone sees the same three — tap a pin or a row to vote.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            candidates.forEachIndexed { index, c ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(index, c) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(26.dp)
                            .background(
                                Color(CANDIDATE_COLORS[index % CANDIDATE_COLORS.size]),
                                RoundedCornerShape(8.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            ('A' + index).toString(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            // Fixed dark text: the candidate colours are chosen
                            // deliberately (see CANDIDATE_COLORS) and are all
                            // light enough that a themed on-color would clash.
                            color = Color(0xFF1A1A1A),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            c.name ?: "Option ${index + 1}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        val distanceMeters = c.route?.distanceMeters ?: c.straightLineMeters
                        val prefix = if (c.route?.distanceMeters == null) "~ straight-line " else "via road "
                        Text(
                            prefix + formatDistanceKm(distanceMeters),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (convoyVotes != null) {
                            val voters = convoyVotes.filterValues { it == index }.keys
                                .map { members.handleFor(it) }.sorted()
                            Text(
                                if (voters.isEmpty()) "No votes yet"
                                else "${voters.size} vote${if (voters.size == 1) "" else "s"} · " +
                                    voters.joinToString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    c.route?.timeMs?.let { timeMs ->
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Text(
                                "%.0f min".format(timeMs / 60_000.0),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
            if (onShare != null) {
                FilledTonalButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Groups, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share with convoy")
                }
            }
            if (onGoWithLead != null) {
                // A silent member can't stall the ride - this commits the
                // current leader immediately, without waiting for a vote
                // from every currently-connected peer.
                Button(onClick = onGoWithLead, modifier = Modifier.fillMaxWidth()) {
                    Text("Go with the lead")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                // Rerolling would only change this device's own list, not the
                // sheet everyone else is voting on - hide it once shared.
                if (convoyVotes == null) {
                    Button(onClick = onReroll, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Casino, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Reroll")
                    }
                }
            }
        }
    }
}

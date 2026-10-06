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
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Groups
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jellemax.detour.R
import com.jellemax.detour.data.GroupMember
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.data.RouteCandidate
import com.jellemax.detour.data.handleFor
import com.jellemax.detour.presentation.SpinCandidateRow

/** Spin results awaiting a pick: distance/ETA per candidate, tap one to commit
 *  to it - or, once [convoyVotes] is non-null, tap one to vote on it instead
 *  (see MapScreen's commit rule for how a vote round actually resolves). */
@Composable
internal fun CandidatesCard(
    candidates: List<RouteCandidate>,
    // Same size/order as [candidates] - the mapper's read-out of exactly the
    // fields this card renders (name, distance, duration), spinStateFrom'd by
    // the caller so this card never formats a number itself. [candidates]
    // itself stays around for what the mapper doesn't carry: the RouteCandidate
    // identity onPick needs, and the coordinates the map pins are drawn from.
    rows: List<SpinCandidateRow>,
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
                stringResource(
                    if (convoyVotes == null) R.string.candidates_pick_title else R.string.candidates_vote_title,
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (convoyVotes == null) stringResource(R.string.candidates_pick_hint)
                else stringResource(R.string.candidates_vote_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            candidates.forEachIndexed { index, c ->
                val row = rows[index]
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
                            row.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            row.distanceText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (convoyVotes != null) {
                            val voters = convoyVotes.filterValues { it == index }.keys
                                .map { members.handleFor(it) }.sorted()
                            Text(
                                if (voters.isEmpty()) stringResource(R.string.candidates_no_votes)
                                else pluralStringResource(
                                    R.plurals.candidates_votes, voters.size, voters.size, voters.joinToString(),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    row.durationText?.let { duration ->
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Text(
                                duration,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
            if (onShare != null) {
                FilledTonalButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Groups, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.candidates_share))
                }
            }
            if (onGoWithLead != null) {
                // A silent member can't stall the ride - this commits the
                // current leader immediately, without waiting for a vote
                // from every currently-connected peer.
                Button(onClick = onGoWithLead, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.candidates_go_with_lead))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.candidates_cancel))
                }
                // Rerolling would only change this device's own list, not the
                // sheet everyone else is voting on - hide it once shared.
                if (convoyVotes == null) {
                    Button(onClick = onReroll, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Casino, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.candidates_reroll))
                    }
                }
            }
        }
    }
}

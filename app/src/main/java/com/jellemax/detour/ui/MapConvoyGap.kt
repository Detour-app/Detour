package com.jellemax.detour.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.R
import com.jellemax.detour.convoy.ConvoyGapAlerts
import com.jellemax.detour.data.Settings
import com.jellemax.detour.net.ConvoyLiveClient
import com.jellemax.detour.presentation.ConvoyGapKind

/**
 * Speaks each new convoy gap alert (#647). [speak] is MapScreen's voice itself,
 * not its `announceAloud`: that one stays silent for the whole of a convoy so
 * guidance never talks over the riders, and a gap alert only exists during
 * one. What this keeps of it is the voice-off setting, read off the StateFlow
 * because the collector runs while the app is backgrounded; and it waits its
 * turn behind a rider who is transmitting by dropping the line — the banner
 * still shows it.
 */
@Composable
internal fun MapConvoyGapVoice(speak: (String) -> Unit) {
    val speakNow by rememberUpdatedState(speak)
    LaunchedEffect(Unit) {
        ConvoyGapAlerts.spoken.collect { line ->
            if (!Settings.voiceGuidance.value) return@collect
            if (ConvoyLiveClient.talking.value.isNotEmpty()) return@collect
            speakNow(line)
        }
    }
}

/** One line per convoy rider with an alert, under the navigation banner; draws
 *  nothing while there is none. Error-coloured, like the speed island over the
 *  limit: a rider missing from the group is the thing to look at. */
@Composable
internal fun ConvoyGapBanner() {
    val lines by ConvoyGapAlerts.lines.collectAsStateWithLifecycle()
    if (lines.isEmpty()) return
    Card(
        modifier = Modifier.padding(top = 10.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            lines.forEach { line ->
                val text = when (line.kind) {
                    ConvoyGapKind.SIGNAL_LOST -> R.string.map_convoy_gap_signal_lost
                    ConvoyGapKind.STOPPED -> R.string.map_convoy_gap_stopped
                    ConvoyGapKind.BEHIND -> R.string.map_convoy_gap_behind
                }
                Text(
                    stringResource(text, line.name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

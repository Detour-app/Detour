package com.jellemax.detour.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.R
import com.jellemax.detour.data.Settings
import com.jellemax.detour.drive.TrafficBudget
import com.jellemax.detour.presentation.TrafficKeyPresenter
import com.jellemax.detour.presentation.TrafficKeyStatus
import kotlinx.coroutines.launch

/**
 * The HERE traffic key (#644): a secret field, a Test key button, and the
 * explainer #637 asks for. The rule — test once, store only on a pass — is
 * [TrafficKeyPresenter]'s; this only draws it.
 */
@Composable
internal fun TrafficKeySection() {
    val storedKey by Settings.hereTrafficKey.collectAsStateWithLifecycle()
    val over by Settings.trafficOverFreeTier.collectAsStateWithLifecycle()
    val usage by Settings.trafficUsage.collectAsStateWithLifecycle()
    val presenter = remember { TrafficKeyPresenter() }
    val status by presenter.status.collectAsStateWithLifecycle()
    // Keyed on the stored key: a pass stores the (trimmed) key and Remove
    // blanks it, and the field should show what is now stored either way.
    var draft by remember(storedKey) { mutableStateOf(storedKey) }
    val scope = rememberCoroutineScope()

    SettingsSection(stringResource(R.string.settings_traffic_title)) {
        LearnMore(
            stringResource(R.string.settings_traffic_summary),
            stringResource(R.string.settings_traffic_detail),
        )
        SecretTextField(
            value = draft,
            onValueChange = { draft = it; presenter.edited() },
            label = stringResource(R.string.settings_traffic_key),
            modifier = Modifier.fillMaxWidth(),
        )
        TrafficKeyStatusLine(status, saved = storedKey.isNotEmpty() && draft.trim() == storedKey)
        if (storedKey.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_traffic_over_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.settings_traffic_over_body, usage.count, TrafficBudget.FREE_TIER),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(over, Settings::setTrafficOverFreeTier)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { scope.launch { presenter.test(draft) } },
                enabled = draft.isNotBlank() && status != TrafficKeyStatus.TESTING,
            ) { Text(stringResource(R.string.settings_traffic_test)) }
            if (storedKey.isNotEmpty()) {
                TextButton(onClick = presenter::remove) {
                    Text(stringResource(R.string.settings_traffic_remove))
                }
            }
        }
    }
}

/** The line under the field. Silent while idle with nothing stored: a rider
 *  who never wanted traffic should not be told it is off. */
@Composable
private fun TrafficKeyStatusLine(status: TrafficKeyStatus, saved: Boolean) {
    val text = when (status) {
        TrafficKeyStatus.IDLE -> if (saved) R.string.settings_traffic_on else null
        TrafficKeyStatus.TESTING -> R.string.settings_traffic_testing
        TrafficKeyStatus.OK -> R.string.settings_traffic_ok
        TrafficKeyStatus.BAD_KEY -> R.string.settings_traffic_bad_key
        TrafficKeyStatus.QUOTA -> R.string.settings_traffic_quota
        TrafficKeyStatus.UNREACHABLE -> R.string.settings_traffic_unreachable
    } ?: return
    val failed = status in setOf(
        TrafficKeyStatus.BAD_KEY, TrafficKeyStatus.QUOTA, TrafficKeyStatus.UNREACHABLE,
    )
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

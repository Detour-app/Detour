package com.jellemax.detour.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.R
import com.jellemax.detour.data.Settings
import com.jellemax.detour.notif.CircleNotifyService
import com.jellemax.detour.ui.SettingsSection

/** The Settings → Displays & media section for [Settings.notificationsEnabled] (#977). */
@Composable
internal fun NotificationsSection() {
    val context = LocalContext.current
    val on by Settings.notificationsEnabled.collectAsStateWithLifecycle()
    SettingsSection(stringResource(R.string.settings_notifications)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_notifications_enabled),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.settings_notifications_enabled_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = on,
                onCheckedChange = {
                    Settings.setNotificationsEnabled(it)
                    // Starts or stands down the push-less circle socket, which
                    // only exists to notify.
                    CircleNotifyService.refresh(context)
                },
            )
        }
    }
}

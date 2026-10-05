package com.jellemax.detour.ui

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.data.Settings

/** The Navigation spoke. Here rather than in SettingsScreen.kt, whose §8 size
 *  pin is why [NavigationSection] is internal. */
@Composable
internal fun NavigationSpoke() {
    NavigationSection()
    CameraWarningsSection()
}

/** Camera-warning settings (#496). The rule that reads them is
 *  `CameraWarner`'s; the two refinements only show while warnings are on. */
@Composable
private fun CameraWarningsSection() {
    val enabled by Settings.cameraWarnings.collectAsStateWithLifecycle()
    val notSpeeding by Settings.cameraWarnNotSpeeding.collectAsStateWithLifecycle()
    val limitUnknown by Settings.cameraWarnLimitUnknown.collectAsStateWithLifecycle()
    SettingsSection("Camera warnings") {
        SwitchRow(
            "Warn of cameras ahead",
            "A chime and a spoken warning for speed and red-light cameras.",
            enabled,
            Settings::setCameraWarnings,
        )
        if (enabled) {
            SwitchRow(
                "Warn even when not speeding",
                "Otherwise a speed camera is only announced when you are over its limit.",
                notSpeeding,
                Settings::setCameraWarnNotSpeeding,
            )
            SwitchRow(
                "Warn when the limit is unknown",
                "Otherwise a speed camera on a road with no known limit stays silent.",
                limitUnknown,
                Settings::setCameraWarnLimitUnknown,
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

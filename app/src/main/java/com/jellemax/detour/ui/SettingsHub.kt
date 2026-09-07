package com.jellemax.detour.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.BuildConfig
import com.jellemax.detour.data.Settings
import com.jellemax.detour.nav.Destination
import com.jellemax.detour.presentation.settingsHubStateFrom
import com.jellemax.detour.update.ManualCheck
import com.jellemax.detour.update.UpdateChecker
import com.jellemax.detour.update.UpdateState
import com.jellemax.detour.update.updateRowStateFrom
import kotlinx.coroutines.launch

/**
 * The Settings root: one row per spoke, plus the update check, which is not a
 * spoke — it acts in place rather than navigating anywhere.
 *
 * [onOpenSpoke] replaced `page = SettingsPage.X`. The screen no longer holds any
 * navigation state and no longer has a `BackHandler` — there is nothing left for
 * one to intercept, because a spoke is an entry on the app's stack and back pops
 * it like any other.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenSpoke: (Destination.SettingsSpoke) -> Unit) {
    val theme by Settings.theme.collectAsStateWithLifecycle()
    val autoDetect by Settings.autoDetectDrives.collectAsStateWithLifecycle()
    val avoidHighways by Settings.avoidHighways.collectAsStateWithLifecycle()
    val fogRadius by Settings.fogRadiusMeters.collectAsStateWithLifecycle()
    val externalDisplayEnabled by Settings.externalDisplayEnabled.collectAsStateWithLifecycle()
    val authUsername by Settings.authUsername.collectAsStateWithLifecycle()
    val manualCheck by UpdateChecker.lastManualCheck.collectAsStateWithLifecycle()
    val updateScope = rememberCoroutineScope()
    val context = LocalContext.current
    val hub = settingsHubStateFrom(
        themeName = theme.name,
        autoDetectDrives = autoDetect,
        avoidHighways = avoidHighways,
        fogRadiusMeters = fogRadius.toDouble(),
        externalDisplayEnabled = externalDisplayEnabled,
        authUsername = authUsername,
    )

    SettingsScaffold("Settings", onBack, spacing = 10.dp) {
        SectionLabel("RIDING")
        ListCard {
            HubRow(
                icon = Icons.Outlined.DirectionsCar,
                title = spokeTitle(Destination.SettingsTrackingVehicles),
                subtitle = hub.trackingSubtitle,
                onClick = { onOpenSpoke(Destination.SettingsTrackingVehicles) },
                paintCard = false,
            )
            CardDivider()
            HubRow(
                icon = Icons.Outlined.Navigation,
                title = spokeTitle(Destination.SettingsNavigation),
                subtitle = hub.navigationSubtitle,
                onClick = { onOpenSpoke(Destination.SettingsNavigation) },
                paintCard = false,
            )
        }

        SectionLabel("MAP")
        ListCard {
            HubRow(
                icon = Icons.Outlined.Brightness6,
                title = spokeTitle(Destination.SettingsAppearanceMap),
                subtitle = hub.appearanceSubtitle,
                onClick = { onOpenSpoke(Destination.SettingsAppearanceMap) },
                paintCard = false,
            )
            CardDivider()
            HubRow(
                icon = Icons.Outlined.VisibilityOff,
                title = spokeTitle(Destination.SettingsFog),
                subtitle = hub.fogSubtitle,
                onClick = { onOpenSpoke(Destination.SettingsFog) },
                paintCard = false,
            )
        }

        SectionLabel("DEVICES")
        ListCard {
            HubRow(
                icon = Icons.Outlined.Tv,
                title = spokeTitle(Destination.SettingsDisplaysMedia),
                subtitle = hub.displaysSubtitle,
                onClick = { onOpenSpoke(Destination.SettingsDisplaysMedia) },
                paintCard = false,
            )
            CardDivider()
            HubRow(
                icon = Icons.Outlined.Speed,
                title = spokeTitle(Destination.SettingsObd2),
                subtitle = "Connect a vehicle's OBD2 adapter for accurate speed",
                onClick = { onOpenSpoke(Destination.SettingsObd2) },
                paintCard = false,
            )
        }

        SectionLabel("ACCOUNT")
        ListCard {
            HubRow(
                icon = Icons.Outlined.Cloud,
                title = spokeTitle(Destination.SettingsServersSync),
                subtitle = hub.serversSubtitle,
                onClick = { onOpenSpoke(Destination.SettingsServersSync) },
                paintCard = false,
            )
        }

        // Only where there is a repository to check. A build made without
        // UPDATE_REPO in the environment has no update mechanism at all, and a
        // row that silently does nothing when tapped is worse than no row.
        if (UpdateChecker.isConfigured) {
            ListCard {
                HubRow(
                    icon = Icons.Outlined.SystemUpdate,
                    title = "Check for updates",
                    subtitle = updateRowStateFrom(manualCheck, UpdateState.status.value).subtitle,
                    onClick = {
                        // Guarded on Running only. A tap with no tokens left is
                        // allowed through so the budget can refuse it out loud —
                        // the subtitle is the whole feedback loop, and a dead row
                        // would be the silence this issue is about.
                        if (manualCheck !is ManualCheck.Running) {
                            updateScope.launch { UpdateChecker.manualCheck(context) }
                        }
                    },
                    paintCard = false,
                )
            }
        }
        Text(
            "Detour ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            textAlign = TextAlign.Center,
        )
    }
}

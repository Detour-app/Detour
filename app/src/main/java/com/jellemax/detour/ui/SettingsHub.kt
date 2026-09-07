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
import com.jellemax.detour.update.UpdateAction
import com.jellemax.detour.update.UpdateChecker
import com.jellemax.detour.update.UpdateDownloadService
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
    // Collected, not read once: the transfer belongs to UpdateDownloadService
    // and keeps reporting while the rider is elsewhere, so the row has to
    // pick up where it got to rather than where it was when Settings opened.
    val updateStatus by UpdateState.status.collectAsStateWithLifecycle()
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
            val row = updateRowStateFrom(manualCheck, updateStatus)
            ListCard {
                HubRow(
                    icon = Icons.Outlined.SystemUpdate,
                    title = row.title,
                    subtitle = row.subtitle,
                    onClick = {
                        // The row's own tap only ever checks. Once there is an
                        // artefact the button below carries the verb, because a
                        // stray tap on a row must not start a 46 MB download.
                        //
                        // CHECK is itself absent while a check is running;
                        // updateRowStateFrom guards on Running only, so a tap
                        // with no rate-limit tokens left still goes through and
                        // the budget refuses it out loud in the subtitle.
                        if (row.action == UpdateAction.CHECK) {
                            updateScope.launch { UpdateChecker.manualCheck(context) }
                        }
                    },
                    paintCard = false,
                )
                // Every phase past the check puts its verb on its own button:
                // the row's tap is a check, and only the button downloads,
                // cancels or installs.
                val action = row.action
                if (action != null && action != UpdateAction.CHECK) {
                    UpdateProgressButton(
                        label = row.actionLabel.orEmpty(),
                        onClick = {
                            when (action) {
                                UpdateAction.DOWNLOAD -> UpdateDownloadService.start(context)
                                UpdateAction.CANCEL -> UpdateDownloadService.cancel(context)
                                UpdateAction.INSTALL -> UpdateDownloadService.install(context)
                                UpdateAction.CHECK -> Unit
                            }
                        },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        fraction = row.fraction,
                    )
                }
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

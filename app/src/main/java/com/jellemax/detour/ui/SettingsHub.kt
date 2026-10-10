package com.jellemax.detour.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import com.jellemax.detour.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.BuildConfig
import com.jellemax.detour.data.Settings
import com.jellemax.detour.nav.Destination
import com.jellemax.detour.presentation.settingsHubStateFrom
import com.jellemax.detour.update.InstalledNotes
import com.jellemax.detour.update.UpdateAction
import com.jellemax.detour.update.UpdateChecker
import com.jellemax.detour.update.UpdateStatus
import com.jellemax.detour.update.UpdateRowState
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
    val avoidSmallRoads by Settings.avoidSmallRoads.collectAsStateWithLifecycle()
    val avoidTolls by Settings.avoidTolls.collectAsStateWithLifecycle()
    val avoidFerries by Settings.avoidFerries.collectAsStateWithLifecycle()
    val avoidUnpaved by Settings.avoidUnpaved.collectAsStateWithLifecycle()
    // Read back through Settings rather than built here, so the subtitle drops
    // an option the server can't honour exactly as a routing request does.
    val avoid = remember(avoidHighways, avoidSmallRoads, avoidTolls, avoidFerries, avoidUnpaved) {
        Settings.routePreferences()
    }
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
        avoid = avoid,
        fogRadiusMeters = fogRadius.toDouble(),
        externalDisplayEnabled = externalDisplayEnabled,
        authUsername = authUsername,
    )

    SettingsScaffold(stringResource(R.string.settings_title), onBack, spacing = 10.dp) {
        SectionLabel(stringResource(R.string.settings_section_riding))
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

        SectionLabel(stringResource(R.string.settings_section_map))
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

        SectionLabel(stringResource(R.string.settings_section_devices))
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
                subtitle = stringResource(R.string.settings_obd2_subtitle),
                onClick = { onOpenSpoke(Destination.SettingsObd2) },
                paintCard = false,
            )
        }

        SectionLabel(stringResource(R.string.settings_section_account))
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
            val installedNotes = remember { InstalledNotes.current() }
            val row = updateRowStateFrom(manualCheck, updateStatus, installedNotes) { id, version ->
                if (version == null) context.getString(id) else context.getString(id, version)
            }
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
                UpdateNotesSection(row, updateStatus)
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
        SectionLabel(stringResource(R.string.settings_section_about))
        ListCard {
            HubRow(
                icon = Icons.Outlined.Info,
                title = spokeTitle(Destination.SettingsLicences),
                subtitle = stringResource(R.string.settings_licences_subtitle),
                onClick = { onOpenSpoke(Destination.SettingsLicences) },
                paintCard = false,
            )
        }

        Text(
            stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The "What's new" disclosure under the update row.
 *
 * Extracted from [SettingsScreen] because that function reached detekt's
 * cyclomatic limit and the branch #358 adds was the one over it. It earns its
 * own function on `boundaries.md` §8.1 terms too: one lifetime (the update
 * row), one reason to change (how release notes are presented), one writer.
 * Two parameters, so §8.4's gate is nowhere near.
 *
 * A [ColumnScope] extension rather than a plain composable: it emits a
 * divider, a header row and the notes as siblings of the rows above it, the
 * same shape `ColumnScope.HomeSheet` uses and what detekt's Compose rule set
 * asks for instead of several top-level emitters.
 */
@Composable
private fun ColumnScope.UpdateNotesSection(row: UpdateRowState, updateStatus: UpdateStatus) {
    // An expandable summary (#295): notes are for deciding whether to
    // download, not for a download already running or done, so row.notes
    // is null in those phases. With nothing on offer it carries the
    // running version's own notes (#359). Collapsed by default.
    //
    // Rendered as Markdown rather than shown as source (#357): the
    // body is GitHub's generated list, so as plain text a rider read
    // an HTML comment, `##`/`###` markers and two bare URLs — 63 % of
    // v2.31.3's 326-character body.
    //
    // Links are live, and that is a decision. The body comes from
    // whatever repo BuildConfig.UPDATE_REPO names — baked at build
    // time from `github.repository` (build.yml), so it is the repo
    // that compiled this APK and a rider cannot repoint it. That same
    // origin already hands this app an APK it downloads and installs,
    // so a link is strictly the smaller trust. Taps go through
    // LocalUriHandler to the platform browser; no WebView is
    // introduced (MASVS 2.1.0 MASVS-PLATFORM-2).
    //
    // Images are the exception and stay off: one would fetch on
    // *expand*, with no tap, disclosing the rider's IP to a host the
    // release author chose. There is no imageTransformer and no coil
    // artifact on the classpath, so the path does not exist rather
    // than being switched off (see app/build.gradle.kts).
    // The merged notes for every skipped release (#358) replace the
    // offered release's own the moment they land. Null until then,
    // and null for good if the fetch failed — so a rider several
    // releases behind reads the newest release's notes immediately
    // and the rest a moment later, and never an empty expander.
    val rangeNotes by UpdateChecker.rangeNotes.collectAsStateWithLifecycle()
    val notes = rangeNotes ?: row.notes
    if (notes != null) {
        var notesExpanded by remember(updateStatus) { mutableStateOf(false) }
        // Fetched on expand, not on the hourly check — an update
        // sits available until it is taken, so checking would mean
        // re-downloading the release list every hour it is deferred.
        // UpdateChecker memoises per installed-to-offered pair, so
        // re-expanding costs nothing.
        LaunchedEffect(notesExpanded, updateStatus) {
            if (notesExpanded) UpdateChecker.loadRangeNotes()
        }
        CardDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { notesExpanded = !notesExpanded }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.settings_whats_new), style = MaterialTheme.typography.bodyMedium)
            Icon(
                if (notesExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = stringResource(
                    if (notesExpanded) R.string.settings_collapse else R.string.settings_expand,
                ),
            )
        }
        if (notesExpanded) {
            ReleaseNotesMarkdown(
                notes,
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
            )
        }
    }
}

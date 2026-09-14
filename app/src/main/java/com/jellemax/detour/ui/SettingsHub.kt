package com.jellemax.detour.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLinkStyles
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding
import androidx.compose.runtime.Composable
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
                // An expandable summary, Available only (#295): notes are for
                // deciding whether to download, not for a download already
                // running or done, and row.notes is already null everywhere
                // else. Collapsed by default.
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
                val notes = row.notes
                if (notes != null) {
                    var notesExpanded by remember(updateStatus) { mutableStateOf(false) }
                    CardDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { notesExpanded = !notesExpanded }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("What's new", style = MaterialTheme.typography.bodyMedium)
                        Icon(
                            if (notesExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = if (notesExpanded) "Collapse" else "Expand",
                        )
                    }
                    if (notesExpanded) {
                        Markdown(
                            content = notes,
                            // Every slot is set, none defaulted. The library's
                            // defaults are sized for a full-page document —
                            // h1 is displayLarge, and `link` is bodyLarge +
                            // Bold + Underline, which is why an unstyled render
                            // put two wrapped, oversized URLs where the changes
                            // should be. Inside a settings card everything is
                            // one size (bodySmall) and hierarchy comes from
                            // weight and colour instead.
                            colors = markdownColor(
                                text = MaterialTheme.colorScheme.onSurfaceVariant,
                                linkText = MaterialTheme.colorScheme.primary,
                                inlineCodeText = MaterialTheme.colorScheme.onSurfaceVariant,
                                codeText = MaterialTheme.colorScheme.onSurfaceVariant,
                                inlineCodeBackground = Color.Transparent,
                                codeBackground = Color.Transparent,
                                dividerColor = MaterialTheme.colorScheme.outlineVariant,
                            ),
                            typography = markdownTypography(
                                text = MaterialTheme.typography.bodySmall,
                                paragraph = MaterialTheme.typography.bodySmall,
                                list = MaterialTheme.typography.bodySmall,
                                ordered = MaterialTheme.typography.bodySmall,
                                bullet = MaterialTheme.typography.bodySmall,
                                quote = MaterialTheme.typography.bodySmall,
                                code = MaterialTheme.typography.bodySmall,
                                inlineCode = MaterialTheme.typography.bodySmall,
                                table = MaterialTheme.typography.bodySmall,
                                // A link is body text in the accent colour, not
                                // a headline. No underline: the colour already
                                // marks it and an underlined 60-character URL
                                // is the thing that made this unreadable.
                                link = MaterialTheme.typography.bodySmall,
                                textLink = TextLinkStyles(
                                    style = MaterialTheme.typography.bodySmall
                                        .copy(color = MaterialTheme.colorScheme.primary)
                                        .toSpanStyle(),
                                ),
                                // GitHub emits `## What's Changed` then a
                                // `### <label group>` per category. Both are
                                // labels above a short list, so they are sized
                                // as labels — not as the display scale the
                                // defaults reach for.
                                h1 = MaterialTheme.typography.labelLarge,
                                h2 = MaterialTheme.typography.labelLarge,
                                h3 = MaterialTheme.typography.labelMedium,
                                h4 = MaterialTheme.typography.labelMedium,
                                h5 = MaterialTheme.typography.labelMedium,
                                h6 = MaterialTheme.typography.labelMedium,
                            ),
                            // The card already pads 16dp; the defaults add a
                            // document's worth on top, which is what made the
                            // bullet, its URL and the changelog line each start
                            // at a different left edge.
                            padding = markdownPadding(
                                block = 4.dp,
                                list = 2.dp,
                                listItemTop = 1.dp,
                                listItemBottom = 1.dp,
                                listIndent = 6.dp,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 12.dp),
                        )
                    }
                }
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

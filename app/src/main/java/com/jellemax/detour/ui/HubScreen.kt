package com.jellemax.detour.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Diversity3
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MilitaryTech
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.auth.AuthBrowser
import com.jellemax.detour.auth.PendingSignIn
import com.jellemax.detour.data.Account
import com.jellemax.detour.presentation.YouPresenter
import com.jellemax.detour.presentation.YouState
import com.jellemax.detour.update.UpdateDownloader
import com.jellemax.detour.update.UpdateInstaller
import com.jellemax.detour.update.UpdateState
import com.jellemax.detour.update.UpdateStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "You" screen: reached from the avatar on the map's search pill, and the one
 * place the destination screens below now hang off. Their own back arrows all
 * return here — the map is reached only from Hub's back arrow.
 *
 * Body is driven entirely by [YouPresenter]/[YouState] (built in an earlier
 * task): profile-or-guest card, a 4-cell stats row, and the RIDES list.
 * Friends and Circles moved to [SocialScreen]; Settings moved from a list row
 * to the top-bar button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubScreen(
    onBack: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSocial: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenRoutes: () -> Unit,
    onOpenSavedPlaces: () -> Unit,
    onOpenBadges: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateStatus by UpdateState.status.collectAsStateWithLifecycle()

    val presenter = remember { YouPresenter() }
    val state by presenter.state.collectAsStateWithLifecycle()
    // Keyed on username, not Unit: signing in from this screen's own guest card
    // (below) changes Account.username without recomposing this composable from
    // scratch, so a key that only fires once would leave the guest card on
    // screen after a successful sign-in until the rider left and came back.
    // Same reasoning as FriendsScreen's own username-keyed reload.
    val accountUsername by Account.username.collectAsStateWithLifecycle()
    LaunchedEffect(accountUsername) { withContext(Dispatchers.IO) { presenter.refresh() } }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SubScreenTopBar("You", onBack, scrollBehavior) {
                IconButton(onClick = onOpenSettings, modifier = Modifier.padding(end = 16.dp)) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            UpdateBanner(
                status = updateStatus,
                onDownload = {
                    val update = UpdateState.current() ?: return@UpdateBanner
                    UpdateState.set(UpdateStatus.Downloading(update, -1f))
                    scope.launch(Dispatchers.IO) {
                        val file = UpdateDownloader.download(context, update) { f ->
                            UpdateState.set(UpdateStatus.Downloading(update, f))
                        }
                        UpdateState.set(
                            if (file != null) UpdateStatus.Downloaded(update, file.path)
                            else UpdateStatus.Failed(update)
                        )
                    }
                },
                onInstall = {
                    val s = updateStatus as? UpdateStatus.Downloaded ?: return@UpdateBanner
                    if (!UpdateInstaller.canInstall(context)) {
                        UpdateInstaller.requestPermission(context)
                    } else {
                        UpdateInstaller.install(context, java.io.File(s.path))
                    }
                },
            )

            if (state.signedIn) {
                YouProfileCard(state, onOpenProfile)
            } else {
                YouGuestCard()
            }

            YouStatsRow(state)

            Text(
                "RIDES",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )

            ListCard {
                HubRow(
                    icon = Icons.Rounded.History,
                    title = "Trip history",
                    onClick = onOpenHistory,
                    paintCard = false,
                )
                CardDivider()
                HubRow(
                    icon = Icons.Rounded.Route,
                    title = "Routes",
                    onClick = onOpenRoutes,
                    paintCard = false,
                )
                CardDivider()
                HubRow(
                    icon = Icons.Rounded.BookmarkBorder,
                    title = "Saved places",
                    onClick = onOpenSavedPlaces,
                    paintCard = false,
                )
                CardDivider()
                HubRow(
                    icon = Icons.Rounded.MilitaryTech,
                    title = "Badges & coverage",
                    trailingText = state.badgeFractionLabel,
                    onClick = onOpenBadges,
                    paintCard = false,
                )
            }

            // TEMP: remove when home screen (batch 5) owns the Social entry.
            // The prototype's own entry point to Social lives on the map screen,
            // which that later batch rebuilds; until then this bridge row is the
            // only way to reach it from You.
            ListCard {
                HubRow(
                    icon = Icons.Rounded.Diversity3,
                    title = "Social",
                    onClick = onOpenSocial,
                    paintCard = false,
                )
            }
        }
    }
}

/** Signed-in state: tappable card to [onClick] (Profile) — 48dp initial avatar,
 *  name, "Profile & account" subtitle, trailing chevron. */
@Composable
private fun YouProfileCard(state: YouState, onClick: () -> Unit) {
    // clip() before clickable() so the ripple is bounded to the card's own
    // rounded corners instead of painting a rectangle over them.
    ListCard(modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(state.avatarInitial, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(state.username, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Profile & account",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Rounded.ChevronRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Signed-out state: guest card + full-width amber "Sign in" button. The
 * button reuses FriendsScreen's SignInSection trigger and failure handling
 * verbatim (FriendsScreen.kt:152-192) — begin()/AuthBrowser.start()/the same
 * StartFailure -> PendingSignIn.fail(...) mapping — rather than a thinner
 * version that would silently do nothing when e.g. no browser is installed.
 */
@Composable
private fun YouGuestCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val error by PendingSignIn.error.collectAsStateWithLifecycle()
    val busy by PendingSignIn.busy.collectAsStateWithLifecycle()

    ListCard(borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Person, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Riding as guest", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Trips and badges are saved on this phone. Sign in to ride with " +
                            "friends and back them up.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!AuthBrowser.configured) {
                Text(
                    "No server or sign-in realm is configured, so there is nobody to " +
                        "sign in to. Set your server address under Settings → Servers & sync.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = {
                        // begin() is reached from inside this launch, so it marks the
                        // screen busy for the probe as well as the browser trip, and
                        // clears any previous error — same shape as FriendsScreen.
                        PendingSignIn.begin()
                        scope.launch {
                            val failure = AuthBrowser.start(context)
                            when (failure) {
                                // The browser is open; MainActivity's redirect handler
                                // owns the rest, including clearing busy.
                                null -> {}
                                AuthBrowser.StartFailure.InvalidRealmUrl -> PendingSignIn.fail(
                                    "The sign-in realm address is not a valid URL. Check it " +
                                        "under Settings → Servers & sync."
                                )
                                AuthBrowser.StartFailure.NoBrowserAvailable ->
                                    PendingSignIn.fail("No browser available to sign in with.")
                                AuthBrowser.StartFailure.NoRealmAdvertised ->
                                    PendingSignIn.fail(
                                        "Your server did not say which realm to sign in to. " +
                                            "Update the server, or set the sign-in realm URL " +
                                            "under Settings → Servers & sync."
                                    )
                                AuthBrowser.StartFailure.NotConfigured ->
                                    PendingSignIn.fail(
                                        "No identity provider is configured. Set the sign-in " +
                                            "realm URL under Settings → Servers & sync."
                                    )
                            }
                        }
                    },
                    enabled = !busy,
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.AutoMirrored.Rounded.Login, contentDescription = null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Sign in", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** 4-cell stats row — km / rides / places / badges — split by vertical
 *  dividers, value bold in [MaterialTheme.colorScheme.primary]. */
@Composable
private fun YouStatsRow(state: YouState) {
    ListCard {
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(vertical = 14.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatCell(state.kilometresLabel, "km", Modifier.weight(1f))
            VerticalDivider(
                Modifier.fillMaxHeight(),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )
            StatCell("${state.rides}", "rides", Modifier.weight(1f))
            VerticalDivider(
                Modifier.fillMaxHeight(),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )
            StatCell("${state.places}", "places", Modifier.weight(1f))
            VerticalDivider(
                Modifier.fillMaxHeight(),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )
            StatCell("${state.badgesEarned}", "badges", Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * One destination row: icon tile, title + subtitle, trailing chevron. Used
 * to share a look with the Settings root's rows; that has drifted since this
 * redesign — Settings still paints `Icons.Outlined.*` per-row cards, while
 * these screens pass `Icons.Rounded.*` and group rows into one card — and
 * stays drifted until Settings itself is rebuilt.
 *
 * [paintCard] defaults to true — every existing call site (Hub, Settings)
 * gets its own rounded [Card] exactly as before. Pass false to render just
 * the row content with no card of its own, for a caller that groups several
 * rows inside one shared [Card] with dividers between them (Social's Friends
 * + Circles list, and You's Rides list) — the prototype's list-card pattern,
 * which a `HubRow` that always paints its own card can't produce.
 */
@Composable
fun HubRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    trailingText: String? = null,
    trailingCount: Int? = null,
    modifier: Modifier = Modifier,
    paintCard: Boolean = true,
) {
    val row: @Composable () -> Unit = {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null,
                    Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (trailingText != null) {
                Text(
                    trailingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (trailingCount != null && trailingCount > 0) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        "$trailingCount",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Icon(
                Icons.Rounded.ChevronRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (paintCard) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) { row() }
    } else {
        Box(modifier.fillMaxWidth()) { row() }
    }
}

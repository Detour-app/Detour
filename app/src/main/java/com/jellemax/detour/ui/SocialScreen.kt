package com.jellemax.detour.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.ShareLocation
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.data.Account
import com.jellemax.detour.data.CirclesStore

/**
 * The social hub: Friends and Circles, split out of the general Hub screen so
 * the redesign has one place to grow the rider's social surface without
 * crowding You. Convoys is out of scope for this effort and has no row here.
 *
 * The trailing avatar button mirrors the prototype's `goYou` action — it is
 * the same [onBack] as the top bar's arrow, not a second destination.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialScreen(onBack: () -> Unit, onOpenFriends: () -> Unit, onOpenCircles: () -> Unit) {
    val username by Account.username.collectAsStateWithLifecycle()

    // Same staleness reload CirclesScreen does on entry (Hub -> Social -> Hub ->
    // Social is not a new visit); reloadIfStale skips the round trip inside its
    // window.
    LaunchedEffect(Unit) { CirclesStore.reloadIfStale() }
    val circlesState by CirclesStore.state.collectAsStateWithLifecycle()
    val circleCount = circlesState.circles.size

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SubScreenTopBar("Social", onBack, scrollBehavior) {
                Box(
                    Modifier
                        .padding(end = 16.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        username.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HubRow(
                icon = Icons.Rounded.Group,
                title = "Friends",
                onClick = onOpenFriends,
                trailingText = "leaderboard",
            )
            HubRow(
                icon = Icons.Rounded.ShareLocation,
                title = "Circles",
                onClick = onOpenCircles,
                trailingText = "$circleCount sharing",
            )
        }
    }
}

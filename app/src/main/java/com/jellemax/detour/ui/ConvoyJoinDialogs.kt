package com.jellemax.detour.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.R
import com.jellemax.detour.data.ConvoyInvitePreview
import com.jellemax.detour.data.ConvoyJoinRefusal
import com.jellemax.detour.data.ConvoyJoinStep
import com.jellemax.detour.data.ConvoyJoinStore
import com.jellemax.detour.data.SyncClient
import com.jellemax.detour.data.parseConvoyInviteLink
import kotlinx.coroutines.launch

/**
 * Paste an invite link (#763). A link that parses and belongs to the server this install talks
 * to is handed to [ConvoyJoinStore]; the confirm dialog takes it from there. Nothing goes to the
 * server from here, so a typo costs none of the link's attempts.
 */
@Composable
internal fun JoinConvoyByLinkDialog(onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val invalid = stringResource(R.string.friends_convoy_join_link_invalid)
    val otherServer = stringResource(R.string.friends_convoy_join_link_other_server)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.friends_convoy_join_link_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = { Text(stringResource(R.string.friends_convoy_join_link_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = {
                val link = parseConvoyInviteLink(text)
                when {
                    link == null -> error = invalid
                    !link.isOn(SyncClient.url()) -> error = otherServer.format(link.serverUrl)
                    else -> {
                        ConvoyJoinStore.open(link.token)
                        onDismiss()
                    }
                }
            }) { Text(stringResource(R.string.friends_convoy_join_link_open)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.friends_cancel)) } },
    )
}

/**
 * The join confirm screen (#763) for the link [ConvoyJoinStore] holds: which convoy, who hosts
 * it, and what joining shares, before anything is joined. Only composed signed in; a link opened
 * signed out waits in the store, so signing in lands the rider here.
 *
 * Previews once per link: [ConvoyJoinStore.check] is a no-op once a step is set, so this effect
 * re-running after the answer spends none of the link's attempts. A preview or join cut off by
 * rotation or by leaving the screen steps back in the store, so the screen that comes back asks
 * the server again (one more attempt) or offers Join again, rather than spinning forever.
 */
@Composable
internal fun ConvoyJoinConfirmDialog() {
    val state by ConvoyJoinStore.state.collectAsStateWithLifecycle()
    val token = state.token ?: return
    val scope = rememberCoroutineScope()
    LaunchedEffect(token) { ConvoyJoinStore.check() }

    val step = state.step
    val preview = when (step) {
        is ConvoyJoinStep.Ready -> step.preview
        is ConvoyJoinStep.Joining -> step.preview
        else -> null
    }
    val canJoin = step is ConvoyJoinStep.Ready && !step.preview.alreadyMember
    AlertDialog(
        onDismissRequest = ConvoyJoinStore::dismiss,
        title = {
            Text(
                preview?.let { stringResource(R.string.friends_convoy_join_title, it.convoyName) }
                    ?: stringResource(R.string.friends_convoy_join_title_link),
            )
        },
        text = { ConvoyJoinBody(step, preview) },
        confirmButton = {
            when {
                step is ConvoyJoinStep.Failed -> TextButton(onClick = { scope.launch { ConvoyJoinStore.retry() } }) {
                    Text(stringResource(R.string.friends_convoy_join_retry))
                }
                canJoin || step is ConvoyJoinStep.Joining -> TextButton(
                    enabled = canJoin,
                    onClick = { scope.launch { ConvoyJoinStore.join() } },
                ) {
                    if (step is ConvoyJoinStep.Joining) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.friends_convoy_join))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = ConvoyJoinStore::dismiss) {
                Text(stringResource(if (canJoin) R.string.friends_cancel else R.string.friends_close))
            }
        },
    )
}

@Composable
private fun ConvoyJoinBody(step: ConvoyJoinStep?, preview: ConvoyInvitePreview?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (step) {
            null, ConvoyJoinStep.Checking -> Text(stringResource(R.string.friends_convoy_join_checking))
            is ConvoyJoinStep.Refused -> Text(stringResource(refusalText(step.refusal)))
            is ConvoyJoinStep.Failed -> Text(step.message, color = MaterialTheme.colorScheme.error)
            is ConvoyJoinStep.Ready, is ConvoyJoinStep.Joining -> Unit
        }
        if (preview != null) {
            Text(stringResource(R.string.friends_convoy_join_host, preview.hostUsername, preview.riderCount))
            Text(
                stringResource(
                    if (preview.alreadyMember) R.string.friends_convoy_join_already
                    else R.string.friends_convoy_join_sharing,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun refusalText(refusal: ConvoyJoinRefusal): Int = when (refusal) {
    ConvoyJoinRefusal.UNKNOWN_LINK -> R.string.friends_convoy_join_unknown
    ConvoyJoinRefusal.EXPIRED -> R.string.friends_convoy_join_expired
    ConvoyJoinRefusal.REMOVED -> R.string.friends_convoy_join_removed
    ConvoyJoinRefusal.FULL -> R.string.friends_convoy_join_full
    ConvoyJoinRefusal.TOO_MANY_ATTEMPTS -> R.string.friends_convoy_join_too_many
}

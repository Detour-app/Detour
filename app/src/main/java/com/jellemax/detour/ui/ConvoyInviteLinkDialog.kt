package com.jellemax.detour.ui

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.R
import com.jellemax.detour.data.ConvoyGuest
import com.jellemax.detour.data.ConvoyInvitesStore
import com.jellemax.detour.data.Group
import com.jellemax.detour.data.SyncClient
import com.jellemax.detour.data.convoyInviteLink
import com.jellemax.detour.data.shareableLink
import kotlinx.coroutines.launch
import java.util.Date

/**
 * The host's invite link for [convoy] (#761): mint one, share it through the system share sheet
 * or show it as a QR code to a rider standing beside the host (#762), revoke it, and remove a
 * guest who came in by it. Only offered to the convoy's owner; the server answers 403
 * to anyone else (CIRCLES_AND_CONVOYS.md §4.1), which would land here as the error.
 *
 * All state is [ConvoyInvitesStore]'s, so closing and reopening this keeps the link the host can
 * share: the server never hands a token out twice.
 */
@Composable
internal fun ConvoyInviteLinkDialog(convoy: Group, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by ConvoyInvitesStore.state.collectAsStateWithLifecycle()
    LaunchedEffect(convoy.id) { ConvoyInvitesStore.open(convoy.id) }

    // Only this convoy's answer: until open() lands, the store may still hold another one's.
    val current = state.takeIf { it.convoyId == convoy.id }
    val busy = current?.busy ?: true
    val shareable = current?.shareableLink(System.currentTimeMillis())
    val serverUrl = SyncClient.url()
    val link = if (shareable != null && serverUrl != null) convoyInviteLink(serverUrl, shareable.token) else null
    val elsewhereExpiresAtMs = current?.guests?.inviteExpiresAtMs?.takeIf { shareable == null }
    val create: () -> Unit = { scope.launch { ConvoyInvitesStore.createLink() } }
    val revoke: () -> Unit = { scope.launch { ConvoyInvitesStore.revokeLink() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.convoy_link_title, convoy.name)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.convoy_link_explainer), style = MaterialTheme.typography.bodySmall)
                when {
                    link != null && shareable != null -> {
                        QrCode(
                            link,
                            contentDescription = stringResource(R.string.convoy_link_qr),
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                        SelectionContainer { Text(link, style = MaterialTheme.typography.bodyMedium) }
                        Text(
                            stringResource(R.string.convoy_link_expires, clockTime(context, shareable.expiresAtMs)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !busy, onClick = { shareLink(context, link) }) {
                                Text(stringResource(R.string.convoy_link_share))
                            }
                            TextButton(enabled = !busy, onClick = revoke) {
                                Text(stringResource(R.string.convoy_link_revoke))
                            }
                        }
                    }
                    elsewhereExpiresAtMs != null -> {
                        Text(
                            stringResource(R.string.convoy_link_elsewhere, clockTime(context, elsewhereExpiresAtMs)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !busy, onClick = create) {
                                Text(stringResource(R.string.convoy_link_new))
                            }
                            TextButton(enabled = !busy, onClick = revoke) {
                                Text(stringResource(R.string.convoy_link_revoke))
                            }
                        }
                    }
                    else -> Button(enabled = !busy, onClick = create) {
                        Text(stringResource(R.string.convoy_link_create))
                    }
                }

                current?.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Text(
                    stringResource(R.string.convoy_link_guests),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                val guests = current?.guests?.guests
                when {
                    guests == null -> if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    guests.isEmpty() -> Text(
                        stringResource(R.string.convoy_link_no_guests),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> for (guest in guests) {
                        GuestRow(guest, enabled = !busy, onRemove = {
                            scope.launch { ConvoyInvitesStore.removeGuest(guest.rider.id) }
                        })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.friends_close)) } },
    )
}

@Composable
private fun GuestRow(guest: ConvoyGuest, enabled: Boolean, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(guest.rider.username, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(
                    R.string.convoy_link_joined,
                    DateUtils.getRelativeTimeSpanString(guest.joinedAtMs).toString(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(enabled = enabled, onClick = onRemove) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.convoy_link_remove_guest, guest.rider.username),
                Modifier.size(18.dp),
            )
        }
    }
}

/** "14:05" or "2:05 PM", whichever the phone is set to. */
private fun clockTime(context: Context, ms: Long): String = DateFormat.getTimeFormat(context).format(Date(ms))

private fun shareLink(context: Context, link: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, link)
    }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.convoy_link_share)))
}

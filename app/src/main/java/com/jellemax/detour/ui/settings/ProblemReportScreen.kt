package com.jellemax.detour.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jellemax.detour.R
import com.jellemax.detour.data.CrashStore
import com.jellemax.detour.data.PendingCrash
import com.jellemax.detour.data.ProblemReport
import com.jellemax.detour.data.ProblemReportKind
import com.jellemax.detour.data.ProblemReports
import com.jellemax.detour.data.SendResult
import com.jellemax.detour.diag.EventSink
import com.jellemax.detour.ui.SettingsSection
import com.jellemax.detour.ui.formatDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Something's wrong" (#823): the rider reads exactly what would be sent, adds
 * a note, and nothing leaves the phone until they tap Send.
 *
 * What is shown is the [ProblemReport] that [ProblemReports.send] posts, the
 * same instance, not a description of it. With a [crash] the report is about
 * that crash (#824 opens this screen with one) and the crash is forgotten once
 * the server has it.
 */
@Composable
fun ProblemReportScreen(onClose: () -> Unit, modifier: Modifier = Modifier, crash: PendingCrash? = null) {
    val scope = rememberCoroutineScope()
    var note by rememberSaveable { mutableStateOf("") }
    // Saveable so a rotation after Sent doesn't offer to send it a second time.
    var sentId by rememberSaveable { mutableStateOf<String?>(null) }
    var notSent by remember { mutableStateOf<SendResult?>(null) }
    var sending by remember { mutableStateOf(false) }
    // Null until read: the log is a file, and the first read flushes the writer.
    val events by produceState<List<String>?>(null) {
        value = withContext(Dispatchers.IO) { EventSink.lines() }
    }
    val kind = if (crash != null) ProblemReportKind.CRASH else ProblemReportKind.MANUAL
    val report = remember(note, events) {
        events?.let { ProblemReports.preview(kind, note, crash, it) }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsSection(stringResource(R.string.settings_report_what_is_sent)) {
            Text(
                stringResource(R.string.settings_report_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (report == null) {
                Text(stringResource(R.string.settings_report_reading_log), style = MaterialTheme.typography.bodySmall)
            } else {
                ReportPreview(report)
            }
        }

        SettingsSection(stringResource(R.string.settings_report_note)) {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(ProblemReports.NOTE_MAX_CHARS) },
                modifier = Modifier.fillMaxWidth(),
                enabled = sentId == null && !sending,
                placeholder = { Text(stringResource(R.string.settings_report_note_hint)) },
                supportingText = { Text("${note.length} / ${ProblemReports.NOTE_MAX_CHARS}") },
                minLines = 3,
            )
        }

        val id = sentId
        if (id != null) {
            Text(stringResource(R.string.settings_report_sent, id), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onClose) { Text(stringResource(R.string.settings_report_done)) }
        } else {
            notSent?.let { Text(stringResource(notSentMessage(it)), color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClose) { Text(stringResource(R.string.settings_report_cancel)) }
                Button(
                    enabled = report != null && !sending,
                    onClick = {
                        val toSend = report ?: return@Button
                        sending = true
                        notSent = null
                        scope.launch {
                            val result = ProblemReports.send(toSend)
                            if (result is SendResult.Sent) {
                                if (crash != null) withContext(Dispatchers.IO) { CrashStore.clear() }
                                sentId = result.id
                            } else {
                                notSent = result
                            }
                            sending = false
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            when {
                                sending -> R.string.settings_report_sending
                                notSent is SendResult.Failed -> R.string.settings_report_retry
                                else -> R.string.settings_report_send
                            },
                        ),
                    )
                }
            }
        }
    }
}

/** Every field of [report] as it goes on the wire, the note aside: that is the text field. */
@Composable
private fun ReportPreview(report: ProblemReport) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PreviewRow(R.string.settings_report_platform, report.platform?.wire ?: "—")
        PreviewRow(R.string.settings_report_version, report.appVersion)
        PreviewRow(R.string.settings_report_device, report.deviceModel)
        PreviewRow(R.string.settings_report_os, report.osVersion)
        PreviewRow(R.string.settings_report_time, formatDate(report.occurredAt))
        report.stackTrace?.let {
            Text(stringResource(R.string.settings_report_stack), style = MaterialTheme.typography.titleSmall)
            MonospaceBox(it)
        }
        Text(
            pluralStringResource(R.plurals.settings_report_events, report.events.size, report.events.size),
            style = MaterialTheme.typography.titleSmall,
        )
        if (report.events.isEmpty()) {
            Text(stringResource(R.string.settings_none), style = MaterialTheme.typography.bodySmall)
        } else {
            MonospaceBox(report.events.joinToString("\n"))
        }
    }
}

@Composable
private fun PreviewRow(@StringRes label: Int, value: String) {
    Text("${stringResource(label)}: $value", style = MaterialTheme.typography.bodySmall)
}

/** Its own scroll, both ways, so a long log or stack doesn't push Send off the page. */
@Composable
private fun MonospaceBox(text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState()),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

/** What the rider reads when [result] is not [SendResult.Sent]. */
@StringRes
private fun notSentMessage(result: SendResult): Int =
    if (result == SendResult.Unsupported) R.string.settings_report_unsupported else R.string.settings_report_failed

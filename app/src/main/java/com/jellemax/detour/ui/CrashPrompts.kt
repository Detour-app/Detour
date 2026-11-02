package com.jellemax.detour.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.jellemax.detour.R
import com.jellemax.detour.data.CrashConsent
import com.jellemax.detour.data.CrashOnStart
import com.jellemax.detour.data.CrashStartup
import com.jellemax.detour.data.CrashStore
import com.jellemax.detour.diag.EventSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class CrashPrompt { OFFER, NOTICE }

/**
 * What a start does about crashes (#824): sends the last crash quietly when the
 * rider opted in, offers it when they haven't, and asks once whether they want
 * to opt in. One dialog at a time, the offer first.
 *
 * Decided once per visit and saved, not again after a rotation: the crash is
 * still on disk while the rider reviews it, and deciding again would put the
 * offer back over the report screen it opened. A send a rotation cancels
 * leaves the crash on disk for the next start. While [held] (another dialog
 * is up), the decision and a quiet send still run; only the dialogs wait.
 */
@Composable
fun CrashPrompts(held: Boolean, onReviewCrash: () -> Unit) {
    val scope = rememberCoroutineScope()
    var decided by rememberSaveable { mutableStateOf(false) }
    var prompt by rememberSaveable { mutableStateOf<CrashPrompt?>(null) }
    var noticeShown by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        if (decided) return@LaunchedEffect
        // Both are disk reads: the crash file, and the consent bag on first use.
        val (step, shown) = withContext(Dispatchers.IO) {
            CrashStartup.decide(CrashStore.pending() != null, CrashConsent.sendCrashReports.value) to
                CrashConsent.crashNoticeShown.value
        }
        decided = true
        noticeShown = shown
        prompt = when {
            step == CrashOnStart.OFFER -> CrashPrompt.OFFER
            !shown -> CrashPrompt.NOTICE
            else -> null
        }
        if (step == CrashOnStart.SEND) {
            withContext(Dispatchers.IO) { CrashStartup.sendPending(EventSink::lines) }
        }
    }

    // After the offer, the notice if it is still due.
    fun offerAnswered() {
        prompt = if (noticeShown) null else CrashPrompt.NOTICE
    }

    when (prompt.takeUnless { held }) {
        CrashPrompt.OFFER -> AlertDialog(
            // Tapping outside isn't "Not now": the crash stays for the next start.
            onDismissRequest = { prompt = null },
            title = { Text(stringResource(R.string.crash_offer_title)) },
            text = { Text(stringResource(R.string.crash_offer_body)) },
            confirmButton = {
                TextButton(onClick = {
                    offerAnswered()
                    onReviewCrash()
                }) { Text(stringResource(R.string.crash_offer_review)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    offerAnswered()
                    scope.launch { withContext(Dispatchers.IO) { CrashStore.clear() } }
                }) { Text(stringResource(R.string.crash_not_now)) }
            },
        )
        CrashPrompt.NOTICE -> {
            val answer = { sendAutomatically: Boolean ->
                if (sendAutomatically) CrashConsent.setSendCrashReports(true)
                CrashConsent.markCrashNoticeShown()
                noticeShown = true
                prompt = null
            }
            AlertDialog(
                onDismissRequest = { answer(false) },
                title = { Text(stringResource(R.string.crash_notice_title)) },
                text = { Text(stringResource(R.string.crash_notice_body)) },
                confirmButton = {
                    TextButton(onClick = { answer(true) }) { Text(stringResource(R.string.crash_notice_turn_on)) }
                },
                dismissButton = {
                    TextButton(onClick = { answer(false) }) { Text(stringResource(R.string.crash_not_now)) }
                },
            )
        }
        null -> Unit
    }
}

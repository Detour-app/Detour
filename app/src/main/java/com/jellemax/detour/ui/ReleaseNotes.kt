package com.jellemax.detour.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.unit.dp
import com.jellemax.detour.BuildConfig
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding

/**
 * What changed in the version now running, once, on the first launch after an
 * in-app update (#359). Dismissing it is what marks it seen; the same notes stay
 * under the update row in Settings afterwards.
 */
@Composable
fun InstalledNotesDialog(notes: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text("What's new in ${BuildConfig.VERSION_NAME}") },
        text = {
            ReleaseNotesMarkdown(notes, Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
        },
    )
}

/**
 * A release body rendered for a settings card or a dialog. Shared by the update
 * row's "What's new" (see `UpdateNotesSection` for why links are live and
 * images are not) and [InstalledNotesDialog].
 */
@Composable
fun ReleaseNotesMarkdown(notes: String, modifier: Modifier = Modifier) {
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
        modifier = modifier,
    )
}

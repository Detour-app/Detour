package com.jellemax.detour.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.jellemax.detour.ui.SettingsSection

/**
 * Attribution for every open dataset the app ships with, required by ODbL's
 * attribution/share-alike terms (issue #371). MapLibre's own basemap
 * attribution button (the map's ⓘ, wired in `RetainedMap.kt`) covers only the
 * basemap tiles — a different dataset from the camera-data overlay this
 * screen accounts for, and not something app code populates.
 *
 * One section per source rather than one paragraph, so a future source (an
 * official portal, per #303's phasing) is one more [LicenceSection] call, not
 * a rewrite.
 */
@Composable
fun LicencesScreen() {
    val context = LocalContext.current
    LicenceSection(
        name = "OpenStreetMap",
        text = "Speed cameras, enforcement sections and road data — " +
            "© OpenStreetMap contributors, Open Database License (ODbL).",
        url = "https://www.openstreetmap.org/copyright",
    ) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
    LicenceSection(
        name = "lufop.net",
        text = "Additional speed cameras — data from lufop.net, " +
            "Open Database License (ODbL).",
        url = "https://lufop.net/mentions-legales/",
    ) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
}

@Composable
private fun LicenceSection(name: String, text: String, url: String, onOpen: (String) -> Unit) {
    SettingsSection(name) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { onOpen(url) }) { Text("View licence") }
    }
}

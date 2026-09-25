package com.jellemax.detour.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    LicenceSection(
        name = "OpenStreetMap",
        text = "Speed cameras, enforcement sections and road data — " +
            "© OpenStreetMap contributors, Open Database License (ODbL).",
        url = "https://www.openstreetmap.org/copyright",
    )
    LicenceSection(
        name = "lufop.net",
        text = "Additional speed cameras — data from lufop.net, " +
            "Open Database License (ODbL).",
        url = "https://lufop.net/mentions-legales/",
    )
}

@Composable
private fun LicenceSection(name: String, text: String, url: String) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    SettingsSection(name) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = {
            error = try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                null
            } catch (e: ActivityNotFoundException) {
                "No app to open $url"
            }
        }) { Text("View licence") }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

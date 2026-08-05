package com.jellemax.detour.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.jellemax.detour.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Exports a recorded trip as GPX 1.1 — the one track format every mapping tool
 * reads, so a ride can leave this app for Strava, Garmin, JOSM or a backup
 * without the trace store's JSONL going with it.
 *
 * Only the trip being exported is written. Traces are private (see the fog
 * sharing rules in the sync server) and this is the one path that puts one
 * outside the app, so it is driven by an explicit share from the trip's own
 * screen and never batches the whole history.
 */
object Gpx {

    /** Cache subdirectory the FileProvider is scoped to in `res/xml/file_paths.xml`. */
    private const val SHARE_DIR = "shared"

    fun build(trip: Trip, points: List<TraceStore.TracePoint>): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        val sb = StringBuilder(points.size * 80)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Detour\"")
        sb.append(" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <metadata><time>").append(stamp.format(Date(trip.startTimeMs)))
            .append("</time></metadata>\n")
        sb.append("  <trk>\n")
        sb.append("    <name>").append(escape(trackName(trip))).append("</name>\n")
        sb.append("    <type>").append(trip.mode.name.lowercase(Locale.US)).append("</type>\n")
        sb.append("    <trkseg>\n")
        for (p in points) {
            // Fixed decimals rather than Double.toString: a longitude near the
            // prime meridian renders as 1.0E-5 otherwise, which no GPX reader
            // parses. Seven places is ~1 cm, well past what a phone fix knows.
            sb.append(String.format(Locale.US, "      <trkpt lat=\"%.7f\" lon=\"%.7f\">",
                p.at.lat, p.at.lon))
            if (p.timeMs > 0) {
                sb.append("<time>").append(stamp.format(Date(p.timeMs))).append("</time>")
            }
            sb.append("</trkpt>\n")
        }
        sb.append("    </trkseg>\n  </trk>\n</gpx>\n")
        return sb.toString()
    }

    /**
     * Writes [trip]'s GPX into the shared cache and returns a content:// Uri
     * for it. Cached rather than saved: the receiving app copies what it keeps,
     * and an export the user didn't ask to keep shouldn't outlive the share.
     */
    fun writeForShare(context: Context, trip: Trip, points: List<TraceStore.TracePoint>): Uri {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        // One file per trip, overwritten on re-export — sharing the same ride
        // twice shouldn't litter the cache with copies.
        val file = File(dir, fileName(trip))
        file.writeText(build(trip, points))
        return FileProvider.getUriForFile(
            context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
    }

    fun fileName(trip: Trip): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(trip.startTimeMs))
        return "detour-${trip.mode.name.lowercase(Locale.US)}-$stamp.gpx"
    }

    private fun trackName(trip: Trip): String {
        val stamp = SimpleDateFormat("d MMMM yyyy HH:mm", Locale.getDefault())
            .format(Date(trip.startTimeMs))
        return "${trip.mode.label} · $stamp"
    }

    /** The track name carries a user-visible label and a localized date, so it
     *  is the one part of this document that isn't ours to trust as XML. */
    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

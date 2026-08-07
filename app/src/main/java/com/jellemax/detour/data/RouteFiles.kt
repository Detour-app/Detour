package com.jellemax.detour.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.jellemax.detour.BuildConfig
import java.io.File
import java.io.IOException

/**
 * File in/out for saved routes: share a GPX out through the system share
 * sheet, or export/import one through the SAF file picker. Mirrors Gpx.kt's
 * shape (share via FileProvider cache, export/import via contentResolver) and
 * ConfigFile.kt's export/import split, but for a [SavedRoute] instead of a
 * recorded trip or the server config.
 */
object RouteFiles {

    /**
     * Writes [route]'s GPX into the same FileProvider cache dir Gpx.kt uses
     * (see [Gpx.SHARE_DIR]) and returns a content:// Uri for it — one
     * `file_paths.xml` entry covers both, so no manifest change is needed
     * here. Cached rather than saved: the receiving app copies what it keeps.
     */
    fun writeForShare(context: Context, route: SavedRoute): Uri {
        val dir = File(context.cacheDir, Gpx.SHARE_DIR).apply { mkdirs() }
        // One file per route name, overwritten on re-share — sharing the same
        // route twice shouldn't litter the cache with copies.
        val file = File(dir, RouteGpx.fileName(route))
        file.writeText(RouteGpx.buildGpx(route))
        return FileProvider.getUriForFile(
            context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
    }

    /** Writes [route] as GPX to a location the user picked via
     *  `CreateDocument(RouteGpx.MIME_TYPE)`. */
    fun export(context: Context, uri: Uri, route: SavedRoute) {
        context.contentResolver.openOutputStream(uri, "wt")?.use {
            it.write(RouteGpx.buildGpx(route).toByteArray())
        } ?: throw IOException("Could not open $uri for writing")
    }

    /**
     * Reads a route from a location the user picked via `OpenDocument()`,
     * gives it a fresh id (an imported file's id may collide with one already
     * on this device) and saves it into [RouteStore]. Null when the file
     * isn't a route this app or a GPX tool could have written — see
     * [RouteGpx.parseRouteFile].
     */
    fun import(context: Context, uri: Uri): SavedRoute? {
        val text = context.contentResolver.openInputStream(uri)?.use {
            it.bufferedReader().readText()
        } ?: throw IOException("Could not open $uri for reading")
        val nowMs = System.currentTimeMillis()
        val parsed = RouteGpx.parseRouteFile(text, nowMs) ?: return null
        val route = parsed.copy(id = nowMs)
        RouteStore.save(route)
        return route
    }

    /**
     * File types accepted by the import picker. Several file managers report
     * a `.gpx` file's MIME type as `application/octet-stream` rather than
     * `application/gpx+xml`, so a filter of just [RouteGpx.MIME_TYPE] greys
     * out the very files this is meant to open — these cover GPX, plain XML,
     * this app's own JSON export, and the octet-stream fallback.
     */
    val IMPORT_MIME_TYPES = arrayOf(
        RouteGpx.MIME_TYPE,
        "application/xml",
        "text/xml",
        "application/json",
        "application/octet-stream",
    )
}

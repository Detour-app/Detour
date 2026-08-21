package com.jellemax.detour.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.jellemax.detour.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a rendered trip card PNG into the same FileProvider cache dir
 * [Gpx] uses, and hands back the same `ACTION_SEND` shape — a card share is
 * additive to the `.gpx` export, not a replacement, so it deliberately reuses
 * every part of that path except the file extension and mime type.
 */
object TripCardFile {

    fun writeForShare(context: Context, trip: Trip, bitmap: Bitmap): Uri {
        val dir = File(context.cacheDir, Gpx.SHARE_DIR).apply { mkdirs() }
        val file = File(dir, fileName(trip))
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        return FileProvider.getUriForFile(
            context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
    }

    fun shareIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun fileName(trip: Trip): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(trip.startTimeMs))
        return "detour-card-${trip.mode.name.lowercase(Locale.US)}-$stamp.png"
    }
}

package com.jellemax.detour.map

import android.content.Context
import android.util.Log
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.LatLonBox
import com.jellemax.detour.data.RouteCorridor
import com.jellemax.detour.ui.openFreeMapStyleUrl
import org.maplibre.android.offline.OfflineGeometryRegionDefinition
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.geojson.MultiPolygon
import org.maplibre.geojson.Point

private const val TAG = "DetourTileCache"

/** Prefix of the region's metadata, so a clear or a new route only touches our corridor. */
private const val CORRIDOR_TAG = "detour-route-corridor"

/**
 * Most a corridor may download, in bytes. A 100 km loop at ±5 km, z8–15, is
 * tens of MB of OpenFreeMap vector tiles; past this the download stops where it
 * got to rather than filling the phone on a cross-country route. #439.
 */
private const val MAX_CORRIDOR_BYTES = 150L * 1024 * 1024

/**
 * Pre-fetches the map tiles along the active route through MapLibre's offline
 * region API, so the map keeps drawing when coverage drops mid-ride. #439.
 *
 * Android-only because MapLibre's offline database is; which area to fetch is
 * [RouteCorridor] in shared. The phone map and `CarMapRenderer` share the one
 * offline database per process, so tiles fetched from either surface serve both.
 *
 * Holds at most one corridor: a new route deletes the previous one first, so
 * the cache never grows past [MAX_CORRIDOR_BYTES] plus MapLibre's own ambient
 * cache. The same route again (car nav resuming, the phone restarting it) keeps
 * the corridor it already has: deleting it evicts its tiles, and with no signal
 * the replacement could not fetch them back. Every call is from the main thread,
 * where MapLibre delivers callbacks.
 */
object RouteTileCache {
    /** Bumped per [prefetch] and [clear]; a region created for an older call is deleted on arrival. */
    private var generation = 0

    fun prefetch(context: Context, polyline: List<LatLon>, darkTheme: Boolean) {
        val boxes = RouteCorridor.boxes(polyline)
        if (boxes.isEmpty()) return
        val gen = ++generation
        val manager = OfflineManager.getInstance(context)
        // Keyed on the area alone, not the style: light and dark draw the same
        // OpenFreeMap tiles, so a theme switch between phone and car is the same corridor.
        val metadata = "$CORRIDOR_TAG:${boxes.hashCode()}".encodeToByteArray()
        deleteCorridors(manager, keep = metadata) { kept ->
            if (gen != generation) return@deleteCorridors
            // Resume rather than recreate: tiles it already holds are not fetched again,
            // and one cut short by a lost connection gets finished.
            if (kept != null) {
                download(kept)
                return@deleteCorridors
            }
            val definition = OfflineGeometryRegionDefinition(
                openFreeMapStyleUrl(darkTheme),
                MultiPolygon.fromLngLats(boxes.map { listOf(ring(it)) }),
                RouteCorridor.MIN_ZOOM,
                RouteCorridor.MAX_ZOOM,
                context.resources.displayMetrics.density,
            )
            val created = object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    // A newer route (or a clear) arrived while this one was being created.
                    if (gen != generation) {
                        offlineRegion.delete(logDelete)
                        return
                    }
                    download(offlineRegion)
                }

                override fun onError(error: String) {
                    Log.w(TAG, "could not create corridor region: $error")
                }
            }
            manager.createOfflineRegion(definition, metadata, created)
        }
    }

    /** Deletes the corridor and empties MapLibre's ambient tile cache, then calls [onDone]. */
    fun clear(context: Context, onDone: () -> Unit) {
        generation++
        val manager = OfflineManager.getInstance(context)
        deleteCorridors(manager, keep = null) {
            manager.clearAmbientCache(object : OfflineManager.FileSourceCallback {
                override fun onSuccess() = onDone()

                override fun onError(message: String) {
                    Log.w(TAG, "could not clear ambient cache: $message")
                    onDone()
                }
            })
        }
    }

    private fun download(region: OfflineRegion) {
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                if (status.isComplete || status.completedResourceSize >= MAX_CORRIDOR_BYTES) {
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                }
            }

            override fun onError(error: OfflineRegionError) {
                // Transient (a dropped connection mid-download); MapLibre retries on its own.
                Log.w(TAG, "corridor download: ${error.reason} ${error.message}")
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            }
        })
        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    /**
     * Deletes every region this cache created except one whose metadata is [keep],
     * then runs [then] with that one, if any — also when listing fails.
     */
    private fun deleteCorridors(manager: OfflineManager, keep: ByteArray?, then: (kept: OfflineRegion?) -> Unit) {
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val ours = offlineRegions.orEmpty().filter { it.metadata.decodeToString().startsWith(CORRIDOR_TAG) }
                val (kept, stale) = ours.partition { keep != null && it.metadata.contentEquals(keep) }
                stale.forEach {
                    it.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    it.delete(logDelete)
                }
                then(kept.firstOrNull())
            }

            override fun onError(error: String) {
                Log.w(TAG, "could not list offline regions: $error")
                then(null)
            }
        })
    }

    private val logDelete = object : OfflineRegion.OfflineRegionDeleteCallback {
        override fun onDelete() = Unit

        override fun onError(error: String) {
            Log.w(TAG, "could not delete corridor region: $error")
        }
    }

    /** Closed ring around [box], counter-clockwise as GeoJSON wants. */
    private fun ring(box: LatLonBox): List<Point> = listOf(
        Point.fromLngLat(box.west, box.south),
        Point.fromLngLat(box.east, box.south),
        Point.fromLngLat(box.east, box.north),
        Point.fromLngLat(box.west, box.north),
        Point.fromLngLat(box.west, box.south),
    )
}

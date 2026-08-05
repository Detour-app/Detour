package com.jellemax.detour.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persists recently picked search results, most recent first, in app-private storage. */
object RecentSearchStore {

    private const val FILE_NAME = "recent_searches.json"
    private const val MAX_ENTRIES = 8

    fun save(context: Context, result: GeocodeResult) {
        val entries = load(context).toMutableList()
        entries.removeAll { it.name == result.name }
        entries.add(0, result)
        val array = JSONArray()
        for (r in entries.take(MAX_ENTRIES)) {
            array.put(
                JSONObject()
                    .put("name", r.name)
                    .put("lat", r.location.lat)
                    .put("lon", r.location.lon)
            )
        }
        file(context).writeText(array.toString())
    }

    fun load(context: Context): List<GeocodeResult> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val array = JSONArray(f.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                GeocodeResult(
                    name = o.getString("name"),
                    location = LatLon(o.getDouble("lat"), o.getDouble("lon")),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}

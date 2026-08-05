package com.jellemax.detour.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The combined fog of war: your friends' traces unioned with your own, so a
 * group uncovers the map together.
 *
 * Held in memory only. These are someone else's traces — they are not ours to
 * write to disk, and re-fetching them costs one request per app launch. The
 * server hands them over only when both sides have opted in
 * ([Settings.shareFog]), so an empty list is the normal state, not an error.
 */
object FriendFog {

    private val _traces = MutableStateFlow<List<List<LatLon>>>(emptyList())
    val traces: StateFlow<List<List<LatLon>>> = _traces

    /** Blocking; call off the main thread. Never throws — a friend's fog going
     *  missing is not worth interrupting the map for. */
    fun refresh(context: Context) {
        if (!SyncClient.configured(context) || !Account.signedIn || !Settings.shareFog.value) {
            _traces.value = emptyList()
            return
        }
        _traces.value = try {
            val response: JSONObject = Api.requestJson(context, "GET", "/friends/fog")
            val array: JSONArray = response.optJSONArray("traces") ?: JSONArray()
            TraceStore.parseLines((0 until array.length()).map { array.getString(it) })
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Stop showing friends' territory the moment sharing is turned off, rather
     *  than at the next launch. */
    fun clear() {
        _traces.value = emptyList()
    }
}

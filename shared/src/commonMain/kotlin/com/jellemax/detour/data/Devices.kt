package com.jellemax.detour.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * This install's push registration token, on the server. The token itself is
 * obtained per-platform (an FCM token on Android, via the Firebase SDK) and is
 * not this object's concern — it only carries whatever token it is given to the
 * backend so the server can wake this device when a circle event lands for it
 * (see `docs/PUSH.md` §3). Both calls are idempotent and bearer-authed like the
 * rest of [Api].
 */
object Devices {

    @Throws(Exception::class)
    suspend fun register(token: String, platform: String = "android") {
        Api.request(
            "PUT", "/devices",
            buildJsonObject {
                put("token", token)
                put("platform", platform)
            },
        )
    }

    @Throws(Exception::class)
    suspend fun unregister(token: String) {
        Api.request("DELETE", "/devices", buildJsonObject { put("token", token) })
    }
}

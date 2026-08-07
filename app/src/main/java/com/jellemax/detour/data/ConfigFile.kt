package com.jellemax.detour.data

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/**
 * Export/import of the server configuration to a file the user picks through
 * the system file picker.
 *
 * Everything here otherwise lives in app-private preferences (wiped on
 * uninstall) or is baked into the APK at build time from local.properties
 * (absent from a release APK built by CI). A config file the user keeps
 * outside the app survives a reinstall without any of it going near the repo.
 *
 * The file contains the sync bearer token: it is a credential, not a backup.
 */
object ConfigFile {

    const val SUGGESTED_NAME = "detour-config.json"
    const val MIME_TYPE = "application/json"

    /** The effective config, so exporting from a locally built APK captures
     *  the baked-in defaults that a CI-built APK will not have. */
    fun export(context: Context, uri: Uri) {
        val server = RoutingServer.load()
        val json = JSONObject()
            .put("routingUrl", server.url)
            .put("routingClientId", server.clientId)
            .put("routingClientSecret", server.clientSecret)
            .put("authToken", Settings.authToken.value)
            .put("authUsername", Settings.authUsername.value)
        context.contentResolver.openOutputStream(uri, "wt")?.use {
            it.write(json.toString(2).toByteArray())
        } ?: throw java.io.IOException("Could not open $uri for writing")
    }

    /** Applies every field the file carries. Blank routing URL clears the
     *  custom server rather than saving an unusable one. */
    fun import(context: Context, uri: Uri) {
        val text = context.contentResolver.openInputStream(uri)?.use {
            it.bufferedReader().readText()
        } ?: throw java.io.IOException("Could not open $uri for reading")
        val json = JSONObject(text)

        val routingUrl = json.optString("routingUrl").trim()
        if (routingUrl.isBlank()) {
            RoutingServer.clearCustom()
        } else {
            RoutingServer.save(ServerConfig(
                url = routingUrl,
                clientId = json.optString("routingClientId"),
                clientSecret = json.optString("routingClientSecret"),
                enabled = true,
            ))
        }
        val token = json.optString("authToken").trim()
        if (token.isNotBlank()) {
            Settings.setAuth(token, json.optString("authUsername").trim())
        }
    }
}

package com.jellemax.detour.update

import java.io.File

/**
 * The three filenames one update artefact owns, and the gate in front of them.
 *
 * `PendingUpdate.asset` is read from the release's `update.json`
 * (`shared/…/data/UpdateClient.kt:16-24`), so it is remote data being used as a
 * filename. A manifest naming `../../shared_prefs/settings.xml` is not a
 * release worth installing, so a name that fails [isSafeAssetName] refuses the
 * download rather than being sanitised into a different one.
 */
internal object UpdatePartFiles {

    /** One path segment of the characters a release asset actually uses. */
    private val SAFE = Regex("^[A-Za-z0-9._-]{1,128}$")

    /** The three paths for one asset, all inside the updates directory. */
    data class Paths(val target: File, val part: File, val meta: File)

    fun isSafeAssetName(name: String): Boolean =
        SAFE.matches(name) && name != "." && name != ".."

    /**
     * The paths for [name] under [dir], or null when [name] is not a safe
     * segment or does not resolve inside [dir] — the second check is belt and
     * braces over the first, and costs one canonicalisation per download.
     */
    fun resolve(dir: File, name: String): Paths? {
        if (!isSafeAssetName(name)) return null
        val target = File(dir, name)
        val root = dir.canonicalFile
        if (target.canonicalFile.parentFile != root) return null
        return Paths(
            target = target,
            part = File(dir, "$name.part"),
            meta = File(dir, "$name.part.meta"),
        )
    }
}

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

    /**
     * One path segment of the characters a release asset actually uses.
     *
     * The 128 cap has no filesystem basis — it is not a verified limit of any
     * target filesystem — it is there so a hostile manifest cannot hand this
     * code a pathological filename. The convention this repo actually
     * produces, `detour-<version>.apk` (see
     * `UpdateCheck.conventionalPhoneAsset` in
     * `shared/src/commonMain/kotlin/com/jellemax/detour/data/UpdateCheck.kt`),
     * is a fraction of that.
     */
    private val SAFE = Regex("^[A-Za-z0-9._-]{1,128}$")

    /** The three paths for one asset, all inside the updates directory. */
    data class Paths(val target: File, val part: File, val meta: File)

    fun isSafeAssetName(name: String): Boolean =
        SAFE.matches(name) && name != "." && name != ".."

    /**
     * The paths for [name] under [dir], or null when [name] is not a safe
     * segment or does not resolve inside [dir] — the second check is belt and
     * braces over the first, and costs one canonicalisation per download.
     *
     * `canonicalFile` can throw (e.g. a filesystem I/O error); that is treated
     * the same as "resolved outside [dir]" — a failure to canonicalise means
     * containment cannot be proven, so refuse rather than let the exception
     * escape past this function's null contract.
     */
    fun resolve(dir: File, name: String): Paths? {
        if (!isSafeAssetName(name)) return null
        val target = File(dir, name)
        val root = runCatching { dir.canonicalFile }.getOrNull() ?: return null
        val resolvedParent = runCatching { target.canonicalFile.parentFile }.getOrNull()
        if (resolvedParent != root) return null
        return Paths(
            target = target,
            part = File(dir, "$name.part"),
            meta = File(dir, "$name.part.meta"),
        )
    }
}

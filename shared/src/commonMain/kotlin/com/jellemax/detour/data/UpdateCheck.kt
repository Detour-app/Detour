package com.jellemax.detour.data

/**
 * The decidable half of the update check: is the published release newer than
 * this build, and which file should be downloaded for it.
 *
 * Pure by design. The fetch is [UpdateClient]'s and the install is Android's,
 * and neither can be tested here — this can, and it holds the two rules that
 * fail silently when wrong.
 */
object UpdateCheck {

    /**
     * Whether [candidate] is a newer version than [installed].
     *
     * Dotted numbers compared component-wise, missing segments read as zero.
     * Not a string comparison: "1.10.0" sorts before "1.9.0" lexically.
     *
     * Anything unparseable is false. Offering an update the app cannot reason
     * about ends at an install sheet the rider cannot complete.
     */
    fun isNewer(installed: String, candidate: String): Boolean {
        val a = parseVersion(installed) ?: return false
        val b = parseVersion(candidate) ?: return false
        val width = maxOf(a.size, b.size)
        for (i in 0 until width) {
            val ai = a.getOrElse(i) { 0 }
            val bi = b.getOrElse(i) { 0 }
            if (bi != ai) return bi > ai
        }
        return false
    }

    /** Null for anything that is not dot-separated non-negative integers. */
    private fun parseVersion(v: String): List<Int>? {
        if (v.isBlank()) return null
        val parts = v.split(".")
        return parts.map { it.toIntOrNull() ?: return null }
    }
}

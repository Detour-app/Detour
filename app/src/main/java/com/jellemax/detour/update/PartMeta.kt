package com.jellemax.detour.update

import com.jellemax.detour.data.UpdateClient

/**
 * What a `.part` file on disk is, so a resume can prove it is still the right
 * bytes before appending to them.
 *
 * Four newline-separated fields rather than JSON: every field is a token that
 * cannot contain a newline (a version, a hex digest, a decimal, an HTTP header
 * value), and the app-side JSON helpers live in `:shared` behind types this
 * package does not otherwise need.
 *
 * That assumption is not what actually protects [decode], though: `encode`
 * joins exactly 4 fields with `\n`, so a stray newline inside any field can
 * only ever raise the split count above 4, never keep it at 4 with different
 * boundaries. If a field ever does contain a newline, [decode] does not
 * mis-parse it into a different, wrong record — it sees more than 4 parts and
 * returns null. The guarantee holds even if the assumption above turns out to
 * be false.
 */
internal data class PartMeta(
    val version: String,
    val sha256: String,
    val size: Long,
    val etag: String,
) {
    fun encode(): String = listOf(version, sha256, size.toString(), etag).joinToString("\n")

    /**
     * Whether a partial written under this record may be continued for
     * [update] against a server now reporting [observedEtag]. Every field
     * must agree: a release re-cut under the same version, or an asset
     * re-uploaded under the same name, both change the bytes without
     * changing the filename.
     */
    fun matches(update: UpdateClient.PendingUpdate, observedEtag: String): Boolean =
        version == update.version &&
            sha256 == update.sha256 &&
            size == update.size &&
            etag == observedEtag

    companion object {
        fun of(update: UpdateClient.PendingUpdate, etag: String): PartMeta =
            PartMeta(update.version, update.sha256, update.size, etag)

        /** Null for anything unreadable — same contract as `UpdateCheck.parseRelease`. */
        fun decode(text: String): PartMeta? {
            val lines = text.split("\n")
            if (lines.size != 4) return null
            val size = lines[2].toLongOrNull() ?: return null
            return PartMeta(lines[0], lines[1], size, lines[3])
        }
    }
}

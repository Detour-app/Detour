package com.jellemax.detour.data

/**
 * GitHub's generated release bodies, turned into the thing a rider reads in
 * Settings → "What's new".
 *
 * Two jobs, both pure and both testable with literals (§10):
 *
 *  - [riderNotes] takes one body and removes the scaffolding GitHub's generator
 *    wraps around the actual changes.
 *  - [releaseRangeNotes] merges every release between the installed version and
 *    the one being offered, so a rider several releases behind reads all of it
 *    rather than only the newest (#358).
 *
 * Kept apart from `UpdateCheck` deliberately: that file turns release JSON into
 * a [UpdateCheck.Release], and this one decides what a rider sees. Two reasons
 * to change, so two files (`boundaries.md` §8.1).
 */
internal object ReleaseNotes {

    /**
     * How many releases' notes are merged at most.
     *
     * Two limits in one number, and they are set equal on purpose. It is the
     * page size asked of `/releases` — never paginated, so a rider on a
     * year-old build costs exactly one request rather than walking hundreds —
     * and it is also the number of sections rendered, because ten bodies is
     * already more than an expander in a settings card can usefully show.
     *
     * Ten because the payload is the binding constraint, not the rate limit:
     * measured against this repo, `/releases` costs ~8.7 KB per release (the
     * body is a fraction of it; the rest is author, asset and reaction
     * metadata, and the REST API offers no way to ask for less). Ten is ~87 KB,
     * against 264 KB for the default 30. Five releases inside ninety minutes
     * has happened here, so ten covers the realistic case twice over; beyond it
     * the rider gets a compare link instead.
     */
    const val RANGE_CAP = 10

    /**
     * One release body with GitHub's scaffolding removed.
     *
     * Null when nothing is left — a release whose body is only scaffolding
     * shows no section at all rather than an empty version heading.
     *
     * What goes, and why each is safe to drop:
     *
     *  - **The generator's own HTML comment.** Invisible on the releases page,
     *    literal text in the app.
     *  - **`## What's Changed`.** It sits directly under the app's own
     *    "What's new" header saying the same thing, and once several releases
     *    are merged it repeats once per release.
     *  - **`**Full Changelog**: <compare url>`.** Developer-facing, and ~66
     *    characters of unwrappable URL per release.
     *  - **The pre-#360 preamble.** Releases up to v2.31.2 open with a fixed
     *    version/sideload/no-server block that #360 retired. Those bodies are
     *    published and will not change, so a rider updating across that
     *    boundary still meets it. Matched on its own first line, which is text
     *    this repo wrote and has since stopped writing.
     *  - **A lone `###` category.** `.github/release.yml` groups by label, so a
     *    release with one category renders a subheading that says nothing the
     *    version heading above it did not. A release with two or more keeps
     *    them, which is what makes this a rule rather than a special case for
     *    the string "Other".
     *
     * `by @user in <pull url>` becomes `(#123)`: the URL is most of the line,
     * and a merged view has one per change.
     */
    fun riderNotes(body: String?): String? {
        val raw = body?.trim().orEmpty()
        if (raw.isEmpty()) return null
        var text = HTML_COMMENT.replace(raw, "")
        text = PREAMBLE.replace(text, "")
        text = text.lineSequence()
            .filterNot { CHANGELOG_LINE.matches(it.trim()) }
            .filterNot { it.trim() == WHATS_CHANGED }
            .joinToString("\n")
        text = dropLoneCategory(text)
        text = PULL_CREDIT.replace(text) { m -> " (#${m.groupValues[1]})" }
        return text.lines().joinToString("\n") { it.trimEnd() }
            .replace(BLANK_RUN, "\n\n")
            .trim()
            .ifBlank { null }
    }

    /**
     * Every release newer than [installedVersion], newest first, each under its
     * own `## <version>` heading — which the Android surface renders as a
     * heading rather than as source since #357.
     *
     * Null when there is nothing to show, so the caller keeps whatever it had.
     *
     * **Pre-releases are skipped.** `/releases` returns them and
     * `/releases/latest` does not, so including them would describe changes the
     * rider is not being offered. Making that a rider-facing toggle is a real
     * option and deliberately not taken here: this repo publishes none, so the
     * setting would ship unused and untested.
     *
     * [releases] is one page, never more. When the page runs out before the
     * installed version does — or when there are more than [cap] of them — the
     * rider is further behind than one request can describe, and gets a compare
     * link for the rest rather than a second request.
     */
    fun releaseRangeNotes(
        releases: List<UpdateCheck.Release>,
        installedVersion: String,
        repo: String,
        cap: Int = RANGE_CAP,
    ): String? {
        val newer = releases
            .filterNot { it.prerelease }
            .filter { UpdateCheck.isNewer(installedVersion, it.version) }
        if (newer.isEmpty()) return null

        val sections = newer.take(cap).mapNotNull { release ->
            riderNotes(release.notes)?.let { "## ${release.version}\n$it" }
        }
        if (sections.isEmpty()) return null

        // Either more than fit, or the page ended before the installed version
        // did — in both cases there is more than this response can describe.
        val moreBeyond = newer.size > cap || newer.size == releases.size
        val tail = if (!moreBeyond || repo.isBlank()) "" else {
            val from = "v$installedVersion"
            val to = "v${newer.first().version}"
            "\n\n[Earlier releases](https://github.com/$repo/compare/$from...$to)"
        }
        return sections.joinToString("\n\n") + tail
    }

    /**
     * Removes a `###` heading when it is the only one in the body.
     *
     * Not a match on "Other": the rule is that a single category under a single
     * version heading carries no information, whatever it is called. Two or
     * more are a real grouping and are kept — which is also what makes this
     * improve on its own once pull requests here start carrying the labels
     * `.github/release.yml` groups by.
     */
    private fun dropLoneCategory(text: String): String {
        val lines = text.lines()
        val headings = lines.count { CATEGORY_LINE.matches(it.trim()) }
        if (headings != 1) return text
        return lines.filterNot { CATEGORY_LINE.matches(it.trim()) }.joinToString("\n")
    }

    private const val WHATS_CHANGED = "## What's Changed"
    // `[\s\S]` rather than RegexOption.DOT_MATCHES_ALL, for the reason
    // RouteGpx.kt already records: that option exists only on the JVM, and this
    // is common code. It compiles for the Android target either way, so the
    // gate that catches it is `compileCommonMainKotlinMetadata`, not the tests.
    private val HTML_COMMENT = Regex("""<!--[\s\S]*?-->""")
    private val CHANGELOG_LINE = Regex("""\*\*Full Changelog\*\*:.*""")
    private val CATEGORY_LINE = Regex("""###\s+\S.*""")
    private val PULL_CREDIT = Regex("""\s+by @[\w-]+ in https://github\.com/\S+/pull/(\d+)""")
    private val BLANK_RUN = Regex("\n{3,}")

    /** The retired preamble, matched from its first line to the end of the
     *  no-server paragraph. Anchored on `Detour v<version> (versionCode`, which
     *  only that block ever produced. */
    private val PREAMBLE = Regex(
        """^Detour v[\d.]+ \(versionCode[\s\S]*?to host one\.\s*""",
        RegexOption.MULTILINE,
    )
}

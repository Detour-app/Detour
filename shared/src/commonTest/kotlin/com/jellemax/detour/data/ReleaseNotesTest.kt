package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Turning GitHub's generated release bodies into what a rider reads (#358).
 *
 * The fixtures are the real published bodies, shortened only where a line
 * repeats — a v2.31.3 body is what `generate_release_notes` actually wrote,
 * including the HTML comment and the trailing compare URL, because those are
 * the parts being removed and a hand-idealised fixture would not exercise them.
 */
class ReleaseNotesTest {

    /** A body as published after #360 retired the preamble. */
    private fun body(version: String, pull: Int, summary: String) = """
        <!-- Release notes generated using configuration in .github/release.yml at main -->

        ## What's Changed
        ### Other
        * $summary by @someone in https://github.com/Detour-app/Detour/pull/$pull


        **Full Changelog**: https://github.com/Detour-app/Detour/compare/v0.0.0...v$version
    """.trimIndent()

    private fun release(version: String, pull: Int, summary: String, pre: Boolean = false) =
        UpdateCheck.Release(
            version = version,
            prerelease = pre,
            assets = emptyMap(),
            notes = body(version, pull, summary),
        )

    // ---- riderNotes: one body -------------------------------------------

    @Test
    fun theGeneratorsScaffoldingIsRemoved() {
        val out = ReleaseNotes.riderNotes(body("2.31.3", 363, "chore(release): retire the preamble"))
        assertNotNull(out)
        assertFalse(out!!.contains("<!--"), "the HTML comment is not rider-facing: $out")
        assertFalse(out.contains("What's Changed"), "duplicates the app's own header: $out")
        assertFalse(out.contains("Full Changelog"), "developer-facing: $out")
        assertFalse(out.contains("### "), "a lone category says nothing: $out")
    }

    @Test
    fun aPullCreditBecomesItsNumber() {
        val out = ReleaseNotes.riderNotes(body("2.31.3", 363, "chore(release): retire the preamble"))
        assertEquals("* chore(release): retire the preamble (#363)", out)
    }

    /** Two categories are a real grouping, so both headings stay — the rule is
     *  "a lone category", not "the string Other". */
    @Test
    fun twoCategoriesAreBothKept() {
        val out = ReleaseNotes.riderNotes(
            """
            ## What's Changed
            ### New
            * feat(map): a thing by @a in https://github.com/x/y/pull/1
            ### Fixed
            * fix(map): another by @b in https://github.com/x/y/pull/2
            """.trimIndent()
        )
        assertNotNull(out)
        assertTrue(out!!.contains("### New"), out)
        assertTrue(out.contains("### Fixed"), out)
    }

    /** Releases up to v2.31.2 open with the block #360 retired. Those bodies
     *  are published and will not change, so a rider crossing that boundary
     *  still meets them. */
    @Test
    fun theRetiredPreambleIsRemovedFromOlderReleases() {
        val out = ReleaseNotes.riderNotes(
            """
            Detour v2.31.2 (versionCode
            23102), built from
            6076e073. Also published to the Play internal testing track.

            Download the `.apk` below and install it on your phone (allow
            installs from unknown sources).

            This build ships with no server configured. Point it at your own
            under Settings → Routing server and Settings → Backup sync, or
            import a saved config under Settings → Server config file. See
            `backend/README.md` to host one.

            ## What's Changed
            ### Other
            * fix(build): derive versionCode by @maxke24 in https://github.com/x/y/pull/361
            """.trimIndent()
        )
        assertEquals("* fix(build): derive versionCode (#361)", out)
    }

    @Test
    fun aBodyThatIsOnlyScaffoldingYieldsNothing() {
        assertNull(ReleaseNotes.riderNotes("<!-- generated -->\n\n## What's Changed\n"))
        assertNull(ReleaseNotes.riderNotes(""))
        assertNull(ReleaseNotes.riderNotes(null))
    }

    // ---- releaseRangeNotes: the merge ------------------------------------

    private fun fiveReleases() = listOf(
        release("2.31.3", 363, "chore(release): retire the preamble"),
        release("2.31.2", 361, "fix(build): derive versionCode"),
        release("2.31.1", 356, "fix(data): announcement outranks the general address"),
        release("2.31.0", 350, "feat(server): announce routing and geocoder"),
        release("2.30.0", 288, "feat(map): navigating is driving"),
    )

    @Test
    fun everyReleaseNewerThanTheInstalledOneIsShownNewestFirst() {
        val out = ReleaseNotes.releaseRangeNotes(fiveReleases(), "2.30.0", "Detour-app/Detour")
        assertEquals(
            """
            ## 2.31.3
            * chore(release): retire the preamble (#363)

            ## 2.31.2
            * fix(build): derive versionCode (#361)

            ## 2.31.1
            * fix(data): announcement outranks the general address (#356)

            ## 2.31.0
            * feat(server): announce routing and geocoder (#350)
            """.trimIndent(),
            out,
        )
    }

    /** The installed release itself is not "new". */
    @Test
    fun theInstalledVersionIsNotIncluded() {
        val out = ReleaseNotes.releaseRangeNotes(fiveReleases(), "2.30.0", "r")
        assertFalse(out!!.contains("## 2.30.0"), out)
    }

    /** One release behind is what shipped in #295 — one section, no tail. */
    @Test
    fun oneReleaseBehindYieldsThatOneRelease() {
        val out = ReleaseNotes.releaseRangeNotes(fiveReleases(), "2.31.2", "r")
        assertEquals("## 2.31.3\n* chore(release): retire the preamble (#363)", out)
    }

    @Test
    fun anAlreadyCurrentRiderGetsNothing() {
        assertNull(ReleaseNotes.releaseRangeNotes(fiveReleases(), "2.31.3", "r"))
        assertNull(ReleaseNotes.releaseRangeNotes(fiveReleases(), "9.9.9", "r"))
        assertNull(ReleaseNotes.releaseRangeNotes(emptyList(), "1.0.0", "r"))
    }

    /** Pre-releases are skipped: `/releases/latest` does not offer them, so
     *  describing them would describe something the rider cannot install. */
    @Test
    fun aPrereleaseInTheRangeIsSkipped() {
        val withPre = listOf(
            release("2.32.0", 400, "feat: a beta", pre = true),
            release("2.31.3", 363, "chore(release): retire the preamble"),
        )
        val out = ReleaseNotes.releaseRangeNotes(withPre, "2.31.2", "r")
        assertNotNull(out)
        assertFalse(out!!.contains("2.32.0"), "a prerelease is not on offer: $out")
        assertTrue(out.contains("## 2.31.3"), out)
    }

    /** More than the cap: the cap's worth is shown and the rest becomes one
     *  link, rather than a second request. */
    @Test
    fun beyondTheCapTheRestBecomesACompareLink() {
        val many = (1..15).map { release("3.0.$it", it, "fix: change $it") }.reversed()
        val out = ReleaseNotes.releaseRangeNotes(many, "2.0.0", "Detour-app/Detour", cap = 3)
        assertNotNull(out)
        assertEquals(3, Regex("""^## """, RegexOption.MULTILINE).findAll(out!!).count(), out)
        assertTrue(out.contains("compare/v2.0.0...v3.0.15"), out)
    }

    /** The page ran out before the installed version did — there may be more
     *  than this one response can describe, so the link appears even under the
     *  cap. */
    @Test
    fun aFullPageOfNewerReleasesAlsoGetsTheLink() {
        val out = ReleaseNotes.releaseRangeNotes(fiveReleases(), "1.0.0", "Detour-app/Detour")
        assertNotNull(out)
        assertTrue(out!!.contains("compare/v1.0.0...v2.31.3"), out)
    }

    /** A release with no body is skipped rather than rendered as an empty
     *  version heading. */
    @Test
    fun aReleaseWithNoBodyIsSkipped() {
        // Carries a release older than the installed one too, so the page is
        // seen to reach back past it and this test is about the empty body
        // alone rather than also about the truncation link.
        val mixed = listOf(
            UpdateCheck.Release("2.31.3", false, emptyMap(), notes = null),
            release("2.31.2", 361, "fix(build): derive versionCode"),
            release("2.31.0", 350, "feat(server): announce routing and geocoder"),
        )
        val out = ReleaseNotes.releaseRangeNotes(mixed, "2.31.1", "r")
        assertEquals("## 2.31.2\n* fix(build): derive versionCode (#361)", out)
    }
}

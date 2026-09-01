package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers UpdateCheck.kt — the only part of the update feature anything can
 * test. The download and the install are Android plus OS dialogs; this is the
 * arithmetic and the parsing they depend on.
 */
class UpdateCheckTest {

    @Test
    fun aHigherPatchIsNewer() {
        assertTrue(UpdateCheck.isNewer(installed = "1.86.0", candidate = "1.86.1"))
    }

    /**
     * The reason this is not a string comparison: lexically "1.10.0" sorts
     * before "1.9.0", so a string compare stops offering updates at the tenth
     * minor of any major and nobody notices until it has been broken for weeks.
     */
    @Test
    fun tenIsNewerThanNine() {
        assertTrue(UpdateCheck.isNewer(installed = "1.9.0", candidate = "1.10.0"))
        assertFalse(UpdateCheck.isNewer(installed = "1.10.0", candidate = "1.9.0"))
    }

    @Test
    fun theSameVersionIsNotNewer() {
        assertFalse(UpdateCheck.isNewer(installed = "1.86.0", candidate = "1.86.0"))
    }

    @Test
    fun anOlderCandidateIsNotNewer() {
        assertFalse(UpdateCheck.isNewer(installed = "1.87.0", candidate = "1.86.9"))
    }

    /** A missing segment reads as zero, so 1.87 and 1.87.0 are the same build. */
    @Test
    fun aMissingPatchSegmentReadsAsZero() {
        assertFalse(UpdateCheck.isNewer(installed = "1.87.0", candidate = "1.87"))
        assertTrue(UpdateCheck.isNewer(installed = "1.87", candidate = "1.87.1"))
    }

    /**
     * Anything unparseable is "not newer", never "newer". The failure mode of
     * guessing wrong here is offering a rider a download that cannot install.
     */
    @Test
    fun aMalformedVersionIsNeverNewer() {
        assertFalse(UpdateCheck.isNewer(installed = "1.87.0", candidate = "nightly"))
        assertFalse(UpdateCheck.isNewer(installed = "1.87.0", candidate = ""))
        assertFalse(UpdateCheck.isNewer(installed = "garbage", candidate = "1.88.0"))
    }

    /** CI tags releases `v<versionName>`; the caller strips it, so this must
     *  not silently accept a tag that still carries the prefix. */
    @Test
    fun aTagPrefixIsNotStrippedHere() {
        assertFalse(UpdateCheck.isNewer(installed = "1.87.0", candidate = "v1.88.0"))
    }
}

package com.jellemax.detour.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

/** [avatarInitialOf]: the one derivation every avatar circle shares. */
class AvatarInitialTest {

    @Test fun firstLetterIsUppercased() {
        assertEquals("K", avatarInitialOf("kasper"))
    }

    @Test fun leadingSpaceIsTrimmedBeforeTakingTheFirstLetter() {
        assertEquals("K", avatarInitialOf(" kasper"))
    }

    @Test fun blankUsernameFallsBackToQuestionMark() {
        assertEquals("?", avatarInitialOf("   "))
    }

    @Test fun emptyUsernameFallsBackToQuestionMark() {
        assertEquals("?", avatarInitialOf(""))
    }
}

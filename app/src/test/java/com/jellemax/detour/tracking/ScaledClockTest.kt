package com.jellemax.detour.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ScaledClock]'s arithmetic, which is the whole of [ReplayClock] that can be
 * tested without Android: `setScale`'s clamp and its `BuildConfig.DEBUG` gate
 * are platform reads, and the anchoring below is what would actually be wrong
 * if a replay recorded a bogus duration.
 */
class ScaledClockTest {

    @Test
    fun `real time reads back the wall clock unchanged`() {
        val c = ScaledClock.real()
        assertEquals(0L, c.at(0L))
        assertEquals(1_757_000_000_000L, c.at(1_757_000_000_000L))
        assertEquals(1, c.scale)
    }

    @Test
    fun `a scaled clock advances by the factor per wall millisecond`() {
        val c = ScaledClock.real().rescaled(wallMs = 1_000L, scale = 5)
        // One wall second after the rescale is five drive-seconds.
        assertEquals(6_000L, c.at(2_000L))
        // A 24-minute route replayed in 4 minutes 48 reads as the 24 minutes.
        assertEquals(1_000L + 24 * 60_000L, c.at(1_000L + 288_000L))
    }

    @Test
    fun `the reading does not jump at the instant of a rescale`() {
        val c = ScaledClock.real().rescaled(wallMs = 1_000L, scale = 5)
        assertEquals("rescaling must be continuous", 1_000L, c.at(1_000L))
    }

    @Test
    fun `dropping back to real time keeps the drive time already accumulated`() {
        // 5x for two wall seconds — ten drive-seconds — then back to 1x.
        val fast = ScaledClock.real().rescaled(wallMs = 1_000L, scale = 5)
        val back = fast.rescaled(wallMs = 3_000L, scale = 1)
        assertEquals(11_000L, back.at(3_000L))
        // and from there it ticks once per wall millisecond again
        assertEquals(12_000L, back.at(4_000L))
        assertEquals(1, back.scale)
    }

    @Test
    fun `re-anchoring at the same factor changes nothing observable`() {
        val once = ScaledClock.real().rescaled(wallMs = 1_000L, scale = 5)
        val twice = once.rescaled(wallMs = 2_000L, scale = 5)
        // This is the case ReplayClock.setScale returns early for; if it ever
        // stopped doing that, the readings still have to agree.
        assertEquals(once.at(5_000L), twice.at(5_000L))
    }
}

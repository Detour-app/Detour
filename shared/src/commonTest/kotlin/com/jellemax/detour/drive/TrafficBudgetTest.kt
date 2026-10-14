package com.jellemax.detour.drive

import kotlin.test.Test
import kotlin.test.assertEquals

class TrafficBudgetTest {
    private val jan = 1_736_000_000_000L // 2025-01-04
    private val feb = 1_739_000_000_000L // 2025-02-08

    @Test
    fun capsAtFreeTierThenResetsNextMonth() {
        val full = TrafficBudget.Usage(TrafficBudget.monthOf(jan), TrafficBudget.FREE_TIER - 2)
        val (g1, u1) = TrafficBudget.grant(full, 4, jan, unlimited = false)
        assertEquals(2, g1)
        assertEquals(0, TrafficBudget.grant(u1, 4, jan, unlimited = false).first)
        assertEquals(4, TrafficBudget.grant(u1, 4, feb, unlimited = false).first)
    }

    @Test
    fun unlimitedGrantsAllAndStillCounts() {
        val (g, u) = TrafficBudget.grant(TrafficBudget.Usage(TrafficBudget.monthOf(jan), TrafficBudget.FREE_TIER), 4, jan, unlimited = true)
        assertEquals(4, g)
        assertEquals(TrafficBudget.FREE_TIER + 4, u.count)
    }
}

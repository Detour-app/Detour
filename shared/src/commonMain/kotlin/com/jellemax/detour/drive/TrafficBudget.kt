package com.jellemax.detour.drive

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The rider's monthly HERE request budget (#637): HERE bills per transaction
 * past a free allocation, and the key is the rider's, so by default the app
 * stops asking when that allocation is spent. A rider who accepts the bill
 * turns the cap off in Settings.
 *
 * Pure: [grant] maps one [Usage] to the next, Settings persists it.
 */
object TrafficBudget {

    /** HERE's free Traffic transactions per calendar month (unverified against
     *  a live account; one place to change if it moves). */
    const val FREE_TIER = 5_000

    /** Requests made in [month] (`year * 12 + month0`, UTC). */
    data class Usage(val month: Int = 0, val count: Int = 0)

    fun monthOf(nowMs: Long): Int {
        val d = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(TimeZone.UTC)
        return d.year * 12 + (d.monthNumber - 1)
    }

    /**
     * How many of [wanted] requests may go out at [nowMs], and the usage after
     * them. A new month starts from zero. [unlimited] grants all and still
     * counts, so the settings line stays honest.
     */
    fun grant(usage: Usage, wanted: Int, nowMs: Long, unlimited: Boolean): Pair<Int, Usage> {
        val month = monthOf(nowMs)
        val used = if (usage.month == month) usage.count else 0
        val granted = if (unlimited) wanted else wanted.coerceAtMost((FREE_TIER - used).coerceAtLeast(0))
        return granted to Usage(month, used + granted)
    }
}

package com.jellemax.detour.data

/**
 * How fast each stretch of a recorded route was ridden, as a few colour steps
 * from slow to fast (#834) — the Logbook card and trip detail draw a route
 * coloured along its length from this.
 *
 * Speed comes from each hop's own distance ÷ its own time, never from the
 * stored `speedKmh`: the trace is decimated to 25 m, so a stored speed is the
 * speed at the far end of a hop, and a standstill is one long hop rather than
 * a run of slow points. A long hop over a short distance therefore reads as
 * the slowest step, which is what it was.
 *
 * Each hop is read over the hops whose middles lie within [WINDOW_HALF_MS] of
 * its own: a 25 m hop takes one or two seconds at road speed, so one hop's
 * speed swings with each fix's timing and would paint the line in flecks. The
 * window is time, not hops, so a long stop stays one slow hop and doesn't slow
 * the stretches either side of it.
 */
object RouteSpeed {

    /** Colour steps, 0 = slowest. */
    const val BUCKETS = 5

    /** Hops faster or slower than this share of the ride set the ends of the
     *  scale, so one GPS jump or one stop doesn't squash the rest into the middle. */
    private const val TAIL_SHARE = 0.1

    /** The narrowest scale (m/s, 30 km/h), centred on the ride's own middle: a
     *  ride ridden at one pace stays one or two middle steps rather than its
     *  last few km/h of wobble being stretched into the full teal-to-amber range. */
    private const val MIN_SPAN_MPS = 30 / 3.6

    /** Half of the time each hop's speed is read over. */
    private const val WINDOW_HALF_MS = 10_000L

    /**
     * One step per hop of [points] (`points.size - 1` of them), scaled to the
     * ride's own range. Null when there's nothing to colour by: fewer than two
     * points, or any point written before points carried a time — the route is
     * then drawn in a single ink colour. A hop whose window holds no time at
     * all takes its neighbour's step.
     */
    fun buckets(points: List<TraceStore.TracePoint>): List<Int>? {
        if (points.size < 2 || points.any { it.timeMs < 0 }) return null
        val speeds = windowSpeeds(points)
        val known = speeds.filterNot { it.isNaN() }.sorted()
        if (known.isEmpty()) return null
        val low = known[(known.size * TAIL_SHARE).toInt()]
        val high = known[((known.size - 1) * (1 - TAIL_SHARE)).toInt()]
        val span = maxOf(high - low, MIN_SPAN_MPS)
        val floor = (low + high - span) / 2
        val steps = speeds.map { s ->
            if (s.isNaN()) null else ((s - floor) / span * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
        }
        var last = steps.firstNotNullOf { it }
        return steps.map { s -> (s ?: last).also { last = it } }
    }

    /** Each hop's speed (m/s) over its time window, NaN where that window has no time. */
    private fun windowSpeeds(points: List<TraceStore.TracePoint>): List<Double> {
        val hops = points.size - 1
        val metersBefore = DoubleArray(points.size)
        for (i in 1 until points.size) {
            metersBefore[i] = metersBefore[i - 1] + RoadRoulette.distanceMeters(points[i - 1].at, points[i].at)
        }
        fun middle(hop: Int) = (points[hop].timeMs + points[hop + 1].timeMs) / 2
        var from = 0
        var to = 0
        return (0 until hops).map { i ->
            while (from < i && middle(from) < middle(i) - WINDOW_HALF_MS) from++
            to = maxOf(to, i)
            while (to + 1 < hops && middle(to + 1) <= middle(i) + WINDOW_HALF_MS) to++
            val ms = points[to + 1].timeMs - points[from].timeMs
            if (ms <= 0) Double.NaN else (metersBefore[to + 1] - metersBefore[from]) / (ms / 1000.0)
        }
    }

    /** Points [from]..[to] (inclusive) of a route, every hop between them at [step]. */
    data class Run(val step: Int, val from: Int, val to: Int)

    /** [steps] (one per hop, from [buckets]) folded into runs of one step, so a
     *  route is drawn as a handful of lines rather than one per 25 m hop. */
    fun runs(steps: List<Int>): List<Run> {
        val runs = ArrayList<Run>()
        var start = 0
        for (i in 1..steps.size) {
            if (i == steps.size || steps[i] != steps[start]) {
                runs += Run(steps[start], start, i)
                start = i
            }
        }
        return runs
    }
}

package com.ebike.router.service.elevation

import kotlin.math.roundToInt

/**
 * Cleans up noisy raw DEM/API elevation samples (typical vertical error of
 * +/-2 to 5m) so that flat roads don't register as false climbing and
 * grades stay physically plausible.
 */
object ElevationSmoother {
    private val WINDOW_WEIGHTS = doubleArrayOf(0.1, 0.2, 0.4, 0.2, 0.1)
    private const val GAIN_LOSS_THRESHOLD_M = 1.5
    private const val MAX_GRADE_PERCENT = 25.0

    /** 5-point weighted moving average low-pass filter. */
    fun smooth(elevations: List<Double>): List<Double> {
        if (elevations.size < 3) return elevations
        return elevations.indices.map { i ->
            var sum = 0.0
            var weightSum = 0.0
            for (w in WINDOW_WEIGHTS.indices) {
                val offset = w - 2
                val idx = i + offset
                if (idx in elevations.indices) {
                    val weight = WINDOW_WEIGHTS[w]
                    sum += elevations[idx] * weight
                    weightSum += weight
                }
            }
            if (weightSum > 0.0) sum / weightSum else elevations[i]
        }
    }

    /**
     * Accumulates gain/loss with hysteresis: only counts a vertical move
     * once it exceeds [GAIN_LOSS_THRESHOLD_M] from the last reference point,
     * so DEM micro-ripples on flat ground don't inflate cumulative ascent.
     */
    fun computeGainLoss(smoothed: List<Double>): Pair<Int, Int> {
        if (smoothed.size < 2) return 0 to 0
        var gain = 0.0
        var loss = 0.0
        var reference = smoothed[0]
        for (i in 1 until smoothed.size) {
            val delta = smoothed[i] - reference
            when {
                delta >= GAIN_LOSS_THRESHOLD_M -> {
                    gain += delta
                    reference = smoothed[i]
                }
                delta <= -GAIN_LOSS_THRESHOLD_M -> {
                    loss += -delta
                    reference = smoothed[i]
                }
            }
        }
        return gain.roundToInt() to loss.roundToInt()
    }

    /** Grade across a segment, clamped to realistic urban-cycling road limits. */
    fun gradePercent(eleDiffM: Double, distanceMeters: Double): Double {
        if (distanceMeters <= 0.0) return 0.0
        val raw = (eleDiffM / distanceMeters) * 100.0
        val clamped = raw.coerceIn(-MAX_GRADE_PERCENT, MAX_GRADE_PERCENT)
        return (clamped * 10).roundToInt() / 10.0
    }
}

package com.ebike.router.service.elevation

import com.ebike.router.model.GeoPoint

/**
 * Builds a real elevation profile for a route polyline: downsamples at
 * ~35m intervals (always preserving the given anchor indices, e.g. turn
 * boundaries), queries the [ElevationProvider] for ground elevation,
 * smooths the result, and exposes lookup by cumulative distance so callers
 * can derive real grades for arbitrary sub-segments of the route.
 */
class ElevationSampler private constructor(
    private val cumulativeDistancesM: List<Double>,
    private val smoothedElevations: List<Double>
) {
    val elevationGainM: Int
    val elevationLossM: Int

    init {
        val (gain, loss) = ElevationSmoother.computeGainLoss(smoothedElevations)
        elevationGainM = gain
        elevationLossM = loss
    }

    /** Linear interpolation of ground elevation at [distanceM] along the route. */
    fun elevationAt(distanceM: Double): Double {
        if (smoothedElevations.isEmpty()) return 0.0
        if (distanceM <= cumulativeDistancesM.first()) return smoothedElevations.first()
        if (distanceM >= cumulativeDistancesM.last()) return smoothedElevations.last()

        var lo = 0
        var hi = cumulativeDistancesM.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (cumulativeDistancesM[mid] <= distanceM) lo = mid else hi = mid
        }
        val d0 = cumulativeDistancesM[lo]
        val d1 = cumulativeDistancesM[hi]
        val e0 = smoothedElevations[lo]
        val e1 = smoothedElevations[hi]
        if (d1 <= d0) return e0
        val t = (distanceM - d0) / (d1 - d0)
        return e0 + (e1 - e0) * t
    }

    companion object {
        private const val SAMPLE_INTERVAL_M = 35.0

        suspend fun build(
            routeCoords: List<GeoPoint>,
            anchorIndices: Set<Int>,
            provider: ElevationProvider
        ): ElevationSampler {
            if (routeCoords.size < 2) {
                val single = routeCoords.firstOrNull()?.ele ?: 0.0
                return ElevationSampler(listOf(0.0), listOf(single))
            }

            val cumDist = DoubleArray(routeCoords.size)
            for (i in 1 until routeCoords.size) {
                cumDist[i] = cumDist[i - 1] + routeCoords[i - 1].distanceTo(routeCoords[i])
            }

            val sampleIndices = sortedSetOf(0, routeCoords.size - 1)
            sampleIndices.addAll(anchorIndices.filter { it in routeCoords.indices })
            var nextTarget = SAMPLE_INTERVAL_M
            for (i in routeCoords.indices) {
                if (cumDist[i] >= nextTarget) {
                    sampleIndices.add(i)
                    nextTarget += SAMPLE_INTERVAL_M
                }
            }

            val orderedIndices = sampleIndices.sorted()
            val samplePoints = orderedIndices.map { routeCoords[it] }
            val sampleDistances = orderedIndices.map { cumDist[it] }

            val rawElevations = provider.getElevations(samplePoints)
            val filledElevations = fillGaps(rawElevations, samplePoints)
            val smoothed = ElevationSmoother.smooth(filledElevations)

            return ElevationSampler(sampleDistances, smoothed)
        }

        /** Fills missing (null) elevations via nearest-known-neighbor fill. */
        private fun fillGaps(raw: List<Double?>, points: List<GeoPoint>): List<Double> {
            if (raw.all { it == null }) return points.map { it.ele }

            val result = raw.toMutableList()
            var lastKnown: Double? = null
            for (i in result.indices) {
                if (result[i] != null) lastKnown = result[i] else if (lastKnown != null) result[i] = lastKnown
            }
            var nextKnown: Double? = null
            for (i in result.indices.reversed()) {
                if (result[i] != null) nextKnown = result[i] else if (nextKnown != null) result[i] = nextKnown
            }
            return result.map { it ?: 0.0 }
        }
    }
}

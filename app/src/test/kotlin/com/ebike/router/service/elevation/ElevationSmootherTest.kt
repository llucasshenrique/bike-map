package com.ebike.router.service.elevation

import org.junit.Assert.*
import org.junit.Test

class ElevationSmootherTest {

    @Test
    fun testSmoothReturnsSameListWhenFewerThanThreePoints() {
        val input = listOf(10.0, 20.0)
        assertEquals(input, ElevationSmoother.smooth(input))
    }

    @Test
    fun testSmoothPreservesConstantProfile() {
        val input = List(9) { 50.0 }
        val smoothed = ElevationSmoother.smooth(input)
        smoothed.forEach { assertEquals(50.0, it, 0.0001) }
    }

    @Test
    fun testSmoothDampensSingleSpike() {
        // Flat profile with one 10m spike in the middle - the low-pass filter
        // should pull the peak down well below the raw spike value.
        val input = listOf(0.0, 0.0, 0.0, 10.0, 0.0, 0.0, 0.0)
        val smoothed = ElevationSmoother.smooth(input)
        assertTrue("Spike should be dampened below raw value", smoothed[3] < 10.0)
        assertTrue("Spike should still be the local max", smoothed[3] > smoothed[2])
        assertTrue("Spike should still be the local max", smoothed[3] > smoothed[4])
    }

    @Test
    fun testComputeGainLossOnEmptyOrSinglePoint() {
        assertEquals(0 to 0, ElevationSmoother.computeGainLoss(emptyList()))
        assertEquals(0 to 0, ElevationSmoother.computeGainLoss(listOf(100.0)))
    }

    @Test
    fun testComputeGainLossIgnoresNoiseBelowThreshold() {
        // Micro-ripples of +/-1m (below the 1.5m hysteresis threshold) on an
        // otherwise flat profile must not accumulate any gain or loss.
        val flatWithNoise = listOf(100.0, 100.9, 100.2, 100.8, 100.1)
        val (gain, loss) = ElevationSmoother.computeGainLoss(flatWithNoise)
        assertEquals(0, gain)
        assertEquals(0, loss)
    }

    @Test
    fun testComputeGainLossAccumulatesRealClimbAndDescent() {
        val profile = listOf(0.0, 10.0, 5.0)
        val (gain, loss) = ElevationSmoother.computeGainLoss(profile)
        assertEquals(10, gain)
        assertEquals(5, loss)
    }

    @Test
    fun testGradePercentComputesRatio() {
        assertEquals(5.0, ElevationSmoother.gradePercent(5.0, 100.0), 0.001)
        assertEquals(-2.5, ElevationSmoother.gradePercent(-5.0, 200.0), 0.001)
    }

    @Test
    fun testGradePercentClampsToPhysicalLimits() {
        assertEquals(25.0, ElevationSmoother.gradePercent(1000.0, 100.0), 0.001)
        assertEquals(-25.0, ElevationSmoother.gradePercent(-1000.0, 100.0), 0.001)
    }

    @Test
    fun testGradePercentZeroDistanceReturnsZero() {
        assertEquals(0.0, ElevationSmoother.gradePercent(50.0, 0.0), 0.001)
    }
}

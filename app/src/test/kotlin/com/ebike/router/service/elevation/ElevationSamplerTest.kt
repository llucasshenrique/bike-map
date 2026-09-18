package com.ebike.router.service.elevation

import com.ebike.router.model.GeoPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ElevationSamplerTest {

    /** Deterministic fake: elevation equals the fraction of `maxEle` implied by latitude. */
    private class LinearFakeProvider(private val maxEle: Double, private val maxLat: Double) : ElevationProvider {
        override suspend fun getElevations(points: List<GeoPoint>): List<Double?> {
            return points.map { (it.lat / maxLat) * maxEle }
        }
    }

    private fun straightRoute(count: Int, latStep: Double): List<GeoPoint> {
        return (0 until count).map { GeoPoint(lat = it * latStep, lng = 0.0) }
    }

    @Test
    fun testSingleOrEmptyRouteReturnsAnchorElevation() = runBlocking {
        val single = ElevationSampler.build(
            routeCoords = listOf(GeoPoint(lat = 1.0, lng = 1.0, ele = 42.0)),
            anchorIndices = emptySet(),
            provider = LinearFakeProvider(100.0, 1.0)
        )
        assertEquals(42.0, single.elevationAt(0.0), 0.001)
        assertEquals(0, single.elevationGainM)
    }

    @Test
    fun testClimbingRouteAccumulatesGainAndNoLoss() = runBlocking {
        // ~20 points spaced ~16.7m apart along a straight line rising to 100m.
        val route = straightRoute(count = 20, latStep = 0.00015)
        val maxLat = route.last().lat
        val sampler = ElevationSampler.build(route, emptySet(), LinearFakeProvider(100.0, maxLat))

        assertTrue("Monotonic climb should register meaningful gain", sampler.elevationGainM in 80..100)
        assertEquals("Monotonic climb should not register any loss", 0, sampler.elevationLossM)
    }

    @Test
    fun testElevationAtInterpolatesAlongRoute() = runBlocking {
        val route = straightRoute(count = 20, latStep = 0.00015)
        val maxLat = route.last().lat
        val sampler = ElevationSampler.build(route, emptySet(), LinearFakeProvider(100.0, maxLat))

        val totalDistance = (0 until route.size - 1).sumOf { route[it].distanceTo(route[it + 1]) }

        assertEquals(0.0, sampler.elevationAt(0.0), 10.0)
        assertEquals(100.0, sampler.elevationAt(totalDistance), 10.0)
        // Midpoint should be roughly half the total climb, allowing for smoothing slack.
        assertEquals(50.0, sampler.elevationAt(totalDistance / 2.0), 15.0)
    }

    @Test
    fun testElevationAtClampsOutsideRouteBounds() = runBlocking {
        val route = straightRoute(count = 10, latStep = 0.0002)
        val maxLat = route.last().lat
        val sampler = ElevationSampler.build(route, emptySet(), LinearFakeProvider(100.0, maxLat))

        assertEquals(sampler.elevationAt(0.0), sampler.elevationAt(-500.0), 0.0001)
        assertEquals(sampler.elevationAt(1_000_000.0), sampler.elevationAt(2_000_000.0), 0.0001)
    }

    @Test
    fun testMissingElevationsFallBackToNearestKnownNeighbor() = runBlocking {
        val route = straightRoute(count = 10, latStep = 0.0002)
        val allNullProvider = object : ElevationProvider {
            override suspend fun getElevations(points: List<GeoPoint>): List<Double?> =
                points.map { null }
        }
        val sampler = ElevationSampler.build(route, emptySet(), allNullProvider)
        // Falls back to each point's default GeoPoint.ele (20.0) rather than crashing.
        assertEquals(20.0, sampler.elevationAt(0.0), 0.001)
    }
}

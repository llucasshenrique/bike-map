package com.ebike.router.navigation

import com.ebike.router.model.GeoPoint
import org.junit.Assert.*
import org.junit.Test

class RouteDeviationTest {

    // A straight north-south route along a fixed longitude, ~111m per 0.001 deg latitude.
    private val straightRoute = listOf(
        GeoPoint(lat = 0.0000, lng = 0.0000),
        GeoPoint(lat = 0.0010, lng = 0.0000),
        GeoPoint(lat = 0.0020, lng = 0.0000),
        GeoPoint(lat = 0.0030, lng = 0.0000)
    )

    @Test
    fun `point exactly on the route has zero distance`() {
        val onRoute = GeoPoint(lat = 0.0015, lng = 0.0000)
        val distance = RouteDeviation.distanceToRoute(onRoute, straightRoute)
        assertEquals(0.0, distance, 0.5)
    }

    @Test
    fun `point near the route returns a small distance under threshold`() {
        // ~0.0001 deg longitude offset at the equator is roughly 11 meters.
        val nearRoute = GeoPoint(lat = 0.0015, lng = 0.0001)
        val distance = RouteDeviation.distanceToRoute(nearRoute, straightRoute)
        assertTrue("Expected a small deviation, got $distance", distance in 5.0..20.0)
    }

    @Test
    fun `point far from the route returns a large distance`() {
        // ~0.001 deg longitude offset at the equator is roughly 111 meters.
        val farFromRoute = GeoPoint(lat = 0.0015, lng = 0.0010)
        val distance = RouteDeviation.distanceToRoute(farFromRoute, straightRoute)
        assertTrue("Expected a large deviation, got $distance", distance > 90.0)
    }

    @Test
    fun `closest point clamps to segment endpoint beyond the route`() {
        // Well past the last coordinate; nearest point should clamp to the route's end.
        val beyondEnd = GeoPoint(lat = 0.0100, lng = 0.0000)
        val expectedDistanceToEnd = beyondEnd.distanceTo(straightRoute.last())
        val distance = RouteDeviation.distanceToRoute(beyondEnd, straightRoute)
        assertEquals(expectedDistanceToEnd, distance, 0.5)
    }

    @Test
    fun `findNearestPointOnRoute reports the matching segment index`() {
        val nearSecondSegment = GeoPoint(lat = 0.0015, lng = 0.0000)
        val result = RouteDeviation.findNearestPointOnRoute(nearSecondSegment, straightRoute)
        assertNotNull(result)
        assertEquals(1, result!!.segmentIndex)
    }

    @Test
    fun `windowed search finds nearby segment without scanning whole route`() {
        val longRoute = (0..200).map { i -> GeoPoint(lat = i * 0.0001, lng = 0.0000) }
        val point = GeoPoint(lat = 0.0100, lng = 0.0000) // near index 100

        val result = RouteDeviation.findNearestPointOnRoute(
            point = point,
            routeCoordinates = longRoute,
            searchAroundIndex = 100,
            windowSize = 5
        )

        assertNotNull(result)
        assertEquals(0.0, result!!.distanceMeters, 0.5)
        assertTrue("Segment index should be within the search window", kotlin.math.abs(result.segmentIndex - 100) <= 5)
    }

    @Test
    fun `windowed search falls back to full scan when nothing nearby in window`() {
        val longRoute = (0..200).map { i -> GeoPoint(lat = i * 0.0001, lng = 0.0000) }
        val point = GeoPoint(lat = 0.0000, lng = 0.0000) // actually near index 0

        // Search window centered far away from the true match; should fall back to a full scan.
        val result = RouteDeviation.findNearestPointOnRoute(
            point = point,
            routeCoordinates = longRoute,
            searchAroundIndex = 150,
            windowSize = 5
        )

        assertNotNull(result)
        assertEquals(0.0, result!!.distanceMeters, 0.5)
        assertEquals(0, result.segmentIndex)
    }

    @Test
    fun `returns null for a route with fewer than two coordinates`() {
        val singlePointRoute = listOf(GeoPoint(lat = 0.0, lng = 0.0))
        assertNull(RouteDeviation.findNearestPointOnRoute(GeoPoint(lat = 1.0, lng = 1.0), singlePointRoute))

        val emptyRoute = emptyList<GeoPoint>()
        assertNull(RouteDeviation.findNearestPointOnRoute(GeoPoint(lat = 1.0, lng = 1.0), emptyRoute))
    }

    @Test
    fun `distanceToRoute returns MAX_VALUE when no nearest point can be found`() {
        val emptyRoute = emptyList<GeoPoint>()
        val distance = RouteDeviation.distanceToRoute(GeoPoint(lat = 1.0, lng = 1.0), emptyRoute)
        assertEquals(Double.MAX_VALUE, distance, 0.0)
    }

    @Test
    fun `degenerate zero-length segment does not throw and returns endpoint distance`() {
        val degenerateRoute = listOf(
            GeoPoint(lat = 0.0, lng = 0.0),
            GeoPoint(lat = 0.0, lng = 0.0),
            GeoPoint(lat = 0.0020, lng = 0.0000)
        )
        val point = GeoPoint(lat = 0.0005, lng = 0.0001)
        val distance = RouteDeviation.distanceToRoute(point, degenerateRoute)
        assertTrue(distance.isFinite())
        assertTrue(distance > 0.0)
    }

    @Test
    fun `closest point clamps to segment endpoint before the route start`() {
        // South of the origin; nearest point should clamp to the first coordinate.
        val beforeStart = GeoPoint(lat = -0.0100, lng = 0.0000)
        val expectedDistanceToStart = beforeStart.distanceTo(straightRoute.first())
        val distance = RouteDeviation.distanceToRoute(beforeStart, straightRoute)
        assertEquals(expectedDistanceToStart, distance, 0.5)
    }

    @Test
    fun `L-shaped route correctly identifies closest point on perpendicular segment`() {
        val lRoute = listOf(
            GeoPoint(lat = 0.0, lng = 0.0),
            GeoPoint(lat = 0.0010, lng = 0.0), // segment 0: north
            GeoPoint(lat = 0.0010, lng = 0.0010) // segment 1: east
        )
        // Point near the east-running segment
        val nearEast = GeoPoint(lat = 0.00105, lng = 0.0005)
        val result = RouteDeviation.findNearestPointOnRoute(nearEast, lRoute)
        assertNotNull(result)
        assertEquals(1, result!!.segmentIndex)
        assertTrue(result.distanceMeters < 10.0)
    }

    @Test
    fun `custom fallbackThresholdMeters is respected`() {
        val longRoute = (0..200).map { i -> GeoPoint(lat = i * 0.0001, lng = 0.0000) }
        // Point is ~111m away (lat offset 0.0010 from index 100)
        val point = GeoPoint(lat = 0.0110, lng = 0.0000)

        // With fallback threshold of 50m, 111m exceeds threshold so it falls back to full scan and finds exact segment ~index 110
        val fallbackResult = RouteDeviation.findNearestPointOnRoute(
            point = point,
            routeCoordinates = longRoute,
            searchAroundIndex = 100,
            windowSize = 2, // window is [98..102], point is at 110
            fallbackThresholdMeters = 50.0
        )
        assertNotNull(fallbackResult)
        assertTrue(fallbackResult!!.segmentIndex in 109..110)
        assertEquals(0.0, fallbackResult.distanceMeters, 0.5)
    }
}

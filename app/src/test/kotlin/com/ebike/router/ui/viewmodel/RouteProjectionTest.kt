package com.ebike.router.ui.viewmodel

import com.ebike.router.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Independent review of BikeMapViewModel.projectPointOntoRoute, added in commit
 * bff4f01 (real GPS-projected ETA). Written by a different agent than the one
 * that implemented the feature.
 */
class RouteProjectionTest {

    private val straightLine = listOf(
        GeoPoint(lat = 0.0, lng = 0.0),
        GeoPoint(lat = 0.0, lng = 0.001),
        GeoPoint(lat = 0.0, lng = 0.002),
        GeoPoint(lat = 0.0, lng = 0.003)
    )

    @Test
    fun `rider exactly at route start reports full remaining distance`() {
        val fullLength = polylineLength(straightLine)

        val result = BikeMapViewModel.projectPointOntoRoute(straightLine, straightLine.first())

        assertEquals(fullLength, result.remainingDistanceMeters, 0.5)
    }

    @Test
    fun `rider exactly at route end reports zero remaining distance`() {
        val result = BikeMapViewModel.projectPointOntoRoute(straightLine, straightLine.last())

        assertEquals(0.0, result.remainingDistanceMeters, 0.5)
    }

    @Test
    fun `rider at the midpoint of a segment reports half that segment plus the rest`() {
        val midOfFirstSegment = GeoPoint(lat = 0.0, lng = 0.0005)
        val fullLength = polylineLength(straightLine)
        val firstSegmentLength = straightLine[0].distanceTo(straightLine[1])

        val result = BikeMapViewModel.projectPointOntoRoute(straightLine, midOfFirstSegment)

        val expectedRemaining = fullLength - firstSegmentLength / 2.0
        assertEquals(expectedRemaining, result.remainingDistanceMeters, 1.0)
    }

    @Test
    fun `rider off to the side of a segment snaps perpendicular, not to the nearest vertex`() {
        // A point abeam the middle of the second segment, off the polyline entirely.
        val offToTheSide = GeoPoint(lat = 0.0005, lng = 0.0015)

        val result = BikeMapViewModel.projectPointOntoRoute(straightLine, offToTheSide)

        // Snapped point must land ON the polyline (lat ~ 0.0), not at either endpoint's lat.
        assertEquals(0.0, result.snappedPoint.lat, 0.0001)
        assertTrue(
            "snapped longitude should be between the segment endpoints",
            result.snappedPoint.lng in 0.001..0.002
        )
    }

    @Test
    fun `off-route deviation still finds the closest segment, not just the first one`() {
        // Closest to the third segment (0.002 -> 0.003), even though it comes later in the list.
        val nearThirdSegment = GeoPoint(lat = 0.00001, lng = 0.0025)
        val fullLength = polylineLength(straightLine)
        val thirdSegmentLength = straightLine[2].distanceTo(straightLine[3])

        val result = BikeMapViewModel.projectPointOntoRoute(straightLine, nearThirdSegment)

        // Should report roughly half of the last segment remaining, not the whole route.
        val expectedRemaining = thirdSegmentLength / 2.0
        assertTrue(
            "remaining distance ($expectedRemaining expected) should be much less than the full route ($fullLength)",
            result.remainingDistanceMeters < fullLength / 2
        )
    }

    @Test
    fun `fewer than two coordinates returns zero remaining distance without crashing`() {
        val single = listOf(GeoPoint(lat = 1.0, lng = 1.0))

        val result = BikeMapViewModel.projectPointOntoRoute(single, GeoPoint(lat = 5.0, lng = 5.0))

        assertEquals(0.0, result.remainingDistanceMeters, 0.0)
        assertEquals(single.first(), result.snappedPoint)
    }

    @Test
    fun `empty coordinates returns zero remaining distance using the query point itself`() {
        val queryPoint = GeoPoint(lat = 2.0, lng = 2.0)

        val result = BikeMapViewModel.projectPointOntoRoute(emptyList(), queryPoint)

        assertEquals(0.0, result.remainingDistanceMeters, 0.0)
        assertEquals(queryPoint, result.snappedPoint)
    }

    private fun polylineLength(points: List<GeoPoint>): Double =
        points.zipWithNext { a, b -> a.distanceTo(b) }.sum()
}

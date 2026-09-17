package com.ebike.router.navigation

import com.ebike.router.model.GeoPoint
import kotlin.math.cos

/**
 * Pure, Android-free helpers for detecting how far a rider has strayed from a route polyline.
 */
object RouteDeviation {

    data class NearestPointResult(
        val distanceMeters: Double,
        val segmentIndex: Int
    )

    /**
     * Finds the closest point on the route polyline to [point], searching a window around
     * [searchAroundIndex] first (cheap for the common case of steady progress along the route)
     * and falling back to a full scan when nothing nearby is found (GPS jumps, desynced index).
     */
    fun findNearestPointOnRoute(
        point: GeoPoint,
        routeCoordinates: List<GeoPoint>,
        searchAroundIndex: Int? = null,
        windowSize: Int = 30
    ): NearestPointResult? {
        if (routeCoordinates.size < 2) return null

        val windowed = searchAroundIndex?.let { center ->
            val from = (center - windowSize).coerceAtLeast(0)
            val to = (center + windowSize).coerceAtMost(routeCoordinates.size - 2)
            if (from <= to) scanSegments(point, routeCoordinates, from, to) else null
        }

        if (windowed != null) return windowed

        return scanSegments(point, routeCoordinates, 0, routeCoordinates.size - 2)
    }

    /** Convenience wrapper returning just the deviation distance in meters. */
    fun distanceToRoute(
        point: GeoPoint,
        routeCoordinates: List<GeoPoint>,
        searchAroundIndex: Int? = null,
        windowSize: Int = 30
    ): Double {
        return findNearestPointOnRoute(point, routeCoordinates, searchAroundIndex, windowSize)
            ?.distanceMeters ?: Double.MAX_VALUE
    }

    private fun scanSegments(
        point: GeoPoint,
        routeCoordinates: List<GeoPoint>,
        fromIndex: Int,
        toIndex: Int
    ): NearestPointResult? {
        var bestDist = Double.MAX_VALUE
        var bestIndex = -1

        for (i in fromIndex..toIndex) {
            val a = routeCoordinates[i]
            val b = routeCoordinates[i + 1]
            val closest = closestPointOnSegment(point, a, b)
            val dist = point.distanceTo(closest)
            if (dist < bestDist) {
                bestDist = dist
                bestIndex = i
            }
        }

        return if (bestIndex >= 0) NearestPointResult(bestDist, bestIndex) else null
    }

    /**
     * Projects [point] onto segment [a]-[b] using a local equirectangular (flat-earth)
     * approximation, which is accurate to well under a meter for the short segment lengths
     * found in bike routes. The projection parameter is clamped to [0, 1] so the result stays
     * within the segment rather than the infinite line through it.
     */
    private fun closestPointOnSegment(point: GeoPoint, a: GeoPoint, b: GeoPoint): GeoPoint {
        val latRad = Math.toRadians(a.lat)
        val lngScale = cos(latRad)

        val ax = a.lng * lngScale
        val ay = a.lat
        val bx = b.lng * lngScale
        val by = b.lat
        val px = point.lng * lngScale
        val py = point.lat

        val dx = bx - ax
        val dy = by - ay
        val lengthSq = dx * dx + dy * dy

        val t = if (lengthSq == 0.0) {
            0.0
        } else {
            (((px - ax) * dx + (py - ay) * dy) / lengthSq).coerceIn(0.0, 1.0)
        }

        val closestX = ax + t * dx
        val closestY = ay + t * dy

        return GeoPoint(lat = closestY, lng = closestX / lngScale, ele = a.ele)
    }
}

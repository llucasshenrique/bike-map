package com.ebike.router

import com.ebike.router.model.GeoPoint
import com.ebike.router.service.OfflineTileCacheService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OfflineTileCacheServiceTest {
    private lateinit var service: OfflineTileCacheService

    @Before
    fun setUp() {
        service = OfflineTileCacheService()
    }

    @Test
    fun testBoundingBoxReturnsNullForFewerThanTwoPoints() {
        assertNull(service.boundingBoxForRoute(emptyList()))
        assertNull(service.boundingBoxForRoute(listOf(GeoPoint(lat = -23.55, lng = -46.63))))
    }

    @Test
    fun testBoundingBoxCoversAllRouteCoordinatesPlusPadding() {
        val coordinates = listOf(
            GeoPoint(lat = -23.60, lng = -46.70),
            GeoPoint(lat = -23.55, lng = -46.63),
            GeoPoint(lat = -23.58, lng = -46.68)
        )

        val bbox = service.boundingBoxForRoute(coordinates)

        assertNotNull(bbox)
        assertTrue("North bound must be beyond the max route latitude", bbox!!.latNorth > -23.55)
        assertTrue("South bound must be beyond the min route latitude", bbox.latSouth < -23.60)
        assertTrue("East bound must be beyond the max route longitude", bbox.lonEast > -46.63)
        assertTrue("West bound must be beyond the min route longitude", bbox.lonWest < -46.70)
    }

    @Test
    fun testBoundingBoxIsSymmetricAroundRouteExtent() {
        val coordinates = listOf(
            GeoPoint(lat = 10.0, lng = 20.0),
            GeoPoint(lat = 11.0, lng = 21.0)
        )

        val bbox = service.boundingBoxForRoute(coordinates)!!

        val northPadding = bbox.latNorth - 11.0
        val southPadding = 10.0 - bbox.latSouth
        val eastPadding = bbox.lonEast - 21.0
        val westPadding = 20.0 - bbox.lonWest

        assertEquals(northPadding, southPadding, 1e-9)
        assertEquals(eastPadding, westPadding, 1e-9)
        assertEquals(northPadding, eastPadding, 1e-9)
    }

    @Test
    fun testEstimateTileCountIsPositiveForValidBoundingBox() {
        val coordinates = listOf(
            GeoPoint(lat = -23.60, lng = -46.70),
            GeoPoint(lat = -23.55, lng = -46.63)
        )
        val bbox = service.boundingBoxForRoute(coordinates)!!

        val tileCount = service.estimateTileCount(bbox)

        assertTrue("Tile count should be positive for a valid region", tileCount > 0)
    }

    @Test
    fun testEstimateTileCountCoversEveryZoomLevelInTheDownloadBand() {
        // OfflineTileCacheService downloads a fixed zoom band (12-17, 6 levels) for
        // every route, so a valid region must always contribute at least one tile
        // per zoom level regardless of how small the route is.
        val coordinates = listOf(
            GeoPoint(lat = -23.551, lng = -46.631),
            GeoPoint(lat = -23.550, lng = -46.630)
        )
        val bbox = service.boundingBoxForRoute(coordinates)!!

        val tileCount = service.estimateTileCount(bbox)

        assertTrue("Should cover all 6 zoom levels (12-17) of the download band", tileCount >= 6)
    }

    @Test
    fun testEstimateDownloadBytesScalesWithTileCount() {
        val coordinates = listOf(
            GeoPoint(lat = -23.60, lng = -46.70),
            GeoPoint(lat = -23.55, lng = -46.63)
        )
        val bbox = service.boundingBoxForRoute(coordinates)!!

        val tileCount = service.estimateTileCount(bbox)
        val bytes = service.estimateDownloadBytes(bbox)

        assertEquals(tileCount * 15_000L, bytes)
    }
}

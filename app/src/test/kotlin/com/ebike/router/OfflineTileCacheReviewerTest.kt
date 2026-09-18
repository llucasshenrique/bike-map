package com.ebike.router

import com.ebike.router.model.GeoPoint
import com.ebike.router.service.OfflineTileCacheService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.osmdroid.util.BoundingBox

/**
 * Independent reviewer test suite for [OfflineTileCacheService] and offline map caching edge cases.
 *
 * Covers:
 * - Zero-size regions (identical route points, point bounding boxes)
 * - Malformed bounding boxes (inverted latitudes/longitudes, NaN/infinite values, out-of-world coordinates)
 * - Projection limits (Web Mercator latitude/longitude clamping)
 * - Cache-size overflow (huge regions, tile count thresholds, existing cache usage accounting)
 * - Cancellation lifecycle and graceful exception handling
 */
class OfflineTileCacheReviewerTest {

    private lateinit var service: OfflineTileCacheService

    @Before
    fun setUp() {
        service = OfflineTileCacheService()
    }

    // =========================================================================
    // 1. ZERO-SIZE REGIONS
    // =========================================================================

    @Test
    fun testBoundingBoxReturnsNullForZeroSpanRouteAllIdenticalPoints() {
        val identicalPoints = listOf(
            GeoPoint(lat = -23.5505, lng = -46.6333),
            GeoPoint(lat = -23.5505, lng = -46.6333),
            GeoPoint(lat = -23.5505, lng = -46.6333)
        )
        // A route with zero spatial extent represents a stationary point, not a traversable route.
        assertNull(
            "Route with identical points (zero span) must return null bounding box",
            service.boundingBoxForRoute(identicalPoints)
        )
    }

    @Test
    fun testIsZeroSizeIdentifiesDegeneratePointBoundingBox() {
        val pointBox = BoundingBox(10.0, 20.0, 10.0, 20.0)
        assertTrue("Bounding box with equal north/south and east/west must be zero-size", service.isZeroSize(pointBox))

        val normalBox = BoundingBox(11.0, 21.0, 10.0, 20.0)
        assertFalse("Normal bounding box must not be zero-size", service.isZeroSize(normalBox))
    }

    @Test
    fun testEstimateTileCountReturnsZeroForZeroSizeBoundingBox() {
        val pointBox = BoundingBox(0.0, 0.0, 0.0, 0.0)
        assertEquals(
            "Zero-size bounding box should yield 0 estimated tiles",
            0,
            service.estimateTileCount(pointBox)
        )
    }

    @Test
    fun testEstimateDownloadBytesReturnsZeroForZeroSizeRegion() {
        val pointBox = BoundingBox(-23.55, -46.63, -23.55, -46.63)
        assertEquals(0L, service.estimateDownloadBytes(pointBox))
    }

    // =========================================================================
    // 2. MALFORMED BOUNDING BOXES & COORDINATE VALIDATION
    // =========================================================================

    @Test
    fun testBoundingBoxReturnsNullForNaNCoordinates() {
        val nanLatitude = listOf(
            GeoPoint(lat = Double.NaN, lng = -46.63),
            GeoPoint(lat = -23.55, lng = -46.63)
        )
        assertNull("Route with NaN latitude must return null", service.boundingBoxForRoute(nanLatitude))

        val nanLongitude = listOf(
            GeoPoint(lat = -23.55, lng = -46.63),
            GeoPoint(lat = -23.55, lng = Double.NaN)
        )
        assertNull("Route with NaN longitude must return null", service.boundingBoxForRoute(nanLongitude))
    }

    @Test
    fun testBoundingBoxReturnsNullForInfiniteCoordinates() {
        val infiniteCoords = listOf(
            GeoPoint(lat = Double.POSITIVE_INFINITY, lng = -46.63),
            GeoPoint(lat = -23.55, lng = -46.63)
        )
        assertNull("Route with infinite coordinates must return null", service.boundingBoxForRoute(infiniteCoords))
    }

    @Test
    fun testBoundingBoxReturnsNullForGeographicallyInvalidCoordinates() {
        val outOfBoundsCoords = listOf(
            GeoPoint(lat = 95.0, lng = -46.63),
            GeoPoint(lat = -23.55, lng = -46.63)
        )
        assertNull("Route with latitude > 90 must return null", service.boundingBoxForRoute(outOfBoundsCoords))

        val outOfBoundsLng = listOf(
            GeoPoint(lat = -23.55, lng = 200.0),
            GeoPoint(lat = -23.55, lng = -46.63)
        )
        assertNull("Route with longitude > 180 must return null", service.boundingBoxForRoute(outOfBoundsLng))
    }

    @Test
    fun testBoundingBoxPaddedCoordinatesAreClampedToWebMercatorBounds() {
        // Points near extreme northern latitude and eastern longitude
        val polarEastRoute = listOf(
            GeoPoint(lat = 85.05, lng = 179.99),
            GeoPoint(lat = 85.04, lng = 179.98)
        )
        val bbox = service.boundingBoxForRoute(polarEastRoute)
        assertNotNull("Bounding box should be generated for near-extreme route", bbox)

        // North must not exceed Web Mercator max latitude (85.05112878)
        assertTrue(
            "North latitude must be clamped to Web Mercator max",
            bbox!!.latNorth <= OfflineTileCacheService.MAX_LATITUDE
        )
        // East must not exceed 180.0 longitude
        assertTrue(
            "East longitude must be clamped to 180.0",
            bbox.lonEast <= OfflineTileCacheService.MAX_LONGITUDE
        )

        // Points near extreme southern latitude and western longitude
        val polarWestRoute = listOf(
            GeoPoint(lat = -85.05, lng = -179.99),
            GeoPoint(lat = -85.04, lng = -179.98)
        )
        val southBbox = service.boundingBoxForRoute(polarWestRoute)!!
        assertTrue(
            "South latitude must be clamped to Web Mercator min",
            southBbox.latSouth >= OfflineTileCacheService.MIN_LATITUDE
        )
        assertTrue(
            "West longitude must be clamped to -180.0",
            southBbox.lonWest >= OfflineTileCacheService.MIN_LONGITUDE
        )
    }

    @Test
    fun testEstimateTileCountReturnsZeroForInvertedLatitudes() {
        // Malformed: latNorth is south of latSouth (upside down)
        val invertedLatBox = BoundingBox(10.0, 20.0, 25.0, 15.0)
        assertEquals(
            "Inverted latitude bounding box must yield 0 tiles, not world-wrap",
            0,
            service.estimateTileCount(invertedLatBox)
        )
    }

    @Test
    fun testEstimateTileCountReturnsZeroForInvertedLongitudes() {
        // Malformed: lonEast is west of lonWest
        val invertedLonBox = BoundingBox(25.0, 10.0, 10.0, 20.0)
        assertEquals(
            "Inverted longitude bounding box must yield 0 tiles",
            0,
            service.estimateTileCount(invertedLonBox)
        )
    }

    @Test
    fun testEstimateTileCountReturnsZeroForNaNBoundingBox() {
        val nanBox = BoundingBox(Double.NaN, 20.0, 10.0, 15.0)
        assertEquals("NaN bounding box must return 0 tiles", 0, service.estimateTileCount(nanBox))
    }

    @Test
    fun testIsValidBoundingBoxRejectionRules() {
        // Normal valid box
        val validBox = BoundingBox(23.60, -46.60, 23.50, -46.70)
        assertTrue(service.isValidBoundingBox(validBox))

        // Inverted latitude
        assertFalse(service.isValidBoundingBox(BoundingBox(23.50, -46.60, 23.60, -46.70)))
        // Inverted longitude
        assertFalse(service.isValidBoundingBox(BoundingBox(23.60, -46.70, 23.50, -46.60)))
        // Out-of-range latitude (> 90)
        assertFalse(service.isValidBoundingBox(BoundingBox(95.0, -46.60, 23.50, -46.70)))
        // Out-of-range longitude (> 180)
        assertFalse(service.isValidBoundingBox(BoundingBox(23.60, 185.0, 23.50, -46.70)))
        // NaN coordinates
        assertFalse(service.isValidBoundingBox(BoundingBox(Double.NaN, -46.60, 23.50, -46.70)))
        // Degenerate zero-size
        assertFalse(service.isValidBoundingBox(BoundingBox(23.50, -46.60, 23.50, -46.60)))
    }

    // =========================================================================
    // 3. CACHE-SIZE OVERFLOW & LIMIT CHECKS
    // =========================================================================

    @Test
    fun testWouldExceedCacheLimitForNormalRouteReturnsFalse() {
        // A typical city route (5-10 km in São Paulo)
        val cityCoordinates = listOf(
            GeoPoint(lat = -23.58, lng = -46.68),
            GeoPoint(lat = -23.55, lng = -46.63)
        )
        val bbox = service.boundingBoxForRoute(cityCoordinates)!!

        assertFalse(
            "A standard city route must not exceed the default 500 MB cache limit",
            service.wouldExceedCacheLimit(bbox)
        )
        assertFalse(
            "A standard city route must not exceed the recommended 10,000 tile limit",
            service.wouldExceedTileLimit(bbox)
        )
    }

    @Test
    fun testWouldExceedCacheLimitForEnormousRegionReturnsTrue() {
        // A massive 20x20 degree region (approx 2,200 km x 2,200 km)
        val hugeBox = BoundingBox(20.0, 20.0, 0.0, 0.0)

        assertTrue(
            "A huge multi-state region must exceed the cache capacity limit",
            service.wouldExceedCacheLimit(hugeBox)
        )
        assertTrue(
            "A huge multi-state region must exceed the tile count limit",
            service.wouldExceedTileLimit(hugeBox)
        )
    }

    @Test
    fun testWouldExceedCacheLimitAccountsForExistingCacheUsage() {
        val coordinates = listOf(
            GeoPoint(lat = -23.56, lng = -46.66),
            GeoPoint(lat = -23.55, lng = -46.65)
        )
        val bbox = service.boundingBoxForRoute(coordinates)!!
        val downloadBytes = service.estimateDownloadBytes(bbox)
        assertTrue("Download bytes must be positive", downloadBytes > 0)

        val maxCache = OfflineTileCacheService.MAX_CACHE_SIZE_BYTES
        // If the cache is already almost completely full:
        val existingUsage = maxCache - (downloadBytes / 2)

        assertTrue(
            "Must flag overflow when existing cache usage + new download exceeds capacity",
            service.wouldExceedCacheLimit(bbox, maxCacheBytes = maxCache, currentUsageBytes = existingUsage)
        )

        // If plenty of headroom remains:
        val lowUsage = 10_000_000L // 10 MB used
        assertFalse(
            "Must not flag overflow when sufficient headroom is available",
            service.wouldExceedCacheLimit(bbox, maxCacheBytes = maxCache, currentUsageBytes = lowUsage)
        )
    }

    @Test
    fun testWouldExceedCacheLimitSafelyHandlesMalformedBox() {
        val invertedBox = BoundingBox(10.0, 20.0, 20.0, 10.0)
        assertFalse(
            "Malformed boxes should safely return false from cache capacity check",
            service.wouldExceedCacheLimit(invertedBox)
        )
    }

    // =========================================================================
    // 4. CANCELLATION LIFECYCLE & SAFETY
    // =========================================================================

    @Test
    fun testCancelDownloadWhenIdleReturnsFalse() {
        assertFalse("Cancelling when no task is active must return false", service.cancelDownload())
        assertFalse("isDownloading must be false initially", service.isDownloading())
        assertNull("activeTask must be null initially", service.activeTask)
    }

    @Test
    fun testClearCacheHandlesUninitializedStorageGracefully() {
        // In local JVM unit tests without an active SQLite database / Android context,
        // clearCache must not throw an unhandled exception.
        val result = service.clearCache()
        assertFalse("Should return false without throwing when storage is uninitialized", result)
    }

    @Test
    fun testCurrentCacheSizeBytesHandlesUninitializedStorageGracefully() {
        // In local JVM unit tests, currentCacheSizeBytes must return 0L rather than crashing.
        val size = service.currentCacheSizeBytes()
        assertEquals(0L, size)
    }
}

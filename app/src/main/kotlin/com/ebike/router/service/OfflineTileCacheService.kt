package com.ebike.router.service

import android.content.Context
import com.ebike.router.model.GeoPoint
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.TileSourcePolicyException
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.MapTileArea
import org.osmdroid.views.MapView

/** State of a region pre-download for a planned route, surfaced to the UI. */
sealed class OfflineDownloadState {
    data object Idle : OfflineDownloadState()
    data class Running(val downloaded: Int, val total: Int) : OfflineDownloadState()
    data object Done : OfflineDownloadState()
    data class Error(val message: String) : OfflineDownloadState()
}

/**
 * Wraps osmdroid's [CacheManager]/[SqlTileWriter] to pre-download the tiles for a
 * planned route's bounding box, so the ride is viewable without a network connection.
 */
class OfflineTileCacheService {

    companion object {
        // City/route-following zoom band; wide enough to be useful, bounded enough to
        // keep tile counts (and MAPNIK bulk-download load) reasonable.
        const val MIN_ZOOM = 12
        const val MAX_ZOOM = 17

        // Buffer beyond the route polyline so minor reroutes/off-route excursions
        // still land on cached tiles (larger than the 0.005 deg camera-framing pad).
        const val REGION_PADDING_DEGREES = 0.015

        // Rough average MAPNIK PNG tile size, used only to estimate download size for
        // the UI before committing (osmdroid has no cheap way to know exact bytes upfront).
        const val AVERAGE_TILE_BYTES = 15_000L

        // Default maximum cache capacity (500 MB) matching MapView configuration
        const val MAX_CACHE_SIZE_BYTES = 500L * 1024 * 1024L

        // Recommended upper bound on pre-downloadable tiles for a single route (~10k tiles ≈ 150 MB)
        // to comply with OSM tile server usage policies and prevent cache thrashing.
        const val MAX_RECOMMENDED_TILES = 10_000

        // Web Mercator coordinate bounds
        const val MAX_LATITUDE = 85.05112878
        const val MIN_LATITUDE = -85.05112878
        const val MAX_LONGITUDE = 180.0
        const val MIN_LONGITUDE = -180.0
    }

    @Volatile
    var activeTask: CacheManager.CacheManagerTask? = null
        private set

    /** Checks whether [bbox] has zero spatial extent (single point). */
    fun isZeroSize(bbox: BoundingBox): Boolean =
        bbox.latNorth == bbox.latSouth && bbox.lonEast == bbox.lonWest

    /** Validates that [bbox] has valid finite coordinates, non-inverted bounds, and non-zero extent. */
    fun isValidBoundingBox(bbox: BoundingBox): Boolean {
        if (bbox.latNorth.isNaN() || bbox.latSouth.isNaN() || bbox.lonEast.isNaN() || bbox.lonWest.isNaN()) return false
        if (bbox.latNorth.isInfinite() || bbox.latSouth.isInfinite() || bbox.lonEast.isInfinite() || bbox.lonWest.isInfinite()) return false
        if (bbox.latNorth < bbox.latSouth) return false
        if (bbox.lonEast < bbox.lonWest) return false
        if (bbox.latNorth > 90.0 || bbox.latSouth < -90.0) return false
        if (bbox.lonEast > 180.0 || bbox.lonWest < -180.0) return false
        if (isZeroSize(bbox)) return false
        return true
    }

    /** Bounding box covering [coordinates] plus a buffer, or null if invalid or fewer than 2 distinct points. */
    fun boundingBoxForRoute(coordinates: List<GeoPoint>): BoundingBox? {
        if (coordinates.size < 2) return null
        if (coordinates.any { it.lat.isNaN() || it.lng.isNaN() || it.lat.isInfinite() || it.lng.isInfinite() }) return null
        val minLat = coordinates.minOf { it.lat }
        val maxLat = coordinates.maxOf { it.lat }
        val minLng = coordinates.minOf { it.lng }
        val maxLng = coordinates.maxOf { it.lng }

        // Route with zero spatial span (all points identical)
        if (minLat == maxLat && minLng == maxLng) return null
        if (minLat < -90.0 || maxLat > 90.0 || minLng < -180.0 || maxLng > 180.0) return null

        val clampedNorth = (maxLat + REGION_PADDING_DEGREES).coerceAtMost(MAX_LATITUDE)
        val clampedSouth = (minLat - REGION_PADDING_DEGREES).coerceAtLeast(MIN_LATITUDE)
        val clampedEast = (maxLng + REGION_PADDING_DEGREES).coerceAtMost(MAX_LONGITUDE)
        val clampedWest = (minLng - REGION_PADDING_DEGREES).coerceAtLeast(MIN_LONGITUDE)

        if (clampedNorth < clampedSouth || clampedEast < clampedWest) return null

        return BoundingBox(clampedNorth, clampedEast, clampedSouth, clampedWest)
    }

    private val tileSystem = org.osmdroid.util.TileSystemWebMercator()

    /** Exact tile count for [bbox] across the fixed zoom band, computed mathematically without heavy allocations. */
    fun estimateTileCount(bbox: BoundingBox): Int {
        if (!isValidBoundingBox(bbox)) return 0
        var total = 0L
        for (zoom in MIN_ZOOM..MAX_ZOOM) {
            val maxTile = 1 shl zoom
            val xWest = tileSystem.getTileXFromLongitude(bbox.lonWest, zoom)
            val xEast = tileSystem.getTileXFromLongitude(bbox.lonEast, zoom)
            val yNorth = tileSystem.getTileYFromLatitude(bbox.latNorth, zoom)
            val ySouth = tileSystem.getTileYFromLatitude(bbox.latSouth, zoom)

            val width = if (xEast >= xWest) (xEast - xWest + 1) else (xEast - xWest + 1 + maxTile)
            val height = if (ySouth >= yNorth) (ySouth - yNorth + 1) else (ySouth - yNorth + 1 + maxTile)
            total += (width.toLong() * height.toLong())
            if (total > Int.MAX_VALUE) return Int.MAX_VALUE
        }
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun estimateDownloadBytes(bbox: BoundingBox): Long =
        estimateTileCount(bbox) * AVERAGE_TILE_BYTES

    /** Checks whether downloading [bbox] would exceed the maximum cache capacity. */
    fun wouldExceedCacheLimit(
        bbox: BoundingBox,
        maxCacheBytes: Long = MAX_CACHE_SIZE_BYTES,
        currentUsageBytes: Long = 0L
    ): Boolean {
        if (!isValidBoundingBox(bbox)) return false
        val downloadBytes = estimateDownloadBytes(bbox)
        return (currentUsageBytes + downloadBytes) > maxCacheBytes
    }

    /** Checks whether the estimated tile count exceeds the recommended threshold. */
    fun wouldExceedTileLimit(bbox: BoundingBox, maxTiles: Int = MAX_RECOMMENDED_TILES): Boolean {
        return estimateTileCount(bbox) > maxTiles
    }

    /** Cancels any active tile pre-download job. Returns true if an active task was cancelled. */
    fun cancelDownload(): Boolean {
        val task = activeTask
        return if (task != null) {
            val cancelled = task.cancel(true)
            activeTask = null
            cancelled
        } else {
            false
        }
    }

    /** Returns whether a pre-download job is actively running. */
    fun isDownloading(): Boolean = activeTask?.let { !it.isCancelled } ?: false

    /**
     * Downloads every tile in [bbox] for the fixed zoom band into osmdroid's shared
     * tile cache, so it plays back offline via the normal MAPNIK render path.
     *
     * Returns the created [CacheManager.CacheManagerTask], or null if rejected.
     */
    fun downloadRegion(
        context: Context,
        mapView: MapView,
        bbox: BoundingBox,
        onProgress: (downloaded: Int, total: Int) -> Unit,
        onDone: (success: Boolean) -> Unit
    ): CacheManager.CacheManagerTask? {
        if (!isValidBoundingBox(bbox)) {
            onDone(false)
            return null
        }
        if (wouldExceedCacheLimit(bbox)) {
            onDone(false)
            return null
        }

        cancelDownload()

        return try {
            val cacheManager = CacheManager(mapView)
            var totalTiles = estimateTileCount(bbox)
            if (totalTiles <= 0) {
                onDone(false)
                return null
            }

            val task = cacheManager.downloadAreaAsyncNoUI(
                context,
                bbox,
                MIN_ZOOM,
                MAX_ZOOM,
                object : CacheManager.CacheManagerCallback {
                    override fun onTaskComplete() {
                        activeTask = null
                        onProgress(totalTiles, totalTiles)
                        onDone(true)
                    }

                    override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {
                        onProgress(progress, totalTiles)
                    }

                    override fun downloadStarted() {}

                    override fun setPossibleTilesInArea(total: Int) {
                        totalTiles = total
                    }

                    override fun onTaskFailed(errors: Int) {
                        activeTask = null
                        onDone(false)
                    }
                }
            )
            activeTask = task
            task
        } catch (e: Exception) {
            activeTask = null
            onDone(false)
            null
        }
    }

    /** Total on-disk size of the shared osmdroid tile cache, in bytes. */
    fun currentCacheSizeBytes(): Long {
        return try {
            val writer = SqlTileWriter()
            try {
                writer.getSize()
            } finally {
                writer.onDetach()
            }
        } catch (e: Throwable) {
            0L
        }
    }

    /** Wipes the entire shared tile cache (both browsed and pre-downloaded tiles). */
    fun clearCache(): Boolean {
        return try {
            val writer = SqlTileWriter()
            try {
                writer.purgeCache()
            } finally {
                writer.onDetach()
            }
        } catch (e: Throwable) {
            false
        }
    }
}

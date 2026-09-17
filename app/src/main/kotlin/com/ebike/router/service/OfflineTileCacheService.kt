package com.ebike.router.service

import android.content.Context
import com.ebike.router.model.GeoPoint
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.TileSourcePolicyException
import org.osmdroid.util.BoundingBox
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
        private const val REGION_PADDING_DEGREES = 0.015

        // Rough average MAPNIK PNG tile size, used only to estimate download size for
        // the UI before committing (osmdroid has no cheap way to know exact bytes upfront).
        private const val AVERAGE_TILE_BYTES = 15_000L
    }

    /** Bounding box covering [coordinates] plus a buffer, or null if too few points. */
    fun boundingBoxForRoute(coordinates: List<GeoPoint>): BoundingBox? {
        if (coordinates.size < 2) return null
        val minLat = coordinates.minOf { it.lat }
        val maxLat = coordinates.maxOf { it.lat }
        val minLng = coordinates.minOf { it.lng }
        val maxLng = coordinates.maxOf { it.lng }
        return BoundingBox(
            maxLat + REGION_PADDING_DEGREES,
            maxLng + REGION_PADDING_DEGREES,
            minLat - REGION_PADDING_DEGREES,
            minLng - REGION_PADDING_DEGREES
        )
    }

    /** Exact tile count for [bbox] across the fixed zoom band, no MapView required. */
    fun estimateTileCount(bbox: BoundingBox): Int =
        CacheManager.getTilesCoverage(bbox, MIN_ZOOM, MAX_ZOOM).size

    fun estimateDownloadBytes(bbox: BoundingBox): Long =
        estimateTileCount(bbox) * AVERAGE_TILE_BYTES

    /**
     * Downloads every tile in [bbox] for the fixed zoom band into osmdroid's shared
     * tile cache, so it plays back offline via the normal MAPNIK render path.
     *
     * Requires a live, attached [mapView] because [CacheManager]'s constructor
     * validates the active tile source's usage policy against it.
     */
    fun downloadRegion(
        context: Context,
        mapView: MapView,
        bbox: BoundingBox,
        onProgress: (downloaded: Int, total: Int) -> Unit,
        onDone: (success: Boolean) -> Unit
    ) {
        try {
            val cacheManager = CacheManager(mapView)
            var totalTiles = estimateTileCount(bbox)
            cacheManager.downloadAreaAsyncNoUI(
                context,
                bbox,
                MIN_ZOOM,
                MAX_ZOOM,
                object : CacheManager.CacheManagerCallback {
                    override fun onTaskComplete() {
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
                        onDone(false)
                    }
                }
            )
        } catch (e: TileSourcePolicyException) {
            onDone(false)
        }
    }

    /** Total on-disk size of the shared osmdroid tile cache, in bytes. */
    fun currentCacheSizeBytes(): Long {
        val writer = SqlTileWriter()
        return try {
            writer.getSize()
        } finally {
            writer.onDetach()
        }
    }

    /** Wipes the entire shared tile cache (both browsed and pre-downloaded tiles). */
    fun clearCache(): Boolean {
        val writer = SqlTileWriter()
        return try {
            writer.purgeCache()
        } finally {
            writer.onDetach()
        }
    }
}

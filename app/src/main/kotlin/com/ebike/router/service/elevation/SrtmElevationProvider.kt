package com.ebike.router.service.elevation

import com.ebike.router.data.dem.SrtmTileManager
import com.ebike.router.model.GeoPoint

/**
 * Tier 2: offline lookup against bundled/downloaded SRTM .hgt tiles.
 * Returns null per-point when no local tile covers that coordinate, letting
 * the caller fall through to the remote tier.
 */
class SrtmElevationProvider(private val tileManager: SrtmTileManager) : ElevationProvider {
    override suspend fun getElevations(points: List<GeoPoint>): List<Double?> {
        return points.map { tileManager.getElevation(it.lat, it.lng) }
    }
}

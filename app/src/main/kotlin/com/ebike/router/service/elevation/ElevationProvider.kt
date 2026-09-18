package com.ebike.router.service.elevation

import com.ebike.router.model.GeoPoint

interface ElevationProvider {
    suspend fun getElevations(points: List<GeoPoint>): List<Double?>
}

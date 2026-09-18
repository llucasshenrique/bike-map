package com.ebike.router.service.elevation

import com.ebike.router.data.local.ElevationDatabase
import com.ebike.router.data.local.ElevationEntity
import com.ebike.router.model.GeoPoint

/**
 * Orchestrates the three-tier elevation lookup: on-device cache (L1 memory
 * + L2 SQLite) first, then bundled/downloaded SRTM tiles, then the remote
 * elevation API for anything still missing. Every resolved point is written
 * back into the cache so repeat lookups (alternative routes sharing the
 * same streets, redraws) resolve instantly and offline.
 */
class CompositeElevationProvider(
    private val database: ElevationDatabase,
    private val srtmProvider: ElevationProvider,
    private val remoteProvider: ElevationProvider
) : ElevationProvider {

    override suspend fun getElevations(points: List<GeoPoint>): List<Double?> {
        if (points.isEmpty()) return emptyList()

        val keys = points.map { ElevationDatabase.computeSpatialKey(it.lat, it.lng) }
        val cached = database.elevationDao.getElevations(keys)

        val result = MutableList<Double?>(points.size) { null }
        val missing = mutableListOf<Int>()
        points.indices.forEach { i ->
            val hit = cached[keys[i]]
            if (hit != null) result[i] = hit.elevationM else missing.add(i)
        }
        if (missing.isEmpty()) return result

        val toCache = mutableListOf<ElevationEntity>()

        val srtmResults = srtmProvider.getElevations(missing.map { points[it] })
        val stillMissing = mutableListOf<Int>()
        missing.forEachIndexed { idx, originalIdx ->
            val ele = srtmResults.getOrNull(idx)
            if (ele != null) {
                result[originalIdx] = ele
                toCache.add(ElevationEntity(keys[originalIdx], points[originalIdx].lat, points[originalIdx].lng, ele))
            } else {
                stillMissing.add(originalIdx)
            }
        }

        if (stillMissing.isNotEmpty()) {
            val remoteResults = remoteProvider.getElevations(stillMissing.map { points[it] })
            stillMissing.forEachIndexed { idx, originalIdx ->
                val ele = remoteResults.getOrNull(idx)
                if (ele != null) {
                    result[originalIdx] = ele
                    toCache.add(ElevationEntity(keys[originalIdx], points[originalIdx].lat, points[originalIdx].lng, ele))
                }
            }
        }

        if (toCache.isNotEmpty()) database.elevationDao.insertAll(toCache)

        return result
    }
}

package com.ebike.router.data.local

interface ElevationDao {
    fun getElevation(spatialKey: Long): ElevationEntity?
    fun getElevations(keys: List<Long>): Map<Long, ElevationEntity>
    fun insert(entity: ElevationEntity)
    fun insertAll(entities: List<ElevationEntity>)
    fun getCount(): Long
    fun pruneOldest(keepCount: Int)
}

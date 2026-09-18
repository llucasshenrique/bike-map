package com.ebike.router.data.local

data class ElevationEntity(
    val spatialKey: Long,
    val latitude: Double,
    val longitude: Double,
    val elevationM: Double,
    val createdAt: Long = System.currentTimeMillis()
)

package com.ebike.router.data.repository

import com.ebike.router.data.local.entity.DestinationCategory
import com.ebike.router.data.local.entity.RideHistoryEntity
import com.ebike.router.data.local.entity.SavedDestinationEntity
import com.ebike.router.data.local.entity.SavedRouteEntity
import com.ebike.router.model.GeoPoint
import com.ebike.router.model.RouteResult
import com.ebike.router.model.RouteWaypoint
import kotlinx.coroutines.flow.Flow

interface BikeRepository {
    val allRides: Flow<List<RideHistoryEntity>>
    val savedRoutes: Flow<List<SavedRouteEntity>>
    val savedDestinations: Flow<List<SavedDestinationEntity>>

    suspend fun saveRide(ride: RideHistoryEntity): Long
    suspend fun renameRide(id: Long, title: String)
    suspend fun deleteRide(id: Long)
    suspend fun getRideById(id: Long): RideHistoryEntity?

    suspend fun saveRoute(name: String, route: RouteResult, waypoints: List<RouteWaypoint>): Long
    suspend fun deleteRoute(id: Long)
    suspend fun renameRoute(id: Long, newName: String)
    suspend fun setRouteFavorite(id: Long, isFavorite: Boolean)

    suspend fun saveDestination(
        label: String,
        point: GeoPoint,
        address: String,
        category: DestinationCategory = DestinationCategory.FAVORITE
    ): Long
    suspend fun deleteDestination(id: Long)
    suspend fun renameDestination(id: Long, newLabel: String)
}

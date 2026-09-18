package com.ebike.router.data.repository

import com.ebike.router.data.local.dao.RideHistoryDao
import com.ebike.router.data.local.dao.SavedDestinationDao
import com.ebike.router.data.local.dao.SavedRouteDao
import com.ebike.router.data.local.entity.DestinationCategory
import com.ebike.router.data.local.entity.RideHistoryEntity
import com.ebike.router.data.local.entity.SavedDestinationEntity
import com.ebike.router.data.local.entity.SavedRouteEntity
import com.ebike.router.model.GeoPoint
import com.ebike.router.model.RouteResult
import com.ebike.router.model.RouteWaypoint
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class BikeRepositoryImpl(
    private val rideHistoryDao: RideHistoryDao,
    private val savedRouteDao: SavedRouteDao,
    private val savedDestinationDao: SavedDestinationDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : BikeRepository {

    private val gson = Gson()

    override val allRides: Flow<List<RideHistoryEntity>> = rideHistoryDao.getAllRides()
    override val savedRoutes: Flow<List<SavedRouteEntity>> = savedRouteDao.getAllSavedRoutes()
    override val savedDestinations: Flow<List<SavedDestinationEntity>> = savedDestinationDao.getAllDestinations()

    override suspend fun saveRide(ride: RideHistoryEntity): Long = withContext(ioDispatcher) {
        rideHistoryDao.insertRide(ride)
    }

    override suspend fun renameRide(id: Long, title: String) = withContext(ioDispatcher) {
        rideHistoryDao.renameRide(id, title)
    }

    override suspend fun deleteRide(id: Long) = withContext(ioDispatcher) {
        rideHistoryDao.deleteRideById(id)
    }

    override suspend fun getRideById(id: Long): RideHistoryEntity? = withContext(ioDispatcher) {
        rideHistoryDao.getRideById(id)
    }

    override suspend fun saveRoute(
        name: String,
        route: RouteResult,
        waypoints: List<RouteWaypoint>
    ): Long = withContext(ioDispatcher) {
        val entity = SavedRouteEntity(
            name = name,
            profile = route.profile,
            waypointsJson = gson.toJson(waypoints),
            polylineJson = gson.toJson(route.coordinates),
            totalDistanceMeters = route.totalDistanceMeters,
            totalDurationSeconds = route.totalDurationSeconds,
            elevationGainM = route.elevationGainM,
            isFavorite = true
        )
        savedRouteDao.insertRoute(entity)
    }

    override suspend fun deleteRoute(id: Long) = withContext(ioDispatcher) {
        savedRouteDao.deleteRouteById(id)
    }

    override suspend fun renameRoute(id: Long, newName: String) = withContext(ioDispatcher) {
        savedRouteDao.renameRoute(id, newName)
    }

    override suspend fun setRouteFavorite(id: Long, isFavorite: Boolean) = withContext(ioDispatcher) {
        savedRouteDao.setFavorite(id, isFavorite)
    }

    override suspend fun saveDestination(
        label: String,
        point: GeoPoint,
        address: String,
        category: DestinationCategory
    ): Long = withContext(ioDispatcher) {
        val entity = SavedDestinationEntity(
            label = label,
            subText = address,
            lat = point.lat,
            lng = point.lng,
            ele = point.ele,
            category = category
        )
        savedDestinationDao.insertDestination(entity)
    }

    override suspend fun deleteDestination(id: Long) = withContext(ioDispatcher) {
        savedDestinationDao.deleteDestinationById(id)
    }

    override suspend fun renameDestination(id: Long, newLabel: String) = withContext(ioDispatcher) {
        savedDestinationDao.renameDestination(id, newLabel)
    }
}

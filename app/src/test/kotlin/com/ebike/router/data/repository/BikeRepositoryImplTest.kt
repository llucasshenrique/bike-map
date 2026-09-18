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
import com.ebike.router.model.RoutingProfile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

private class FakeRideHistoryDao : RideHistoryDao {
    val rides = mutableListOf<RideHistoryEntity>()
    val flow = MutableStateFlow<List<RideHistoryEntity>>(emptyList())
    var nextId = 1L

    private fun publish() {
        flow.value = rides.sortedByDescending { it.timestampMillis }
    }

    override suspend fun insertRide(ride: RideHistoryEntity): Long {
        val id = if (ride.id != 0L) ride.id else nextId++
        rides.removeAll { it.id == id }
        rides.add(ride.copy(id = id))
        publish()
        return id
    }

    override suspend fun renameRide(id: Long, newTitle: String) {
        val idx = rides.indexOfFirst { it.id == id }
        if (idx >= 0) {
            rides[idx] = rides[idx].copy(title = newTitle)
            publish()
        }
    }

    override suspend fun deleteRide(ride: RideHistoryEntity) {
        rides.removeAll { it.id == ride.id }
        publish()
    }

    override suspend fun deleteRideById(id: Long) {
        rides.removeAll { it.id == id }
        publish()
    }

    override fun getAllRides(): Flow<List<RideHistoryEntity>> = flow

    override suspend fun getRideById(id: Long): RideHistoryEntity? = rides.find { it.id == id }
}

private class FakeSavedRouteDao : SavedRouteDao {
    val routes = mutableListOf<SavedRouteEntity>()
    val flow = MutableStateFlow<List<SavedRouteEntity>>(emptyList())
    var nextId = 1L
    var lastInserted: SavedRouteEntity? = null

    private fun publish() {
        flow.value = routes.sortedByDescending { it.createdAtMillis }
    }

    override suspend fun insertRoute(route: SavedRouteEntity): Long {
        val id = if (route.id != 0L) route.id else nextId++
        val stored = route.copy(id = id)
        routes.removeAll { it.id == id }
        routes.add(stored)
        lastInserted = stored
        publish()
        return id
    }

    override suspend fun renameRoute(id: Long, newName: String) {
        val idx = routes.indexOfFirst { it.id == id }
        if (idx >= 0) {
            routes[idx] = routes[idx].copy(name = newName)
            publish()
        }
    }

    override suspend fun setFavorite(id: Long, isFav: Boolean) {
        val idx = routes.indexOfFirst { it.id == id }
        if (idx >= 0) {
            routes[idx] = routes[idx].copy(isFavorite = isFav)
            publish()
        }
    }

    override suspend fun deleteRouteById(id: Long) {
        routes.removeAll { it.id == id }
        publish()
    }

    override fun getAllSavedRoutes(): Flow<List<SavedRouteEntity>> = flow

    override suspend fun getRouteById(id: Long): SavedRouteEntity? = routes.find { it.id == id }
}

private class FakeSavedDestinationDao : SavedDestinationDao {
    val destinations = mutableListOf<SavedDestinationEntity>()
    val flow = MutableStateFlow<List<SavedDestinationEntity>>(emptyList())
    var nextId = 1L

    private fun publish() {
        flow.value = destinations.sortedByDescending { it.createdAtMillis }
    }

    override suspend fun insertDestination(destination: SavedDestinationEntity): Long {
        val id = if (destination.id != 0L) destination.id else nextId++
        val stored = destination.copy(id = id)
        destinations.removeAll { it.id == id }
        destinations.add(stored)
        publish()
        return id
    }

    override suspend fun renameDestination(id: Long, newLabel: String) {
        val idx = destinations.indexOfFirst { it.id == id }
        if (idx >= 0) {
            destinations[idx] = destinations[idx].copy(label = newLabel)
            publish()
        }
    }

    override suspend fun deleteDestinationById(id: Long) {
        destinations.removeAll { it.id == id }
        publish()
    }

    override fun getAllDestinations(): Flow<List<SavedDestinationEntity>> = flow

    override suspend fun getDestinationById(id: Long): SavedDestinationEntity? =
        destinations.find { it.id == id }
}

class BikeRepositoryImplTest {
    private lateinit var rideHistoryDao: FakeRideHistoryDao
    private lateinit var savedRouteDao: FakeSavedRouteDao
    private lateinit var savedDestinationDao: FakeSavedDestinationDao
    private lateinit var repository: BikeRepositoryImpl

    @Before
    fun setUp() {
        rideHistoryDao = FakeRideHistoryDao()
        savedRouteDao = FakeSavedRouteDao()
        savedDestinationDao = FakeSavedDestinationDao()
        repository = BikeRepositoryImpl(
            rideHistoryDao,
            savedRouteDao,
            savedDestinationDao,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    private fun sampleRide(title: String = "Morning ride") = RideHistoryEntity(
        title = title,
        timestampMillis = 1_000L,
        distanceMeters = 5000,
        durationSeconds = 900,
        avgSpeedKmh = 20.0,
        maxSpeedKmh = 30.0,
        elevationGainM = 50,
        elevationLossM = 40,
        routePolyline = "[]"
    )

    @Test
    fun `saveRide inserts and returns generated id`() = runBlocking {
        val id = repository.saveRide(sampleRide())
        assertEquals(1L, id)
        assertEquals("Morning ride", rideHistoryDao.getRideById(id)?.title)
    }

    @Test
    fun `renameRide delegates to dao`() = runBlocking {
        val id = repository.saveRide(sampleRide())
        repository.renameRide(id, "Renamed ride")
        assertEquals("Renamed ride", repository.getRideById(id)?.title)
    }

    @Test
    fun `deleteRide removes the ride`() = runBlocking {
        val id = repository.saveRide(sampleRide())
        repository.deleteRide(id)
        assertNull(repository.getRideById(id))
    }

    @Test
    fun `saveRoute serializes waypoints and polyline as json and copies route fields`() = runBlocking {
        val waypoints = listOf(
            RouteWaypoint(id = "a", letter = "A", label = "Home", point = GeoPoint(1.0, 2.0)),
            RouteWaypoint(id = "b", letter = "B", label = "Work", point = GeoPoint(3.0, 4.0))
        )
        val coordinates = listOf(GeoPoint(1.0, 2.0), GeoPoint(3.0, 4.0))
        val route = RouteResult(
            id = "r1",
            name = "Commute",
            profile = RoutingProfile.SAFE,
            totalDistanceMeters = 4200,
            totalDurationSeconds = 720,
            totalEnergyWh = 15.0,
            elevationGainM = 12,
            elevationLossM = 8,
            maxGradePercent = 3.0,
            avgGradePercent = 1.0,
            coordinates = coordinates,
            elevationProfile = emptyList(),
            instructions = emptyList(),
            segments = emptyList(),
            batteryDrainPercent = 5.0,
            estimatedBatteryRemainingWh = 100,
            batteryRemainingPercent = 90
        )

        val id = repository.saveRoute(name = "My Commute", route = route, waypoints = waypoints)

        val stored = savedRouteDao.getRouteById(id)
        assertNotNull(stored)
        assertEquals("My Commute", stored!!.name)
        assertEquals(RoutingProfile.SAFE, stored.profile)
        assertEquals(4200, stored.totalDistanceMeters)
        assertEquals(720, stored.totalDurationSeconds)
        assertEquals(12, stored.elevationGainM)
        assertTrue(stored.isFavorite)

        val gson = Gson()
        val waypointType = object : TypeToken<List<RouteWaypoint>>() {}.type
        val decodedWaypoints = gson.fromJson<List<RouteWaypoint>>(stored.waypointsJson, waypointType)
        assertEquals(waypoints, decodedWaypoints)

        val pointType = object : TypeToken<List<GeoPoint>>() {}.type
        val decodedCoordinates = gson.fromJson<List<GeoPoint>>(stored.polylineJson, pointType)
        assertEquals(coordinates, decodedCoordinates)
    }

    @Test
    fun `setRouteFavorite toggles favorite flag`() = runBlocking {
        val id = savedRouteDao.insertRoute(
            SavedRouteEntity(
                name = "Loop",
                profile = RoutingProfile.EFFICIENT,
                waypointsJson = "[]",
                polylineJson = "[]",
                totalDistanceMeters = 1000,
                totalDurationSeconds = 300,
                elevationGainM = 0,
                isFavorite = true
            )
        )
        repository.setRouteFavorite(id, false)
        assertEquals(false, savedRouteDao.getRouteById(id)?.isFavorite)
    }

    @Test
    fun `saveDestination maps GeoPoint fields onto the entity`() = runBlocking {
        val point = GeoPoint(lat = 10.5, lng = -20.25, ele = 33.0)
        val id = repository.saveDestination(
            label = "Trailhead",
            point = point,
            address = "123 Forest Rd",
            category = DestinationCategory.TRAIL
        )

        val stored = savedDestinationDao.getDestinationById(id)
        assertNotNull(stored)
        assertEquals("Trailhead", stored!!.label)
        assertEquals("123 Forest Rd", stored.subText)
        assertEquals(10.5, stored.lat, 0.0001)
        assertEquals(-20.25, stored.lng, 0.0001)
        assertEquals(33.0, stored.ele, 0.0001)
        assertEquals(DestinationCategory.TRAIL, stored.category)
    }

    @Test
    fun `deleteDestination removes it from the dao`() = runBlocking {
        val id = repository.saveDestination(
            label = "Home",
            point = GeoPoint(0.0, 0.0),
            address = "Somewhere"
        )
        repository.deleteDestination(id)
        assertNull(savedDestinationDao.getDestinationById(id))
    }

    @Test
    fun `saveDestination defaults category to FAVORITE`() = runBlocking {
        val id = repository.saveDestination(
            label = "Cafe",
            point = GeoPoint(1.0, 1.0),
            address = "Main St"
        )
        assertEquals(DestinationCategory.FAVORITE, savedDestinationDao.getDestinationById(id)?.category)
    }
}

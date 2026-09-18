package com.ebike.router.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ebike.router.data.local.database.AppDatabase
import com.ebike.router.data.local.entity.DestinationCategory
import com.ebike.router.data.local.entity.RideHistoryEntity
import com.ebike.router.data.local.entity.SavedDestinationEntity
import com.ebike.router.data.local.entity.SavedRouteEntity
import com.ebike.router.data.repository.BikeRepository
import com.ebike.router.data.repository.BikeRepositoryImpl
import com.ebike.router.model.*
import com.ebike.router.physics.EBikePhysicsEngine
import com.ebike.router.service.AudioGuidanceService
import com.ebike.router.service.GeocodingService
import com.ebike.router.service.GraphRouterService
import com.ebike.router.service.LocationTrackerService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class BikeMapViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getInstance(application)
    val repository: BikeRepository = BikeRepositoryImpl(
        database.rideHistoryDao(),
        database.savedRouteDao(),
        database.savedDestinationDao()
    )

    // Observable Room StateFlows
    val rideHistory: StateFlow<List<RideHistoryEntity>> = repository.allRides.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )
    val savedRoutes: StateFlow<List<SavedRouteEntity>> = repository.savedRoutes.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )
    val savedDestinations: StateFlow<List<SavedDestinationEntity>> = repository.savedDestinations.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    val physicsEngine = EBikePhysicsEngine()
    val routerService = GraphRouterService(physicsEngine)
    val geocodingService = GeocodingService()
    val audioGuidance = AudioGuidanceService(application)
    val locationTracker = LocationTrackerService(application)

    // Waypoints
    private val _waypoints = MutableStateFlow<List<RouteWaypoint>>(
        listOf(
            RouteWaypoint("wp_0", "A", "Ponto de Partida (A)", null),
            RouteWaypoint("wp_1", "B", "Destino (B)", null)
        )
    )
    val waypoints: StateFlow<List<RouteWaypoint>> = _waypoints.asStateFlow()

    private val _activePickingWaypointIndex = MutableStateFlow<Int?>(null)
    val activePickingWaypointIndex: StateFlow<Int?> = _activePickingWaypointIndex.asStateFlow()

    // Routes
    private val _availableRoutes = MutableStateFlow<List<RouteResult>>(emptyList())
    val availableRoutes: StateFlow<List<RouteResult>> = _availableRoutes.asStateFlow()

    private val _selectedRouteIndex = MutableStateFlow(0)
    val selectedRouteIndex: StateFlow<Int> = _selectedRouteIndex.asStateFlow()

    val activeRoute = MutableStateFlow<RouteResult?>(null)

    private val _isCalculating = MutableStateFlow(false)
    val isCalculating: StateFlow<Boolean> = _isCalculating.asStateFlow()

    private val _selectedProfile = MutableStateFlow(RoutingProfile.EFFICIENT)
    val selectedProfile: StateFlow<RoutingProfile> = _selectedProfile.asStateFlow()

    // Navigation
    private val _isNavigating = MutableStateFlow(false)
    val isNavigating: StateFlow<Boolean> = _isNavigating.asStateFlow()

    private val _currentInstructionIndex = MutableStateFlow(0)
    val currentInstructionIndex: StateFlow<Int> = _currentInstructionIndex.asStateFlow()

    private val _currentInstruction = MutableStateFlow<TurnInstruction?>(null)
    val currentInstruction: StateFlow<TurnInstruction?> = _currentInstruction.asStateFlow()

    private val _distanceToNextManeuverMeters = MutableStateFlow(0)
    val distanceToNextManeuverMeters: StateFlow<Int> = _distanceToNextManeuverMeters.asStateFlow()

    // Live Telemetry
    private val _telemetry = MutableStateFlow(LiveRideTelemetry())
    val telemetry: StateFlow<LiveRideTelemetry> = _telemetry.asStateFlow()

    // Search
    private val _searchResults = MutableStateFlow<List<SearchResultItem>>(emptyList())
    val searchResults: StateFlow<List<SearchResultItem>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    // Recenter map event
    val recenterEvent = MutableStateFlow<Long>(0L)

    fun recenterMap() {
        locationTracker.refreshCurrentLocation()
        recenterEvent.value = System.currentTimeMillis()
    }

    // UI Sheets / Dialogs
    val showRoutePlannerSheet = MutableStateFlow(false) // Start with full map view & compact bottom bar
    val showSearchDialogForIndex = MutableStateFlow<Int?>(null)
    val showCockpitDialog = MutableStateFlow(false)
    val showRangeCircle = MutableStateFlow(true)
    val showHistorySheet = MutableStateFlow(false)
    val completedRideSummary = MutableStateFlow<RideHistoryEntity?>(null)

    private var rideStartTimeMillis: Long = 0L
    private var rideTimerJob: Job? = null
    private val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    init {
        locationTracker.startTracking()

        // Follow user location changes
        viewModelScope.launch {
            locationTracker.locationState.collect { locState ->
                handleRiderLocationUpdate(locState.point, locState.speedKmh, locState.headingDegrees)
            }
        }
    }

    // --- WAYPOINT MANAGEMENT ---

    fun setPickingWaypointIndex(index: Int?) {
        _activePickingWaypointIndex.value = index
    }

    fun setWaypoint(index: Int, point: GeoPoint, label: String? = null) {
        val list = _waypoints.value.toMutableList()
        if (index in list.indices) {
            val wp = list[index]
            list[index] = wp.copy(
                point = point,
                label = label ?: "Parada ${wp.letter} (${(point.lat * 1000).toInt() / 1000.0}, ${(point.lng * 1000).toInt() / 1000.0})"
            )
            _waypoints.value = list
        }
        _activePickingWaypointIndex.value = null
    }

    fun addWaypoint(point: GeoPoint? = null, label: String? = null) {
        val list = _waypoints.value.toMutableList()
        val newIdx = list.size
        val letter = alphabet[newIdx % alphabet.length].toString()
        list.add(
            RouteWaypoint(
                id = "wp_${System.currentTimeMillis()}_$newIdx",
                letter = letter,
                label = label ?: "Parada $letter",
                point = point
            )
        )
        _waypoints.value = reindexWaypoints(list)
    }

    fun removeWaypoint(index: Int) {
        val list = _waypoints.value.toMutableList()
        if (list.size <= 2) return
        list.removeAt(index)
        _waypoints.value = reindexWaypoints(list)
        if (hasEnoughValidPoints()) {
            calculateRoute()
        }
    }

    fun moveWaypoint(fromIdx: Int, toIdx: Int) {
        val list = _waypoints.value.toMutableList()
        if (fromIdx !in list.indices || toIdx !in list.indices) return
        val item = list.removeAt(fromIdx)
        list.add(toIdx, item)
        _waypoints.value = reindexWaypoints(list)
        if (hasEnoughValidPoints()) {
            calculateRoute()
        }
    }

    fun reverseWaypoints() {
        val list = _waypoints.value.reversed()
        _waypoints.value = reindexWaypoints(list)
        if (hasEnoughValidPoints()) {
            calculateRoute()
        }
    }

    private fun reindexWaypoints(list: List<RouteWaypoint>): List<RouteWaypoint> {
        return list.mapIndexed { idx, wp ->
            val letter = alphabet[idx % alphabet.length].toString()
            val label = if (wp.point != null) wp.label else {
                when (idx) {
                    0 -> "Ponto de Partida (A)"
                    list.size - 1 -> "Destino ($letter)"
                    else -> "Parada $letter"
                }
            }
            wp.copy(letter = letter, label = label)
        }
    }

    fun hasEnoughValidPoints(): Boolean {
        return _waypoints.value.count { it.point != null } >= 2
    }

    fun clearAll() {
        stopNavigation()
        _waypoints.value = listOf(
            RouteWaypoint("wp_0", "A", "Ponto de Partida (A)", null),
            RouteWaypoint("wp_1", "B", "Destino (B)", null)
        )
        _availableRoutes.value = emptyList()
        activeRoute.value = null
        _currentInstruction.value = null
    }

    fun setProfile(profile: RoutingProfile) {
        _selectedProfile.value = profile
        if (hasEnoughValidPoints()) {
            calculateRoute()
        }
    }

    // --- ROUTE CALCULATION ---

    fun calculateRoute() {
        val validPoints = _waypoints.value.mapNotNull { it.point }
        if (validPoints.size < 2) return

        viewModelScope.launch {
            _isCalculating.value = true
            try {
                val routes = routerService.calculateMultipleRoutes(validPoints, _selectedProfile.value)
                _availableRoutes.value = routes
                if (routes.isNotEmpty()) {
                    selectRoute(0)
                }
            } finally {
                _isCalculating.value = false
            }
        }
    }

    fun selectRoute(index: Int) {
        val routes = _availableRoutes.value
        if (index in routes.indices) {
            _selectedRouteIndex.value = index
            val route = routes[index]
            activeRoute.value = route
            _currentInstruction.value = route.instructions.firstOrNull()
        }
    }

    // --- SEARCH ---

    fun searchPlaces(query: String) {
        viewModelScope.launch {
            _isSearching.value = true
            val userPoint = locationTracker.locationState.value.point
            val results = geocodingService.searchPlaces(query, userPoint.lat, userPoint.lng)
            _searchResults.value = results
            _isSearching.value = false
        }
    }

    fun selectSearchResult(targetIndex: Int, item: SearchResultItem) {
        setWaypoint(targetIndex, item.point, item.name)
        showSearchDialogForIndex.value = null
    }

    fun useGpsForWaypoint(index: Int) {
        locationTracker.refreshCurrentLocation()
        val userPoint = locationTracker.locationState.value.point
        setWaypoint(index, userPoint, "Minha Localização GPS")
        showSearchDialogForIndex.value = null
    }

    // --- NAVIGATION ---

    fun startNavigation(route: RouteResult? = activeRoute.value) {
        val targetRoute = route ?: return
        activeRoute.value = targetRoute
        _isNavigating.value = true
        _currentInstructionIndex.value = 0
        _currentInstruction.value = targetRoute.instructions.firstOrNull()
        showRoutePlannerSheet.value = false
        rideStartTimeMillis = System.currentTimeMillis()

        audioGuidance.speak("Navegação iniciada. ${targetRoute.instructions.firstOrNull()?.text ?: ""}", true)
        startRideTimer(targetRoute)
    }

    fun startSimulation(speedMultiplier: Int = 2) {
        val route = activeRoute.value ?: return
        startNavigation(route)
        locationTracker.startSimulator(route, speedMultiplier)
    }

    fun stopNavigation(savePrompt: Boolean = true) {
        val telem = _telemetry.value
        val curRoute = activeRoute.value
        val elapsed = telem.timeElapsedSeconds
        val distKm = telem.distanceRiddenKm

        _isNavigating.value = false
        rideTimerJob?.cancel()
        rideTimerJob = null
        locationTracker.stopSimulator()
        audioGuidance.speak("Navegação finalizada.")

        // Guard against accidental clicks: check if ride was substantive (>= 50m or >= 15s)
        val isSubstantive = distKm >= 0.05 || elapsed >= 15
        if (savePrompt && isSubstantive && curRoute != null) {
            val isEBike = telem.activeAssist != AssistLevel.OFF
            val energyConsumed = if (isEBike) {
                (curRoute.totalEnergyWh * (distKm / max(0.01, curRoute.totalDistanceMeters / 1000.0))).coerceAtLeast(0.0)
            } else null

            val batteryDrain = if (isEBike) {
                (curRoute.batteryDrainPercent * (distKm / max(0.01, curRoute.totalDistanceMeters / 1000.0))).coerceAtLeast(0.0)
            } else null

            val polylineJson = Gson().toJson(curRoute.coordinates)
            val startTime = if (rideStartTimeMillis > 0) rideStartTimeMillis else System.currentTimeMillis()
            val formattedDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(startTime))
            val defaultTitle = "Pedal $formattedDate"

            val summary = RideHistoryEntity(
                title = defaultTitle,
                timestampMillis = startTime,
                distanceMeters = (distKm * 1000).toInt(),
                durationSeconds = elapsed,
                avgSpeedKmh = telem.avgSpeedKmh,
                maxSpeedKmh = telem.maxSpeedKmh,
                elevationGainM = telem.elevationGainedM.toInt(),
                elevationLossM = curRoute.elevationLossM,
                routePolyline = polylineJson,
                startAddress = _waypoints.value.firstOrNull()?.label,
                endAddress = _waypoints.value.lastOrNull()?.label,
                isEBikeMode = isEBike,
                assistLevel = if (isEBike) telem.activeAssist.name else null,
                energyConsumedWh = energyConsumed?.let { (it * 10).toInt() / 10.0 },
                batteryDrainPercent = batteryDrain?.let { (it * 10).toInt() / 10.0 }
            )
            completedRideSummary.value = summary
        }
    }

    fun saveCompletedRide(customTitle: String? = null) {
        val current = completedRideSummary.value ?: return
        val rideToSave = if (!customTitle.isNullOrBlank()) {
            current.copy(title = customTitle.trim())
        } else {
            current
        }
        viewModelScope.launch {
            repository.saveRide(rideToSave)
            completedRideSummary.value = null
        }
    }

    fun discardCompletedRide() {
        completedRideSummary.value = null
    }

    // --- PERSISTENCE HOOKS ---

    fun saveCurrentRoute(customName: String? = null) {
        val route = activeRoute.value ?: return
        val wps = _waypoints.value
        viewModelScope.launch {
            repository.saveRoute(
                name = customName ?: route.name,
                route = route,
                waypoints = wps
            )
        }
    }

    fun loadSavedRoute(savedRoute: SavedRouteEntity) {
        val type = object : TypeToken<List<RouteWaypoint>>() {}.type
        val decodedWaypoints = runCatching {
            Gson().fromJson<List<RouteWaypoint>>(savedRoute.waypointsJson, type)
        }.getOrNull()

        if (!decodedWaypoints.isNullOrEmpty()) {
            _waypoints.value = decodedWaypoints
            _selectedProfile.value = savedRoute.profile
            showHistorySheet.value = false
            showRoutePlannerSheet.value = true
            calculateRoute()
        }
    }

    fun deleteSavedRoute(id: Long) {
        viewModelScope.launch {
            repository.deleteRoute(id)
        }
    }

    fun renameSavedRoute(id: Long, newName: String) {
        viewModelScope.launch {
            repository.renameRoute(id, newName)
        }
    }

    fun deleteRide(id: Long) {
        viewModelScope.launch {
            repository.deleteRide(id)
        }
    }

    fun renameRide(id: Long, newTitle: String) {
        viewModelScope.launch {
            repository.renameRide(id, newTitle)
        }
    }

    fun previewRideOnMap(ride: RideHistoryEntity) {
        val type = object : TypeToken<List<GeoPoint>>() {}.type
        val coords = runCatching { Gson().fromJson<List<GeoPoint>>(ride.routePolyline, type) }.getOrNull()
        if (!coords.isNullOrEmpty()) {
            val simulatedRoute = RouteResult(
                id = "ride_${ride.id}",
                name = ride.title,
                summary = "${(ride.distanceMeters / 1000.0 * 10).toInt() / 10.0} km • ${ride.durationSeconds / 60} min",
                profile = RoutingProfile.EFFICIENT,
                totalDistanceMeters = ride.distanceMeters,
                totalDurationSeconds = ride.durationSeconds,
                totalEnergyWh = ride.energyConsumedWh ?: 0.0,
                elevationGainM = ride.elevationGainM,
                elevationLossM = ride.elevationLossM,
                maxGradePercent = 0.0,
                avgGradePercent = 0.0,
                coordinates = coords,
                elevationProfile = emptyList(),
                instructions = emptyList(),
                segments = emptyList(),
                batteryDrainPercent = ride.batteryDrainPercent ?: 0.0,
                estimatedBatteryRemainingWh = 0,
                batteryRemainingPercent = 0
            )
            activeRoute.value = simulatedRoute
            showHistorySheet.value = false
            showRoutePlannerSheet.value = false
        }
    }

    fun saveDestination(
        label: String,
        point: GeoPoint,
        address: String,
        category: DestinationCategory = DestinationCategory.FAVORITE
    ) {
        viewModelScope.launch {
            repository.saveDestination(label, point, address, category)
        }
    }

    fun deleteDestination(id: Long) {
        viewModelScope.launch {
            repository.deleteDestination(id)
        }
    }

    fun renameDestination(id: Long, newLabel: String) {
        viewModelScope.launch {
            repository.renameDestination(id, newLabel)
        }
    }

    fun selectDestinationAsWaypoint(targetIndex: Int, destination: SavedDestinationEntity) {
        val point = GeoPoint(destination.lat, destination.lng, destination.ele)
        setWaypoint(targetIndex, point, destination.label)
        showSearchDialogForIndex.value = null
    }

    fun setAssistLevel(level: AssistLevel) {
        physicsEngine.setAssistLevel(level)
        _telemetry.value = _telemetry.value.copy(
            activeAssist = level,
            batteryTelemetry = physicsEngine.getBatteryTelemetry()
        )
    }

    private fun startRideTimer(route: RouteResult) {
        rideTimerJob?.cancel()
        var elapsedSec = 0
        var speedSum = 0.0
        var speedCount = 0
        val recentSpeedsKmh = ArrayDeque<Double>()

        // Planned average speed from the route plan, used only as a fallback when we have no
        // GPS-derived speed yet (e.g. the first tick after starting navigation).
        val plannedAvgSpeedKmh = if (route.totalDurationSeconds > 0) {
            (route.totalDistanceMeters / 1000.0) / (route.totalDurationSeconds / 3600.0)
        } else 0.0

        rideTimerJob = viewModelScope.launch {
            while (_isNavigating.value) {
                delay(1000L)
                elapsedSec++
                val curLoc = locationTracker.locationState.value
                val speed = curLoc.speedKmh

                speedSum += speed
                speedCount++
                val avgSpeed = if (speedCount > 0) speedSum / speedCount else 0.0

                recentSpeedsKmh.addLast(speed)
                if (recentSpeedsKmh.size > RECENT_SPEED_WINDOW) recentSpeedsKmh.removeFirst()
                val recentAvgSpeed = if (recentSpeedsKmh.isNotEmpty()) {
                    recentSpeedsKmh.sum() / recentSpeedsKmh.size
                } else 0.0

                val progress = projectPointOntoRoute(route.coordinates, curLoc.point)
                val distRemaining = progress.remainingDistanceMeters / 1000.0
                val distRidden = max(0.0, (route.totalDistanceMeters / 1000.0) - distRemaining)

                // Prefer a recent-speed average (smooths out GPS jitter) over instantaneous speed;
                // fall back to the route's planned average speed if the rider hasn't moved yet.
                val etaSpeedKmh = when {
                    recentAvgSpeed > 1.0 -> recentAvgSpeed
                    speed > 1.0 -> speed
                    else -> plannedAvgSpeedKmh
                }
                val timeRemaining = if (etaSpeedKmh > 0.1) {
                    ((distRemaining / etaSpeedKmh) * 3600.0).toInt()
                } else {
                    max(0, route.totalDurationSeconds - elapsedSec)
                }

                val batTelem = physicsEngine.getBatteryTelemetry()

                _telemetry.value = LiveRideTelemetry(
                    currentSpeedKmh = (speed * 10).toInt() / 10.0,
                    avgSpeedKmh = (avgSpeed * 10).toInt() / 10.0,
                    maxSpeedKmh = max(_telemetry.value.maxSpeedKmh, speed),
                    distanceRiddenKm = (distRidden * 100).toInt() / 100.0,
                    distanceRemainingKm = (distRemaining * 10).toInt() / 10.0,
                    timeElapsedSeconds = elapsedSec,
                    timeRemainingSeconds = timeRemaining,
                    currentElevationM = curLoc.point.ele,
                    elevationGainedM = max(0.0, curLoc.point.ele - (route.coordinates.firstOrNull()?.ele ?: 20.0)),
                    currentGradePercent = 0.0,
                    motorPowerWatts = (speed * 10).coerceAtMost(physicsEngine.getConfig().motorMaxWatt).toInt(),
                    riderPowerWatts = (speed * 6).coerceAtLeast(40.0).toInt(),
                    activeAssist = physicsEngine.getConfig().activeAssist,
                    batteryTelemetry = batTelem,
                    headingDegrees = curLoc.headingDegrees,
                    isNavigating = true
                )
            }
        }
    }

    private fun handleRiderLocationUpdate(point: GeoPoint, speedKmh: Double, heading: Float) {
        // Immediately update telemetry live speed from GPS
        _telemetry.value = _telemetry.value.copy(
            currentSpeedKmh = speedKmh,
            maxSpeedKmh = max(_telemetry.value.maxSpeedKmh, speedKmh),
            headingDegrees = heading,
            currentElevationM = point.ele
        )

        val route = activeRoute.value ?: return
        if (!_isNavigating.value || route.instructions.isEmpty()) return

        val activeIdx = _currentInstructionIndex.value
        val currentManeuver = route.instructions.getOrNull(activeIdx) ?: return
        val distToManeuver = point.distanceTo(currentManeuver.point).toInt()

        _distanceToNextManeuverMeters.value = distToManeuver

        if (distToManeuver <= 50) {
            audioGuidance.speak("Em 50 metros, ${currentManeuver.text}")
        }

        if (distToManeuver <= 15) {
            if (activeIdx < route.instructions.size - 1) {
                val nextIdx = activeIdx + 1
                _currentInstructionIndex.value = nextIdx
                val nextManeuver = route.instructions[nextIdx]
                _currentInstruction.value = nextManeuver
                audioGuidance.speak(nextManeuver.text, true)
            } else {
                audioGuidance.speak("Você chegou ao seu destino! Parabéns pelo trajeto.")
                stopNavigation()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        locationTracker.stopTracking()
        locationTracker.stopSimulator()
        audioGuidance.shutdown()
    }

    companion object {
        private const val RECENT_SPEED_WINDOW = 12

        /**
         * Projects [point] onto the polyline [coordinates] (the rider's snapped position on the
         * route) and returns the remaining distance from that snapped position to the end of the
         * route, following the polyline rather than a straight line.
         */
        internal fun projectPointOntoRoute(coordinates: List<GeoPoint>, point: GeoPoint): RouteProjection {
            if (coordinates.size < 2) {
                return RouteProjection(remainingDistanceMeters = 0.0, snappedPoint = coordinates.firstOrNull() ?: point)
            }

            var bestSegmentIndex = 0
            var bestProjected = coordinates[0]
            var bestDistanceToPoint = Double.MAX_VALUE

            for (i in 0 until coordinates.size - 1) {
                val a = coordinates[i]
                val b = coordinates[i + 1]
                val t = projectionFraction(a, b, point)
                val projected = interpolatePoint(a, b, t)
                val distanceToPoint = point.distanceTo(projected)
                if (distanceToPoint < bestDistanceToPoint) {
                    bestDistanceToPoint = distanceToPoint
                    bestSegmentIndex = i
                    bestProjected = projected
                }
            }

            var remaining = bestProjected.distanceTo(coordinates[bestSegmentIndex + 1])
            for (i in (bestSegmentIndex + 1) until coordinates.size - 1) {
                remaining += coordinates[i].distanceTo(coordinates[i + 1])
            }

            return RouteProjection(remainingDistanceMeters = remaining, snappedPoint = bestProjected)
        }

        /** Fraction (0..1) along segment a->b closest to p, using a local planar approximation. */
        private fun projectionFraction(a: GeoPoint, b: GeoPoint, p: GeoPoint): Double {
            val metersPerDegLat = 111320.0
            val metersPerDegLng = 111320.0 * Math.cos(Math.toRadians(a.lat))

            val bx = (b.lng - a.lng) * metersPerDegLng
            val by = (b.lat - a.lat) * metersPerDegLat
            val px = (p.lng - a.lng) * metersPerDegLng
            val py = (p.lat - a.lat) * metersPerDegLat

            val lenSq = bx * bx + by * by
            if (lenSq < 1e-9) return 0.0

            val t = (px * bx + py * by) / lenSq
            return t.coerceIn(0.0, 1.0)
        }

        private fun interpolatePoint(a: GeoPoint, b: GeoPoint, t: Double): GeoPoint {
            return GeoPoint(
                lat = a.lat + (b.lat - a.lat) * t,
                lng = a.lng + (b.lng - a.lng) * t,
                ele = a.ele + (b.ele - a.ele) * t
            )
        }
    }

    internal data class RouteProjection(
        val remainingDistanceMeters: Double,
        val snappedPoint: GeoPoint
    )
}

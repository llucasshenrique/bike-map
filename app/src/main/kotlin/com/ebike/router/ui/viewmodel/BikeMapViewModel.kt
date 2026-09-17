package com.ebike.router.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ebike.router.model.*
import com.ebike.router.navigation.RouteDeviation
import com.ebike.router.physics.EBikePhysicsEngine
import com.ebike.router.service.AudioGuidanceService
import com.ebike.router.service.GeocodingService
import com.ebike.router.service.GraphRouterService
import com.ebike.router.service.LocationTrackerService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

class BikeMapViewModel(application: Application) : AndroidViewModel(application) {
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

    // Off-route detection & automatic rerouting
    private val _isRerouting = MutableStateFlow(false)
    val isRerouting: StateFlow<Boolean> = _isRerouting.asStateFlow()

    private var lastMatchedCoordIndex: Int? = null
    private var offRouteSinceMillis: Long? = null
    private var consecutiveOffRouteSamples: Int = 0
    private var rerouteCooldownUntilMillis: Long = 0L

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
        resetOffRouteTracking()

        audioGuidance.speak("Navegação iniciada. ${targetRoute.instructions.firstOrNull()?.text ?: ""}", true)
        startRideTimer(targetRoute)
    }

    fun startSimulation(speedMultiplier: Int = 2) {
        val route = activeRoute.value ?: return
        startNavigation(route)
        locationTracker.startSimulator(route, speedMultiplier)
    }

    fun stopNavigation() {
        _isNavigating.value = false
        rideTimerJob?.cancel()
        rideTimerJob = null
        locationTracker.stopSimulator()
        audioGuidance.speak("Navegação finalizada.")
        resetOffRouteTracking()
    }

    private fun resetOffRouteTracking() {
        lastMatchedCoordIndex = null
        offRouteSinceMillis = null
        consecutiveOffRouteSamples = 0
        _isRerouting.value = false
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

        rideTimerJob = viewModelScope.launch {
            while (_isNavigating.value) {
                delay(1000L)
                elapsedSec++
                val curLoc = locationTracker.locationState.value
                val speed = curLoc.speedKmh

                speedSum += speed
                speedCount++
                val avgSpeed = if (speedCount > 0) speedSum / speedCount else 0.0

                val progressRatio = min(1.0, elapsedSec / max(1.0, route.totalDurationSeconds.toDouble()))
                val distRidden = (route.totalDistanceMeters * progressRatio) / 1000.0
                val distRemaining = max(0.0, (route.totalDistanceMeters / 1000.0) - distRidden)
                val timeRemaining = max(0, route.totalDurationSeconds - elapsedSec)

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
                return
            }
        }

        checkOffRouteAndReroute(point, route)
    }

    // --- OFF-ROUTE DETECTION & AUTOMATIC REROUTING ---

    private fun checkOffRouteAndReroute(point: GeoPoint, route: RouteResult) {
        if (_isRerouting.value) return

        val now = System.currentTimeMillis()
        if (now < rerouteCooldownUntilMillis) return

        val nearest = RouteDeviation.findNearestPointOnRoute(
            point = point,
            routeCoordinates = route.coordinates,
            searchAroundIndex = lastMatchedCoordIndex
        ) ?: return

        lastMatchedCoordIndex = nearest.segmentIndex

        val accuracyMeters = locationTracker.locationState.value.accuracyMeters.toDouble()
        val threshold = max(OFF_ROUTE_THRESHOLD_METERS, accuracyMeters * 2.5)

        if (nearest.distanceMeters <= threshold) {
            offRouteSinceMillis = null
            consecutiveOffRouteSamples = 0
            return
        }

        consecutiveOffRouteSamples++
        if (offRouteSinceMillis == null) {
            offRouteSinceMillis = now
        }

        val sustainedForMillis = now - (offRouteSinceMillis ?: now)
        val isConfirmedOffRoute = sustainedForMillis >= OFF_ROUTE_CONFIRM_MS &&
            consecutiveOffRouteSamples >= MIN_CONSECUTIVE_OFF_ROUTE_SAMPLES

        if (isConfirmedOffRoute) {
            triggerReroute(point, route)
        }
    }

    private fun triggerReroute(fromPoint: GeoPoint, staleRoute: RouteResult) {
        val destination = staleRoute.coordinates.lastOrNull() ?: return

        _isRerouting.value = true
        offRouteSinceMillis = null
        consecutiveOffRouteSamples = 0

        audioGuidance.speak("Você saiu da rota. Recalculando...", true)

        viewModelScope.launch {
            try {
                val routes = routerService.calculateMultipleRoutes(
                    listOf(fromPoint, destination),
                    _selectedProfile.value
                )
                val newRoute = routes.firstOrNull()
                if (newRoute != null) {
                    activeRoute.value = newRoute
                    _currentInstructionIndex.value = 0
                    _currentInstruction.value = newRoute.instructions.firstOrNull()
                    lastMatchedCoordIndex = null
                    rerouteCooldownUntilMillis = System.currentTimeMillis() + REROUTE_COOLDOWN_MS
                    audioGuidance.speak(newRoute.instructions.firstOrNull()?.text ?: "Nova rota calculada.", true)
                }
            } finally {
                _isRerouting.value = false
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
        private const val OFF_ROUTE_THRESHOLD_METERS = 35.0
        private const val OFF_ROUTE_CONFIRM_MS = 6000L
        private const val MIN_CONSECUTIVE_OFF_ROUTE_SAMPLES = 3
        private const val REROUTE_COOLDOWN_MS = 5000L
    }
}

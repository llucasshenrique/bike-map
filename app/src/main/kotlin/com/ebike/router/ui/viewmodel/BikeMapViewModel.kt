package com.ebike.router.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
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
import com.ebike.router.navigation.RouteDeviation
import com.ebike.router.physics.EBikePhysicsEngine
import com.ebike.router.service.AudioGuidanceService
import com.ebike.router.service.GeocodingService
import com.ebike.router.service.GraphRouterService
import com.ebike.router.service.LocationTrackerService
import com.ebike.router.service.RiderLocationState
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
    val routerService = GraphRouterService(physicsEngine, application)
    val geocodingService = GeocodingService()
    val audioGuidance = AudioGuidanceService(application)

    // Service binding state
    private var serviceConnection: ServiceConnection? = null
    private val _trackerService = MutableStateFlow<LocationTrackerService?>(null)
    val trackerService: StateFlow<LocationTrackerService?> = _trackerService.asStateFlow()

    // Location state exposed for UI
    private val _locationState = MutableStateFlow(RiderLocationState())
    val locationState: StateFlow<RiderLocationState> = _locationState.asStateFlow()

    // Backward-compatible locationTracker accessor for existing UI calls
    inner class LocationTrackerCompat {
        val locationState: StateFlow<RiderLocationState> get() = this@BikeMapViewModel.locationState
        fun startTracking() {
            val app = getApplication<Application>()
            val intent = Intent(app, LocationTrackerService::class.java).apply {
                action = LocationTrackerService.ACTION_START_TRACKING
            }
            ContextCompat.startForegroundService(app, intent)
            _trackerService.value?.startTracking()
        }
        fun stopTracking() {
            val app = getApplication<Application>()
            val intent = Intent(app, LocationTrackerService::class.java).apply {
                action = LocationTrackerService.ACTION_STOP_TRACKING
            }
            app.startService(intent)
            _trackerService.value?.stopTracking()
        }
        fun refreshCurrentLocation() {
            _trackerService.value?.refreshCurrentLocation()
        }
        fun startSimulator(route: RouteResult, speedMultiplier: Int = 2) {
            _trackerService.value?.startSimulator(route, speedMultiplier)
        }
        fun stopSimulator() {
            _trackerService.value?.stopSimulator()
        }
    }
    val locationTracker = LocationTrackerCompat()

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
    val showRoutePlannerSheet = MutableStateFlow(false)
    val showSearchDialogForIndex = MutableStateFlow<Int?>(null)
    val showCockpitDialog = MutableStateFlow(false)
    val showRangeCircle = MutableStateFlow(true)
    val showHistorySheet = MutableStateFlow(false)
    val completedRideSummary = MutableStateFlow<RideHistoryEntity?>(null)

    private var rideStartTimeMillis: Long = 0L
    private var rideTimerJob: Job? = null
    private val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    init {
        bindService(application)
    }

    fun bindService(context: Context) {
        if (serviceConnection != null) return

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as? LocationTrackerService.LocalBinder
                val svc = binder?.getService()
                _trackerService.value = svc

                svc?.let { s ->
                    viewModelScope.launch {
                        s.locationState.collect { loc ->
                            _locationState.value = loc
                            val r = activeRoute.value
                            if (_isNavigating.value && r != null) {
                                checkOffRouteAndReroute(loc.point, r)
                            }
                        }
                    }
                    viewModelScope.launch {
                        s.telemetry.collect { telem ->
                            _telemetry.value = telem
                        }
                    }
                    viewModelScope.launch {
                        s.isNavigating.collect { nav ->
                            _isNavigating.value = nav
                        }
                    }
                    viewModelScope.launch {
                        s.currentInstruction.collect { instruction ->
                            _currentInstruction.value = instruction
                        }
                    }
                    viewModelScope.launch {
                        s.distanceToNextManeuverMeters.collect { dist ->
                            _distanceToNextManeuverMeters.value = dist
                        }
                    }
                    viewModelScope.launch {
                        s.currentInstructionIndex.collect { idx ->
                            _currentInstructionIndex.value = idx
                        }
                    }
                    viewModelScope.launch {
                        s.activeRoute.collect { r ->
                            if (r != null && activeRoute.value == null) {
                                activeRoute.value = r
                            }
                        }
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                _trackerService.value = null
            }
        }

        val intent = Intent(context, LocationTrackerService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        serviceConnection = connection
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
            val userPoint = _locationState.value.point
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
        val userPoint = _locationState.value.point
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
        rideStartTimeMillis = System.currentTimeMillis()

        _trackerService.value?.startNavigation(targetRoute)
            ?: audioGuidance.speak("Navegação iniciada. ${targetRoute.instructions.firstOrNull()?.text ?: ""}", true)
    }

    fun startSimulation(speedMultiplier: Int = 2) {
        val route = activeRoute.value ?: return
        startNavigation(route)
        _trackerService.value?.startSimulator(route, speedMultiplier)
    }

    fun stopNavigation(savePrompt: Boolean = true) {
        val telem = _telemetry.value
        val curRoute = activeRoute.value
        val elapsed = telem.timeElapsedSeconds
        val distKm = telem.distanceRiddenKm

        _isNavigating.value = false
        _trackerService.value?.stopNavigation()
        audioGuidance.speak("Navegação finalizada.")
        resetOffRouteTracking()

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
        _trackerService.value?.setAssistLevel(level)
        _telemetry.value = _telemetry.value.copy(
            activeAssist = level,
            batteryTelemetry = physicsEngine.getBatteryTelemetry()
        )
    }

    fun setAudioMuted(muted: Boolean) {
        audioGuidance.isMuted = muted
        _trackerService.value?.setAudioMuted(muted)
    }

    private fun resetOffRouteTracking() {
        lastMatchedCoordIndex = null
        offRouteSinceMillis = null
        consecutiveOffRouteSamples = 0
        _isRerouting.value = false
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

        val accuracyMeters = _locationState.value.accuracyMeters.toDouble()
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
                    _trackerService.value?.startNavigation(newRoute)
                    audioGuidance.speak(newRoute.instructions.firstOrNull()?.text ?: "Nova rota calculada.", true)
                }
            } finally {
                _isRerouting.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        serviceConnection?.let { conn ->
            try {
                getApplication<Application>().unbindService(conn)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            serviceConnection = null
        }
        audioGuidance.shutdown()
    }

    companion object {
        private const val OFF_ROUTE_THRESHOLD_METERS = 35.0
        private const val OFF_ROUTE_CONFIRM_MS = 6000L
        private const val MIN_CONSECUTIVE_OFF_ROUTE_SAMPLES = 3
        private const val REROUTE_COOLDOWN_MS = 5000L
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

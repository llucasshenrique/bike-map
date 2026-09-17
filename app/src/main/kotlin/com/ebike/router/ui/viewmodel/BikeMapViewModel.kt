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
import com.ebike.router.model.*
import com.ebike.router.physics.EBikePhysicsEngine
import com.ebike.router.service.AudioGuidanceService
import com.ebike.router.service.GeocodingService
import com.ebike.router.service.GraphRouterService
import com.ebike.router.service.LocationTrackerService
import com.ebike.router.service.RiderLocationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max

class BikeMapViewModel(application: Application) : AndroidViewModel(application) {
    val physicsEngine = EBikePhysicsEngine()
    val routerService = GraphRouterService(physicsEngine)
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

        _trackerService.value?.startNavigation(targetRoute)
            ?: audioGuidance.speak("Navegação iniciada. ${targetRoute.instructions.firstOrNull()?.text ?: ""}", true)
    }

    fun startSimulation(speedMultiplier: Int = 2) {
        val route = activeRoute.value ?: return
        startNavigation(route)
        _trackerService.value?.startSimulator(route, speedMultiplier)
    }

    fun stopNavigation() {
        _isNavigating.value = false
        _trackerService.value?.stopNavigation()
        audioGuidance.speak("Navegação finalizada.")
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
}

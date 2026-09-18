package com.ebike.router

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.max
import com.ebike.router.service.LocationTrackerService
import com.ebike.router.ui.components.*
import com.ebike.router.ui.theme.*
import com.ebike.router.ui.viewmodel.BikeMapViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: BikeMapViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (fineGranted || coarseGranted) {
            startAndBindTrackerService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()

        setContent {
            EBikeMapTheme {
                MainScreen(viewModel)
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(missing.toTypedArray())
        } else {
            startAndBindTrackerService()
        }
    }

    private fun startAndBindTrackerService() {
        val serviceIntent = Intent(this, LocationTrackerService::class.java).apply {
            action = LocationTrackerService.ACTION_START_TRACKING
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        viewModel.bindService(this)
    }
}

@Composable
fun MainScreen(viewModel: BikeMapViewModel) {
    var showSplash by remember { mutableStateOf(true) }

    val isNavigating by viewModel.isNavigating.collectAsState()
    val showPlannerSheet by viewModel.showRoutePlannerSheet.collectAsState()
    val searchDialogIndex by viewModel.showSearchDialogForIndex.collectAsState()
    val showCockpit by viewModel.showCockpitDialog.collectAsState()
    val showSettings by viewModel.showSettingsDialog.collectAsState()
    val isEBike by viewModel.isEBikeMode.collectAsState()
    val bikePreferences by viewModel.bikePreferences.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val activeRoute by viewModel.activeRoute.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val waypoints by viewModel.waypoints.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()

    val showHistorySheet by viewModel.showHistorySheet.collectAsState()
    val completedRideSummary by viewModel.completedRideSummary.collectAsState()
    val rideHistory by viewModel.rideHistory.collectAsState()
    val savedRoutes by viewModel.savedRoutes.collectAsState()
    val savedDestinations by viewModel.savedDestinations.collectAsState()

    var showMapClickMenuForPoint by remember { mutableStateOf<com.ebike.router.model.GeoPoint?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. BASE MAP LAYER (Osmdroid with elevation-colored polylines & multi-stop pins)
        OsmdroidMapView(
            viewModel = viewModel,
            onMapClick = { clickedPoint ->
                showMapClickMenuForPoint = clickedPoint
            }
        )

        // 2. FLOATING TOP BAR (Search & Quick Status)
        if (!isNavigating) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Search destination pill
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clickable { viewModel.showSearchDialogForIndex.value = max(1, waypoints.size - 1) },
                    shape = RoundedCornerShape(24.dp),
                    color = Slate900.copy(alpha = 0.95f),
                    shadowElevation = 8.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Slate700)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Buscar", tint = CyanGlow, modifier = Modifier.size(20.dp))
                        Text(
                            text = "Para onde vamos pedalar?",
                            color = Slate400,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }

                // History & Saved Pill
                Surface(
                    modifier = Modifier
                        .height(48.dp)
                        .clickable { viewModel.showHistorySheet.value = true },
                    shape = RoundedCornerShape(24.dp),
                    color = Slate900.copy(alpha = 0.95f),
                    shadowElevation = 8.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Slate700)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Bookmark, contentDescription = "Salvos & Histórico", tint = AmberWarning, modifier = Modifier.size(18.dp))
                        Text(
                            text = "Salvos",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                // Battery / Cockpit Pill (Only shown when E-Bike mode is ON)
                if (isEBike && telemetry.batteryTelemetry != null) {
                    val bat = telemetry.batteryTelemetry!!
                    Surface(
                        modifier = Modifier
                            .height(48.dp)
                            .clickable { viewModel.showCockpitDialog.value = true },
                        shape = RoundedCornerShape(24.dp),
                        color = Slate900.copy(alpha = 0.95f),
                        shadowElevation = 8.dp,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Slate700)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(Icons.Default.ElectricBike, contentDescription = null, tint = EmeraldGreen, modifier = Modifier.size(17.dp))
                            Text(
                                text = "${bat.percentage}%",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = EmeraldGreen
                            )
                            Surface(
                                color = CyanPrimary.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "EST.",
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Black,
                                    color = CyanGlow
                                )
                            }
                        }
                    }
                }
                Surface(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable { viewModel.showSettingsDialog.value = true },
                    shape = CircleShape,
                    color = Slate900.copy(alpha = 0.95f),
                    shadowElevation = 8.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Slate700)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Configurações",
                            tint = if (isEBike) CyanGlow else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // 3. FLOATING GRADE LEGEND (Bottom-Left when route is active and not navigating)
        if (activeRoute != null && !isNavigating && !showPlannerSheet) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 180.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Slate900.copy(alpha = 0.92f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("INCLINAÇÃO:", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Slate400)
                    Spacer(modifier = Modifier.height(3.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("🔵 <0%", fontSize = 10.sp, color = SlopeDownhill, fontWeight = FontWeight.Bold)
                        Text("🟢 0-3%", fontSize = 10.sp, color = SlopeFlat, fontWeight = FontWeight.Bold)
                        Text("🟡 3-7%", fontSize = 10.sp, color = SlopeModerate, fontWeight = FontWeight.Bold)
                        Text("🔴 >7%", fontSize = 10.sp, color = SlopeSteep, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 4. ⭐ ALWAYS-VISIBLE RECENTER BUTTON ⭐
        // Positioned prominently on the right side, floating above any bottom controls or sheets
        val recenterBottomPadding = if (isNavigating) 180.dp else if (showPlannerSheet) 90.dp else 160.dp
        FloatingActionButton(
            onClick = { viewModel.recenterMap() },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = recenterBottomPadding)
                .size(54.dp)
                .border(2.dp, CyanGlow, CircleShape),
            containerColor = Slate900,
            contentColor = CyanGlow,
            shape = CircleShape,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 10.dp)
        ) {
            Icon(
                Icons.Default.MyLocation,
                contentDescription = "Recentralizar no GPS",
                modifier = Modifier.size(26.dp)
            )
        }

        // 5. BOTTOM CARDS / NAVIGATION OVERLAY
        if (isNavigating) {
            // Full Turn-by-Turn HUD
            val curInstruction by viewModel.currentInstruction.collectAsState()
            val distToNextManeuver by viewModel.distanceToNextManeuverMeters.collectAsState()
            val isRerouting by viewModel.isRerouting.collectAsState()

            NavigationHud(
                instruction = curInstruction,
                distanceToNextManeuverMeters = distToNextManeuver,
                telemetry = telemetry,
                isMuted = viewModel.audioGuidance.isMuted,
                isRerouting = isRerouting,
                onToggleMute = {
                    viewModel.setAudioMuted(!viewModel.audioGuidance.isMuted)
                },
                onStopNavigation = { viewModel.stopNavigation() },
                onOpenCockpit = { viewModel.showCockpitDialog.value = true },
                isEBikeMode = isEBike
            )
        } else if (!showPlannerSheet) {
            // COMPACT BOTTOM CONTROLS (When Planner is minimized)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // If route already calculated: Quick Action Preview Bar
                activeRoute?.let { route ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = Slate900.copy(alpha = 0.95f)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(text = route.name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                                    val subText = if (isEBike) {
                                        "${(route.totalDistanceMeters / 1000.0 * 10).toInt() / 10.0} km • ${route.totalDurationSeconds / 60} min • -${route.totalEnergyWh} Wh"
                                    } else {
                                        "${(route.totalDistanceMeters / 1000.0 * 10).toInt() / 10.0} km • ${route.totalDurationSeconds / 60} min • ▲ ${route.elevationGainM}m"
                                    }
                                    Text(
                                        text = subText,
                                        color = CyanGlow,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.showRoutePlannerSheet.value = true },
                                    modifier = Modifier.size(36.dp).background(Slate800, CircleShape)
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = "Editar", tint = Slate400, modifier = Modifier.size(18.dp))
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { viewModel.startNavigation(route) },
                                    modifier = Modifier.weight(1.2f).height(46.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen, contentColor = Slate950),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("NAVEGAR", fontWeight = FontWeight.Black)
                                }

                                Button(
                                    onClick = { viewModel.startSimulation(2) },
                                    modifier = Modifier.weight(1f).height(46.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Slate800, contentColor = CyanGlow),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("SIMULAR", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // If no route calculated yet: Floating Explorer Buttons
                if (activeRoute == null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { viewModel.showRoutePlannerSheet.value = true },
                            modifier = Modifier.weight(1.3f).height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = Slate950),
                            shape = RoundedCornerShape(16.dp),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
                        ) {
                            Icon(Icons.Default.Route, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("PLANEJAR ROTA", fontWeight = FontWeight.Black, fontSize = 13.sp)
                        }

                        Button(
                            onClick = { viewModel.showCockpitDialog.value = true },
                            modifier = Modifier.weight(1f).height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Slate900, contentColor = EmeraldGreen),
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Slate700),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
                        ) {
                            Icon(Icons.Default.DirectionsBike, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("COCKPIT", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // 6. FULL ROUTE PLANNER SHEET (When Expanded)
        if (!isNavigating && showPlannerSheet) {
            val availableRoutes by viewModel.availableRoutes.collectAsState()
            val selectedRouteIdx by viewModel.selectedRouteIndex.collectAsState()
            val selectedProfile by viewModel.selectedProfile.collectAsState()
            val isCalculating by viewModel.isCalculating.collectAsState()
            val pickingIdx by viewModel.activePickingWaypointIndex.collectAsState()

            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                RoutePlannerSheet(
                    waypoints = waypoints,
                    activePickingIdx = pickingIdx,
                    availableRoutes = availableRoutes,
                    selectedRouteIdx = selectedRouteIdx,
                    activeRoute = activeRoute,
                    selectedProfile = selectedProfile,
                    isCalculating = isCalculating,
                    onSelectProfile = { viewModel.setProfile(it) },
                    onPickOnMap = { viewModel.setPickingWaypointIndex(it) },
                    onOpenSearch = { viewModel.showSearchDialogForIndex.value = it },
                    onMoveWaypoint = { from, to -> viewModel.moveWaypoint(from, to) },
                    onRemoveWaypoint = { viewModel.removeWaypoint(it) },
                    onAddWaypoint = { viewModel.addWaypoint() },
                    onReverseWaypoints = { viewModel.reverseWaypoints() },
                    onClearAll = { viewModel.clearAll() },
                    onCalculateRoute = { viewModel.calculateRoute() },
                    onSelectRoute = { viewModel.selectRoute(it) },
                    onStartNavigation = { viewModel.startNavigation(it) },
                    onStartSimulation = { viewModel.startSimulation(2) },
                    onSaveRoute = { viewModel.saveCurrentRoute() },
                    onClose = { viewModel.showRoutePlannerSheet.value = false },
                    estimatedOfflineTiles = activeRoute?.let { viewModel.estimateOfflineTileCount(it) } ?: 0,
                    downloadProgress = downloadProgress,
                    onDownloadOfflineMap = { viewModel.downloadOfflineMap(it) },
                    isEBikeMode = isEBike
                )
            }
        }

        // 7. MAP TAP CONTEXT MENU
        showMapClickMenuForPoint?.let { clickedPt ->
            AlertDialog(
                onDismissRequest = { showMapClickMenuForPoint = null },
                title = { Text("Local Selecionado", fontWeight = FontWeight.Bold, color = Color.White) },
                text = {
                    Text(
                        "Coordenadas: ${(clickedPt.lat * 1000).toInt() / 1000.0}, ${(clickedPt.lng * 1000).toInt() / 1000.0}",
                        color = Slate400
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.setWaypoint(0, clickedPt, "Ponto de Partida (A)")
                            showMapClickMenuForPoint = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen, contentColor = Slate950)
                    ) {
                        Text("Definir como Início (A)")
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = {
                                viewModel.addWaypoint(clickedPt)
                                showMapClickMenuForPoint = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AmberWarning, contentColor = Slate950)
                        ) {
                            Text("+ Parada")
                        }
                        Button(
                            onClick = {
                                val destIdx = max(1, waypoints.size - 1)
                                viewModel.setWaypoint(destIdx, clickedPt, "Destino")
                                showMapClickMenuForPoint = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = RedSteep, contentColor = Color.White)
                        ) {
                            Text("Destino")
                        }
                        Button(
                            onClick = {
                                viewModel.saveDestination(
                                    label = "Ponto Marcado",
                                    point = clickedPt,
                                    address = "${(clickedPt.lat * 1000).toInt() / 1000.0}, ${(clickedPt.lng * 1000).toInt() / 1000.0}"
                                )
                                showMapClickMenuForPoint = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = Slate950)
                        ) {
                            Text("★ Salvar")
                        }
                    }
                },
                containerColor = Slate900
            )
        }

        // 8. SEARCH DIALOG
        searchDialogIndex?.let { targetIdx ->
            val targetWp = waypoints.getOrNull(targetIdx)
            WaypointSearchDialog(
                targetIndex = targetIdx,
                targetLetter = targetWp?.letter ?: "A",
                searchResults = searchResults,
                isSearching = isSearching,
                onSearch = { viewModel.searchPlaces(it) },
                onSelectResult = { viewModel.selectSearchResult(targetIdx, it) },
                onUseGps = { viewModel.useGpsForWaypoint(targetIdx) },
                savedDestinations = savedDestinations,
                onSelectSavedDestination = { viewModel.selectDestinationAsWaypoint(targetIdx, it) },
                onSaveSearchResultAsFavorite = { viewModel.saveDestination(it.name, it.point, it.subText) },
                onDismiss = { viewModel.showSearchDialogForIndex.value = null }
            )
        }

        // 9. RIDE HISTORY & SAVED ROUTES SHEET
        if (showHistorySheet) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                RideHistorySheet(
                    rides = rideHistory,
                    savedRoutes = savedRoutes,
                    savedDestinations = savedDestinations,
                    onPreviewRide = { viewModel.previewRideOnMap(it) },
                    onRenameRide = { id, name -> viewModel.renameRide(id, name) },
                    onDeleteRide = { viewModel.deleteRide(it) },
                    onLoadRoute = { viewModel.loadSavedRoute(it) },
                    onRenameRoute = { id, name -> viewModel.renameSavedRoute(id, name) },
                    onDeleteRoute = { viewModel.deleteSavedRoute(it) },
                    onSelectDestination = {
                        val targetIdx = max(1, waypoints.size - 1)
                        viewModel.selectDestinationAsWaypoint(targetIdx, it)
                        viewModel.showHistorySheet.value = false
                    },
                    onRenameDestination = { id, name -> viewModel.renameDestination(id, name) },
                    onDeleteDestination = { viewModel.deleteDestination(it) },
                    onClose = { viewModel.showHistorySheet.value = false }
                )
            }
        }

        // 10. POST-RIDE SUMMARY DIALOG
        completedRideSummary?.let { ride ->
            RideSummaryDialog(
                ride = ride,
                onSave = { customTitle -> viewModel.saveCompletedRide(customTitle) },
                onDiscard = { viewModel.discardCompletedRide() }
            )
        }

        // 11. COCKPIT DIALOG
        if (showCockpit) {
            TelemetryCockpitDialog(
                telemetry = telemetry,
                onSelectAssist = { viewModel.setAssistLevel(it) },
                onDismiss = { viewModel.showCockpitDialog.value = false },
                isEBikeMode = isEBike,
                maxAssistSpeedKmh = bikePreferences.maxAssistSpeedKmh
            )
        }

        // 10. SETTINGS DIALOG
        if (showSettings) {
            SettingsDialog(
                preferences = bikePreferences,
                onUpdateEBikeMode = { viewModel.updateEBikeMode(it) },
                onSaveSpecs = { cap, spd, bWeight, rWeight, regen ->
                    viewModel.updateBikeSpecs(cap, spd, bWeight, rWeight, regen)
                },
                onUpdateCurrentBatteryWh = { viewModel.updateCurrentBatteryWh(it) },
                onDismiss = { viewModel.showSettingsDialog.value = false }
            )
        }

        // 10. SPLASH SCREEN OVERLAY (Smooth launch animation)
        if (showSplash) {
            SplashScreenOverlay(
                onSplashFinished = { showSplash = false }
            )
        }
    }
}

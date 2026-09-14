package com.ebike.router

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
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
import com.ebike.router.ui.components.*
import com.ebike.router.ui.theme.*
import com.ebike.router.ui.viewmodel.BikeMapViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: BikeMapViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            viewModel.locationTracker.startTracking()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkLocationPermissions()

        setContent {
            EBikeMapTheme {
                MainScreen(viewModel)
            }
        }
    }

    private fun checkLocationPermissions() {
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineLocationGranted) {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            viewModel.locationTracker.startTracking()
        }
    }
}

@Composable
fun MainScreen(viewModel: BikeMapViewModel) {
    val isNavigating by viewModel.isNavigating.collectAsState()
    val showPlannerSheet by viewModel.showRoutePlannerSheet.collectAsState()
    val searchDialogIndex by viewModel.showSearchDialogForIndex.collectAsState()
    val showCockpit by viewModel.showCockpitDialog.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val activeRoute by viewModel.activeRoute.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val waypoints by viewModel.waypoints.collectAsState()

    var showMapClickMenuForPoint by remember { mutableStateOf<com.ebike.router.model.GeoPoint?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        // Base OpenStreetMap Layer
        OsmdroidMapView(
            viewModel = viewModel,
            onMapClick = { clickedPoint ->
                showMapClickMenuForPoint = clickedPoint
            }
        )

        // Floating Map Controls (Top-Right)
        if (!isNavigating) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Route Planner Toggle
                FloatingActionButton(
                    onClick = { viewModel.showRoutePlannerSheet.value = !showPlannerSheet },
                    containerColor = Slate900,
                    contentColor = CyanGlow,
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.Route, contentDescription = "Rotas")
                }

                // Cockpit Button
                FloatingActionButton(
                    onClick = { viewModel.showCockpitDialog.value = true },
                    containerColor = Slate900,
                    contentColor = EmeraldGreen,
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.DirectionsBike, contentDescription = "Cockpit")
                }
            }
        }

        // Grade Legend Bar (Bottom-Left when route is active and not navigating)
        if (activeRoute != null && !isNavigating) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Slate900.copy(alpha = 0.9f))
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("INCLINAÇÃO / GRAU:", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Slate400)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("🔵 Descida", fontSize = 10.sp, color = SlopeDownhill, fontWeight = FontWeight.Bold)
                        Text("🟢 0-3% Plano", fontSize = 10.sp, color = SlopeFlat, fontWeight = FontWeight.Bold)
                        Text("🟡 3-7% Médio", fontSize = 10.sp, color = SlopeModerate, fontWeight = FontWeight.Bold)
                        Text("🔴 >7% Subida", fontSize = 10.sp, color = SlopeSteep, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Navigation HUD (When Navigating)
        if (isNavigating) {
            val curInstruction by viewModel.currentInstruction.collectAsState()
            val distToNextManeuver by viewModel.distanceToNextManeuverMeters.collectAsState()

            NavigationHud(
                instruction = curInstruction,
                distanceToNextManeuverMeters = distToNextManeuver,
                telemetry = telemetry,
                isMuted = viewModel.audioGuidance.isMuted,
                onToggleMute = {
                    viewModel.audioGuidance.isMuted = !viewModel.audioGuidance.isMuted
                },
                onStopNavigation = { viewModel.stopNavigation() },
                onOpenCockpit = { viewModel.showCockpitDialog.value = true }
            )
        }

        // Route Planner Sheet (When Not Navigating and Visible)
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
                    onClose = { viewModel.showRoutePlannerSheet.value = false }
                )
            }
        }

        // Map Tap Location Context Menu
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
                    }
                },
                containerColor = Slate900
            )
        }

        // Search Dialog
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
                onDismiss = { viewModel.showSearchDialogForIndex.value = null }
            )
        }

        // Cockpit Bike Computer Dialog
        if (showCockpit) {
            TelemetryCockpitDialog(
                telemetry = telemetry,
                onSelectAssist = { viewModel.setAssistLevel(it) },
                onDismiss = { viewModel.showCockpitDialog.value = false }
            )
        }
    }
}

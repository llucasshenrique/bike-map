package com.ebike.router.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ebike.router.model.RouteResult
import com.ebike.router.model.RouteWaypoint
import com.ebike.router.model.RoutingProfile
import com.ebike.router.ui.theme.*

@Composable
fun RoutePlannerSheet(
    waypoints: List<RouteWaypoint>,
    activePickingIdx: Int?,
    availableRoutes: List<RouteResult>,
    selectedRouteIdx: Int,
    activeRoute: RouteResult?,
    selectedProfile: RoutingProfile,
    isCalculating: Boolean,
    onSelectProfile: (RoutingProfile) -> Unit,
    onPickOnMap: (Int) -> Unit,
    onOpenSearch: (Int) -> Unit,
    onMoveWaypoint: (Int, Int) -> Unit,
    onRemoveWaypoint: (Int) -> Unit,
    onAddWaypoint: () -> Unit,
    onReverseWaypoints: () -> Unit,
    onClearAll: () -> Unit,
    onCalculateRoute: () -> Unit,
    onSelectRoute: (Int) -> Unit,
    onStartNavigation: (RouteResult) -> Unit,
    onStartSimulation: (RouteResult) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.85f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900.copy(alpha = 0.96f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.size(8.dp).background(EmeraldGreen, CircleShape))
                    Text("Planejador E-Bike", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Surface(
                        color = EmeraldGreen.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            "MULTI-PARADAS",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = EmeraldGreen
                        )
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Fechar", tint = Slate400)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Profile Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Slate800, RoundedCornerShape(12.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                RoutingProfile.values().forEach { profile ->
                    val isSelected = selectedProfile == profile
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(if (isSelected) Slate700 else Color.Transparent, RoundedCornerShape(8.dp))
                            .clickable { onSelectProfile(profile) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(profile.icon, fontSize = 14.sp)
                            Text(
                                text = profile.label.split(" ").first(),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) CyanGlow else Slate400
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Waypoints List
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                waypoints.forEachIndexed { idx, wp ->
                    val isFirst = idx == 0
                    val isLast = idx == waypoints.size - 1
                    val badgeColor = if (isFirst) EmeraldGreen else if (isLast) RedSteep else AmberWarning
                    val isPicking = activePickingIdx == idx

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isPicking) CyanPrimary.copy(alpha = 0.15f) else Slate800.copy(alpha = 0.6f),
                                RoundedCornerShape(12.dp)
                            )
                            .border(
                                width = if (isPicking) 1.5.dp else 1.dp,
                                color = if (isPicking) CyanGlow else Slate700,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Badge
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(badgeColor, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = wp.letter,
                                fontWeight = FontWeight.Black,
                                fontSize = 13.sp,
                                color = if (isLast) Color.White else Slate950
                            )
                        }

                        // Label / Click to search
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onOpenSearch(idx) }
                        ) {
                            Text(
                                text = if (isFirst) "PONTO DE PARTIDA" else if (isLast) "DESTINO" else "PARADA ${wp.letter}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Slate400
                            )
                            Text(
                                text = wp.point?.let { wp.label } ?: "Buscar endereço ou tocar no mapa...",
                                fontSize = 13.sp,
                                fontWeight = if (wp.point != null) FontWeight.Bold else FontWeight.Normal,
                                color = if (wp.point != null) Color.White else Slate400,
                                maxLines = 1
                            )
                        }

                        // Actions
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(
                                onClick = { onMoveWaypoint(idx, idx - 1) },
                                enabled = idx > 0,
                                modifier = Modifier.size(32.dp).background(Slate700, RoundedCornerShape(6.dp))
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowUp,
                                    contentDescription = "Mover para cima",
                                    tint = if (idx > 0) Color.White else Slate700,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            IconButton(
                                onClick = { onMoveWaypoint(idx, idx + 1) },
                                enabled = idx < waypoints.size - 1,
                                modifier = Modifier.size(32.dp).background(Slate700, RoundedCornerShape(6.dp))
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Mover para baixo",
                                    tint = if (idx < waypoints.size - 1) Color.White else Slate700,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            IconButton(
                                onClick = { onPickOnMap(idx) },
                                modifier = Modifier.size(32.dp).background(if (isPicking) CyanPrimary else Slate700, RoundedCornerShape(6.dp))
                            ) {
                                Icon(Icons.Default.Place, contentDescription = "Mapa", tint = if (isPicking) Slate950 else CyanGlow, modifier = Modifier.size(16.dp))
                            }
                            IconButton(
                                onClick = { onOpenSearch(idx) },
                                modifier = Modifier.size(32.dp).background(Slate700, RoundedCornerShape(6.dp))
                            ) {
                                Icon(Icons.Default.Search, contentDescription = "Buscar", tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                            if (waypoints.size > 2) {
                                IconButton(
                                    onClick = { onRemoveWaypoint(idx) },
                                    modifier = Modifier.size(32.dp).background(Slate700, RoundedCornerShape(6.dp))
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Remover", tint = RedSteep, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Waypoints Toolbar
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onAddWaypoint,
                    modifier = Modifier.weight(1.2f),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary.copy(alpha = 0.2f), contentColor = CyanGlow),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Adicionar Parada", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onReverseWaypoints,
                    modifier = Modifier.weight(0.9f),
                    colors = ButtonDefaults.buttonColors(containerColor = Slate800, contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.SwapVert, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Inverter", fontSize = 12.sp)
                }

                Button(
                    onClick = onClearAll,
                    modifier = Modifier.weight(0.9f),
                    colors = ButtonDefaults.buttonColors(containerColor = Slate800, contentColor = Slate400),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Limpar", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // CALCULATE ROUTE BUTTON
            Button(
                onClick = onCalculateRoute,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen, contentColor = Slate950),
                shape = RoundedCornerShape(14.dp),
                enabled = !isCalculating && waypoints.count { it.point != null } >= 2
            ) {
                if (isCalculating) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Slate950, strokeWidth = 2.5.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("CALCULANDO ROTA REAL...", fontWeight = FontWeight.Black)
                } else {
                    Icon(Icons.Default.ElectricBike, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("CALCULAR ROTA E-BIKE", fontWeight = FontWeight.Black, fontSize = 14.sp)
                }
            }

            // ROUTE OPTIONS CARDS
            if (availableRoutes.size > 1) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "${availableRoutes.size} OPÇÕES DE ROTA ENCONTRADAS:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate400
                )
                Spacer(modifier = Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    availableRoutes.forEachIndexed { idx, opt ->
                        val isSelected = selectedRouteIdx == idx
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectRoute(idx) },
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) CyanPrimary.copy(alpha = 0.15f) else Slate800
                            ),
                            border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, CyanGlow) else null
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = opt.name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                                    Text(
                                        text = if (isSelected) "✓ ATIVA" else "SELECIONAR",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        color = if (isSelected) CyanGlow else Slate400
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text("📏 ${(opt.totalDistanceMeters / 1000.0 * 10).toInt() / 10.0} km", fontSize = 11.sp, color = Slate400)
                                    Text("⏱️ ${opt.totalDurationSeconds / 60} min", fontSize = 11.sp, color = CyanGlow)
                                    Text("⚡ -${opt.totalEnergyWh} Wh", fontSize = 11.sp, color = EmeraldGreen)
                                    Text("▲ ${opt.elevationGainM}m", fontSize = 11.sp, color = AmberWarning)
                                }
                            }
                        }
                    }
                }
            }

            // ACTIVE ROUTE SUMMARY & ACTIONS
            activeRoute?.let { route ->
                Spacer(modifier = Modifier.height(14.dp))

                // Summary Grid
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Card(modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = Slate800)) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("${(route.totalDistanceMeters / 1000.0 * 10).toInt() / 10.0} km", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("DISTÂNCIA", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Slate400)
                        }
                    }
                    Card(modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = Slate800)) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("${route.totalDurationSeconds / 60} min", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = CyanGlow)
                            Text("TEMPO EST.", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Slate400)
                        }
                    }
                    Card(modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = Slate800)) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("-${route.totalEnergyWh} Wh", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = EmeraldGreen)
                            Text("BATERIA (${route.batteryDrainPercent}%)", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Slate400)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Elevation Profile Chart
                ElevationProfileChart(
                    points = route.elevationProfile,
                    elevationGainM = route.elevationGainM,
                    elevationLossM = route.elevationLossM,
                    maxGradePercent = route.maxGradePercent
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Action Buttons
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { onStartNavigation(route) },
                        modifier = Modifier.weight(1.3f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen, contentColor = Slate950),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Navigation, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("NAVEGAR", fontWeight = FontWeight.Black)
                    }

                    Button(
                        onClick = { onStartSimulation(route) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Slate800, contentColor = CyanGlow),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("SIMULADOR", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

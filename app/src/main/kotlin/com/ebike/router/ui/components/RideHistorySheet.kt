package com.ebike.router.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.window.Dialog
import com.ebike.router.data.local.entity.DestinationCategory
import com.ebike.router.data.local.entity.RideHistoryEntity
import com.ebike.router.data.local.entity.SavedDestinationEntity
import com.ebike.router.data.local.entity.SavedRouteEntity
import com.ebike.router.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RideHistorySheet(
    rides: List<RideHistoryEntity>,
    savedRoutes: List<SavedRouteEntity>,
    savedDestinations: List<SavedDestinationEntity>,
    onPreviewRide: (RideHistoryEntity) -> Unit,
    onRenameRide: (Long, String) -> Unit,
    onDeleteRide: (Long) -> Unit,
    onLoadRoute: (SavedRouteEntity) -> Unit,
    onRenameRoute: (Long, String) -> Unit,
    onDeleteRoute: (Long) -> Unit,
    onSelectDestination: (SavedDestinationEntity) -> Unit,
    onRenameDestination: (Long, String) -> Unit,
    onDeleteDestination: (Long) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    // Dialog states for rename and delete
    var renameTarget by remember { mutableStateOf<Triple<Long, String, String>?>(null) } // id, currentName, type ("ride", "route", "destination")
    var deleteTarget by remember { mutableStateOf<Triple<Long, String, String>?>(null) } // id, itemName, type

    Card(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.88f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900.copy(alpha = 0.98f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.size(8.dp).background(CyanPrimary, CircleShape))
                    Text(
                        "Histórico & Salvos",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Fechar", tint = Slate400)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Tabs: Histórico / Rotas Salvas / Favoritos
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Slate800,
                contentColor = CyanGlow,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Slate800, RoundedCornerShape(12.dp)),
                indicator = {}
            ) {
                val tabs = listOf(
                    "Histórico (${rides.size})",
                    "Rotas (${savedRoutes.size})",
                    "Favoritos (${savedDestinations.size})"
                )
                tabs.forEachIndexed { index, title ->
                    val isSelected = selectedTab == index
                    Tab(
                        selected = isSelected,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) CyanGlow else Slate400,
                                fontSize = 12.sp
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Content per tab
            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    0 -> {
                        // TAB 1: RIDE HISTORY
                        if (rides.isEmpty()) {
                            EmptyStateNotice(
                                icon = Icons.Default.DirectionsBike,
                                title = "Nenhum pedal registrado",
                                description = "Seus passeios completados aparecerão aqui com distância, velocidade e métricas detalhadas."
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(rides, key = { it.id }) { ride ->
                                    RideHistoryItemCard(
                                        ride = ride,
                                        onPreview = { onPreviewRide(ride) },
                                        onRename = { renameTarget = Triple(ride.id, ride.title, "ride") },
                                        onDelete = { deleteTarget = Triple(ride.id, ride.title, "ride") }
                                    )
                                }
                            }
                        }
                    }
                    1 -> {
                        // TAB 2: SAVED ROUTES
                        if (savedRoutes.isEmpty()) {
                            EmptyStateNotice(
                                icon = Icons.Default.Route,
                                title = "Nenhuma rota salva",
                                description = "No planejador de rotas, toque no ícone de salvar para guardar seus trajetos favoritos aqui."
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(savedRoutes, key = { it.id }) { route ->
                                    SavedRouteItemCard(
                                        route = route,
                                        onLoad = { onLoadRoute(route) },
                                        onRename = { renameTarget = Triple(route.id, route.name, "route") },
                                        onDelete = { deleteTarget = Triple(route.id, route.name, "route") }
                                    )
                                }
                            }
                        }
                    }
                    2 -> {
                        // TAB 3: SAVED DESTINATIONS
                        if (savedDestinations.isEmpty()) {
                            EmptyStateNotice(
                                icon = Icons.Default.Star,
                                title = "Nenhum local favorito",
                                description = "Marque pontos na busca ou toque no mapa para fixar Casa, Trabalho ou locais de pedal."
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(savedDestinations, key = { it.id }) { dest ->
                                    SavedDestinationItemCard(
                                        destination = dest,
                                        onSelect = { onSelectDestination(dest) },
                                        onRename = { renameTarget = Triple(dest.id, dest.label, "destination") },
                                        onDelete = { deleteTarget = Triple(dest.id, dest.label, "destination") }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Rename Dialog
    renameTarget?.let { (id, currentName, type) ->
        var newName by remember { mutableStateOf(currentName) }
        Dialog(onDismissRequest = { renameTarget = null }) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Slate900),
                modifier = Modifier.padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Renomear", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { renameTarget = null }) {
                            Text("Cancelar", color = Slate400)
                        }
                        Button(
                            onClick = {
                                if (newName.isNotBlank()) {
                                    when (type) {
                                        "ride" -> onRenameRide(id, newName.trim())
                                        "route" -> onRenameRoute(id, newName.trim())
                                        "destination" -> onRenameDestination(id, newName.trim())
                                    }
                                }
                                renameTarget = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = Slate950)
                        ) {
                            Text("Salvar", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Delete Confirmation Dialog
    deleteTarget?.let { (id, name, type) ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Excluir item?", fontWeight = FontWeight.Bold, color = Color.White) },
            text = { Text("Tem certeza que deseja excluir \"$name\"? Esta ação não pode ser desfeita.", color = Slate400) },
            confirmButton = {
                Button(
                    onClick = {
                        when (type) {
                            "ride" -> onDeleteRide(id)
                            "route" -> onDeleteRoute(id)
                            "destination" -> onDeleteDestination(id)
                        }
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RedSteep, contentColor = Color.White)
                ) {
                    Text("Excluir", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancelar", color = Slate400)
                }
            },
            containerColor = Slate900
        )
    }
}

@Composable
fun RideHistoryItemCard(
    ride: RideHistoryEntity,
    onPreview: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = SimpleDateFormat("dd/MM/yyyy • HH:mm", Locale.getDefault()).format(Date(ride.timestampMillis))
    val distKm = (ride.distanceMeters / 1000.0 * 10).toInt() / 10.0
    val durationMin = ride.durationSeconds / 60

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Slate800),
        border = androidx.compose.foundation.BorderStroke(1.dp, Slate700)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Title and Action Icons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = ride.title, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                    Text(text = dateStr, color = Slate400, fontSize = 11.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconButton(onClick = onRename, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Renomear", tint = Slate400, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Excluir", tint = Slate400, modifier = Modifier.size(16.dp))
                    }
                }
            }

            // Mode Badge
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (ride.isEBikeMode) CyanPrimary.copy(alpha = 0.15f) else EmeraldGreen.copy(alpha = 0.15f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        if (ride.isEBikeMode) Icons.Default.ElectricBike else Icons.Default.DirectionsBike,
                        contentDescription = null,
                        tint = if (ride.isEBikeMode) CyanGlow else EmeraldGreen,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = if (ride.isEBikeMode) "E-Bike • ${ride.assistLevel ?: "Assist"}" else "Bicicleta Convencional",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (ride.isEBikeMode) CyanGlow else EmeraldGreen
                    )
                }
            }

            // Metrics row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("📏 $distKm km", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium)
                Text("⏱️ ${durationMin} min", fontSize = 12.sp, color = CyanGlow, fontWeight = FontWeight.Medium)
                Text("⚡ ${ride.avgSpeedKmh} km/h", fontSize = 12.sp, color = Slate400)
                Text("▲ ${ride.elevationGainM}m", fontSize = 12.sp, color = AmberWarning)
            }

            // Optional E-Bike metrics
            if (ride.isEBikeMode && ride.energyConsumedWh != null) {
                Text(
                    text = "🔋 Consumo: -${ride.energyConsumedWh} Wh (${ride.batteryDrainPercent ?: 0.0}%)",
                    fontSize = 11.sp,
                    color = EmeraldGreen,
                    fontWeight = FontWeight.Medium
                )
            }

            // Preview Button
            OutlinedButton(
                onClick = onPreview,
                modifier = Modifier.fillMaxWidth().height(36.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanGlow),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyanGlow.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Ver Trajeto no Mapa", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun SavedRouteItemCard(
    route: SavedRouteEntity,
    onLoad: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val distKm = (route.totalDistanceMeters / 1000.0 * 10).toInt() / 10.0
    val durationMin = route.totalDurationSeconds / 60

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Slate800),
        border = androidx.compose.foundation.BorderStroke(1.dp, Slate700)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = route.name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                    Text(text = "${route.profile.icon} ${route.profile.label}", color = CyanGlow, fontSize = 11.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconButton(onClick = onRename, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Renomear", tint = Slate400, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Excluir", tint = Slate400, modifier = Modifier.size(16.dp))
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("📏 $distKm km", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium)
                Text("⏱️ ${durationMin} min", fontSize = 12.sp, color = CyanGlow, fontWeight = FontWeight.Medium)
                Text("▲ ${route.elevationGainM}m", fontSize = 12.sp, color = AmberWarning)
            }

            Button(
                onClick = onLoad,
                modifier = Modifier.fillMaxWidth().height(38.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen, contentColor = Slate950)
            ) {
                Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Carregar Rota", fontSize = 12.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
fun SavedDestinationItemCard(
    destination: SavedDestinationEntity,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Slate800),
        border = androidx.compose.foundation.BorderStroke(1.dp, Slate700)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val icon = when (destination.category) {
                DestinationCategory.HOME -> Icons.Default.Home
                DestinationCategory.WORK -> Icons.Default.Work
                DestinationCategory.TRAIL -> Icons.Default.Terrain
                DestinationCategory.POI -> Icons.Default.Place
                else -> Icons.Default.Star
            }
            Icon(icon, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(24.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(text = destination.label, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                Text(text = destination.subText, color = Slate400, fontSize = 11.sp, maxLines = 1)
            }

            IconButton(onClick = onRename, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Edit, contentDescription = "Renomear", tint = Slate400, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Excluir", tint = Slate400, modifier = Modifier.size(16.dp))
            }

            Button(
                onClick = onSelect,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen, contentColor = Slate950),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text("Ir", fontSize = 12.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
fun EmptyStateNotice(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, description: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = Slate600, modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(12.dp))
        Text(title, fontWeight = FontWeight.Bold, color = Slate400, fontSize = 16.sp)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            description,
            color = Slate600,
            fontSize = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

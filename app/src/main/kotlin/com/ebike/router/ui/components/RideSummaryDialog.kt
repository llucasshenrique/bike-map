package com.ebike.router.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import com.ebike.router.data.local.entity.RideHistoryEntity
import com.ebike.router.ui.theme.*

@Composable
fun RideSummaryDialog(
    ride: RideHistoryEntity,
    onSave: (String) -> Unit,
    onDiscard: () -> Unit
) {
    var title by remember(ride.id) { mutableStateOf(ride.title) }

    Dialog(onDismissRequest = onDiscard) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Slate900),
            border = androidx.compose.foundation.BorderStroke(1.dp, Slate700)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
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
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = EmeraldGreen,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Pedal Concluído!",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    IconButton(onClick = onDiscard, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Fechar", tint = Slate400)
                    }
                }

                // Mode Badge: Normal-bike-first vs E-bike
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (ride.isEBikeMode) CyanPrimary.copy(alpha = 0.15f) else EmeraldGreen.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (ride.isEBikeMode) CyanGlow.copy(alpha = 0.4f) else EmeraldGreen.copy(alpha = 0.4f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            if (ride.isEBikeMode) Icons.Default.ElectricBike else Icons.Default.DirectionsBike,
                            contentDescription = null,
                            tint = if (ride.isEBikeMode) CyanGlow else EmeraldGreen,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (ride.isEBikeMode) "E-Bike (${ride.assistLevel ?: "Assistida"})" else "Bicicleta Convencional",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (ride.isEBikeMode) CyanGlow else EmeraldGreen
                        )
                    }
                }

                // Name input
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Nome do trajeto", color = Slate400) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = CyanPrimary,
                        unfocusedBorderColor = Slate700
                    )
                )

                // Stats Grid
                val distKm = (ride.distanceMeters / 1000.0 * 10).toInt() / 10.0
                val mins = ride.durationSeconds / 60
                val secs = ride.durationSeconds % 60
                val timeStr = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Slate800, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("DISTÂNCIA", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Slate400)
                            Text("$distKm km", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Column {
                            Text("DURAÇÃO", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Slate400)
                            Text(timeStr, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = CyanGlow)
                        }
                        Column {
                            Text("VEL. MÉDIA", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Slate400)
                            Text("${ride.avgSpeedKmh} km/h", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }

                    HorizontalDivider(color = Slate700, thickness = 1.dp)

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("VEL. MÁXIMA", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Slate400)
                            Text("${ride.maxSpeedKmh} km/h", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AmberWarning)
                        }
                        Column {
                            Text("ELEVAÇÃO ▲", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Slate400)
                            Text("${ride.elevationGainM} m", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = EmeraldGreen)
                        }
                        if (ride.isEBikeMode && ride.energyConsumedWh != null) {
                            Column {
                                Text("ENERGIA", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Slate400)
                                Text("-${ride.energyConsumedWh} Wh", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = CyanGlow)
                            }
                        } else {
                            Column {
                                Text("DESCIDA ▼", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Slate400)
                                Text("${ride.elevationLossM} m", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = SlopeDownhill)
                            }
                        }
                    }
                }

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDiscard,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Slate400),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Slate700)
                    ) {
                        Text("Descartar", fontWeight = FontWeight.Medium)
                    }

                    Button(
                        onClick = { onSave(title) },
                        modifier = Modifier
                            .weight(1.4f)
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen, contentColor = Slate950)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Salvar Pedal", fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
}

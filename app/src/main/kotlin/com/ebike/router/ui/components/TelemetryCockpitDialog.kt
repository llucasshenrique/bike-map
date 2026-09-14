package com.ebike.router.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ebike.router.model.AssistLevel
import com.ebike.router.model.LiveRideTelemetry
import com.ebike.router.ui.theme.*

@Composable
fun TelemetryCockpitDialog(
    telemetry: LiveRideTelemetry,
    onSelectAssist: (AssistLevel) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Slate900)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Speed, contentDescription = null, tint = CyanGlow)
                        Text("Computador de Bordo", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Fechar", tint = Slate400)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Big Speedometer & Assist Level
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Slate800, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${telemetry.currentSpeedKmh.toInt()}",
                            fontSize = 46.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                        Text(text = "KM/H ATUAL", fontSize = 11.sp, color = Slate400, fontWeight = FontWeight.Bold)
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${telemetry.avgSpeedKmh}",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanGlow
                        )
                        Text(text = "MÉDIA (KM/H)", fontSize = 10.sp, color = Slate400)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${telemetry.maxSpeedKmh}",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = AmberWarning
                        )
                        Text(text = "MÁXIMA", fontSize = 10.sp, color = Slate400)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Motor Power & Rider Power Split
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Slate800)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("MOTOR", fontSize = 10.sp, color = CyanGlow, fontWeight = FontWeight.Bold)
                            Text("${telemetry.motorPowerWatts} W", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Até 350W nominal", fontSize = 10.sp, color = Slate400)
                        }
                    }
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Slate800)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("CICLISTA", fontSize = 10.sp, color = EmeraldGreen, fontWeight = FontWeight.Bold)
                            Text("${telemetry.riderPowerWatts} W", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Força nas pernas", fontSize = 10.sp, color = Slate400)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Battery Telemetry
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Slate800)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("BATERIA", fontSize = 11.sp, color = Slate400, fontWeight = FontWeight.Bold)
                            Text("${telemetry.batteryTelemetry.percentage}% (${telemetry.batteryTelemetry.currentWh.toInt()} Wh)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = EmeraldGreen)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { telemetry.batteryTelemetry.percentage / 100f },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = EmeraldGreen,
                            trackColor = Slate950
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Autonomia: ~${telemetry.batteryTelemetry.estimatedRangeKm} km", fontSize = 11.sp, color = Slate400)
                            Text("Tensão: ~${telemetry.batteryTelemetry.voltageApprox}V", fontSize = 11.sp, color = Slate400)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Assist Level Selector (CONTRAN 996/2023 - 32 km/h)
                Text("NÍVEL DE ASSISTÊNCIA (ATÉ 32 KM/H):", fontSize = 11.sp, color = Slate400, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AssistLevel.values().forEach { level ->
                        val isSelected = telemetry.activeAssist == level
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(
                                    if (isSelected) Color(level.colorHex) else Slate800,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { onSelectAssist(level) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = level.name,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) Slate950 else Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

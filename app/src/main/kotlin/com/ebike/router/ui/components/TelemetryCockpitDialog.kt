package com.ebike.router.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.ElectricBike
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
    onDismiss: () -> Unit,
    isEBikeMode: Boolean = telemetry.isEBikeMode,
    maxAssistSpeedKmh: Double = 32.0
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Slate900),
            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState())
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
                            if (isEBikeMode) Icons.Default.ElectricBike else Icons.Default.DirectionsBike,
                            contentDescription = null,
                            tint = if (isEBikeMode) CyanGlow else EmeraldGreen
                        )
                        Text(
                            text = if (isEBikeMode) "Computador E-Bike" else "Ciclocomputador de Bordo",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Fechar", tint = Slate400)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Prominent Disclaimer for E-Bike Mode
                if (isEBikeMode) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = AmberWarning.copy(alpha = 0.12f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AmberWarning.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("⚠️", fontSize = 14.sp)
                            Text(
                                text = "ESTIMATIVA POR FÍSICA SIMULADA • SEM CONEXÃO BLE",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = AmberWarning
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Speedometer Section
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Slate800, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val currentSpeedStr = if (telemetry.currentSpeedKmh > 0.0 && telemetry.currentSpeedKmh < 10.0) {
                            String.format(java.util.Locale.US, "%.1f", telemetry.currentSpeedKmh)
                        } else {
                            "${telemetry.currentSpeedKmh.toInt()}"
                        }
                        Text(
                            text = currentSpeedStr,
                            fontSize = 46.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                        Text(text = "KM/H ATUAL", fontSize = 11.sp, color = Slate400, fontWeight = FontWeight.Bold)
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${telemetry.avgSpeedKmh}",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanGlow
                        )
                        Text(text = "MÉDIA (KM/H)", fontSize = 10.sp, color = Slate400)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${telemetry.maxSpeedKmh}",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            color = AmberWarning
                        )
                        Text(text = "MÁXIMA", fontSize = 10.sp, color = Slate400)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Trip Statistics Section (Always shown for both modes)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Slate800)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("DADOS DO TRAJETO", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Slate400)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("${telemetry.distanceRiddenKm} km", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("Distância Pedalada", fontSize = 10.sp, color = Slate400)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                val min = telemetry.timeElapsedSeconds / 60
                                val sec = telemetry.timeElapsedSeconds % 60
                                Text(String.format(java.util.Locale.US, "%02d:%02d", min, sec), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("Tempo Decorrido", fontSize = 10.sp, color = Slate400)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("▲ ${telemetry.elevationGainedM.toInt()} m", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AmberWarning)
                                Text("Ganho de Elevação", fontSize = 10.sp, color = Slate400)
                            }
                        }
                    }
                }

                // E-BIKE SPECIFIC SECTIONS: Only rendered when isEBikeMode == true
                if (isEBikeMode) {
                    Spacer(modifier = Modifier.height(12.dp))

                    // Power Section (Motor Power & Rider Power)
                    Text("POTÊNCIA ESTIMADA (CÁLCULO FÍSICO):", fontSize = 10.sp, color = Slate400, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Card(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Slate800)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("MOTOR (EST.)", fontSize = 10.sp, color = CyanGlow, fontWeight = FontWeight.Bold)
                                Text("${telemetry.motorPowerWatts} W", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("Estimado via física", fontSize = 10.sp, color = Slate400)
                            }
                        }
                        Card(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Slate800)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("CICLISTA (EST.)", fontSize = 10.sp, color = EmeraldGreen, fontWeight = FontWeight.Bold)
                                Text("${telemetry.riderPowerWatts} W", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("Estimado nas pernas", fontSize = 10.sp, color = Slate400)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Battery Telemetry Card
                    telemetry.batteryTelemetry?.let { battery ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Slate800)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("ESTIMATIVA DE BATERIA", fontSize = 10.sp, color = Slate400, fontWeight = FontWeight.Bold)
                                    Text("${battery.percentage}% est. (${battery.currentWh.toInt()} Wh)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = EmeraldGreen)
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { battery.percentage / 100f },
                                    modifier = Modifier.fillMaxWidth().height(6.dp),
                                    color = EmeraldGreen,
                                    trackColor = Slate950
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Autonomia: ~${battery.estimatedRangeKm} km (est.)", fontSize = 11.sp, color = Slate400)
                                    Text("Tensão: ~${battery.voltageApprox}V", fontSize = 11.sp, color = Slate400)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Assist Level Selector
                    Text("NÍVEL DE ASSISTÊNCIA SIMULADO (ATÉ ${maxAssistSpeedKmh.toInt()} KM/H):", fontSize = 10.sp, color = Slate400, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
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
}

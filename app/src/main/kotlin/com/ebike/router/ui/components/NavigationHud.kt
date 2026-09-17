package com.ebike.router.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.ebike.router.model.LiveRideTelemetry
import com.ebike.router.model.ManeuverType
import com.ebike.router.model.TurnInstruction
import com.ebike.router.ui.theme.*

@Composable
fun NavigationHud(
    instruction: TurnInstruction?,
    distanceToNextManeuverMeters: Int,
    telemetry: LiveRideTelemetry,
    isMuted: Boolean,
    isRerouting: Boolean = false,
    onToggleMute: () -> Unit,
    onStopNavigation: () -> Unit,
    onOpenCockpit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize().padding(16.dp)) {
        // TOP: Turn Instruction Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Slate900.copy(alpha = 0.95f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Maneuver Icon
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .background(CyanPrimary, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isRerouting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = Slate950,
                            strokeWidth = 3.dp
                        )
                    } else {
                        val icon = when (instruction?.maneuver) {
                            ManeuverType.TURN_RIGHT, ManeuverType.SHARP_RIGHT, ManeuverType.SLIGHT_RIGHT -> Icons.Default.TurnRight
                            ManeuverType.TURN_LEFT, ManeuverType.SHARP_LEFT, ManeuverType.SLIGHT_LEFT -> Icons.Default.TurnLeft
                            ManeuverType.ROUNDABOUT -> Icons.Default.Refresh
                            ManeuverType.CLIMB_AHEAD -> Icons.Default.TrendingUp
                            ManeuverType.ARRIVE -> Icons.Default.Flag
                            else -> Icons.Default.Straight
                        }
                        Icon(icon, contentDescription = "Manobra", tint = Slate950, modifier = Modifier.size(32.dp))
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    if (isRerouting) {
                        Text(
                            text = "FORA DA ROTA",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = AmberWarning
                        )
                        Text(
                            text = "Recalculando rota...",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 2
                        )
                    } else {
                        val distStr = if (distanceToNextManeuverMeters >= 1000) {
                            "${(distanceToNextManeuverMeters / 1000.0 * 10).toInt() / 10.0} km"
                        } else {
                            "$distanceToNextManeuverMeters m"
                        }
                        Text(
                            text = "EM $distStr",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = CyanGlow
                        )
                        Text(
                            text = instruction?.text ?: "Siga em frente",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 2
                        )
                    }
                }

                // Voice Mute button
                IconButton(
                    onClick = onToggleMute,
                    modifier = Modifier.size(40.dp).background(Slate800, CircleShape)
                ) {
                    Icon(
                        if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = "Áudio",
                        tint = if (isMuted) Slate400 else CyanGlow
                    )
                }
            }
        }

        // BOTTOM: Telemetry Bar & Controls
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Slate900.copy(alpha = 0.95f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Speedometer
                    Column {
                        Row(verticalAlignment = Alignment.Bottom) {
                            val speedText = if (telemetry.currentSpeedKmh > 0.0 && telemetry.currentSpeedKmh < 10.0) {
                                String.format(java.util.Locale.US, "%.1f", telemetry.currentSpeedKmh)
                            } else {
                                "${telemetry.currentSpeedKmh.toInt()}"
                            }
                            Text(
                                text = speedText,
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Black,
                                color = if (telemetry.currentSpeedKmh > 0.5) EmeraldGreen else Color.White
                            )
                            Text(
                                text = " km/h",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate400,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }
                        Text(text = "Assistência: ${telemetry.activeAssist.name}", fontSize = 11.sp, color = CyanGlow, fontWeight = FontWeight.Bold)
                    }

                    // Battery & Distance
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${telemetry.distanceRemainingKm} km",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        val remMin = telemetry.timeRemainingSeconds / 60
                        Text(text = "Restante: $remMin min", fontSize = 11.sp, color = Slate400)
                    }

                    // Battery status
                    Column(horizontalAlignment = Alignment.End) {
                        val batPct = telemetry.batteryTelemetry.percentage
                        val batColor = if (batPct > 40) EmeraldGreen else if (batPct > 15) AmberWarning else RedSteep
                        Text(
                            text = "$batPct%",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = batColor
                        )
                        Text(
                            text = "${telemetry.batteryTelemetry.estimatedRangeKm} km est.",
                            fontSize = 11.sp,
                            color = Slate400
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onOpenCockpit,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Slate800)
                    ) {
                        Icon(Icons.Default.DirectionsBike, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Cockpit")
                    }

                    Button(
                        onClick = onStopNavigation,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = RedSteep)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Encerrar")
                    }
                }
            }
        }
    }
}

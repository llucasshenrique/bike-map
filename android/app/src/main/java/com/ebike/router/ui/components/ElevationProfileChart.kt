package com.ebike.router.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ebike.router.model.ElevationPoint
import com.ebike.router.ui.theme.*
import kotlin.math.max
import kotlin.math.min

@Composable
fun ElevationProfileChart(
    points: List<ElevationPoint>,
    elevationGainM: Int,
    elevationLossM: Int,
    maxGradePercent: Double,
    modifier: Modifier = Modifier
) {
    if (points.size < 2) return

    val minEle = points.minOf { it.elevationM }
    val maxEle = points.maxOf { it.elevationM }
    val eleRange = max(10.0, maxEle - minEle)
    val maxDist = points.last().distanceKm

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Slate800.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PERFIL DE ELEVAÇÃO",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Slate400,
                letterSpacing = 1.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "▲ ${elevationGainM}m",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = AmberWarning
                )
                Text(
                    text = "▼ ${elevationLossM}m",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyanPrimary
                )
                Text(
                    text = "Máx: ${maxGradePercent}%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = RedSteep
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(70.dp)
        ) {
            val w = size.width
            val h = size.height

            val path = Path()
            val fillPath = Path()

            points.forEachIndexed { idx, pt ->
                val x = (pt.distanceKm / maxDist).toFloat() * w
                val normEle = ((pt.elevationM - minEle) / eleRange).toFloat()
                val y = h - (normEle * (h - 10f)) - 5f

                if (idx == 0) {
                    path.moveTo(x, y)
                    fillPath.moveTo(x, h)
                    fillPath.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }

            fillPath.lineTo(w, h)
            fillPath.close()

            // Draw filled gradient
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        CyanPrimary.copy(alpha = 0.4f),
                        Color.Transparent
                    )
                )
            )

            // Draw elevation line
            drawPath(
                path = path,
                color = CyanGlow,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "0 km (${minEle.toInt()}m)", fontSize = 10.sp, color = Slate400)
            Text(text = "${(maxDist * 10).toInt() / 10.0} km (${maxEle.toInt()}m)", fontSize = 10.sp, color = Slate400)
        }
    }
}

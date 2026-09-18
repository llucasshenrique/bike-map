package com.ebike.router.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.ebike.router.ui.theme.CyanGlow
import com.ebike.router.ui.theme.Slate900
import org.junit.Rule
import org.junit.Test

/**
 * Independent visual review of the dark/night map-tile toggle button added in
 * commit 4ab0443 (top-right circular button overlaid on OsmdroidMapView).
 * Written by a different agent than the one that implemented the feature.
 *
 * The map tiles themselves need live network tile fetches and are not
 * meaningfully renderable in a JVM screenshot test, so this reproduces the
 * exact button chrome (same modifiers, colors, icons) over a placeholder map
 * background to give a reviewer a visual of both toggle states without
 * needing to build and launch the app.
 */
class DarkMapToggleScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6)

    @Test
    fun `toggle button when dark map tiles are active shows light-mode icon`() {
        paparazzi.snapshot {
            MapWithToggleButton(isDarkMapTiles = true)
        }
    }

    @Test
    fun `toggle button when normal map tiles are active shows dark-mode icon`() {
        paparazzi.snapshot {
            MapWithToggleButton(isDarkMapTiles = false)
        }
    }

    @Composable
    private fun MapWithToggleButton(isDarkMapTiles: Boolean) {
        MaterialTheme {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Placeholder for the real osmdroid tile surface: a dark-desaturated fill
                    // when night mode is on, a lighter neutral fill otherwise.
                    .background(if (isDarkMapTiles) Color(0xFF23262B) else Color(0xFFE5E3DF))
            ) {
                IconButton(
                    onClick = {},
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Slate900.copy(alpha = 0.9f))
                ) {
                    Icon(
                        imageVector = if (isDarkMapTiles) Icons.Default.LightMode else Icons.Default.DarkMode,
                        contentDescription = if (isDarkMapTiles) "Usar mapa claro" else "Usar mapa escuro",
                        tint = CyanGlow
                    )
                }
            }
        }
    }
}

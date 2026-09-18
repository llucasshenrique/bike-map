package com.ebike.router

import com.ebike.router.model.AssistLevel
import com.ebike.router.model.BatteryTelemetry
import com.ebike.router.model.EBikeConfig
import com.ebike.router.model.LiveRideTelemetry
import com.ebike.router.model.TelemetryOrigin
import org.junit.Assert.*
import org.junit.Test

class EBikeModelsTest {

    @Test
    fun testAssistLevelsConfiguration() {
        // OFF
        assertEquals(0.0, AssistLevel.OFF.motorAssistRatio, 0.001)
        assertEquals(45.0, AssistLevel.OFF.maxSpeedKmh, 0.001)
        assertEquals("No Assist (0%)", AssistLevel.OFF.label)

        // ECO
        assertEquals(0.4, AssistLevel.ECO.motorAssistRatio, 0.001)
        assertEquals(32.0, AssistLevel.ECO.maxSpeedKmh, 0.001)
        assertEquals("Eco (40% - 32 km/h)", AssistLevel.ECO.label)

        // TOUR
        assertEquals(1.0, AssistLevel.TOUR.motorAssistRatio, 0.001)
        assertEquals(32.0, AssistLevel.TOUR.maxSpeedKmh, 0.001)

        // SPORT
        assertEquals(1.8, AssistLevel.SPORT.motorAssistRatio, 0.001)
        assertEquals(32.0, AssistLevel.SPORT.maxSpeedKmh, 0.001)

        // TURBO
        assertEquals(3.0, AssistLevel.TURBO.motorAssistRatio, 0.001)
        assertEquals(32.0, AssistLevel.TURBO.maxSpeedKmh, 0.001)
    }

    @Test
    fun testEBikeConfigDefaultsFollowContranNorms() {
        val config = EBikeConfig()
        assertEquals(625.0, config.batteryCapacityWh, 0.001)
        assertEquals(560.0, config.currentBatteryWh, 0.001)
        assertEquals(32.0, config.maxAssistSpeedKmh, 0.001)
        assertEquals(350.0, config.motorMaxWatt, 0.001) // CONTRAN 996/2023
        assertEquals(0.82, config.motorEfficiency, 0.001)
        assertTrue(config.regenerativeBraking)
        assertEquals(AssistLevel.TOUR, config.activeAssist)
    }

    @Test
    fun testBatteryTelemetryDefaultsToSimulatedEstimate() {
        val telemetry = BatteryTelemetry(
            currentWh = 500.0,
            maxWh = 500.0,
            percentage = 100,
            estimatedRangeKm = 45.0
        )
        assertEquals(TelemetryOrigin.SIMULATED_ESTIMATE, telemetry.origin)
        assertEquals(36.0, telemetry.voltageApprox, 0.001)
    }

    @Test
    fun testLiveRideTelemetryDefaultsToNormalBicycleMode() {
        val live = LiveRideTelemetry()
        assertFalse("LiveRideTelemetry must default to normal bike mode (isEBikeMode = false)", live.isEBikeMode)
        assertNull("Battery telemetry must be null when in normal bike mode by default", live.batteryTelemetry)
        assertTrue("isPowerEstimated should be true by default", live.isPowerEstimated)
        assertEquals(0.0, live.currentSpeedKmh, 0.001)
        assertEquals(0, live.motorPowerWatts)
        assertEquals(0, live.riderPowerWatts)
    }
}

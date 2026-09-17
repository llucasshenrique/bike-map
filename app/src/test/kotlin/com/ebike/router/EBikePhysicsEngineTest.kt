package com.ebike.router

import com.ebike.router.model.AssistLevel
import com.ebike.router.physics.EBikePhysicsEngine
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EBikePhysicsEngineTest {
    private lateinit var physicsEngine: EBikePhysicsEngine

    @Before
    fun setUp() {
        physicsEngine = EBikePhysicsEngine()
    }

    @Test
    fun testContranSpeedLimitIs32Kmh() {
        assertEquals(32.0, AssistLevel.ECO.maxSpeedKmh, 0.001)
        assertEquals(32.0, AssistLevel.TOUR.maxSpeedKmh, 0.001)
        assertEquals(32.0, AssistLevel.SPORT.maxSpeedKmh, 0.001)
        assertEquals(32.0, AssistLevel.TURBO.maxSpeedKmh, 0.001)
    }

    @Test
    fun testFlatTerrainCalculation() {
        val result = physicsEngine.calculateSegmentEnergy(
            distanceMeters = 1000.0,
            gradePercent = 0.0,
            targetSpeedKmh = 25.0,
            assistLevel = AssistLevel.TOUR
        )
        assertTrue("Duration should be positive", result.durationSeconds > 0)
        assertTrue("Energy should be positive for flat terrain", result.energyWh > 0.0)
        assertTrue("Motor power should be under 350W limit", result.motorWatt <= 350)
    }

    @Test
    fun testDownhillRegeneration() {
        val result = physicsEngine.calculateSegmentEnergy(
            distanceMeters = 1000.0,
            gradePercent = -5.0,
            targetSpeedKmh = 30.0,
            assistLevel = AssistLevel.TOUR
        )
        assertTrue("Downhill steep segment should produce negative or zero energy (regen)", result.energyWh <= 0.0)
        assertEquals(0, result.motorWatt)
    }

    @Test
    fun testAssistOffZeroEnergyAndClimbDeceleration() {
        // Flat segment with assist OFF
        val flatResult = physicsEngine.calculateSegmentEnergy(
            distanceMeters = 1000.0,
            gradePercent = 0.0,
            targetSpeedKmh = 22.0,
            assistLevel = AssistLevel.OFF
        )
        assertEquals(0.0, flatResult.energyWh, 0.001)
        assertEquals(0, flatResult.motorWatt)
        assertTrue("Rider should provide positive watts", flatResult.riderWatt > 0)

        // Steep climb with assist OFF
        val climbResult = physicsEngine.calculateSegmentEnergy(
            distanceMeters = 1000.0,
            gradePercent = 8.0,
            targetSpeedKmh = 22.0,
            assistLevel = AssistLevel.OFF
        )
        assertEquals(0.0, climbResult.energyWh, 0.001)
        assertEquals(0, climbResult.motorWatt)
        assertTrue(
            "Climb duration without assist should be longer than flat due to human power limit",
            climbResult.durationSeconds > flatResult.durationSeconds
        )
    }

    @Test
    fun testSyncWithPreferencesScalesRange() {
        val initialTelem = physicsEngine.getBatteryTelemetry()

        val customPrefs = com.ebike.router.data.UserBikePreferences(
            isEBikeMode = true,
            batteryCapacityWh = 750.0,
            currentBatteryWh = 750.0,
            maxAssistSpeedKmh = 25.0,
            bikeWeightKg = 26.0,
            riderWeightKg = 80.0
        )
        physicsEngine.syncWithPreferences(customPrefs)

        val updatedTelem = physicsEngine.getBatteryTelemetry()
        assertEquals(750.0, updatedTelem.maxWh, 0.001)
        assertEquals(750.0, updatedTelem.currentWh, 0.001)
        assertEquals(100, updatedTelem.percentage)
        assertTrue(
            "Higher battery capacity should provide greater estimated range",
            updatedTelem.estimatedRangeKm > initialTelem.estimatedRangeKm
        )
        assertEquals(25.0, physicsEngine.getConfig().maxAssistSpeedKmh, 0.001)
    }
}

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
    fun testRoughSurfaceIncreasesEnergyConsumption() {
        val smooth = physicsEngine.calculateSegmentEnergy(
            distanceMeters = 1000.0,
            gradePercent = 0.0,
            targetSpeedKmh = 20.0,
            assistLevel = AssistLevel.TOUR,
            rollingMultiplier = 1.0
        )
        val rough = physicsEngine.calculateSegmentEnergy(
            distanceMeters = 1000.0,
            gradePercent = 0.0,
            targetSpeedKmh = 20.0,
            assistLevel = AssistLevel.TOUR,
            rollingMultiplier = 2.1
        )
        assertTrue(
            "Rougher surface (higher rolling resistance) should draw more energy",
            rough.energyWh > smooth.energyWh
        )
    }

    @Test
    fun testHighRollingResistanceCapsEffectiveSpeed() {
        val result = physicsEngine.calculateSegmentEnergy(
            distanceMeters = 1000.0,
            gradePercent = 0.0,
            targetSpeedKmh = 30.0,
            assistLevel = AssistLevel.TOUR,
            rollingMultiplier = 1.85
        )
        val baseline = physicsEngine.calculateSegmentEnergy(
            distanceMeters = 1000.0,
            gradePercent = 0.0,
            targetSpeedKmh = 30.0,
            assistLevel = AssistLevel.TOUR,
            rollingMultiplier = 1.0
        )
        assertTrue(
            "Surface roughness above the 1.4 threshold should cap speed and take longer",
            result.durationSeconds >= baseline.durationSeconds
        )
    }

    @Test
    fun testDefaultRollingMultiplierMatchesExplicitBaseline() {
        val default = physicsEngine.calculateSegmentEnergy(
            distanceMeters = 500.0,
            gradePercent = 1.0,
            targetSpeedKmh = 25.0,
            assistLevel = AssistLevel.SPORT
        )
        val explicit = physicsEngine.calculateSegmentEnergy(
            distanceMeters = 500.0,
            gradePercent = 1.0,
            targetSpeedKmh = 25.0,
            assistLevel = AssistLevel.SPORT,
            rollingMultiplier = 1.0
        )
        assertEquals(explicit.energyWh, default.energyWh, 0.001)
        assertEquals(explicit.durationSeconds, default.durationSeconds)
    }
}

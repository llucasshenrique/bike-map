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
}

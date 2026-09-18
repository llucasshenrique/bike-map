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

    @Test
    fun testSyncWithPreferencesDisablesAssistWhenEBikeModeIsFalse() {
        physicsEngine.setAssistLevel(AssistLevel.TURBO)
        assertEquals(AssistLevel.TURBO, physicsEngine.getConfig().activeAssist)

        val normalBikePrefs = com.ebike.router.data.UserBikePreferences(
            isEBikeMode = false,
            batteryCapacityWh = 500.0,
            currentBatteryWh = 500.0
        )
        physicsEngine.syncWithPreferences(normalBikePrefs)

        assertEquals(
            "When e-bike mode is false, active assist must be forced to OFF",
            AssistLevel.OFF,
            physicsEngine.getConfig().activeAssist
        )
    }

    @Test
    fun testUpdateBatteryWhClamping() {
        // Upper bound clamp
        physicsEngine.updateBatteryWh(9999.0)
        assertEquals(physicsEngine.getConfig().batteryCapacityWh, physicsEngine.getConfig().currentBatteryWh, 0.001)

        // Lower bound clamp
        physicsEngine.updateBatteryWh(-50.0)
        assertEquals(0.0, physicsEngine.getConfig().currentBatteryWh, 0.001)

        // Within bounds
        physicsEngine.updateBatteryWh(300.0)
        assertEquals(300.0, physicsEngine.getConfig().currentBatteryWh, 0.001)
    }

    @Test
    fun testBatteryTelemetryVoltageAndRange() {
        physicsEngine.updateBatteryWh(0.0)
        val emptyTelem = physicsEngine.getBatteryTelemetry()
        assertEquals(0, emptyTelem.percentage)
        assertEquals(0.0, emptyTelem.estimatedRangeKm, 0.001)
        assertEquals(30.6, emptyTelem.voltageApprox, 0.001) // 36 * 0.85

        physicsEngine.updateBatteryWh(physicsEngine.getConfig().batteryCapacityWh)
        val fullTelem = physicsEngine.getBatteryTelemetry()
        assertEquals(100, fullTelem.percentage)
        assertEquals(36.0, fullTelem.voltageApprox, 0.001) // 36 * 1.0
        assertEquals(com.ebike.router.model.TelemetryOrigin.SIMULATED_ESTIMATE, fullTelem.origin)
    }

    @Test
    fun testEstimatedRangeZeroWhenAssistIsOff() {
        physicsEngine.setAssistLevel(AssistLevel.OFF)
        val telem = physicsEngine.getBatteryTelemetry()
        assertEquals(0.0, telem.estimatedRangeKm, 0.001)
    }

    @Test
    fun testRegenerativeBrakingDisabledProducesNoRegen() {
        val nonRegenConfig = physicsEngine.getConfig().copy(regenerativeBraking = false)
        physicsEngine.updateConfig(nonRegenConfig)

        val result = physicsEngine.calculateSegmentEnergy(
            distanceMeters = 1000.0,
            gradePercent = -6.0,
            targetSpeedKmh = 25.0,
            assistLevel = AssistLevel.TOUR
        )
        assertEquals("Energy should be 0 when regenerative braking is disabled", 0.0, result.energyWh, 0.001)
    }

    @Test
    fun testDownhillGentleSlopeProducesNoRegen() {
        val regenConfig = physicsEngine.getConfig().copy(regenerativeBraking = true)
        physicsEngine.updateConfig(regenConfig)

        // Gentle downhill (grade >= -3.0%)
        val result = physicsEngine.calculateSegmentEnergy(
            distanceMeters = 1000.0,
            gradePercent = -2.0,
            targetSpeedKmh = 25.0,
            assistLevel = AssistLevel.TOUR
        )
        assertEquals("Gentle slopes (>= -3%) should not trigger regen", 0.0, result.energyWh, 0.001)
    }

    @Test
    fun testZeroBatteryCapacityProducesSafeTelemetryNoCrash() {
        val zeroCapConfig = physicsEngine.getConfig().copy(batteryCapacityWh = 0.0, currentBatteryWh = 0.0)
        physicsEngine.updateConfig(zeroCapConfig)

        val telem = physicsEngine.getBatteryTelemetry()
        assertEquals("Percentage must not be negative or NaN-derived with 0 capacity", 0, telem.percentage)
        assertTrue("Percentage must stay within [0, 100] even with 0 capacity", telem.percentage in 0..100)
        assertTrue("Estimated range must never be negative", telem.estimatedRangeKm >= 0.0)
        assertFalse("Estimated range must not be NaN", telem.estimatedRangeKm.isNaN())
        assertFalse("Estimated range must not be infinite", telem.estimatedRangeKm.isInfinite())
    }

    @Test
    fun testNegativeBatteryCapacityDoesNotCrashAndKeepsPercentageInBounds() {
        val negativeCapConfig = physicsEngine.getConfig().copy(batteryCapacityWh = -300.0, currentBatteryWh = 50.0)
        physicsEngine.updateConfig(negativeCapConfig)

        val telem = physicsEngine.getBatteryTelemetry()
        assertTrue("Percentage must stay within [0, 100] even with negative capacity", telem.percentage in 0..100)
        assertTrue("Estimated range must never be negative", telem.estimatedRangeKm >= 0.0)
    }

    @Test
    fun testNegativeCurrentBatteryNeverProducesNegativeEstimatedRange() {
        // Simulates corrupted/out-of-band state reaching the engine directly
        // (e.g. via a bad preferences sync), bypassing updateBatteryWh's own clamp.
        val corruptedConfig = physicsEngine.getConfig().copy(currentBatteryWh = -50.0)
        physicsEngine.updateConfig(corruptedConfig)

        val telem = physicsEngine.getBatteryTelemetry()
        assertTrue(
            "Estimated range must be clamped to >= 0 even if currentBatteryWh is negative",
            telem.estimatedRangeKm >= 0.0
        )
    }

    @Test
    fun testTogglingModeOffMidRideImmediatelyZeroesEnergyAndMotorPower() {
        // Start a "ride" in e-bike mode with a strong assist level.
        physicsEngine.setAssistLevel(AssistLevel.TURBO)
        val duringRide = physicsEngine.calculateSegmentEnergy(
            distanceMeters = 500.0,
            gradePercent = 3.0,
            targetSpeedKmh = 28.0
        )
        assertTrue("Energy should be positive while e-bike mode is active", duringRide.energyWh > 0.0)
        assertTrue("Motor should be delivering power while e-bike mode is active", duringRide.motorWatt > 0)

        // Rider toggles e-bike mode OFF mid-ride via the same preferences sync path
        // the app uses (BikeMapViewModel collects bikePreferencesFlow -> syncWithPreferences).
        val prefsAfterToggleOff = com.ebike.router.data.UserBikePreferences(isEBikeMode = false)
        physicsEngine.syncWithPreferences(prefsAfterToggleOff)

        val afterToggleOff = physicsEngine.calculateSegmentEnergy(
            distanceMeters = 500.0,
            gradePercent = 3.0,
            targetSpeedKmh = 28.0
        )
        assertEquals(
            "Energy must drop to 0 Wh immediately once e-bike mode is toggled off mid-ride",
            0.0,
            afterToggleOff.energyWh,
            0.001
        )
        assertEquals(
            "Motor power must drop to 0 immediately once e-bike mode is toggled off mid-ride",
            0,
            afterToggleOff.motorWatt
        )
        assertTrue(
            "Rider must still be shown supplying power (human-only propulsion) once assist is off",
            afterToggleOff.riderWatt > 0
        )
    }

    @Test
    fun testMotorPowerCappedAtMaxWatt() {
        // High resistance climb with Turbo assist
        val result = physicsEngine.calculateSegmentEnergy(
            distanceMeters = 500.0,
            gradePercent = 15.0,
            targetSpeedKmh = 30.0,
            assistLevel = AssistLevel.TURBO
        )
        assertTrue("Motor power should not exceed motorMaxWatt (350W)", result.motorWatt <= 350)
        assertTrue("Motor should be delivering power", result.motorWatt > 0)
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
}

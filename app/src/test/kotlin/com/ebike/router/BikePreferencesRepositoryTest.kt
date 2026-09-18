package com.ebike.router

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.ebike.router.data.BikePreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BikePreferencesRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testScope: CoroutineScope
    private lateinit var repository: BikePreferencesRepository

    @Before
    fun setUp() {
        testScope = CoroutineScope(Dispatchers.IO + Job())
        val testDataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { tempFolder.newFile("test_bike_prefs.preferences_pb") }
        )
        repository = BikePreferencesRepository(testDataStore)
    }

    @After
    fun tearDown() {
        testScope.cancel()
    }

    @Test
    fun testDefaultPreferencesAreNormalBikeFirst() = runBlocking {
        val initial = repository.bikePreferencesFlow.first()
        assertFalse("E-bike mode should be OFF by default", initial.isEBikeMode)
        assertEquals(500.0, initial.batteryCapacityWh, 0.001)
        assertEquals(500.0, initial.currentBatteryWh, 0.001)
        assertEquals(32.0, initial.maxAssistSpeedKmh, 0.001)
        assertEquals(24.0, initial.bikeWeightKg, 0.001)
        assertEquals(75.0, initial.riderWeightKg, 0.001)
        assertFalse(initial.regenerativeBraking)
    }

    @Test
    fun testUpdateEBikeModeToggle() = runBlocking {
        repository.updateEBikeMode(true)
        val updated = repository.bikePreferencesFlow.first()
        assertTrue("E-bike mode should now be enabled", updated.isEBikeMode)

        repository.updateEBikeMode(false)
        val reverted = repository.bikePreferencesFlow.first()
        assertFalse("E-bike mode should now be disabled", reverted.isEBikeMode)
    }

    @Test
    fun testUpdateBikeSpecs() = runBlocking {
        repository.updateBikeSpecs(
            batteryCapacityWh = 625.0,
            maxAssistSpeedKmh = 25.0,
            bikeWeightKg = 26.0,
            riderWeightKg = 85.0,
            regenerativeBraking = true
        )

        val updated = repository.bikePreferencesFlow.first()
        assertEquals(625.0, updated.batteryCapacityWh, 0.001)
        assertEquals(25.0, updated.maxAssistSpeedKmh, 0.001)
        assertEquals(26.0, updated.bikeWeightKg, 0.001)
        assertEquals(85.0, updated.riderWeightKg, 0.001)
        assertTrue(updated.regenerativeBraking)
    }

    @Test
    fun testUpdateCurrentBatteryWhClamping() = runBlocking {
        repository.updateBikeSpecs(
            batteryCapacityWh = 400.0,
            maxAssistSpeedKmh = 32.0,
            bikeWeightKg = 22.0,
            riderWeightKg = 70.0
        )
        repository.updateCurrentBatteryWh(300.0)

        val updated = repository.bikePreferencesFlow.first()
        assertEquals(300.0, updated.currentBatteryWh, 0.001)
    }

    @Test
    fun testUpdateBikeSpecsClampsCurrentBatteryWhenCapacityReduced() = runBlocking {
        val initial = repository.bikePreferencesFlow.first()
        assertEquals(500.0, initial.currentBatteryWh, 0.001)

        repository.updateBikeSpecs(
            batteryCapacityWh = 350.0,
            maxAssistSpeedKmh = 32.0,
            bikeWeightKg = 24.0,
            riderWeightKg = 75.0
        )

        val updated = repository.bikePreferencesFlow.first()
        assertEquals(350.0, updated.batteryCapacityWh, 0.001)
        assertEquals(
            "Current battery must be clamped to new capacity if previous current Wh exceeded it",
            350.0,
            updated.currentBatteryWh,
            0.001
        )
    }

    @Test
    fun testUpdateBikeSpecsDoesNotInflateCurrentBattery() = runBlocking {
        repository.updateCurrentBatteryWh(150.0)
        assertEquals(150.0, repository.bikePreferencesFlow.first().currentBatteryWh, 0.001)

        repository.updateBikeSpecs(
            batteryCapacityWh = 750.0,
            maxAssistSpeedKmh = 32.0,
            bikeWeightKg = 24.0,
            riderWeightKg = 75.0
        )

        val updated = repository.bikePreferencesFlow.first()
        assertEquals(750.0, updated.batteryCapacityWh, 0.001)
        assertEquals(
            "Current battery Wh should remain 150.0 when capacity is increased",
            150.0,
            updated.currentBatteryWh,
            0.001
        )
    }
}

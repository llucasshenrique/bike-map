package com.ebike.router.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.bikeDataStore: DataStore<Preferences> by preferencesDataStore(name = "bike_preferences")

data class UserBikePreferences(
    val isEBikeMode: Boolean = false,
    val batteryCapacityWh: Double = 500.0,
    val currentBatteryWh: Double = 500.0,
    val maxAssistSpeedKmh: Double = 32.0,
    val bikeWeightKg: Double = 24.0,
    val riderWeightKg: Double = 75.0,
    val regenerativeBraking: Boolean = false
)

class BikePreferencesRepository(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.bikeDataStore)

    companion object {
        val KEY_IS_EBIKE_MODE = booleanPreferencesKey("is_ebike_mode")
        val KEY_BATTERY_CAPACITY_WH = doublePreferencesKey("battery_capacity_wh")
        val KEY_CURRENT_BATTERY_WH = doublePreferencesKey("current_battery_wh")
        val KEY_MAX_ASSIST_SPEED_KMH = doublePreferencesKey("max_assist_speed_kmh")
        val KEY_BIKE_WEIGHT_KG = doublePreferencesKey("bike_weight_kg")
        val KEY_RIDER_WEIGHT_KG = doublePreferencesKey("rider_weight_kg")
        val KEY_REGENERATIVE_BRAKING = booleanPreferencesKey("regenerative_braking")
    }

    val bikePreferencesFlow: Flow<UserBikePreferences> = dataStore.data.map { prefs ->
        UserBikePreferences(
            isEBikeMode = prefs[KEY_IS_EBIKE_MODE] ?: false,
            batteryCapacityWh = prefs[KEY_BATTERY_CAPACITY_WH] ?: 500.0,
            currentBatteryWh = prefs[KEY_CURRENT_BATTERY_WH] ?: 500.0,
            maxAssistSpeedKmh = prefs[KEY_MAX_ASSIST_SPEED_KMH] ?: 32.0,
            bikeWeightKg = prefs[KEY_BIKE_WEIGHT_KG] ?: 24.0,
            riderWeightKg = prefs[KEY_RIDER_WEIGHT_KG] ?: 75.0,
            regenerativeBraking = prefs[KEY_REGENERATIVE_BRAKING] ?: false
        )
    }

    suspend fun updateEBikeMode(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_IS_EBIKE_MODE] = enabled
        }
    }

    suspend fun updateBikeSpecs(
        batteryCapacityWh: Double,
        maxAssistSpeedKmh: Double,
        bikeWeightKg: Double,
        riderWeightKg: Double,
        regenerativeBraking: Boolean = false
    ) {
        dataStore.edit { prefs ->
            prefs[KEY_BATTERY_CAPACITY_WH] = batteryCapacityWh
            prefs[KEY_MAX_ASSIST_SPEED_KMH] = maxAssistSpeedKmh
            prefs[KEY_BIKE_WEIGHT_KG] = bikeWeightKg
            prefs[KEY_RIDER_WEIGHT_KG] = riderWeightKg
            prefs[KEY_REGENERATIVE_BRAKING] = regenerativeBraking
            val current = prefs[KEY_CURRENT_BATTERY_WH] ?: 500.0
            if (current > batteryCapacityWh) {
                prefs[KEY_CURRENT_BATTERY_WH] = batteryCapacityWh
            }
        }
    }

    suspend fun updateCurrentBatteryWh(currentWh: Double) {
        dataStore.edit { prefs ->
            prefs[KEY_CURRENT_BATTERY_WH] = currentWh
        }
    }
}

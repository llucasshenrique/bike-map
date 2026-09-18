package com.ebike.router.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ride_history")
data class RideHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,                          // e.g. "Pedal Matinal", "Parque Ibirapuera", or custom name
    val timestampMillis: Long,                  // Start timestamp (System.currentTimeMillis())
    val distanceMeters: Int,                    // Total distance traveled
    val durationSeconds: Int,                   // Total elapsed active ride time
    val avgSpeedKmh: Double,                    // Calculated average speed
    val maxSpeedKmh: Double,                    // Peak speed recorded
    val elevationGainM: Int,                    // Total meters climbed
    val elevationLossM: Int,                    // Total meters descended
    val routePolyline: String,                  // JSON array or Encoded Polyline (GeoPoint list)
    val startAddress: String? = null,           // Human-readable origin label
    val endAddress: String? = null,             // Human-readable destination label

    // Normal-bike-first vs E-bike opt-in attributes:
    val isEBikeMode: Boolean = false,           // False for regular acoustic bikes, true if e-bike mode was used
    val assistLevel: String? = null,            // AssistLevel name (OFF, ECO, TOUR, SPORT, TURBO) or null
    val energyConsumedWh: Double? = null,       // Wh consumed during ride (null if standard bike)
    val batteryDrainPercent: Double? = null     // Percentage drain (null if standard bike)
)

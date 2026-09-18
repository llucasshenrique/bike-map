package com.ebike.router.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.ebike.router.model.RoutingProfile

@Entity(tableName = "saved_routes")
data class SavedRouteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,                           // e.g. "Caminho do Trabalho (Ciclovia)"
    val profile: RoutingProfile,                // EFFICIENT, TURBO, SCENIC, SAFE
    val waypointsJson: String,                  // Serialized List<RouteWaypoint>
    val polylineJson: String,                   // Serialized List<GeoPoint> for immediate rendering
    val totalDistanceMeters: Int,
    val totalDurationSeconds: Int,
    val elevationGainM: Int,
    val isFavorite: Boolean = true,
    val createdAtMillis: Long = System.currentTimeMillis()
)

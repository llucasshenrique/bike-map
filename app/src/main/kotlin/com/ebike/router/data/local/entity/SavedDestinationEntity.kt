package com.ebike.router.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class DestinationCategory {
    HOME, WORK, FAVORITE, TRAIL, POI
}

@Entity(tableName = "saved_destinations")
data class SavedDestinationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val label: String,                          // e.g. "Casa", "Trabalho", "Ciclovia Pinheiros"
    val subText: String,                        // Address or descriptive text
    val lat: Double,
    val lng: Double,
    val ele: Double = 20.0,
    val category: DestinationCategory = DestinationCategory.FAVORITE,
    val createdAtMillis: Long = System.currentTimeMillis()
)

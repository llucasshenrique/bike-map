package com.ebike.router.data.local.dao

import androidx.room.*
import com.ebike.router.data.local.entity.SavedDestinationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedDestinationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDestination(destination: SavedDestinationEntity): Long

    @Query("UPDATE saved_destinations SET label = :newLabel WHERE id = :id")
    suspend fun renameDestination(id: Long, newLabel: String)

    @Query("DELETE FROM saved_destinations WHERE id = :id")
    suspend fun deleteDestinationById(id: Long)

    @Query("SELECT * FROM saved_destinations ORDER BY createdAtMillis DESC")
    fun getAllDestinations(): Flow<List<SavedDestinationEntity>>

    @Query("SELECT * FROM saved_destinations WHERE id = :id")
    suspend fun getDestinationById(id: Long): SavedDestinationEntity?
}

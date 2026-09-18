package com.ebike.router.data.local.dao

import androidx.room.*
import com.ebike.router.data.local.entity.RideHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RideHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRide(ride: RideHistoryEntity): Long

    @Query("UPDATE ride_history SET title = :newTitle WHERE id = :id")
    suspend fun renameRide(id: Long, newTitle: String)

    @Delete
    suspend fun deleteRide(ride: RideHistoryEntity)

    @Query("DELETE FROM ride_history WHERE id = :id")
    suspend fun deleteRideById(id: Long)

    @Query("SELECT * FROM ride_history ORDER BY timestampMillis DESC")
    fun getAllRides(): Flow<List<RideHistoryEntity>>

    @Query("SELECT * FROM ride_history WHERE id = :id")
    suspend fun getRideById(id: Long): RideHistoryEntity?
}

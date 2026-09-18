package com.ebike.router.data.local.dao

import androidx.room.*
import com.ebike.router.data.local.entity.SavedRouteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedRouteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoute(route: SavedRouteEntity): Long

    @Query("UPDATE saved_routes SET name = :newName WHERE id = :id")
    suspend fun renameRoute(id: Long, newName: String)

    @Query("UPDATE saved_routes SET isFavorite = :isFav WHERE id = :id")
    suspend fun setFavorite(id: Long, isFav: Boolean)

    @Query("DELETE FROM saved_routes WHERE id = :id")
    suspend fun deleteRouteById(id: Long)

    @Query("SELECT * FROM saved_routes ORDER BY createdAtMillis DESC")
    fun getAllSavedRoutes(): Flow<List<SavedRouteEntity>>

    @Query("SELECT * FROM saved_routes WHERE id = :id")
    suspend fun getRouteById(id: Long): SavedRouteEntity?
}

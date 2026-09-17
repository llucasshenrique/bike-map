package com.ebike.router.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.ebike.router.data.local.converter.RoomConverters
import com.ebike.router.data.local.dao.RideHistoryDao
import com.ebike.router.data.local.dao.SavedDestinationDao
import com.ebike.router.data.local.dao.SavedRouteDao
import com.ebike.router.data.local.entity.RideHistoryEntity
import com.ebike.router.data.local.entity.SavedDestinationEntity
import com.ebike.router.data.local.entity.SavedRouteEntity

@Database(
    entities = [
        RideHistoryEntity::class,
        SavedRouteEntity::class,
        SavedDestinationEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(RoomConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rideHistoryDao(): RideHistoryDao
    abstract fun savedRouteDao(): SavedRouteDao
    abstract fun savedDestinationDao(): SavedDestinationDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bike_router.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}

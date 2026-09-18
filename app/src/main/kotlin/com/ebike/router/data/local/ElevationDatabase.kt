package com.ebike.router.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.LruCache
import kotlin.math.roundToLong

class ElevationDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "elevation_cache.db"
        const val DATABASE_VERSION = 1

        const val TABLE_ELEVATION = "elevation_cache"
        const val COL_SPATIAL_KEY = "spatial_key"
        const val COL_LATITUDE = "latitude"
        const val COL_LONGITUDE = "longitude"
        const val COL_ELEVATION_M = "elevation_m"
        const val COL_CREATED_AT = "created_at"

        const val MAX_CACHE_RECORDS = 100_000
        const val LRU_CACHE_SIZE = 20_000

        /**
         * Quantizes (lat, lng) to ~11m resolution (4 decimal places)
         * and packs into a 64-bit Long spatial key.
         */
        fun computeSpatialKey(lat: Double, lng: Double): Long {
            val latInt = (lat * 10000.0).roundToLong()
            val lngInt = (lng * 10000.0).roundToLong()
            return (latInt shl 32) or (lngInt and 0xFFFFFFFFL)
        }
    }

    // L1 in-memory cache for instant lookups (<1ms)
    private val l1Cache = LruCache<Long, Float>(LRU_CACHE_SIZE)

    val elevationDao: ElevationDao = object : ElevationDao {
        override fun getElevation(spatialKey: Long): ElevationEntity? {
            // Check L1 first
            val cachedFloat = l1Cache.get(spatialKey)
            if (cachedFloat != null) {
                // Approximate coordinates from spatial key
                val lat = (spatialKey shr 32).toDouble() / 10000.0
                val lng = (spatialKey.toInt()).toDouble() / 10000.0
                return ElevationEntity(spatialKey, lat, lng, cachedFloat.toDouble())
            }

            // Check L2 SQLite
            val db = readableDatabase
            val cursor = db.query(
                TABLE_ELEVATION,
                arrayOf(COL_SPATIAL_KEY, COL_LATITUDE, COL_LONGITUDE, COL_ELEVATION_M, COL_CREATED_AT),
                "$COL_SPATIAL_KEY = ?",
                arrayOf(spatialKey.toString()),
                null, null, null, "1"
            )

            cursor.use {
                if (it.moveToFirst()) {
                    val key = it.getLong(0)
                    val lat = it.getDouble(1)
                    val lng = it.getDouble(2)
                    val ele = it.getDouble(3)
                    val createdAt = it.getLong(4)
                    l1Cache.put(key, ele.toFloat())
                    return ElevationEntity(key, lat, lng, ele, createdAt)
                }
            }
            return null
        }

        override fun getElevations(keys: List<Long>): Map<Long, ElevationEntity> {
            val result = mutableMapOf<Long, ElevationEntity>()
            val missingKeys = mutableListOf<Long>()

            // Check L1
            for (key in keys) {
                val cached = l1Cache.get(key)
                if (cached != null) {
                    val lat = (key shr 32).toDouble() / 10000.0
                    val lng = (key.toInt()).toDouble() / 10000.0
                    result[key] = ElevationEntity(key, lat, lng, cached.toDouble())
                } else {
                    missingKeys.add(key)
                }
            }

            if (missingKeys.isEmpty()) {
                return result
            }

            // Query SQLite in batches of 500 to stay within SQL argument limits
            val db = readableDatabase
            missingKeys.chunked(500).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                val args = chunk.map { it.toString() }.toTypedArray()
                val cursor = db.query(
                    TABLE_ELEVATION,
                    arrayOf(COL_SPATIAL_KEY, COL_LATITUDE, COL_LONGITUDE, COL_ELEVATION_M, COL_CREATED_AT),
                    "$COL_SPATIAL_KEY IN ($placeholders)",
                    args,
                    null, null, null
                )
                cursor.use {
                    while (it.moveToNext()) {
                        val key = it.getLong(0)
                        val lat = it.getDouble(1)
                        val lng = it.getDouble(2)
                        val ele = it.getDouble(3)
                        val createdAt = it.getLong(4)
                        l1Cache.put(key, ele.toFloat())
                        result[key] = ElevationEntity(key, lat, lng, ele, createdAt)
                    }
                }
            }

            return result
        }

        override fun insert(entity: ElevationEntity) {
            insertAll(listOf(entity))
        }

        override fun insertAll(entities: List<ElevationEntity>) {
            if (entities.isEmpty()) return

            entities.forEach {
                l1Cache.put(it.spatialKey, it.elevationM.toFloat())
            }

            val db = writableDatabase
            db.beginTransaction()
            try {
                for (entity in entities) {
                    val values = ContentValues().apply {
                        put(COL_SPATIAL_KEY, entity.spatialKey)
                        put(COL_LATITUDE, entity.latitude)
                        put(COL_LONGITUDE, entity.longitude)
                        put(COL_ELEVATION_M, entity.elevationM)
                        put(COL_CREATED_AT, entity.createdAt)
                    }
                    db.insertWithOnConflict(
                        TABLE_ELEVATION,
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        override fun getCount(): Long {
            val db = readableDatabase
            val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_ELEVATION", null)
            cursor.use {
                if (it.moveToFirst()) return it.getLong(0)
            }
            return 0L
        }

        override fun pruneOldest(keepCount: Int) {
            val count = getCount()
            if (count > keepCount) {
                val toDelete = count - keepCount
                writableDatabase.execSQL(
                    "DELETE FROM $TABLE_ELEVATION WHERE $COL_SPATIAL_KEY IN (SELECT $COL_SPATIAL_KEY FROM $TABLE_ELEVATION ORDER BY $COL_CREATED_AT ASC LIMIT $toDelete)"
                )
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_ELEVATION (
                $COL_SPATIAL_KEY INTEGER PRIMARY KEY,
                $COL_LATITUDE REAL NOT NULL,
                $COL_LONGITUDE REAL NOT NULL,
                $COL_ELEVATION_M REAL NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_elevation_spatial ON $TABLE_ELEVATION ($COL_SPATIAL_KEY)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_ELEVATION")
        onCreate(db)
    }
}

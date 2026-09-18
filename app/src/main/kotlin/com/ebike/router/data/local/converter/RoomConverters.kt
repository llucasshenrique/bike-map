package com.ebike.router.data.local.converter

import androidx.room.TypeConverter
import com.ebike.router.data.local.entity.DestinationCategory
import com.ebike.router.model.GeoPoint
import com.ebike.router.model.RouteWaypoint
import com.ebike.router.model.RoutingProfile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class RoomConverters {
    private val gson = Gson()

    @TypeConverter
    fun fromRoutingProfile(profile: RoutingProfile?): String? = profile?.name

    @TypeConverter
    fun toRoutingProfile(value: String?): RoutingProfile? =
        value?.let { runCatching { RoutingProfile.valueOf(it) }.getOrDefault(RoutingProfile.EFFICIENT) }

    @TypeConverter
    fun fromDestinationCategory(category: DestinationCategory?): String? = category?.name

    @TypeConverter
    fun toDestinationCategory(value: String?): DestinationCategory? =
        value?.let { runCatching { DestinationCategory.valueOf(it) }.getOrDefault(DestinationCategory.FAVORITE) }

    @TypeConverter
    fun fromGeoPointList(points: List<GeoPoint>?): String? =
        points?.let { gson.toJson(it) }

    @TypeConverter
    fun toGeoPointList(value: String?): List<GeoPoint>? {
        if (value.isNullOrBlank()) return emptyList()
        val type = object : TypeToken<List<GeoPoint>>() {}.type
        return runCatching { gson.fromJson<List<GeoPoint>>(value, type) }.getOrDefault(emptyList())
    }

    @TypeConverter
    fun fromWaypointList(waypoints: List<RouteWaypoint>?): String? =
        waypoints?.let { gson.toJson(it) }

    @TypeConverter
    fun toWaypointList(value: String?): List<RouteWaypoint>? {
        if (value.isNullOrBlank()) return emptyList()
        val type = object : TypeToken<List<RouteWaypoint>>() {}.type
        return runCatching { gson.fromJson<List<RouteWaypoint>>(value, type) }.getOrDefault(emptyList())
    }
}

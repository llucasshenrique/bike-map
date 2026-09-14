package com.ebike.router.model

data class GeoPoint(
    val lat: Double,
    val lng: Double,
    val ele: Double = 20.0
) {
    fun distanceTo(other: GeoPoint): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(other.lat - lat)
        val dLng = Math.toRadians(other.lng - lng)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(other.lat)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }
}

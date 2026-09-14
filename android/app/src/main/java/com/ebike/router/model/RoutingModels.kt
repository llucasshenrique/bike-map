package com.ebike.router.model

enum class RoutingProfile(val label: String, val icon: String) {
    EFFICIENT("Eco Efficient", "⚡"),
    TURBO("Turbo Fast", "🚀"),
    SCENIC("Scenic Trails", "🌲"),
    SAFE("Safe Bikeways", "🛡️")
}

enum class ManeuverType {
    DEPART,
    STRAIGHT,
    SLIGHT_RIGHT,
    TURN_RIGHT,
    SHARP_RIGHT,
    U_TURN,
    SHARP_LEFT,
    TURN_LEFT,
    SLIGHT_LEFT,
    ROUNDABOUT,
    CLIMB_AHEAD,
    ARRIVE
}

data class TurnInstruction(
    val index: Int,
    val maneuver: ManeuverType,
    val text: String,
    val streetName: String,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val point: GeoPoint,
    val gradePercent: Double,
    val energyWh: Double,
    val cumulativeDistanceKm: Double
)

data class RouteSegment(
    val fromNodeId: String,
    val toNodeId: String,
    val name: String,
    val distanceMeters: Int,
    val gradePercent: Double,
    val elevationGainM: Int,
    val elevationLossM: Int,
    val coordinates: List<GeoPoint>,
    val estimatedEnergyWh: Double,
    val estimatedTimeSeconds: Int
)

data class ElevationPoint(
    val distanceKm: Double,
    val elevationM: Double,
    val gradePercent: Double,
    val lat: Double,
    val lng: Double
)

data class RouteWaypoint(
    val id: String,
    val letter: String,
    val label: String,
    val point: GeoPoint? = null,
    val isGps: Boolean = false
)

data class RouteResult(
    val id: String,
    val name: String = "Fastest Route",
    val summary: String = "Optimal balance of speed & energy",
    val profile: RoutingProfile = RoutingProfile.EFFICIENT,
    val totalDistanceMeters: Int,
    val totalDurationSeconds: Int,
    val totalEnergyWh: Double,
    val elevationGainM: Int,
    val elevationLossM: Int,
    val maxGradePercent: Double,
    val avgGradePercent: Double,
    val coordinates: List<GeoPoint>,
    val elevationProfile: List<ElevationPoint>,
    val instructions: List<TurnInstruction>,
    val segments: List<RouteSegment>,
    val batteryDrainPercent: Double,
    val estimatedBatteryRemainingWh: Int,
    val batteryRemainingPercent: Int
)

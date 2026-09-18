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
    val estimatedTimeSeconds: Int,
    val surface: String = "asphalt",
    val highway: String? = null,
    val isBikeLane: Boolean = false,
    val safetyScore: Double = 1.0,
    val rollingResistanceMultiplier: Double = 1.0
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
    val batteryRemainingPercent: Int,
    val safetyScore: Double = 1.0,
    val bikeLanePercentage: Double = 0.0,
    val pavedPercentage: Double = 100.0,
    val dominantSurface: String = "asphalt"
)

data class OsmWayTags(
    val surface: String? = null,
    val highway: String? = null,
    val cycleway: String? = null,
    val bicycle: String? = null,
    val smoothness: String? = null,
    val name: String? = null
) {
    fun isBikeLaneOrCycleway(): Boolean {
        if (highway in listOf("cycleway", "path") && bicycle !in listOf("no", "dismount")) return true
        if (cycleway in listOf("lane", "track", "opposite_lane", "opposite_track", "share_busway", "shared_lane", "yes", "designated")) return true
        if (bicycle == "designated") return true
        val lowerName = name?.lowercase() ?: ""
        if (lowerName.contains("ciclovia") || lowerName.contains("ciclofaixa") || lowerName.contains("bike path")) return true
        return false
    }

    fun getRollingResistanceMultiplier(): Double {
        val surf = surface?.lowercase()?.trim()
        return when (surf) {
            "asphalt", "concrete", "paved", "paving_stones:smooth" -> 1.0
            "paving_stones", "sett", "cobblestone:flattened" -> 1.35
            "compacted", "fine_gravel", "chipseal" -> 1.45
            "cobblestone" -> 1.75
            "gravel", "pebblestone" -> 1.85
            "unpaved", "ground", "dirt", "earth" -> 2.10
            "grass", "sand", "mud" -> 2.80
            null, "" -> {
                when (highway?.lowercase()) {
                    "track", "path" -> 1.4
                    else -> 1.0
                }
            }
            else -> 1.15
        }
    }

    fun isPaved(): Boolean {
        val surf = surface?.lowercase()?.trim() ?: return highway !in listOf("track", "bridleway")
        return surf in listOf("asphalt", "concrete", "paved", "paving_stones", "sett", "cobblestone", "metal")
    }

    fun getSafetyScore(): Double {
        val isCycleway = isBikeLaneOrCycleway()
        val baseScore = when {
            highway == "cycleway" || cycleway == "track" -> 1.0
            isCycleway -> 0.88
            highway in listOf("living_street", "pedestrian") -> 0.85
            highway == "residential" -> 0.80
            highway in listOf("service", "unclassified") -> 0.70
            highway == "tertiary" -> 0.55
            highway == "secondary" -> 0.35
            highway in listOf("primary", "trunk") -> 0.20
            highway == "motorway" -> 0.05
            else -> {
                val lowerName = name?.lowercase() ?: ""
                if (lowerName.contains("ciclovia") || lowerName.contains("ciclofaixa")) 0.95
                else if (lowerName.contains("rodovia") || lowerName.contains("avenida")) 0.40
                else 0.65
            }
        }

        val surf = surface?.lowercase()?.trim()
        val surfacePenalty = when (surf) {
            "sand", "mud" -> 0.25
            "cobblestone", "gravel" -> 0.15
            "unpaved", "dirt" -> 0.10
            else -> 0.0
        }
        return (baseScore - surfacePenalty).coerceIn(0.05, 1.0)
    }
}

package com.ebike.router.model

enum class AssistLevel(
    val motorAssistRatio: Double,
    val maxSpeedKmh: Double,
    val label: String,
    val colorHex: Long
) {
    OFF(0.0, 45.0, "No Assist (0%)", 0xFF64748B),
    ECO(0.4, 32.0, "Eco (40% - 32 km/h)", 0xFF10B981),
    TOUR(1.0, 32.0, "Tour (100% - 32 km/h)", 0xFF06B6D4),
    SPORT(1.8, 32.0, "Sport (180% - 32 km/h)", 0xFFF59E0B),
    TURBO(3.0, 32.0, "Turbo (300% - 32 km/h)", 0xFFEF4444)
}

data class EBikeConfig(
    val batteryCapacityWh: Double = 625.0,
    val currentBatteryWh: Double = 560.0,
    val bikeWeightKg: Double = 24.0,
    val riderWeightKg: Double = 75.0,
    val motorMaxWatt: Double = 350.0, // CONTRAN 996/2023 (Brasil)
    val motorEfficiency: Double = 0.82,
    val regenerativeBraking: Boolean = true,
    val activeAssist: AssistLevel = AssistLevel.TOUR,
    val tireRollingCoeff: Double = 0.0055,
    val aerodynamicCdA: Double = 0.38
)

data class BatteryTelemetry(
    val currentWh: Double,
    val maxWh: Double,
    val percentage: Int,
    val estimatedRangeKm: Double,
    val instantPowerWatt: Double = 0.0,
    val instantConsumptionWhPerKm: Double = 0.0,
    val voltageApprox: Double = 36.0
)

data class LiveRideTelemetry(
    val currentSpeedKmh: Double = 0.0,
    val avgSpeedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val distanceRiddenKm: Double = 0.0,
    val distanceRemainingKm: Double = 0.0,
    val timeElapsedSeconds: Int = 0,
    val timeRemainingSeconds: Int = 0,
    val currentElevationM: Double = 30.0,
    val elevationGainedM: Double = 0.0,
    val currentGradePercent: Double = 0.0,
    val motorPowerWatts: Int = 0,
    val riderPowerWatts: Int = 0,
    val activeAssist: AssistLevel = AssistLevel.TOUR,
    val batteryTelemetry: BatteryTelemetry = BatteryTelemetry(560.0, 625.0, 90, 56.0),
    val headingDegrees: Float = 0f,
    val isNavigating: Boolean = false,
    val isPaused: Boolean = false
)

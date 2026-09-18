package com.ebike.router.physics

import com.ebike.router.model.*
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

class EBikePhysicsEngine(
    private var config: EBikeConfig = EBikeConfig()
) {
    companion object {
        const val GRAVITY = 9.81 // m/s^2
        const val AIR_DENSITY = 1.225 // kg/m^3
    }

    fun getConfig(): EBikeConfig = config

    fun updateConfig(newConfig: EBikeConfig) {
        config = newConfig
    }

    fun syncWithPreferences(prefs: com.ebike.router.data.UserBikePreferences) {
        config = config.copy(
            batteryCapacityWh = prefs.batteryCapacityWh,
            currentBatteryWh = prefs.currentBatteryWh,
            maxAssistSpeedKmh = prefs.maxAssistSpeedKmh,
            bikeWeightKg = prefs.bikeWeightKg,
            riderWeightKg = prefs.riderWeightKg,
            regenerativeBraking = prefs.regenerativeBraking,
            activeAssist = if (prefs.isEBikeMode) config.activeAssist else AssistLevel.OFF
        )
    }

    fun setAssistLevel(level: AssistLevel) {
        config = config.copy(activeAssist = level)
    }

    fun updateBatteryWh(wh: Double) {
        config = config.copy(currentBatteryWh = max(0.0, min(config.batteryCapacityWh, wh)))
    }

    fun getBatteryTelemetry(): BatteryTelemetry {
        val pct = max(0, min(100, ((config.currentBatteryWh / max(1.0, config.batteryCapacityWh)) * 100).toInt()))
        val avgWhPerKm = getAverageWhPerKm(config.activeAssist)
        val rangeKm = if (avgWhPerKm > 0) max(0.0, config.currentBatteryWh / avgWhPerKm) else 0.0
        val voltage = 36.0 * (0.85 + 0.15 * (pct / 100.0))

        return BatteryTelemetry(
            currentWh = config.currentBatteryWh,
            maxWh = config.batteryCapacityWh,
            percentage = pct,
            estimatedRangeKm = (rangeKm * 10).toInt() / 10.0,
            voltageApprox = (voltage * 10).toInt() / 10.0,
            origin = TelemetryOrigin.SIMULATED_ESTIMATE
        )
    }

    fun getAverageWhPerKm(assist: AssistLevel): Double {
        return when (assist) {
            AssistLevel.OFF -> 0.0
            AssistLevel.ECO -> 7.0
            AssistLevel.TOUR -> 11.0
            AssistLevel.SPORT -> 15.0
            AssistLevel.TURBO -> 21.0
        }
    }

    data class SegmentEnergyResult(
        val energyWh: Double,
        val durationSeconds: Int,
        val motorWatt: Int,
        val riderWatt: Int
    )

    fun calculateSegmentEnergy(
        distanceMeters: Double,
        gradePercent: Double,
        targetSpeedKmh: Double,
        assistLevel: AssistLevel = config.activeAssist
    ): SegmentEnergyResult {
        val totalMass = config.bikeWeightKg + config.riderWeightKg
        val theta = atan(gradePercent / 100.0)

        // If assist is OFF (normal bike or motor turned off):
        if (assistLevel.motorAssistRatio <= 0.0) {
            // Human aerobic sustained power (~150W)
            val humanPowerWatts = 150.0
            val effectiveSpeedKmh = if (gradePercent > 0.0) {
                // On inclines, speed naturally drops based on gravity & rolling resistance: v = P / F
                val fGravity = totalMass * GRAVITY * sin(theta)
                val fRolling = config.tireRollingCoeff * totalMass * GRAVITY * cos(theta)
                val fResistance = fGravity + fRolling
                val climbSpeedMs = if (fResistance > 0) humanPowerWatts / fResistance else 5.0
                val calculatedKmh = climbSpeedMs * 3.6
                max(5.0, min(targetSpeedKmh, calculatedKmh))
            } else {
                max(5.0, targetSpeedKmh)
            }

            val speedMs = effectiveSpeedKmh / 3.6
            val durationSeconds = max(1, (distanceMeters / speedMs).toInt())

            val fRolling = config.tireRollingCoeff * totalMass * GRAVITY * cos(theta)
            val fGravity = totalMass * GRAVITY * sin(theta)
            val fAero = 0.5 * AIR_DENSITY * config.aerodynamicCdA * speedMs.pow(2)
            val powerTotal = (fRolling + fGravity + fAero) * speedMs

            return SegmentEnergyResult(
                energyWh = 0.0,
                durationSeconds = durationSeconds,
                motorWatt = 0,
                riderWatt = if (powerTotal <= 0) 10 else min(450.0, max(40.0, powerTotal)).toInt()
            )
        }

        // Motor assisted cycling
        val maxSpeedLimit = min(assistLevel.maxSpeedKmh, config.maxAssistSpeedKmh)
        val effectiveSpeedKmh = max(5.0, min(maxSpeedLimit + 5.0, targetSpeedKmh))
        val speedMs = effectiveSpeedKmh / 3.6
        val durationSeconds = max(1, (distanceMeters / speedMs).toInt())

        // Physical forces
        val fRolling = config.tireRollingCoeff * totalMass * GRAVITY * cos(theta)
        val fGravity = totalMass * GRAVITY * sin(theta)
        val fAero = 0.5 * AIR_DENSITY * config.aerodynamicCdA * speedMs.pow(2)

        val fTotal = fRolling + fGravity + fAero
        val powerTotal = fTotal * speedMs // Mechanical Watts

        if (powerTotal <= 0) {
            // Downhill or coasting
            var regenWh = 0.0
            if (config.regenerativeBraking && gradePercent < -3.0) {
                regenWh = min(0.2, (kotlin.math.abs(gradePercent) / 100.0) * distanceMeters * 0.0005)
            }
            return SegmentEnergyResult(
                energyWh = -regenWh,
                durationSeconds = durationSeconds,
                motorWatt = 0,
                riderWatt = 10
            )
        }

        // Motor active: Share power with rider
        val totalRatio = 1.0 + assistLevel.motorAssistRatio
        val motorDemand = (powerTotal * (assistLevel.motorAssistRatio / totalRatio)) / config.motorEfficiency
        val effectiveMotorWatt = min(config.motorMaxWatt, motorDemand)
        val riderWatt = max(30.0, powerTotal - (effectiveMotorWatt * config.motorEfficiency))

        val energyWh = (effectiveMotorWatt * (durationSeconds / 3600.0))

        return SegmentEnergyResult(
            energyWh = (energyWh * 100).toInt() / 100.0,
            durationSeconds = durationSeconds,
            motorWatt = effectiveMotorWatt.toInt(),
            riderWatt = riderWatt.toInt()
        )
    }
}

import { Injectable, signal, computed } from '@angular/core';
import { AssistLevel, EBikeConfig, BatteryTelemetry, AssistLevelConfig } from '../models/ebike.types';

export const ASSIST_CONFIGS: Record<AssistLevel, AssistLevelConfig> = {
  OFF: {
    name: 'OFF',
    motorAssistRatio: 0.0,
    maxSpeedKmh: 45,
    label: 'No Assist (0%)',
    color: '#64748b',
    badgeClass: 'badge-off'
  },
  ECO: {
    name: 'ECO',
    motorAssistRatio: 0.4, // 40% motor contribution
    maxSpeedKmh: 25,
    label: 'Eco (40%)',
    color: '#10b981',
    badgeClass: 'badge-eco'
  },
  TOUR: {
    name: 'TOUR',
    motorAssistRatio: 1.0, // 100% motor match (1:1 with rider)
    maxSpeedKmh: 25,
    label: 'Tour (100%)',
    color: '#06b6d4',
    badgeClass: 'badge-tour'
  },
  SPORT: {
    name: 'SPORT',
    motorAssistRatio: 1.8, // 180% assist
    maxSpeedKmh: 25,
    label: 'Sport (180%)',
    color: '#f59e0b',
    badgeClass: 'badge-sport'
  },
  TURBO: {
    name: 'TURBO',
    motorAssistRatio: 3.0, // 300% assist (maximum punch for climbs)
    maxSpeedKmh: 25,
    label: 'Turbo (300%)',
    color: '#ef4444',
    badgeClass: 'badge-turbo'
  }
};

const GRAVITY = 9.81; // m/s^2
const AIR_DENSITY = 1.225; // kg/m^3

@Injectable({
  providedIn: 'root'
})
export class EBikePhysicsService {
  // Config state
  readonly config = signal<EBikeConfig>({
    batteryCapacityWh: 625,
    currentBatteryWh: 560, // default ~90%
    bikeWeightKg: 24,
    riderWeightKg: 75,
    motorMaxWatt: 250,
    motorEfficiency: 0.82,
    regenerativeBraking: true,
    activeAssist: 'TOUR',
    tireRollingCoeff: 0.0055,
    aerodynamicCdA: 0.38
  });

  // Battery Telemetry
  readonly batteryTelemetry = computed<BatteryTelemetry>(() => {
    const cfg = this.config();
    const pct = Math.max(0, Math.min(100, Math.round((cfg.currentBatteryWh / cfg.batteryCapacityWh) * 100)));
    const avgConsumption = this.getAverageWhPerKm(cfg.activeAssist);
    const rangeKm = avgConsumption > 0 ? Number((cfg.currentBatteryWh / avgConsumption).toFixed(1)) : 0;

    return {
      currentWh: Math.round(cfg.currentBatteryWh),
      maxWh: cfg.batteryCapacityWh,
      percentage: pct,
      estimatedRangeKm: rangeKm,
      instantPowerWatt: 0,
      instantConsumptionWhPerKm: avgConsumption,
      voltageApprox: Number((36 * (0.85 + 0.15 * (pct / 100))).toFixed(1))
    };
  });

  setAssist(level: AssistLevel): void {
    this.config.update(c => ({ ...c, activeAssist: level }));
  }

  updateBatteryWh(wh: number): void {
    this.config.update(c => ({
      ...c,
      currentBatteryWh: Math.max(0, Math.min(c.batteryCapacityWh, wh))
    }));
  }

  updateConfig(partial: Partial<EBikeConfig>): void {
    this.config.update(c => ({ ...c, ...partial }));
  }

  /**
   * Calculates energy consumption in Wh for a route segment
   */
  calculateSegmentEnergy(
    distanceMeters: number,
    gradePercent: number,
    speedKmh: number,
    assistLevel: AssistLevel = this.config().activeAssist
  ): { energyWh: number; durationSeconds: number; motorWatt: number; riderWatt: number } {
    const cfg = this.config();
    const assist = ASSIST_CONFIGS[assistLevel];

    // Cap speed based on assist limit or slope
    const effectiveSpeedKmh = Math.max(5, Math.min(assist.maxSpeedKmh + 10, speedKmh));
    const speedMs = effectiveSpeedKmh / 3.6;
    const durationSeconds = distanceMeters / speedMs;

    const totalMass = cfg.bikeWeightKg + cfg.riderWeightKg;
    const theta = Math.atan(gradePercent / 100);

    // Forces
    const fRolling = cfg.tireRollingCoeff * totalMass * GRAVITY * Math.cos(theta);
    const fGravity = totalMass * GRAVITY * Math.sin(theta);
    const fAero = 0.5 * AIR_DENSITY * cfg.aerodynamicCdA * Math.pow(speedMs, 2);

    const fTotal = fRolling + fGravity + fAero;
    let powerTotal = fTotal * speedMs; // Total mechanical power needed (Watts)

    let motorPower = 0;
    let riderPower = 0;

    if (powerTotal <= 0) {
      // Downhill or coasting
      motorPower = 0;
      riderPower = 10; // minimal freewheeling effort
      // Optional slight regen if steep downhill
      let regenWh = 0;
      if (cfg.regenerativeBraking && gradePercent < -3) {
        regenWh = Math.min(0.2, (Math.abs(gradePercent) / 100) * distanceMeters * 0.0005);
      }
      return {
        energyWh: -regenWh,
        durationSeconds,
        motorWatt: 0,
        riderWatt: riderPower
      };
    }

    if (assist.motorAssistRatio <= 0) {
      // Motor off
      motorPower = 0;
      riderPower = Math.min(450, powerTotal);
    } else {
      // Assist active: Motor shares proportion with rider
      const totalRatio = 1 + assist.motorAssistRatio;
      const desiredMotorPwr = (assist.motorAssistRatio / totalRatio) * powerTotal;
      motorPower = Math.min(cfg.motorMaxWatt, desiredMotorPwr);
      riderPower = Math.max(0, powerTotal - motorPower);
    }

    // Battery energy consumption: accounting for motor efficiency
    const batteryPowerWatt = motorPower > 0 ? motorPower / cfg.motorEfficiency : 0;
    const hours = durationSeconds / 3600;
    const energyWh = batteryPowerWatt * hours;

    return {
      energyWh: Number(energyWh.toFixed(2)),
      durationSeconds: Math.round(durationSeconds),
      motorWatt: Math.round(motorPower),
      riderWatt: Math.round(riderPower)
    };
  }

  /**
   * Average consumption estimation per kilometer for each assist mode on average flat/rolling terrain
   */
  getAverageWhPerKm(assistLevel: AssistLevel): number {
    switch (assistLevel) {
      case 'OFF':
        return 0;
      case 'ECO':
        return 5.2; // ~120km on 625Wh
      case 'TOUR':
        return 9.5; // ~65km on 625Wh
      case 'SPORT':
        return 14.5; // ~43km on 625Wh
      case 'TURBO':
        return 21.0; // ~30km on 625Wh
      default:
        return 9.5;
    }
  }

  /**
   * Estimates maximum reach radius (in km) from current battery level for each assist mode
   */
  getRangeForAssist(assistLevel: AssistLevel): number {
    const wh = this.config().currentBatteryWh;
    const avg = this.getAverageWhPerKm(assistLevel);
    if (avg <= 0) return 999;
    return Number((wh / avg).toFixed(1));
  }
}

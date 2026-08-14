export type AssistLevel = 'OFF' | 'ECO' | 'TOUR' | 'SPORT' | 'TURBO';

export interface AssistLevelConfig {
  name: AssistLevel;
  motorAssistRatio: number; // e.g. OFF: 0.0, ECO: 0.5, TOUR: 1.2, SPORT: 2.0, TURBO: 3.0
  maxSpeedKmh: number;
  label: string;
  color: string;
  badgeClass: string;
}

export interface EBikeConfig {
  batteryCapacityWh: number; // e.g. 500Wh, 625Wh, 750Wh
  currentBatteryWh: number;
  bikeWeightKg: number;      // e.g. 24kg
  riderWeightKg: number;     // e.g. 75kg
  motorMaxWatt: number;      // e.g. 250W or 500W
  motorEfficiency: number;   // e.g. 0.85
  regenerativeBraking: boolean;
  activeAssist: AssistLevel;
  tireRollingCoeff: number;  // 0.005 for road/commuter, 0.008 for gravel
  aerodynamicCdA: number;    // 0.4 m^2 for upright commuter
}

export interface BatteryTelemetry {
  currentWh: number;
  maxWh: number;
  percentage: number;
  estimatedRangeKm: number;
  instantPowerWatt: number;
  instantConsumptionWhPerKm: number;
  voltageApprox: number;
}

export interface LiveRideTelemetry {
  currentSpeedKmh: number;
  avgSpeedKmh: number;
  maxSpeedKmh: number;
  distanceRiddenKm: number;
  distanceRemainingKm: number;
  timeElapsedSeconds: number;
  timeRemainingSeconds: number;
  currentElevationM: number;
  elevationGainedM: number;
  currentGradePercent: number;
  motorPowerWatts: number;
  riderPowerWatts: number;
  activeAssist: AssistLevel;
  batteryTelemetry: BatteryTelemetry;
  headingDegrees: number;
  isNavigating: boolean;
  isPaused: boolean;
}

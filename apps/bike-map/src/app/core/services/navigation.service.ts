import { Injectable, inject, signal, computed, effect } from '@angular/core';
import { RouteResult, TurnInstruction, RoutingProfile, RouteWaypoint } from '../models/routing.types';
import { GeoPoint } from '../models/geo.types';
import { RiderPositionState, GpsTrackingService } from './gps-tracking.service';
import { LiveRideTelemetry, AssistLevel } from '../models/ebike.types';
import { GraphRouterService } from './graph-router.service';
import { AudioGuidanceService } from './audio-guidance.service';
import { EBikePhysicsService } from './ebike-physics.service';

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ';

@Injectable({
  providedIn: 'root'
})
export class NavigationService {
  private router = inject(GraphRouterService);
  private audio = inject(AudioGuidanceService);
  private gps = inject(GpsTrackingService);
  private physics = inject(EBikePhysicsService);

  // Multi-Stop Waypoints State
  readonly waypoints = signal<RouteWaypoint[]>([
    { id: 'wp_0', letter: 'A', label: 'Start Point (A)', point: null },
    { id: 'wp_1', letter: 'B', label: 'Destination (B)', point: null }
  ]);

  readonly activePickingWaypointIndex = signal<number | null>(null);
  readonly activeRoute = signal<RouteResult | null>(null);
  readonly currentInstruction = signal<TurnInstruction | null>(null);
  readonly currentInstructionIndex = signal<number>(0);
  readonly distanceToNextManeuverMeters = signal<number>(0);
  readonly isNavigating = signal<boolean>(false);
  readonly isPaused = signal<boolean>(false);

  readonly selectedProfile = signal<RoutingProfile>('efficient');
  readonly isOffRoute = signal<boolean>(false);
  readonly isCalculating = signal<boolean>(false);

  // Computed Origin & Destination
  readonly originPoint = computed(() => this.waypoints()[0]?.point || null);
  readonly destinationPoint = computed(() => {
    const list = this.waypoints();
    return list.length > 1 ? list[list.length - 1]?.point || null : null;
  });

  // Map Selection Mode
  readonly mapSelectionMode = computed(() => {
    const idx = this.activePickingWaypointIndex();
    if (idx === null) return 'none';
    if (idx === 0) return 'origin';
    if (idx === this.waypoints().length - 1) return 'destination';
    return 'none';
  });

  // Live Telemetry
  readonly telemetry = signal<LiveRideTelemetry>({
    currentSpeedKmh: 0,
    avgSpeedKmh: 0,
    maxSpeedKmh: 0,
    distanceRiddenKm: 0,
    distanceRemainingKm: 0,
    timeElapsedSeconds: 0,
    timeRemainingSeconds: 0,
    currentElevationM: 30,
    elevationGainedM: 0,
    currentGradePercent: 0,
    motorPowerWatts: 0,
    riderPowerWatts: 0,
    activeAssist: 'TOUR',
    batteryTelemetry: this.physics.batteryTelemetry(),
    headingDegrees: 0,
    isNavigating: false,
    isPaused: false
  });

  private rideTimer: ReturnType<typeof setInterval> | null = null;
  private totalSpeedSum = 0;
  private speedReadingCount = 0;
  private startElevation = 0;
  private lastPromptedInstructionIndex = -1;
  private announcedMilestones = new Set<string>();

  constructor() {
    effect(() => {
      const pos = this.gps.currentPosition();
      this.handlePositionUpdate(pos);
    });
  }

  // --- WAYPOINT MANAGEMENT ---

  setWaypoint(index: number, point: GeoPoint, label?: string): void {
    const list = [...this.waypoints()];
    if (index >= 0 && index < list.length) {
      list[index] = {
        ...list[index],
        point,
        label: label || `Stop ${list[index].letter} (${point.lat.toFixed(3)}, ${point.lng.toFixed(3)})`
      };
      this.waypoints.set(list);
    }
    this.activePickingWaypointIndex.set(null);
  }

  setOriginPoint(pt: GeoPoint, label?: string): void {
    this.setWaypoint(0, pt, label || `Start (A)`);
  }

  setDestinationPoint(pt: GeoPoint, label?: string): void {
    const list = this.waypoints();
    this.setWaypoint(list.length - 1, pt, label || `Destination (${list[list.length - 1].letter})`);
  }

  setOrigin(pt: GeoPoint | null): void {
    if (pt) this.setOriginPoint(pt);
  }

  setDestination(pt: GeoPoint | null): void {
    if (pt) this.setDestinationPoint(pt);
  }

  addWaypoint(point?: GeoPoint, label?: string): void {
    const list = [...this.waypoints()];
    const newIdx = list.length;
    const letter = ALPHABET[newIdx % ALPHABET.length];
    const newWp: RouteWaypoint = {
      id: `wp_${Date.now()}_${newIdx}`,
      letter,
      label: label || `Stop ${letter}`,
      point: point || null
    };
    list.push(newWp);
    this.waypoints.set(this.reindexLetters(list));
  }

  removeWaypoint(index: number): void {
    const list = [...this.waypoints()];
    if (list.length <= 2) return;
    list.splice(index, 1);
    this.waypoints.set(this.reindexLetters(list));
    if (this.hasEnoughValidPoints()) {
      this.recomputePlan();
    }
  }

  moveWaypoint(fromIdx: number, toIdx: number): void {
    const list = [...this.waypoints()];
    if (fromIdx < 0 || fromIdx >= list.length || toIdx < 0 || toIdx >= list.length) return;
    const [item] = list.splice(fromIdx, 1);
    list.splice(toIdx, 0, item);
    this.waypoints.set(this.reindexLetters(list));
    if (this.hasEnoughValidPoints()) {
      this.recomputePlan();
    }
  }

  reverseWaypoints(): void {
    const list = [...this.waypoints()].reverse();
    this.waypoints.set(this.reindexLetters(list));
    if (this.hasEnoughValidPoints()) {
      this.recomputePlan();
    }
  }

  private reindexLetters(list: RouteWaypoint[]): RouteWaypoint[] {
    return list.map((wp, idx) => ({
      ...wp,
      letter: ALPHABET[idx % ALPHABET.length],
      label: wp.point
        ? wp.label
        : idx === 0
        ? `Start Point (${ALPHABET[0]})`
        : idx === list.length - 1
        ? `Destination (${ALPHABET[idx % ALPHABET.length]})`
        : `Stop ${ALPHABET[idx % ALPHABET.length]}`
    }));
  }

  setPickingWaypointIndex(idx: number | null): void {
    this.activePickingWaypointIndex.set(idx);
  }

  setMapSelectionMode(mode: 'none' | 'origin' | 'destination'): void {
    if (mode === 'origin') this.activePickingWaypointIndex.set(0);
    else if (mode === 'destination') this.activePickingWaypointIndex.set(this.waypoints().length - 1);
    else this.activePickingWaypointIndex.set(null);
  }

  clearRoute(): void {
    this.waypoints.set([
      { id: 'wp_0', letter: 'A', label: 'Start Point (A)', point: null },
      { id: 'wp_1', letter: 'B', label: 'Destination (B)', point: null }
    ]);
    this.activeRoute.set(null);
    this.currentInstruction.set(null);
    this.isNavigating.set(false);
  }

  setProfile(profile: RoutingProfile): void {
    this.selectedProfile.set(profile);
    if (this.activeRoute() && this.hasEnoughValidPoints()) {
      this.recomputePlan();
    }
  }

  hasEnoughValidPoints(): boolean {
    const validPoints = this.waypoints().filter(w => w.point !== null);
    return validPoints.length >= 2;
  }

  async recomputePlan(): Promise<RouteResult | null> {
    const validWaypoints = this.waypoints().filter(w => w.point !== null);
    if (validWaypoints.length < 2) return null;

    const points = validWaypoints.map(w => w.point!);

    this.isCalculating.set(true);
    try {
      const route = await this.router.calculateMultiStopRoute(points, this.selectedProfile());
      if (route) {
        this.activeRoute.set(route);
        if (route.instructions.length > 0) {
          this.currentInstruction.set(route.instructions[0]);
        }
      }
      return route;
    } finally {
      this.isCalculating.set(false);
    }
  }

  // --- NAVIGATION LIFECYCLE ---

  startNavigation(route?: RouteResult): void {
    const targetRoute = route || this.activeRoute();
    if (!targetRoute) return;

    this.activeRoute.set(targetRoute);
    this.isNavigating.set(true);
    this.isPaused.set(false);
    this.currentInstructionIndex.set(0);
    this.lastPromptedInstructionIndex = -1;
    this.announcedMilestones.clear();

    if (targetRoute.instructions.length > 0) {
      this.currentInstruction.set(targetRoute.instructions[0]);
      this.audio.speak(`Navigation started. ${targetRoute.instructions[0].text}`);
    }

    this.startRideTimer(targetRoute);
  }

  pauseNavigation(): void {
    this.isPaused.set(true);
  }

  resumeNavigation(): void {
    this.isPaused.set(false);
  }

  stopNavigation(): void {
    this.isNavigating.set(false);
    this.isPaused.set(false);
    if (this.rideTimer) {
      clearInterval(this.rideTimer);
      this.rideTimer = null;
    }
    this.audio.speak('Navigation ended.');
  }

  setAssistLevel(level: AssistLevel): void {
    this.physics.setAssist(level);
    this.telemetry.update(t => ({
      ...t,
      activeAssist: level,
      batteryTelemetry: this.physics.batteryTelemetry()
    }));
  }

  private startRideTimer(route: RouteResult): void {
    if (this.rideTimer) clearInterval(this.rideTimer);

    this.totalSpeedSum = 0;
    this.speedReadingCount = 0;
    this.startElevation = this.gps.currentPosition().point.ele || 30;

    let elapsed = 0;
    this.rideTimer = setInterval(() => {
      if (this.isPaused()) return;
      elapsed++;

      const currentPos = this.gps.currentPosition();
      const speed = currentPos.speedKmh || 0;

      this.totalSpeedSum += speed;
      this.speedReadingCount++;
      const avgSpeed = this.speedReadingCount > 0 ? this.totalSpeedSum / this.speedReadingCount : 0;

      const progressRatio = Math.min(1, elapsed / (route.totalDurationSeconds || 1));
      const distRidden = (route.totalDistanceMeters * progressRatio) / 1000;
      const distRem = Math.max(0, (route.totalDistanceMeters / 1000) - distRidden);
      const timeRem = Math.max(0, route.totalDurationSeconds - elapsed);

      const curEle = currentPos.point.ele || 30;
      const eleGain = Math.max(0, curEle - this.startElevation);

      this.telemetry.set({
        currentSpeedKmh: Number(speed.toFixed(1)),
        avgSpeedKmh: Number(avgSpeed.toFixed(1)),
        maxSpeedKmh: Math.max(this.telemetry().maxSpeedKmh, speed),
        distanceRiddenKm: Number(distRidden.toFixed(2)),
        distanceRemainingKm: Number(distRem.toFixed(1)),
        timeElapsedSeconds: elapsed,
        timeRemainingSeconds: timeRem,
        currentElevationM: curEle,
        elevationGainedM: eleGain,
        currentGradePercent: 0,
        motorPowerWatts: this.calculateEstimatedMotorWatts(speed, 0),
        riderPowerWatts: this.calculateEstimatedRiderWatts(speed),
        activeAssist: this.physics.config().activeAssist,
        batteryTelemetry: this.physics.batteryTelemetry(),
        headingDegrees: currentPos.heading || 0,
        isNavigating: true,
        isPaused: this.isPaused()
      });
    }, 1000);
  }

  private handlePositionUpdate(pos: RiderPositionState): void {
    const route = this.activeRoute();
    if (!this.isNavigating() || !route || route.instructions.length === 0) return;

    const instructions = route.instructions;
    const activeIdx = this.currentInstructionIndex();
    const currentManeuver = instructions[activeIdx];
    if (!currentManeuver) return;

    const distToManeuver = this.haversineDistance(pos.point, currentManeuver.point);
    this.distanceToNextManeuverMeters.set(distToManeuver);

    if (distToManeuver > 200 && activeIdx > 0) {
      this.isOffRoute.set(true);
      this.handleOffRouteRecalculation(pos.point);
      return;
    } else {
      this.isOffRoute.set(false);
    }

    if (distToManeuver <= 50 && this.lastPromptedInstructionIndex !== activeIdx) {
      this.audio.speak(`In 50 meters, ${currentManeuver.text}`, true);
      this.lastPromptedInstructionIndex = activeIdx;
    }

    if (distToManeuver <= 15) {
      if (activeIdx < instructions.length - 1) {
        const nextIdx = activeIdx + 1;
        this.currentInstructionIndex.set(nextIdx);
        const nextManeuver = instructions[nextIdx];
        this.currentInstruction.set(nextManeuver);
        this.audio.speak(nextManeuver.text);
      }
    }

    if (distToManeuver < 15 && activeIdx === instructions.length - 1) {
      this.audio.speak('You have arrived at your destination! Great ride.');
      this.stopNavigation();
    }
  }

  private async handleOffRouteRecalculation(currentPoint: GeoPoint): Promise<void> {
    const dest = this.destinationPoint();
    if (!dest) return;

    const newRoute = await this.router.calculateRoute(currentPoint, dest, this.selectedProfile());
    if (newRoute) {
      this.activeRoute.set(newRoute);
      this.currentInstructionIndex.set(0);
      this.announcedMilestones.clear();
      if (newRoute.instructions.length > 0) {
        this.currentInstruction.set(newRoute.instructions[0]);
        this.audio.speak(`New route found. ${newRoute.instructions[0].text}`);
      }
    }
  }

  private calculateEstimatedMotorWatts(speedKmh: number, gradePercent: number): number {
    const maxPower = this.physics.config().motorMaxWatt;
    const assist = this.physics.config().activeAssist;
    const mult = assist === 'TURBO' ? 1.0 : assist === 'SPORT' ? 0.75 : assist === 'TOUR' ? 0.5 : assist === 'ECO' ? 0.25 : 0;
    const hillBoost = gradePercent > 0 ? (gradePercent / 12) * 0.5 : 0;
    return Math.round(Math.min(maxPower, (speedKmh * 10 + hillBoost * maxPower) * mult));
  }

  private calculateEstimatedRiderWatts(speedKmh: number): number {
    return Math.round(Math.max(50, speedKmh * 6));
  }

  private haversineDistance(p1: GeoPoint, p2: GeoPoint): number {
    const R = 6371000;
    const dLat = ((p2.lat - p1.lat) * Math.PI) / 180;
    const dLng = ((p2.lng - p1.lng) * Math.PI) / 180;
    const a =
      Math.sin(dLat / 2) * Math.sin(dLat / 2) +
      Math.cos((p1.lat * Math.PI) / 180) * Math.cos((p2.lat * Math.PI) / 180) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
    return Math.round(R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)));
  }
}

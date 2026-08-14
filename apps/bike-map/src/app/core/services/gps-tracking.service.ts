import { Injectable, signal, inject } from '@angular/core';
import { Geolocation, Position } from '@capacitor/geolocation';
import { Haptics, ImpactStyle } from '@capacitor/haptics';
import { GeoPoint } from '../models/geo.types';
import { RouteResult } from '../models/routing.types';
import { OfflineNetworkService } from './offline-network.service';

export interface RiderPositionState {
  point: GeoPoint;
  speedKmh: number;
  heading: number; // 0-360 degrees
  accuracy: number;
  timestamp: number;
  isSimulated: boolean;
}

@Injectable({
  providedIn: 'root'
})
export class GpsTrackingService {
  private networkService = inject(OfflineNetworkService);

  readonly currentPosition = signal<RiderPositionState>({
    point: { lat: 37.7749, lng: -122.4194, ele: 30 },
    speedKmh: 0,
    heading: 0,
    accuracy: 5,
    timestamp: Date.now(),
    isSimulated: false
  });

  readonly isTracking = signal<boolean>(false);
  readonly isSimulatorRunning = signal<boolean>(false);
  readonly simulationSpeedMultiplier = signal<number>(2); // 1x, 2x, 4x

  private watchId: string | null = null;
  private simulationInterval: ReturnType<typeof setInterval> | null = null;
  private simCoordIndex = 0;
  private simActiveRoute: RouteResult | null = null;

  async startTracking(): Promise<void> {
    if (this.isTracking()) return;

    try {
      // Check permissions
      const status = await Geolocation.checkPermissions();
      if (status.location !== 'granted') {
        await Geolocation.requestPermissions();
      }

      // Initial quick fix
      const pos = await Geolocation.getCurrentPosition({ enableHighAccuracy: true });
      this.updateFromCapacitorPos(pos);

      // Watch continuously
      this.watchId = await Geolocation.watchPosition(
        { enableHighAccuracy: true, timeout: 5000, maximumAge: 1000 },
        (position, err) => {
          if (err || !position) return;
          this.updateFromCapacitorPos(position);
        }
      );

      this.isTracking.set(true);
    } catch {
      // Fallback to HTML5 Geolocation API
      if (typeof navigator !== 'undefined' && 'geolocation' in navigator) {
        const id = navigator.geolocation.watchPosition(
          pos => this.updateFromBrowserPos(pos),
          () => {},
          { enableHighAccuracy: true }
        );
        this.watchId = String(id);
        this.isTracking.set(true);
      }
    }
  }

  async stopTracking(): Promise<void> {
    if (this.watchId !== null) {
      try {
        await Geolocation.clearWatch({ id: this.watchId });
      } catch {
        if (typeof navigator !== 'undefined') {
          navigator.geolocation.clearWatch(Number(this.watchId));
        }
      }
      this.watchId = null;
    }
    this.stopSimulator();
    this.isTracking.set(false);
  }

  /**
   * Starts GPS ride simulation following the calculated route points
   */
  startSimulator(route: RouteResult): void {
    this.stopSimulator();
    if (!route || route.coordinates.length < 2) return;

    this.simActiveRoute = route;
    this.simCoordIndex = 0;
    this.isSimulatorRunning.set(true);

    const firstPt = route.coordinates[0];
    this.currentPosition.set({
      point: firstPt,
      speedKmh: 22,
      heading: 0,
      accuracy: 2,
      timestamp: Date.now(),
      isSimulated: true
    });

    const stepIntervalMs = 1000;

    this.simulationInterval = setInterval(() => {
      if (!this.simActiveRoute) return;

      this.simCoordIndex += 1;
      if (this.simCoordIndex >= this.simActiveRoute.coordinates.length) {
        // Arrived at destination
        this.stopSimulator();
        return;
      }

      const prevPt = this.simActiveRoute.coordinates[this.simCoordIndex - 1];
      const curPt = this.simActiveRoute.coordinates[this.simCoordIndex];

      const dist = this.networkService.haversineDistance(prevPt, curPt);
      const heading = this.calculateBearing(prevPt, curPt);
      const simulatedSpeed = 22 * this.simulationSpeedMultiplier();

      this.currentPosition.set({
        point: curPt,
        speedKmh: simulatedSpeed,
        heading,
        accuracy: 1,
        timestamp: Date.now(),
        isSimulated: true
      });
    }, stepIntervalMs / this.simulationSpeedMultiplier());
  }

  stopSimulator(): void {
    if (this.simulationInterval) {
      clearInterval(this.simulationInterval);
      this.simulationInterval = null;
    }
    this.isSimulatorRunning.set(false);
    this.simActiveRoute = null;
  }

  setSimulatorSpeed(multiplier: number): void {
    this.simulationSpeedMultiplier.set(multiplier);
    if (this.isSimulatorRunning() && this.simActiveRoute) {
      // restart timer with new speed
      const curIdx = this.simCoordIndex;
      const curRoute = this.simActiveRoute;
      this.startSimulator(curRoute);
      this.simCoordIndex = curIdx;
    }
  }

  async triggerTurnHaptic(): Promise<void> {
    try {
      await Haptics.impact({ style: ImpactStyle.Heavy });
    } catch {
      // Haptics not available on desktop browser
    }
  }

  private updateFromCapacitorPos(pos: Position): void {
    this.currentPosition.set({
      point: {
        lat: pos.coords.latitude,
        lng: pos.coords.longitude,
        ele: pos.coords.altitude ?? 30
      },
      speedKmh: pos.coords.speed ? Math.round(pos.coords.speed * 3.6) : 0,
      heading: pos.coords.heading ?? 0,
      accuracy: pos.coords.accuracy,
      timestamp: pos.timestamp,
      isSimulated: false
    });
  }

  private updateFromBrowserPos(pos: GeolocationPosition): void {
    this.currentPosition.set({
      point: {
        lat: pos.coords.latitude,
        lng: pos.coords.longitude,
        ele: pos.coords.altitude ?? 30
      },
      speedKmh: pos.coords.speed ? Math.round(pos.coords.speed * 3.6) : 0,
      heading: pos.coords.heading ?? 0,
      accuracy: pos.coords.accuracy,
      timestamp: pos.timestamp,
      isSimulated: false
    });
  }

  private calculateBearing(start: GeoPoint, end: GeoPoint): number {
    const lat1 = (start.lat * Math.PI) / 180;
    const lat2 = (end.lat * Math.PI) / 180;
    const dLng = ((end.lng - start.lng) * Math.PI) / 180;

    const y = Math.sin(dLng) * Math.cos(lat2);
    const x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng);
    const brng = (Math.atan2(y, x) * 180) / Math.PI;
    return Math.round((brng + 360) % 360);
  }
}

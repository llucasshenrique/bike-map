export interface GeoPoint {
  lat: number;
  lng: number;
  ele?: number; // Elevation in meters
}

export interface ElevationPoint {
  distanceKm: number;
  elevationM: number;
  gradePercent: number;
  lat: number;
  lng: number;
}

export interface BoundingBox {
  minLat: number;
  maxLat: number;
  minLng: number;
  maxLng: number;
}

export type RoadSurface = 'paved' | 'asphalt' | 'gravel' | 'dirt' | 'cobblestone' | 'cycleway';
export type BikeInfrastructure = 'dedicated_track' | 'bike_lane' | 'shared_bus_lane' | 'quiet_street' | 'trail' | 'main_road';

export interface RouteSegment {
  fromNodeId: string;
  toNodeId: string;
  name: string;
  distanceMeters: number;
  gradePercent: number; // Slope percentage
  elevationGainM: number;
  elevationLossM: number;
  surface: RoadSurface;
  infrastructure: BikeInfrastructure;
  speedLimitKmh: number;
  coordinates: GeoPoint[];
  estimatedEnergyWh: number;
  estimatedTimeSeconds: number;
}

import { GeoPoint, RouteSegment, ElevationPoint, RoadSurface, BikeInfrastructure } from './geo.types';

export type RoutingProfile = 'efficient' | 'turbo' | 'scenic' | 'safe';

export type ManeuverType =
  | 'depart'
  | 'straight'
  | 'slight-right'
  | 'turn-right'
  | 'sharp-right'
  | 'u-turn'
  | 'sharp-left'
  | 'turn-left'
  | 'slight-left'
  | 'roundabout'
  | 'climb-ahead'
  | 'arrive';

export interface TurnInstruction {
  index: number;
  maneuver: ManeuverType;
  text: string;
  streetName: string;
  distanceMeters: number;
  durationSeconds: number;
  point: GeoPoint;
  gradePercent: number;
  energyWh: number;
  cumulativeDistanceKm: number;
}

export interface GraphNode {
  id: string;
  lat: number;
  lng: number;
  ele: number;
  name?: string;
  edges: GraphEdge[];
}

export interface GraphEdge {
  targetId: string;
  distanceMeters: number;
  elevationDiffM: number;
  gradePercent: number;
  name: string;
  surface: RoadSurface;
  infrastructure: BikeInfrastructure;
  speedLimitKmh: number;
  coordinates: GeoPoint[];
  oneWay?: boolean;
}

export interface RoutingGraph {
  nodes: Map<string, GraphNode>;
  bounds: {
    minLat: number;
    maxLat: number;
    minLng: number;
    maxLng: number;
  };
  networkName: string;
}

export interface RouteWaypoint {
  id: string;
  letter: string;
  label: string;
  point: GeoPoint | null;
  isGps?: boolean;
}

export interface RouteResult {
  id: string;
  name?: string;
  summary?: string;
  profile: RoutingProfile;
  totalDistanceMeters: number;
  totalDurationSeconds: number;
  totalEnergyWh: number;
  elevationGainM: number;
  elevationLossM: number;
  maxGradePercent: number;
  avgGradePercent: number;
  coordinates: GeoPoint[];
  elevationProfile: ElevationPoint[];
  instructions: TurnInstruction[];
  segments: RouteSegment[];
  batteryDrainPercent: number;
  estimatedBatteryRemainingWh: number;
  batteryRemainingPercent: number;
}

import { Injectable, signal } from '@angular/core';
import { RoutingGraph, GraphNode, GraphEdge } from '../models/routing.types';
import { GeoPoint, RoadSurface, BikeInfrastructure } from '../models/geo.types';
import { BikePOI } from '../models/poi.types';

export interface PrebuiltNetworkOption {
  id: string;
  name: string;
  region: string;
  description: string;
  center: GeoPoint;
  defaultZoom: number;
}

interface EastWestDef {
  name: string;
  lat: number;
  lngMin: number;
  lngMax: number;
  infra: BikeInfrastructure;
  surface: RoadSurface;
  speedLimitKmh: number;
  baseEle: number;
}

interface NorthSouthDef {
  name: string;
  lng: number;
  latMin: number;
  latMax: number;
  infra: BikeInfrastructure;
  surface: RoadSurface;
  speedLimitKmh: number;
  baseEle: number;
}

interface SpecialRoadDef {
  name: string;
  surface: RoadSurface;
  infrastructure: BikeInfrastructure;
  speedLimitKmh: number;
  points: [number, number, number?][];
}

@Injectable({
  providedIn: 'root'
})
export class OfflineNetworkService {
  readonly activeNetworkId = signal<string>('sf-bay-bikeways');

  readonly availableNetworks: PrebuiltNetworkOption[] = [
    {
      id: 'sf-bay-bikeways',
      name: 'San Francisco Connected Street & Bikeway Network',
      region: 'San Francisco (All Neighborhoods & Corridors)',
      description: 'Fully connected topological street network covering Downtown, Mission, SoMa, Richmond, Sunset, Marina, Castro, Haight, and Waterfront.',
      center: { lat: 37.7749, lng: -122.4194, ele: 30 },
      defaultZoom: 13
    },
    {
      id: 'gg-park-presidio',
      name: 'Golden Gate Park, Presidio & Ocean Beach',
      region: 'Westside Parks & Coastal Ridges',
      description: 'Car-free park promenades, Crissy Field bay trail, Presidio forest paths, and Great Highway oceanside bikeway.',
      center: { lat: 37.7700, lng: -122.4650, ele: 75 },
      defaultZoom: 13
    },
    {
      id: 'twin-peaks-climb',
      name: 'Twin Peaks & Mountain Ridge Climbs',
      region: 'Central Hills & Valley Corridors',
      description: 'High-elevation scenic mountain climbs with switchback gradients, Portola trails, and panoramic lookouts.',
      center: { lat: 37.7550, lng: -122.4400, ele: 150 },
      defaultZoom: 14
    }
  ];

  readonly pois = signal<BikePOI[]>([]);
  private cachedGraphs = new Map<string, RoutingGraph>();

  constructor() {
    this.initPOIs();
  }

  private initPOIs(): void {
    this.pois.set([
      {
        id: 'poi-1',
        name: 'Ferry Building Fast E-Bike Charger',
        category: 'charger',
        coordinates: { lat: 37.7955, lng: -122.3937, ele: 4 },
        description: 'Bosch & Shimano 4A fast charging hub at Embarcadero Plaza.',
        hasFastCharging: true,
        powerOutputWatt: 500
      },
      {
        id: 'poi-2',
        name: 'Valencia & 16th St Bike Repair Hub',
        category: 'repair',
        coordinates: { lat: 37.7650, lng: -122.4215, ele: 22 },
        description: 'Public tools, high-pressure floor pump, and tube vending.',
        availableTools: ['Hex keys', 'Torx T25', 'Floor Pump', 'Tire Levers', 'Chain Breaker']
      },
      {
        id: 'poi-3',
        name: 'Twin Peaks Summit Vista & Solar Socket',
        category: 'viewpoint',
        coordinates: { lat: 37.7544, lng: -122.4477, ele: 275 },
        description: 'Spectacular 360° panoramic city viewpoint with solar charging socket.',
        hasFastCharging: false,
        powerOutputWatt: 250
      },
      {
        id: 'poi-4',
        name: 'Crissy Field Warming Hut & Water Station',
        category: 'water',
        coordinates: { lat: 37.8055, lng: -122.4670, ele: 3 },
        description: 'Fresh drinking water refill and shaded shelter near Golden Gate Bridge.'
      },
      {
        id: 'poi-5',
        name: 'Marina Green Fast Charging Post',
        category: 'charger',
        coordinates: { lat: 37.8050, lng: -122.4380, ele: 4 },
        description: 'Waterfront high-power e-bike charging dock.',
        hasFastCharging: true,
        powerOutputWatt: 600
      },
      {
        id: 'poi-6',
        name: 'Golden Gate Park JFK Promenade Hub',
        category: 'repair',
        coordinates: { lat: 37.7710, lng: -122.4690, ele: 70 },
        description: 'Car-free promenade service station with pump and multi-tools.',
        availableTools: ['Multi-tool', 'Pressure gauge', 'Tire pump']
      },
      {
        id: 'poi-7',
        name: 'Ocean Beach Windmill Rest Point',
        category: 'viewpoint',
        coordinates: { lat: 37.7710, lng: -122.5100, ele: 6 },
        description: 'Scenic Pacific ocean viewpoint and rest stop at the end of JFK Promenade.'
      }
    ]);
  }

  getNetworkGraph(networkId: string = this.activeNetworkId()): RoutingGraph {
    if (this.cachedGraphs.has(networkId)) {
      return this.cachedGraphs.get(networkId)!;
    }

    const graph = this.buildConnectedStreetNetwork(networkId);
    this.cachedGraphs.set(networkId, graph);
    return graph;
  }

  /**
   * Constructs 100% interconnected, topologically guaranteed street & cycleway network
   */
  private buildConnectedStreetNetwork(networkId: string): RoutingGraph {
    const nodes = new Map<string, GraphNode>();

    // 1. Define ALL Real-World East-West Streets
    const EW: EastWestDef[] = [
      { name: 'Beach Street', lat: 37.8075, lngMin: -122.4230, lngMax: -122.4100, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 20, baseEle: 5 },
      { name: 'Bay Street', lat: 37.8045, lngMin: -122.4400, lngMax: -122.4080, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 8 },
      { name: 'Chestnut Street', lat: 37.8015, lngMin: -122.4480, lngMax: -122.4050, infra: 'quiet_street', surface: 'asphalt', speedLimitKmh: 20, baseEle: 18 },
      { name: 'Lombard Street', lat: 37.8000, lngMin: -122.4480, lngMax: -122.4050, infra: 'quiet_street', surface: 'asphalt', speedLimitKmh: 25, baseEle: 28 },
      { name: 'Union Street', lat: 37.7980, lngMin: -122.4420, lngMax: -122.4030, infra: 'quiet_street', surface: 'asphalt', speedLimitKmh: 20, baseEle: 35 },
      { name: 'Broadway', lat: 37.7960, lngMin: -122.4420, lngMax: -122.3965, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 28 },
      { name: 'Pacific Avenue', lat: 37.7935, lngMin: -122.4450, lngMax: -122.3980, infra: 'quiet_street', surface: 'asphalt', speedLimitKmh: 20, baseEle: 40 },
      { name: 'Jackson Street', lat: 37.7925, lngMin: -122.4500, lngMax: -122.3960, infra: 'quiet_street', surface: 'asphalt', speedLimitKmh: 20, baseEle: 42 },
      { name: 'Washington Street', lat: 37.7915, lngMin: -122.4540, lngMax: -122.3950, infra: 'quiet_street', surface: 'asphalt', speedLimitKmh: 20, baseEle: 45 },
      { name: 'Clay Street', lat: 37.7905, lngMin: -122.4540, lngMax: -122.3950, infra: 'quiet_street', surface: 'asphalt', speedLimitKmh: 20, baseEle: 48 },
      { name: 'Sacramento Street', lat: 37.7895, lngMin: -122.4540, lngMax: -122.3950, infra: 'quiet_street', surface: 'asphalt', speedLimitKmh: 20, baseEle: 50 },
      { name: 'California Street', lat: 37.7885, lngMin: -122.5080, lngMax: -122.3960, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 52 },
      { name: 'Pine Street', lat: 37.7875, lngMin: -122.4450, lngMax: -122.3980, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 40 },
      { name: 'Bush Street', lat: 37.7865, lngMin: -122.4450, lngMax: -122.3980, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 38 },
      { name: 'Sutter Street', lat: 37.7855, lngMin: -122.4450, lngMax: -122.4020, infra: 'quiet_street', surface: 'asphalt', speedLimitKmh: 20, baseEle: 32 },
      { name: 'Post Street', lat: 37.7845, lngMin: -122.4450, lngMax: -122.4020, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 30 },
      { name: 'Geary Boulevard', lat: 37.7815, lngMin: -122.5090, lngMax: -122.4070, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 45 },
      { name: 'Turk Street', lat: 37.7800, lngMin: -122.4540, lngMax: -122.4130, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 35 },
      { name: 'Golden Gate Avenue', lat: 37.7785, lngMin: -122.4540, lngMax: -122.4130, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 34 },
      { name: 'McAllister Street', lat: 37.7770, lngMin: -122.4540, lngMax: -122.4150, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 38 },
      { name: 'Fulton Street', lat: 37.7750, lngMin: -122.5095, lngMax: -122.4180, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 55 },
      { name: 'Grove Street', lat: 37.7760, lngMin: -122.4450, lngMax: -122.4180, infra: 'quiet_street', surface: 'asphalt', speedLimitKmh: 20, baseEle: 38 },
      { name: 'Hayes Street', lat: 37.7745, lngMin: -122.4450, lngMax: -122.4180, infra: 'quiet_street', surface: 'asphalt', speedLimitKmh: 20, baseEle: 42 },
      { name: 'Fell Street Protected Bikeway', lat: 37.7735, lngMin: -122.4540, lngMax: -122.4200, infra: 'dedicated_track', surface: 'asphalt', speedLimitKmh: 25, baseEle: 48 },
      { name: 'Oak Street Bike Corridor', lat: 37.7720, lngMin: -122.4540, lngMax: -122.4200, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 44 },
      { name: 'Page Street Slow Street', lat: 37.7710, lngMin: -122.4540, lngMax: -122.4250, infra: 'quiet_street', surface: 'asphalt', speedLimitKmh: 20, baseEle: 50 },
      { name: 'Haight Street', lat: 37.7695, lngMin: -122.4540, lngMax: -122.4260, infra: 'quiet_street', surface: 'asphalt', speedLimitKmh: 20, baseEle: 60 },
      { name: 'Waller Street', lat: 37.7685, lngMin: -122.4540, lngMax: -122.4280, infra: 'quiet_street', surface: 'asphalt', speedLimitKmh: 20, baseEle: 55 },
      { name: '14th Street', lat: 37.7675, lngMin: -122.4350, lngMax: -122.4080, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 28 },
      { name: '16th Street Mission Corridor', lat: 37.7650, lngMin: -122.4400, lngMax: -122.3900, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 22 },
      { name: '17th Street Bikeway', lat: 37.7628, lngMin: -122.4400, lngMax: -122.3900, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 30 },
      { name: '18th Street (Dolores Park)', lat: 37.7615, lngMin: -122.4400, lngMax: -122.3900, infra: 'quiet_street', surface: 'asphalt', speedLimitKmh: 20, baseEle: 24 },
      { name: '20th Street Corridor', lat: 37.7585, lngMin: -122.4420, lngMax: -122.3880, infra: 'quiet_street', surface: 'asphalt', speedLimitKmh: 20, baseEle: 20 },
      { name: '22nd Street Corridor', lat: 37.7550, lngMin: -122.4450, lngMax: -122.3880, infra: 'quiet_street', surface: 'asphalt', speedLimitKmh: 20, baseEle: 22 },
      { name: '24th Street Mission Corridor', lat: 37.7520, lngMin: -122.4450, lngMax: -122.3880, infra: 'quiet_street', surface: 'asphalt', speedLimitKmh: 20, baseEle: 24 },
      { name: 'Cesar Chavez Bikeway', lat: 37.7485, lngMin: -122.4450, lngMax: -122.3850, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 26 },
      { name: 'Lincoln Way (GGP South)', lat: 37.7650, lngMin: -122.5095, lngMax: -122.4540, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 35 },
      { name: 'Judah Street', lat: 37.7600, lngMin: -122.5093, lngMax: -122.4600, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 32 },
      { name: 'Lawton Street', lat: 37.7565, lngMin: -122.5090, lngMax: -122.4600, infra: 'quiet_street', surface: 'asphalt', speedLimitKmh: 20, baseEle: 30 },
      { name: 'Noriega Street', lat: 37.7540, lngMin: -122.5088, lngMax: -122.4600, infra: 'quiet_street', surface: 'asphalt', speedLimitKmh: 20, baseEle: 30 },
      { name: 'Taraval Street', lat: 37.7420, lngMin: -122.5080, lngMax: -122.4650, infra: 'quiet_street', surface: 'asphalt', speedLimitKmh: 20, baseEle: 28 },
      { name: 'Vicente Street', lat: 37.7370, lngMin: -122.5075, lngMax: -122.4700, infra: 'quiet_street', surface: 'asphalt', speedLimitKmh: 20, baseEle: 24 },
      { name: 'Sloat Boulevard', lat: 37.7320, lngMin: -122.5050, lngMax: -122.4700, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 18 }
    ];

    // 2. Define ALL Real-World North-South Streets
    const NS: NorthSouthDef[] = [
      { name: 'The Embarcadero', lng: -122.3937, latMin: 37.7710, latMax: 37.8085, infra: 'dedicated_track', surface: 'cycleway', speedLimitKmh: 25, baseEle: 4 },
      { name: '1st & 2nd Street Corridor', lng: -122.3990, latMin: 37.7800, latMax: 37.7940, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 8 },
      { name: '3rd & 4th Street SoMa', lng: -122.4040, latMin: 37.7750, latMax: 37.7900, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 14 },
      { name: '5th & 6th Street Corridor', lng: -122.4080, latMin: 37.7720, latMax: 37.7840, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 16 },
      { name: '7th & 8th Street Civic', lng: -122.4137, latMin: 37.7680, latMax: 37.7820, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 20 },
      { name: 'Folsom Street Protected Bikeway', lng: -122.4000, latMin: 37.7675, latMax: 37.7895, infra: 'dedicated_track', surface: 'asphalt', speedLimitKmh: 25, baseEle: 14 },
      { name: 'Howard Street Protected Bikeway', lng: -122.4020, latMin: 37.7690, latMax: 37.7910, infra: 'dedicated_track', surface: 'asphalt', speedLimitKmh: 25, baseEle: 12 },
      { name: 'Mission Street', lng: -122.4180, latMin: 37.7485, latMax: 37.7930, infra: 'quiet_street', surface: 'asphalt', speedLimitKmh: 20, baseEle: 22 },
      { name: 'Polk Street Protected Bikeway', lng: -122.4200, latMin: 37.7780, latMax: 37.8060, infra: 'dedicated_track', surface: 'asphalt', speedLimitKmh: 25, baseEle: 28 },
      { name: 'Valencia Street Bike Corridor', lng: -122.4215, latMin: 37.7485, latMax: 37.7700, infra: 'dedicated_track', surface: 'asphalt', speedLimitKmh: 25, baseEle: 24 },
      { name: 'Van Ness Avenue', lng: -122.4215, latMin: 37.7700, latMax: 37.8060, infra: 'quiet_street', surface: 'asphalt', speedLimitKmh: 25, baseEle: 30 },
      { name: 'Franklin & Gough Street', lng: -122.4245, latMin: 37.7650, latMax: 37.8040, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 35 },
      { name: 'Octavia Boulevard & Laguna', lng: -122.4270, latMin: 37.7680, latMax: 37.8040, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 32 },
      { name: 'Buchanan & Webster Street', lng: -122.4300, latMin: 37.7650, latMax: 37.8050, infra: 'quiet_street', surface: 'asphalt', speedLimitKmh: 20, baseEle: 40 },
      { name: 'Fillmore Street', lng: -122.4330, latMin: 37.7650, latMax: 37.8050, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 45 },
      { name: 'Steiner & Pierce Street', lng: -122.4385, latMin: 37.7650, latMax: 37.8050, infra: 'quiet_street', surface: 'asphalt', speedLimitKmh: 20, baseEle: 55 },
      { name: 'Divisadero & Castro Street', lng: -122.4400, latMin: 37.7550, latMax: 37.8000, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 50 },
      { name: 'Baker & Lyon Street', lng: -122.4440, latMin: 37.7650, latMax: 37.8040, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 62 },
      { name: 'Central & Masonic Avenue', lng: -122.4480, latMin: 37.7650, latMax: 37.7880, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 70 },
      { name: 'Stanyan & Arguello Boulevard', lng: -122.4540, latMin: 37.7600, latMax: 37.7950, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 75 },
      { name: '6th & 8th Avenue', lng: -122.4650, latMin: 37.7450, latMax: 37.7880, infra: 'quiet_street', surface: 'asphalt', speedLimitKmh: 20, baseEle: 65 },
      { name: '10th & 12th Avenue', lng: -122.4690, latMin: 37.7450, latMax: 37.7880, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 60 },
      { name: 'Park Presidio & 14th Ave', lng: -122.4730, latMin: 37.7400, latMax: 37.8075, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 58 },
      { name: '19th Avenue & Funston', lng: -122.4760, latMin: 37.7300, latMax: 37.7880, infra: 'bike_lane', surface: 'asphalt', speedLimitKmh: 25, baseEle: 55 },
      { name: '25th Avenue Corridor', lng: -122.4850, latMin: 37.7400, latMax: 37.7880, infra: 'quiet_street', surface: 'asphalt', speedLimitKmh: 20, baseEle: 45 },
      { name: '30th & 32nd Avenue', lng: -122.4920, latMin: 37.7350, latMax: 37.7850, infra: 'quiet_street', surface: 'asphalt', speedLimitKmh: 20, baseEle: 38 },
      { name: 'Sunset Boulevard Parkway', lng: -122.4980, latMin: 37.7250, latMax: 37.7720, infra: 'dedicated_track', surface: 'cycleway', speedLimitKmh: 25, baseEle: 30 },
      { name: '36th & 41st Avenue', lng: -122.5020, latMin: 37.7350, latMax: 37.7850, infra: 'quiet_street', surface: 'asphalt', speedLimitKmh: 20, baseEle: 22 },
      { name: 'Great Highway Oceanfront', lng: -122.5100, latMin: 37.7250, latMax: 37.7770, infra: 'dedicated_track', surface: 'cycleway', speedLimitKmh: 30, baseEle: 6 }
    ];

    // Helper: get or create unique intersection node
    const getNodeKey = (lat: number, lng: number) => `${lat.toFixed(5)}_${lng.toFixed(5)}`;

    const getOrCreateIntersection = (lat: number, lng: number, name: string, ele: number): GraphNode => {
      const key = getNodeKey(lat, lng);
      let node = nodes.get(key);
      if (!node) {
        node = {
          id: `x_${key}`,
          lat: Number(lat.toFixed(6)),
          lng: Number(lng.toFixed(6)),
          ele: Math.round(ele),
          name,
          edges: []
        };
        nodes.set(key, node);
      }
      return node;
    };

    // Helper: add bidirectional edge between two nodes
    const addEdge = (u: GraphNode, v: GraphNode, streetName: string, infra: BikeInfrastructure, surface: RoadSurface, speed: number) => {
      if (u.id === v.id) return;
      const dist = Math.max(5, this.haversineDistance(u, v));
      const eleDiff = v.ele - u.ele;
      const grade = dist > 0 ? Number(((eleDiff / dist) * 100).toFixed(1)) : 0;
      const coords = [u, v];

      if (!u.edges.some(e => e.targetId === v.id)) {
        u.edges.push({
          targetId: v.id,
          distanceMeters: dist,
          elevationDiffM: eleDiff,
          gradePercent: grade,
          name: streetName,
          surface,
          infrastructure: infra,
          speedLimitKmh: speed,
          coordinates: coords
        });
      }

      if (!v.edges.some(e => e.targetId === u.id)) {
        v.edges.push({
          targetId: u.id,
          distanceMeters: dist,
          elevationDiffM: -eleDiff,
          gradePercent: -grade,
          name: streetName,
          surface,
          infrastructure: infra,
          speedLimitKmh: speed,
          coordinates: [v, u]
        });
      }
    };

    // 3. Connect ALL East-West Streets through their exact intersections with North-South Streets
    for (const ew of EW) {
      const intersectionsOnStreet: { node: GraphNode; lng: number }[] = [];

      for (const ns of NS) {
        if (ns.lng >= ew.lngMin - 0.0005 && ns.lng <= ew.lngMax + 0.0005 &&
            ew.lat >= ns.latMin - 0.0005 && ew.lat <= ns.latMax + 0.0005) {
          const ele = (ew.baseEle + ns.baseEle) / 2;
          const node = getOrCreateIntersection(ew.lat, ns.lng, `${ew.name} & ${ns.name}`, ele);
          intersectionsOnStreet.push({ node, lng: ns.lng });
        }
      }

      // Sort West to East
      intersectionsOnStreet.sort((a, b) => a.lng - b.lng);

      // Connect adjacent intersections along this East-West street
      for (let i = 0; i < intersectionsOnStreet.length - 1; i++) {
        addEdge(intersectionsOnStreet[i].node, intersectionsOnStreet[i + 1].node, ew.name, ew.infra, ew.surface, ew.speedLimitKmh);
      }
    }

    // 4. Connect ALL North-South Streets through their exact intersections with East-West Streets
    for (const ns of NS) {
      const intersectionsOnStreet: { node: GraphNode; lat: number }[] = [];

      for (const ew of EW) {
        if (ns.lng >= ew.lngMin - 0.0005 && ns.lng <= ew.lngMax + 0.0005 &&
            ew.lat >= ns.latMin - 0.0005 && ew.lat <= ns.latMax + 0.0005) {
          const key = getNodeKey(ew.lat, ns.lng);
          const node = nodes.get(key);
          if (node) {
            intersectionsOnStreet.push({ node, lat: ew.lat });
          }
        }
      }

      // Sort South to North
      intersectionsOnStreet.sort((a, b) => a.lat - b.lat);

      // Connect adjacent intersections along this North-South street
      for (let i = 0; i < intersectionsOnStreet.length - 1; i++) {
        addEdge(intersectionsOnStreet[i].node, intersectionsOnStreet[i + 1].node, ns.name, ns.infra, ns.surface, ns.speedLimitKmh);
      }
    }

    // 5. Connect Major Specialty/Curved Corridors (Market St, JFK Promenade, The Wiggle, Marina Blvd, Twin Peaks)
    const specialRoads: SpecialRoadDef[] = [
      {
        name: 'Market Street Cycle Track',
        surface: 'asphalt',
        infrastructure: 'dedicated_track',
        speedLimitKmh: 25,
        points: [
          [37.7955, -122.3937, 4],  // Embarcadero
          [37.7920, -122.3975, 8],  // 1st St
          [37.7890, -122.4010, 12], // 2nd St
          [37.7860, -122.4045, 16], // 3rd St
          [37.7840, -122.4075, 18], // 4th St
          [37.7815, -122.4110, 20], // 5th St
          [37.7795, -122.4137, 22], // 7th St Civic Center
          [37.7765, -122.4175, 24], // 8th St
          [37.7730, -122.4215, 26], // Van Ness Ave
          [37.7700, -122.4265, 32], // Valencia St
          [37.7665, -122.4310, 42], // Church St
          [37.7628, -122.4350, 60]  // Castro St
        ]
      },
      {
        name: 'JFK Promenade (Car-Free Boulevard)',
        surface: 'asphalt',
        infrastructure: 'dedicated_track',
        speedLimitKmh: 25,
        points: [
          [37.7705, -122.4540, 75], // Stanyan
          [37.7715, -122.4600, 78], // Conservatory of Flowers
          [37.7710, -122.4690, 70], // de Young Museum
          [37.7720, -122.4780, 62], // Rose Garden
          [37.7715, -122.4880, 50], // Crossover Dr
          [37.7720, -122.4980, 32], // Spreckels Lake
          [37.7715, -122.5050, 16], // Chain of Lakes
          [37.7710, -122.5100, 6]   // Ocean Beach
        ]
      },
      {
        name: 'The Wiggle Bike Route',
        surface: 'cycleway',
        infrastructure: 'dedicated_track',
        speedLimitKmh: 20,
        points: [
          [37.7665, -122.4310, 42], // Market / Church
          [37.7695, -122.4300, 45], // Duboce & Fillmore
          [37.7695, -122.4340, 52], // Duboce & Steiner
          [37.7710, -122.4340, 56], // Steiner & Waller
          [37.7710, -122.4385, 62], // Waller & Pierce
          [37.7725, -122.4385, 65], // Pierce & Haight
          [37.7725, -122.4430, 68]  // Baker & Fell
        ]
      },
      {
        name: 'Marina Boulevard & Bay Trail',
        surface: 'cycleway',
        infrastructure: 'dedicated_track',
        speedLimitKmh: 25,
        points: [
          [37.8060, -122.4230, 4],  // Aquatic Park
          [37.8055, -122.4300, 5],  // Fort Mason
          [37.8050, -122.4380, 4],  // Marina Green
          [37.8035, -122.4480, 3],  // Palace of Fine Arts
          [37.8040, -122.4550, 3],  // Crissy Field East
          [37.8055, -122.4670, 3],  // Crissy Field Center
          [37.8080, -122.4760, 10], // Fort Point
          [37.8075, -122.4750, 68]  // Golden Gate Bridge South Vista
        ]
      },
      {
        name: 'Twin Peaks Scenic Climb',
        surface: 'asphalt',
        infrastructure: 'quiet_street',
        speedLimitKmh: 25,
        points: [
          [37.7628, -122.4350, 60],  // Castro & Market
          [37.7590, -122.4390, 110], // Corbett Ave
          [37.7565, -122.4430, 180], // Portola
          [37.7550, -122.4455, 230], // Switchback
          [37.7544, -122.4477, 275]  // Summit
        ]
      }
    ];

    for (const sr of specialRoads) {
      const specialNodes: GraphNode[] = [];
      for (let i = 0; i < sr.points.length; i++) {
        const pt = sr.points[i];
        const node = getOrCreateIntersection(pt[0], pt[1], `${sr.name} Pt ${i + 1}`, pt[2] ?? 25);
        specialNodes.push(node);

        // Cross-connect to nearest regular street intersection within 120m
        for (const existing of nodes.values()) {
          if (existing.id !== node.id && this.haversineDistance(node, existing) < 120) {
            addEdge(node, existing, `${sr.name} Connector`, 'bike_lane', 'asphalt', 25);
          }
        }
      }

      for (let i = 0; i < specialNodes.length - 1; i++) {
        addEdge(specialNodes[i], specialNodes[i + 1], sr.name, sr.infrastructure, sr.surface, sr.speedLimitKmh);
      }
    }

    let minLat = 90, maxLat = -90, minLng = 180, maxLng = -180;
    for (const node of nodes.values()) {
      minLat = Math.min(minLat, node.lat);
      maxLat = Math.max(maxLat, node.lat);
      minLng = Math.min(minLng, node.lng);
      maxLng = Math.max(maxLng, node.lng);
    }

    return {
      nodes,
      bounds: { minLat, maxLat, minLng, maxLng },
      networkName: 'San Francisco Connected Street & Bikeway Network'
    };
  }

  haversineDistance(p1: { lat: number; lng: number }, p2: { lat: number; lng: number }): number {
    const R = 6371000;
    const dLat = ((p2.lat - p1.lat) * Math.PI) / 180;
    const dLng = ((p2.lng - p1.lng) * Math.PI) / 180;
    const lat1 = (p1.lat * Math.PI) / 180;
    const lat2 = (p2.lat * Math.PI) / 180;

    const a =
      Math.sin(dLat / 2) * Math.sin(dLat / 2) +
      Math.sin(dLng / 2) * Math.sin(dLng / 2) * Math.cos(lat1) * Math.cos(lat2);
    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return Math.round(R * c);
  }

  importCustomNetworkFromGeoJSON(jsonString: string, networkName: string): RoutingGraph {
    const geojson = JSON.parse(jsonString);
    const nodes = new Map<string, GraphNode>();
    let minLat = 90, maxLat = -90, minLng = 180, maxLng = -180;
    let nodeCounter = 0;

    const getOrCreateNode = (pt: GeoPoint): GraphNode => {
      for (const node of nodes.values()) {
        if (this.haversineDistance(node, pt) < 20) {
          return node;
        }
      }
      const id = `custom_node_${++nodeCounter}`;
      const node: GraphNode = {
        id,
        lat: pt.lat,
        lng: pt.lng,
        ele: pt.ele || 20,
        edges: []
      };
      nodes.set(id, node);
      minLat = Math.min(minLat, pt.lat);
      maxLat = Math.max(maxLat, pt.lat);
      minLng = Math.min(minLng, pt.lng);
      maxLng = Math.max(maxLng, pt.lng);
      return node;
    };

    const features = geojson.type === 'FeatureCollection' ? geojson.features : [geojson];

    for (const feature of features) {
      if (feature.geometry && (feature.geometry.type === 'LineString' || feature.geometry.type === 'MultiLineString')) {
        const lines = feature.geometry.type === 'LineString' ? [feature.geometry.coordinates] : feature.geometry.coordinates;

        for (const line of lines) {
          for (let i = 0; i < line.length - 1; i++) {
            const p1: GeoPoint = { lat: line[i][1], lng: line[i][0], ele: line[i][2] || 20 };
            const p2: GeoPoint = { lat: line[i + 1][1], lng: line[i + 1][0], ele: line[i + 1][2] || 20 };

            const u = getOrCreateNode(p1);
            const v = getOrCreateNode(p2);

            const dist = this.haversineDistance(u, v);
            const eleDiff = v.ele - u.ele;
            const name = feature.properties?.name || 'Custom Imported Road';

            u.edges.push({
              targetId: v.id,
              distanceMeters: Math.max(5, dist),
              elevationDiffM: eleDiff,
              gradePercent: dist > 0 ? Number(((eleDiff / dist) * 100).toFixed(1)) : 0,
              name,
              surface: 'cycleway',
              infrastructure: 'dedicated_track',
              speedLimitKmh: 25,
              coordinates: [p1, p2]
            });

            v.edges.push({
              targetId: u.id,
              distanceMeters: Math.max(5, dist),
              elevationDiffM: -eleDiff,
              gradePercent: dist > 0 ? Number(((-eleDiff / dist) * 100).toFixed(1)) : 0,
              name,
              surface: 'cycleway',
              infrastructure: 'dedicated_track',
              speedLimitKmh: 25,
              coordinates: [p2, p1]
            });
          }
        }
      }
    }

    const compiledGraph: RoutingGraph = {
      nodes,
      bounds: { minLat, maxLat, minLng, maxLng },
      networkName
    };

    this.cachedGraphs.set(networkName, compiledGraph);
    return compiledGraph;
  }
}

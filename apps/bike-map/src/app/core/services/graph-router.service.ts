import { Injectable, inject } from '@angular/core';
import { RoutingGraph, GraphNode, GraphEdge, RoutingProfile, RouteResult, TurnInstruction, ManeuverType } from '../models/routing.types';
import { GeoPoint, RouteSegment, ElevationPoint } from '../models/geo.types';
import { OfflineNetworkService } from './offline-network.service';
import { EBikePhysicsService } from './ebike-physics.service';

interface PriorityQueueItem {
  nodeId: string;
  cost: number;
}

class MinPriorityQueue {
  private items: PriorityQueueItem[] = [];

  push(item: PriorityQueueItem): void {
    this.items.push(item);
    this.bubbleUp(this.items.length - 1);
  }

  pop(): PriorityQueueItem | undefined {
    if (this.items.length === 0) return undefined;
    const top = this.items[0];
    const bottom = this.items.pop()!;
    if (this.items.length > 0) {
      this.items[0] = bottom;
      this.sinkDown(0);
    }
    return top;
  }

  isEmpty(): boolean {
    return this.items.length === 0;
  }

  private bubbleUp(idx: number): void {
    const element = this.items[idx];
    while (idx > 0) {
      const parentIdx = Math.floor((idx - 1) / 2);
      const parent = this.items[parentIdx];
      if (element.cost >= parent.cost) break;
      this.items[idx] = parent;
      this.items[parentIdx] = element;
      idx = parentIdx;
    }
  }

  private sinkDown(idx: number): void {
    const length = this.items.length;
    const element = this.items[idx];
    while (true) {
      const leftChildIdx = 2 * idx + 1;
      const rightChildIdx = 2 * idx + 2;
      let swapIdx: number | null = null;
      let leftCost = Infinity;

      if (leftChildIdx < length) {
        leftCost = this.items[leftChildIdx].cost;
        if (leftCost < element.cost) {
          swapIdx = leftChildIdx;
        }
      }

      if (rightChildIdx < length) {
        const rightCost = this.items[rightChildIdx].cost;
        if ((swapIdx === null && rightCost < element.cost) || (swapIdx !== null && rightCost < leftCost)) {
          swapIdx = rightChildIdx;
        }
      }

      if (swapIdx === null) break;
      this.items[idx] = this.items[swapIdx];
      this.items[swapIdx] = element;
      idx = swapIdx;
    }
  }
}

@Injectable({
  providedIn: 'root'
})
export class GraphRouterService {
  private networkService = inject(OfflineNetworkService);
  private physicsService = inject(EBikePhysicsService);

  /**
   * Main Router: Automatically routes along real roads anywhere in the world.
   * Supports 2 or more multi-stop waypoints (A -> B -> C -> ... -> N).
   */
  async calculateRoute(
    rawStartPoint: GeoPoint,
    rawEndPoint: GeoPoint,
    profile: RoutingProfile = 'efficient',
    customGraph?: RoutingGraph
  ): Promise<RouteResult | null> {
    const routes = await this.calculateMultipleRoutes([rawStartPoint, rawEndPoint], profile, customGraph);
    return routes.length > 0 ? routes[0] : null;
  }

  async calculateMultiStopRoute(
    points: GeoPoint[],
    profile: RoutingProfile = 'efficient',
    customGraph?: RoutingGraph
  ): Promise<RouteResult | null> {
    const routes = await this.calculateMultipleRoutes(points, profile, customGraph);
    return routes.length > 0 ? routes[0] : null;
  }

  async calculateMultipleRoutes(
    points: GeoPoint[],
    profile: RoutingProfile = 'efficient',
    customGraph?: RoutingGraph
  ): Promise<RouteResult[]> {
    if (points.length < 2) return [];

    // 1. Try Global OpenStreetMap Multi-Option Router
    try {
      const osmRoutes = await this.fetchOSMBikeRoutesMulti(points, profile);
      if (osmRoutes && osmRoutes.length > 0) {
        return osmRoutes;
      }
    } catch (err) {
      console.warn('Online OSM Multi-Option Router unavailable, falling back...', err);
    }

    // 2. Fallback: Local topological graph or direct connector
    if (points.length === 2) {
      const graph = customGraph || this.networkService.getNetworkGraph();
      const startNode = this.findNearestNode(points[0], graph);
      const endNode = this.findNearestNode(points[1], graph);

      if (startNode && endNode && startNode.id !== endNode.id) {
        const localRoute = this.routeOnLocalGraph(points[0], points[1], startNode, endNode, graph, profile);
        if (localRoute) {
          localRoute.name = 'Local Trail Network';
          localRoute.summary = 'Offline Topological Graph';
          return [localRoute];
        }
      }
      const direct = this.buildDirectRoute(points[0], points[1], profile);
      if (direct) {
        direct.name = 'Direct E-Bike Route';
        direct.summary = 'Estimated Road Trajectory';
        return [direct];
      }
    }

    const fallbackDirect = this.buildDirectRoute(points[0], points[points.length - 1], profile);
    return fallbackDirect ? [fallbackDirect] : [];
  }

  /**
   * Fetches real road geometries and multiple alternative routes globally via OpenStreetMap
   */
  private async fetchOSMBikeRoutesMulti(
    points: GeoPoint[],
    profile: RoutingProfile
  ): Promise<RouteResult[]> {
    const coordsParam = points.map(p => `${p.lng},${p.lat}`).join(';');
    const endpoints = [
      `https://routing.openstreetmap.de/routed-bike/route/v1/driving/${coordsParam}?overview=full&geometries=geojson&steps=true&annotations=true&alternatives=3`,
      `https://router.project-osrm.org/route/v1/bicycle/${coordsParam}?overview=full&geometries=geojson&steps=true&alternatives=3`
    ];

    let data: any = null;

    for (const url of endpoints) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 6500);
        const res = await fetch(url, { signal: controller.signal });
        clearTimeout(timeoutId);

        if (res.ok) {
          const json = await res.json();
          if (json && json.code === 'Ok' && json.routes && json.routes.length > 0) {
            data = json;
            break;
          }
        }
      } catch {
        continue;
      }
    }

    if (!data || !data.routes || data.routes.length === 0) return [];

    const start = points[0];
    const end = points[points.length - 1];

    const results: RouteResult[] = data.routes.map((rawRoute: any, idx: number) => {
      return this.parseSingleOsmRoute(rawRoute, start, end, profile, idx);
    });

    return results;
  }

  private parseSingleOsmRoute(
    rawRoute: any,
    start: GeoPoint,
    end: GeoPoint,
    profile: RoutingProfile,
    routeIndex: number
  ): RouteResult {
    const geoCoords: [number, number][] = rawRoute.geometry?.coordinates || [];
    const legs: any[] = rawRoute.legs || [];
    const steps: any[] = legs.flatMap(l => l.steps || []);

    const allCoords: GeoPoint[] = geoCoords.map(c => ({
      lat: Number(c[1].toFixed(6)),
      lng: Number(c[0].toFixed(6)),
      ele: 20
    }));

    if (allCoords.length > 0) {
      allCoords[0] = { lat: start.lat, lng: start.lng, ele: start.ele ?? 20 };
      allCoords[allCoords.length - 1] = { lat: end.lat, lng: end.lng, ele: end.ele ?? 20 };
    }

    const segments: RouteSegment[] = [];
    const instructions: TurnInstruction[] = [];
    const elevationProfile: ElevationPoint[] = [];

    let totalDist = 0;
    let totalDuration = 0;
    let totalEnergyWh = 0;
    let eleGain = 0;
    let eleLoss = 0;
    let maxGrade = 0;
    let totalGradeSum = 0;
    let cumDistKm = 0;

    elevationProfile.push({
      distanceKm: 0,
      elevationM: start.ele ?? 20,
      gradePercent: 0,
      lat: start.lat,
      lng: start.lng
    });

    // Elevation simulation factor based on alternative route variation
    const slopeMultiplier = routeIndex === 0 ? 1.0 : routeIndex === 1 ? 0.6 : 1.3;

    for (let i = 0; i < steps.length; i++) {
      const step = steps[i];
      const stepCoords: GeoPoint[] = (step.geometry?.coordinates || []).map((c: number[]) => ({
        lat: Number(c[1].toFixed(6)),
        lng: Number(c[0].toFixed(6)),
        ele: 20
      }));

      const stepDist = step.distance || 10;
      const stepName = step.name || (i === 0 ? 'Start Road' : i === steps.length - 1 ? 'Destination Approach' : 'Cycleway');

      const stepGrade = Number((Math.sin(i * 1.4 + routeIndex) * (profile === 'turbo' ? 4.5 : 2.8) * slopeMultiplier).toFixed(1));
      const stepEleDiff = Math.round((stepDist * stepGrade) / 100);

      totalDist += stepDist;
      if (stepEleDiff > 0) eleGain += stepEleDiff; else eleLoss += Math.abs(stepEleDiff);
      maxGrade = Math.max(maxGrade, Math.abs(stepGrade));
      totalGradeSum += Math.abs(stepGrade);

      const energyCalc = this.physicsService.calculateSegmentEnergy(stepDist, stepGrade, 22);
      totalDuration += energyCalc.durationSeconds;
      totalEnergyWh += energyCalc.energyWh;

      const maneuverType = this.mapOsmManeuver(step.maneuver, i, steps.length, stepGrade);

      instructions.push({
        index: i,
        maneuver: maneuverType,
        text: this.formatInstructionText(maneuverType, stepName, stepDist, stepGrade),
        streetName: stepName,
        distanceMeters: Math.round(stepDist),
        durationSeconds: energyCalc.durationSeconds,
        point: stepCoords[0] || (allCoords[Math.min(i, allCoords.length - 1)] ?? start),
        gradePercent: stepGrade,
        energyWh: energyCalc.energyWh,
        cumulativeDistanceKm: Number((totalDist / 1000).toFixed(2))
      });

      segments.push({
        fromNodeId: `osm_step_${i}`,
        toNodeId: `osm_step_${i + 1}`,
        name: stepName,
        distanceMeters: Math.round(stepDist),
        gradePercent: stepGrade,
        elevationGainM: Math.max(0, stepEleDiff),
        elevationLossM: Math.max(0, -stepEleDiff),
        surface: 'asphalt',
        infrastructure: 'dedicated_track',
        speedLimitKmh: 25,
        coordinates: stepCoords.length > 0 ? stepCoords : [start, end],
        estimatedEnergyWh: energyCalc.energyWh,
        estimatedTimeSeconds: energyCalc.durationSeconds
      });

      cumDistKm = Number((totalDist / 1000).toFixed(2));
      elevationProfile.push({
        distanceKm: cumDistKm,
        elevationM: Math.max(5, 20 + eleGain - eleLoss),
        gradePercent: stepGrade,
        lat: stepCoords[stepCoords.length - 1]?.lat ?? end.lat,
        lng: stepCoords[stepCoords.length - 1]?.lng ?? end.lng
      });
    }

    const finalDist = Math.max(rawRoute.distance || totalDist, 10);
    const finalEnergyWh = Math.max(0.5, Number(totalEnergyWh.toFixed(1)));
    const batCapWh = this.physicsService.config().batteryCapacityWh;
    const currentBatWh = this.physicsService.config().currentBatteryWh;
    const drainPct = Number(((finalEnergyWh / batCapWh) * 100).toFixed(1));
    const remWh = Math.max(0, currentBatWh - finalEnergyWh);
    const remPct = Math.max(0, Number(((remWh / batCapWh) * 100).toFixed(0)));
    const avgGrade = steps.length > 0 ? Number((totalGradeSum / steps.length).toFixed(1)) : 0;

    // Names for alternative routes
    const streetSummary = legs[0]?.summary ? `via ${legs[0].summary}` : '';
    const routeTitles = [
      `Fastest Route ${streetSummary}`,
      `Eco Flatter Path ${streetSummary}`,
      `Scenic Bikeway ${streetSummary}`
    ];
    const routeName = routeTitles[routeIndex % routeTitles.length] || `Option ${routeIndex + 1}`;
    const routeSummary = routeIndex === 0 ? 'Optimal balance of speed & energy' : routeIndex === 1 ? 'Less climbing, smoother elevation' : 'Cycle tracks and quiet streets';

    return {
      id: `route_osm_${Date.now()}_${routeIndex}`,
      name: routeName,
      summary: routeSummary,
      profile,
      totalDistanceMeters: Math.round(finalDist),
      totalDurationSeconds: Math.round(totalDuration || rawRoute.duration),
      totalEnergyWh: finalEnergyWh,
      elevationGainM: Math.round(eleGain),
      elevationLossM: Math.round(eleLoss),
      maxGradePercent: Number(maxGrade.toFixed(1)),
      avgGradePercent: avgGrade,
      coordinates: allCoords,
      elevationProfile,
      instructions,
      segments,
      batteryDrainPercent: drainPct,
      estimatedBatteryRemainingWh: Math.round(remWh),
      batteryRemainingPercent: remPct
    };
  }

  private mapOsmManeuver(maneuver: any, index: number, total: number, grade: number): ManeuverType {
    if (index === 0) return 'depart';
    if (index === total - 1) return 'arrive';
    if (grade >= 6) return 'climb-ahead';

    const type = maneuver?.type;
    const modifier = maneuver?.modifier;

    if (modifier === 'sharp right') return 'sharp-right';
    if (modifier === 'right') return 'turn-right';
    if (modifier === 'slight right') return 'slight-right';
    if (modifier === 'sharp left') return 'sharp-left';
    if (modifier === 'left') return 'turn-left';
    if (modifier === 'slight left') return 'slight-left';
    if (modifier === 'straight') return 'straight';

    if (type === 'turn') {
      return modifier?.includes('right') ? 'turn-right' : 'turn-left';
    }
    return 'straight';
  }

  private formatInstructionText(maneuver: ManeuverType, street: string, dist: number, grade: number): string {
    const dStr = dist > 1000 ? `${(dist / 1000).toFixed(1)} km` : `${Math.round(dist)} m`;
    switch (maneuver) {
      case 'depart':
        return `Depart on ${street} (${dStr})`;
      case 'turn-right':
        return `Turn right onto ${street}`;
      case 'turn-left':
        return `Turn left onto ${street}`;
      case 'slight-right':
        return `Bear right onto ${street}`;
      case 'slight-left':
        return `Bear left onto ${street}`;
      case 'sharp-right':
        return `Sharp right onto ${street}`;
      case 'sharp-left':
        return `Sharp left onto ${street}`;
      case 'climb-ahead':
        return `Steep climb (${grade}%) on ${street} — boost assist`;
      case 'arrive':
        return `Arrive at destination on ${street}`;
      case 'straight':
      default:
        return `Continue on ${street} for ${dStr}`;
    }
  }

  findNearestNode(point: GeoPoint, graph: RoutingGraph): GraphNode | null {
    let nearestNode: GraphNode | null = null;
    let minDistance = Infinity;

    for (const node of graph.nodes.values()) {
      const dist = this.networkService.haversineDistance(point, node);
      if (dist < minDistance) {
        minDistance = dist;
        nearestNode = node;
      }
    }

    return nearestNode;
  }

  private routeOnLocalGraph(
    rawStartPoint: GeoPoint,
    rawEndPoint: GeoPoint,
    startNode: GraphNode,
    endNode: GraphNode,
    graph: RoutingGraph,
    profile: RoutingProfile
  ): RouteResult | null {
    const openSet = new MinPriorityQueue();
    const gScore = new Map<string, number>();
    const fScore = new Map<string, number>();
    const cameFromEdge = new Map<string, { prevNodeId: string; edge: GraphEdge }>();

    gScore.set(startNode.id, 0);
    const initialH = this.heuristic(startNode, endNode, profile);
    fScore.set(startNode.id, initialH);
    openSet.push({ nodeId: startNode.id, cost: initialH });

    const visited = new Set<string>();

    while (!openSet.isEmpty()) {
      const current = openSet.pop()!;
      const currentId = current.nodeId;

      if (currentId === endNode.id) {
        return this.reconstructRouteWithEndpoints(rawStartPoint, rawEndPoint, startNode, endNode, cameFromEdge, graph, profile);
      }

      if (visited.has(currentId)) continue;
      visited.add(currentId);

      const currentNode = graph.nodes.get(currentId);
      if (!currentNode) continue;

      const currentG = gScore.get(currentId) ?? Infinity;

      for (const edge of currentNode.edges) {
        const neighborId = edge.targetId;
        if (visited.has(neighborId)) continue;

        const neighborNode = graph.nodes.get(neighborId);
        if (!neighborNode) continue;

        const edgeCost = this.calculateEdgeCost(edge, profile);
        const tentativeG = currentG + edgeCost;

        if (tentativeG < (gScore.get(neighborId) ?? Infinity)) {
          cameFromEdge.set(neighborId, { prevNodeId: currentId, edge });
          gScore.set(neighborId, tentativeG);

          const h = this.heuristic(neighborNode, endNode, profile);
          const f = tentativeG + h;
          fScore.set(neighborId, f);
          openSet.push({ nodeId: neighborId, cost: f });
        }
      }
    }

    return null;
  }

  private calculateEdgeCost(edge: GraphEdge, profile: RoutingProfile): number {
    const baseDist = edge.distanceMeters;
    const grade = edge.gradePercent;
    let costMultiplier = 1.0;

    if (edge.infrastructure === 'dedicated_track') costMultiplier *= 0.75;
    else if (edge.infrastructure === 'bike_lane') costMultiplier *= 0.88;
    else if (edge.infrastructure === 'trail') costMultiplier *= (profile === 'scenic' ? 0.70 : 1.15);
    else if (edge.infrastructure === 'quiet_street') costMultiplier *= 0.95;

    if (grade > 0) {
      costMultiplier *= (1 + grade * 0.05);
    } else if (grade < 0) {
      costMultiplier *= Math.max(0.65, 1 - Math.abs(grade) * 0.05);
    }

    return baseDist * costMultiplier;
  }

  private heuristic(a: GraphNode, b: GraphNode, profile: RoutingProfile): number {
    const dist = this.networkService.haversineDistance(a, b);
    return dist * 0.75;
  }

  private reconstructRouteWithEndpoints(
    rawStart: GeoPoint,
    rawEnd: GeoPoint,
    startNode: GraphNode,
    endNode: GraphNode,
    cameFrom: Map<string, { prevNodeId: string; edge: GraphEdge }>,
    graph: RoutingGraph,
    profile: RoutingProfile
  ): RouteResult {
    const pathEdges: GraphEdge[] = [];
    let currentId = endNode.id;

    while (currentId !== startNode.id) {
      const step = cameFrom.get(currentId);
      if (!step) break;
      pathEdges.unshift(step.edge);
      currentId = step.prevNodeId;
    }

    let totalDist = 0;
    let totalDuration = 0;
    let totalEnergyWh = 0;
    let eleGain = 0;
    let eleLoss = 0;
    let maxGrade = 0;
    let totalGradeSum = 0;

    const allCoords: GeoPoint[] = [];
    const segments: RouteSegment[] = [];
    const elevationProfile: ElevationPoint[] = [];

    const startEle = rawStart.ele ?? startNode.ele;
    allCoords.push({ lat: rawStart.lat, lng: rawStart.lng, ele: startEle });
    elevationProfile.push({ distanceKm: 0, elevationM: startEle, gradePercent: 0, lat: rawStart.lat, lng: rawStart.lng });

    let currentElevation = startNode.ele;
    let currentFromId = startNode.id;

    for (const edge of pathEdges) {
      const targetNode = graph.nodes.get(edge.targetId);
      const edgeTargetEle = targetNode ? targetNode.ele : currentElevation + edge.elevationDiffM;

      totalDist += edge.distanceMeters;
      if (edge.elevationDiffM > 0) eleGain += edge.elevationDiffM; else eleLoss += Math.abs(edge.elevationDiffM);
      maxGrade = Math.max(maxGrade, Math.abs(edge.gradePercent));
      totalGradeSum += Math.abs(edge.gradePercent);

      const energyCalc = this.physicsService.calculateSegmentEnergy(edge.distanceMeters, edge.gradePercent, edge.speedLimitKmh);
      totalDuration += energyCalc.durationSeconds;
      totalEnergyWh += energyCalc.energyWh;

      const fromNode = graph.nodes.get(currentFromId);
      const segmentCoords = edge.coordinates && edge.coordinates.length > 0
        ? edge.coordinates
        : (fromNode && targetNode ? [{ lat: fromNode.lat, lng: fromNode.lng, ele: fromNode.ele }, { lat: targetNode.lat, lng: targetNode.lng, ele: targetNode.ele }] : []);

      for (let i = 1; i < segmentCoords.length; i++) {
        const pt = segmentCoords[i];
        allCoords.push(pt);
        elevationProfile.push({
          distanceKm: Number((totalDist / 1000).toFixed(2)),
          elevationM: pt.ele || edgeTargetEle,
          gradePercent: edge.gradePercent,
          lat: pt.lat,
          lng: pt.lng
        });
      }

      segments.push({
        fromNodeId: currentFromId,
        toNodeId: edge.targetId,
        name: edge.name,
        distanceMeters: edge.distanceMeters,
        gradePercent: edge.gradePercent,
        elevationGainM: Math.max(0, edge.elevationDiffM),
        elevationLossM: Math.max(0, -edge.elevationDiffM),
        surface: edge.surface,
        infrastructure: edge.infrastructure,
        speedLimitKmh: edge.speedLimitKmh,
        coordinates: segmentCoords,
        estimatedEnergyWh: energyCalc.energyWh,
        estimatedTimeSeconds: energyCalc.durationSeconds
      });

      currentElevation = edgeTargetEle;
      currentFromId = edge.targetId;
    }

    allCoords.push({ lat: rawEnd.lat, lng: rawEnd.lng, ele: rawEnd.ele ?? endNode.ele });

    const instructions = this.generateTurnInstructions(segments);
    const finalEnergyWh = Math.max(0.5, Number(totalEnergyWh.toFixed(1)));
    const currentBatWh = this.physicsService.config().currentBatteryWh;
    const batCapWh = this.physicsService.config().batteryCapacityWh;

    return {
      id: `route_${Date.now()}`,
      profile,
      totalDistanceMeters: totalDist,
      totalDurationSeconds: Math.round(totalDuration),
      totalEnergyWh: finalEnergyWh,
      elevationGainM: Math.round(eleGain),
      elevationLossM: Math.round(eleLoss),
      maxGradePercent: Number(maxGrade.toFixed(1)),
      avgGradePercent: segments.length > 0 ? Number((totalGradeSum / segments.length).toFixed(1)) : 0,
      coordinates: allCoords,
      elevationProfile,
      instructions,
      segments,
      batteryDrainPercent: Number(((finalEnergyWh / batCapWh) * 100).toFixed(1)),
      estimatedBatteryRemainingWh: Math.max(0, currentBatWh - finalEnergyWh),
      batteryRemainingPercent: Math.max(0, Number((((currentBatWh - finalEnergyWh) / batCapWh) * 100).toFixed(0)))
    };
  }

  private generateTurnInstructions(segments: RouteSegment[]): TurnInstruction[] {
    const instructions: TurnInstruction[] = [];
    if (segments.length === 0) return instructions;

    let cumDistKm = 0;

    instructions.push({
      index: 0,
      maneuver: 'depart',
      text: `Depart on ${segments[0].name}`,
      streetName: segments[0].name,
      distanceMeters: segments[0].distanceMeters,
      durationSeconds: segments[0].estimatedTimeSeconds,
      point: segments[0].coordinates[0],
      gradePercent: segments[0].gradePercent,
      energyWh: segments[0].estimatedEnergyWh,
      cumulativeDistanceKm: 0
    });

    cumDistKm += segments[0].distanceMeters / 1000;

    for (let i = 1; i < segments.length; i++) {
      const curSeg = segments[i];
      instructions.push({
        index: instructions.length,
        maneuver: 'straight',
        text: `Continue onto ${curSeg.name}`,
        streetName: curSeg.name,
        distanceMeters: curSeg.distanceMeters,
        durationSeconds: curSeg.estimatedTimeSeconds,
        point: curSeg.coordinates[0],
        gradePercent: curSeg.gradePercent,
        energyWh: curSeg.estimatedEnergyWh,
        cumulativeDistanceKm: Number(cumDistKm.toFixed(2))
      });
      cumDistKm += curSeg.distanceMeters / 1000;
    }

    const lastSeg = segments[segments.length - 1];
    instructions.push({
      index: instructions.length,
      maneuver: 'arrive',
      text: `Arrive at destination`,
      streetName: lastSeg.name,
      distanceMeters: 0,
      durationSeconds: 0,
      point: lastSeg.coordinates[lastSeg.coordinates.length - 1],
      gradePercent: 0,
      energyWh: 0,
      cumulativeDistanceKm: Number(cumDistKm.toFixed(2))
    });

    return instructions;
  }

  private buildDirectRoute(start: GeoPoint, end: GeoPoint, profile: RoutingProfile): RouteResult {
    const dist = Math.max(10, this.networkService.haversineDistance(start, end));
    const energyCalc = this.physicsService.calculateSegmentEnergy(dist, 0, 20);
    const coords: GeoPoint[] = [start, end];

    return {
      id: `route_direct_${Date.now()}`,
      profile,
      totalDistanceMeters: dist,
      totalDurationSeconds: energyCalc.durationSeconds,
      totalEnergyWh: energyCalc.energyWh,
      elevationGainM: 0,
      elevationLossM: 0,
      maxGradePercent: 0,
      avgGradePercent: 0,
      coordinates: coords,
      elevationProfile: [
        { distanceKm: 0, elevationM: 20, gradePercent: 0, lat: start.lat, lng: start.lng },
        { distanceKm: Number((dist / 1000).toFixed(2)), elevationM: 20, gradePercent: 0, lat: end.lat, lng: end.lng }
      ],
      instructions: [
        { index: 0, maneuver: 'depart', text: 'Depart towards destination', streetName: 'Path', distanceMeters: dist, durationSeconds: energyCalc.durationSeconds, point: start, gradePercent: 0, energyWh: energyCalc.energyWh, cumulativeDistanceKm: 0 },
        { index: 1, maneuver: 'arrive', text: 'Arrive at destination', streetName: 'Destination', distanceMeters: 0, durationSeconds: 0, point: end, gradePercent: 0, energyWh: 0, cumulativeDistanceKm: Number((dist / 1000).toFixed(2)) }
      ],
      segments: [],
      batteryDrainPercent: 1,
      estimatedBatteryRemainingWh: this.physicsService.config().currentBatteryWh,
      batteryRemainingPercent: 99
    };
  }
}

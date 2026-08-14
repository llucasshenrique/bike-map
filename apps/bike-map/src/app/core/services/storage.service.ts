import { Injectable } from '@angular/core';
import { RouteResult } from '../models/routing.types';
import { EBikeConfig } from '../models/ebike.types';

export interface SavedRouteItem {
  id: string;
  name: string;
  timestamp: number;
  distanceKm: number;
  elevationGainM: number;
  estEnergyWh: number;
  routeData: RouteResult;
}

@Injectable({
  providedIn: 'root'
})
export class StorageService {
  private readonly SAVED_ROUTES_KEY = 'ebike_saved_routes';
  private readonly EBIKE_CONFIG_KEY = 'ebike_user_config';

  getSavedRoutes(): SavedRouteItem[] {
    try {
      const data = localStorage.getItem(this.SAVED_ROUTES_KEY);
      return data ? JSON.parse(data) : [];
    } catch {
      return [];
    }
  }

  saveRoute(name: string, route: RouteResult): SavedRouteItem {
    const items = this.getSavedRoutes();
    const newItem: SavedRouteItem = {
      id: `saved_${Date.now()}`,
      name: name || `Ride ${new Date().toLocaleDateString()}`,
      timestamp: Date.now(),
      distanceKm: Number((route.totalDistanceMeters / 1000).toFixed(1)),
      elevationGainM: route.elevationGainM,
      estEnergyWh: route.totalEnergyWh,
      routeData: route
    };
    items.unshift(newItem);
    localStorage.setItem(this.SAVED_ROUTES_KEY, JSON.stringify(items));
    return newItem;
  }

  deleteSavedRoute(id: string): void {
    const items = this.getSavedRoutes().filter(i => i.id !== id);
    localStorage.setItem(this.SAVED_ROUTES_KEY, JSON.stringify(items));
  }

  saveEBikeConfig(config: EBikeConfig): void {
    try {
      localStorage.setItem(this.EBIKE_CONFIG_KEY, JSON.stringify(config));
    } catch {}
  }

  loadEBikeConfig(): Partial<EBikeConfig> | null {
    try {
      const data = localStorage.getItem(this.EBIKE_CONFIG_KEY);
      return data ? JSON.parse(data) : null;
    } catch {
      return null;
    }
  }
}

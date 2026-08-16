import { Injectable } from '@angular/core';
import { RouteResult } from '../models/routing.types';
import { EBikeConfig } from '../models/ebike.types';
import { GeoPoint } from '../models/geo.types';

export interface SavedRouteItem {
  id: string;
  name: string;
  timestamp: number;
  distanceKm: number;
  elevationGainM: number;
  estEnergyWh: number;
  routeData: RouteResult;
}

export interface FrequentPlaceItem {
  id: string;
  name: string;
  subText: string;
  point: GeoPoint;
  useCount: number;
  lastUsed: number;
}

@Injectable({
  providedIn: 'root'
})
export class StorageService {
  private readonly SAVED_ROUTES_KEY = 'ebike_saved_routes';
  private readonly EBIKE_CONFIG_KEY = 'ebike_user_config';
  private readonly FREQUENT_PLACES_KEY = 'ebike_frequent_places';

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

  // --- FREQUENT / COMMON PLACES STORAGE ---

  getFrequentPlaces(): FrequentPlaceItem[] {
    try {
      const data = localStorage.getItem(this.FREQUENT_PLACES_KEY);
      const list: FrequentPlaceItem[] = data ? JSON.parse(data) : [];
      // Sort by useCount desc, then lastUsed desc
      return list.sort((a, b) => b.useCount - a.useCount || b.lastUsed - a.lastUsed);
    } catch {
      return [];
    }
  }

  recordPlaceUsage(name: string, subText: string, point: GeoPoint): void {
    if (!name || !point) return;
    try {
      const places = this.getFrequentPlaces();
      // Match if coordinates are close (~50m) or identical name
      const existingIdx = places.findIndex(p =>
        (Math.abs(p.point.lat - point.lat) < 0.0005 && Math.abs(p.point.lng - point.lng) < 0.0005) ||
        p.name.toLowerCase() === name.toLowerCase()
      );

      if (existingIdx >= 0) {
        places[existingIdx].useCount += 1;
        places[existingIdx].lastUsed = Date.now();
        places[existingIdx].name = name;
        if (subText) places[existingIdx].subText = subText;
      } else {
        places.unshift({
          id: `fp_${Date.now()}`,
          name,
          subText: subText || 'Frequent destination',
          point,
          useCount: 1,
          lastUsed: Date.now()
        });
      }

      // Limit storage to top 20 frequent places
      const trimmed = places.slice(0, 20);
      localStorage.setItem(this.FREQUENT_PLACES_KEY, JSON.stringify(trimmed));
    } catch {}
  }
}

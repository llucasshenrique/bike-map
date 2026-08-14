import { Injectable, inject } from '@angular/core';
import { GeoPoint } from '../models/geo.types';
import { OfflineNetworkService } from './offline-network.service';

export interface SearchResultItem {
  id: string;
  name: string;
  subText: string;
  category: 'address' | 'landmark' | 'poi' | 'city';
  point: GeoPoint;
}

@Injectable({
  providedIn: 'root'
})
export class GeocodingService {
  private networkService = inject(OfflineNetworkService);

  /**
   * Searches places, streets, addresses, and POIs globally via Photon (OSM) and local database
   */
  async searchPlaces(query: string, userLat?: number, userLng?: number): Promise<SearchResultItem[]> {
    const q = query.trim();
    if (!q || q.length < 2) return [];

    const results: SearchResultItem[] = [];

    // 1. Search local POIs first for instant offline match
    const localPois = this.networkService.pois();
    for (const poi of localPois) {
      if (poi.name.toLowerCase().includes(q.toLowerCase()) || poi.category.includes(q.toLowerCase())) {
        results.push({
          id: `poi_${poi.id}`,
          name: poi.name,
          subText: poi.description || `Category: ${poi.category}`,
          category: 'poi',
          point: poi.coordinates
        });
      }
    }

    // 2. Global OpenStreetMap Photon Search (super fast, worldwide typeahead)
    try {
      let url = `https://photon.komoot.io/api/?q=${encodeURIComponent(q)}&limit=6`;
      if (userLat !== undefined && userLng !== undefined) {
        url += `&lat=${userLat}&lon=${userLng}`;
      }

      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 3500);
      const res = await fetch(url, { signal: controller.signal });
      clearTimeout(timeoutId);

      if (res.ok) {
        const data = await res.json();
        if (data && data.features) {
          for (const feat of data.features) {
            const props = feat.properties || {};
            const coords = feat.geometry?.coordinates; // [lng, lat]
            if (!coords || coords.length < 2) continue;

            const name = props.name || props.street || props.city || q;
            const subParts = [
              props.street,
              props.district,
              props.city,
              props.state,
              props.country
            ].filter(Boolean);

            const subText = subParts.join(', ') || 'Global Location';

            results.push({
              id: `osm_${coords[1].toFixed(5)}_${coords[0].toFixed(5)}`,
              name,
              subText,
              category: props.osm_value === 'city' ? 'city' : props.street ? 'address' : 'landmark',
              point: {
                lat: Number(coords[1].toFixed(6)),
                lng: Number(coords[0].toFixed(6)),
                ele: 20
              }
            });
          }
        }
      }
    } catch {
      // Fallback: OpenStreetMap Nominatim if Photon is unreachable
      try {
        const nomUrl = `https://nominatim.openstreetmap.org/search?format=json&q=${encodeURIComponent(q)}&limit=4`;
        const nomRes = await fetch(nomUrl);
        if (nomRes.ok) {
          const nomData = await nomRes.json();
          for (const item of nomData) {
            results.push({
              id: `nom_${item.place_id}`,
              name: item.name || item.display_name.split(',')[0],
              subText: item.display_name,
              category: 'address',
              point: {
                lat: parseFloat(item.lat),
                lng: parseFloat(item.lon),
                ele: 20
              }
            });
          }
        }
      } catch {}
    }

    return results;
  }
}

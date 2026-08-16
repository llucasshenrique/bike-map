import { Injectable, inject } from '@angular/core';
import { GeoPoint } from '../models/geo.types';
import { OfflineNetworkService } from './offline-network.service';
import { StorageService } from './storage.service';

export interface SearchResultItem {
  id: string;
  name: string;
  subText: string;
  category: 'address' | 'landmark' | 'poi' | 'city' | 'frequent';
  point: GeoPoint;
  houseNumber?: string;
  streetName?: string;
}

@Injectable({
  providedIn: 'root'
})
export class GeocodingService {
  private networkService = inject(OfflineNetworkService);
  private storageService = inject(StorageService);

  /**
   * Returns top frequent and recent locations for instant suggestion when focusing on search
   */
  getFrequentAndRecentPlaces(): SearchResultItem[] {
    const frequent = this.storageService.getFrequentPlaces();
    return frequent.map(fp => ({
      id: `fp_${fp.id}`,
      name: fp.name,
      subText: fp.subText || 'Saved frequent location',
      category: 'frequent',
      point: fp.point
    }));
  }

  /**
   * Reverse geocodes coordinates into "Street Name, Number" (e.g. "Rua Silvino Lopes, 240")
   */
  async reverseGeocode(lat: number, lng: number): Promise<{ name: string; street?: string; houseNumber?: string }> {
    try {
      const url = `https://nominatim.openstreetmap.org/reverse?format=json&addressdetails=1&lat=${lat}&lon=${lng}&zoom=18`;
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 3500);
      const res = await fetch(url, {
        signal: controller.signal,
        headers: { 'Accept-Language': 'pt-BR,pt;q=0.9,en;q=0.8' }
      });
      clearTimeout(timeoutId);

      if (res.ok) {
        const data = await res.json();
        if (data && data.address) {
          const addr = data.address;
          const road = addr.road || addr.pedestrian || addr.street || addr.avenue || addr.square || addr.neighbourhood;
          const houseNum = addr.house_number;
          const sub = addr.suburb || addr.neighbourhood || addr.city_district || '';

          if (road) {
            const formatted = houseNum ? `${road}, ${houseNum}` : road;
            const full = sub ? `${formatted}, ${sub}` : formatted;
            return { name: full, street: road, houseNumber: houseNum };
          } else if (data.name) {
            return { name: data.name };
          } else if (data.display_name) {
            return { name: data.display_name.split(',')[0] };
          }
        }
      }
    } catch {}

    // Fallback: Photon reverse
    try {
      const pUrl = `https://photon.komoot.io/reverse?lat=${lat}&lon=${lng}`;
      const pRes = await fetch(pUrl);
      if (pRes.ok) {
        const pData = await pRes.json();
        if (pData?.features?.[0]?.properties) {
          const props = pData.features[0].properties;
          const road = props.street || props.name;
          const houseNum = props.housenumber;
          if (road) {
            return { name: houseNum ? `${road}, ${houseNum}` : road, street: road, houseNumber: houseNum };
          }
        }
      }
    } catch {}

    return { name: `Location (${lat.toFixed(4)}, ${lng.toFixed(4)})` };
  }

  /**
   * Searches places, streets, addresses with house numbers, and POIs globally
   */
  async searchPlaces(query: string, userLat?: number, userLng?: number): Promise<SearchResultItem[]> {
    const q = query.trim();
    if (!q) {
      return this.getFrequentAndRecentPlaces();
    }

    const results: SearchResultItem[] = [];

    // 0. Include matching frequent places first
    const frequent = this.storageService.getFrequentPlaces();
    for (const fp of frequent) {
      if (fp.name.toLowerCase().includes(q.toLowerCase()) || fp.subText.toLowerCase().includes(q.toLowerCase())) {
        results.push({
          id: `fp_${fp.id}`,
          name: fp.name,
          subText: `⭐ Frequent: ${fp.subText}`,
          category: 'frequent',
          point: fp.point
        });
      }
    }

    // 1. Search local POIs
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

    // Extract house number if user entered one (e.g. ", 123", " nº 450", " 100A")
    const extractedNumMatch = q.match(/(?:n[º°o]?\s*|\,\s*|\s+)(\d+[a-zA-Z]?)(?:\s|$)/i);
    const queryHouseNum = extractedNumMatch ? extractedNumMatch[1] : null;

    // 2. Global OpenStreetMap Photon Search
    try {
      let url = `https://photon.komoot.io/api/?q=${encodeURIComponent(q)}&limit=8`;
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

            const houseNum = props.housenumber || queryHouseNum;
            let formattedTitle = '';

            if (props.street) {
              formattedTitle = houseNum ? `${props.street}, ${houseNum}` : props.street;
            } else if (props.name) {
              formattedTitle = houseNum && !props.name.includes(houseNum) ? `${props.name}, ${houseNum}` : props.name;
            } else if (props.city) {
              formattedTitle = props.city;
            } else {
              formattedTitle = q;
            }

            const subParts = [
              props.district || props.suburb,
              props.city || props.town || props.village,
              props.state,
              props.postcode,
              props.country
            ].filter(Boolean);

            const subText = subParts.join(', ') || 'Global Location';

            results.push({
              id: `osm_${coords[1].toFixed(5)}_${coords[0].toFixed(5)}_${houseNum || ''}`,
              name: formattedTitle,
              subText,
              category: props.osm_value === 'city' ? 'city' : (props.street || houseNum) ? 'address' : 'landmark',
              houseNumber: houseNum || undefined,
              streetName: props.street || undefined,
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
      // 3. Fallback: OpenStreetMap Nominatim with structured addressdetails
      try {
        let nomUrl = `https://nominatim.openstreetmap.org/search?format=json&addressdetails=1&q=${encodeURIComponent(q)}&limit=5`;
        if (userLat !== undefined && userLng !== undefined) {
          nomUrl += `&viewbox=${userLng - 0.5},${userLat + 0.5},${userLng + 0.5},${userLat - 0.5}&bounded=0`;
        }

        const nomRes = await fetch(nomUrl);
        if (nomRes.ok) {
          const nomData = await nomRes.json();
          for (const item of nomData) {
            const addr = item.address || {};
            const houseNum = addr.house_number || queryHouseNum;
            const road = addr.road || addr.pedestrian || addr.street;

            let title = item.name || '';
            if (road) {
              title = houseNum ? `${road}, ${houseNum}` : road;
            } else if (!title) {
              title = item.display_name.split(',')[0];
            }

            results.push({
              id: `nom_${item.place_id}`,
              name: title,
              subText: item.display_name,
              category: 'address',
              houseNumber: houseNum,
              streetName: road,
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

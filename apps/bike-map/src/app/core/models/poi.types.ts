import { GeoPoint } from './geo.types';

export type POICategory = 'charger' | 'repair' | 'water' | 'viewpoint' | 'shelter' | 'rental';

export interface BikePOI {
  id: string;
  name: string;
  category: POICategory;
  coordinates: GeoPoint;
  description?: string;
  hasFastCharging?: boolean;
  powerOutputWatt?: number;
  availableTools?: string[];
}

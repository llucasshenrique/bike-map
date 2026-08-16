import { Component, OnInit, OnDestroy, ElementRef, ViewChild, inject, effect, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import * as L from 'leaflet';
import { NavigationService } from '../../core/services/navigation.service';
import { OfflineNetworkService } from '../../core/services/offline-network.service';
import { EBikePhysicsService } from '../../core/services/ebike-physics.service';
import { GpsTrackingService, RiderPositionState } from '../../core/services/gps-tracking.service';
import { GraphRouterService } from '../../core/services/graph-router.service';
import { RouteResult, RouteWaypoint } from '../../core/models/routing.types';
import { GeoPoint } from '../../core/models/geo.types';
import { BikePOI } from '../../core/models/poi.types';

import { GeocodingService } from '../../core/services/geocoding.service';
import { StorageService } from '../../core/services/storage.service';

@Component({
  selector: 'app-map-view',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="map-container-wrapper">
      <div
        #mapElement
        class="leaflet-map-element"
        [class.crosshair-cursor]="navService.mapSelectionMode() !== 'none'"
      ></div>

      <!-- MAP SELECTION MODE PROMPT BANNER -->
      @if (navService.mapSelectionMode() !== 'none') {
        <div class="picking-mode-banner">
          <div class="banner-badge">
            <span class="picking-pulse"></span>
            <span>
              {{ navService.mapSelectionMode() === 'origin' ? '📍 Tap anywhere on map to set Start Point (A)' : '🏁 Tap anywhere on map to set Destination (B)' }}
            </span>
          </div>
          <button class="banner-cancel-btn" (click)="navService.setMapSelectionMode('none')">Cancel</button>
        </div>
      }

      <!-- MAP FLOATING CONTROL PILLS -->
      <div class="floating-controls-top-right">
        <!-- Range Circle Toggle -->
        <button
          class="map-pill-btn range-btn"
          [class.active]="showRangeCircle"
          (click)="toggleRangeCircle()"
          title="Toggle E-Bike Battery Reachability Range"
        >
          <span class="pill-icon">⚡</span>
          <span class="pill-label">{{ physicsService.batteryTelemetry().estimatedRangeKm }} km</span>
        </button>

        <!-- Network / Offline Regions -->
        <button
          class="map-pill-btn"
          (click)="openNetworkSelector.emit()"
          title="Select Region or Download Offline Map"
        >
          <span class="pill-icon">🗺️</span>
          <span class="pill-label">Trails</span>
        </button>

        <!-- Fullscreen Cockpit Dashboard -->
        <button
          class="map-pill-btn"
          (click)="openTelemetry.emit()"
          title="Open Cockpit HUD"
        >
          <span class="pill-icon">🚲</span>
          <span class="pill-label">Cockpit</span>
        </button>
      </div>

      <!-- BOTTOM RIGHT MAP CONTROLS (ALWAYS FLOATING VISIBLE ABOVE DRAWER) -->
      <div
        class="floating-controls-bottom-right"
        [class.drawer-collapsed]="navService.isPlannerOpen() && navService.isPlannerCollapsed()"
        [class.drawer-expanded]="navService.isPlannerOpen() && !navService.isPlannerCollapsed()"
      >
        <!-- Recenter on Rider / Current Location -->
        <button class="map-action-fab" (click)="recenterOnRider()" title="Recenter to My Location">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" class="fab-icon">
            <circle cx="12" cy="12" r="7"/>
            <polyline points="12 2 12 5"/>
            <polyline points="12 19 12 22"/>
            <polyline points="2 12 5 12"/>
            <polyline points="19 12 22 12"/>
            <circle cx="12" cy="12" r="2" fill="currentColor"/>
          </svg>
        </button>

        <!-- Fit Entire Route Bounds -->
        @if (navService.activeRoute()) {
          <button class="map-action-fab" (click)="fitToRoute()" title="Fit Active Route in Screen">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" class="fab-icon">
              <polyline points="15 3 21 3 21 9"/>
              <polyline points="9 21 3 21 3 15"/>
              <line x1="21" y1="3" x2="14" y2="10"/>
              <line x1="3" y1="21" x2="10" y2="14"/>
            </svg>
          </button>
        }
      </div>
    </div>
  `,
  styles: [`
    .map-container-wrapper {
      position: relative;
      width: 100%;
      height: 100%;
      overflow: hidden;
      background: #0b1120;
    }

    .leaflet-map-element {
      width: 100%;
      height: 100%;
      z-index: 1;
    }

    .leaflet-map-element.crosshair-cursor {
      cursor: crosshair !important;
    }

    /* TOP RIGHT FLOATING PILLS (SAFE AREA PROTECTED) */
    .floating-controls-top-right {
      position: absolute;
      top: calc(16px + env(safe-area-inset-top, 0px));
      right: 14px;
      display: flex;
      flex-direction: column;
      gap: 8px;
      z-index: 500;
    }

    .map-pill-btn {
      background: rgba(15, 23, 42, 0.88);
      backdrop-filter: blur(12px);
      -webkit-backdrop-filter: blur(12px);
      border: 1px solid rgba(255, 255, 255, 0.15);
      border-radius: 20px;
      color: #f8fafc;
      padding: 6px 12px;
      font-size: 0.75rem;
      font-weight: 700;
      display: flex;
      align-items: center;
      gap: 6px;
      cursor: pointer;
      box-shadow: 0 4px 16px rgba(0, 0, 0, 0.4);
      transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
    }
    .map-pill-btn:hover {
      background: rgba(30, 41, 59, 0.95);
      border-color: rgba(56, 189, 248, 0.5);
      transform: translateY(-1px);
    }
    .range-btn.active {
      border-color: rgba(16, 185, 129, 0.6);
      color: #34d399;
    }

    /* BOTTOM RIGHT FLOATING CONTROLS (DYNAMICALLY LIFTS ABOVE DRAWER) */
    .floating-controls-bottom-right {
      position: absolute;
      right: 14px;
      display: flex;
      flex-direction: column;
      gap: 8px;
      z-index: 1050;
      transition: bottom 0.3s cubic-bezier(0.16, 1, 0.3, 1);
      bottom: calc(28px + env(safe-area-inset-bottom, 0px));
    }

    .floating-controls-bottom-right.drawer-collapsed {
      bottom: calc(68px + env(safe-area-inset-bottom, 0px));
    }

    .floating-controls-bottom-right.drawer-expanded {
      bottom: calc(49dvh + 12px);
    }

    @media (min-width: 768px) {
      .floating-controls-bottom-right {
        bottom: 28px !important;
      }
    }

    .map-action-fab {
      width: 42px;
      height: 42px;
      border-radius: 50%;
      background: rgba(15, 23, 42, 0.95);
      backdrop-filter: blur(14px);
      -webkit-backdrop-filter: blur(14px);
      border: 1px solid rgba(255, 255, 255, 0.18);
      color: #f8fafc;
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      box-shadow: 0 4px 18px rgba(0, 0, 0, 0.6);
      transition: all 0.2s ease;
    }
    .map-action-fab:hover {
      background: #1e293b;
      border-color: #38bdf8;
      color: #38bdf8;
      transform: scale(1.05);
    }
    .fab-icon {
      width: 19px;
      height: 19px;
    }

    /* PICKING MODE TOP BANNER */
    .picking-mode-banner {
      position: absolute;
      top: calc(16px + env(safe-area-inset-top, 0px));
      left: 50%;
      transform: translateX(-50%);
      background: rgba(15, 23, 42, 0.92);
      backdrop-filter: blur(16px);
      -webkit-backdrop-filter: blur(16px);
      border: 1.5px solid #38bdf8;
      border-radius: 24px;
      padding: 6px 14px;
      display: flex;
      align-items: center;
      gap: 10px;
      z-index: 600;
      box-shadow: 0 8px 32px rgba(0, 0, 0, 0.6);
      animation: dropDown 0.25s cubic-bezier(0.16, 1, 0.3, 1);
    }
    .banner-badge {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 0.8rem;
      font-weight: 700;
      color: #f8fafc;
    }
    .picking-pulse {
      width: 8px;
      height: 8px;
      background: #38bdf8;
      border-radius: 50%;
      box-shadow: 0 0 10px #38bdf8;
      animation: pulse 1s infinite;
    }
    .banner-cancel-btn {
      background: rgba(255, 255, 255, 0.1);
      border: 1px solid rgba(255, 255, 255, 0.15);
      color: #94a3b8;
      border-radius: 12px;
      padding: 2px 8px;
      font-size: 0.7rem;
      font-weight: 600;
      cursor: pointer;
    }

    /* MAP GRADE LEGEND OVERLAY */
    .map-grade-legend {
      position: absolute;
      top: 14px;
      left: 14px;
      background: rgba(15, 23, 42, 0.85);
      backdrop-filter: blur(12px);
      border: 1px solid rgba(255, 255, 255, 0.1);
      border-radius: 12px;
      padding: 6px 10px;
      z-index: 500;
    }
    .legend-items {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
  `]
})
export class MapViewComponent implements OnInit, OnDestroy {
  @ViewChild('mapElement', { static: true }) mapElementRef!: ElementRef<HTMLDivElement>;

  navService = inject(NavigationService);
  networkService = inject(OfflineNetworkService);
  physicsService = inject(EBikePhysicsService);
  gpsService = inject(GpsTrackingService);
  routerService = inject(GraphRouterService);
  geocodingService = inject(GeocodingService);
  storageService = inject(StorageService);

  readonly openNetworkSelector = output<void>();
  readonly openTelemetry = output<void>();

  showRangeCircle = true;

  private map: L.Map | null = null;
  private networkLayerGroup: L.LayerGroup = L.layerGroup();
  private poiLayerGroup: L.LayerGroup = L.layerGroup();
  private routeLayerGroup: L.LayerGroup = L.layerGroup();
  private waypointsLayerGroup: L.LayerGroup = L.layerGroup();
  private riderMarker: L.Marker | null = null;
  private originMarker: L.Marker | null = null;
  private destMarker: L.Marker | null = null;
  private rangeCircleLayer: L.Circle | null = null;
  private hasAutoCentered = false;
  private lastRouteSetKey = '';

  constructor() {
    // React to route changes
    effect(() => {
      const route = this.navService.activeRoute();
      if (this.map) {
        if (route) {
          this.renderRoute(route);
        } else {
          this.routeLayerGroup.clearLayers();
          this.lastRouteSetKey = '';
        }
      }
    });

    // React to waypoints changes for all draggable markers (A, B, C, D...)
    effect(() => {
      const waypoints = this.navService.waypoints();
      if (this.map) {
        this.renderDraggableWaypoints(waypoints);
      }
    });

    // React to GPS position changes
    effect(() => {
      const pos = this.gpsService.currentPosition();
      if (this.map) {
        this.updateRiderPosition(pos);
      }
    });

    // React to assist or battery changes for reachability range circle
    effect(() => {
      const telemetry = this.physicsService.batteryTelemetry();
      if (this.map && this.rangeCircleLayer && this.showRangeCircle) {
        const radiusMeters = telemetry.estimatedRangeKm * 1000;
        this.rangeCircleLayer.setRadius(radiusMeters);
      }
    });

    // React to drawer expand/collapse to keep map centered in the remaining free viewport
    effect(() => {
      const isDrawerOpen = this.navService.isPlannerOpen();
      const isCollapsed = this.navService.isPlannerCollapsed();
      const route = this.navService.activeRoute();

      if (this.map && !this.navService.isNavigating()) {
        if (route && route.coordinates.length >= 2) {
          const latLngs = route.coordinates.map(p => [p.lat, p.lng] as L.LatLngTuple);
          this.focusOnCalculatedRoute(latLngs);
        } else {
          const riderPos = this.gpsService.currentPosition().point;
          if (riderPos && (riderPos.lat !== 0 || riderPos.lng !== 0)) {
            this.centerOnLocation(riderPos, this.map.getZoom());
          }
        }
      }
    });
  }

  async ngOnInit(): Promise<void> {
    this.initLeafletMap();
    this.renderBikeNetwork();
    this.renderPOIs();

    // Automatically request live GPS position and center map on user
    try {
      const posState = await this.gpsService.getCurrentLocationOrRequest();
      if (posState && !posState.isSimulated && this.map) {
        this.hasAutoCentered = true;
        this.map.setView([posState.point.lat, posState.point.lng], 16, { animate: true });
        this.updateRiderPosition(posState);
      }
    } catch {
      this.gpsService.startTracking();
    }
  }

  ngOnDestroy(): void {
    if (this.map) {
      this.map.remove();
      this.map = null;
    }
  }

  private initLeafletMap(): void {
    const defaultCenter = this.networkService.availableNetworks[0].center;
    const defaultZoom = this.networkService.availableNetworks[0].defaultZoom;

    this.map = L.map(this.mapElementRef.nativeElement, {
      center: [defaultCenter.lat, defaultCenter.lng],
      zoom: defaultZoom,
      zoomControl: false,
      attributionControl: false
    });

    // CartoDB Dark Matter / OSM Tile Layer with high contrast styling
    L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
      maxZoom: 19,
      subdomains: 'abcd'
    }).addTo(this.map);

    this.networkLayerGroup.addTo(this.map);
    this.poiLayerGroup.addTo(this.map);
    this.routeLayerGroup.addTo(this.map);
    this.waypointsLayerGroup.addTo(this.map);

    // Initial Rider Marker
    this.initRiderMarker(defaultCenter);

    // Initial Range Circle
    this.initRangeCircle(defaultCenter);

    // Set up Map Interaction Listeners (Long press for point selection)
    this.setupMapInteractionListeners();
  }

  private longPressTimer: ReturnType<typeof setTimeout> | null = null;
  private touchStartPos: { x: number; y: number } | null = null;

  private isRouteCreationMode(): boolean {
    // Available when planning waypoints before route calculation, or when actively picking a point on map
    if (this.navService.isNavigating()) return false;
    if (this.navService.activePickingWaypointIndex() !== null) return true;
    return this.navService.activeRoute() === null;
  }

  private setupMapInteractionListeners(): void {
    if (!this.map) return;

    // 1. Direct click when actively picking a specific waypoint from the UI (e.g. "📍 Map" button)
    this.map.on('click', async (e: L.LeafletMouseEvent) => {
      const pickingIdx = this.navService.activePickingWaypointIndex();
      if (pickingIdx !== null && this.isRouteCreationMode()) {
        const clickedPoint: GeoPoint = {
          lat: Number(e.latlng.lat.toFixed(6)),
          lng: Number(e.latlng.lng.toFixed(6)),
          ele: 25
        };
        const geo = await this.geocodingService.reverseGeocode(clickedPoint.lat, clickedPoint.lng);
        this.navService.setWaypoint(pickingIdx, clickedPoint, geo.name);
        this.storageService.recordPlaceUsage(geo.name, geo.street || 'Map Pin', clickedPoint);
      }
    });

    // 2. Long Press on Touch devices
    this.map.on('touchstart', (e: any) => {
      this.cancelLongPress();
      if (this.isRouteCreationMode()) {
        const point = e.containerPoint || (e.touches && e.touches[0] ? { x: e.touches[0].clientX, y: e.touches[0].clientY } : { x: 0, y: 0 });
        this.touchStartPos = { x: point.x, y: point.y };
        this.longPressTimer = setTimeout(() => {
          if (e.latlng) {
            this.handleLongPress(e.latlng);
          }
          this.cancelLongPress();
        }, 500);
      }
    });

    this.map.on('touchmove', (e: any) => {
      if (this.touchStartPos) {
        const point = e.containerPoint || (e.touches && e.touches[0] ? { x: e.touches[0].clientX, y: e.touches[0].clientY } : null);
        if (point) {
          const dx = Math.abs(point.x - this.touchStartPos.x);
          const dy = Math.abs(point.y - this.touchStartPos.y);
          if (dx > 10 || dy > 10) {
            this.cancelLongPress();
          }
        }
      }
    });

    this.map.on('touchend', () => {
      this.cancelLongPress();
    });

    // 3. Long Press on Mouse / Desktop
    this.map.on('mousedown', (e: any) => {
      if (e.originalEvent && e.originalEvent.button !== 0) return; // Only left click
      this.cancelLongPress();
      if (this.isRouteCreationMode()) {
        this.touchStartPos = { x: e.containerPoint?.x ?? 0, y: e.containerPoint?.y ?? 0 };
        this.longPressTimer = setTimeout(() => {
          if (e.latlng) {
            this.handleLongPress(e.latlng);
          }
          this.cancelLongPress();
        }, 500);
      }
    });

    this.map.on('mousemove', (e: any) => {
      if (this.touchStartPos && e.containerPoint) {
        const dx = Math.abs(e.containerPoint.x - this.touchStartPos.x);
        const dy = Math.abs(e.containerPoint.y - this.touchStartPos.y);
        if (dx > 8 || dy > 8) {
          this.cancelLongPress();
        }
      }
    });

    this.map.on('mouseup', () => {
      this.cancelLongPress();
    });

    // 4. Desktop Right-click / Context Menu (acts as instant long-press)
    this.map.on('contextmenu', (e: any) => {
      if (this.isRouteCreationMode() && e.latlng) {
        this.handleLongPress(e.latlng);
      }
    });
  }

  private cancelLongPress(): void {
    if (this.longPressTimer) {
      clearTimeout(this.longPressTimer);
      this.longPressTimer = null;
    }
    this.touchStartPos = null;
  }

  private async handleLongPress(latlng: L.LatLng): Promise<void> {
    if (!this.isRouteCreationMode() || !this.map) return;

    // Trigger haptic pulse on mobile devices
    this.gpsService.triggerTurnHaptic();

    const clickedPoint: GeoPoint = {
      lat: Number(latlng.lat.toFixed(6)),
      lng: Number(latlng.lng.toFixed(6)),
      ele: 25
    };

    const pickingIdx = this.navService.activePickingWaypointIndex();
    if (pickingIdx !== null) {
      const geo = await this.geocodingService.reverseGeocode(clickedPoint.lat, clickedPoint.lng);
      this.navService.setWaypoint(pickingIdx, clickedPoint, geo.name);
      this.storageService.recordPlaceUsage(geo.name, geo.street || 'Map Pin', clickedPoint);
      return;
    }

    // Lookup street name & number for the location
    const geo = await this.geocodingService.reverseGeocode(clickedPoint.lat, clickedPoint.lng);
    const locationTitle = geo.name || `Location (${clickedPoint.lat.toFixed(4)}, ${clickedPoint.lng.toFixed(4)})`;

    // Show themed context popup for setting start, stop, or destination
    const popupContent = document.createElement('div');
    popupContent.style.fontFamily = 'sans-serif';
    popupContent.style.padding = '4px 0';
    popupContent.style.color = '#0f172a';
    popupContent.style.maxWidth = '240px';

    popupContent.innerHTML = `
      <div style="font-weight: 800; font-size: 12px; margin-bottom: 8px; color: #0284c7; display: flex; align-items: center; gap: 4px; line-height: 1.3;">
        <span>📍</span> <span>${locationTitle}</span>
      </div>
      <div style="display: flex; flex-direction: column; gap: 6px;">
        <button id="set-origin-btn" style="background: #10b981; color: #0f172a; border: none; padding: 7px 10px; border-radius: 8px; font-weight: 800; font-size: 11px; cursor: pointer; text-align: left; display: flex; align-items: center; gap: 6px;">
          🟢 Set as Pickup / Start (A)
        </button>
        <button id="set-dest-btn" style="background: #ef4444; color: #fff; border: none; padding: 7px 10px; border-radius: 8px; font-weight: 800; font-size: 11px; cursor: pointer; text-align: left; display: flex; align-items: center; gap: 6px;">
          🏁 Set as Destination (B)
        </button>
        <button id="add-stop-btn" style="background: rgba(0,0,0,0.06); color: #0f172a; border: 1px solid rgba(0,0,0,0.15); padding: 6px 10px; border-radius: 8px; font-weight: 700; font-size: 11px; cursor: pointer; text-align: left; display: flex; align-items: center; gap: 6px;">
          ➕ Add as Stop
        </button>
      </div>
    `;

    const popup = L.popup({ className: 'custom-map-click-popup' })
      .setLatLng(latlng)
      .setContent(popupContent)
      .openOn(this.map);

    setTimeout(() => {
      const originBtn = document.getElementById('set-origin-btn');
      const addStopBtn = document.getElementById('add-stop-btn');
      const destBtn = document.getElementById('set-dest-btn');

      if (originBtn) {
        originBtn.onclick = () => {
          this.navService.setOriginPoint(clickedPoint, locationTitle);
          this.storageService.recordPlaceUsage(locationTitle, geo.street || 'Start', clickedPoint);
          this.map?.closePopup();
        };
      }
      if (addStopBtn) {
        addStopBtn.onclick = () => {
          this.navService.addWaypoint(clickedPoint, locationTitle);
          this.storageService.recordPlaceUsage(locationTitle, geo.street || 'Stop', clickedPoint);
          this.map?.closePopup();
        };
      }
      if (destBtn) {
        destBtn.onclick = () => {
          this.navService.setDestinationPoint(clickedPoint, locationTitle);
          this.storageService.recordPlaceUsage(locationTitle, geo.street || 'Destination', clickedPoint);
          this.map?.closePopup();
        };
      }
    }, 50);
  }

  private renderDraggableWaypoints(waypoints: RouteWaypoint[]): void {
    this.waypointsLayerGroup.clearLayers();
    if (!this.map) return;

    waypoints.forEach((wp, idx) => {
      if (!wp.point) return;

      const isFirst = idx === 0;
      const isLast = idx === waypoints.length - 1;
      const bg = isFirst ? '#10b981' : isLast ? '#ef4444' : '#f59e0b';
      const textColor = isFirst || !isLast ? '#0f172a' : '#fff';

      const html = `
        <div style="background: ${bg}; width: 32px; height: 32px; border-radius: 50% 50% 50% 0; transform: rotate(-45deg); display: flex; align-items: center; justify-content: center; box-shadow: 0 4px 14px rgba(0,0,0,0.5); border: 2.5px solid #fff; cursor: grab;">
          <div style="transform: rotate(45deg); color: ${textColor}; font-size: 13px; font-weight: 900;">${wp.letter}</div>
        </div>
      `;

      const icon = L.divIcon({
        className: `waypoint-pin-${wp.letter}`,
        html,
        iconSize: [32, 32],
        iconAnchor: [16, 32]
      });

      const marker = L.marker([wp.point.lat, wp.point.lng], {
        icon,
        draggable: true
      });

      marker.bindTooltip(`${wp.label} (Drag to move)`, { direction: 'top' });

      marker.on('dragend', () => {
        const pos = marker.getLatLng();
        if (pos) {
          const pt: GeoPoint = { lat: Number(pos.lat.toFixed(6)), lng: Number(pos.lng.toFixed(6)), ele: wp.point?.ele || 25 };
          this.navService.setWaypoint(idx, pt);
        }
      });

      this.waypointsLayerGroup.addLayer(marker);
    });
  }

  private initRiderMarker(center: { lat: number; lng: number }): void {
    if (!this.map) return;

    const riderHtml = `
      <div class="rider-marker-pulse">
        <div class="rider-inner-dot">
          <svg viewBox="0 0 24 24" fill="currentColor">
            <polygon points="12,2 22,22 12,17 2,22"/>
          </svg>
        </div>
      </div>
    `;

    const customIcon = L.divIcon({
      className: 'rider-custom-icon',
      html: riderHtml,
      iconSize: [36, 36],
      iconAnchor: [18, 18]
    });

    this.riderMarker = L.marker([center.lat, center.lng], { icon: customIcon }).addTo(this.map);
  }

  private initRangeCircle(center: { lat: number; lng: number }): void {
    if (!this.map) return;

    const rangeKm = this.physicsService.batteryTelemetry().estimatedRangeKm;
    this.rangeCircleLayer = L.circle([center.lat, center.lng], {
      radius: rangeKm * 1000,
      color: '#10b981',
      fillColor: '#10b981',
      fillOpacity: 0.07,
      weight: 1.5,
      dashArray: '4, 8'
    }).addTo(this.map);
  }

  private updateRiderPosition(pos: RiderPositionState): void {
    if (!this.riderMarker || !this.map) return;

    const latLng = L.latLng(pos.point.lat, pos.point.lng);
    this.riderMarker.setLatLng(latLng);

    if (this.rangeCircleLayer) {
      this.rangeCircleLayer.setLatLng(latLng);
    }

    // Auto-center once when first real GPS fix arrives
    if (!this.hasAutoCentered && !pos.isSimulated) {
      this.hasAutoCentered = true;
      this.map.setView(latLng, 16, { animate: true });
    }

    // Rotate directional heading cone
    const element = this.riderMarker.getElement();
    if (element) {
      const innerDot = element.querySelector('.rider-inner-dot') as HTMLElement;
      if (innerDot && pos.heading) {
        innerDot.style.transform = `rotate(${pos.heading}deg)`;
      }
    }

    // Auto-center in navigation mode
    if (this.navService.isNavigating()) {
      this.map.panTo(latLng, { animate: true, duration: 0.5 });
    }
  }

  renderBikeNetwork(): void {
    this.networkLayerGroup.clearLayers();
    // Network is rendered via OSM base map and clean active route overlay for maximum 60fps performance
  }

  renderPOIs(): void {
    this.poiLayerGroup.clearLayers();
    const pois = this.networkService.pois();

    for (const poi of pois) {
      const isCharger = poi.category === 'charger';
      const isRepair = poi.category === 'repair';
      const iconEmoji = isCharger ? '⚡' : isRepair ? '🔧' : '👁️';
      const badgeBg = isCharger ? '#10b981' : isRepair ? '#38bdf8' : '#f59e0b';

      const poiHtml = `
        <div style="background: ${badgeBg}; width: 28px; height: 28px; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-size: 14px; box-shadow: 0 4px 12px rgba(0,0,0,0.5); border: 2px solid #fff;">
          ${iconEmoji}
        </div>
      `;

      const icon = L.divIcon({
        className: 'poi-custom-marker',
        html: poiHtml,
        iconSize: [28, 28],
        iconAnchor: [14, 14]
      });

      const marker = L.marker([poi.coordinates.lat, poi.coordinates.lng], { icon });
      marker.bindPopup(`
        <div style="color: #0f172a; font-family: sans-serif; font-size: 12px; min-width: 160px;">
          <strong style="font-size: 13px; color: #0284c7;">${poi.name}</strong>
          <p style="margin: 4px 0; color: #475569;">${poi.description || ''}</p>
          ${poi.hasFastCharging ? '<span style="background: #10b981; color: #fff; padding: 2px 6px; border-radius: 4px; font-weight: bold; font-size: 10px;">FAST 4A CHARGE</span>' : ''}
        </div>
      `);

      this.poiLayerGroup.addLayer(marker);
    }
  }

  private renderRoute(route: RouteResult): void {
    this.routeLayerGroup.clearLayers();
    if (!this.map || route.coordinates.length < 2) return;

    this.map.closePopup();

    const availableRoutes = this.navService.availableRoutes();
    const currentActiveIdx = this.navService.selectedRouteIndex();

    // 1. Render alternative routes in background (clickable)
    availableRoutes.forEach((altRoute, altIdx) => {
      if (altIdx === currentActiveIdx) return; // Skip active route for now

      const altLatLngs = altRoute.coordinates.map(p => [p.lat, p.lng] as L.LatLngTuple);
      const altPolyline = L.polyline(altLatLngs, {
        color: '#64748b',
        weight: 5,
        opacity: 0.6,
        dashArray: '6, 8',
        lineCap: 'round'
      });

      altPolyline.bindTooltip(
        `<div style="font-family: sans-serif; font-size: 11px; font-weight: 700; color: #0f172a;">
          ${altRoute.name || 'Alternative Route'} · ${(altRoute.totalDistanceMeters / 1000).toFixed(1)} km · ⚡ ${altRoute.totalEnergyWh} Wh<br>
          <span style="color: #0284c7; font-size: 10px;">Tap to select this route</span>
        </div>`,
        { sticky: true }
      );

      altPolyline.on('click', () => {
        this.navService.selectRoute(altIdx);
      });

      this.routeLayerGroup.addLayer(altPolyline);
    });

    // 2. Render Outer Glow Casing for Active Route
    const activeLatLngs = route.coordinates.map(p => [p.lat, p.lng] as L.LatLngTuple);
    const casing = L.polyline(activeLatLngs, {
      color: '#38bdf8',
      weight: 10,
      opacity: 0.35,
      lineCap: 'round'
    });
    this.routeLayerGroup.addLayer(casing);

    // 3. Render Grade/Elevation-Colored Segments for Active Route
    const getGradeColor = (grade: number): string => {
      if (grade < 0) return '#06b6d4'; // Downhill (Regen)
      if (grade <= 3) return '#10b981'; // 0-3% Flat/Gentle
      if (grade <= 7) return '#f59e0b'; // 3-7% Moderate Slope
      return '#ef4444'; // >7% Steep Climb
    };

    if (route.segments && route.segments.length > 0) {
      route.segments.forEach(seg => {
        if (!seg.coordinates || seg.coordinates.length < 2) return;
        const segLatLngs = seg.coordinates.map(p => [p.lat, p.lng] as L.LatLngTuple);
        const segColor = getGradeColor(seg.gradePercent);

        const segLine = L.polyline(segLatLngs, {
          color: segColor,
          weight: 6,
          opacity: 0.95,
          lineCap: 'round',
          lineJoin: 'round'
        });

        const slopeSign = seg.gradePercent > 0 ? '+' : '';
        segLine.bindTooltip(
          `<div style="font-family: sans-serif; font-size: 11px; color: #0f172a;">
            <strong style="color: ${segColor};">${seg.name || 'Cycleway'}</strong><br>
            Slope: <strong>${slopeSign}${seg.gradePercent}%</strong> · Distance: <strong>${seg.distanceMeters}m</strong><br>
            Elevation Gain: <strong>+${seg.elevationGainM}m</strong> · Energy: <strong>${seg.estimatedEnergyWh} Wh</strong>
          </div>`,
          { sticky: true }
        );

        this.routeLayerGroup.addLayer(segLine);
      });
    } else {
      // Fallback: Uniform bright route if segments unavailable
      const mainRoute = L.polyline(activeLatLngs, {
        color: '#0284c7',
        weight: 6,
        opacity: 0.95,
        lineCap: 'round'
      });
      this.routeLayerGroup.addLayer(mainRoute);
    }

    // Auto-focus camera ONLY when a NEW set of routes is computed, preventing screen jump/flash on switching cards
    const routeSetKey = availableRoutes.map(r => r.id).join('|');
    if (routeSetKey !== this.lastRouteSetKey) {
      this.lastRouteSetKey = routeSetKey;
      if (this.map && !this.navService.isNavigating()) {
        this.focusOnCalculatedRoute(activeLatLngs);
      }
    }
  }

  private focusOnCalculatedRoute(latLngs: L.LatLngTuple[]): void {
    if (!this.map || latLngs.length < 2) return;

    const bounds = L.latLngBounds(latLngs);

    // Also include any waypoints outside the path if present
    for (const wp of this.navService.waypoints()) {
      if (wp.point) {
        bounds.extend([wp.point.lat, wp.point.lng]);
      }
    }

    const isDesktop = window.innerWidth > 768;
    const isDrawerOpen = this.navService.isPlannerOpen();
    const isCollapsed = this.navService.isPlannerCollapsed();

    let bottomPadding = 30;
    if (!isDesktop && isDrawerOpen) {
      bottomPadding = isCollapsed ? 80 : Math.round(window.innerHeight * 0.49);
    }

    const paddingTL: L.PointTuple = isDesktop ? [460, 40] : [24, 24];
    const paddingBR: L.PointTuple = isDesktop ? [40, 40] : [24, bottomPadding];

    this.map.flyToBounds(bounds, {
      paddingTopLeft: paddingTL,
      paddingBottomRight: paddingBR,
      maxZoom: 16,
      duration: 0.8,
      easeLinearity: 0.25
    });
  }

  toggleRangeCircle(): void {
    this.showRangeCircle = !this.showRangeCircle;
    if (!this.map || !this.rangeCircleLayer) return;

    if (this.showRangeCircle) {
      this.rangeCircleLayer.addTo(this.map);
    } else {
      this.rangeCircleLayer.remove();
    }
  }

  fitToRoute(): void {
    const route = this.navService.activeRoute();
    if (!route || !this.map || route.coordinates.length < 2) return;

    const latLngs = route.coordinates.map(p => [p.lat, p.lng] as L.LatLngTuple);
    this.focusOnCalculatedRoute(latLngs);
  }

  centerOnLocation(point: GeoPoint, zoom = 16): void {
    if (!this.map) return;
    const isDesktop = window.innerWidth > 768;
    const isDrawerOpen = this.navService.isPlannerOpen();
    const isCollapsed = this.navService.isPlannerCollapsed();

    // Center point in the remaining visible area above the drawer
    if (!isDesktop && isDrawerOpen && !isCollapsed) {
      const freeAreaOffsetPx = Math.round(window.innerHeight * 0.22);
      this.map.setView([point.lat, point.lng], zoom, { animate: false });
      this.map.panBy([0, freeAreaOffsetPx], { animate: true, duration: 0.4 });
    } else {
      this.map.setView([point.lat, point.lng], zoom, { animate: true });
    }
  }

  async recenterOnRider(): Promise<void> {
    const posState = await this.gpsService.getCurrentLocationOrRequest();
    if (!this.map) return;
    const pos = posState.point;
    this.centerOnLocation(pos, 16);
  }

  switchNetwork(networkId: string): void {
    this.networkService.activeNetworkId.set(networkId);
    const net = this.networkService.availableNetworks.find(n => n.id === networkId);
    if (net && this.map) {
      this.map.setView([net.center.lat, net.center.lng], net.defaultZoom);
    }
    this.renderBikeNetwork();
  }
}

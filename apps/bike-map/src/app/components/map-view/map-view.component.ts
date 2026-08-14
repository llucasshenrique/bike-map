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
          class="map-btn"
          [class.active]="showRangeCircle"
          (click)="toggleRangeCircle()"
          title="Toggle Battery Reachability Circle"
        >
          <span class="btn-icon">⚡</span>
          <span class="btn-text">{{ physicsService.batteryTelemetry().estimatedRangeKm }} km</span>
        </button>

        <!-- Network Switcher -->
        <button class="map-btn" (click)="openNetworkSelector.emit()" title="Switch Offline Bike Network">
          <span class="btn-icon">🗺️</span>
          <span class="btn-text">Trails</span>
        </button>

        <!-- Bike Cockpit HUD -->
        <button class="map-btn" (click)="openTelemetry.emit()" title="E-Bike Cockpit Computer">
          <span class="btn-icon">🚲</span>
          <span class="btn-text">Cockpit</span>
        </button>
      </div>

      <!-- MAP BOTTOM RIGHT CONTROLS -->
      <div class="floating-controls-bottom-right">
        <!-- Fit Route -->
        @if (navService.activeRoute()) {
          <button class="action-circle-btn" (click)="fitToRoute()" title="Fit Route to Screen">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M15 3h6v6M9 21H3v-6M21 3l-7 7M3 21l7-7"/>
            </svg>
          </button>
        }

        <!-- Recenter GPS -->
        <button class="action-circle-btn gps-btn" (click)="recenterOnRider()" title="Recenter GPS Position">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="12" cy="12" r="8"/>
            <line x1="12" y1="2" x2="12" y2="6"/>
            <line x1="12" y1="18" x2="12" y2="22"/>
            <line x1="2" y1="12" x2="6" y2="12"/>
            <line x1="18" y1="12" x2="22" y2="12"/>
          </svg>
        </button>
      </div>
    </div>
  `,
  styles: [`
    .map-container-wrapper {
      position: relative;
      width: 100vw;
      height: 100vh;
      overflow: hidden;
      background: #0b1120;
    }
    .leaflet-map-element {
      width: 100%;
      height: 100%;
      z-index: 1;
    }
    .crosshair-cursor {
      cursor: crosshair !important;
    }

    /* PICKING MODE BANNER */
    .picking-mode-banner {
      position: absolute;
      top: 18px;
      left: 50%;
      transform: translateX(-50%);
      background: rgba(15, 23, 42, 0.95);
      backdrop-filter: blur(16px);
      border: 1.5px solid #38bdf8;
      border-radius: 30px;
      padding: 8px 16px;
      display: flex;
      align-items: center;
      gap: 14px;
      z-index: 1100;
      box-shadow: 0 12px 36px rgba(0, 0, 0, 0.7);
      animation: dropDown 0.3s ease;
    }
    .banner-badge {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 0.85rem;
      font-weight: 700;
      color: #f8fafc;
    }
    .picking-pulse {
      width: 10px;
      height: 10px;
      border-radius: 50%;
      background: #38bdf8;
      box-shadow: 0 0 12px #38bdf8;
      animation: pulse 1s infinite;
    }
    .banner-cancel-btn {
      background: rgba(255, 255, 255, 0.15);
      border: none;
      color: #cbd5e1;
      padding: 4px 10px;
      border-radius: 12px;
      font-size: 0.75rem;
      font-weight: 700;
      cursor: pointer;
    }
    .banner-cancel-btn:hover {
      background: rgba(239, 68, 68, 0.3);
      color: #fca5a5;
    }

    /* TOP RIGHT PILLS */
    .floating-controls-top-right {
      position: absolute;
      top: 14px;
      right: 14px;
      display: flex;
      flex-direction: column;
      gap: 8px;
      z-index: 900;
    }
    .map-btn {
      background: rgba(15, 23, 42, 0.9);
      backdrop-filter: blur(12px);
      border: 1px solid rgba(255, 255, 255, 0.15);
      border-radius: 20px;
      padding: 8px 14px;
      color: #f8fafc;
      font-size: 0.78rem;
      font-weight: 700;
      display: flex;
      align-items: center;
      gap: 6px;
      cursor: pointer;
      box-shadow: 0 4px 16px rgba(0, 0, 0, 0.4);
      transition: all 0.2s ease;
    }
    .map-btn.active {
      background: #10b981;
      color: #0f172a;
      border-color: #34d399;
      box-shadow: 0 4px 14px rgba(16, 185, 129, 0.4);
    }

    /* BOTTOM RIGHT ACTION BUTTONS */
    .floating-controls-bottom-right {
      position: absolute;
      bottom: 24px;
      right: 14px;
      display: flex;
      flex-direction: column;
      gap: 10px;
      z-index: 900;
    }
    .action-circle-btn {
      width: 44px;
      height: 44px;
      border-radius: 50%;
      background: rgba(15, 23, 42, 0.9);
      backdrop-filter: blur(12px);
      border: 1px solid rgba(255, 255, 255, 0.18);
      color: #f8fafc;
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      box-shadow: 0 8px 24px rgba(0, 0, 0, 0.5);
    }
    .action-circle-btn svg {
      width: 20px;
      height: 20px;
    }
    .action-circle-btn.gps-btn {
      background: #38bdf8;
      color: #0f172a;
      border-color: #7dd3fc;
    }

    @keyframes dropDown {
      from { transform: translate(-50%, -20px); opacity: 0; }
      to { transform: translate(-50%, 0); opacity: 1; }
    }
    @keyframes pulse {
      0%, 100% { transform: scale(1); opacity: 1; }
      50% { transform: scale(1.4); opacity: 0.6; }
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

  constructor() {
    // React to route changes
    effect(() => {
      const route = this.navService.activeRoute();
      if (this.map) {
        if (route) {
          this.renderRoute(route);
        } else {
          this.routeLayerGroup.clearLayers();
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
  }

  ngOnInit(): void {
    this.initLeafletMap();
    this.renderBikeNetwork();
    this.renderPOIs();
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

    // Set up Map Click Listener for interactive point picking
    this.map.on('click', (e: L.LeafletMouseEvent) => {
      this.handleMapClick(e.latlng);
    });
  }

  private handleMapClick(latlng: L.LatLng): void {
    const clickedPoint: GeoPoint = { lat: Number(latlng.lat.toFixed(6)), lng: Number(latlng.lng.toFixed(6)), ele: 25 };
    const pickingIdx = this.navService.activePickingWaypointIndex();

    if (pickingIdx !== null) {
      this.navService.setWaypoint(pickingIdx, clickedPoint);
      return;
    }

    // Normal mode: Show context popup at click location
    if (this.map) {
      const popupContent = document.createElement('div');
      popupContent.style.fontFamily = 'sans-serif';
      popupContent.style.padding = '4px 0';
      popupContent.style.color = '#0f172a';

      popupContent.innerHTML = `
        <div style="font-weight: 700; font-size: 13px; margin-bottom: 6px; color: #0284c7;">
          📍 Selected Location (${clickedPoint.lat.toFixed(4)}, ${clickedPoint.lng.toFixed(4)})
        </div>
        <div style="display: flex; flex-direction: column; gap: 6px;">
          <button id="set-origin-btn" style="background: #10b981; color: #fff; border: none; padding: 6px 10px; border-radius: 6px; font-weight: 700; font-size: 11px; cursor: pointer; text-align: left;">
            🟢 Set as Start Point (A)
          </button>
          <button id="add-stop-btn" style="background: #f59e0b; color: #0f172a; border: none; padding: 6px 10px; border-radius: 6px; font-weight: 700; font-size: 11px; cursor: pointer; text-align: left;">
            ➕ Add as Intermediate Stop
          </button>
          <button id="set-dest-btn" style="background: #ef4444; color: #fff; border: none; padding: 6px 10px; border-radius: 6px; font-weight: 700; font-size: 11px; cursor: pointer; text-align: left;">
            🏁 Set as Destination
          </button>
          <button id="route-here-btn" style="background: #0284c7; color: #fff; border: none; padding: 6px 10px; border-radius: 6px; font-weight: 700; font-size: 11px; cursor: pointer; text-align: left;">
            ⚡ Route from GPS to Here
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
        const routeHereBtn = document.getElementById('route-here-btn');

        if (originBtn) {
          originBtn.onclick = () => {
            this.navService.setOriginPoint(clickedPoint);
            this.map?.closePopup();
          };
        }
        if (addStopBtn) {
          addStopBtn.onclick = () => {
            this.navService.addWaypoint(clickedPoint);
            this.map?.closePopup();
          };
        }
        if (destBtn) {
          destBtn.onclick = () => {
            this.navService.setDestinationPoint(clickedPoint);
            this.map?.closePopup();
          };
        }
        if (routeHereBtn) {
          routeHereBtn.onclick = () => {
            const gpsPt = this.gpsService.currentPosition().point;
            this.navService.setOriginPoint(gpsPt);
            this.navService.setDestinationPoint(clickedPoint);
            this.map?.closePopup();
          };
        }
      }, 50);
    }
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
    if (route.coordinates.length < 2) return;

    const latLngs = route.coordinates.map(p => [p.lat, p.lng] as L.LatLngTuple);

    // Outer glow casing
    const casing = L.polyline(latLngs, {
      color: '#38bdf8',
      weight: 9,
      opacity: 0.4,
      lineCap: 'round'
    });

    // Inner bright active route
    const mainRoute = L.polyline(latLngs, {
      color: '#0284c7',
      weight: 5,
      opacity: 0.95,
      lineCap: 'round'
    });

    this.routeLayerGroup.addLayer(casing);
    this.routeLayerGroup.addLayer(mainRoute);

    if (this.map && !this.navService.isNavigating()) {
      this.map.fitBounds(latLngs, { padding: [60, 60], maxZoom: 16 });
    }
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

    const bounds = L.latLngBounds(route.coordinates.map(p => [p.lat, p.lng] as L.LatLngTuple));
    this.map.fitBounds(bounds, { padding: [60, 60], maxZoom: 16 });
  }

  recenterOnRider(): void {
    if (!this.map) return;
    const pos = this.gpsService.currentPosition().point;
    this.map.setView([pos.lat, pos.lng], 15, { animate: true });
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

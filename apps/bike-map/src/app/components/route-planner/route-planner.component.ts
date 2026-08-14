import { Component, inject, signal, computed, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NavigationService } from '../../core/services/navigation.service';
import { GraphRouterService } from '../../core/services/graph-router.service';
import { OfflineNetworkService } from '../../core/services/offline-network.service';
import { EBikePhysicsService } from '../../core/services/ebike-physics.service';
import { GpsTrackingService } from '../../core/services/gps-tracking.service';
import { GeocodingService, SearchResultItem } from '../../core/services/geocoding.service';
import { RoutingProfile, RouteResult, RouteWaypoint } from '../../core/models/routing.types';
import { GeoPoint } from '../../core/models/geo.types';
import { StorageService } from '../../core/services/storage.service';
import { ElevationChartComponent } from '../elevation-chart/elevation-chart.component';

@Component({
  selector: 'app-route-planner',
  standalone: true,
  imports: [CommonModule, FormsModule, ElevationChartComponent],
  template: `
    <div class="planner-panel">
      <!-- HEADER -->
      <div class="panel-header">
        <div class="title-with-badge">
          <span class="pulse-indicator"></span>
          <h2>E-Bike Route Planner</h2>
          <span class="offline-tag">MULTI-STOP ROUTING</span>
        </div>
        <button class="close-btn" (click)="close.emit()">✕</button>
      </div>

      <!-- ROUTING PROFILE SELECTOR -->
      <div class="profile-tabs">
        <button
          class="profile-tab"
          [class.active]="navService.selectedProfile() === 'efficient'"
          (click)="selectProfile('efficient')"
        >
          <span class="tab-icon">⚡</span>
          <span class="tab-name">Eco Efficient</span>
        </button>
        <button
          class="profile-tab"
          [class.active]="navService.selectedProfile() === 'turbo'"
          (click)="selectProfile('turbo')"
        >
          <span class="tab-icon">🚀</span>
          <span class="tab-name">Turbo Fast</span>
        </button>
        <button
          class="profile-tab"
          [class.active]="navService.selectedProfile() === 'scenic'"
          (click)="selectProfile('scenic')"
        >
          <span class="tab-icon">🌲</span>
          <span class="tab-name">Scenic Trails</span>
        </button>
        <button
          class="profile-tab"
          [class.active]="navService.selectedProfile() === 'safe'"
          (click)="selectProfile('safe')"
        >
          <span class="tab-icon">🛡️</span>
          <span class="tab-name">Safe Bikeways</span>
        </button>
      </div>

      <!-- WAYPOINTS LIST WITH SEARCH & REORGANIZE -->
      <div class="waypoints-container">
        @for (wp of navService.waypoints(); track wp.id; let idx = $index; let first = $first; let last = $last) {
          <div class="waypoint-card" [class.is-picking]="navService.activePickingWaypointIndex() === idx">
            <!-- Left Marker Badge -->
            <div class="marker-col">
              <div
                class="waypoint-badge"
                [class.start-badge]="first"
                [class.dest-badge]="last"
                [class.stop-badge]="!first && !last"
              >
                {{ wp.letter }}
              </div>
              @if (!last) {
                <div class="waypoint-connector-line"></div>
              }
            </div>

            <!-- Content & Search Input -->
            <div class="waypoint-main">
              <div class="waypoint-top-bar">
                <span class="waypoint-role-label">
                  {{ first ? 'START POINT' : last ? 'DESTINATION' : 'STOP ' + wp.letter }}
                </span>

                <!-- Reorder & Action Controls -->
                <div class="waypoint-tools">
                  <button
                    class="tool-btn"
                    [disabled]="first"
                    (click)="navService.moveWaypoint(idx, idx - 1)"
                    title="Move Up"
                  >▲</button>
                  <button
                    class="tool-btn"
                    [disabled]="last"
                    (click)="navService.moveWaypoint(idx, idx + 1)"
                    title="Move Down"
                  >▼</button>
                  @if (navService.waypoints().length > 2) {
                    <button
                      class="tool-btn remove-btn"
                      (click)="navService.removeWaypoint(idx)"
                      title="Remove Stop"
                    >✕</button>
                  }
                </div>
              </div>

              <!-- Search / Location Box -->
              <div class="search-input-wrapper">
                <input
                  type="text"
                  class="search-input"
                  [placeholder]="'Search street, place, or tap map...'"
                  [ngModel]="getWaypointSearchText(wp)"
                  (ngModelChange)="onSearchInput(wp.id, $event, idx)"
                  (focus)="onSearchFocus(wp.id)"
                />

                <div class="input-actions">
                  <button
                    class="btn-pick-map"
                    [class.active]="navService.activePickingWaypointIndex() === idx"
                    (click)="pickWaypointOnMap(idx)"
                    title="Tap on Map"
                  >
                    📍 Map
                  </button>
                  @if (first) {
                    <button class="btn-gps-tag" (click)="setGpsAsWaypoint(idx)" title="Use GPS Location">
                      ⚡ GPS
                    </button>
                  }
                </div>

                <!-- Autocomplete Dropdown -->
                @if (activeSearchWpId() === wp.id && hasSearchResults(wp.id)) {
                  <div class="search-results-dropdown">
                    @for (res of searchResults[wp.id]; track res.id) {
                      <div class="search-item" (click)="selectSearchResult(idx, res)">
                        <span class="search-icon">{{ res.category === 'poi' ? '⚡' : res.category === 'city' ? '🏙️' : '📍' }}</span>
                        <div class="search-info">
                          <strong class="search-title">{{ res.name }}</strong>
                          <span class="search-sub">{{ res.subText }}</span>
                        </div>
                      </div>
                    }
                  </div>
                }
              </div>
            </div>
          </div>
        }
      </div>

      <!-- WAYPOINT TOOLBAR: ADD STOP / REVERSE / CLEAR -->
      <div class="waypoint-toolbar">
        <button class="btn-add-stop" (click)="navService.addWaypoint()">
          <span>+ Add Stop</span>
        </button>
        <button class="btn-reverse" (click)="navService.reverseWaypoints()">
          <span>⇄ Reverse</span>
        </button>
        <button class="btn-clear-all" (click)="navService.clearRoute()">
          <span>Clear All</span>
        </button>
      </div>

      <!-- PRIMARY ACTION: CALCULATE ROUTE -->
      <div class="calc-action-row">
        <button
          class="btn-calculate"
          [disabled]="!navService.hasEnoughValidPoints() || navService.isCalculating()"
          (click)="calculateRouteNow()"
        >
          @if (navService.isCalculating()) {
            <span class="spinner-icon">⏳</span>
            <span>CALCULATING REAL ROUTE...</span>
          } @else {
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" class="calc-icon">
              <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>
            </svg>
            <span>CALCULATE E-BIKE ROUTE</span>
          }
        </button>
      </div>

      <!-- ROUTE RESULT SUMMARY CARD -->
      @if (navService.activeRoute(); as route) {
        <div class="route-summary-card">
          <!-- Main Highlights -->
          <div class="summary-grid">
            <div class="sum-item">
              <span class="sum-val text-primary">{{ formatDistance(route.totalDistanceMeters) }}</span>
              <span class="sum-lbl">TOTAL DISTANCE</span>
            </div>
            <div class="sum-item">
              <span class="sum-val text-cyan">{{ formatDuration(route.totalDurationSeconds) }}</span>
              <span class="sum-lbl">EST. RIDE TIME</span>
            </div>
            <div class="sum-item">
              <span class="sum-val text-green">-{{ route.totalEnergyWh }} Wh</span>
              <span class="sum-lbl">BATTERY ({{ route.batteryDrainPercent }}%)</span>
            </div>
            <div class="sum-item">
              <span class="sum-val text-amber">▲{{ route.elevationGainM }}m</span>
              <span class="sum-lbl">CLIMB (Max {{ route.maxGradePercent }}%)</span>
            </div>
          </div>

          <!-- Battery Reachability Meter -->
          <div class="battery-projection">
            <div class="proj-header">
              <span>Arrival Battery Estimate</span>
              <strong [style.color]="getBatteryColor(route.batteryRemainingPercent)">
                {{ route.batteryRemainingPercent }}% ({{ route.estimatedBatteryRemainingWh }} Wh)
              </strong>
            </div>
            <div class="proj-bar-bg">
              <div
                class="proj-bar-fill"
                [style.width.%]="route.batteryRemainingPercent"
                [style.background]="getBatteryColor(route.batteryRemainingPercent)"
              ></div>
            </div>
          </div>

          <!-- Elevation Chart -->
          <div class="chart-wrapper">
            <app-elevation-chart
              [points]="route.elevationProfile"
              [elevationGain]="route.elevationGainM"
              [elevationLoss]="route.elevationLossM"
              [maxGrade]="route.maxGradePercent"
            />
          </div>

          <!-- Action Buttons -->
          <div class="action-buttons">
            <button class="btn-start" (click)="startNavigation(route)">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                <polygon points="5 3 19 12 5 21 5 3"/>
              </svg>
              <span>START TURN-BY-TURN</span>
            </button>

            <button class="btn-sim" (click)="startSimulation(route)">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <circle cx="12" cy="12" r="10"/>
                <polygon points="10 8 16 12 10 16 10 8"/>
              </svg>
              <span>SIMULATOR</span>
            </button>
          </div>

          <!-- Expandable Turn List Toggle -->
          <div class="instructions-accordion">
            <button class="toggle-steps-btn" (click)="showSteps.set(!showSteps())">
              <span>Turn-by-Turn Maneuvers ({{ route.instructions.length }} steps)</span>
              <span>{{ showSteps() ? '▲ Hide' : '▼ View' }}</span>
            </button>

            @if (showSteps()) {
              <div class="steps-list">
                @for (step of route.instructions; track step.index) {
                  <div class="step-item">
                    <div class="step-idx">{{ step.index + 1 }}</div>
                    <div class="step-info">
                      <div class="step-text">{{ step.text }}</div>
                      <div class="step-meta">
                        <span>{{ step.distanceMeters }}m</span>
                        @if (step.gradePercent !== 0) {
                          <span [class.climb]="step.gradePercent > 5">Slope: {{ step.gradePercent }}%</span>
                        }
                        <span>⚡ {{ step.energyWh }} Wh</span>
                      </div>
                    </div>
                  </div>
                }
              </div>
            }
          </div>
        </div>
      } @else {
        <div class="empty-state">
          <p>
            {{ navService.hasEnoughValidPoints() ? 'Waypoints ready! Tap "Calculate E-Bike Route" above.' : 'Search places or tap on the map to set waypoints.' }}
          </p>
        </div>
      }
    </div>
  `,
  styles: [`
    .planner-panel {
      position: absolute;
      top: 12px;
      left: 12px;
      width: 440px;
      max-width: calc(100vw - 24px);
      max-height: calc(100vh - 24px);
      overflow-y: auto;
      background: rgba(15, 23, 42, 0.95);
      backdrop-filter: blur(16px);
      border: 1px solid rgba(255, 255, 255, 0.12);
      border-radius: 18px;
      padding: 16px;
      color: #f8fafc;
      z-index: 1000;
      box-shadow: 0 16px 48px rgba(0, 0, 0, 0.6);
    }

    .panel-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 12px;
    }
    .title-with-badge {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .pulse-indicator {
      width: 8px;
      height: 8px;
      background: #10b981;
      border-radius: 50%;
      box-shadow: 0 0 10px #10b981;
    }
    h2 {
      font-size: 1.05rem;
      font-weight: 700;
      margin: 0;
      color: #fff;
    }
    .offline-tag {
      font-size: 0.62rem;
      font-weight: 800;
      background: rgba(16, 185, 129, 0.2);
      color: #34d399;
      padding: 2px 6px;
      border-radius: 4px;
      letter-spacing: 0.05em;
    }
    .close-btn {
      background: rgba(255, 255, 255, 0.1);
      border: none;
      color: #94a3b8;
      width: 28px;
      height: 28px;
      border-radius: 50%;
      cursor: pointer;
      font-size: 0.85rem;
    }

    /* PROFILE TABS */
    .profile-tabs {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      gap: 6px;
      background: rgba(0, 0, 0, 0.3);
      padding: 4px;
      border-radius: 12px;
      margin-bottom: 12px;
    }
    .profile-tab {
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: 6px 2px;
      background: transparent;
      border: none;
      color: #94a3b8;
      border-radius: 8px;
      cursor: pointer;
      transition: all 0.2s ease;
    }
    .profile-tab.active {
      background: rgba(255, 255, 255, 0.12);
      color: #38bdf8;
      box-shadow: 0 2px 8px rgba(0, 0, 0, 0.3);
    }
    .tab-icon {
      font-size: 1rem;
    }
    .tab-name {
      font-size: 0.65rem;
      font-weight: 700;
      margin-top: 2px;
      white-space: nowrap;
    }

    /* WAYPOINTS CONTAINER */
    .waypoints-container {
      display: flex;
      flex-direction: column;
      gap: 8px;
      margin-bottom: 10px;
    }
    .waypoint-card {
      display: flex;
      gap: 10px;
      background: rgba(255, 255, 255, 0.04);
      border: 1px solid rgba(255, 255, 255, 0.08);
      border-radius: 12px;
      padding: 8px 10px;
      transition: border-color 0.2s ease;
    }
    .waypoint-card.is-picking {
      border-color: #38bdf8;
      background: rgba(56, 189, 248, 0.08);
    }
    .marker-col {
      display: flex;
      flex-direction: column;
      align-items: center;
      width: 24px;
    }
    .waypoint-badge {
      width: 24px;
      height: 24px;
      border-radius: 50%;
      display: flex;
      align-items: center;
      justify-content: center;
      font-weight: 900;
      font-size: 0.75rem;
      flex-shrink: 0;
    }
    .start-badge {
      background: #10b981;
      color: #0f172a;
    }
    .dest-badge {
      background: #ef4444;
      color: #fff;
    }
    .stop-badge {
      background: #f59e0b;
      color: #0f172a;
    }
    .waypoint-connector-line {
      width: 2px;
      flex: 1;
      min-height: 12px;
      background: #475569;
      margin-top: 4px;
    }

    .waypoint-main {
      flex: 1;
      min-width: 0;
      position: relative;
    }
    .waypoint-top-bar {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 4px;
    }
    .waypoint-role-label {
      font-size: 0.62rem;
      font-weight: 800;
      color: #64748b;
      letter-spacing: 0.04em;
    }
    .waypoint-tools {
      display: flex;
      gap: 4px;
    }
    .tool-btn {
      background: rgba(255, 255, 255, 0.08);
      border: none;
      color: #94a3b8;
      width: 20px;
      height: 20px;
      border-radius: 4px;
      font-size: 0.65rem;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .tool-btn:disabled {
      opacity: 0.3;
      cursor: not-allowed;
    }
    .remove-btn:hover {
      color: #ef4444;
      background: rgba(239, 68, 68, 0.2);
    }

    /* SEARCH INPUT */
    .search-input-wrapper {
      position: relative;
      display: flex;
      align-items: center;
      background: rgba(15, 23, 42, 0.85);
      border: 1px solid rgba(255, 255, 255, 0.15);
      border-radius: 8px;
      padding: 2px 4px;
    }
    .search-input {
      flex: 1;
      min-width: 0;
      background: transparent;
      border: none;
      outline: none;
      color: #f8fafc;
      font-size: 0.8rem;
      padding: 6px 8px;
    }
    .search-input::placeholder {
      color: #64748b;
    }
    .input-actions {
      display: flex;
      gap: 4px;
    }
    .btn-pick-map {
      background: rgba(56, 189, 248, 0.15);
      border: 1px solid rgba(56, 189, 248, 0.3);
      color: #38bdf8;
      font-size: 0.65rem;
      font-weight: 700;
      padding: 3px 6px;
      border-radius: 4px;
      cursor: pointer;
    }
    .btn-pick-map.active {
      background: #38bdf8;
      color: #0f172a;
    }
    .btn-gps-tag {
      background: rgba(16, 185, 129, 0.2);
      border: 1px solid rgba(16, 185, 129, 0.4);
      color: #34d399;
      font-size: 0.65rem;
      font-weight: 800;
      padding: 3px 6px;
      border-radius: 4px;
      cursor: pointer;
    }

    /* SEARCH DROPDOWN */
    .search-results-dropdown {
      position: absolute;
      top: calc(100% + 4px);
      left: 0;
      right: 0;
      background: #0f172a;
      border: 1px solid rgba(56, 189, 248, 0.4);
      border-radius: 8px;
      max-height: 180px;
      overflow-y: auto;
      z-index: 1050;
      box-shadow: 0 10px 25px rgba(0, 0, 0, 0.8);
    }
    .search-item {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 8px 10px;
      cursor: pointer;
      border-bottom: 1px solid rgba(255, 255, 255, 0.06);
    }
    .search-item:hover {
      background: rgba(56, 189, 248, 0.15);
    }
    .search-icon {
      font-size: 1rem;
    }
    .search-info {
      flex: 1;
      min-width: 0;
      display: flex;
      flex-direction: column;
    }
    .search-title {
      font-size: 0.78rem;
      color: #f8fafc;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .search-sub {
      font-size: 0.65rem;
      color: #94a3b8;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    /* TOOLBAR */
    .waypoint-toolbar {
      display: flex;
      gap: 8px;
      margin-bottom: 12px;
    }
    .btn-add-stop {
      flex: 1;
      background: rgba(56, 189, 248, 0.15);
      border: 1px solid rgba(56, 189, 248, 0.3);
      color: #38bdf8;
      padding: 8px;
      border-radius: 8px;
      font-weight: 700;
      font-size: 0.75rem;
      cursor: pointer;
    }
    .btn-reverse {
      background: rgba(255, 255, 255, 0.08);
      border: 1px solid rgba(255, 255, 255, 0.12);
      color: #e2e8f0;
      padding: 8px 12px;
      border-radius: 8px;
      font-weight: 700;
      font-size: 0.75rem;
      cursor: pointer;
    }
    .btn-clear-all {
      background: rgba(255, 255, 255, 0.05);
      border: 1px solid rgba(255, 255, 255, 0.1);
      color: #94a3b8;
      padding: 8px 10px;
      border-radius: 8px;
      font-weight: 600;
      font-size: 0.75rem;
      cursor: pointer;
    }
    .btn-clear-all:hover {
      color: #ef4444;
      border-color: #ef4444;
    }

    /* CALCULATE ACTION */
    .calc-action-row {
      margin-bottom: 12px;
    }
    .btn-calculate {
      width: 100%;
      background: #10b981;
      color: #0f172a;
      border: none;
      padding: 12px;
      border-radius: 12px;
      font-weight: 800;
      font-size: 0.88rem;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 8px;
      cursor: pointer;
      box-shadow: 0 4px 16px rgba(16, 185, 129, 0.35);
      transition: all 0.2s ease;
    }
    .btn-calculate:disabled {
      background: rgba(255, 255, 255, 0.1);
      color: #64748b;
      box-shadow: none;
      cursor: not-allowed;
    }
    .calc-icon {
      width: 18px;
      height: 18px;
    }

    /* SUMMARY CARD */
    .route-summary-card {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .summary-grid {
      display: grid;
      grid-template-columns: repeat(2, 1fr);
      gap: 8px;
      background: rgba(0, 0, 0, 0.25);
      border-radius: 12px;
      padding: 10px;
    }
    .sum-item {
      display: flex;
      flex-direction: column;
    }
    .sum-val {
      font-size: 1.15rem;
      font-weight: 800;
      font-family: 'JetBrains Mono', monospace;
    }
    .sum-lbl {
      font-size: 0.62rem;
      color: #64748b;
      font-weight: 700;
      letter-spacing: 0.04em;
    }
    .text-primary { color: #f8fafc; }
    .text-cyan { color: #22d3ee; }
    .text-green { color: #34d399; }
    .text-amber { color: #fbbf24; }

    /* BATTERY PROJECTION */
    .battery-projection {
      background: rgba(255, 255, 255, 0.04);
      border-radius: 8px;
      padding: 8px 10px;
    }
    .proj-header {
      display: flex;
      justify-content: space-between;
      font-size: 0.75rem;
      margin-bottom: 4px;
    }
    .proj-bar-bg {
      height: 6px;
      background: rgba(0, 0, 0, 0.4);
      border-radius: 3px;
      overflow: hidden;
    }
    .proj-bar-fill {
      height: 100%;
      border-radius: 3px;
      transition: width 0.3s ease;
    }

    /* ACTION BUTTONS */
    .action-buttons {
      display: flex;
      gap: 8px;
    }
    .btn-start {
      flex: 1.4;
      background: #10b981;
      color: #0f172a;
      border: none;
      border-radius: 12px;
      padding: 12px;
      font-weight: 800;
      font-size: 0.88rem;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 8px;
      cursor: pointer;
      box-shadow: 0 4px 16px rgba(16, 185, 129, 0.35);
    }
    .btn-start svg {
      width: 18px;
      height: 18px;
      fill: #0f172a;
    }
    .btn-sim {
      flex: 1;
      background: rgba(56, 189, 248, 0.15);
      border: 1px solid rgba(56, 189, 248, 0.3);
      color: #38bdf8;
      border-radius: 12px;
      padding: 12px;
      font-weight: 700;
      font-size: 0.8rem;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 6px;
      cursor: pointer;
    }
    .btn-sim svg {
      width: 16px;
      height: 16px;
    }

    /* INSTRUCTIONS ACCORDION */
    .instructions-accordion {
      background: rgba(0, 0, 0, 0.2);
      border-radius: 10px;
      overflow: hidden;
    }
    .toggle-steps-btn {
      width: 100%;
      padding: 8px 12px;
      background: transparent;
      border: none;
      color: #94a3b8;
      font-size: 0.78rem;
      font-weight: 600;
      display: flex;
      justify-content: space-between;
      cursor: pointer;
    }
    .steps-list {
      max-height: 180px;
      overflow-y: auto;
      padding: 4px 8px 8px;
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    .step-item {
      display: flex;
      gap: 8px;
      font-size: 0.78rem;
      border-bottom: 1px solid rgba(255, 255, 255, 0.05);
      padding-bottom: 4px;
    }
    .step-idx {
      font-weight: 800;
      color: #64748b;
      width: 18px;
    }
    .step-info {
      flex: 1;
    }
    .step-text {
      color: #e2e8f0;
      font-weight: 600;
    }
    .step-meta {
      display: flex;
      gap: 8px;
      color: #64748b;
      font-size: 0.68rem;
      margin-top: 2px;
    }
    .step-meta .climb {
      color: #f59e0b;
      font-weight: 700;
    }

    .empty-state {
      text-align: center;
      padding: 20px 12px;
      color: #64748b;
      font-size: 0.85rem;
    }
  `]
})
export class RoutePlannerComponent {
  navService = inject(NavigationService);
  routerService = inject(GraphRouterService);
  networkService = inject(OfflineNetworkService);
  physicsService = inject(EBikePhysicsService);
  gpsService = inject(GpsTrackingService);
  geocodingService = inject(GeocodingService);
  storageService = inject(StorageService);

  readonly close = output<void>();
  readonly showSteps = signal<boolean>(false);

  // Search state
  searchQueries: Record<string, string> = {};
  searchResults: Record<string, SearchResultItem[]> = {};
  readonly activeSearchWpId = signal<string | null>(null);
  private searchDebounceTimers: Record<string, any> = {};

  getWaypointSearchText(wp: RouteWaypoint): string {
    if (this.searchQueries[wp.id] !== undefined) {
      return this.searchQueries[wp.id];
    }
    return wp.point ? wp.label : '';
  }

  hasSearchResults(wpId: string): boolean {
    const list = this.searchResults[wpId];
    return Array.isArray(list) && list.length > 0;
  }

  onSearchFocus(wpId: string): void {
    this.activeSearchWpId.set(wpId);
  }

  onSearchInput(wpId: string, val: string, index: number): void {
    this.searchQueries[wpId] = val;
    this.activeSearchWpId.set(wpId);

    if (this.searchDebounceTimers[wpId]) {
      clearTimeout(this.searchDebounceTimers[wpId]);
    }

    this.searchDebounceTimers[wpId] = setTimeout(async () => {
      if (val.trim().length >= 2) {
        const userPos = this.gpsService.currentPosition().point;
        const res = await this.geocodingService.searchPlaces(val, userPos.lat, userPos.lng);
        this.searchResults[wpId] = res;
      } else {
        this.searchResults[wpId] = [];
      }
    }, 300);
  }

  selectSearchResult(index: number, res: SearchResultItem): void {
    const wp = this.navService.waypoints()[index];
    if (wp) {
      this.navService.setWaypoint(index, res.point, res.name);
      this.searchQueries[wp.id] = res.name;
    }
    this.activeSearchWpId.set(null);
  }

  pickWaypointOnMap(index: number): void {
    this.navService.setPickingWaypointIndex(index);
  }

  setGpsAsWaypoint(index: number): void {
    const pos = this.gpsService.currentPosition().point;
    const wp = this.navService.waypoints()[index];
    this.navService.setWaypoint(index, pos, '📍 Current GPS Location');
    if (wp) {
      this.searchQueries[wp.id] = '📍 Current GPS Location';
    }
  }

  selectProfile(p: RoutingProfile): void {
    this.navService.setProfile(p);
    if (this.navService.activeRoute()) {
      this.calculateRouteNow();
    }
  }

  async calculateRouteNow(): Promise<void> {
    await this.navService.recomputePlan();
  }

  startNavigation(route: RouteResult): void {
    this.navService.startNavigation(route);
    this.close.emit();
  }

  startSimulation(route: RouteResult): void {
    this.navService.startNavigation(route);
    this.gpsService.startSimulator(route);
    this.close.emit();
  }

  formatDistance(meters: number): string {
    return `${(meters / 1000).toFixed(1)} km`;
  }

  formatDuration(seconds: number): string {
    const mins = Math.round(seconds / 60);
    return `${mins} min`;
  }

  getBatteryColor(pct: number): string {
    if (pct > 50) return '#10b981';
    if (pct > 20) return '#fbbf24';
    return '#ef4444';
  }
}

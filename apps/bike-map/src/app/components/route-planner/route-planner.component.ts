import { Component, inject, signal, computed, output, ViewChild, ElementRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NavigationService } from '../../core/services/navigation.service';
import { GraphRouterService } from '../../core/services/graph-router.service';
import { OfflineNetworkService } from '../../core/services/offline-network.service';
import { EBikePhysicsService } from '../../core/services/ebike-physics.service';
import { GpsTrackingService } from '../../core/services/gps-tracking.service';
import { GeocodingService, SearchResultItem } from '../../core/services/geocoding.service';
import { RouteResult, RouteWaypoint } from '../../core/models/routing.types';
import { GeoPoint } from '../../core/models/geo.types';
import { StorageService } from '../../core/services/storage.service';
import { ElevationChartComponent } from '../elevation-chart/elevation-chart.component';

@Component({
  selector: 'app-route-planner',
  standalone: true,
  imports: [CommonModule, FormsModule, ElevationChartComponent],
  template: `
    <div
      class="planner-drawer"
      [class.collapsed]="isCollapsed()"
      [class.is-picking-active]="navService.activePickingWaypointIndex() !== null"
      [class.cyclers-mode]="hasActiveRoute() && !isEditingWaypoints()"
    >
      <!-- DRAWER DRAG HANDLE BAR WITH TOUCH SWIPE LISTENERS -->
      <div
        class="drawer-handle-bar"
        (click)="toggleCollapse()"
        (touchstart)="onTouchStart($event)"
        (touchmove)="onTouchMove($event)"
        (touchend)="onTouchEnd()"
        title="Toggle Drawer"
      >
        <div class="drawer-handle-pill"></div>
      </div>

      <!-- ======================================================== -->
      <!-- MODE 1: CYCLERS APP ROUTE SELECTION GALLERY -->
      <!-- ======================================================== -->
      @if (hasActiveRoute() && !isEditingWaypoints()) {
        <!-- CYCLERS HEADER -->
        <div
          class="cyclers-header"
          (touchstart)="onTouchStart($event)"
          (touchmove)="onTouchMove($event)"
          (touchend)="onTouchEnd()"
        >
          <div class="cyclers-title-box">
            <span class="cyclers-badge">CYCLERS ROUTING</span>
            <h2>Select Route ({{ navService.availableRoutes().length }} options)</h2>
          </div>

          <div class="cyclers-header-actions">
            <!-- CAROUSEL ARROWS & DOTS NAVIGATOR -->
            <div class="carousel-nav-controls">
              <button
                class="carousel-arrow-btn"
                (click)="scrollCarousel(-1)"
                [disabled]="navService.selectedRouteIndex() === 0"
                title="Previous Route Option"
              >
                ‹
              </button>

              <div class="carousel-dots-row">
                @for (r of navService.availableRoutes(); track r.id; let dotIdx = $index) {
                  <button
                    class="carousel-dot"
                    [class.is-active]="navService.selectedRouteIndex() === dotIdx"
                    (click)="selectAndScroll(dotIdx)"
                    [title]="'Select Route ' + (dotIdx + 1)"
                  ></button>
                }
              </div>

              <button
                class="carousel-arrow-btn"
                (click)="scrollCarousel(1)"
                [disabled]="navService.selectedRouteIndex() === navService.availableRoutes().length - 1"
                title="Next Route Option"
              >
                ›
              </button>
            </div>

            <button
              class="cyclers-edit-btn"
              (click)="isEditingWaypoints.set(true)"
              title="Change destinations / Edit stops"
            >
              ✏️ Edit
            </button>
            <button
              class="drawer-icon-btn"
              (click)="toggleCollapse()"
              [title]="isCollapsed() ? 'Expand' : 'Collapse'"
            >
              {{ isCollapsed() ? '▲' : '▼' }}
            </button>
            <button class="drawer-icon-btn" (click)="close.emit()" title="Close">✕</button>
          </div>
        </div>

        <div class="drawer-body">
          <!-- CYCLERS ROUTE CARDS LIST WITH HORIZONTAL WHEEL AND REF -->
          <div
            #carouselTrack
            class="cyclers-cards-container"
            (wheel)="onCarouselWheel($event)"
          >
            @for (opt of navService.availableRoutes(); track opt.id; let optIdx = $index) {
              <div
                class="cyclers-route-card"
                [class.is-selected]="navService.selectedRouteIndex() === optIdx"
                (click)="selectAndScroll(optIdx)"
              >
                <!-- Card Top: Tag + ETA -->
                <div class="cyclers-card-top">
                  <div class="cyclers-tag-pill" [class.fast-tag]="optIdx === 0" [class.safe-tag]="optIdx === 1" [class.scenic-tag]="optIdx >= 2">
                    <span class="tag-emoji">{{ optIdx === 0 ? '⚡ FAST' : optIdx === 1 ? '🛡️ SAFE & FLAT' : '🌲 QUIET TRAILS' }}</span>
                  </div>

                  <div class="cyclers-eta-badge">
                    <span class="eta-label">ETA</span>
                    <strong class="eta-time">{{ getETA(opt.totalDurationSeconds) }}</strong>
                  </div>
                </div>

                <!-- Card Middle: Huge Ride Time & Route Name -->
                <div class="cyclers-main-info">
                  <div class="cyclers-time-row">
                    <span class="cyclers-big-time">{{ formatDuration(opt.totalDurationSeconds) }}</span>
                    <span class="cyclers-distance">({{ formatDistance(opt.totalDistanceMeters) }})</span>
                  </div>
                  <div class="cyclers-route-name">{{ opt.name }}</div>
                  @if (opt.summary) {
                    <div class="cyclers-route-summary">{{ opt.summary }}</div>
                  }
                </div>

                <!-- Cyclers Cycling Specs Grid -->
                <div class="cyclers-specs-row">
                  <!-- Battery -->
                  <div class="spec-pill text-green">
                    <span class="spec-icon">⚡</span>
                    <span>-{{ opt.totalEnergyWh }} Wh ({{ opt.batteryDrainPercent }}%)</span>
                  </div>
                  <!-- Climb -->
                  <div class="spec-pill text-amber">
                    <span class="spec-icon">▲</span>
                    <span>{{ opt.elevationGainM }}m climb · Max {{ opt.maxGradePercent }}%</span>
                  </div>
                  <!-- Bikeway / Paved -->
                  <div class="spec-pill text-cyan">
                    <span class="spec-icon">🚲</span>
                    <span>{{ optIdx === 0 ? '90% Paved Road' : optIdx === 1 ? '95% Protected Bikeway' : '75% Green Park Trail' }}</span>
                  </div>
                </div>

                <!-- Battery Projection Mini Meter -->
                <div class="cyclers-battery-meter">
                  <div class="bat-row">
                    <span>Battery on Arrival</span>
                    <strong [style.color]="getBatteryColor(opt.batteryRemainingPercent)">
                      {{ opt.batteryRemainingPercent }}% ({{ opt.estimatedBatteryRemainingWh }} Wh)
                    </strong>
                  </div>
                  <div class="bat-bar-bg">
                    <div
                      class="bat-bar-fill"
                      [style.width.%]="opt.batteryRemainingPercent"
                      [style.background]="getBatteryColor(opt.batteryRemainingPercent)"
                    ></div>
                  </div>
                </div>
              </div>
            }
          </div>

          <!-- CYCLERS PRIMARY "GO" / "START" BUTTON -->
          @if (navService.activeRoute(); as activeRoute) {
            <div class="cyclers-cta-row">
              <button class="btn-cyclers-go" (click)="startNavigation(activeRoute)">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.8" class="go-arrow-icon">
                  <polygon points="5 3 19 12 5 21 5 3"/>
                </svg>
                <div class="go-btn-content">
                  <span class="go-btn-title">START NAVIGATION</span>
                  <span class="go-btn-subtitle">{{ formatDuration(activeRoute.totalDurationSeconds) }} · {{ formatDistance(activeRoute.totalDistanceMeters) }}</span>
                </div>
              </button>

              <button class="btn-cyclers-sim" (click)="startSimulation(activeRoute)" title="Run GPS Ride Simulator">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <circle cx="12" cy="12" r="10"/>
                  <polygon points="10 8 16 12 10 16 10 8"/>
                </svg>
                <span>SIMULATOR</span>
              </button>
            </div>

            <!-- EXPANDABLE ELEVATION PROFILE & TURNS -->
            <div class="cyclers-details-accordion">
              <button class="cyclers-toggle-btn" (click)="showSteps.set(!showSteps())">
                <span>📊 Elevation Profile & Maneuvers ({{ activeRoute.instructions.length }} turns)</span>
                <span>{{ showSteps() ? '▲ Hide' : '▼ View' }}</span>
              </button>

              @if (showSteps()) {
                <div class="cyclers-expanded-body">
                  <div class="chart-wrapper">
                    <app-elevation-chart
                      [points]="activeRoute.elevationProfile"
                      [elevationGain]="activeRoute.elevationGainM"
                      [elevationLoss]="activeRoute.elevationLossM"
                      [maxGrade]="activeRoute.maxGradePercent"
                    />
                  </div>

                  <div class="steps-list">
                    @for (step of activeRoute.instructions; track step.index) {
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
                </div>
              }
            </div>
          }
        </div>
      } @else {
        <!-- ======================================================== -->
        <!-- MODE 2: UBER-STYLE ROUTE CREATION (WHERE TO?) -->
        <!-- ======================================================== -->
        <div
          class="uber-header"
          (touchstart)="onTouchStart($event)"
          (touchmove)="onTouchMove($event)"
          (touchend)="onTouchEnd()"
        >
          <div class="uber-title-box">
            <h2>Plan Your Ride</h2>
            <span class="uber-subtitle">Choose pickup & destination</span>
          </div>

          <div class="uber-header-actions">
            @if (hasActiveRoute() && isEditingWaypoints()) {
              <button
                class="uber-view-routes-btn"
                (click)="isEditingWaypoints.set(false)"
                title="Return to calculated routes"
              >
                🗺️ View Routes
              </button>
            }
            <button
              class="drawer-icon-btn"
              (click)="toggleCollapse()"
              [title]="isCollapsed() ? 'Expand' : 'Collapse'"
            >
              {{ isCollapsed() ? '▲' : '▼' }}
            </button>
            <button class="drawer-icon-btn" (click)="close.emit()" title="Close">✕</button>
          </div>
        </div>

        <!-- PICKING NOTIFICATION (IF TAPPING MAP) -->
        @if (navService.activePickingWaypointIndex() !== null) {
          <div class="uber-picking-banner">
            <span class="picking-pulse"></span>
            <span>Tap on map to set <strong>Stop {{ navService.waypoints()[navService.activePickingWaypointIndex()!].letter }}</strong></span>
            <button class="btn-done-pick" (click)="navService.setPickingWaypointIndex(null)">Done</button>
          </div>
        }

        <div class="drawer-body">
          <!-- UBER-STYLE INTERCONNECTED ROUTE INPUT CARD -->
          <div class="uber-route-card">
            <!-- Left Connecting Dots & Track -->
            <div class="uber-connector-col">
              @for (wp of navService.waypoints(); track wp.id; let idx = $index; let first = $first; let last = $last) {
                <div class="uber-dot-wrapper">
                  <div
                    class="uber-dot"
                    [class.dot-origin]="first"
                    [class.dot-dest]="last"
                    [class.dot-stop]="!first && !last"
                  ></div>
                  @if (!last) {
                    <div class="uber-dashed-line"></div>
                  }
                </div>
              }
            </div>

            <!-- Right Inputs Column -->
            <div class="uber-inputs-col">
              @for (wp of navService.waypoints(); track wp.id; let idx = $index; let first = $first; let last = $last) {
                <div class="uber-input-row" [class.is-picking]="navService.activePickingWaypointIndex() === idx">
                  <input
                    type="text"
                    class="uber-input"
                    [placeholder]="first ? 'Start: Street name & house nº...' : last ? 'Where to? (e.g. Rua Augusta, 500)...' : 'Add stop ' + wp.letter + ' (e.g. Av Paulista, 1000)...'"
                    [ngModel]="getWaypointSearchText(wp)"
                    (ngModelChange)="onSearchInput(wp.id, $event, idx)"
                    (focus)="onSearchFocus(wp.id)"
                    (keyup.enter)="onSearchEnter(idx, wp.id)"
                  />

                  <!-- Row Quick Actions -->
                  <div class="uber-input-actions">
                    <button
                      class="uber-action-chip"
                      [class.chip-active]="navService.activePickingWaypointIndex() === idx"
                      (click)="pickWaypointOnMap(idx)"
                      title="Set on Map"
                    >
                      📍 Map
                    </button>
                    @if (first) {
                      <button class="uber-action-chip chip-gps" (click)="setGpsAsWaypoint(idx)" title="Use Current GPS Location">
                        ⚡ GPS
                      </button>
                    }
                    @if (navService.waypoints().length > 2) {
                      <button class="uber-remove-btn" (click)="navService.removeWaypoint(idx)" title="Remove stop">
                        ✕
                      </button>
                    }
                  </div>

                  <!-- Dropdown autocomplete -->
                  @if (activeSearchWpId() === wp.id && hasSearchResults(wp.id)) {
                    <div class="uber-dropdown">
                      @for (res of searchResults[wp.id]; track res.id) {
                        <div class="uber-search-item" (click)="selectSearchResult(idx, res)">
                          <div class="search-item-icon">
                            {{ res.category === 'poi' ? '⚡' : res.category === 'city' ? '🏙️' : '📍' }}
                          </div>
                          <div class="search-item-info">
                            <strong class="search-item-title">{{ res.name }}</strong>
                            <span class="search-item-sub">{{ res.subText }}</span>
                          </div>
                        </div>
                      }
                    </div>
                  }
                </div>
              }
            </div>
          </div>

          <!-- UBER SHORTCUT PILLS ROW -->
          <div class="uber-shortcuts-row">
            <button class="uber-shortcut-pill" (click)="navService.addWaypoint()">
              <span>+ Add Stop</span>
            </button>
            <button class="uber-shortcut-pill" (click)="navService.reverseWaypoints()">
              <span>⇄ Swap</span>
            </button>
            <button class="uber-shortcut-pill pill-clear" (click)="clearAllWaypoints()">
              <span>✕ Clear</span>
            </button>
          </div>

          <!-- UBER PRIMARY ACTION: FIND ROUTES -->
          <div class="uber-cta-box">
            <button
              class="btn-uber-search"
              [disabled]="!navService.hasEnoughValidPoints() || navService.isCalculating()"
              (click)="calculateRouteNow()"
            >
              @if (navService.isCalculating()) {
                <span class="spinner-icon">⏳</span>
                <span>CALCULATING 3+ REAL ROUTES...</span>
              } @else {
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" class="uber-calc-icon">
                  <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>
                </svg>
                <span>SEARCH E-BIKE ROUTES</span>
              }
            </button>
          </div>
        </div>
      }
    </div>
  `,
  styles: [`
    /* ================= DRAWER BASE ================= */
    .planner-drawer {
      position: absolute;
      bottom: 0;
      left: 0;
      right: 0;
      width: 100%;
      max-height: 80dvh;
      display: flex;
      flex-direction: column;
      background: rgba(11, 17, 32, 0.97);
      backdrop-filter: blur(24px);
      -webkit-backdrop-filter: blur(24px);
      border-top: 1px solid rgba(255, 255, 255, 0.15);
      border-radius: 24px 24px 0 0;
      color: #f8fafc;
      z-index: 1000;
      box-shadow: 0 -14px 48px rgba(0, 0, 0, 0.75);
      transition: max-height 0.3s cubic-bezier(0.16, 1, 0.3, 1), transform 0.3s cubic-bezier(0.16, 1, 0.3, 1);
      animation: slideUpDrawer 0.3s cubic-bezier(0.16, 1, 0.3, 1);
      padding: 6px 14px calc(14px + env(safe-area-inset-bottom, 0px));
    }

    @media (min-width: 768px) {
      .planner-drawer {
        bottom: 24px;
        left: 24px;
        right: auto;
        width: 440px;
        max-height: calc(100dvh - 48px);
        border-radius: 20px;
        border: 1px solid rgba(255, 255, 255, 0.15);
        padding: 8px 16px 16px;
        box-shadow: 0 16px 48px rgba(0, 0, 0, 0.65);
        animation: slideInDrawer 0.3s cubic-bezier(0.16, 1, 0.3, 1);
      }
    }

    .planner-drawer.collapsed {
      max-height: 56px !important;
      overflow: hidden;
    }

    .planner-drawer.is-picking-active {
      max-height: 120px;
    }

    @keyframes slideUpDrawer {
      from { transform: translateY(100%); }
      to { transform: translateY(0); }
    }

    @keyframes slideInDrawer {
      from { transform: translateY(20px); opacity: 0; }
      to { transform: translateY(0); opacity: 1; }
    }

    /* DRAG HANDLE */
    .drawer-handle-bar {
      width: 100%;
      display: flex;
      justify-content: center;
      padding: 4px 0 6px;
      cursor: pointer;
      flex-shrink: 0;
    }
    .drawer-handle-pill {
      width: 38px;
      height: 4px;
      border-radius: 2px;
      background: rgba(255, 255, 255, 0.3);
      transition: background 0.2s ease, width 0.2s ease;
    }
    .drawer-handle-bar:hover .drawer-handle-pill {
      background: #38bdf8;
      width: 52px;
    }

    .drawer-icon-btn {
      background: rgba(255, 255, 255, 0.08);
      border: 1px solid rgba(255, 255, 255, 0.12);
      color: #94a3b8;
      width: 28px;
      height: 28px;
      border-radius: 50%;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 0.8rem;
      transition: all 0.2s ease;
    }
    .drawer-icon-btn:hover {
      background: rgba(255, 255, 255, 0.18);
      color: #fff;
    }

    .drawer-body {
      overflow-y: auto;
      flex: 1;
      display: flex;
      flex-direction: column;
      gap: 10px;
      padding-right: 2px;
    }

    .planner-drawer.cyclers-mode {
      max-height: 48dvh;
      background: rgba(11, 17, 32, 0.96);
    }

    @media (min-width: 768px) {
      .planner-drawer.cyclers-mode {
        width: 580px;
        max-width: 95vw;
        max-height: 46dvh;
      }
    }

    /* ======================================================== */
    /* CYCLERS ROUTE SELECTION STYLES (HORIZONTAL GALLERY) */
    /* ======================================================== */
    .cyclers-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding-bottom: 6px;
      border-bottom: 1px solid rgba(255, 255, 255, 0.08);
      margin-bottom: 6px;
      flex-shrink: 0;
      gap: 8px;
    }
    .cyclers-title-box {
      display: flex;
      flex-direction: column;
      gap: 1px;
      min-width: 0;
    }
    .cyclers-badge {
      font-size: 0.56rem;
      font-weight: 900;
      color: #10b981;
      letter-spacing: 0.08em;
    }
    .cyclers-title-box h2 {
      font-size: 0.88rem;
      font-weight: 700;
      color: #f8fafc;
      margin: 0;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .cyclers-header-actions {
      display: flex;
      gap: 6px;
      align-items: center;
      flex-shrink: 0;
    }

    /* CAROUSEL ARROWS AND PAGINATION DOTS */
    .carousel-nav-controls {
      display: flex;
      align-items: center;
      gap: 3px;
      background: rgba(255, 255, 255, 0.06);
      border: 1px solid rgba(255, 255, 255, 0.12);
      border-radius: 12px;
      padding: 2px 4px;
    }
    .carousel-arrow-btn {
      background: transparent;
      border: none;
      color: #38bdf8;
      font-size: 1.1rem;
      font-weight: 900;
      line-height: 1;
      width: 22px;
      height: 22px;
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      border-radius: 6px;
      transition: all 0.15s ease;
    }
    .carousel-arrow-btn:hover:not(:disabled) {
      background: rgba(56, 189, 248, 0.2);
      color: #fff;
    }
    .carousel-arrow-btn:disabled {
      opacity: 0.3;
      cursor: not-allowed;
    }
    .carousel-dots-row {
      display: flex;
      gap: 4px;
      align-items: center;
      padding: 0 2px;
    }
    .carousel-dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: rgba(255, 255, 255, 0.3);
      border: none;
      padding: 0;
      cursor: pointer;
      transition: all 0.2s ease;
    }
    .carousel-dot.is-active {
      width: 14px;
      border-radius: 3px;
      background: #10b981;
      box-shadow: 0 0 6px rgba(16, 185, 129, 0.8);
    }

    .cyclers-edit-btn {
      background: rgba(255, 255, 255, 0.08);
      border: 1px solid rgba(255, 255, 255, 0.15);
      color: #38bdf8;
      border-radius: 10px;
      padding: 3px 8px;
      font-size: 0.7rem;
      font-weight: 700;
      cursor: pointer;
      transition: all 0.2s ease;
    }
    .cyclers-edit-btn:hover {
      background: rgba(56, 189, 248, 0.2);
    }

    /* HORIZONTAL SCROLL CAROUSEL TRACK */
    .cyclers-cards-container {
      display: flex;
      flex-direction: row;
      overflow-x: auto;
      scroll-snap-type: x mandatory;
      gap: 10px;
      padding: 2px 2px 8px;
      -webkit-overflow-scrolling: touch;
      flex-shrink: 0;
      scrollbar-width: thin;
      scrollbar-color: rgba(56, 189, 248, 0.4) rgba(255, 255, 255, 0.05);
    }
    .cyclers-cards-container::-webkit-scrollbar {
      height: 4px;
      display: block;
    }
    .cyclers-cards-container::-webkit-scrollbar-track {
      background: rgba(255, 255, 255, 0.04);
      border-radius: 2px;
    }
    .cyclers-cards-container::-webkit-scrollbar-thumb {
      background: rgba(56, 189, 248, 0.35);
      border-radius: 2px;
    }
    .cyclers-cards-container::-webkit-scrollbar-thumb:hover {
      background: #38bdf8;
    }

    .cyclers-route-card {
      flex: 0 0 80%;
      min-width: 240px;
      max-width: 300px;
      scroll-snap-align: start;
      background: rgba(255, 255, 255, 0.04);
      border: 1.5px solid rgba(255, 255, 255, 0.1);
      border-radius: 14px;
      padding: 8px 12px;
      cursor: pointer;
      transition: all 0.2s ease;
      display: flex;
      flex-direction: column;
      gap: 6px;
      position: relative;
    }

    .cyclers-route-card:hover {
      background: rgba(56, 189, 248, 0.08);
      border-color: rgba(56, 189, 248, 0.4);
      transform: translateY(-1px);
    }

    .cyclers-route-card.is-selected {
      background: rgba(16, 185, 129, 0.12);
      border-color: #10b981;
      box-shadow: 0 0 16px rgba(16, 185, 129, 0.25);
    }

    .cyclers-card-top {
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .cyclers-tag-pill {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-size: 0.6rem;
      font-weight: 800;
      padding: 2px 6px;
      border-radius: 5px;
      letter-spacing: 0.04em;
    }
    .fast-tag {
      background: rgba(245, 158, 11, 0.2);
      color: #fbbf24;
      border: 1px solid rgba(245, 158, 11, 0.4);
    }
    .safe-tag {
      background: rgba(16, 185, 129, 0.2);
      color: #34d399;
      border: 1px solid rgba(16, 185, 129, 0.4);
    }
    .scenic-tag {
      background: rgba(56, 189, 248, 0.2);
      color: #38bdf8;
      border: 1px solid rgba(56, 189, 248, 0.4);
    }

    .cyclers-eta-badge {
      display: flex;
      align-items: center;
      gap: 3px;
      font-size: 0.65rem;
      color: #94a3b8;
    }
    .eta-time {
      color: #f8fafc;
      font-family: 'JetBrains Mono', monospace;
      font-weight: 800;
    }

    .cyclers-main-info {
      display: flex;
      flex-direction: column;
      gap: 1px;
    }
    .cyclers-time-row {
      display: flex;
      align-items: baseline;
      gap: 6px;
    }
    .cyclers-big-time {
      font-size: 1.15rem;
      font-weight: 900;
      color: #ffffff;
      font-family: 'Outfit', sans-serif;
    }
    .cyclers-distance {
      font-size: 0.78rem;
      font-weight: 700;
      color: #94a3b8;
    }
    .cyclers-route-name {
      font-size: 0.76rem;
      font-weight: 700;
      color: #e2e8f0;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .cyclers-route-summary {
      font-size: 0.62rem;
      color: #64748b;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .cyclers-specs-row {
      display: flex;
      flex-wrap: wrap;
      gap: 4px;
      margin-top: 1px;
    }
    .spec-pill {
      background: rgba(0, 0, 0, 0.35);
      border-radius: 5px;
      padding: 2px 5px;
      font-size: 0.62rem;
      font-weight: 600;
      display: flex;
      align-items: center;
      gap: 3px;
    }
    .text-green { color: #34d399; }
    .text-amber { color: #fbbf24; }
    .text-cyan { color: #38bdf8; }

    .cyclers-battery-meter {
      background: rgba(255, 255, 255, 0.03);
      border-radius: 5px;
      padding: 3px 6px;
    }
    .bat-row {
      display: flex;
      justify-content: space-between;
      font-size: 0.6rem;
      color: #94a3b8;
      margin-bottom: 2px;
    }
    .bat-bar-bg {
      height: 3px;
      background: rgba(0, 0, 0, 0.4);
      border-radius: 2px;
      overflow: hidden;
    }
    .bat-bar-fill {
      height: 100%;
      border-radius: 2px;
      transition: width 0.3s ease;
    }

    .cyclers-cta-row {
      display: flex;
      gap: 8px;
      margin-top: 4px;
    }
    .btn-cyclers-go {
      flex: 1.5;
      background: #10b981;
      color: #0f172a;
      border: none;
      border-radius: 14px;
      padding: 12px 14px;
      display: flex;
      align-items: center;
      gap: 10px;
      cursor: pointer;
      box-shadow: 0 6px 24px rgba(16, 185, 129, 0.4);
      transition: transform 0.2s ease, box-shadow 0.2s ease;
    }
    .btn-cyclers-go:hover {
      transform: translateY(-1px);
      box-shadow: 0 8px 28px rgba(16, 185, 129, 0.55);
    }
    .go-arrow-icon {
      width: 22px;
      height: 22px;
      fill: #0f172a;
      flex-shrink: 0;
    }
    .go-btn-content {
      display: flex;
      flex-direction: column;
      align-items: flex-start;
    }
    .go-btn-title {
      font-size: 0.88rem;
      font-weight: 900;
      letter-spacing: 0.04em;
    }
    .go-btn-subtitle {
      font-size: 0.68rem;
      font-weight: 700;
      opacity: 0.85;
    }

    .btn-cyclers-sim {
      flex: 1;
      background: rgba(56, 189, 248, 0.15);
      border: 1px solid rgba(56, 189, 248, 0.3);
      color: #38bdf8;
      border-radius: 14px;
      padding: 12px;
      font-weight: 800;
      font-size: 0.78rem;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 6px;
      cursor: pointer;
      transition: background 0.2s ease;
    }
    .btn-cyclers-sim:hover {
      background: rgba(56, 189, 248, 0.25);
    }
    .btn-cyclers-sim svg {
      width: 16px;
      height: 16px;
    }

    .cyclers-details-accordion {
      background: rgba(0, 0, 0, 0.25);
      border-radius: 10px;
      overflow: hidden;
      margin-top: 2px;
    }
    .cyclers-toggle-btn {
      width: 100%;
      padding: 8px 12px;
      background: transparent;
      border: none;
      color: #94a3b8;
      font-size: 0.74rem;
      font-weight: 700;
      display: flex;
      justify-content: space-between;
      cursor: pointer;
    }
    .cyclers-expanded-body {
      padding: 4px 10px 10px;
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .chart-wrapper {
      margin-bottom: 4px;
    }
    .steps-list {
      max-height: 140px;
      overflow-y: auto;
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    .step-item {
      display: flex;
      gap: 8px;
      font-size: 0.75rem;
      border-bottom: 1px solid rgba(255, 255, 255, 0.05);
      padding-bottom: 4px;
    }
    .step-idx {
      font-weight: 800;
      color: #64748b;
      width: 16px;
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
      font-size: 0.65rem;
      margin-top: 2px;
    }
    .step-meta .climb {
      color: #f59e0b;
      font-weight: 700;
    }

    /* ======================================================== */
    /* UBER-STYLE ROUTE CREATION STYLES */
    /* ======================================================== */
    .uber-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding-bottom: 8px;
      border-bottom: 1px solid rgba(255, 255, 255, 0.08);
      margin-bottom: 8px;
      flex-shrink: 0;
    }
    .uber-title-box h2 {
      font-size: 1.05rem;
      font-weight: 800;
      color: #ffffff;
      margin: 0;
    }
    .uber-subtitle {
      font-size: 0.68rem;
      color: #94a3b8;
    }
    .uber-header-actions {
      display: flex;
      gap: 6px;
      align-items: center;
    }
    .uber-view-routes-btn {
      background: rgba(56, 189, 248, 0.15);
      border: 1px solid rgba(56, 189, 248, 0.3);
      color: #38bdf8;
      border-radius: 12px;
      padding: 4px 10px;
      font-size: 0.72rem;
      font-weight: 700;
      cursor: pointer;
    }

    .uber-picking-banner {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
      background: rgba(56, 189, 248, 0.15);
      border: 1px solid rgba(56, 189, 248, 0.35);
      border-radius: 10px;
      padding: 6px 10px;
      margin-bottom: 8px;
      font-size: 0.8rem;
      color: #e0f2fe;
    }
    .picking-pulse {
      width: 8px;
      height: 8px;
      background: #38bdf8;
      border-radius: 50%;
      box-shadow: 0 0 8px #38bdf8;
      animation: pulse 1s infinite;
      flex-shrink: 0;
    }
    .btn-done-pick {
      background: #38bdf8;
      color: #0f172a;
      border: none;
      padding: 3px 8px;
      border-radius: 6px;
      font-size: 0.72rem;
      font-weight: 700;
      cursor: pointer;
    }

    /* UBER ROUTE CARD */
    .uber-route-card {
      background: rgba(255, 255, 255, 0.05);
      border: 1px solid rgba(255, 255, 255, 0.1);
      border-radius: 18px;
      padding: 12px 14px;
      display: flex;
      gap: 12px;
    }

    /* UBER CONNECTOR COLUMN */
    .uber-connector-col {
      display: flex;
      flex-direction: column;
      align-items: center;
      padding-top: 10px;
      width: 16px;
    }
    .uber-dot-wrapper {
      display: flex;
      flex-direction: column;
      align-items: center;
      flex: 1;
      width: 100%;
    }
    .uber-dot {
      width: 10px;
      height: 10px;
      border-radius: 50%;
      flex-shrink: 0;
    }
    .dot-origin {
      background: #10b981;
      box-shadow: 0 0 8px rgba(16, 185, 129, 0.6);
    }
    .dot-dest {
      background: #ef4444;
      border-radius: 2px;
      box-shadow: 0 0 8px rgba(239, 68, 68, 0.6);
    }
    .dot-stop {
      background: #f59e0b;
      border-radius: 2px;
    }
    .uber-dashed-line {
      width: 2px;
      flex: 1;
      min-height: 28px;
      background: repeating-linear-gradient(
        to bottom,
        rgba(255, 255, 255, 0.3) 0,
        rgba(255, 255, 255, 0.3) 4px,
        transparent 4px,
        transparent 8px
      );
      margin: 4px 0;
    }

    /* UBER INPUTS COLUMN */
    .uber-inputs-col {
      flex: 1;
      display: flex;
      flex-direction: column;
      gap: 10px;
      min-width: 0;
    }

    .uber-input-row {
      position: relative;
      display: flex;
      align-items: center;
      background: rgba(15, 23, 42, 0.9);
      border: 1px solid rgba(255, 255, 255, 0.12);
      border-radius: 12px;
      padding: 2px 6px;
      transition: border-color 0.2s ease;
    }
    .uber-input-row.is-picking {
      border-color: #38bdf8;
      background: rgba(56, 189, 248, 0.08);
    }
    .uber-input-row:focus-within {
      border-color: #10b981;
      box-shadow: 0 0 10px rgba(16, 185, 129, 0.25);
    }

    .uber-input {
      flex: 1;
      background: transparent;
      border: none;
      outline: none;
      color: #f8fafc;
      font-size: 0.85rem;
      font-weight: 600;
      padding: 8px 6px;
      min-width: 0;
    }
    .uber-input::placeholder {
      color: #64748b;
      font-weight: 500;
    }

    .uber-input-actions {
      display: flex;
      gap: 4px;
      align-items: center;
    }
    .uber-action-chip {
      background: rgba(255, 255, 255, 0.08);
      border: 1px solid rgba(255, 255, 255, 0.15);
      color: #cbd5e1;
      padding: 4px 8px;
      border-radius: 8px;
      font-size: 0.68rem;
      font-weight: 700;
      cursor: pointer;
      transition: all 0.2s ease;
      white-space: nowrap;
    }
    .uber-action-chip:hover {
      background: rgba(56, 189, 248, 0.15);
      color: #38bdf8;
    }
    .uber-action-chip.chip-active {
      background: #38bdf8;
      color: #0f172a;
    }
    .chip-gps {
      background: rgba(16, 185, 129, 0.15);
      border-color: rgba(16, 185, 129, 0.3);
      color: #34d399;
    }

    .uber-remove-btn {
      background: transparent;
      border: none;
      color: #64748b;
      font-size: 0.8rem;
      padding: 2px 4px;
      cursor: pointer;
    }
    .uber-remove-btn:hover {
      color: #ef4444;
    }

    /* UBER AUTOCOMPLETE DROPDOWN */
    .uber-dropdown {
      position: absolute;
      top: calc(100% + 6px);
      left: 0;
      right: 0;
      background: #0b1120;
      border: 1px solid rgba(255, 255, 255, 0.2);
      border-radius: 12px;
      max-height: 200px;
      overflow-y: auto;
      z-index: 200;
      box-shadow: 0 12px 36px rgba(0, 0, 0, 0.85);
    }
    .uber-search-item {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 10px 12px;
      border-bottom: 1px solid rgba(255, 255, 255, 0.05);
      cursor: pointer;
      transition: background 0.15s ease;
    }
    .uber-search-item:hover {
      background: rgba(56, 189, 248, 0.15);
    }
    .search-item-icon {
      font-size: 1.1rem;
      width: 24px;
      text-align: center;
    }
    .search-item-info {
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }
    .search-item-title {
      font-size: 0.82rem;
      color: #f8fafc;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .search-item-sub {
      font-size: 0.68rem;
      color: #94a3b8;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    /* UBER SHORTCUTS */
    .uber-shortcuts-row {
      display: flex;
      gap: 8px;
    }
    .uber-shortcut-pill {
      flex: 1;
      padding: 8px 6px;
      background: rgba(255, 255, 255, 0.06);
      border: 1px solid rgba(255, 255, 255, 0.1);
      border-radius: 10px;
      color: #cbd5e1;
      font-size: 0.74rem;
      font-weight: 700;
      cursor: pointer;
      transition: all 0.2s ease;
      text-align: center;
    }
    .uber-shortcut-pill:hover {
      background: rgba(255, 255, 255, 0.12);
      color: #fff;
    }
    .pill-clear:hover {
      background: rgba(239, 68, 68, 0.2) !important;
      color: #fca5a5 !important;
      border-color: rgba(239, 68, 68, 0.3) !important;
    }

    /* UBER CTA SEARCH BUTTON */
    .uber-cta-box {
      margin-top: 2px;
    }
    .btn-uber-search {
      width: 100%;
      background: #ffffff;
      color: #0f172a;
      border: none;
      padding: 13px;
      border-radius: 14px;
      font-weight: 900;
      font-size: 0.88rem;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 8px;
      cursor: pointer;
      box-shadow: 0 6px 24px rgba(255, 255, 255, 0.2);
      transition: transform 0.2s ease, box-shadow 0.2s ease;
      letter-spacing: 0.04em;
    }
    .btn-uber-search:hover:not(:disabled) {
      transform: translateY(-1px);
      box-shadow: 0 8px 30px rgba(255, 255, 255, 0.35);
      background: #f1f5f9;
    }
    .btn-uber-search:disabled {
      background: rgba(255, 255, 255, 0.08);
      color: #64748b;
      box-shadow: none;
      cursor: not-allowed;
    }
    .uber-calc-icon {
      width: 18px;
      height: 18px;
    }

    @keyframes pulse {
      0%, 100% { opacity: 1; }
      50% { opacity: 0.4; }
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
  readonly isCollapsed = signal<boolean>(false);
  readonly isEditingWaypoints = signal<boolean>(false);

  readonly hasActiveRoute = computed(() => this.navService.activeRoute() !== null);

  // Search state
  searchQueries: Record<string, string> = {};
  searchResults: Record<string, SearchResultItem[]> = {};
  readonly activeSearchWpId = signal<string | null>(null);
  private searchDebounceTimers: Record<string, any> = {};

  @ViewChild('carouselTrack') carouselTrackRef?: ElementRef<HTMLDivElement>;

  scrollCarousel(delta: number): void {
    const total = this.navService.availableRoutes().length;
    if (total === 0) return;
    const current = this.navService.selectedRouteIndex();
    const next = Math.max(0, Math.min(total - 1, current + delta));
    this.selectAndScroll(next);
  }

  selectAndScroll(idx: number): void {
    this.navService.selectRoute(idx);
    if (this.carouselTrackRef?.nativeElement) {
      const el = this.carouselTrackRef.nativeElement;
      const cards = el.querySelectorAll('.cyclers-route-card');
      if (cards[idx]) {
        (cards[idx] as HTMLElement).scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
      }
    }
  }

  onCarouselWheel(e: WheelEvent): void {
    if (this.carouselTrackRef?.nativeElement) {
      const el = this.carouselTrackRef.nativeElement;
      if (Math.abs(e.deltaY) > Math.abs(e.deltaX)) {
        el.scrollLeft += e.deltaY * 0.9;
        e.preventDefault();
      }
    }
  }

  private touchStartY = 0;
  private isSwiping = false;

  onTouchStart(e: TouchEvent): void {
    if (e.touches && e.touches[0]) {
      this.touchStartY = e.touches[0].clientY;
      this.isSwiping = true;
    }
  }

  onTouchMove(e: TouchEvent): void {
    if (!this.isSwiping || !e.touches || !e.touches[0]) return;
    const currentY = e.touches[0].clientY;
    const deltaY = currentY - this.touchStartY;

    // Swipe Up (deltaY < -25) -> expand drawer
    if (deltaY < -25 && this.isCollapsed()) {
      this.isCollapsed.set(false);
      this.navService.isPlannerCollapsed.set(false);
      this.isSwiping = false;
    }
    // Swipe Down (deltaY > 25) -> collapse drawer
    else if (deltaY > 25 && !this.isCollapsed()) {
      this.isCollapsed.set(true);
      this.navService.isPlannerCollapsed.set(true);
      this.isSwiping = false;
    }
  }

  onTouchEnd(): void {
    this.isSwiping = false;
  }

  toggleCollapse(): void {
    const next = !this.isCollapsed();
    this.isCollapsed.set(next);
    this.navService.isPlannerCollapsed.set(next);
  }

  getETA(durationSeconds: number): string {
    const now = new Date();
    now.setSeconds(now.getSeconds() + durationSeconds);
    const hours = String(now.getHours()).padStart(2, '0');
    const mins = String(now.getMinutes()).padStart(2, '0');
    return `${hours}:${mins}`;
  }

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

  async onSearchFocus(wpId: string): Promise<void> {
    this.activeSearchWpId.set(wpId);
    const currentText = this.searchQueries[wpId]?.trim() || '';
    if (currentText.length < 2) {
      this.searchResults[wpId] = this.geocodingService.getFrequentAndRecentPlaces();
    }
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
        this.searchResults[wpId] = this.geocodingService.getFrequentAndRecentPlaces();
      }
    }, 280);
  }

  async onSearchEnter(index: number, wpId: string): Promise<void> {
    const currentResults = this.searchResults[wpId];
    if (currentResults && currentResults.length > 0) {
      this.selectSearchResult(index, currentResults[0]);
      return;
    }

    const query = this.searchQueries[wpId]?.trim();
    if (query && query.length >= 2) {
      const userPos = this.gpsService.currentPosition().point;
      const res = await this.geocodingService.searchPlaces(query, userPos.lat, userPos.lng);
      if (res && res.length > 0) {
        this.selectSearchResult(index, res[0]);
      }
    }
  }

  selectSearchResult(index: number, res: SearchResultItem): void {
    const wp = this.navService.waypoints()[index];
    if (wp) {
      this.navService.setWaypoint(index, res.point, res.name);
      this.searchQueries[wp.id] = res.name;
      this.storageService.recordPlaceUsage(res.name, res.subText, res.point);
    }
    this.activeSearchWpId.set(null);
  }

  pickWaypointOnMap(index: number): void {
    this.navService.setPickingWaypointIndex(index);
  }

  async setGpsAsWaypoint(index: number): Promise<void> {
    const posState = await this.gpsService.getCurrentLocationOrRequest();
    const pos = posState.point;
    const geo = await this.geocodingService.reverseGeocode(pos.lat, pos.lng);
    const locationName = geo.name || '📍 Current GPS Location';

    const wp = this.navService.waypoints()[index];
    this.navService.setWaypoint(index, pos, locationName);
    if (wp) {
      this.searchQueries[wp.id] = locationName;
    }
    this.storageService.recordPlaceUsage(locationName, geo.street || 'GPS Location', pos);
  }

  clearAllWaypoints(): void {
    this.navService.clearRoute();
    this.isEditingWaypoints.set(false);
    this.searchQueries = {};
    this.searchResults = {};
  }

  async calculateRouteNow(): Promise<void> {
    await this.navService.recomputePlan();
    this.isEditingWaypoints.set(false);
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

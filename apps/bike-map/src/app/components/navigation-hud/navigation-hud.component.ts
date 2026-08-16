import { Component, inject, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { NavigationService } from '../../core/services/navigation.service';
import { EBikePhysicsService, ASSIST_CONFIGS } from '../../core/services/ebike-physics.service';
import { AudioGuidanceService } from '../../core/services/audio-guidance.service';
import { GpsTrackingService } from '../../core/services/gps-tracking.service';
import { AssistLevel } from '../../core/models/ebike.types';

@Component({
  selector: 'app-navigation-hud',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="hud-overlay" [class.paused]="navService.isPaused()">
      <!-- TOP MANEUVER BANNER -->
      <div class="top-banner" [class.off-route]="navService.isOffRoute()">
        @if (navService.isOffRoute()) {
          <div class="off-route-badge">
            <svg class="alert-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
              <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>
              <line x1="12" y1="9" x2="12" y2="13"/>
              <line x1="12" y1="17" x2="12.01" y2="17"/>
            </svg>
            <span>OFF ROUTE — Auto Recalculating...</span>
          </div>
        } @else if (navService.currentInstruction(); as instr) {
          <div class="maneuver-content">
            <div class="maneuver-icon-container" [attr.data-maneuver]="instr.maneuver">
              @switch (instr.maneuver) {
                @case ('turn-left') {
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
                    <path d="M9 18l-6-6 6-6"/>
                    <path d="M3 12h11a4 4 0 0 1 4 4v4"/>
                  </svg>
                }
                @case ('turn-right') {
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
                    <path d="M15 18l6-6-6-6"/>
                    <path d="M21 12H10a4 4 0 0 0-4 4v4"/>
                  </svg>
                }
                @case ('slight-left') {
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
                    <path d="M7 14l-4-4 4-4"/>
                    <path d="M3 10h9a5 5 0 0 1 5 5v5"/>
                  </svg>
                }
                @case ('slight-right') {
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
                    <path d="M17 14l4-4-4-4"/>
                    <path d="M21 10h-9a5 5 0 0 0-5 5v5"/>
                  </svg>
                }
                @case ('sharp-left') {
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
                    <path d="M6 19l-4-4 4-4"/>
                    <path d="M2 15h12a4 4 0 0 0 4-4V5"/>
                  </svg>
                }
                @case ('sharp-right') {
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
                    <path d="M18 19l4-4-4-4"/>
                    <path d="M22 15H10a4 4 0 0 1-4-4V5"/>
                  </svg>
                }
                @case ('climb-ahead') {
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                    <path d="M3 20h18L14 4 9 13l-3-4z"/>
                    <path d="M12 11v6m0-6l2 2m-2-2l-2 2" stroke-width="2"/>
                  </svg>
                }
                @case ('arrive') {
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                    <path d="M12 22s-8-4.5-8-11.8A8 8 0 0 1 12 2a8 8 0 0 1 8 8.2c0 7.3-8 11.8-8 11.8z"/>
                    <circle cx="12" cy="10" r="3"/>
                  </svg>
                }
                @default {
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
                    <line x1="12" y1="19" x2="12" y2="5"/>
                    <polyline points="5 12 12 5 19 12"/>
                  </svg>
                }
              }
            </div>

            <div class="maneuver-text-box">
              <div class="distance-countdown">
                {{ formatDistance(navService.distanceToNextManeuverMeters()) }}
              </div>
              <div class="instruction-street">{{ instr.streetName }}</div>
              <div class="instruction-detail">{{ instr.text }}</div>
            </div>

            <div class="hud-audio-btn" (click)="audioService.toggleMute()">
              @if (audioService.isMuted()) {
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <line x1="1" y1="1" x2="23" y2="23"/>
                  <path d="M9 9v3a3 3 0 0 0 5.12 2.12M15 9.34V4a3 3 0 0 0-5.94-.6"/>
                  <path d="M17 16.95A7 7 0 0 1 5 12v-2m14 0v2a7 7 0 0 1-.11 1.23"/>
                </svg>
              } @else {
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/>
                  <path d="M19.07 4.93a10 10 0 0 1 0 14.14M15.54 8.46a5 5 0 0 1 0 7.07"/>
                </svg>
              }
            </div>
          </div>
        }
      </div>

      <!-- SIMULATOR CONTROL CHIP -->
      @if (gpsService.isSimulatorRunning()) {
        <div class="simulator-chip">
          <span class="sim-dot"></span>
          <span class="sim-label">GPS SIMULATOR ACTIVE</span>
          <div class="sim-speeds">
            <button [class.active]="gpsService.simulationSpeedMultiplier() === 1" (click)="gpsService.setSimulatorSpeed(1)">1x</button>
            <button [class.active]="gpsService.simulationSpeedMultiplier() === 2" (click)="gpsService.setSimulatorSpeed(2)">2x</button>
            <button [class.active]="gpsService.simulationSpeedMultiplier() === 4" (click)="gpsService.setSimulatorSpeed(4)">4x</button>
          </div>
        </div>
      }

      <!-- BOTTOM CONTROL & TELEMETRY CLUSTER -->
      <div class="bottom-cluster">
        <!-- Live Numbers Grid -->
        <div class="live-metrics-grid">
          <!-- Speedometer -->
          <div class="metric-block speed-block">
            <div class="metric-value">{{ navService.telemetry().currentSpeedKmh }}</div>
            <div class="metric-unit">KM/H SPEED</div>
          </div>

          <!-- Distance Remaining -->
          <div class="metric-block">
            <div class="metric-value accent-cyan">{{ navService.telemetry().distanceRemainingKm }}</div>
            <div class="metric-unit">KM REMAINING</div>
          </div>

          <!-- Battery Remaining -->
          <div class="metric-block battery-block">
            <div class="metric-value" [style.color]="getBatteryColor(physicsService.batteryTelemetry().percentage)">
              {{ physicsService.batteryTelemetry().percentage }}%
            </div>
            <div class="metric-unit">
              {{ physicsService.batteryTelemetry().currentWh }} Wh / {{ physicsService.batteryTelemetry().estimatedRangeKm }} km
            </div>
          </div>

          <!-- Time ETA -->
          <div class="metric-block">
            <div class="metric-value accent-amber">{{ formatTime(navService.telemetry().timeRemainingSeconds) }}</div>
            <div class="metric-unit">EST. TIME</div>
          </div>
        </div>

        <!-- Assist Mode Quick Switcher -->
        <div class="assist-switcher">
          @for (mode of assistModes; track mode) {
            <button
              class="assist-btn"
              [class.active]="physicsService.config().activeAssist === mode"
              [attr.data-mode]="mode"
              (click)="setAssist(mode)"
            >
              {{ mode }}
            </button>
          }
        </div>

        <!-- Action Buttons -->
        <div class="hud-action-row">
          <button class="btn-hud pause-btn" (click)="navService.pauseNavigation()">
            {{ navService.isPaused() ? 'RESUME' : 'PAUSE' }}
          </button>
          <button class="btn-hud stop-btn" (click)="stopNav()">
            END RIDE
          </button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .hud-overlay {
      position: absolute;
      top: 0;
      left: 0;
      right: 0;
      bottom: 0;
      pointer-events: none;
      display: flex;
      flex-direction: column;
      justify-content: space-between;
      z-index: 1000;
      padding: calc(14px + env(safe-area-inset-top, 0px)) 12px calc(14px + env(safe-area-inset-bottom, 0px));
    }
    .hud-overlay > * {
      pointer-events: auto;
    }

    /* TOP BANNER */
    .top-banner {
      background: rgba(15, 23, 42, 0.94);
      backdrop-filter: blur(16px);
      border: 1px solid rgba(255, 255, 255, 0.15);
      border-radius: 16px;
      padding: 14px 16px;
      box-shadow: 0 12px 36px rgba(0, 0, 0, 0.6);
      transition: background 0.3s ease;
    }
    .top-banner.off-route {
      background: rgba(220, 38, 38, 0.95);
      border-color: #f87171;
    }
    .off-route-badge {
      display: flex;
      align-items: center;
      gap: 10px;
      color: #fff;
      font-weight: 700;
      font-size: 0.95rem;
    }
    .alert-icon {
      width: 22px;
      height: 22px;
      stroke: #fff;
      animation: pulse 1s infinite;
    }

    .maneuver-content {
      display: flex;
      align-items: center;
      gap: 14px;
    }
    .maneuver-icon-container {
      width: 52px;
      height: 52px;
      border-radius: 14px;
      background: #10b981;
      display: flex;
      align-items: center;
      justify-content: center;
      color: #0f172a;
      flex-shrink: 0;
    }
    .maneuver-icon-container[data-maneuver="climb-ahead"] {
      background: #f59e0b;
    }
    .maneuver-icon-container svg {
      width: 32px;
      height: 32px;
    }
    .maneuver-text-box {
      flex: 1;
      min-width: 0;
    }
    .distance-countdown {
      font-size: 1.6rem;
      font-weight: 800;
      color: #38bdf8;
      font-family: 'JetBrains Mono', monospace;
      line-height: 1.1;
    }
    .instruction-street {
      font-size: 1rem;
      font-weight: 700;
      color: #f8fafc;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .instruction-detail {
      font-size: 0.78rem;
      color: #94a3b8;
    }
    .hud-audio-btn {
      width: 40px;
      height: 40px;
      border-radius: 50%;
      background: rgba(255, 255, 255, 0.1);
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      color: #e2e8f0;
    }
    .hud-audio-btn svg {
      width: 20px;
      height: 20px;
    }

    /* SIMULATOR CHIP */
    .simulator-chip {
      align-self: center;
      background: rgba(30, 41, 59, 0.9);
      backdrop-filter: blur(8px);
      border: 1px solid rgba(56, 189, 248, 0.4);
      padding: 6px 12px;
      border-radius: 20px;
      display: flex;
      align-items: center;
      gap: 8px;
      color: #38bdf8;
      font-size: 0.72rem;
      font-weight: 700;
      letter-spacing: 0.05em;
    }
    .sim-dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: #38bdf8;
      animation: pulse 1.2s infinite;
    }
    .sim-speeds {
      display: flex;
      gap: 4px;
      margin-left: 6px;
    }
    .sim-speeds button {
      background: rgba(255, 255, 255, 0.1);
      border: none;
      color: #94a3b8;
      padding: 2px 6px;
      border-radius: 4px;
      font-size: 0.68rem;
      cursor: pointer;
    }
    .sim-speeds button.active {
      background: #38bdf8;
      color: #0f172a;
      font-weight: 700;
    }

    /* BOTTOM CLUSTER */
    .bottom-cluster {
      background: rgba(15, 23, 42, 0.94);
      backdrop-filter: blur(16px);
      border: 1px solid rgba(255, 255, 255, 0.15);
      border-radius: 20px;
      padding: 14px 16px;
      display: flex;
      flex-direction: column;
      gap: 12px;
      box-shadow: 0 16px 40px rgba(0, 0, 0, 0.6);
    }
    .live-metrics-grid {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      gap: 8px;
      text-align: center;
    }
    .metric-block {
      background: rgba(255, 255, 255, 0.04);
      border-radius: 10px;
      padding: 8px 4px;
    }
    .metric-value {
      font-size: 1.35rem;
      font-weight: 800;
      font-family: 'JetBrains Mono', monospace;
      color: #f8fafc;
      line-height: 1.1;
    }
    .metric-unit {
      font-size: 0.62rem;
      color: #64748b;
      font-weight: 700;
      margin-top: 2px;
      letter-spacing: 0.04em;
    }
    .accent-cyan { color: #22d3ee; }
    .accent-amber { color: #fbbf24; }

    /* ASSIST SWITCHER */
    .assist-switcher {
      display: flex;
      gap: 6px;
      background: rgba(0, 0, 0, 0.3);
      padding: 4px;
      border-radius: 10px;
    }
    .assist-btn {
      flex: 1;
      padding: 8px 0;
      border: none;
      background: transparent;
      color: #94a3b8;
      font-size: 0.75rem;
      font-weight: 700;
      border-radius: 6px;
      cursor: pointer;
      transition: all 0.2s ease;
    }
    .assist-btn.active {
      background: #10b981;
      color: #0f172a;
      box-shadow: 0 2px 8px rgba(16, 185, 129, 0.4);
    }
    .assist-btn.active[data-mode="SPORT"] {
      background: #f59e0b;
      box-shadow: 0 2px 8px rgba(245, 158, 11, 0.4);
    }
    .assist-btn.active[data-mode="TURBO"] {
      background: #ef4444;
      color: #fff;
      box-shadow: 0 2px 8px rgba(239, 68, 68, 0.4);
    }
    .assist-btn.active[data-mode="OFF"] {
      background: #475569;
      color: #fff;
    }

    /* ACTION ROW */
    .hud-action-row {
      display: flex;
      gap: 10px;
    }
    .btn-hud {
      flex: 1;
      padding: 10px 0;
      border-radius: 10px;
      font-size: 0.85rem;
      font-weight: 700;
      border: none;
      cursor: pointer;
      letter-spacing: 0.05em;
    }
    .pause-btn {
      background: rgba(255, 255, 255, 0.1);
      color: #e2e8f0;
    }
    .stop-btn {
      background: rgba(239, 68, 68, 0.2);
      color: #f87171;
      border: 1px solid rgba(239, 68, 68, 0.3);
    }

    @keyframes pulse {
      0%, 100% { opacity: 1; }
      50% { opacity: 0.4; }
    }
  `]
})
export class NavigationHudComponent {
  navService = inject(NavigationService);
  physicsService = inject(EBikePhysicsService);
  audioService = inject(AudioGuidanceService);
  gpsService = inject(GpsTrackingService);

  readonly closeRequested = output<void>();

  readonly assistModes: AssistLevel[] = ['OFF', 'ECO', 'TOUR', 'SPORT', 'TURBO'];

  setAssist(level: AssistLevel): void {
    this.physicsService.setAssist(level);
  }

  stopNav(): void {
    this.navService.stopNavigation();
    this.closeRequested.emit();
  }

  formatDistance(meters: number): string {
    if (meters < 20) return 'NOW';
    if (meters < 1000) return `${Math.round(meters)} m`;
    return `${(meters / 1000).toFixed(1)} km`;
  }

  formatTime(seconds: number): string {
    const mins = Math.round(seconds / 60);
    if (mins < 60) return `${mins} min`;
    const hrs = Math.floor(mins / 60);
    const remMins = mins % 60;
    return `${hrs}h ${remMins}m`;
  }

  getBatteryColor(pct: number): string {
    if (pct > 50) return '#10b981';
    if (pct > 20) return '#fbbf24';
    return '#ef4444';
  }
}

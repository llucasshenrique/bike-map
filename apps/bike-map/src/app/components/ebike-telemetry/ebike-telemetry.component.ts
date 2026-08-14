import { Component, inject, output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NavigationService } from '../../core/services/navigation.service';
import { EBikePhysicsService, ASSIST_CONFIGS } from '../../core/services/ebike-physics.service';
import { GpsTrackingService } from '../../core/services/gps-tracking.service';
import { AssistLevel } from '../../core/models/ebike.types';

@Component({
  selector: 'app-ebike-telemetry',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="cockpit-backdrop">
      <div class="cockpit-modal">
        <!-- HEADER -->
        <div class="cockpit-header">
          <div class="brand">
            <span class="ebike-icon">⚡</span>
            <div>
              <h3>E-BIKE TELEMETRY HUD</h3>
              <span class="sub">CAN-BUS / ON-DEVICE SENSORS</span>
            </div>
          </div>
          <button class="close-btn" (click)="close.emit()">✕</button>
        </div>

        <!-- MAIN SPEED GAUGE -->
        <div class="speedo-section">
          <div class="speed-ring">
            <div class="speed-number">{{ navService.telemetry().currentSpeedKmh }}</div>
            <div class="speed-unit">KM/H</div>
          </div>

          <div class="power-split-card">
            <div class="split-labels">
              <span class="motor-label">⚡ Motor: {{ navService.telemetry().motorPowerWatts }} W</span>
              <span class="rider-label">🦵 Legs: {{ navService.telemetry().riderPowerWatts }} W</span>
            </div>
            <div class="split-bar">
              <div class="motor-bar" [style.width.%]="getMotorPowerPercent()"></div>
              <div class="rider-bar" [style.width.%]="getRiderPowerPercent()"></div>
            </div>
          </div>
        </div>

        <!-- ASSIST MODE ROW -->
        <div class="assist-row">
          <span class="assist-title">ASSIST LEVEL:</span>
          <div class="assist-buttons">
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
        </div>

        <!-- BATTERY TELEMETRY SECTION -->
        <div class="battery-section">
          <div class="bat-header">
            <span class="bat-title">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="bat-icon">
                <rect x="1" y="6" width="18" height="12" rx="2"/>
                <line x1="23" y1="11" x2="23" y2="13"/>
              </svg>
              BATTERY STATUS
            </span>
            <span class="bat-pct" [style.color]="getBatteryColor(physicsService.batteryTelemetry().percentage)">
              {{ physicsService.batteryTelemetry().percentage }}%
            </span>
          </div>

          <div class="bat-bar-track">
            <div
              class="bat-bar-fill"
              [style.width.%]="physicsService.batteryTelemetry().percentage"
              [style.background]="getBatteryColor(physicsService.batteryTelemetry().percentage)"
            ></div>
          </div>

          <div class="bat-stats-grid">
            <div class="stat-col">
              <span class="val">{{ physicsService.batteryTelemetry().currentWh }} Wh</span>
              <span class="lbl">REMAINING ENERGY</span>
            </div>
            <div class="stat-col">
              <span class="val text-cyan">{{ physicsService.batteryTelemetry().estimatedRangeKm }} km</span>
              <span class="lbl">EST. RANGE IN {{ physicsService.config().activeAssist }}</span>
            </div>
            <div class="stat-col">
              <span class="val">{{ physicsService.batteryTelemetry().voltageApprox }} V</span>
              <span class="lbl">VOLTAGE</span>
            </div>
          </div>
        </div>

        <!-- TRIP METRICS 2x2 -->
        <div class="trip-grid">
          <div class="trip-cell">
            <span class="trip-val">{{ navService.telemetry().distanceRiddenKm }} km</span>
            <span class="trip-lbl">DISTANCE RIDDEN</span>
          </div>
          <div class="trip-cell">
            <span class="trip-val">{{ formatSeconds(navService.telemetry().timeElapsedSeconds) }}</span>
            <span class="trip-lbl">ELAPSED TIME</span>
          </div>
          <div class="trip-cell">
            <span class="trip-val">{{ navService.telemetry().avgSpeedKmh }} km/h</span>
            <span class="trip-lbl">AVERAGE SPEED</span>
          </div>
          <div class="trip-cell">
            <span class="trip-val accent-amber">▲ {{ navService.telemetry().elevationGainedM }} m</span>
            <span class="trip-lbl">ELEVATION GAIN</span>
          </div>
        </div>

        <!-- QUICK BATTERY ADJUSTER (SIMULATION & CUSTOMIZATION) -->
        <div class="tuning-section">
          <span class="tune-title">Adjust Current Battery Simulation:</span>
          <div class="battery-slider-row">
            <input
              type="range"
              min="10"
              [max]="physicsService.config().batteryCapacityWh"
              step="10"
              [ngModel]="physicsService.config().currentBatteryWh"
              (ngModelChange)="onBatterySliderChange($event)"
            />
            <span class="slider-val">{{ physicsService.config().currentBatteryWh }} Wh</span>
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .cockpit-backdrop {
      position: fixed;
      top: 0;
      left: 0;
      right: 0;
      bottom: 0;
      background: rgba(0, 0, 0, 0.75);
      backdrop-filter: blur(8px);
      z-index: 2000;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 16px;
    }
    .cockpit-modal {
      width: 480px;
      max-width: 100%;
      max-height: 92vh;
      overflow-y: auto;
      background: #0f172a;
      border: 1px solid rgba(255, 255, 255, 0.15);
      border-radius: 24px;
      padding: 20px;
      color: #f8fafc;
      box-shadow: 0 24px 64px rgba(0, 0, 0, 0.8);
      display: flex;
      flex-direction: column;
      gap: 16px;
    }
    .cockpit-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      border-bottom: 1px solid rgba(255, 255, 255, 0.1);
      padding-bottom: 10px;
    }
    .brand {
      display: flex;
      align-items: center;
      gap: 10px;
    }
    .ebike-icon {
      font-size: 1.6rem;
    }
    h3 {
      font-size: 1.05rem;
      font-weight: 800;
      margin: 0;
      letter-spacing: 0.05em;
    }
    .sub {
      font-size: 0.65rem;
      color: #64748b;
      font-weight: 700;
    }
    .close-btn {
      background: rgba(255, 255, 255, 0.1);
      border: none;
      color: #94a3b8;
      width: 32px;
      height: 32px;
      border-radius: 50%;
      cursor: pointer;
      font-size: 1rem;
    }

    /* SPEEDO */
    .speedo-section {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 12px;
    }
    .speed-ring {
      width: 140px;
      height: 140px;
      border-radius: 50%;
      border: 4px solid #10b981;
      box-shadow: 0 0 24px rgba(16, 185, 129, 0.3);
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      background: rgba(16, 185, 129, 0.05);
    }
    .speed-number {
      font-size: 3.4rem;
      font-weight: 900;
      font-family: 'JetBrains Mono', monospace;
      line-height: 1;
      color: #f8fafc;
    }
    .speed-unit {
      font-size: 0.75rem;
      font-weight: 800;
      color: #64748b;
      letter-spacing: 0.1em;
      margin-top: 4px;
    }

    /* POWER SPLIT */
    .power-split-card {
      width: 100%;
      background: rgba(255, 255, 255, 0.04);
      border-radius: 12px;
      padding: 10px 14px;
    }
    .split-labels {
      display: flex;
      justify-content: space-between;
      font-size: 0.78rem;
      font-weight: 700;
      margin-bottom: 6px;
    }
    .motor-label { color: #38bdf8; }
    .rider-label { color: #f59e0b; }
    .split-bar {
      height: 8px;
      background: rgba(0, 0, 0, 0.4);
      border-radius: 4px;
      display: flex;
      overflow: hidden;
    }
    .motor-bar {
      background: #38bdf8;
      transition: width 0.3s ease;
    }
    .rider-bar {
      background: #f59e0b;
      transition: width 0.3s ease;
    }

    /* ASSIST ROW */
    .assist-row {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    .assist-title {
      font-size: 0.72rem;
      font-weight: 800;
      color: #64748b;
      letter-spacing: 0.05em;
    }
    .assist-buttons {
      display: flex;
      gap: 6px;
      background: rgba(0, 0, 0, 0.3);
      padding: 4px;
      border-radius: 12px;
    }
    .assist-btn {
      flex: 1;
      padding: 10px 0;
      background: transparent;
      border: none;
      color: #94a3b8;
      font-weight: 800;
      font-size: 0.8rem;
      border-radius: 8px;
      cursor: pointer;
      transition: all 0.2s ease;
    }
    .assist-btn.active {
      background: #10b981;
      color: #0f172a;
      box-shadow: 0 4px 12px rgba(16, 185, 129, 0.4);
    }
    .assist-btn.active[data-mode="SPORT"] {
      background: #f59e0b;
      box-shadow: 0 4px 12px rgba(245, 158, 11, 0.4);
    }
    .assist-btn.active[data-mode="TURBO"] {
      background: #ef4444;
      color: #fff;
      box-shadow: 0 4px 12px rgba(239, 68, 68, 0.4);
    }
    .assist-btn.active[data-mode="OFF"] {
      background: #475569;
      color: #fff;
    }

    /* BATTERY SECTION */
    .battery-section {
      background: rgba(255, 255, 255, 0.04);
      border: 1px solid rgba(255, 255, 255, 0.08);
      border-radius: 14px;
      padding: 12px 14px;
    }
    .bat-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 8px;
    }
    .bat-title {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 0.8rem;
      font-weight: 700;
      color: #94a3b8;
    }
    .bat-icon {
      width: 18px;
      height: 18px;
    }
    .bat-pct {
      font-size: 1.4rem;
      font-weight: 900;
      font-family: 'JetBrains Mono', monospace;
    }
    .bat-bar-track {
      height: 8px;
      background: rgba(0, 0, 0, 0.5);
      border-radius: 4px;
      overflow: hidden;
      margin-bottom: 12px;
    }
    .bat-bar-fill {
      height: 100%;
      border-radius: 4px;
      transition: width 0.3s ease;
    }
    .bat-stats-grid {
      display: grid;
      grid-template-columns: repeat(3, 1fr);
      gap: 8px;
      text-align: center;
    }
    .stat-col {
      display: flex;
      flex-direction: column;
    }
    .stat-col .val {
      font-size: 0.95rem;
      font-weight: 800;
      font-family: 'JetBrains Mono', monospace;
    }
    .stat-col .lbl {
      font-size: 0.6rem;
      color: #64748b;
      font-weight: 700;
      margin-top: 2px;
    }
    .text-cyan { color: #22d3ee; }

    /* TRIP GRID */
    .trip-grid {
      display: grid;
      grid-template-columns: repeat(2, 1fr);
      gap: 8px;
    }
    .trip-cell {
      background: rgba(255, 255, 255, 0.04);
      border-radius: 12px;
      padding: 10px 12px;
      display: flex;
      flex-direction: column;
    }
    .trip-val {
      font-size: 1.25rem;
      font-weight: 800;
      font-family: 'JetBrains Mono', monospace;
    }
    .trip-lbl {
      font-size: 0.62rem;
      color: #64748b;
      font-weight: 700;
      margin-top: 2px;
    }
    .accent-amber { color: #fbbf24; }

    /* TUNING */
    .tuning-section {
      background: rgba(0, 0, 0, 0.3);
      border-radius: 12px;
      padding: 10px 14px;
    }
    .tune-title {
      font-size: 0.72rem;
      color: #94a3b8;
      font-weight: 600;
      display: block;
      margin-bottom: 6px;
    }
    .battery-slider-row {
      display: flex;
      align-items: center;
      gap: 12px;
    }
    .battery-slider-row input[type="range"] {
      flex: 1;
      accent-color: #10b981;
    }
    .slider-val {
      font-size: 0.85rem;
      font-weight: 700;
      font-family: 'JetBrains Mono', monospace;
      color: #34d399;
      width: 70px;
      text-align: right;
    }
  `]
})
export class EBikeTelemetryComponent {
  navService = inject(NavigationService);
  physicsService = inject(EBikePhysicsService);
  gpsService = inject(GpsTrackingService);

  readonly close = output<void>();
  readonly assistModes: AssistLevel[] = ['OFF', 'ECO', 'TOUR', 'SPORT', 'TURBO'];

  setAssist(mode: AssistLevel): void {
    this.physicsService.setAssist(mode);
  }

  onBatterySliderChange(wh: number): void {
    this.physicsService.updateBatteryWh(wh);
  }

  getMotorPowerPercent(): number {
    const total = this.navService.telemetry().motorPowerWatts + this.navService.telemetry().riderPowerWatts;
    if (total === 0) return 50;
    return (this.navService.telemetry().motorPowerWatts / total) * 100;
  }

  getRiderPowerPercent(): number {
    const total = this.navService.telemetry().motorPowerWatts + this.navService.telemetry().riderPowerWatts;
    if (total === 0) return 50;
    return (this.navService.telemetry().riderPowerWatts / total) * 100;
  }

  getBatteryColor(pct: number): string {
    if (pct > 50) return '#10b981';
    if (pct > 20) return '#fbbf24';
    return '#ef4444';
  }

  formatSeconds(sec: number): string {
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return `${m}m ${s < 10 ? '0' : ''}${s}s`;
  }
}

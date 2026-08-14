import { Component, input, output, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ElevationPoint } from '../../core/models/geo.types';

@Component({
  selector: 'app-elevation-chart',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="elevation-card">
      <div class="chart-header">
        <div class="chart-title">
          <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M3 20h18L14 4 9 13l-3-4z"/>
          </svg>
          <span>Elevation Profile & Grade</span>
        </div>
        <div class="chart-stats" *ngIf="stats()">
          <span class="stat-badge gain">▲ {{ stats().gain }}m</span>
          <span class="stat-badge loss">▼ {{ stats().loss }}m</span>
          <span class="stat-badge max-grade">Max: {{ stats().maxGrade }}%</span>
        </div>
      </div>

      <div class="svg-container" *ngIf="points().length > 1">
        <svg viewBox="0 0 400 110" preserveAspectRatio="none" class="elevation-svg">
          <defs>
            <linearGradient id="gradeGradient" x1="0%" y1="0%" x2="0%" y2="100%">
              <stop offset="0%" stop-color="#10b981" stop-opacity="0.6"/>
              <stop offset="100%" stop-color="#10b981" stop-opacity="0.05"/>
            </linearGradient>
          </defs>

          <!-- Grid lines -->
          <line x1="0" y1="25" x2="400" y2="25" stroke="#334155" stroke-dasharray="3,3" stroke-width="0.8"/>
          <line x1="0" y1="65" x2="400" y2="65" stroke="#334155" stroke-dasharray="3,3" stroke-width="0.8"/>

          <!-- Area fill -->
          <path [attr.d]="svgAreaPath()" fill="url(#gradeGradient)" />

          <!-- Line outline -->
          <path [attr.d]="svgLinePath()" fill="none" stroke="#10b981" stroke-width="2.5" stroke-linejoin="round" />

          <!-- Steep climb warning overlays -->
          @for (climb of steepSegments(); track climb.x) {
            <circle [attr.cx]="climb.x" [attr.cy]="climb.y" r="4" fill="#ef4444" />
          }
        </svg>
      </div>

      <div class="axis-labels" *ngIf="points().length > 1">
        <span>0 km ({{ minEle() }}m)</span>
        <span>{{ totalDistanceKm() }} km ({{ maxEle() }}m peak)</span>
      </div>
    </div>
  `,
  styles: [`
    .elevation-card {
      background: rgba(15, 23, 42, 0.85);
      backdrop-filter: blur(12px);
      border: 1px solid rgba(255, 255, 255, 0.1);
      border-radius: 12px;
      padding: 12px 14px;
      color: #f8fafc;
      box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4);
    }
    .chart-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 8px;
    }
    .chart-title {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 0.85rem;
      font-weight: 600;
      color: #94a3b8;
    }
    .icon {
      width: 16px;
      height: 16px;
      stroke: #10b981;
    }
    .chart-stats {
      display: flex;
      gap: 6px;
    }
    .stat-badge {
      font-size: 0.72rem;
      padding: 2px 6px;
      border-radius: 4px;
      font-weight: 600;
      font-family: monospace;
    }
    .stat-badge.gain {
      background: rgba(16, 185, 129, 0.2);
      color: #34d399;
    }
    .stat-badge.loss {
      background: rgba(6, 182, 212, 0.2);
      color: #22d3ee;
    }
    .stat-badge.max-grade {
      background: rgba(245, 158, 11, 0.2);
      color: #fbbf24;
    }
    .svg-container {
      width: 100%;
      height: 70px;
    }
    .elevation-svg {
      width: 100%;
      height: 100%;
      display: block;
    }
    .axis-labels {
      display: flex;
      justify-content: space-between;
      font-size: 0.7rem;
      color: #64748b;
      margin-top: 4px;
      font-family: monospace;
    }
  `]
})
export class ElevationChartComponent {
  readonly points = input<ElevationPoint[]>([]);
  readonly elevationGain = input<number>(0);
  readonly elevationLoss = input<number>(0);
  readonly maxGrade = input<number>(0);

  readonly pointSelected = output<ElevationPoint>();

  readonly minEle = computed(() => {
    const pts = this.points();
    if (pts.length === 0) return 0;
    return Math.min(...pts.map(p => p.elevationM));
  });

  readonly maxEle = computed(() => {
    const pts = this.points();
    if (pts.length === 0) return 100;
    return Math.max(...pts.map(p => p.elevationM));
  });

  readonly totalDistanceKm = computed(() => {
    const pts = this.points();
    if (pts.length === 0) return 0;
    return pts[pts.length - 1].distanceKm;
  });

  readonly stats = computed(() => {
    return {
      gain: this.elevationGain(),
      loss: this.elevationLoss(),
      maxGrade: this.maxGrade()
    };
  });

  readonly svgLinePath = computed(() => {
    const pts = this.points();
    if (pts.length < 2) return '';
    const min = this.minEle();
    const max = Math.max(min + 10, this.maxEle());
    const totalDist = this.totalDistanceKm() || 1;

    return pts.map((p, i) => {
      const x = (p.distanceKm / totalDist) * 396 + 2;
      const y = 95 - ((p.elevationM - min) / (max - min)) * 80;
      return `${i === 0 ? 'M' : 'L'} ${x.toFixed(1)} ${y.toFixed(1)}`;
    }).join(' ');
  });

  readonly svgAreaPath = computed(() => {
    const line = this.svgLinePath();
    if (!line) return '';
    return `${line} L 398 105 L 2 105 Z`;
  });

  readonly steepSegments = computed(() => {
    const pts = this.points();
    const min = this.minEle();
    const max = Math.max(min + 10, this.maxEle());
    const totalDist = this.totalDistanceKm() || 1;

    return pts
      .filter(p => p.gradePercent >= 7)
      .map(p => ({
        x: (p.distanceKm / totalDist) * 396 + 2,
        y: 95 - ((p.elevationM - min) / (max - min)) * 80,
        grade: p.gradePercent
      }));
  });
}

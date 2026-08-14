import { Component, inject, output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { OfflineNetworkService, PrebuiltNetworkOption } from '../../core/services/offline-network.service';
import { EBikePhysicsService } from '../../core/services/ebike-physics.service';

@Component({
  selector: 'app-network-selector',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="modal-backdrop">
      <div class="modal-panel">
        <div class="modal-header">
          <div>
            <h3>OFFLINE BIKE NETWORKS & SETTINGS</h3>
            <span class="sub">Self-Contained Local Routing Graphs</span>
          </div>
          <button class="close-btn" (click)="close.emit()">✕</button>
        </div>

        <!-- TABS -->
        <div class="tab-row">
          <button [class.active]="activeTab() === 'networks'" (click)="activeTab.set('networks')">
            🚴 Networks ({{ networkService.availableNetworks.length }})
          </button>
          <button [class.active]="activeTab() === 'custom'" (click)="activeTab.set('custom')">
            📁 Import GPX/GeoJSON
          </button>
          <button [class.active]="activeTab() === 'specs'" (click)="activeTab.set('specs')">
            ⚙️ Bike Specs
          </button>
        </div>

        <!-- NETWORKS TAB -->
        @if (activeTab() === 'networks') {
          <div class="networks-list">
            @for (net of networkService.availableNetworks; track net.id) {
              <div
                class="network-card"
                [class.selected]="networkService.activeNetworkId() === net.id"
                (click)="selectNetwork(net.id)"
              >
                <div class="card-top">
                  <span class="card-name">{{ net.name }}</span>
                  @if (networkService.activeNetworkId() === net.id) {
                    <span class="active-badge">ACTIVE</span>
                  }
                </div>
                <span class="card-region">📍 {{ net.region }}</span>
                <p class="card-desc">{{ net.description }}</p>
              </div>
            }
          </div>
        }

        <!-- CUSTOM IMPORT TAB -->
        @if (activeTab() === 'custom') {
          <div class="custom-import-box">
            <div class="drop-zone" (click)="fileInput.click()">
              <input #fileInput type="file" accept=".geojson,.json,.gpx" (change)="onFileSelected($event)" style="display: none;" />
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="upload-icon">
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
                <polyline points="17 8 12 3 7 8"/>
                <line x1="12" y1="3" x2="12" y2="15"/>
              </svg>
              <strong>Upload GeoJSON / GPX Trail Network</strong>
              <span>Parses road topology & generates offline routing graph directly on device</span>
            </div>

            @if (importStatus()) {
              <div class="import-status">{{ importStatus() }}</div>
            }
          </div>
        }

        <!-- BIKE SPECS TAB -->
        @if (activeTab() === 'specs') {
          <div class="specs-form">
            <div class="form-row">
              <label>Battery Capacity (Wh):</label>
              <input type="number" [(ngModel)]="batteryCapacity" (change)="saveSpecs()" min="200" max="1500" step="25" />
            </div>

            <div class="form-row">
              <label>Rider Weight (kg):</label>
              <input type="number" [(ngModel)]="riderWeight" (change)="saveSpecs()" min="40" max="160" />
            </div>

            <div class="form-row">
              <label>Bike + Gear Weight (kg):</label>
              <input type="number" [(ngModel)]="bikeWeight" (change)="saveSpecs()" min="10" max="45" />
            </div>

            <div class="form-row">
              <label>Motor Max Power (Watts):</label>
              <input type="number" [(ngModel)]="motorMaxWatt" (change)="saveSpecs()" min="250" max="1000" step="50" />
            </div>
          </div>
        }
      </div>
    </div>
  `,
  styles: [`
    .modal-backdrop {
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
    .modal-panel {
      width: 480px;
      max-width: 100%;
      max-height: 90vh;
      overflow-y: auto;
      background: #0f172a;
      border: 1px solid rgba(255, 255, 255, 0.15);
      border-radius: 20px;
      padding: 20px;
      color: #f8fafc;
      box-shadow: 0 24px 64px rgba(0, 0, 0, 0.8);
      display: flex;
      flex-direction: column;
      gap: 16px;
    }
    .modal-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      border-bottom: 1px solid rgba(255, 255, 255, 0.1);
      padding-bottom: 10px;
    }
    h3 {
      font-size: 1rem;
      font-weight: 800;
      margin: 0;
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
      width: 30px;
      height: 30px;
      border-radius: 50%;
      cursor: pointer;
      font-size: 0.95rem;
    }

    .tab-row {
      display: flex;
      gap: 6px;
      background: rgba(0, 0, 0, 0.3);
      padding: 4px;
      border-radius: 12px;
    }
    .tab-row button {
      flex: 1;
      padding: 8px 4px;
      background: transparent;
      border: none;
      color: #94a3b8;
      font-weight: 700;
      font-size: 0.75rem;
      border-radius: 8px;
      cursor: pointer;
    }
    .tab-row button.active {
      background: rgba(255, 255, 255, 0.12);
      color: #38bdf8;
    }

    .networks-list {
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    .network-card {
      background: rgba(255, 255, 255, 0.04);
      border: 1px solid rgba(255, 255, 255, 0.08);
      border-radius: 14px;
      padding: 12px 14px;
      cursor: pointer;
      transition: all 0.2s ease;
    }
    .network-card:hover {
      border-color: rgba(56, 189, 248, 0.4);
    }
    .network-card.selected {
      border-color: #10b981;
      background: rgba(16, 185, 129, 0.08);
    }
    .card-top {
      display: flex;
      justify-content: space-between;
      align-items: center;
    }
    .card-name {
      font-weight: 800;
      font-size: 0.9rem;
      color: #fff;
    }
    .active-badge {
      background: #10b981;
      color: #0f172a;
      font-size: 0.62rem;
      font-weight: 800;
      padding: 2px 6px;
      border-radius: 4px;
    }
    .card-region {
      font-size: 0.72rem;
      color: #38bdf8;
      display: block;
      margin: 2px 0 6px;
    }
    .card-desc {
      font-size: 0.75rem;
      color: #94a3b8;
      margin: 0;
    }

    .drop-zone {
      border: 2px dashed rgba(255, 255, 255, 0.2);
      border-radius: 14px;
      padding: 30px 16px;
      text-align: center;
      cursor: pointer;
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 8px;
    }
    .upload-icon {
      width: 36px;
      height: 36px;
      stroke: #38bdf8;
    }
    .drop-zone strong {
      font-size: 0.85rem;
      color: #f8fafc;
    }
    .drop-zone span {
      font-size: 0.72rem;
      color: #64748b;
    }
    .import-status {
      margin-top: 10px;
      padding: 8px 12px;
      background: rgba(16, 185, 129, 0.15);
      border-radius: 8px;
      font-size: 0.78rem;
      color: #34d399;
    }

    .specs-form {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .form-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
    }
    .form-row label {
      font-size: 0.8rem;
      color: #94a3b8;
      font-weight: 600;
    }
    .form-row input {
      width: 90px;
      background: rgba(0, 0, 0, 0.4);
      border: 1px solid rgba(255, 255, 255, 0.15);
      border-radius: 6px;
      padding: 6px 8px;
      color: #f8fafc;
      font-weight: 700;
      font-family: 'JetBrains Mono', monospace;
      text-align: right;
    }
  `]
})
export class NetworkSelectorComponent {
  networkService = inject(OfflineNetworkService);
  physicsService = inject(EBikePhysicsService);

  readonly close = output<void>();
  readonly networkChanged = output<string>();

  readonly activeTab = signal<'networks' | 'custom' | 'specs'>('networks');
  readonly importStatus = signal<string>('');

  batteryCapacity = this.physicsService.config().batteryCapacityWh;
  riderWeight = this.physicsService.config().riderWeightKg;
  bikeWeight = this.physicsService.config().bikeWeightKg;
  motorMaxWatt = this.physicsService.config().motorMaxWatt;

  selectNetwork(id: string): void {
    this.networkService.activeNetworkId.set(id);
    this.networkChanged.emit(id);
  }

  saveSpecs(): void {
    this.physicsService.updateConfig({
      batteryCapacityWh: this.batteryCapacity,
      riderWeightKg: this.riderWeight,
      bikeWeightKg: this.bikeWeight,
      motorMaxWatt: this.motorMaxWatt
    });
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (!input.files || input.files.length === 0) return;

    const file = input.files[0];
    const reader = new FileReader();
    reader.onload = () => {
      try {
        const content = reader.result as string;
        const graph = this.networkService.importCustomNetworkFromGeoJSON(content, file.name.replace(/\.[^/.]+$/, ''));
        this.importStatus.set(`Successfully parsed ${graph.nodes.size} trail nodes! Offline graph ready.`);
      } catch (err) {
        this.importStatus.set('Failed to parse file. Ensure valid GeoJSON format.');
      }
    };
    reader.readAsText(file);
  }
}

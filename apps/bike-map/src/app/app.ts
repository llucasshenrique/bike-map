import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MapViewComponent } from './components/map-view/map-view.component';
import { NavigationHudComponent } from './components/navigation-hud/navigation-hud.component';
import { RoutePlannerComponent } from './components/route-planner/route-planner.component';
import { EBikeTelemetryComponent } from './components/ebike-telemetry/ebike-telemetry.component';
import { NetworkSelectorComponent } from './components/network-selector/network-selector.component';
import { NavigationService } from './core/services/navigation.service';
import { EBikePhysicsService } from './core/services/ebike-physics.service';
import { GpsTrackingService } from './core/services/gps-tracking.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule,
    MapViewComponent,
    NavigationHudComponent,
    RoutePlannerComponent,
    EBikeTelemetryComponent,
    NetworkSelectorComponent
  ],
  templateUrl: './app.html',
  styleUrl: './app.scss'
})
export class App {
  navService = inject(NavigationService);
  physicsService = inject(EBikePhysicsService);
  gpsService = inject(GpsTrackingService);

  readonly showPlanner = signal<boolean>(true);
  readonly showTelemetry = signal<boolean>(false);
  readonly showNetworkSelector = signal<boolean>(false);

  togglePlanner(): void {
    this.showPlanner.set(!this.showPlanner());
  }

  openTelemetryModal(): void {
    this.showTelemetry.set(true);
  }

  closeTelemetryModal(): void {
    this.showTelemetry.set(false);
  }

  openNetworkModal(): void {
    this.showNetworkSelector.set(true);
  }

  closeNetworkModal(): void {
    this.showNetworkSelector.set(false);
  }
}

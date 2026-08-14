# 🚴⚡ E-Bike Offline Navigation Router

A high-performance, self-contained offline e-bike navigation and routing system built with **Nx Monorepo**, **Angular (Standalone)**, and **Capacitor for Android**.

Runs **100% offline with zero external API dependencies**. All graph pathfinding, battery consumption physics modeling, turn-by-turn instruction generation, offline speech synthesis, and GPS simulation execute locally on-device.

---

## 🌟 Key Features

- 🔋 **Physics-Based E-Bike Energy Engine**:
  - Calculates mechanical resistance ($F_{rolling} + F_{gravity} + F_{aero}$) and motor electrical drain in Watt-Hours (Wh) per segment.
  - Multi-assist mode support: `OFF (0%)`, `ECO (40%)`, `TOUR (100%)`, `SPORT (180%)`, and `TURBO (300%)`.
  - Dynamic battery reachability isocline / range circle on map based on current Wh and assist mode.
- ⚡ **Multi-Criteria Offline A\* Router**:
  - `Eco-Efficient`: Avoids steep climbs and preserves battery.
  - `Turbo Fast`: High-speed direct routes utilizing motor assist on climbs.
  - `Scenic Trails`: Prioritizes river paths, parks, gravel trails, and nature corridors.
  - `Safe Bikeways`: Maximizes dedicated protected cycle tracks and quiet residential streets.
- 🗣️ **Turn-by-Turn GPS Navigation & HUD**:
  - Live maneuver icons, distance countdowns, and turn notifications.
  - Offline Voice Audio Guidance using on-device Web Speech Synthesis.
  - Auto-rerouting in < 50ms when off-route.
  - Built-in GPS Ride Simulator (`1x`, `2x`, `4x` speed) for testing routes anywhere.
- 📊 **Bike Computer Cockpit & Telemetry**:
  - Digital cockpit with speedometer, motor vs human power split, battery voltage/Wh, trip metrics, and hill climb grades.
- 🗺️ **Offline Networks & GPX/GeoJSON Trail Importer**:
  - Bundled high-density bike networks (Emerald Valley, Alpine Crest, Coastal Bayfront).
  - On-device file importer to load custom trail networks and compile routing graphs offline.
- 📱 **Native Android Integration**:
  - Built with `@capacitor/core`, `@capacitor/android`, `@capacitor/geolocation`, and `@capacitor/haptics`.

---

## 🚀 Quick Start (Fish & Bash Compatible)

### 1. Run Development Server
```fish
pnpm start
# or: nx serve bike-map
```
Open [http://localhost:4200](http://localhost:4200) in your browser.

### 2. Build Web Assets
```fish
pnpm run build
# or: nx build bike-map
```

### 3. Sync & Build Android App
```fish
# Sync compiled web bundle to Android native project
pnpm run cap:sync

# Open Android Studio project
pnpm run cap:open

# Run directly on connected Android device / emulator
pnpm run cap:run
```

---

## 📁 Project Architecture

```
bike-map/
├── android/                         # Capacitor Native Android Project
│   └── app/src/main/
│       ├── AndroidManifest.xml      # GPS & Native Permissions
│       └── assets/public/           # Bundled Offline Web Assets
├── apps/bike-map/
│   └── src/
│       ├── app/
│       │   ├── components/
│       │   │   ├── map-view/        # Leaflet Offline Map View
│       │   │   ├── navigation-hud/  # Turn-by-Turn HUD & Speedometer
│       │   │   ├── route-planner/   # Profile Selector & Summary
│       │   │   ├── ebike-telemetry/ # Cockpit Dashboard Modal
│       │   │   ├── elevation-chart/ # SVG Elevation & Grade Scrubber
│       │   │   └── network-selector/# Offline Network & Specs Manager
│       │   └── core/
│       │       ├── models/          # Geo, Routing, EBike, POI types
│       │       └── services/
│       │           ├── graph-router.service.ts   # Offline A* Pathfinding
│       │           ├── ebike-physics.service.ts  # Wh Energy Modeling
│       │           ├── offline-network.service.ts# Bike Network Graphs
│       │           ├── navigation.service.ts     # Navigation State Machine
│       │           ├── gps-tracking.service.ts   # Geolocation & Simulator
│       │           └── audio-guidance.service.ts # Voice Synthesizer
│       ├── styles.scss              # Global High-Contrast Styling
│       └── index.html               # Mobile Viewport & Theme Configuration
├── capacitor.config.ts              # Capacitor App Configuration
├── package.json                     # Scripts & Dependencies (pnpm)
└── nx.json                          # Nx Workspace Configuration
```

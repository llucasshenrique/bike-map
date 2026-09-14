# 🚴⚡ E-Bike Map & Navigation Router (Pure Kotlin)

A 100% native Android application built entirely with **Kotlin**, **Jetpack Compose (Material 3)**, **Osmdroid (OpenStreetMap)**, and **Gradle Kotlin DSL (`.kts`)**.

Engineered specifically for electric bicycles (e-bikes) conforming to Brazilian CONTRAN Resolution 996/2023 (motor assist up to 32 km/h), real-world physical resistance modeling, multi-stop routing, worldwide address search, elevation slope coloring, turn-by-turn voice guidance, and an on-device digital bike computer.

---

## 🌟 Key Features

- 📱 **100% Pure Kotlin & Jetpack Compose**:
  - Entire application, UI, services, models, and build configuration written exclusively in Kotlin (`.kt` and `.gradle.kts`).
  - Declarative reactive UI built with Android Jetpack Compose Material 3.
- 🗺️ **High-Performance Native OpenStreetMap Engine (Osmdroid)**:
  - Hardware-accelerated offline tile caching, zero paid API keys or tracking.
  - Multi-stop waypoints ($A \rightarrow B \rightarrow C \dots \rightarrow N$) with draggable pin markers.
  - Segment-by-segment **elevation grade coloring**:
    - 🔵 **Cyan (`< 0%`)**: Downhill sections (inward momentum & regenerative braking).
    - 🟢 **Emerald (`0% – 3%`)**: Flat & gentle slopes.
    - 🟡 **Amber (`3% – 7%`)**: Moderate climbs.
    - 🔴 **Red (`> 7%`)**: Steep ascents requiring high motor assistance.
  - Interactive alternative routes rendered as dashed clickable candidate paths.
- 🔋 **E-Bike Physical Resistance Engine (`EBikePhysicsEngine`)**:
  - Computes mechanical forces ($F_{\text{rolling}} + F_{\text{gravity}} + F_{\text{aero}}$) and motor electrical drain ($\text{Wh}$).
  - Conforms to **Resolução CONTRAN nº 996/2023** (legal motor cut-off at **32 km/h**).
  - 5 Assist modes: `OFF (0%)`, `ECO (40%)`, `TOUR (100%)`, `SPORT (180%)`, and `TURBO (300%)`.
  - Calculates dynamic battery remaining %, remaining range in km, and battery voltage.
- 🔍 **Worldwide Address & POI Search (`GeocodingService`)**:
  - Global address search via OpenStreetMap Photon & Nominatim APIs with fast typeahead.
  - "Usar Minha Localização Atual (GPS)" shortcut.
- 🗣️ **Turn-by-Turn Voice Navigation & HUD**:
  - Floating HUD with next maneuver icons, distance countdown, live speedometer, and ETA.
  - Native voice audio guidance using Android `TextToSpeech` in Portuguese (`pt-BR`).
  - Built-in GPS Ride Simulator (`1x`, `2x`, `4x` speed) for testing routes without cycling outside.
- 📊 **Digital Bike Computer Cockpit (`TelemetryCockpitDialog`)**:
  - Real-time speedometer, average speed, max speed, motor power (W) vs rider leg power (W) split, battery health, and assist selector.

---

## 🚀 Building and Running (Fish & Bash Compatible)

### Prerequisites
- JDK 17 or 21
- Android SDK (Platform 35, Build-Tools 34+)

### 1. Build Debug APK
```fish
./gradlew assembleDebug
```
The compiled APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### 2. Install directly to a connected Android device or emulator
```fish
./gradlew installDebug
```

### 3. Clean Build
```fish
./gradlew clean
```

---

## 🏛️ Project Architecture

```
app/src/main/kotlin/com/ebike/router/
├── MainActivity.kt                      # Main Activity hosting Jetpack Compose & Permissions
├── model/
│   ├── GeoPoint.kt                      # Geographic coordinates & Haversine distance calculations
│   ├── RoutingModels.kt                 # Multi-stop waypoints, route results, profiles, maneuvers
│   ├── EBikeModels.kt                   # Assist levels, battery telemetry, and cockpit state
│   └── GeocodingModels.kt               # Search and geocoding data structures
├── physics/
│   └── EBikePhysicsEngine.kt            # On-device Frr + Fgrade + Faero physics & CONTRAN 32 km/h limits
├── service/
│   ├── GraphRouterService.kt            # Global OSRM Bike router, multi-stop, alternatives, offline fallback
│   ├── GeocodingService.kt              # Photon (OSM) & Nominatim search with fast typeahead
│   ├── AudioGuidanceService.kt          # Android TextToSpeech for turn maneuvers
│   └── LocationTrackerService.kt        # FusedLocation / GPS tracking & simulator
└── ui/
    ├── theme/                           # E-Bike high-contrast color scheme & Material3 Dark Theme
    ├── viewmodel/
    │   └── BikeMapViewModel.kt          # Unified ViewModel managing waypoints, routes, navigation, telemetry
    └── components/
        ├── OsmdroidMapView.kt           # OpenStreetMap view with multi-stop pins & slope-colored lines
        ├── RoutePlannerSheet.kt         # Waypoint cards, search, reordering, and alternative route comparison
        ├── NavigationHud.kt             # Next maneuver banner, distance countdown, speedometer, ETA
        ├── ElevationProfileChart.kt     # Canvas-based elevation curve with grade colors & scrubber
        ├── TelemetryCockpitDialog.kt    # Full cockpit bike computer (watts, voltage, stats)
        └── WaypointSearchDialog.kt      # Fast place/address search dialog
```

---

## 📄 License
MIT License

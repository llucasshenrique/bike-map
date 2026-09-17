# Implementation Plan: Explicit Opt-In E-Bike Mode & Bike Specs Configuration

## 1. Executive Summary & Problem Analysis

### 1.1 Context & Core Philosophy
The application (`com.ebike.router`) is a 100% native Android app built with Kotlin, Jetpack Compose, and Osmdroid. While it includes mathematical bicycle physics modeling (`EBikePhysicsEngine.kt`), **the app has no hardware or Bluetooth Low Energy (BLE) connection to any real bicycle or motor controller**. All battery consumption, remaining range, motor power, and rider leg effort figures are purely simulated estimates calculated on-device.

Furthermore, the product vision is **normal-bike-first**. A regular cyclist should be able to open the app, plan routes, and navigate without encountering battery percentages, motor assist selectors, or e-bike jargon. Cyclists who do ride an e-bike must be able to **explicitly opt in**, specify their bicycle's actual physical parameters, and see assist-aware range and ETA predictions—**transparently and unequivocally labeled as "estimated, not measured"**.

### 1.2 Current Architectural Deficiencies
1. **Zero Persistence Layer**: There is currently no `DataStore`, `SharedPreferences`, or database. All state resets to hardcoded constants on app restart (`EBikeConfig(batteryCapacityWh = 625.0, bikeWeightKg = 24.0, ...)`).
2. **Missing Settings / Preferences UI**: There is no settings screen, menu, or dialog to configure bike type or ride parameters.
3. **Pervasive E-Bike Bias in UI**:
   - `MainActivity.kt`: Top floating bar displays an e-bike icon, battery %, and assist mode button by default.
   - `RoutePlannerSheet.kt`: Displays "Planejador E-Bike", "CALCULAR ROTA E-BIKE", and "-XX Wh" battery drain unconditionally.
   - `NavigationHud.kt`: Displays assist level ("Assistência: TOUR") and battery % during navigation.
   - `TelemetryCockpitDialog.kt`: Features motor power (W), rider power (W), battery % and voltage, and assist level selectors.
   - `AudioGuidanceService.kt` / `GraphRouterService.kt`: Prompt says: *"Subida íngreme à frente! Aumente o nível de assistência"*.
4. **Fabricated Telemetry Presented as Fact**:
   - In `BikeMapViewModel.kt` (lines 331–332):
     ```kotlin
     motorPowerWatts = (speed * 10).coerceAtMost(physicsEngine.getConfig().motorMaxWatt).toInt(),
     riderPowerWatts = (speed * 6).coerceAtLeast(40.0).toInt(),
     ```
     These power values are computed directly from GPS speed with arbitrary scalar multipliers, yet presented in the Cockpit and HUD as if they were live telemetry measurements from motor shunts and strain-gauge power meters.

---

## 2. Target Architecture & Component Matrix

```
                      +------------------------------------------+
                      |   Jetpack DataStore Preferences          |
                      |   (UserPreferencesRepository)            |
                      +--------------------+---------------------+
                                           |
                               Flow<BikePreferences>
                                           v
                      +------------------------------------------+
                      |          BikeMapViewModel                |
                      |   - isEBikeMode: StateFlow<Boolean>      |
                      |   - bikeConfig: StateFlow<EBikeConfig>   |
                      |   - telemetry: StateFlow<LiveRide... >   |
                      +--------------------+---------------------+
                                           |
                +--------------------------+--------------------------+
                |                                                     |
                v                                                     v
   [ E-Bike Mode = FALSE (Default) ]                    [ E-Bike Mode = TRUE (Opt-In) ]
   - Standard cycling ETA (no motor assist)             - Assist-aware ETA & route energy
   - Hide battery % and Wh drain                        - User-entered specs (Wh, max km/h, kg)
   - Hide motor power cards & assist selectors          - Labeled "Estimated, not measured"
   - Pure bike computer cockpit (speed, dist, elev)     - Simulated motor & rider power estimates
   - "Planejador de Rotas" / "CALCULAR ROTA"            - "Planejador E-Bike" / Battery telemetry
```

### Component Impact Overview

| Component | Current State | Normal Bike Mode (Default) | E-Bike Mode (Opt-In) |
| :--- | :--- | :--- | :--- |
| **`app/build.gradle.kts`** | No persistence dependency | Add `androidx.datastore:datastore-preferences` | Same |
| **`model/EBikeModels.kt`** | Static e-bike config & telemetry models | Models distinguish e-bike telemetry vs normal bike telemetry | Add labels/flags indicating simulated estimates |
| **`physics/EBikePhysicsEngine.kt`** | Always applies motor assist ratios & 32 km/h assist caps | Disabled/bypassed for ETA calculations; standard human pedaling model | Uses user's real specs (`batteryCapacityWh`, `maxSpeedKmh`, `weight`) |
| **`service/GraphRouterService.kt`** | Hardcodes e-bike cruising speeds & Wh drain | Calculates ETA using human cyclist speeds (~18–22 km/h); zero Wh drain | Calculates assist-aware cruising speeds; computes Wh drain from user specs |
| **`service/AudioGuidanceService.kt`** | Mentions increasing motor assist on hills | Climb prompt: *"Subida íngreme à frente! Reduza as marchas"* | Climb prompt: *"Subida íngreme à frente! Ajuste o nível de assistência"* |
| **`ui/viewmodel/BikeMapViewModel.kt`** | Fabricates `motorPowerWatts = speed * 10` | Motor power = 0 / hidden; battery telemetry null; standard ETA | Physics-based power estimate, explicitly tagged as simulated estimate |
| **`ui/components/SettingsDialog.kt`** | *Does not exist* | **NEW**: Toggle switch (Default: OFF), unit preferences | Expandable form: Battery Wh, Max Assist km/h, Bike kg, Rider kg |
| **`ui/components/TelemetryCockpitDialog.kt`** | Presents simulated motor & battery as live | Pure digital bike computer (Speed, Dist, Elev, Time); hides motor & battery | Shows motor/battery cards with prominent **"ESTIMATED, NOT MEASURED"** labels |
| **`NavigationHud.kt`** | Shows assist mode & battery % | Hides assist level & battery; shows trip stats (elev gain, distance) | Shows assist level & battery with explicit `(est.)` tag and disclaimer |
| **`MainActivity.kt`** | Top bar has battery pill; no settings button | Settings gear button in top bar; hides battery pill | Shows battery pill with `EST.` badge; opens Settings via gear button |
| **`RoutePlannerSheet.kt`** | "Planejador E-Bike" & Wh drain badges | "Planejador de Rotas", hides Wh drain, shows standard cycling duration | "Planejador E-Bike", shows estimated Wh drain & battery % impact |

---

## 3. Detailed Implementation Phases

### Phase 1: Persistence Layer & Domain Models

#### 1.1 Dependency Configuration
Add Jetpack DataStore Preferences to `app/build.gradle.kts`:
```kotlin
implementation("androidx.datastore:datastore-preferences:1.1.2")
```

#### 1.2 Data Store Repository: `BikePreferencesRepository.kt`
Create `com.ebike.router.data.BikePreferencesRepository` encapsulating:
- Preference Keys:
  - `KEY_IS_EBIKE_MODE` (`Boolean`, default: `false`)
  - `KEY_BATTERY_CAPACITY_WH` (`Double`, default: `500.0`)
  - `KEY_CURRENT_BATTERY_WH` (`Double`, default: `500.0`)
  - `KEY_MAX_ASSIST_SPEED_KMH` (`Double`, default: `32.0`)
  - `KEY_BIKE_WEIGHT_KG` (`Double`, default: `24.0`)
  - `KEY_RIDER_WEIGHT_KG` (`Double`, default: `75.0`)
  - `KEY_REGENERATIVE_BRAKING` (`Boolean`, default: `false`)
- Data class `UserBikePreferences`:
  ```kotlin
  data class UserBikePreferences(
      val isEBikeMode: Boolean = false,
      val batteryCapacityWh: Double = 500.0,
      val currentBatteryWh: Double = 500.0,
      val maxAssistSpeedKmh: Double = 32.0,
      val bikeWeightKg: Double = 24.0,
      val riderWeightKg: Double = 75.0,
      val regenerativeBraking: Boolean = false
  )
  ```
- Reactive Flow: `val bikePreferencesFlow: Flow<UserBikePreferences>`
- Mutations: `suspend fun updateEBikeMode(enabled: Boolean)`, `suspend fun updateBikeSpecs(...)`, `suspend fun updateCurrentBatteryWh(...)`.

#### 1.3 Update Models: `model/EBikeModels.kt`
- Introduce a clear telemetry source discriminator:
  ```kotlin
  enum class TelemetryOrigin {
      SIMULATED_ESTIMATE,
      GPS_MEASURED
  }
  ```
- Add metadata to `LiveRideTelemetry`:
  - `val isEBikeMode: Boolean = false`
  - `val isPowerEstimated: Boolean = true` (never presented as measured sensor data)
  - `val batteryTelemetry: BatteryTelemetry? = null` (nullable; `null` when normal bike mode is active)

---

### Phase 2: Physics Engine & Routing Services Adaptation

#### 2.1 Update `physics/EBikePhysicsEngine.kt`
- Support syncing configuration directly from `UserBikePreferences`.
- Ensure segment calculations distinguish between unassisted cycling and assisted cycling:
  - When assist level is `AssistLevel.OFF` (or e-bike mode is disabled):
    - Motor power = 0 W.
    - Segment speed calculation models human sustained aerobic output (e.g. 150 W) on slopes, naturally slowing on hills ($v = P_{\text{rider}} / F_{\text{total}}$), rather than maintaining artificial cruising speeds.
    - Energy consumed = 0.0 Wh.

#### 2.2 Update `service/GraphRouterService.kt`
- Update `calculateMultipleRoutes` to receive `isEBikeMode: Boolean` (or `UserBikePreferences`):
  - **In Normal Bike Mode (`isEBikeMode == false`)**:
    - Cruising speeds: 18.0 km/h (Safe), 22.0 km/h (Flat/Efficient), with slope-based speed degradation ($10\text{--}14\text{ km/h}$ on climbs $>5\%$).
    - `totalEnergyWh = 0.0`, `batteryDrainPercent = 0.0`, `estimatedBatteryRemainingWh = 0`.
    - Turn instructions for climbs: do not instruct user to adjust assist.
  - **In E-Bike Mode (`isEBikeMode == true`)**:
    - Cruising speeds: capped at user's `maxAssistSpeedKmh` (e.g. 25 km/h or 32 km/h).
    - Energy calculation: computes energy based on user's entered total mass (`bikeWeightKg + riderWeightKg`) and battery capacity (`batteryCapacityWh`).

#### 2.3 Update `service/AudioGuidanceService.kt`
- Parameterize or branch climb announcements based on e-bike mode:
  - E-bike mode on: *"Subida íngreme à frente! Ajuste o nível de assistência se necessário."*
  - E-bike mode off: *"Subida íngreme à frente! Reduza a marcha."*

---

### Phase 3: ViewModel Refactoring (`ui/viewmodel/BikeMapViewModel.kt`)

#### 3.1 Preferences Binding
- Inject/instantiate `BikePreferencesRepository(application)`.
- Expose state flows:
  - `val bikePreferences: StateFlow<UserBikePreferences>`
  - `val isEBikeMode: StateFlow<Boolean>`
- Update `physicsEngine` dynamically when preferences change:
  ```kotlin
  viewModelScope.launch {
      bikePreferencesRepo.bikePreferencesFlow.collect { prefs ->
          physicsEngine.updateConfig(
              EBikeConfig(
                  batteryCapacityWh = prefs.batteryCapacityWh,
                  currentBatteryWh = prefs.currentBatteryWh,
                  bikeWeightKg = prefs.bikeWeightKg,
                  riderWeightKg = prefs.riderWeightKg,
                  motorMaxWatt = 350.0,
                  activeAssist = if (prefs.isEBikeMode) physicsEngine.getConfig().activeAssist else AssistLevel.OFF
              )
          )
      }
  }
  ```

#### 3.2 Refactor Telemetry Calculation (`startRideTimer`)
- **Eliminate unlabeled fabricated power figures**:
  - Replace `motorPowerWatts = (speed * 10)` and `riderPowerWatts = (speed * 6)`.
  - In **Normal Bike Mode**:
    - `motorPowerWatts = 0`
    - `batteryTelemetry = null`
    - `riderPowerWatts`: If shown, calculated via physical aerodynamic/rolling drag mechanical equation ($P_{\text{mech}} = F_{\text{total}} \cdot v$) and explicitly labeled as an estimated physics calculation, or set to 0 when stationary.
  - In **E-Bike Mode**:
    - Use segment physics demand: calculate instantaneous mechanical power from road slope and current speed, split between motor and rider according to `activeAssist`.
    - Mark telemetry explicitly as `isPowerEstimated = true`.

---

### Phase 4: Settings & Configuration UI

#### 4.1 Create `ui/components/SettingsDialog.kt`
Create a dedicated Material 3 Compose dialog containing:
1. **Header**: "Configurações da Bicicleta" / "Bike Settings" with a close button.
2. **Primary Opt-In Toggle**:
   - `Switch` component: *"Tenho uma Bicicleta Elétrica (E-Bike)"*
   - Subtitle: *"Desative para usar o aplicativo como ciclocomputador tradicional. Ative para estimativas de consumo de bateria e autonomia."*
   - Default state: **OFF**.
3. **Normal Bike Information Card (Shown when toggle is OFF)**:
   - Icon: `Icons.Default.DirectionsBike`
   - Text: *"Modo Bicicleta Convencional ativo. Suas rotas e estimativas de tempo são calculadas considerando pedalada humana sem assistência de motor. Métricas de bateria estão ocultas."*
4. **E-Bike Specification Form (Shown only when toggle is ON)**:
   - **Simulation Disclaimer Banner**:
     ```
     ⚠️ ESTIMATIVAS POR SIMULAÇÃO MATEMÁTICA
     Este app não possui conexão Bluetooth (BLE) com a sua bicicleta.
     Os números de autonomia, bateria e potência são calculados por
     física simulada a partir das especificações informadas abaixo.
     ```
   - **Battery Capacity Input**:
     - Number field: `Capacidade da Bateria (Wh)`
     - Quick preset chips: `[250 Wh] [400 Wh] [500 Wh] [625 Wh] [750 Wh]`
   - **Max Assist Speed**:
     - Number field / selector: `Velocidade Máxima de Assistência (km/h)`
     - Quick preset chips: `[25 km/h (UE)] [32 km/h (Brasil)] [45 km/h (Speed)]`
   - **Bike Weight**:
     - Number field: `Peso da Bicicleta (kg)` (e.g. default 24 kg)
   - **Rider Weight + Cargo**:
     - Number field: `Peso do Ciclista + Bagagem (kg)` (e.g. default 75 kg)
   - **Current Battery Charge Level**:
     - Slider: `Nível Atual da Bateria (%)` with live Wh equivalent display.

#### 4.2 Entry Points in `MainActivity.kt`
- Add a Settings button (Gear icon `Icons.Default.Settings`) to the floating top bar.
- Manage dialog visibility state in `BikeMapViewModel`: `val showSettingsDialog = MutableStateFlow(false)`.

---

### Phase 5: Cockpit & Navigation HUD Overhauls

#### 5.1 Update `ui/components/TelemetryCockpitDialog.kt`
Add `isEBikeMode: Boolean` parameter.

##### Case A: Normal Bike Mode (`isEBikeMode == false`)
- Header: *"Ciclocomputador de Bordo"* (Bike Computer).
- **Speed Section**:
  - Current Speed (large display), Average Speed, Max Speed.
- **Trip Statistics Section**:
  - Distance Ridden, Elapsed Time, Current Elevation, Total Elevation Gained.
- **HIDDEN Components**:
  - Completely hide the Motor Power (W) card.
  - Completely hide the Battery Telemetry card (percentage, Wh, range, voltage).
  - Completely hide the Assist Level selector buttons.

##### Case B: E-Bike Mode (`isEBikeMode == true`)
- **Prominent Top Disclaimer Badge**:
  - Container: `Slate800` with amber/cyan border.
  - Text: *"ESTIMATIVA POR FÍSICA SIMULADA • SEM CONEXÃO BLE"*.
- **Power Section**:
  - Title: *"POTÊNCIA ESTIMADA (CÁLCULO FÍSICO)"*.
  - Motor card: `"${telemetry.motorPowerWatts} W (est.)"`, subtitle: *"Estimado via física"*.
  - Rider card: `"${telemetry.riderPowerWatts} W (est.)"`, subtitle: *"Estimado nas pernas"*.
- **Battery Section**:
  - Title: *"ESTIMATIVA DE BATERIA"*
  - Percentage: `"${battery.percentage}% est. (${battery.currentWh.toInt()} Wh)"`.
  - Range: *"Autonomia estimada: ~${battery.estimatedRangeKm} km (estimado, não medido)"*.
- **Assist Selector**:
  - Title: *"NÍVEL DE ASSISTÊNCIA SIMULADO (ATÉ ${config.maxAssistSpeedKmh} KM/H)"*.

#### 5.2 Update `ui/components/NavigationHud.kt`
Add `isEBikeMode: Boolean` parameter.

##### Case A: Normal Bike Mode (`isEBikeMode == false`)
- Top Instruction Banner: Unchanged (maneuver icon and distance).
- Bottom Bar:
  - **Left**: Speedometer (km/h) only. Remove the *"Assistência: ECO/TOUR"* subtitle.
  - **Center**: Distance remaining and assist-free human ETA (*"Restante: X min"*).
  - **Right**: Replace the battery percentage column with **Trip Elevation Gain** or **Average Speed** (e.g., `▲ 120m` or `Ø 21.4 km/h`).

##### Case B: E-Bike Mode (`isEBikeMode == true`)
- Bottom Bar:
  - **Left**: Speedometer + subtitle *"Assist: ${telemetry.activeAssist.name} (Simulado)"*.
  - **Center**: Distance remaining and assist-aware ETA (*"Restante: X min (com motor)"*).
  - **Right**:
    - Percentage: `"${battery.percentage}%"`
    - Subtitle: `"${battery.estimatedRangeKm} km est."`
    - Small tag: *"estimado"*

---

### Phase 6: Top Bar, Planner Sheet, & Map Overlays

#### 6.1 `MainActivity.kt` Top Bar Adaptations
- **When E-Bike Mode is OFF**:
  - Replace the Battery/Cockpit pill with a compact Stats/Settings pill or simply show the Settings gear button and a clean Cockpit button.
  - Cyclists do not see an electric bike icon or battery level on their map screen.
- **When E-Bike Mode is ON**:
  - Show the Battery/Assist pill with an added `"EST."` badge to clarify it is an on-device estimate.

#### 6.2 `RoutePlannerSheet.kt` Adaptations
- **Header**:
  - Off: *"Planejador de Rotas"* with badge `MULTI-PARADAS`.
  - On: *"Planejador E-Bike"* with badge `SIMULAÇÃO E-BIKE`.
- **Calculate Button**:
  - Off: *"CALCULAR ROTA"* (`Icons.Default.DirectionsBike`).
  - On: *"CALCULAR ROTA E-BIKE"* (`Icons.Default.ElectricBike`).
- **Route Cards & Active Route Summary**:
  - Off: Show distance, elevation gain, and standard cycling duration. Completely hide the `"-XX Wh"` and `"BATERIA (X%)"` cards.
  - On: Show distance, assist-aware duration, and `"Bateria Est.: -XX Wh (XX%)"`.

#### 6.3 `OsmdroidMapView.kt` Range Circle Overlay
- When e-bike mode is OFF: Never draw the e-bike autonomy range circle.
- When e-bike mode is ON: Draw estimated range circle around current GPS position if enabled in settings, labeled as estimated range radius.

---

## 4. Verification & Testing Strategy

### 4.1 Unit Testing Suite
1. **`BikePreferencesRepositoryTest`**:
   - Verify initial state defaults: `isEBikeMode == false`, `batteryCapacityWh == 500.0`, etc.
   - Verify persistence across repository instances using an in-memory DataStore.
   - Verify updating individual bike specs updates the emitted `UserBikePreferences`.
2. **`EBikePhysicsEngineTest`**:
   - Verify that with assist `OFF`, segment energy consumption is 0.0 Wh.
   - Verify that speed calculation for normal bike slows down on steep inclines ($>6\%$).
   - Verify that updating config with custom battery capacity (e.g. 750 Wh) and max assist speed (e.g. 25 km/h) appropriately scales the resulting range and duration estimates.
3. **`BikeMapViewModelTest`**:
   - Verify telemetry emission when `isEBikeMode == false`: `motorPowerWatts == 0`, `batteryTelemetry == null`.
   - Verify telemetry emission when `isEBikeMode == true`: `batteryTelemetry != null`, `isPowerEstimated == true`.
   - Verify route calculation uses assist-free speed parameters when e-bike mode is off.

### 4.2 UI & Component Testing
1. **Compose Previews**:
   - `SettingsDialogPreview` (both collapsed normal-bike state and expanded e-bike form).
   - `TelemetryCockpitDialogPreview_NormalBike` vs `TelemetryCockpitDialogPreview_EBike`.
   - `NavigationHudPreview_NormalBike` vs `NavigationHudPreview_EBike`.
2. **Manual Functional Flow Verification**:
   - **Scenario 1 (Fresh Install / Default)**: Launch app. Confirm no battery icon in top bar. Open route planner: confirm no Wh or battery labels. Start navigation: confirm no assist mode or battery percentage in HUD. Open Cockpit: confirm pure cycling computer stats without motor or battery sections.
   - **Scenario 2 (Opt-In & Configuration)**: Tap Settings gear. Enable "Tenho uma Bicicleta Elétrica". Change battery capacity to 625 Wh, max speed to 32 km/h, bike weight to 26 kg. Verify disclaimer is prominent. Save settings.
   - **Scenario 3 (E-Bike Navigation)**: Plan a route. Confirm assist-aware ETA and "-XX Wh est." are shown. Start simulation. Open HUD: confirm battery % has "est." label. Open Cockpit: confirm disclaimer "ESTIMATIVA POR FÍSICA SIMULADA - SEM CONEXÃO BLE" is visible, and motor/rider power are labeled as estimated.
   - **Scenario 4 (Opt-Out / Normal Bike Reversion)**: Open Settings. Turn off "Tenho uma Bicicleta Elétrica". Confirm app instantly returns to normal bike mode without residual battery metrics.

---

## 5. Risk Assessment & Mitigations

| Risk | Impact | Mitigation Strategy |
| :--- | :--- | :--- |
| **User Confusion / False Expectations** | Users assume the app connects to their e-bike via BLE and displays real battery state. | Place explicit disclaimers in Settings, Cockpit, and HUD: *"Sem conexão BLE • Valores estimados por simulação física"*. Avoid terms like "telemetria ao vivo" or "medido". |
| **ETA Inaccuracy for Normal Bikes** | If physics engine continues using 25–32 km/h cruise speeds when e-bike mode is off, cyclists will receive impossibly optimistic ETAs on climbs. | Implement human pedaling power curve in `GraphRouterService` / `EBikePhysicsEngine` when e-bike mode is off, reducing uphill cruising speed based on physical grade. |
| **DataStore Migration / Coroutine Lifecycle** | Asynchronous loading of DataStore preferences might cause a visual flicker from e-bike to normal bike on app startup. | Set initial state in ViewModel to `isEBikeMode = false` (safe default). Collect DataStore in `viewModelScope` with `SharingStarted.Eagerly`. |
| **UI Clutter in Cockpit & HUD** | Adding disclaimers and badges might overwhelm smaller phone screens. | Use concise badges (`ESTIMADO`, `EST.`) with high-contrast Material 3 typography and place detailed explanations inside dialog tooltips or headers. |

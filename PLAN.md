# Implementation Plan: Ride History & Saved/Favorite Routes Persistence

## 1. Executive Summary & Architecture Philosophy

The application (`com.ebike.router`) is an Android Kotlin/Jetpack Compose navigation and routing app. Its primary architecture philosophy is **normal-bike-first**, featuring an **opt-in e-bike mode** (with battery drain modeling, assist level scaling, and motor wattage physics).

Currently, the app has **zero local persistence** — no Room, DataStore, or SharedPreferences. If the user closes the app or finishes a ride, all route computations, waypoints, and ride telemetry disappear immediately.

This document outlines a production-ready implementation plan to introduce local database persistence using **Jetpack Room** and **Kotlin Coroutines / Flow**, covering:
1. **Ride History Persistence**: Logging completed rides (distance, duration, avg/max speed, elevation gain/loss, polyline path, and optional e-bike metrics).
2. **Saved / Favorite Routes**: Storing multi-stop itineraries with profile preference, waypoints, and polyline previews.
3. **Saved / Favorite Destinations**: Storing frequent places (Home, Work, Trails) for 1-tap waypoint selection in search dialogs.

---

## 2. Dependencies & Build Configuration (`build.gradle.kts`)

### 2.1 Root `build.gradle.kts`
Add the Kotlin Symbol Processing (KSP) plugin matching Kotlin `2.0.21`:
```kotlin
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}
```

### 2.2 `app/build.gradle.kts`
Apply the KSP plugin and add Room dependencies:
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

dependencies {
    // Existing:
    // ...
    // implementation("com.google.code.gson:gson:2.11.0")

    // Room Persistence
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
}
```

---

## 3. Data Model & Room Entities

To respect the **normal-bike-first** principle, all e-bike-specific telemetry fields (such as `energyConsumedWh`, `batteryDrainPercent`, and `assistLevel`) are **nullable**. When a rider completes a regular bike ride, e-bike metrics are `null`, avoiding artificial zero-battery entries.

### 3.1 Entity: `RideHistoryEntity`
Stores completed or recorded rides.

```kotlin
package com.ebike.router.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ride_history")
data class RideHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,                          // e.g. "Pedal Matinal", "Parque Ibirapuera", or custom name
    val timestampMillis: Long,                  // Start timestamp (System.currentTimeMillis())
    val distanceMeters: Int,                    // Total distance traveled
    val durationSeconds: Int,                   // Total elapsed active ride time
    val avgSpeedKmh: Double,                    // Calculated average speed
    val maxSpeedKmh: Double,                    // Peak speed recorded
    val elevationGainM: Int,                    // Total meters climbed
    val elevationLossM: Int,                    // Total meters descended
    val routePolyline: String,                  // JSON array or Encoded Polyline (GeoPoint list)
    val startAddress: String? = null,           // Human-readable origin label
    val endAddress: String? = null,             // Human-readable destination label
    
    // Normal-bike-first vs E-bike opt-in attributes:
    val isEBikeMode: Boolean = false,           // False for regular acoustic bikes, true if e-bike mode was used
    val assistLevel: String? = null,            // AssistLevel name (OFF, ECO, TOUR, SPORT, TURBO) or null
    val energyConsumedWh: Double? = null,       // Wh consumed during ride (null if standard bike)
    val batteryDrainPercent: Double? = null     // Percentage drain (null if standard bike)
)
```

### 3.2 Entity: `SavedRouteEntity`
Stores complete multi-waypoint itineraries that users want to repeat.

```kotlin
package com.ebike.router.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.ebike.router.model.RoutingProfile

@Entity(tableName = "saved_routes")
data class SavedRouteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,                           // e.g. "Caminho do Trabalho (Ciclovia)"
    val profile: RoutingProfile,                // EFFICIENT, TURBO, SCENIC, SAFE
    val waypointsJson: String,                  // Serialized List<RouteWaypoint>
    val polylineJson: String,                   // Serialized List<GeoPoint> for immediate rendering
    val totalDistanceMeters: Int,
    val totalDurationSeconds: Int,
    val elevationGainM: Int,
    val isFavorite: Boolean = true,
    val createdAtMillis: Long = System.currentTimeMillis()
)
```

### 3.3 Entity: `SavedDestinationEntity`
Stores pinned destination points for rapid reuse in the search dialog and main map.

```kotlin
package com.ebike.router.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class DestinationCategory {
    HOME, WORK, FAVORITE, TRAIL, POI
}

@Entity(tableName = "saved_destinations")
data class SavedDestinationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val label: String,                          // e.g. "Casa", "Trabalho", "Ciclovia Pinheiros"
    val subText: String,                        // Address or descriptive text
    val lat: Double,
    val lng: Double,
    val ele: Double = 20.0,
    val category: DestinationCategory = DestinationCategory.FAVORITE,
    val createdAtMillis: Long = System.currentTimeMillis()
)
```

### 3.4 Room Type Converters
Using the existing Gson library (`com.google.code.gson:gson:2.11.0`):
- `RoutingProfile` <-> `String`
- `DestinationCategory` <-> `String`
- `List<GeoPoint>` <-> `String` (JSON or Google Polyline Algorithm)
- `List<RouteWaypoint>` <-> `String` (JSON)

---

## 4. DAO & Repository Layer Design

### 4.1 DAOs (`RideHistoryDao`, `SavedRouteDao`, `SavedDestinationDao`)

```kotlin
package com.ebike.router.data.local.dao

import androidx.room.*
import com.ebike.router.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface RideHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRide(ride: RideHistoryEntity): Long

    @Query("UPDATE ride_history SET title = :newTitle WHERE id = :id")
    suspend fun renameRide(id: Long, newTitle: String)

    @Delete
    suspend fun deleteRide(ride: RideHistoryEntity)

    @Query("DELETE FROM ride_history WHERE id = :id")
    suspend fun deleteRideById(id: Long)

    @Query("SELECT * FROM ride_history ORDER BY timestampMillis DESC")
    fun getAllRides(): Flow<List<RideHistoryEntity>>

    @Query("SELECT * FROM ride_history WHERE id = :id")
    suspend fun getRideById(id: Long): RideHistoryEntity?
}

@Dao
interface SavedRouteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoute(route: SavedRouteEntity): Long

    @Query("UPDATE saved_routes SET name = :newName WHERE id = :id")
    suspend fun renameRoute(id: Long, newName: String)

    @Query("UPDATE saved_routes SET isFavorite = :isFav WHERE id = :id")
    suspend fun setFavorite(id: Long, isFav: Boolean)

    @Query("DELETE FROM saved_routes WHERE id = :id")
    suspend fun deleteRouteById(id: Long)

    @Query("SELECT * FROM saved_routes ORDER BY createdAtMillis DESC")
    fun getAllSavedRoutes(): Flow<List<SavedRouteEntity>>
}

@Dao
interface SavedDestinationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDestination(destination: SavedDestinationEntity): Long

    @Query("UPDATE saved_destinations SET label = :newLabel WHERE id = :id")
    suspend fun renameDestination(id: Long, newLabel: String)

    @Query("DELETE FROM saved_destinations WHERE id = :id")
    suspend fun deleteDestinationById(id: Long)

    @Query("SELECT * FROM saved_destinations ORDER BY createdAtMillis DESC")
    fun getAllDestinations(): Flow<List<SavedDestinationEntity>>
}
```

### 4.2 Room Database (`AppDatabase`)
```kotlin
@Database(
    entities = [
        RideHistoryEntity::class,
        SavedRouteEntity::class,
        SavedDestinationEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(RoomConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rideHistoryDao(): RideHistoryDao
    abstract fun savedRouteDao(): SavedRouteDao
    abstract fun savedDestinationDao(): SavedDestinationDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bike_router.db"
                ).build().also { INSTANCE = it }
            }
    }
}
```

### 4.3 Repository Interface & Implementation (`BikeRepository`)
A unified repository decoupling Room entities from UI ViewModels:
- `val allRides: Flow<List<RideHistoryEntity>>`
- `val savedRoutes: Flow<List<SavedRouteEntity>>`
- `val savedDestinations: Flow<List<SavedDestinationEntity>>`
- `suspend fun saveRide(ride: RideHistoryEntity): Long`
- `suspend fun renameRide(id: Long, title: String)`
- `suspend fun deleteRide(id: Long)`
- `suspend fun saveRoute(name: String, route: RouteResult, waypoints: List<RouteWaypoint>): Long`
- `suspend fun deleteRoute(id: Long)`
- `suspend fun renameRoute(id: Long, newName: String)`
- `suspend fun saveDestination(label: String, point: GeoPoint, address: String, category: DestinationCategory)`
- `suspend fun deleteDestination(id: Long)`

---

## 5. ViewModel Integration & State Machine Hooks

`BikeMapViewModel` is the single source of truth for the map UI and navigation state. Below are the precise locations to hook save/load operations:

### 5.1 Repository Initialization
In `BikeMapViewModel(application: Application)`:
```kotlin
private val database = AppDatabase.getInstance(application)
val repository: BikeRepository = BikeRepositoryImpl(
    database.rideHistoryDao(),
    database.savedRouteDao(),
    database.savedDestinationDao()
)

// Expose observable state flows to Compose UI
val rideHistory = repository.allRides.stateIn(
    viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
)
val savedRoutes = repository.savedRoutes.stateIn(
    viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
)
val savedDestinations = repository.savedDestinations.stateIn(
    viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
)
```

### 5.2 Hook 1: Ride Completion & Auto-Save Prompt
Currently, in `BikeMapViewModel.kt`:
- `handleRiderLocationUpdate` checks `if (distToManeuver <= 15)` on the last instruction and calls `stopNavigation()`.
- `stopNavigation()` immediately sets `_isNavigating.value = false` and cancels `rideTimerJob`, discarding all telemetry.

**Proposed Hook**:
1. Introduce a state `val completedRideSummary = MutableStateFlow<RideHistoryEntity?>(null)`.
2. Introduce a session start timestamp `rideStartTime: Long = 0L` initialized when `startNavigation()` is called.
3. Update `stopNavigation(savePrompt: Boolean = true)`:
   - Check if ride was substantive (e.g. `distanceRiddenKm >= 0.05` or `timeElapsedSeconds >= 20`) to avoid saving accidental 2-second clicks.
   - If substantive and `savePrompt == true`:
     - Construct a `RideHistoryEntity` from `telemetry.value` and `activeRoute.value`.
     - Determine `isEBikeMode`: check whether motor assist was enabled (`activeAssist != AssistLevel.OFF`) or battery telemetry is active. For regular bike mode, set `energyConsumedWh = null` and `batteryDrainPercent = null`.
     - Assign `completedRideSummary.value = summaryEntity`.
     - This triggers a Compose dialog: "Pedal Finalizado! Deseja salvar?".
     - If user clicks "Salvar", call `viewModelScope.launch { repository.saveRide(...) }`.
     - If user clicks "Descartar", reset `completedRideSummary.value = null`.

### 5.3 Hook 2: Saving the Active Route
In `RoutePlannerSheet`:
1. When `activeRoute.value != null`, the user can tap a "Salvar Rota" / Bookmark button.
2. ViewModel method:
```kotlin
fun saveCurrentRoute(customName: String? = null) {
    val route = activeRoute.value ?: return
    val wps = _waypoints.value
    viewModelScope.launch {
        repository.saveRoute(
            name = customName ?: route.name,
            route = route,
            waypoints = wps
        )
    }
}
```

### 5.4 Hook 3: Loading a Saved Route
When the user taps a saved route from the Saved Routes list:
1. ViewModel method:
```kotlin
fun loadSavedRoute(savedRoute: SavedRouteEntity) {
    val decodedWaypoints = deserializeWaypoints(savedRoute.waypointsJson)
    _waypoints.value = decodedWaypoints
    _selectedProfile.value = savedRoute.profile
    showRoutePlannerSheet.value = true
    calculateRoute() // Recalculate route for up-to-date traffic/profile
}
```

### 5.5 Hook 4: Saving & Loading Favorite Destinations
1. **In `WaypointSearchDialog`**:
   - Above the search results or when query is blank, display `savedDestinations` chips ("🏠 Casa", "💼 Trabalho", "⭐ Favoritos").
   - Tapping a chip immediately invokes `setWaypoint(targetIndex, destination.point, destination.label)` and dismisses the dialog.
   - On search result items, add a Star/Bookmark icon: tapping it invokes `repository.saveDestination(...)`.
2. **In Map Tap Dialog (`showMapClickMenuForPoint`)**:
   - Add a "+ Salvar como Favorito" button to persist the tapped point.

---

## 6. UI Touchpoints & User Experience Flow

```
+-------------------------------------------------------------------------+
| Top Bar: [ 🔍 Para onde vamos pedalar? ]  [ ⭐ Salvos & Histórico ] [ 🔋 90% ] |
+-------------------------------------------------------------------------+
                                    |
                                    v Opens
+-------------------------------------------------------------------------+
|                  MODAL / SHEET: HISTÓRICO & SALVOS                      |
|  [ TAB 1: HISTÓRICO ]   [ TAB 2: ROTAS SALVAS ]   [ TAB 3: FAVORITOS ]  |
|                                                                         |
|  • 17/09/2026 - Pedal Noturno (Normal Bike)                             |
|    📏 14.2 km • ⏱️ 38 min • ⚡ 22.4 km/h • ▲ 120m                      |
|    [ ✏️ Renomear ] [ 🗑️ Excluir ] [ 🗺️ Ver Trajeto ]                   |
|                                                                         |
|  • 15/09/2026 - Rota Parque (E-Bike • ECO)                              |
|    📏 28.5 km • ⏱️ 55 min • ⚡ 31.0 km/h • 🔋 -42 Wh (6.7%)            |
|    [ ✏️ Renomear ] [ 🗑️ Excluir ] [ 🗺️ Ver Trajeto ]                   |
+-------------------------------------------------------------------------+
```

### 6.1 UI Entry Points
1. **Top Bar in `MainActivity.kt`**:
   - Insert an IconButton or Pill next to Search and Cockpit: `Icons.Default.Bookmark` / `Icons.Default.History` ("Histórico & Salvos").
   - Controls state `showHistoryDialog: MutableStateFlow<Boolean>`.
2. **Post-Ride Summary Dialog (`RideSummaryDialog`)**:
   - Appears immediately upon arriving at the destination or ending navigation.
   - Shows summary statistics card:
     - Distance, duration, avg speed, elevation.
     - Mode badge: "Bicicleta Convencional" vs "E-Bike (Tour/Eco)".
   - Editable `OutlinedTextField` for custom ride title (prefilled with e.g. "Pedal em [Data]").
   - Action buttons: "Salvar no Histórico" (EmeraldGreen) vs "Descartar" (Slate700).
3. **History & Saved Sheet (`RideHistorySheet.kt`)**:
   - **Tab 1: Histórico de Pedais**:
     - `LazyColumn` of ride cards sorted by timestamp descending.
     - Distinguishes standard bike vs e-bike visually.
     - Swipe-to-delete or delete icon button with `ConfirmDeleteDialog`.
     - Rename icon button opening `RenameDialog`.
     - "Repetir no Mapa": loads the polyline on the map for viewing.
   - **Tab 2: Rotas Salvas**:
     - List of saved itineraries with profile tags, distance, and duration.
     - "Navegar Agora": populates waypoints and opens planner.
     - Rename / Delete options.
   - **Tab 3: Locais Favoritos**:
     - List of saved locations (Home, Work, custom).
     - "Ir para cá": sets destination waypoint.
4. **Integration into `WaypointSearchDialog.kt`**:
   - Quick Favorites Row displayed when search query is empty.
   - Bookmark icon on each search result item to save directly into favorites.
5. **Integration into `RoutePlannerSheet.kt`**:
   - A "Salvar Rota" button placed on the active route preview card.

---

## 7. Step-by-Step Implementation Roadmap

| Step | Scope | Description |
| :--- | :--- | :--- |
| **Phase 1** | Gradle Setup | Add KSP plugin to root and app `build.gradle.kts`, add `androidx.room` runtime, ktx, and compiler dependencies. |
| **Phase 2** | Local Data Layer | Implement `RideHistoryEntity`, `SavedRouteEntity`, `SavedDestinationEntity`, Room type converters, DAOs, and `AppDatabase`. |
| **Phase 3** | Repository Layer | Create `BikeRepository` interface and `BikeRepositoryImpl` managing coroutines on `Dispatchers.IO`. |
| **Phase 4** | ViewModel Hooks | Inject repository into `BikeMapViewModel`. Wire navigation completion to `completedRideSummary`, add save/load/rename/delete methods. |
| **Phase 5** | UI Components | Create `RideHistorySheet`, `RideSummaryDialog`, `RenameDialog`, and `ConfirmDeleteDialog`. |
| **Phase 6** | Search & Planner UI | Integrate favorite destinations row into `WaypointSearchDialog` and "Salvar Rota" into `RoutePlannerSheet`. |
| **Phase 7** | Verification | Test normal bike rides (no battery data saved), e-bike rides (battery data saved), database migrations, route loading, and deletion. |

---

## 8. Verification & Edge Cases

1. **Normal Bike Mode Integrity**:
   - Verify that rides performed with regular bike settings have `isEBikeMode = false` and `energyConsumedWh = null`, ensuring the UI cleanly hides battery cards.
2. **Zero-Distance / Accidental Clicks**:
   - Guard `completedRideSummary` against short aborts (< 50 meters or < 15 seconds) so database is not polluted.
3. **Database Versioning & Migration**:
   - Initial version `version = 1`. If schema evolves, specify clean Room migrations or `fallbackToDestructiveMigration()` during development.
4. **Polyline Compression**:
   - Store polylines as Google Polyline Algorithm encoded strings (or serialized coordinate JSON) to avoid large payload overhead in SQLite.

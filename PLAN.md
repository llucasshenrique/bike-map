# Off-Route Detection & Automatic Rerouting — Implementation Plan

## Current state (as read)

- `GraphRouterService.calculateMultipleRoutes()` is called once, on demand
  (`BikeMapViewModel.calculateRoute()`), when waypoints/profile change. Nothing
  calls it again once navigation starts.
- `BikeMapViewModel.handleRiderLocationUpdate()` (called from the
  `locationTracker.locationState` collector in `init {}`) only tracks progress
  *along the current instruction list* — it compares the rider's position to
  `currentManeuver.point` to advance `_currentInstructionIndex`. It never
  checks distance to the route polyline itself, so a rider who leaves the
  route entirely keeps "navigating" a route they're no longer on; the next
  maneuver's `distToManeuver` just grows without bound.
- `RouteResult.coordinates: List<GeoPoint>` is the full route polyline (built
  in `GraphRouterService.parseSingleRoute`/`buildDirectRoute`), already
  available on `activeRoute.value`. This is the natural basis for deviation
  distance — checking against it directly is more robust than checking
  against per-maneuver points, since maneuver points are turn locations, not
  a dense path.
- `NavigationHud` is a pure display component (turn card + telemetry bar); it
  has no rerouting affordance and doesn't need deep changes, just a way to
  reflect "recalculating" state and optionally show a toast/banner.
- `GeoPoint.distanceTo(other)` already exists (haversine, presumably) and is
  used throughout — reuse it, don't add a new distance primitive.

## Design

### 1. Deviation-distance algorithm

Add a pure function (e.g. in a new small file
`app/src/main/kotlin/com/ebike/router/navigation/RouteDeviation.kt`, no
Android/service dependencies so it's trivially unit-testable) that computes
the rider's perpendicular distance to the route polyline:

```
fun distanceToRoute(point: GeoPoint, routeCoordinates: List<GeoPoint>): Double
```

Implementation approach:
- Iterate consecutive coordinate pairs `(a, b)` in `routeCoordinates`.
- For each segment, project `point` onto the segment using planar
  equirectangular approximation (routes are short enough — a few km — that
  flat-earth projection around the segment's local latitude is accurate to
  well under a meter of error, and is far cheaper than proper great-circle
  cross-track math). Clamp the projection parameter `t` to `[0, 1]` so the
  closest point is constrained to the segment, not the infinite line.
- Compute the haversine distance (via existing `GeoPoint.distanceTo`) from
  `point` to that clamped closest point on the segment.
- Track the minimum distance across all segments and return it.
- To avoid O(n) full-polyline scans on every GPS tick (routes can have
  hundreds of coordinates from OSRM), restrict the search to a window
  around the rider's current progress index rather than the whole polyline:
  - Track `lastMatchedCoordIndex` (nearest polyline index found on the
    previous tick).
  - Search only `[lastMatchedCoordIndex - WINDOW, lastMatchedCoordIndex +
    WINDOW]` (e.g. `WINDOW = 30` points) first; only fall back to a full
    scan if nothing in the window is within a generous distance (handles
    the case where the rider teleports/GPS jumps, or where progress
    tracking desyncs).
  - This state (`lastMatchedCoordIndex`) can live in the ViewModel next to
    `_currentInstructionIndex`, reset whenever a new route becomes active.

### 2. Debounce / hysteresis to avoid false triggers

Off-route detection must resist normal GPS noise, brief signal loss, tunnel
crossings, and getting off the bike to walk it through pedestrian-only
segments. Use two independent guards:

- **Distance threshold.** Off-route candidate when
  `distanceToRoute(point, route.coordinates) > OFF_ROUTE_THRESHOLD_METERS`.
  Recommend `OFF_ROUTE_THRESHOLD_METERS = 35.0` — generous enough to absorb
  typical smartphone GPS error (5-20 m) plus minor lane/path offset, tight
  enough to catch a genuine wrong turn onto a parallel street quickly.
  Optionally scale it with `locationState.accuracyMeters` (e.g.
  `max(35.0, accuracyMeters * 2.5)`) since `RiderLocationState.accuracyMeters`
  is already tracked in `LocationTrackerService` — this prevents false
  triggers when the fix is degraded (e.g. under tree cover).
- **Sustained-duration debounce (hysteresis).** A single over-threshold
  sample must not trigger a reroute (GPS jump artifacts are common — note
  `LocationTrackerService` already special-cases noisy speed samples for the
  same reason). Require the deviation to persist:
  - Track `offRouteSinceMillis: Long?` in the ViewModel. On the first sample
    exceeding the threshold, set it to `now`. On each subsequent sample: if
    still over threshold and `now - offRouteSinceMillis >=
    OFF_ROUTE_CONFIRM_MS` (recommend **6000 ms**, i.e. ~3-6 consecutive GPS
    fixes at the 1s update interval), fire the reroute. If a sample comes
    back under threshold before the debounce window elapses, reset
    `offRouteSinceMillis = null` (rider was momentarily noisy, not actually
    off-route).
  - Additionally require a minimum number of consecutive over-threshold
    samples (e.g. 3) rather than pure wall-clock time, since
    `locationState` updates aren't perfectly periodic (min interval 500ms,
    target 1000ms) — combining both guards (time AND consecutive-sample
    count) is cheap and removes edge cases where a burst of fast updates
    could trigger on transient noise within the time window.
- **Cooldown after a reroute.** After a reroute is triggered and a new route
  is loaded, suppress off-route re-evaluation for a short cooldown (e.g. 5s)
  to let the rider's position stabilize against the *new* polyline and avoid
  immediately re-triggering while the fresh route is still being fetched.
- **Only evaluate while actually navigating.** Gate all of this behind
  `_isNavigating.value == true` and `activeRoute.value != null`, mirroring
  the existing guard at the top of `handleRiderLocationUpdate`.

### 3. Triggering GraphRouterService recompute

When the debounced check fires:

1. Set a new state flag, e.g. `_isRerouting: StateFlow<Boolean>` (exposed for
   UI, see below), to `true`.
2. Call `routerService.calculateMultipleRoutes(points, profile)` with
   `points = listOf(currentRiderGeoPoint) + remainingOriginalWaypoints`
   (i.e. current GPS fix as new origin, keep the original destination/
   waypoints after the rider's current leg — for a simple A→B route this is
   just `listOf(currentPoint, destinationPoint)`). This reuses the exact
   same method the initial route uses, from `viewModelScope.launch`, same as
   `calculateRoute()`.
3. On success: take the first result (or best-ranked, matching
   `selectRoute(0)` behavior), assign it to `activeRoute.value`, reset
   `_currentInstructionIndex.value = 0`, `_currentInstruction.value =
   newRoute.instructions.firstOrNull()`, reset `lastMatchedCoordIndex` and
   `offRouteSinceMillis`, and speak a guidance line via
   `audioGuidance.speak("Recalculando rota...", true)` before the call and
   `audioGuidance.speak(newInstruction.text, true)` after, mirroring the
   existing `startNavigation` pattern.
4. On failure/empty result (network down, matches the existing
   try/fallback behavior in `GraphRouterService.calculateMultipleRoutes`,
   which already falls back to `buildDirectRoute` so it practically always
   returns *something*): keep navigating the stale route rather than
   clearing it, and only retry off-route detection after the normal
   debounce cycle runs again (don't hot-loop retries).
5. Set `_isRerouting.value = false` in a `finally` block, symmetric to how
   `_isCalculating` is handled in `calculateRoute()`.
6. Concurrency guard: if a reroute is already in flight
   (`_isRerouting.value == true`), skip triggering another one even if the
   debounce condition re-fires (e.g. `handleRiderLocationUpdate` continues
   to receive GPS ticks while the network call is pending).

This logic slots into `handleRiderLocationUpdate` (or a new private method
`checkOffRouteAndReroute(point)` called from it) in `BikeMapViewModel.kt`,
right after the existing maneuver-advance logic, guarded by
`_isNavigating.value`.

### 4. UI feedback (NavigationHud)

- Add an `isRerouting: Boolean` parameter to `NavigationHud`, sourced from
  the new `_isRerouting` StateFlow, and show a small inline state on the top
  instruction card (e.g. replace the maneuver text temporarily with
  "Recalculando rota..." plus a small `CircularProgressIndicator`, reusing
  the same animated-progress pattern already introduced for the splash
  screen per the recent commit history). This requires no structural
  change to the card, just a conditional branch in the existing `Column`
  that already renders `instruction?.text`.
- No changes needed to the bottom telemetry bar or buttons.

### 5. State/reset bookkeeping

- Reset `offRouteSinceMillis`, `lastMatchedCoordIndex`, and consecutive
  over-threshold counters whenever: `startNavigation()` is called (fresh
  route), `stopNavigation()` is called, or a reroute completes successfully
  (step 3 above). This avoids stale debounce state leaking across
  navigation sessions or across route swaps.

## Suggested file-level changes (for the follow-up implementation task)

1. **New file** `navigation/RouteDeviation.kt` — pure `distanceToRoute()` +
   windowed nearest-segment search, unit-testable without Android
   dependencies.
2. **`BikeMapViewModel.kt`**:
   - New private state: `lastMatchedCoordIndex`, `offRouteSinceMillis`,
     `consecutiveOffRouteSamples`.
   - New public `_isRerouting` StateFlow.
   - New private `checkOffRouteAndReroute(point: GeoPoint)` invoked from
     `handleRiderLocationUpdate`.
   - Reset logic added to `startNavigation()` / `stopNavigation()`.
3. **`NavigationHud.kt`**: add `isRerouting` param + conditional UI branch.
4. **Tests**: unit tests for `RouteDeviation.distanceToRoute` covering
   on-route, near-threshold, and off-route cases with a synthetic polyline
   (no emulator/instrumentation needed since the function is pure).

## Constants to tune during implementation

| Constant | Suggested value | Rationale |
|---|---|---|
| `OFF_ROUTE_THRESHOLD_METERS` | 35.0 (or `max(35, accuracy*2.5)`) | Absorbs GPS noise, catches real deviation |
| `OFF_ROUTE_CONFIRM_MS` | 6000 | ~3-6 GPS samples of sustained deviation |
| `MIN_CONSECUTIVE_OFF_SAMPLES` | 3 | Belt-and-suspenders against update-rate jitter |
| `REROUTE_COOLDOWN_MS` | 5000 | Let new route stabilize before re-evaluating |
| `POLYLINE_SEARCH_WINDOW` | 30 points | Bound per-tick cost on long polylines |

No code was written; this file only records the investigation and proposed
approach for a follow-up implementation pass.

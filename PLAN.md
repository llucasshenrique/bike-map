# Plan: Real Offline Map Tile Caching

## Problem

`SplashScreenOverlay.kt:120` displays "Iniciando GPS e mapas offline..." ("Starting GPS and offline maps...") but `OsmdroidMapView.kt` never configures osmdroid's tile cache, base path, or cache expiration policy. It calls `Configuration.getInstance().userAgentValue = ...` and `setTileSource(TileSourceFactory.MAPNIK)` and nothing else. In practice this means:

- osmdroid falls back to its internal default cache dir/size, which is not app-scoped, not sized deliberately, and not guaranteed to survive without storage cleanup.
- There is no way for a rider to pre-fetch tiles for a planned route before losing connectivity.
- There is no cache size ceiling, eviction policy, or user-visible storage management.
- Tiles are never served from a bundled/offline source — `MAPNIK` always attempts network fetch first.

This plan makes "offline maps" true: explicit disk cache configuration, a bulk region-download affordance tied to the route planner, storage/quota management, and a defined interaction between the live MAPNIK network source and the offline cache.

## 1. osmdroid tile cache / base path configuration

In `OsmdroidMapView.kt`, inside the existing `DisposableEffect(Unit)` (before any `MapView` is constructed), configure the shared `Configuration.getInstance()` singleton:

- `osmdroidBasePath`: `File(context.filesDir, "osmdroid")` — app-private, no storage permission needed, survives across app restarts, cleared on uninstall (acceptable for a tile cache).
- `osmdroidTileCache`: `File(osmdroidBasePath, "tiles")` — osmdroid's `SqlTileWriter` (the default `IFilesystemCache` used by osmdroid ≥6.x) stores tiles in a single SQLite DB (`cache.db`) at this path instead of thousands of loose PNG files. This is already osmdroid's default writer as of 6.1.x — the win here is *pointing it at a deliberate, app-scoped path* rather than the OS default (which on many devices resolves to shared external storage and requires legacy storage permissions on API ≤28, or silently fails on scoped storage from API 29+).
- `Configuration.getInstance().tileFileSystemCacheMaxBytes` / `tileFileSystemCacheTrimBytes`: set explicit soft/hard cache size limits (see §3).
- `Configuration.getInstance().expirationOverrideDuration`: set a long override (e.g. 30 days) so tiles already on disk are served immediately without a network revalidation round-trip when the device is offline — osmdroid's default HTTP cache-control handling will otherwise try to hit the network per tile and only fall back to the stale cache on failure/timeout, which is slow when offline.
- Call `Configuration.getInstance().load(context, PreferenceManager.getDefaultSharedPreferences(context))` before setting the above, since `load()` will overwrite fields with persisted prefs if called after.

This alone fixes "tiles get cached at all, in a sane place, with a size cap" for normal online browsing (osmdroid caches every tile it fetches as a side effect of panning). It does **not** give a rider pre-downloaded coverage for a route they haven't scrolled through yet — that requires an explicit bulk download (§2).

## 2. Explicit region pre-download for a planned route

osmdroid ships `org.osmdroid.tileprovider.cachemanager.CacheManager`, built for exactly this: given a `MapView`, a `BoundingBox`, and a zoom range, it enumerates the needed tiles and downloads+writes them into the configured `SqlTileWriter`, reporting progress via a listener.

### Where it hooks in

- `RouteResult` (`RoutingModels.kt:67`) already carries `coordinates` and a computed bounding box is derivable the same way `OsmdroidMapView.kt:220-228` does it for `zoomToBoundingBox` (min/max lat/lng + padding).
- Add a new service, `OfflineTileCacheService` (parallel to existing `service/` classes like `GraphRouterService`), wrapping `CacheManager`:
  - `estimateTileCount(bbox: BoundingBox, minZoom: Int, maxZoom: Int): Int` — calls `CacheManager.possibleTilesInArea(...)` (or manually sums `4^(z2-z1)` per grid cell osmdroid exposes) so the UI can show "~840 tiles / ~42 MB" before committing.
  - `downloadRegion(bbox, minZoom, maxZoom, onProgress: (downloaded: Int, total: Int) -> Unit, onDone: (success: Boolean) -> Unit)` — wraps `CacheManager.downloadAreaAsync(activity, bbox, minZoom, maxZoom, callback)`.
- Zoom range: pick a fixed practical band, e.g. 12–17 (city/route-following zooms), not the full 0–19, to keep tile counts bounded. Buffer the bounding box by ~1–2 km beyond the route polyline (not just the existing 0.005° pad used for camera framing) so minor reroutes/off-route excursions still hit cached tiles.
- `MAPNIK`'s usage policy requires attribution and reasonable bulk-download behavior — throttle concurrent downloads (osmdroid's `CacheManager` already serializes via its own executor) and warn the user this uses their data connection; do not parallelize beyond osmdroid's defaults.

### UI affordance

- In `RoutePlannerSheet.kt`, once a `RouteResult` exists (same place `onCalculateRoute`/`onStartNavigation` live), add a "Baixar mapa offline" (Download offline map) button/row showing estimated tile count and size.
- Tapping it calls into the ViewModel (`BikeMapViewModel.kt`), which owns a new `downloadProgress: StateFlow<OfflineDownloadState>` (idle / running(current, total) / done / error) exposed to the sheet as a progress bar, mirroring how `isCalculating` is already surfaced.
- `MainActivity`/`OsmdroidMapView` doesn't need changes beyond exposing the live `MapView` instance (already held in `mapViewRef`, but that's local to the composable — the download doesn't strictly need a live `MapView`; `CacheManager` can be constructed from a throwaway `MapView` or, in newer osmdroid, from the tile provider directly). Confirm osmdroid 6.1.20's `CacheManager(MapView)` constructor requirement — if it requires an attached `MapView`, reuse `mapViewRef` via a callback rather than instantiating a second one.
- Post-download, surface a small "mapa offline disponível" indicator on the route preview card so the rider knows before departure that this route is safe offline.

## 3. Storage / size management

- Set a hard ceiling via `Configuration.getInstance().tileFileSystemCacheMaxBytes` (e.g. 500 MB) and `tileFileSystemCacheTrimBytes` (e.g. 400 MB) so ordinary pan/zoom browsing self-trims (osmdroid's `SqlTileWriter` runs LRU eviction against these thresholds automatically).
- For explicit region downloads, treat them as a distinct "pinned" concern from the general LRU cache:
  - Simplest approach given osmdroid's storage model (single shared `cache.db`, no per-tile pinning API): before starting a region download, check `SqlTileWriter().getSize()` (or equivalent DB file size) against a separate "reserved for offline routes" budget (e.g. cap total offline-route downloads at 300 MB, independent from but bounded within the 500 MB overall ceiling) and warn/block if exceeded.
  - Because osmdroid has no built-in "protect these tiles from LRU eviction" flag, downloaded offline tiles can still be evicted by later ordinary browsing if the shared cache fills up. Document this limitation to the user (a badge that says "may need re-download if unused for a while") rather than pretending permanence, or mitigate by giving downloaded-route tiles their own separate `SqlTileWriter`/DB file at a distinct path and a custom tile source that checks the offline DB first, falling back to the shared cache — more work, but the only way to get real pinning. Recommend starting with the shared-cache approach and revisiting per-route DBs only if eviction turns out to be a real problem in practice.
- Add a simple settings/storage screen (or a row in the existing cockpit dialog) showing total offline cache size with a "Limpar mapas offline" (clear offline maps) action that deletes the `osmdroidTileCache` directory and re-initializes it — reuse `TelemetryCockpitDialog.kt` as the natural home since it's already the app's "device/status" surface.

## 4. Interaction with the existing MAPNIK source

- Keep `TileSourceFactory.MAPNIK` as the active source — no need to swap to a custom/bundled tile source. osmdroid's `MapTileProviderBasic` already checks the configured `IFilesystemCache` before making an HTTP request for any given tile/zoom/source combination; tiles pre-fetched by `CacheManager` for `MAPNIK` at a given zoom will transparently be served from disk when the network is unavailable, with zero changes to the render path.
- Caveat: the cache key includes tile source name, so pre-downloaded tiles are only reused if the user never switches tile providers. Since the app only ever uses MAPNIK today, this is a non-issue — just don't introduce a second tile source without accounting for doubled cache usage.
- With `expirationOverrideDuration` set (§1), osmdroid will serve cached tiles immediately rather than attempting a network revalidation, which is what makes true offline (airplane-mode) map viewing work — without it, tile requests can stall waiting on a timed-out network call before falling back to cache.
- No manifest changes needed beyond what exists (`INTERNET` permission already present for the download itself); no additional storage permission required since the cache lives under `context.filesDir`.

## Suggested file-level changes (for the follow-up implementation pass)

1. `OsmdroidMapView.kt` — cache/base-path/expiration config in `DisposableEffect`.
2. New `service/OfflineTileCacheService.kt` — `CacheManager` wrapper, size estimation, progress callback.
3. `ui/viewmodel/BikeMapViewModel.kt` — `downloadProgress` StateFlow, `downloadOfflineRegion(route)` method, storage-size query, clear-cache method.
4. `ui/components/RoutePlannerSheet.kt` — "Baixar mapa offline" row with estimate + progress bar.
5. `ui/components/TelemetryCockpitDialog.kt` — offline storage usage + "Limpar mapas offline" action.
6. `SplashScreenOverlay.kt` — no code change required once the above lands; the existing copy becomes accurate rather than aspirational.

## Open questions to resolve before implementation

- Confirm osmdroid 6.1.20's exact `CacheManager` API surface (constructor signature, async download callback shape, `possibleTilesInArea` availability) by checking the actual library sources/javadoc, since APIs shifted across osmdroid 6.x minor versions.
- Decide the offline-cache ceiling numbers (500 MB/400 MB/300 MB above are starting suggestions) based on target device storage expectations for this app's users.
- Decide whether per-route pinning (separate DB) is worth the complexity now or should be a fast-follow if the shared-cache LRU eviction proves annoying in practice.

# Plan: Expose UI affordance for waypoint reordering

## Investigation summary

- `BikeMapViewModel.moveWaypoint(fromIdx: Int, toIdx: Int)` (`ui/viewmodel/BikeMapViewModel.kt:151-160`) is fully implemented: removes the waypoint at `fromIdx`, reinserts it at `toIdx`, reindexes letters/labels, and recalculates the route if there are enough valid points.
- `RoutePlannerSheet` already accepts `onMoveWaypoint: (Int, Int) -> Unit` as a parameter (`ui/components/RoutePlannerSheet.kt:38`), and `MainActivity.kt:362` wires it to `viewModel.moveWaypoint`. So the callback plumbing is complete end-to-end.
- The gap is purely inside `RoutePlannerSheet`'s composable body: the waypoint list (`ui/components/RoutePlannerSheet.kt:127-211`) renders each `RouteWaypoint` in a plain `Column` of `Row`s built with `forEachIndexed`, with an "Actions" `Row` (lines 187-208) that only has Pick-on-map, Search, and Remove `IconButton`s. `onMoveWaypoint` is never called anywhere in the file — it is dead code from the UI's perspective, exactly as described.
- The list is rendered inside a `Card` whose content sits in a `Column().verticalScroll(rememberScrollState())` (line 58-62) — the whole sheet, including the waypoint list, profile tabs, route options, and route summary, scrolls as one unit. The waypoint sub-list is a plain `Column`, **not** a `LazyColumn`.

## Recommendation: up/down icon buttons, not drag-and-drop

Add two small `IconButton`s (▲ `Icons.Default.KeyboardArrowUp` / ▼ `Icons.Default.KeyboardArrowDown`) to the existing "Actions" `Row` per waypoint, calling `onMoveWaypoint(idx, idx - 1)` / `onMoveWaypoint(idx, idx + 1)`.

### Why not drag-and-drop

1. **Not a LazyColumn.** The waypoint list is a plain `Column` nested inside an outer `verticalScroll` `Column` that also contains profile tabs, the calculate button, route option cards, and the route summary/elevation chart. Reorderable-list solutions (`LazyColumn` + `Modifier.dragAndDropContainer`, or libraries like `reorderable`/`sh.calvin.reorderable`) are built around `LazyColumn`/`LazyListState` item slots. Adopting one would require carving the waypoint list out into its own `LazyColumn` and restructuring the sheet's scrolling (nested scrollables inside a `verticalScroll` don't compose well — you'd hit the classic "vertical scrollable was measured with an infinite height" class of bugs, or need to give the inner `LazyColumn` a fixed/measured height). That's a structural rewrite of the sheet, not a small addition.
2. **List is short and same-height rows.** Multi-stop waypoints are typically 2-6 items, all identical fixed-height rows — the classic case where up/down buttons give 90% of the benefit of drag-and-drop for a fraction of the complexity, with no custom gesture/hit-testing code and no new dependency.
3. **Touch-target risk in a dense row.** Each waypoint row already packs a badge, two lines of text, and 2-3 `IconButton`s (32.dp) into a single `Row` (lines 134-209). A `detectDragGesturesAfterLongPress` handle competing with the row's own `clickable` (opens search, line 169) and the map-pick/search/remove buttons increases the chance of accidental drags or gesture conflicts, especially since the row's whole label area is already tappable.
4. **Consistent with the file's existing patterns.** The file already expresses "move" semantics via a single button (`onReverseWaypoints`, lines 228-237, bound to `Icons.Default.SwapVert`) rather than any gesture-based interaction. Up/down `IconButton`s match this established idiom and the icon-button-per-action layout already used in the row (lines 188-207).
5. **No new dependency needed.** `Icons.Default.KeyboardArrowUp`/`KeyboardArrowDown` are already available via the existing `androidx.compose.material.icons.filled.*` import (line 12); no library addition, no gesture-detection code, no semantics/accessibility work for custom drag handles.

### Implementation sketch (not to be done now — investigation only)

In the "Actions" `Row` (`ui/components/RoutePlannerSheet.kt:187-208`), before or after the existing buttons, add:

```kotlin
IconButton(
    onClick = { onMoveWaypoint(idx, idx - 1) },
    enabled = idx > 0,
    modifier = Modifier.size(32.dp).background(Slate700, RoundedCornerShape(6.dp))
) {
    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Mover para cima", tint = if (idx > 0) Color.White else Slate700, modifier = Modifier.size(16.dp))
}
IconButton(
    onClick = { onMoveWaypoint(idx, idx + 1) },
    enabled = idx < waypoints.size - 1,
    modifier = Modifier.size(32.dp).background(Slate700, RoundedCornerShape(6.dp))
) {
    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Mover para baixo", tint = if (idx < waypoints.size - 1) Color.White else Slate700, modifier = Modifier.size(16.dp))
}
```

Notes for the implementer:
- Guard first/last positions (`idx == 0` disables up, `idx == waypoints.size - 1` disables down) — mirrors the existing `isFirst`/`isLast` booleans already computed at lines 129-130.
- Row already has 2-3 buttons at 32.dp each plus a flexible label `Column`; adding two more (4-5 total) in a `.size(32.dp)` `Row` with `spacedBy(4.dp)` will get visually tight, especially on narrow devices — worth checking whether the existing Pick-on-map / Search buttons could be dropped to icon-only more compactly, or whether the row needs to shrink other elements, once this is actually implemented.
- `moveWaypoint` already triggers `calculateRoute()` when valid, so no extra wiring needed beyond calling the existing `onMoveWaypoint` callback — this confirms the callback contract expects simple adjacent-swap style calls, which up/down buttons naturally produce (`idx` ↔ `idx ± 1`), rather than arbitrary drag-drop reordering distances.

## If drag-and-drop is wanted later

If the product direction shifts toward drag-and-drop (e.g. for longer waypoint lists), the prerequisite refactor is: extract the waypoint list into its own `LazyColumn` with explicit `key = { wp.id }` per item, and either give it a bounded height (e.g. `heightIn(max = ...)`) so it can coexist inside the outer `verticalScroll`, or restructure the sheet so only the waypoint list scrolls independently (removing the outer `verticalScroll` and making the whole `Column` a `LazyColumn` with mixed item types for header/tabs/waypoints/routes/summary). At that point, `detectDragGesturesAfterLongPress` with manual offset tracking, or a small library like `sh.calvin.reorderable`, becomes viable. This is a larger, separate change and not recommended as the first step.

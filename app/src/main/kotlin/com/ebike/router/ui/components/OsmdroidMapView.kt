package com.ebike.router.ui.components

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.ebike.router.model.*
import com.ebike.router.ui.viewmodel.BikeMapViewModel
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

@Composable
fun OsmdroidMapView(
    viewModel: BikeMapViewModel,
    modifier: Modifier = Modifier,
    onMapClick: (GeoPoint) -> Unit = {}
) {
    val context = LocalContext.current
    val waypoints by viewModel.waypoints.collectAsState()
    val availableRoutes by viewModel.availableRoutes.collectAsState()
    val selectedRouteIdx by viewModel.selectedRouteIndex.collectAsState()
    val activeRoute by viewModel.activeRoute.collectAsState()
    val locationState by viewModel.locationTracker.locationState.collectAsState()
    val activePickingIdx by viewModel.activePickingWaypointIndex.collectAsState()
    val isEBike by viewModel.isEBikeMode.collectAsState()
    val showRangeCircle by viewModel.showRangeCircle.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var riderMarker by remember { mutableStateOf<Marker?>(null) }

    DisposableEffect(Unit) {
        Configuration.getInstance().userAgentValue = "EBikeRouterAndroid/1.0"
        onDispose {
            mapViewRef?.onDetach()
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                isTilesScaledToDpi = true

                controller.setZoom(15.0)
                controller.setCenter(OsmGeoPoint(-23.5505, -46.6333)) // Default

                // Map Click Listener
                val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: OsmGeoPoint?): Boolean {
                        if (p != null) {
                            val clicked = GeoPoint(p.latitude, p.longitude, 20.0)
                            if (activePickingIdx != null) {
                                viewModel.setWaypoint(activePickingIdx!!, clicked)
                            } else {
                                onMapClick(clicked)
                            }
                        }
                        return true
                    }

                    override fun longPressHelper(p: OsmGeoPoint?): Boolean = false
                })
                overlays.add(0, eventsOverlay)

                mapViewRef = this
            }
        },
        update = { map ->
            // Clear route overlays & markers (except click overlay at 0)
            while (map.overlays.size > 1) {
                map.overlays.removeAt(map.overlays.size - 1)
            }

            // 1. Draw Alternative Routes
            availableRoutes.forEachIndexed { idx, altRoute ->
                if (idx != selectedRouteIdx) {
                    val pts = altRoute.coordinates.map { OsmGeoPoint(it.lat, it.lng) }
                    val poly = Polyline(map).apply {
                        setPoints(pts)
                        outlinePaint.color = Color.DKGRAY
                        outlinePaint.strokeWidth = 10f
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        outlinePaint.pathEffect = DashPathEffect(floatArrayOf(20f, 20f), 0f)
                        setOnClickListener { _, _, _ ->
                            viewModel.selectRoute(idx)
                            true
                        }
                    }
                    map.overlays.add(poly)
                }
            }

            // 2. Draw Active Route with Elevation Grade Colors
            activeRoute?.let { route ->
                val allPts = route.coordinates.map { OsmGeoPoint(it.lat, it.lng) }

                // Outer casing
                val casing = Polyline(map).apply {
                    setPoints(allPts)
                    outlinePaint.color = Color.parseColor("#38BDF8")
                    outlinePaint.strokeWidth = 16f
                    outlinePaint.alpha = 80
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                }
                map.overlays.add(casing)

                // Colored Segments
                if (route.segments.isNotEmpty()) {
                    route.segments.forEach { seg ->
                        if (seg.coordinates.size >= 2) {
                            val segPts = seg.coordinates.map { OsmGeoPoint(it.lat, it.lng) }
                            val color = when {
                                seg.gradePercent < 0.0 -> Color.parseColor("#06B6D4") // Downhill
                                seg.gradePercent <= 3.0 -> Color.parseColor("#10B981") // Flat
                                seg.gradePercent <= 7.0 -> Color.parseColor("#F59E0B") // Moderate
                                else -> Color.parseColor("#EF4444") // Steep
                            }
                            val segPoly = Polyline(map).apply {
                                setPoints(segPts)
                                outlinePaint.color = color
                                outlinePaint.strokeWidth = 12f
                                outlinePaint.strokeCap = Paint.Cap.ROUND
                            }
                            map.overlays.add(segPoly)
                        }
                    }
                } else {
                    val activePoly = Polyline(map).apply {
                        setPoints(allPts)
                        outlinePaint.color = Color.parseColor("#0284C7")
                        outlinePaint.strokeWidth = 12f
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                    }
                    map.overlays.add(activePoly)
                }
            }

            // 3. Draw Waypoint Markers (A, B, C...)
            waypoints.forEachIndexed { idx, wp ->
                wp.point?.let { pt ->
                    val isFirst = idx == 0
                    val isLast = idx == waypoints.size - 1
                    val bgColor = if (isFirst) "#10B981" else if (isLast) "#EF4444" else "#F59E0B"

                    val marker = Marker(map).apply {
                        position = OsmGeoPoint(pt.lat, pt.lng)
                        icon = createPinDrawable(context, wp.letter, Color.parseColor(bgColor))
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        title = wp.label
                        isDraggable = true
                        setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                            override fun onMarkerDrag(m: Marker?) {}
                            override fun onMarkerDragStart(m: Marker?) {}
                            override fun onMarkerDragEnd(m: Marker?) {
                                m?.position?.let { p ->
                                    viewModel.setWaypoint(idx, GeoPoint(p.latitude, p.longitude, 20.0))
                                }
                            }
                        })
                    }
                    map.overlays.add(marker)
                }
            }

            // 4. Draw Rider Position Marker
            val riderPt = OsmGeoPoint(locationState.point.lat, locationState.point.lng)

            // 5. Draw Estimated Range Circle (Only in E-Bike Mode)
            val bat = telemetry.batteryTelemetry
            if (isEBike && showRangeCircle && bat != null && bat.estimatedRangeKm > 0.0) {
                val circle = org.osmdroid.views.overlay.Polygon(map).apply {
                    points = org.osmdroid.views.overlay.Polygon.pointsAsCircle(riderPt, bat.estimatedRangeKm * 1000.0)
                    fillPaint.color = Color.argb(20, 6, 182, 212)
                    outlinePaint.color = Color.argb(80, 6, 182, 212)
                    outlinePaint.strokeWidth = 3f
                    title = "Autonomia Estimada: ~${bat.estimatedRangeKm} km"
                }
                map.overlays.add(circle)
            }

            val rider = Marker(map).apply {
                position = riderPt
                icon = createRiderDrawable(context)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                rotation = locationState.headingDegrees
            }
            map.overlays.add(rider)
            riderMarker = rider

            map.invalidate()
        }
    )

    val recenterEvent by viewModel.recenterEvent.collectAsState()
    var hasCenteredInitially by remember { mutableStateOf(false) }

    // Auto-center on first real GPS fix
    LaunchedEffect(locationState.hasRealFix) {
        if (locationState.hasRealFix && !hasCenteredInitially) {
            val map = mapViewRef ?: return@LaunchedEffect
            hasCenteredInitially = true
            val riderPoint = OsmGeoPoint(locationState.point.lat, locationState.point.lng)
            map.controller.animateTo(riderPoint, 16.5, 600L)
        }
    }

    // Recenter map when triggered by user
    LaunchedEffect(recenterEvent) {
        if (recenterEvent > 0L) {
            val map = mapViewRef ?: return@LaunchedEffect
            val riderPoint = OsmGeoPoint(locationState.point.lat, locationState.point.lng)
            map.controller.animateTo(riderPoint, 16.5, 600L)
        }
    }

    // Zoom to route bounds when calculated
    LaunchedEffect(activeRoute?.id) {
        val route = activeRoute ?: return@LaunchedEffect
        val map = mapViewRef ?: return@LaunchedEffect
        if (route.coordinates.size >= 2) {
            val minLat = route.coordinates.minOf { it.lat }
            val maxLat = route.coordinates.maxOf { it.lat }
            val minLng = route.coordinates.minOf { it.lng }
            val maxLng = route.coordinates.maxOf { it.lng }

            val box = BoundingBox(maxLat + 0.005, maxLng + 0.005, minLat - 0.005, minLng - 0.005)
            map.zoomToBoundingBox(box, true, 80)
        }
    }
}

private fun createPinDrawable(context: Context, letter: String, color: Int): Drawable {
    val size = 96
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f - 8f, size / 2.5f, paint)

    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    canvas.drawCircle(size / 2f, size / 2f - 8f, size / 2.5f, borderPaint)

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.WHITE
        textSize = 40f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    val yPos = (size / 2f - 8f) - ((textPaint.descent() + textPaint.ascent()) / 2)
    canvas.drawText(letter, size / 2f, yPos, textPaint)

    return BitmapDrawable(context.resources, bitmap)
}

private fun createRiderDrawable(context: Context): Drawable {
    val size = 72
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        alpha = 100
        style = Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, glowPaint)

    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0284C7")
        style = Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 3.2f, bodyPaint)

    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 3.2f, borderPaint)

    // Heading arrow
    val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    val path = Path().apply {
        moveTo(size / 2f, 10f)
        lineTo(size / 2f - 10f, size / 2f - 4f)
        lineTo(size / 2f + 10f, size / 2f - 4f)
        close()
    }
    canvas.drawPath(path, arrowPaint)

    return BitmapDrawable(context.resources, bitmap)
}

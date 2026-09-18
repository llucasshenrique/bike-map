package com.ebike.router.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ebike.router.MainActivity
import com.ebike.router.R
import com.ebike.router.model.*
import com.ebike.router.physics.EBikePhysicsEngine
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class RiderLocationState(
    val point: GeoPoint = GeoPoint(-23.5505, -46.6333, 25.0), // Initial fallback
    val speedKmh: Double = 0.0,
    val headingDegrees: Float = 0f,
    val accuracyMeters: Float = 5f,
    val isSimulated: Boolean = false,
    val hasRealFix: Boolean = false
)

class LocationTrackerService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): LocationTrackerService = this@LocationTrackerService
    }

    private val binder = LocalBinder()

    companion object {
        const val ACTION_START_TRACKING = "com.ebike.router.action.START_TRACKING"
        const val ACTION_STOP_TRACKING = "com.ebike.router.action.STOP_TRACKING"
        const val ACTION_PAUSE_RIDE = "com.ebike.router.action.PAUSE_RIDE"
        const val ACTION_RESUME_RIDE = "com.ebike.router.action.RESUME_RIDE"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "ebike_ride_tracking_channel"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationManager: LocationManager? = null
    private var isTracking = false

    val physicsEngine = EBikePhysicsEngine()
    var audioGuidance: AudioGuidanceService? = null

    private val _locationState = MutableStateFlow(RiderLocationState())
    val locationState: StateFlow<RiderLocationState> = _locationState.asStateFlow()

    private val _telemetry = MutableStateFlow(LiveRideTelemetry())
    val telemetry: StateFlow<LiveRideTelemetry> = _telemetry.asStateFlow()

    private val _activeRoute = MutableStateFlow<RouteResult?>(null)
    val activeRoute: StateFlow<RouteResult?> = _activeRoute.asStateFlow()

    private val _isNavigating = MutableStateFlow(false)
    val isNavigating: StateFlow<Boolean> = _isNavigating.asStateFlow()

    private val _currentInstructionIndex = MutableStateFlow(0)
    val currentInstructionIndex: StateFlow<Int> = _currentInstructionIndex.asStateFlow()

    private val _currentInstruction = MutableStateFlow<TurnInstruction?>(null)
    val currentInstruction: StateFlow<TurnInstruction?> = _currentInstruction.asStateFlow()

    private val _distanceToNextManeuverMeters = MutableStateFlow(0)
    val distanceToNextManeuverMeters: StateFlow<Int> = _distanceToNextManeuverMeters.asStateFlow()

    private var isPaused = false
    private var simulatorJob: Job? = null
    private var tickerJob: Job? = null

    private var previousLocation: Location? = null
    private var accumulatedDistanceMeters = 0.0
    private var elapsedSeconds = 0
    private var speedSum = 0.0
    private var speedSamples = 0
    private var lastNotificationUpdateMs = 0L

    // Fused Location Callback
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            handleNewLocation(loc)
        }
    }

    // Android LocationManager fallback listener
    private val legacyListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            handleNewLocation(loc)
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        audioGuidance = AudioGuidanceService(this)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TRACKING -> {
                startForegroundServiceNotification()
                startTracking()
            }
            ACTION_STOP_TRACKING -> {
                stopNavigation()
                stopTracking()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_PAUSE_RIDE -> pauseRide()
            ACTION_RESUME_RIDE -> resumeRide()
        }
        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        createNotificationChannel()
        val notification = buildLiveRideNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        acquireWakeLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "EBikeRouter:RideTrackingWakeLock"
            )?.apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 60 * 1000L) // 10 hour timeout
            }
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rastreamento e Navegação do Pedal",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificação contínua de telemetria e navegação em tempo real"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun formatDuration(seconds: Int): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return if (mins >= 60) {
            val hrs = mins / 60
            val remMins = mins % 60
            String.format(Locale.getDefault(), "%d:%02d:%02d", hrs, remMins, secs)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", mins, secs)
        }
    }

    private fun buildLiveRideNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, LocationTrackerService::class.java).apply {
            action = ACTION_STOP_TRACKING
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val telem = _telemetry.value
        val isNav = _isNavigating.value

        val title = if (isNav) {
            val nextManeuver = _currentInstruction.value
            val dist = _distanceToNextManeuverMeters.value
            if (nextManeuver != null) {
                "Em ${dist}m: ${nextManeuver.text}"
            } else {
                "EBike Router • Navegando"
            }
        } else {
            if (isPaused) "EBike Router • Pedal Pausado" else "EBike Router • Pedal em Andamento"
        }

        val elapsedStr = formatDuration(telem.timeElapsedSeconds)
        val textContent = "Velocidade: ${telem.currentSpeedKmh} km/h • Distância: ${telem.distanceRiddenKm} km • Tempo: $elapsedStr"
        val subText = "Bateria: ${telem.batteryTelemetry.percentage}% • Modo: ${telem.activeAssist.name} • Potência: ${telem.motorPowerWatts}W"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(textContent)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$textContent\n$subText")
            )
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Encerrar Pedal", stopPendingIntent)
            .build()
    }

    private fun updateNotification(force: Boolean = false) {
        if (!isTracking) return
        val now = System.currentTimeMillis()
        if (!force && (now - lastNotificationUpdateMs) < 950) {
            return
        }
        lastNotificationUpdateMs = now
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.notify(NOTIFICATION_ID, buildLiveRideNotification())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleNewLocation(loc: Location) {
        if (_locationState.value.isSimulated) return

        var speed = if (loc.hasSpeed() && loc.speed > 0.1f) {
            (loc.speed * 3.6).toDouble()
        } else {
            0.0
        }

        val prev = previousLocation
        if (prev != null) {
            val dt = (loc.time - prev.time) / 1000.0
            val dist = loc.distanceTo(prev)
            if (speed <= 0.2 && dt in 0.4..8.0 && dist > 0.8) {
                val computed = (dist / dt) * 3.6
                if (computed < 90.0) {
                    speed = computed
                }
            }
            if (dist in 1.5..100.0 && !isPaused) {
                accumulatedDistanceMeters += dist
            }
        }
        previousLocation = loc

        val roundedSpeed = (speed * 10).toInt() / 10.0
        val heading = if (loc.hasBearing()) loc.bearing else _locationState.value.headingDegrees

        val newPoint = GeoPoint(loc.latitude, loc.longitude, loc.altitude)
        _locationState.value = RiderLocationState(
            point = newPoint,
            speedKmh = roundedSpeed,
            headingDegrees = heading,
            accuracyMeters = loc.accuracy,
            isSimulated = false,
            hasRealFix = true
        )

        handleRiderLocationUpdate(newPoint, roundedSpeed, heading)
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        if (isTracking) return
        isTracking = true

        acquireWakeLock()
        startTicker()

        try {
            // 1. Instantly check last known location from Fused client
            fusedLocationClient?.lastLocation?.addOnSuccessListener { loc ->
                if (loc != null && !_locationState.value.isSimulated) {
                    handleNewLocation(loc)
                }
            }

            // 2. Force fresh high accuracy location right now
            refreshCurrentLocation()

            // 3. Continuous Fused location updates (1000ms interval, high accuracy)
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(500L)
                .setMinUpdateDistanceMeters(1.0f)
                .build()

            fusedLocationClient?.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 4. Also register LocationManager as backup for GPS & Network
        try {
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            for (p in providers) {
                try {
                    if (locationManager?.isProviderEnabled(p) == true) {
                        locationManager?.requestLocationUpdates(p, 1000L, 1.0f, legacyListener, Looper.getMainLooper())
                        locationManager?.getLastKnownLocation(p)?.let { lastLoc ->
                            if (!_locationState.value.hasRealFix) {
                                handleNewLocation(lastLoc)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshCurrentLocation() {
        try {
            fusedLocationClient?.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                ?.addOnSuccessListener { loc ->
                    if (loc != null && !_locationState.value.isSimulated) {
                        handleNewLocation(loc)
                    }
                }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopTracking() {
        if (!isTracking) return
        try {
            fusedLocationClient?.removeLocationUpdates(locationCallback)
            locationManager?.removeUpdates(legacyListener)
            stopSimulator()
            stopTicker()
            releaseWakeLock()
            isTracking = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startTicker() {
        if (tickerJob != null) return
        tickerJob = serviceScope.launch {
            while (isActive) {
                delay(1000L)
                if (!isPaused) {
                    elapsedSeconds++
                    val curLoc = _locationState.value
                    val speed = curLoc.speedKmh

                    speedSum += speed
                    speedSamples++
                    val avgSpeed = if (speedSamples > 0) speedSum / speedSamples else 0.0

                    val route = _activeRoute.value
                    val distRiddenKm: Double
                    val distRemainingKm: Double
                    val timeRemaining: Int

                    if (route != null && _isNavigating.value) {
                        val progressRatio = min(1.0, elapsedSeconds / max(1.0, route.totalDurationSeconds.toDouble()))
                        distRiddenKm = (route.totalDistanceMeters * progressRatio) / 1000.0
                        distRemainingKm = max(0.0, (route.totalDistanceMeters / 1000.0) - distRiddenKm)
                        timeRemaining = max(0, route.totalDurationSeconds - elapsedSeconds)
                    } else {
                        distRiddenKm = accumulatedDistanceMeters / 1000.0
                        distRemainingKm = 0.0
                        timeRemaining = 0
                    }

                    val batTelem = physicsEngine.getBatteryTelemetry()

                    _telemetry.value = LiveRideTelemetry(
                        currentSpeedKmh = (speed * 10).toInt() / 10.0,
                        avgSpeedKmh = (avgSpeed * 10).toInt() / 10.0,
                        maxSpeedKmh = max(_telemetry.value.maxSpeedKmh, speed),
                        distanceRiddenKm = (distRiddenKm * 100).toInt() / 100.0,
                        distanceRemainingKm = (distRemainingKm * 10).toInt() / 10.0,
                        timeElapsedSeconds = elapsedSeconds,
                        timeRemainingSeconds = timeRemaining,
                        currentElevationM = curLoc.point.ele,
                        elevationGainedM = max(0.0, curLoc.point.ele - (route?.coordinates?.firstOrNull()?.ele ?: 20.0)),
                        currentGradePercent = 0.0,
                        motorPowerWatts = (speed * 10).coerceAtMost(physicsEngine.getConfig().motorMaxWatt).toInt(),
                        riderPowerWatts = (speed * 6).coerceAtLeast(40.0).toInt(),
                        activeAssist = physicsEngine.getConfig().activeAssist,
                        batteryTelemetry = batTelem,
                        headingDegrees = curLoc.headingDegrees,
                        isNavigating = _isNavigating.value,
                        isPaused = isPaused
                    )
                }
                updateNotification()
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    fun pauseRide() {
        isPaused = true
        _telemetry.value = _telemetry.value.copy(isPaused = true)
        updateNotification(force = true)
    }

    fun resumeRide() {
        isPaused = false
        _telemetry.value = _telemetry.value.copy(isPaused = false)
        updateNotification(force = true)
    }

    fun startNavigation(route: RouteResult) {
        _activeRoute.value = route
        _isNavigating.value = true
        _currentInstructionIndex.value = 0
        _currentInstruction.value = route.instructions.firstOrNull()
        isPaused = false

        audioGuidance?.speak("Navegação iniciada. ${route.instructions.firstOrNull()?.text ?: ""}", true)
        updateNotification(force = true)
    }

    fun stopNavigation() {
        _isNavigating.value = false
        _activeRoute.value = null
        _currentInstruction.value = null
        _distanceToNextManeuverMeters.value = 0
        stopSimulator()
        audioGuidance?.speak("Navegação finalizada.")
        updateNotification(force = true)
    }

    fun setAssistLevel(level: AssistLevel) {
        physicsEngine.setAssistLevel(level)
        _telemetry.value = _telemetry.value.copy(
            activeAssist = level,
            batteryTelemetry = physicsEngine.getBatteryTelemetry()
        )
        updateNotification(force = true)
    }

    fun setAudioMuted(muted: Boolean) {
        audioGuidance?.isMuted = muted
    }

    private fun handleRiderLocationUpdate(point: GeoPoint, speedKmh: Double, heading: Float) {
        _telemetry.value = _telemetry.value.copy(
            currentSpeedKmh = speedKmh,
            maxSpeedKmh = max(_telemetry.value.maxSpeedKmh, speedKmh),
            headingDegrees = heading,
            currentElevationM = point.ele
        )

        val route = _activeRoute.value ?: return
        if (!_isNavigating.value || route.instructions.isEmpty()) return

        val activeIdx = _currentInstructionIndex.value
        val currentManeuver = route.instructions.getOrNull(activeIdx) ?: return
        val distToManeuver = point.distanceTo(currentManeuver.point).toInt()

        _distanceToNextManeuverMeters.value = distToManeuver

        if (distToManeuver <= 50) {
            audioGuidance?.speak("Em 50 metros, ${currentManeuver.text}")
        }

        if (distToManeuver <= 15) {
            if (activeIdx < route.instructions.size - 1) {
                val nextIdx = activeIdx + 1
                _currentInstructionIndex.value = nextIdx
                val nextManeuver = route.instructions[nextIdx]
                _currentInstruction.value = nextManeuver
                audioGuidance?.speak(nextManeuver.text, true)
            } else {
                audioGuidance?.speak("Você chegou ao seu destino! Parabéns pelo trajeto.")
                stopNavigation()
            }
        }
    }

    fun startSimulator(route: RouteResult, speedMultiplier: Int = 2) {
        stopSimulator()
        val coords = route.coordinates
        if (coords.size < 2) return

        startNavigation(route)

        simulatorJob = serviceScope.launch {
            val intervalMs = (1000L / speedMultiplier).coerceAtLeast(200L)
            var idx = 0
            while (isActive && idx < coords.size) {
                val currentPt = coords[idx]
                val nextPt = coords.getOrNull(idx + 1) ?: currentPt
                val distMeters = currentPt.distanceTo(nextPt)
                val simulatedSpeed = if (distMeters > 0) (distMeters / (intervalMs / 1000.0)) * 3.6 else 24.0

                val simLoc = RiderLocationState(
                    point = currentPt,
                    speedKmh = (simulatedSpeed.coerceIn(15.0, 32.0) * 10).toInt() / 10.0,
                    headingDegrees = calculateBearing(currentPt, nextPt),
                    accuracyMeters = 2f,
                    isSimulated = true,
                    hasRealFix = true
                )
                _locationState.value = simLoc
                handleRiderLocationUpdate(simLoc.point, simLoc.speedKmh, simLoc.headingDegrees)
                delay(intervalMs)
                idx++
            }
        }
    }

    fun stopSimulator() {
        simulatorJob?.cancel()
        simulatorJob = null
    }

    private fun calculateBearing(from: GeoPoint, to: GeoPoint): Float {
        val lat1 = Math.toRadians(from.lat)
        val lon1 = Math.toRadians(from.lng)
        val lat2 = Math.toRadians(to.lat)
        val lon2 = Math.toRadians(to.lng)
        val dLon = lon2 - lon1
        val y = Math.sin(dLon) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)
        val brng = Math.toDegrees(Math.atan2(y, x))
        return ((brng + 360) % 360).toFloat()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTracking()
        stopSimulator()
        stopTicker()
        serviceScope.cancel()
        audioGuidance?.shutdown()
        releaseWakeLock()
    }
}

package com.ebike.router.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import com.ebike.router.model.GeoPoint
import com.ebike.router.model.RouteResult
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RiderLocationState(
    val point: GeoPoint = GeoPoint(-23.5505, -46.6333, 25.0), // Initial fallback
    val speedKmh: Double = 0.0,
    val headingDegrees: Float = 0f,
    val accuracyMeters: Float = 5f,
    val isSimulated: Boolean = false,
    val hasRealFix: Boolean = false
)

class LocationTrackerService(private val context: Context) {
    private val _locationState = MutableStateFlow(RiderLocationState())
    val locationState: StateFlow<RiderLocationState> = _locationState.asStateFlow()

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var locationManager: LocationManager? = null
    private var isTracking = false

    private var simulatorJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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

    private var previousLocation: Location? = null

    private fun handleNewLocation(loc: Location) {
        if (_locationState.value.isSimulated) return

        var speed = if (loc.hasSpeed() && loc.speed > 0.1f) {
            (loc.speed * 3.6).toDouble()
        } else {
            0.0
        }

        val prev = previousLocation
        if (speed <= 0.2 && prev != null) {
            val dt = (loc.time - prev.time) / 1000.0
            val dist = loc.distanceTo(prev)
            if (dt in 0.4..8.0 && dist > 0.8) {
                val computed = (dist / dt) * 3.6
                if (computed < 90.0) {
                    speed = computed
                }
            }
        }
        previousLocation = loc

        val roundedSpeed = (speed * 10).toInt() / 10.0

        _locationState.value = RiderLocationState(
            point = GeoPoint(loc.latitude, loc.longitude, loc.altitude),
            speedKmh = roundedSpeed,
            headingDegrees = if (loc.hasBearing()) loc.bearing else _locationState.value.headingDegrees,
            accuracyMeters = loc.accuracy,
            isSimulated = false,
            hasRealFix = true
        )
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        if (isTracking) return
        isTracking = true

        try {
            // 1. Instantly check last known location from Fused client
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
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

            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 4. Also register LocationManager as backup for GPS & Network
        try {
            locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
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
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
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
            fusedLocationClient.removeLocationUpdates(locationCallback)
            locationManager?.removeUpdates(legacyListener)
            isTracking = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun startSimulator(route: RouteResult, speedMultiplier: Int = 2) {
        stopSimulator()
        val coords = route.coordinates
        if (coords.size < 2) return

        simulatorJob = serviceScope.launch {
            val intervalMs = (1000L / speedMultiplier).coerceAtLeast(200L)
            var idx = 0
            while (isActive && idx < coords.size) {
                val currentPt = coords[idx]
                val nextPt = coords.getOrNull(idx + 1) ?: currentPt
                val distMeters = currentPt.distanceTo(nextPt)
                val simulatedSpeed = if (distMeters > 0) (distMeters / (intervalMs / 1000.0)) * 3.6 else 24.0

                _locationState.value = RiderLocationState(
                    point = currentPt,
                    speedKmh = (simulatedSpeed.coerceIn(15.0, 32.0) * 10).toInt() / 10.0,
                    headingDegrees = calculateBearing(currentPt, nextPt),
                    accuracyMeters = 2f,
                    isSimulated = true,
                    hasRealFix = true
                )
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
}

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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RiderLocationState(
    val point: GeoPoint = GeoPoint(-23.5505, -46.6333, 25.0), // Default São Paulo / global fallback
    val speedKmh: Double = 0.0,
    val headingDegrees: Float = 0f,
    val accuracyMeters: Float = 5f,
    val isSimulated: Boolean = false
)

class LocationTrackerService(private val context: Context) {
    private val _locationState = MutableStateFlow(RiderLocationState())
    val locationState: StateFlow<RiderLocationState> = _locationState.asStateFlow()

    private var locationManager: LocationManager? = null
    private var isTracking = false

    private var simulatorJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            if (_locationState.value.isSimulated) return
            _locationState.value = RiderLocationState(
                point = GeoPoint(loc.latitude, loc.longitude, loc.altitude),
                speedKmh = (loc.speed * 3.6).coerceAtLeast(0.0),
                headingDegrees = loc.bearing,
                accuracyMeters = loc.accuracy,
                isSimulated = false
            )
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        if (isTracking) return
        try {
            locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val providers = locationManager?.getProviders(true) ?: emptyList()
            val provider = when {
                providers.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                providers.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> providers.firstOrNull()
            }
            if (provider != null) {
                locationManager?.requestLocationUpdates(
                    provider,
                    1000L,
                    2.0f,
                    locationListener,
                    Looper.getMainLooper()
                )
                locationManager?.getLastKnownLocation(provider)?.let { lastLoc ->
                    _locationState.value = RiderLocationState(
                        point = GeoPoint(lastLoc.latitude, lastLoc.longitude, lastLoc.altitude),
                        speedKmh = (lastLoc.speed * 3.6).coerceAtLeast(0.0),
                        headingDegrees = lastLoc.bearing,
                        accuracyMeters = lastLoc.accuracy,
                        isSimulated = false
                    )
                }
                isTracking = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopTracking() {
        if (!isTracking) return
        try {
            locationManager?.removeUpdates(locationListener)
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
                    isSimulated = true
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

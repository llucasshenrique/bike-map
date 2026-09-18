package com.ebike.router.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.ebike.router.model.AssistLevel
import com.ebike.router.model.ElevationPoint
import com.ebike.router.model.GeoPoint
import com.ebike.router.model.ManeuverType
import com.ebike.router.model.RouteResult
import com.ebike.router.model.RoutingProfile
import com.ebike.router.model.TurnInstruction
import com.ebike.router.service.LocationTrackerService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Unit tests for the foreground-service binding logic added to BikeMapViewModel: the
 * ViewModel now binds to LocationTrackerService instead of instantiating it directly, and
 * its navigation/assist-level actions are delegated to the bound service once connected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BikeMapViewModelTest {

    private lateinit var viewModel: BikeMapViewModel

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()

        // Robolectric doesn't auto-create/bind real Services, so pre-register the binder
        // a real LocationTrackerService instance would return, exactly like the OS does
        // when MainActivity's bindService() call resolves the manifest-declared service.
        val serviceController = Robolectric.buildService(LocationTrackerService::class.java).create()
        val binder = serviceController.get().onBind(Intent(app, LocationTrackerService::class.java))
        shadowOf(app).setComponentNameAndServiceForBindService(
            ComponentName(app, LocationTrackerService::class.java),
            binder
        )

        viewModel = BikeMapViewModel(app)
        // Let the ServiceConnection callback (posted to the main looper) run so the
        // ViewModel finishes binding to LocationTrackerService.
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun sampleRoute(): RouteResult {
        val instructions = listOf(
            TurnInstruction(
                index = 0,
                maneuver = ManeuverType.DEPART,
                text = "Siga em frente",
                streetName = "Rua A",
                distanceMeters = 100,
                durationSeconds = 20,
                point = GeoPoint(-23.5505, -46.6333),
                gradePercent = 0.0,
                energyWh = 2.0,
                cumulativeDistanceKm = 0.0
            )
        )
        return RouteResult(
            id = "route_test",
            totalDistanceMeters = 100,
            totalDurationSeconds = 20,
            totalEnergyWh = 2.0,
            elevationGainM = 0,
            elevationLossM = 0,
            maxGradePercent = 0.0,
            avgGradePercent = 0.0,
            coordinates = listOf(GeoPoint(-23.5505, -46.6333)),
            elevationProfile = listOf(ElevationPoint(0.0, 20.0, 0.0, -23.5505, -46.6333)),
            instructions = instructions,
            segments = emptyList(),
            batteryDrainPercent = 0.5,
            estimatedBatteryRemainingWh = 500,
            batteryRemainingPercent = 90,
            profile = RoutingProfile.EFFICIENT
        )
    }

    @Test
    fun `binding connects the tracker service so it is no longer null`() {
        assertNotNull(
            "ViewModel should bind to LocationTrackerService instead of instantiating it directly",
            viewModel.trackerService.value
        )
    }

    @Test
    fun `starting navigation delegates to the bound tracker service`() {
        val route = sampleRoute()

        viewModel.startNavigation(route)

        val trackerService = viewModel.trackerService.value
        assertNotNull(trackerService)
        assertTrue("Navigation start should be forwarded to the service", trackerService!!.isNavigating.value)
        assertEquals(route, trackerService.activeRoute.value)
    }

    @Test
    fun `stopping navigation delegates to the bound tracker service`() {
        val route = sampleRoute()
        viewModel.startNavigation(route)

        viewModel.stopNavigation()

        assertFalse(viewModel.isNavigating.value)
        val trackerService = viewModel.trackerService.value
        assertNotNull(trackerService)
        assertFalse(trackerService!!.isNavigating.value)
    }

    @Test
    fun `setAssistLevel delegates to the bound tracker service`() {
        viewModel.setAssistLevel(AssistLevel.TURBO)

        val trackerService = viewModel.trackerService.value
        assertNotNull(trackerService)
        assertEquals(AssistLevel.TURBO, trackerService!!.telemetry.value.activeAssist)
        assertEquals(AssistLevel.TURBO, viewModel.telemetry.value.activeAssist)
    }

    @Test
    fun `removeWaypoint refuses to drop below two waypoints`() {
        assertEquals(2, viewModel.waypoints.value.size)

        viewModel.removeWaypoint(0)

        assertEquals("Cannot remove a waypoint when only the minimum of 2 remain", 2, viewModel.waypoints.value.size)
    }

    @Test
    fun `addWaypoint appends a new stop and reindexes letters`() {
        viewModel.addWaypoint(GeoPoint(-23.55, -46.63), "Extra Stop")

        val waypoints = viewModel.waypoints.value
        assertEquals(3, waypoints.size)
        assertEquals("A", waypoints[0].letter)
        assertEquals("B", waypoints[1].letter)
        assertEquals("C", waypoints[2].letter)
        assertEquals("Extra Stop", waypoints[2].label)
    }

    @Test
    fun `hasEnoughValidPoints requires at least two waypoints with coordinates`() {
        assertFalse(viewModel.hasEnoughValidPoints())

        viewModel.setWaypoint(0, GeoPoint(-23.55, -46.63))
        assertFalse(viewModel.hasEnoughValidPoints())

        viewModel.setWaypoint(1, GeoPoint(-23.56, -46.64))
        assertTrue(viewModel.hasEnoughValidPoints())
    }

}

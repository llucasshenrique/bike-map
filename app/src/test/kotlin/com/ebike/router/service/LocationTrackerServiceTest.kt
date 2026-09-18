package com.ebike.router.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.ebike.router.model.AssistLevel
import com.ebike.router.model.ElevationPoint
import com.ebike.router.model.GeoPoint
import com.ebike.router.model.ManeuverType
import com.ebike.router.model.RouteResult
import com.ebike.router.model.RoutingProfile
import com.ebike.router.model.TurnInstruction
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Unit tests for the foreground-service behavior added when LocationTrackerService was
 * converted from a plain helper class into an Android Service (start/stop tracking,
 * persistent notification content, pause/resume, and navigation state transitions).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationTrackerServiceTest {

    private fun sampleRoute(): RouteResult {
        val instructions = listOf(
            TurnInstruction(
                index = 0,
                maneuver = ManeuverType.DEPART,
                text = "Siga em frente na Rua Principal",
                streetName = "Rua Principal",
                distanceMeters = 200,
                durationSeconds = 40,
                point = GeoPoint(-23.5505, -46.6333),
                gradePercent = 0.0,
                energyWh = 5.0,
                cumulativeDistanceKm = 0.0
            ),
            TurnInstruction(
                index = 1,
                maneuver = ManeuverType.TURN_RIGHT,
                text = "Vire à direita na Av. Central",
                streetName = "Av. Central",
                distanceMeters = 300,
                durationSeconds = 60,
                point = GeoPoint(-23.5520, -46.6300),
                gradePercent = 0.0,
                energyWh = 8.0,
                cumulativeDistanceKm = 0.2
            )
        )
        return RouteResult(
            id = "route_1",
            totalDistanceMeters = 500,
            totalDurationSeconds = 100,
            totalEnergyWh = 13.0,
            elevationGainM = 5,
            elevationLossM = 2,
            maxGradePercent = 2.0,
            avgGradePercent = 0.5,
            coordinates = listOf(GeoPoint(-23.5505, -46.6333), GeoPoint(-23.5520, -46.6300)),
            elevationProfile = listOf(ElevationPoint(0.0, 20.0, 0.0, -23.5505, -46.6333)),
            instructions = instructions,
            segments = emptyList(),
            batteryDrainPercent = 1.5,
            estimatedBatteryRemainingWh = 550,
            batteryRemainingPercent = 88,
            profile = RoutingProfile.EFFICIENT
        )
    }

    @Test
    fun `starting the foreground service posts a persistent ongoing notification`() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            LocationTrackerService::class.java
        ).setAction(LocationTrackerService.ACTION_START_TRACKING)

        val controller = Robolectric.buildService(LocationTrackerService::class.java, intent)
        controller.create().startCommand(0, 1)

        val notificationManager = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowManager = shadowOf(notificationManager)

        val posted = shadowManager.getNotification(LocationTrackerService.NOTIFICATION_ID)
        assertNotNull("Starting tracking should post the ongoing ride notification", posted)
        assertTrue("Notification must be ongoing so it cannot be swiped away mid-ride", posted.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)

        controller.destroy()
    }

    @Test
    fun `pausing the ride updates telemetry and the notification title`() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            LocationTrackerService::class.java
        ).setAction(LocationTrackerService.ACTION_START_TRACKING)
        val controller = Robolectric.buildService(LocationTrackerService::class.java, intent)
        val service = controller.create().startCommand(0, 1).get()

        service.pauseRide()
        assertTrue("pauseRide should flip telemetry.isPaused", service.telemetry.value.isPaused)

        val notificationManager = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val paused = shadowOf(notificationManager).getNotification(LocationTrackerService.NOTIFICATION_ID)
        assertNotNull(paused)
        assertTrue(
            "Paused notification title should reflect the paused state",
            paused.extras.getString(android.app.Notification.EXTRA_TITLE)?.contains("Pausado") == true
        )

        service.resumeRide()
        assertFalse("resumeRide should clear telemetry.isPaused", service.telemetry.value.isPaused)

        controller.destroy()
    }

    @Test
    fun `starting navigation exposes the route and first instruction`() {
        val controller = Robolectric.buildService(LocationTrackerService::class.java)
        val service = controller.create().get()
        val route = sampleRoute()

        service.startNavigation(route)

        assertTrue(service.isNavigating.value)
        assertEquals(route, service.activeRoute.value)
        assertEquals(0, service.currentInstructionIndex.value)
        assertEquals(route.instructions.first(), service.currentInstruction.value)

        controller.destroy()
    }

    @Test
    fun `stopping navigation clears route and maneuver state`() {
        val controller = Robolectric.buildService(LocationTrackerService::class.java)
        val service = controller.create().get()
        val route = sampleRoute()

        service.startNavigation(route)
        service.stopNavigation()

        assertFalse(service.isNavigating.value)
        assertNull(service.activeRoute.value)
        assertNull(service.currentInstruction.value)
        assertEquals(0, service.distanceToNextManeuverMeters.value)

        controller.destroy()
    }

    @Test
    fun `setAssistLevel updates telemetry active assist level`() {
        val controller = Robolectric.buildService(LocationTrackerService::class.java)
        val service = controller.create().get()

        service.setAssistLevel(AssistLevel.SPORT)

        assertEquals(AssistLevel.SPORT, service.telemetry.value.activeAssist)

        controller.destroy()
    }

    @Test
    fun `binder returns the same running service instance`() {
        val controller = Robolectric.buildService(LocationTrackerService::class.java)
        val service = controller.create().get()

        val binder = service.onBind(Intent()) as LocationTrackerService.LocalBinder

        assertSame(service, binder.getService())

        controller.destroy()
    }

    @Test
    fun `onDestroy releases resources without throwing`() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            LocationTrackerService::class.java
        ).setAction(LocationTrackerService.ACTION_START_TRACKING)
        val controller = Robolectric.buildService(LocationTrackerService::class.java, intent)
        controller.create().startCommand(0, 1)

        controller.destroy()
    }
}

package com.ebike.router

import com.ebike.router.model.GeoPoint
import com.ebike.router.model.ManeuverType
import com.ebike.router.model.RoutingProfile
import com.ebike.router.physics.EBikePhysicsEngine
import com.ebike.router.service.GraphRouterService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GraphRouterServiceTest {
    private lateinit var physicsEngine: EBikePhysicsEngine
    private lateinit var routerService: GraphRouterService

    @Before
    fun setUp() {
        physicsEngine = EBikePhysicsEngine()
        routerService = GraphRouterService(physicsEngine)
    }

    @Test
    fun testNormalBikeModeZeroEnergyAndNoAssistInstructions() = runBlocking {
        val points = listOf(
            GeoPoint(-23.5505, -46.6333, 20.0),
            GeoPoint(-23.5550, -46.6380, 25.0)
        )

        val routes = routerService.calculateMultipleRoutes(points, RoutingProfile.EFFICIENT, isEBikeMode = false)
        assertTrue("Should produce at least one route", routes.isNotEmpty())

        val route = routes.first()
        assertEquals("Normal bike mode must report 0.0 Wh energy drain", 0.0, route.totalEnergyWh, 0.001)
        assertEquals("Normal bike mode must report 0.0% battery drain", 0.0, route.batteryDrainPercent, 0.001)
        assertEquals("Normal bike mode battery remaining should be 0", 0, route.estimatedBatteryRemainingWh)

        // Verify climb instructions do not mention motor assist
        for (instruction in route.instructions) {
            if (instruction.maneuver == ManeuverType.CLIMB_AHEAD) {
                assertTrue("Instruction should mention gears, not assist", instruction.text.contains("marcha"))
                assertFalse("Instruction should not mention assist", instruction.text.contains("assistência"))
            }
        }
    }

    @Test
    fun testEBikeModeCalculatesEnergyAndAssist() = runBlocking {
        val points = listOf(
            GeoPoint(-23.5505, -46.6333, 20.0),
            GeoPoint(-23.5550, -46.6380, 25.0)
        )

        val routes = routerService.calculateMultipleRoutes(points, RoutingProfile.EFFICIENT, isEBikeMode = true)
        assertTrue("Should produce at least one route", routes.isNotEmpty())

        val route = routes.first()
        assertTrue("E-bike mode must report positive energy drain", route.totalEnergyWh > 0.0)
        assertTrue("E-bike mode must report positive battery drain percent", route.batteryDrainPercent > 0.0)
        assertTrue("E-bike mode battery remaining should be positive", route.estimatedBatteryRemainingWh > 0)
    }

    @Test
    fun testEmptyOrSinglePointReturnsEmptyList() = runBlocking {
        val emptyResult = routerService.calculateMultipleRoutes(emptyList(), RoutingProfile.EFFICIENT)
        assertTrue("Empty points list must return empty routes", emptyResult.isEmpty())

        val singlePointResult = routerService.calculateMultipleRoutes(
            listOf(GeoPoint(-23.5505, -46.6333, 20.0)),
            RoutingProfile.EFFICIENT
        )
        assertTrue("Single point list must return empty routes", singlePointResult.isEmpty())
    }

    @Test
    fun testTogglingModeOffMidRideStopsBatteryDrainOnNextRouteCalculation() = runBlocking {
        val points = listOf(
            GeoPoint(-23.5505, -46.6333, 20.0),
            GeoPoint(-23.5550, -46.6380, 25.0)
        )

        // Rider starts navigating with e-bike mode on (leftover assist state on the
        // shared physics engine instance, same as the long-lived engine held by the
        // app's ViewModel across the whole ride).
        physicsEngine.setAssistLevel(com.ebike.router.model.AssistLevel.TURBO)
        val ebikeRoutes = routerService.calculateMultipleRoutes(points, RoutingProfile.EFFICIENT, isEBikeMode = true)
        val ebikeRoute = ebikeRoutes.first()
        assertTrue("Energy should be positive while e-bike mode is on", ebikeRoute.totalEnergyWh > 0.0)

        // Rider toggles e-bike mode off mid-ride; router must gate on the flag passed
        // in, not leftover engine state (activeAssist is still TURBO on the engine).
        val normalRoutes = routerService.calculateMultipleRoutes(points, RoutingProfile.EFFICIENT, isEBikeMode = false)
        val normalRoute = normalRoutes.first()
        assertEquals(
            "Energy must be 0 immediately after toggling e-bike mode off mid-ride, regardless of leftover engine assist state",
            0.0,
            normalRoute.totalEnergyWh,
            0.001
        )
        assertEquals(0.0, normalRoute.batteryDrainPercent, 0.001)
        assertEquals(0, normalRoute.estimatedBatteryRemainingWh)
    }

    @Test
    fun testZeroBatteryCapacityRouteDoesNotCrashOrProduceInvalidPercentages() = runBlocking {
        val points = listOf(
            GeoPoint(-23.5505, -46.6333, 20.0),
            GeoPoint(-23.5550, -46.6380, 25.0)
        )

        physicsEngine.updateConfig(physicsEngine.getConfig().copy(batteryCapacityWh = 0.0, currentBatteryWh = 0.0))

        val routes = routerService.calculateMultipleRoutes(points, RoutingProfile.EFFICIENT, isEBikeMode = true)
        assertTrue("Should still produce at least one route", routes.isNotEmpty())

        val route = routes.first()
        assertFalse("Battery drain percent must not be NaN with 0 capacity", route.batteryDrainPercent.isNaN())
        assertFalse("Battery drain percent must not be infinite with 0 capacity", route.batteryDrainPercent.isInfinite())
        assertEquals(
            "Battery drain percent must be reported as 0 (not computed) when capacity is 0",
            0.0,
            route.batteryDrainPercent,
            0.001
        )
        assertEquals(
            "Remaining battery percent must be reported as 0 when capacity is 0",
            0,
            route.batteryRemainingPercent
        )
    }

    @Test
    fun testDirectRouteSegmentNamingAndProfileVariations() = runBlocking {
        val points = listOf(
            GeoPoint(-23.5505, -46.6333, 20.0),
            GeoPoint(-23.5550, -46.6380, 25.0)
        )

        for (profile in RoutingProfile.values()) {
            val normalRoutes = routerService.calculateMultipleRoutes(points, profile, isEBikeMode = false)
            assertTrue("Normal bike mode route must be generated for profile $profile", normalRoutes.isNotEmpty())
            val normalRoute = normalRoutes.first()
            assertEquals("Normal bike mode energy must be 0", 0.0, normalRoute.totalEnergyWh, 0.001)

            val eBikeRoutes = routerService.calculateMultipleRoutes(points, profile, isEBikeMode = true)
            assertTrue("E-bike route must be generated for profile $profile", eBikeRoutes.isNotEmpty())
            val eBikeRoute = eBikeRoutes.first()
            assertTrue("E-bike energy must be positive for profile $profile", eBikeRoute.totalEnergyWh > 0.0)
        }
    }
}

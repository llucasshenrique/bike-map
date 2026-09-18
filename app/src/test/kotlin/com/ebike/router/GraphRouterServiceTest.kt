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

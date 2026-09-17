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
}

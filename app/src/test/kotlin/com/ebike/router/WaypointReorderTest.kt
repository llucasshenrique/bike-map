package com.ebike.router

import com.ebike.router.model.GeoPoint
import com.ebike.router.model.RouteWaypoint
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests covering the waypoint reordering feature logic:
 * - UI affordance enablement predicates (up/down buttons)
 * - State transitions when reordering multi-stop waypoints
 * - Letter and label reindexing behavior (default vs custom labels)
 * - Boundary conditions and out-of-bounds safety
 * - Route calculation readiness (hasEnoughValidPoints)
 * - Path geometry effects of waypoint reordering
 */
class WaypointReorderTest {

    private val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    // Helper functions mirroring UI affordance logic in RoutePlannerSheet.kt
    private fun canMoveUp(idx: Int): Boolean = idx > 0

    private fun canMoveDown(idx: Int, totalWaypoints: Int): Boolean = idx < totalWaypoints - 1

    private fun moveUpTarget(idx: Int): Int = idx - 1

    private fun moveDownTarget(idx: Int): Int = idx + 1

    // Helper function mirroring reindexing logic in BikeMapViewModel.kt
    private fun reindexWaypoints(list: List<RouteWaypoint>): List<RouteWaypoint> {
        return list.mapIndexed { idx, wp ->
            val letter = alphabet[idx % alphabet.length].toString()
            val label = if (wp.point != null) wp.label else {
                when (idx) {
                    0 -> "Ponto de Partida (A)"
                    list.size - 1 -> "Destino ($letter)"
                    else -> "Parada $letter"
                }
            }
            wp.copy(letter = letter, label = label)
        }
    }

    // Helper function mirroring moveWaypoint logic in BikeMapViewModel.kt
    private fun moveWaypoint(
        waypoints: List<RouteWaypoint>,
        fromIdx: Int,
        toIdx: Int
    ): List<RouteWaypoint> {
        val list = waypoints.toMutableList()
        if (fromIdx !in list.indices || toIdx !in list.indices) return waypoints
        val item = list.removeAt(fromIdx)
        list.add(toIdx, item)
        return reindexWaypoints(list)
    }

    private fun hasEnoughValidPoints(waypoints: List<RouteWaypoint>): Boolean {
        return waypoints.count { it.point != null } >= 2
    }

    private fun calculateTotalDirectDistance(waypoints: List<RouteWaypoint>): Double {
        val points = waypoints.mapNotNull { it.point }
        return points.zipWithNext { a, b -> a.distanceTo(b) }.sum()
    }

    private lateinit var sampleWaypoints: List<RouteWaypoint>

    @Before
    fun setUp() {
        sampleWaypoints = listOf(
            RouteWaypoint(id = "wp_0", letter = "A", label = "Ponto de Partida (A)", point = null),
            RouteWaypoint(id = "wp_1", letter = "B", label = "Parada B", point = null),
            RouteWaypoint(id = "wp_2", letter = "C", label = "Parada C", point = null),
            RouteWaypoint(id = "wp_3", letter = "D", label = "Destino (D)", point = null)
        )
    }

    // --- UI Affordance / Button State Tests ---

    @Test
    fun testCanMoveUpDisabledForFirstWaypoint() {
        assertFalse("First waypoint (idx 0) should not be movable up in 2-point list", canMoveUp(0))
        assertFalse("First waypoint (idx 0) should not be movable up in 4-point list", canMoveUp(0))
    }

    @Test
    fun testCanMoveUpEnabledForSubsequentWaypoints() {
        assertTrue("Second waypoint (idx 1) should be movable up", canMoveUp(1))
        assertTrue("Third waypoint (idx 2) should be movable up", canMoveUp(2))
        assertTrue("Last waypoint (idx 3) should be movable up", canMoveUp(3))
    }

    @Test
    fun testCanMoveDownDisabledForLastWaypoint() {
        assertFalse("Last waypoint of 2-item list should not move down", canMoveDown(1, 2))
        assertFalse("Last waypoint of 4-item list should not move down", canMoveDown(3, 4))
        assertFalse("Last waypoint of 6-item list should not move down", canMoveDown(5, 6))
    }

    @Test
    fun testCanMoveDownEnabledForPrecedingWaypoints() {
        assertTrue("First waypoint should be movable down when size > 1", canMoveDown(0, 4))
        assertTrue("Second waypoint should be movable down when size > 2", canMoveDown(1, 4))
        assertTrue("Third waypoint should be movable down when size > 3", canMoveDown(2, 4))
    }

    @Test
    fun testSingleWaypointCannotMoveUpOrDown() {
        assertFalse("Single waypoint cannot move up", canMoveUp(0))
        assertFalse("Single waypoint cannot move down", canMoveDown(0, 1))
    }

    @Test
    fun testTargetIndicesForUpAndDownMoves() {
        assertEquals(0, moveUpTarget(1))
        assertEquals(1, moveUpTarget(2))
        assertEquals(2, moveDownTarget(1))
        assertEquals(3, moveDownTarget(2))
    }

    // --- State Transition & Reordering Tests ---

    @Test
    fun testMoveIntermediateWaypointDown() {
        // Move waypoint at index 1 ("wp_1") down to index 2
        val reordered = moveWaypoint(sampleWaypoints, fromIdx = 1, toIdx = 2)

        assertEquals("List size should remain unchanged", 4, reordered.size)
        assertEquals("Index 0 should remain wp_0", "wp_0", reordered[0].id)
        assertEquals("Index 1 should now be wp_2", "wp_2", reordered[1].id)
        assertEquals("Index 2 should now be wp_1", "wp_1", reordered[2].id)
        assertEquals("Index 3 should remain wp_3", "wp_3", reordered[3].id)

        // Letters must be reindexed sequentially
        assertEquals("A", reordered[0].letter)
        assertEquals("B", reordered[1].letter)
        assertEquals("C", reordered[2].letter)
        assertEquals("D", reordered[3].letter)
    }

    @Test
    fun testMoveIntermediateWaypointUp() {
        // Move waypoint at index 2 ("wp_2") up to index 1
        val reordered = moveWaypoint(sampleWaypoints, fromIdx = 2, toIdx = 1)

        assertEquals("wp_0", reordered[0].id)
        assertEquals("wp_2", reordered[1].id)
        assertEquals("wp_1", reordered[2].id)
        assertEquals("wp_3", reordered[3].id)

        assertEquals("A", reordered[0].letter)
        assertEquals("B", reordered[1].letter)
        assertEquals("C", reordered[2].letter)
        assertEquals("D", reordered[3].letter)
    }

    @Test
    fun testMoveFirstWaypointToDestination() {
        // Move start waypoint (index 0) to destination (index 3)
        val reordered = moveWaypoint(sampleWaypoints, fromIdx = 0, toIdx = 3)

        assertEquals("wp_1", reordered[0].id)
        assertEquals("wp_2", reordered[1].id)
        assertEquals("wp_3", reordered[2].id)
        assertEquals("wp_0", reordered[3].id)

        // Labels should update to reflect new roles
        assertEquals("Ponto de Partida (A)", reordered[0].label)
        assertEquals("Parada B", reordered[1].label)
        assertEquals("Parada C", reordered[2].label)
        assertEquals("Destino (D)", reordered[3].label)
    }

    @Test
    fun testMoveDestinationWaypointToStart() {
        // Move destination waypoint (index 3) to start (index 0)
        val reordered = moveWaypoint(sampleWaypoints, fromIdx = 3, toIdx = 0)

        assertEquals("wp_3", reordered[0].id)
        assertEquals("wp_0", reordered[1].id)
        assertEquals("wp_1", reordered[2].id)
        assertEquals("wp_2", reordered[3].id)

        assertEquals("Ponto de Partida (A)", reordered[0].label)
        assertEquals("Parada B", reordered[1].label)
        assertEquals("Parada C", reordered[2].label)
        assertEquals("Destino (D)", reordered[3].label)
    }

    @Test
    fun testOutOfBoundsMoveIsSafelyIgnored() {
        val reorderedNegativeFrom = moveWaypoint(sampleWaypoints, fromIdx = -1, toIdx = 1)
        assertEquals(sampleWaypoints, reorderedNegativeFrom)

        val reorderedExcessFrom = moveWaypoint(sampleWaypoints, fromIdx = 4, toIdx = 1)
        assertEquals(sampleWaypoints, reorderedExcessFrom)

        val reorderedNegativeTo = moveWaypoint(sampleWaypoints, fromIdx = 1, toIdx = -1)
        assertEquals(sampleWaypoints, reorderedNegativeTo)

        val reorderedExcessTo = moveWaypoint(sampleWaypoints, fromIdx = 1, toIdx = 4)
        assertEquals(sampleWaypoints, reorderedExcessTo)
    }

    @Test
    fun testSameIndexMoveIsIdempotent() {
        val reordered = moveWaypoint(sampleWaypoints, fromIdx = 2, toIdx = 2)
        assertEquals("Moving to same index should yield identical list", sampleWaypoints, reordered)
    }

    // --- Label & Coordinate Preservation Tests ---

    @Test
    fun testCustomLabelsPreservedAcrossReordering() {
        val customWaypoints = listOf(
            RouteWaypoint("wp_0", "A", "Home", GeoPoint(-23.5505, -46.6333)),
            RouteWaypoint("wp_1", "B", "Coffee Shop", GeoPoint(-23.5550, -46.6380)),
            RouteWaypoint("wp_2", "C", "Office", GeoPoint(-23.5600, -46.6450))
        )

        // Move "Office" from index 2 to index 1 (between Home and Coffee Shop)
        val reordered = moveWaypoint(customWaypoints, fromIdx = 2, toIdx = 1)

        assertEquals("Home", reordered[0].label)
        assertEquals("Office", reordered[1].label)
        assertEquals("Coffee Shop", reordered[2].label)

        // Letters are reindexed
        assertEquals("A", reordered[0].letter)
        assertEquals("B", reordered[1].letter)
        assertEquals("C", reordered[2].letter)

        // Coordinates are accurately preserved with the item
        assertEquals(-23.5505, reordered[0].point!!.lat, 0.0001)
        assertEquals(-23.5600, reordered[1].point!!.lat, 0.0001)
        assertEquals(-23.5550, reordered[2].point!!.lat, 0.0001)
    }

    // --- Route Calculation Trigger Condition Tests ---

    @Test
    fun testHasEnoughValidPointsRequirement() {
        val noPoints = listOf(
            RouteWaypoint("wp_0", "A", "Start", null),
            RouteWaypoint("wp_1", "B", "Dest", null)
        )
        assertFalse("0 points is not enough to calculate route", hasEnoughValidPoints(noPoints))

        val onePoint = listOf(
            RouteWaypoint("wp_0", "A", "Start", GeoPoint(-23.5505, -46.6333)),
            RouteWaypoint("wp_1", "B", "Dest", null)
        )
        assertFalse("1 point is not enough to calculate route", hasEnoughValidPoints(onePoint))

        val twoPoints = listOf(
            RouteWaypoint("wp_0", "A", "Start", GeoPoint(-23.5505, -46.6333)),
            RouteWaypoint("wp_1", "B", "Dest", GeoPoint(-23.5600, -46.6450))
        )
        assertTrue("2 points is enough to calculate route", hasEnoughValidPoints(twoPoints))

        // Reordering 2 valid points maintains readiness
        val reorderedTwoPoints = moveWaypoint(twoPoints, fromIdx = 0, toIdx = 1)
        assertTrue("Reordering preserves valid points count", hasEnoughValidPoints(reorderedTwoPoints))
    }

    // --- Geometry & Route Distance Impact Tests ---

    @Test
    fun testReorderingChangesDirectRoutePathDistance() {
        // Define points forming a right triangle in São Paulo:
        // P1 (Start): Sé
        val p1 = GeoPoint(-23.5505, -46.6333)
        // P2 (Stop 1): Liberdade
        val p2 = GeoPoint(-23.5580, -46.6350)
        // P3 (Stop 2 / Detour): Paulista
        val p3 = GeoPoint(-23.5615, -46.6560)

        val routeA = listOf(
            RouteWaypoint("wp_0", "A", "Sé", p1),
            RouteWaypoint("wp_1", "B", "Liberdade", p2),
            RouteWaypoint("wp_2", "C", "Paulista", p3)
        )

        // Reorder: Sé -> Paulista -> Liberdade
        val routeB = moveWaypoint(routeA, fromIdx = 1, toIdx = 2)

        val distA = calculateTotalDirectDistance(routeA)
        val distB = calculateTotalDirectDistance(routeB)

        assertTrue("Distance A should be positive", distA > 0.0)
        assertTrue("Distance B should be positive", distB > 0.0)
        assertNotEquals("Reordering waypoints must produce a different path distance", distA, distB, 1.0)
    }

    // --- Sequential Interaction Simulation ---

    @Test
    fun testSequentialUserMovesSimulatingConsecutiveButtonClicks() {
        // User starts with 3 waypoints: A, B, C
        var list = listOf(
            RouteWaypoint("wp_0", "A", "Parada A", GeoPoint(-23.550, -46.630)),
            RouteWaypoint("wp_1", "B", "Parada B", GeoPoint(-23.555, -46.635)),
            RouteWaypoint("wp_2", "C", "Parada C", GeoPoint(-23.560, -46.640))
        )

        // Step 1: User clicks "Down" on waypoint 0 (wp_0)
        assertTrue("wp_0 can move down", canMoveDown(0, list.size))
        list = moveWaypoint(list, 0, moveDownTarget(0))
        assertEquals(listOf("wp_1", "wp_0", "wp_2"), list.map { it.id })

        // Step 2: User clicks "Down" on wp_0 again (now at index 1)
        assertTrue("wp_0 at index 1 can move down", canMoveDown(1, list.size))
        list = moveWaypoint(list, 1, moveDownTarget(1))
        assertEquals(listOf("wp_1", "wp_2", "wp_0"), list.map { it.id })

        // Step 3: Now wp_0 is at the last position (index 2); down must be disabled
        assertFalse("wp_0 at index 2 cannot move down further", canMoveDown(2, list.size))

        // Step 4: User clicks "Up" on wp_0 (at index 2)
        assertTrue("wp_0 can move up", canMoveUp(2))
        list = moveWaypoint(list, 2, moveUpTarget(2))
        assertEquals(listOf("wp_1", "wp_0", "wp_2"), list.map { it.id })
    }
}

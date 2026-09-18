package com.ebike.router.service

import com.ebike.router.model.GeoPoint
import com.ebike.router.model.RouteResult
import com.ebike.router.model.RouteSegment
import org.junit.Assert.*
import org.junit.Test

class RouteClassifierTest {

    private fun route(
        id: String,
        durationSeconds: Int,
        elevationGainM: Int,
        energyWh: Double,
        distanceMeters: Int,
        streetName: String = "Avenida Teste"
    ): RouteResult = RouteResult(
        id = id,
        totalDistanceMeters = distanceMeters,
        totalDurationSeconds = durationSeconds,
        totalEnergyWh = energyWh,
        elevationGainM = elevationGainM,
        elevationLossM = elevationGainM,
        maxGradePercent = 5.0,
        avgGradePercent = 1.0,
        coordinates = listOf(GeoPoint(0.0, 0.0)),
        elevationProfile = emptyList(),
        instructions = emptyList(),
        segments = listOf(
            RouteSegment(
                fromNodeId = "a",
                toNodeId = "b",
                name = streetName,
                distanceMeters = distanceMeters,
                gradePercent = 1.0,
                elevationGainM = elevationGainM,
                elevationLossM = elevationGainM,
                coordinates = emptyList(),
                estimatedEnergyWh = energyWh,
                estimatedTimeSeconds = durationSeconds
            )
        ),
        batteryDrainPercent = 1.0,
        estimatedBatteryRemainingWh = 100,
        batteryRemainingPercent = 90
    )

    @Test
    fun testSingleRouteLabeledRecommended() {
        val result = RouteClassifier.classify(listOf(route("a", 600, 50, 20.0, 5000)))
        assertEquals(1, result.size)
        assertTrue(result[0].name.startsWith("Rota Recomendada"))
    }

    @Test
    fun testEmptyListReturnsEmpty() {
        assertTrue(RouteClassifier.classify(emptyList()).isEmpty())
    }

    @Test
    fun testFastestRouteLabeledEvenWithMoreElevation() {
        // Regression guard for the bug this feature fixes: a fixed index-based
        // label used to call route index 1 "flat" even when it climbed more
        // than the fastest route. The classifier must compare real numbers.
        val fast = route("fast", durationSeconds = 600, elevationGainM = 150, energyWh = 40.0, distanceMeters = 5000)
        val slowButFlatter = route("slow", durationSeconds = 900, elevationGainM = 20, energyWh = 25.0, distanceMeters = 5200)

        val result = RouteClassifier.classify(listOf(fast, slowButFlatter))

        val classifiedFast = result.first { it.id == "fast" }
        val classifiedFlat = result.first { it.id == "slow" }

        assertTrue(classifiedFast.name.contains("Mais Rápida"))
        assertTrue(classifiedFlat.name.contains("Mais Plana"))
    }

    @Test
    fun testNearIdenticalElevationDoesNotEarnFlatLabel() {
        // Elevation difference is real but below the "notably flatter"
        // threshold (>=20m AND >=15% less climbing) so it should not
        // mislabel a route as the eco/flat option.
        val fast = route("fast", durationSeconds = 600, elevationGainM = 100, energyWh = 40.0, distanceMeters = 5000)
        val almostSame = route("alt", durationSeconds = 650, elevationGainM = 95, energyWh = 38.0, distanceMeters = 5100)

        val result = RouteClassifier.classify(listOf(fast, almostSame))
        val classifiedAlt = result.first { it.id == "alt" }

        assertFalse(classifiedAlt.name.contains("Mais Plana"))
    }

    @Test
    fun testMostEfficientRouteLabeledWhenNotFastestOrFlattest() {
        val fast = route("fast", durationSeconds = 600, elevationGainM = 100, energyWh = 40.0, distanceMeters = 5000)
        val efficient = route("efficient", durationSeconds = 700, elevationGainM = 98, energyWh = 15.0, distanceMeters = 5000)

        val result = RouteClassifier.classify(listOf(fast, efficient))
        val classifiedEfficient = result.first { it.id == "efficient" }

        assertTrue(classifiedEfficient.name.contains("Mais Econômica"))
    }

    @Test
    fun testRouteNameIncludesStreetWhenAvailable() {
        val fast = route("fast", 600, 50, 20.0, 5000, streetName = "Rua das Flores")
        val result = RouteClassifier.classify(listOf(fast, route("b", 900, 50, 20.0, 5000)))
        assertTrue(result.first { it.id == "fast" }.name.contains("Rua das Flores"))
    }
}

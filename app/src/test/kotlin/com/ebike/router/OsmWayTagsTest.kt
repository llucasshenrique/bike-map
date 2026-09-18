package com.ebike.router

import com.ebike.router.model.OsmWayTags
import org.junit.Assert.*
import org.junit.Test

class OsmWayTagsTest {

    // --- isBikeLaneOrCycleway ---

    @Test
    fun testCyclewayHighwayIsBikeLane() {
        val tags = OsmWayTags(highway = "cycleway")
        assertTrue(tags.isBikeLaneOrCycleway())
    }

    @Test
    fun testPathWithBicycleNoIsNotBikeLane() {
        val tags = OsmWayTags(highway = "path", bicycle = "no")
        assertFalse(tags.isBikeLaneOrCycleway())
    }

    @Test
    fun testPathWithBicycleDismountIsNotBikeLane() {
        val tags = OsmWayTags(highway = "path", bicycle = "dismount")
        assertFalse(tags.isBikeLaneOrCycleway())
    }

    @Test
    fun testCyclewayLaneTagIsBikeLane() {
        val tags = OsmWayTags(highway = "residential", cycleway = "lane")
        assertTrue(tags.isBikeLaneOrCycleway())
    }

    @Test
    fun testBicycleDesignatedIsBikeLane() {
        val tags = OsmWayTags(highway = "residential", bicycle = "designated")
        assertTrue(tags.isBikeLaneOrCycleway())
    }

    @Test
    fun testNamePortugueseHeuristicDetectsBikeLane() {
        val tags = OsmWayTags(highway = "residential", name = "Ciclovia da Praia")
        assertTrue(tags.isBikeLaneOrCycleway())
    }

    @Test
    fun testOrdinaryResidentialStreetIsNotBikeLane() {
        val tags = OsmWayTags(highway = "residential", name = "Rua das Flores")
        assertFalse(tags.isBikeLaneOrCycleway())
    }

    // --- getRollingResistanceMultiplier ---

    @Test
    fun testAsphaltHasBaselineRollingResistance() {
        assertEquals(1.0, OsmWayTags(surface = "asphalt").getRollingResistanceMultiplier(), 0.001)
    }

    @Test
    fun testSurfaceIsCaseInsensitiveAndTrimmed() {
        assertEquals(1.0, OsmWayTags(surface = "  ASPHALT  ").getRollingResistanceMultiplier(), 0.001)
    }

    @Test
    fun testGravelHasHigherRollingResistanceThanCobblestone() {
        val gravel = OsmWayTags(surface = "gravel").getRollingResistanceMultiplier()
        val cobblestone = OsmWayTags(surface = "cobblestone").getRollingResistanceMultiplier()
        assertTrue(gravel > cobblestone)
    }

    @Test
    fun testSandIsWorstSurface() {
        val sand = OsmWayTags(surface = "sand").getRollingResistanceMultiplier()
        assertTrue(sand > OsmWayTags(surface = "gravel").getRollingResistanceMultiplier())
        assertTrue(sand > OsmWayTags(surface = "unpaved").getRollingResistanceMultiplier())
    }

    @Test
    fun testMissingSurfaceWithTrackHighwayFallsBackToModeratePenalty() {
        val tags = OsmWayTags(surface = null, highway = "track")
        assertEquals(1.4, tags.getRollingResistanceMultiplier(), 0.001)
    }

    @Test
    fun testMissingSurfaceAndHighwayDefaultsToBaseline() {
        val tags = OsmWayTags(surface = null, highway = null)
        assertEquals(1.0, tags.getRollingResistanceMultiplier(), 0.001)
    }

    @Test
    fun testBlankSurfaceStringTreatedAsMissing() {
        val tags = OsmWayTags(surface = "", highway = "path")
        assertEquals(1.4, tags.getRollingResistanceMultiplier(), 0.001)
    }

    @Test
    fun testUnknownSurfaceValueFallsBackToMildPenalty() {
        val tags = OsmWayTags(surface = "wood")
        assertEquals(1.15, tags.getRollingResistanceMultiplier(), 0.001)
    }

    // --- isPaved ---

    @Test
    fun testAsphaltIsPaved() {
        assertTrue(OsmWayTags(surface = "asphalt").isPaved())
    }

    @Test
    fun testDirtIsNotPaved() {
        assertFalse(OsmWayTags(surface = "dirt").isPaved())
    }

    @Test
    fun testMissingSurfaceOnTrackIsNotPaved() {
        assertFalse(OsmWayTags(surface = null, highway = "track").isPaved())
    }

    @Test
    fun testMissingSurfaceOnBridlewayIsNotPaved() {
        assertFalse(OsmWayTags(surface = null, highway = "bridleway").isPaved())
    }

    @Test
    fun testMissingSurfaceOnResidentialDefaultsPaved() {
        assertTrue(OsmWayTags(surface = null, highway = "residential").isPaved())
    }

    // --- getSafetyScore ---

    @Test
    fun testDedicatedCyclewayHasMaxSafety() {
        assertEquals(1.0, OsmWayTags(highway = "cycleway").getSafetyScore(), 0.001)
    }

    @Test
    fun testMotorwayHasMinimalSafety() {
        assertEquals(0.05, OsmWayTags(highway = "motorway").getSafetyScore(), 0.001)
    }

    @Test
    fun testSafetyScoreDecreasesAsRoadClassIncreases() {
        val residential = OsmWayTags(highway = "residential").getSafetyScore()
        val secondary = OsmWayTags(highway = "secondary").getSafetyScore()
        val motorway = OsmWayTags(highway = "motorway").getSafetyScore()
        assertTrue(residential > secondary)
        assertTrue(secondary > motorway)
    }

    @Test
    fun testRoughSurfacePenaltyReducesSafetyScore() {
        val clean = OsmWayTags(highway = "residential", surface = "asphalt").getSafetyScore()
        val sandy = OsmWayTags(highway = "residential", surface = "sand").getSafetyScore()
        assertTrue(sandy < clean)
    }

    @Test
    fun testSafetyScoreIsClampedToMinimum() {
        val tags = OsmWayTags(highway = "motorway", surface = "sand")
        assertTrue(tags.getSafetyScore() >= 0.05)
    }

    @Test
    fun testSafetyScoreIsClampedToMaximum() {
        val tags = OsmWayTags(highway = "cycleway", surface = "asphalt")
        assertTrue(tags.getSafetyScore() <= 1.0)
    }

    @Test
    fun testUnknownHighwayFallsBackToNameHeuristic() {
        val ciclovia = OsmWayTags(highway = null, name = "Ciclovia Beira Mar").getSafetyScore()
        val rodovia = OsmWayTags(highway = null, name = "Rodovia BR-101").getSafetyScore()
        assertTrue(ciclovia > rodovia)
    }

    @Test
    fun testUnknownHighwayAndNameDefaultsToModerateSafety() {
        assertEquals(0.65, OsmWayTags(highway = null, name = null).getSafetyScore(), 0.001)
    }
}
